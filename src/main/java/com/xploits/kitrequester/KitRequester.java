package com.xploits.kitrequester;

import com.xploits.XploitsAddon;
import com.xploits.kitrequester.core.Action;
import com.xploits.kitrequester.core.KitQueue;
import com.xploits.kitrequester.core.KitText;
import com.xploits.kitrequester.core.OrderMachine;
import com.xploits.kitrequester.core.Progress;
import com.xploits.kitrequester.core.ProgressStore;
import com.xploits.kitrequester.inventory.EnderDepositor;
import com.xploits.shared.Texts;
import com.xploits.shared.XploitsModule;
import com.xploits.shared.chat.ChatPatterns;
import com.xploits.shared.core.PositionedMsg;
import com.xploits.shared.core.i18n.Msg;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.InteractBlockEvent;
import meteordevelopment.meteorclient.events.entity.player.InteractEntityEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.item.Items;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.sound.SoundEvents;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Adapter: translates Meteor events into OrderMachine and runs its actions. It decides nothing
 * (spec §3). No dedicated reconnect handler is needed: Meteor already calls {@link #onDeactivate()}
 * on leaving the server (progress is saved here) and {@link #onActivate()} on rejoining (this is
 * where kits-queue.txt and progress.json are reloaded and {@code machine.onJoin} is called).
 */
public class KitRequester extends XploitsModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> intervalSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("interval-seconds")
        .description(Texts.startupText(KitText.SETTING_INTERVAL_SECONDS))
        .defaultValue(300)
        .min(300)
        .sliderRange(300, 1800)
        .build()
    );

    private final Setting<List<String>> knownCouriers = sgGeneral.add(new StringListSetting.Builder()
        .name("known-couriers")
        .description(Texts.startupText(KitText.SETTING_KNOWN_COURIERS))
        .defaultValue("StormAegis44", "ValorKnight27", "IronSentri08")
        .build()
    );

    private final Setting<Boolean> trustUnknownCouriers = sgGeneral.add(new BoolSetting.Builder()
        .name("trust-unknown-couriers")
        .description(Texts.startupText(KitText.SETTING_TRUST_UNKNOWN_COURIERS))
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> autoEnder = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-ender")
        .description(Texts.startupText(KitText.SETTING_AUTO_ENDER))
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> notifySound = sgGeneral.add(new BoolSetting.Builder()
        .name("notify-sound")
        .description(Texts.startupText(KitText.SETTING_NOTIFY_SOUND))
        .defaultValue(true)
        .build()
    );

    private final Path folder = MeteorClient.FOLDER.toPath().resolve("xploits");
    private final ProgressStore store = new ProgressStore(folder.resolve("progress.json"));
    private final EnderDepositor depositor = new EnderDepositor();

    private Progress progress;
    private OrderMachine machine;

    public KitRequester() {
        super(XploitsAddon.CATEGORY, "kit-requester", Texts.startupText(KitText.MODULE_DESC));
    }

    @Override
    public void onActivate() {
        try {
            KitQueue queue = loadQueue();
            progress = store.load();
            machine = newMachine(queue);
        } catch (IOException e) {
            errorPrivate(new PositionedMsg(Msg.of(KitText.LOAD_FAILED, "detail", String.valueOf(e.getMessage())),
                Msg.of(KitText.LOAD_FAILED_LOG)));
            progress = null;
            machine = null;
            toggle();
            return;
        }
        run(machine.onJoin(System.currentTimeMillis()));
    }

    @Override
    public void onDeactivate() {
        depositor.reset();
        if (progress != null) save();
        machine = null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (machine == null) return;
        long now = System.currentTimeMillis();
        if (machine.state() == OrderMachine.State.DEPOSIT) {
            if (mc.player != null) {
                depositor.tick(mc, now).ifPresent(ok -> run(machine.onDepositResult(ok, freeSlots())));
            }
            return;
        }
        run(machine.tick(now, context()));
    }

    /**
     * Feeds {@link EnderDepositor} with every block interaction, whether its own (the interact that
     * {@code start()} sends to the ender chest) or the player's (opening another container by hand
     * while waiting on the server's response). It is the only signal to tie the operation to a
     * specific container (spec §6); without it, EnderDepositor cannot tell its ender chest apart from
     * any other one opened in the same time window.
     */
    @EventHandler
    private void onInteractBlock(InteractBlockEvent event) {
        depositor.onInteractBlock(mc, event.result.getBlockPos(), System.currentTimeMillis());
    }

    /**
     * Minecart chests and boat chests are entities: {@code InteractBlockEvent} never fires for them,
     * so without this hook a click there went unnoticed by {@link EnderDepositor#start} (spec §6.1,
     * third fix).
     */
    @EventHandler
    private void onInteractEntity(InteractEntityEvent event) {
        depositor.onInteractEntity(System.currentTimeMillis());
    }

    /** Highest priority to see the message before BetterChat or others modify it (spec §2.3). */
    @EventHandler(priority = EventPriority.HIGHEST)
    private void onMessage(ReceiveMessageEvent event) {
        if (machine == null) return;
        ChatPatterns.classify(event.getMessage().getString())
            .ifPresent(chatEvent -> run(machine.onChat(chatEvent, System.currentTimeMillis())));
    }

    public Msg status() {
        return machine == null ? Msg.of(KitText.DISABLED) : machine.status(System.currentTimeMillis());
    }

    @Override
    public String activity() {
        return machine == null ? "" : Texts.render(KitText.of(machine.state()));
    }

    /** Configured couriers; AutoTPY leaves them to this module while it is active. */
    public Set<String> knownCouriers() {
        return Set.copyOf(knownCouriers.get());
    }

    /**
     * Whether an unknown player can currently get into {@link #knownCouriers()} on their own with a
     * READY and a TPA. auto-pvp asks this: with it on, the list is no longer trustworthy and none of
     * it is written to Meteor's friends list (spec §14.2 of auto-pvp).
     */
    public boolean trustsUnknownCouriers() {
        return trustUnknownCouriers.get();
    }

    public Msg reload() {
        if (machine == null) return Msg.of(KitText.DISABLED);
        OrderMachine.State state = machine.state();
        if (state != OrderMachine.State.IDLE && state != OrderMachine.State.PAUSED) {
            return Msg.of(KitText.RELOAD_WRONG_STATE, "state", KitText.of(state));
        }
        try {
            machine = newMachine(loadQueue());
        } catch (IOException e) {
            return Msg.of(KitText.RELOAD_FAILED, "detail", String.valueOf(e.getMessage()));
        }
        return Msg.of(KitText.RELOAD_DONE, "status", machine.status(System.currentTimeMillis()));
    }

    private OrderMachine newMachine(KitQueue queue) {
        return new OrderMachine(queue, progress, this::config, () -> ThreadLocalRandom.current().nextLong(20_001));
    }

    private OrderMachine.Config config() {
        return new OrderMachine.Config(intervalSeconds.get() * 1000L, Set.copyOf(knownCouriers.get()),
            trustUnknownCouriers.get(), autoEnder.get());
    }

    private KitQueue loadQueue() throws IOException {
        Path file = folder.resolve("kits-queue.txt");
        if (!Files.exists(file)) {
            throw new IOException(file + " does not exist. Paste the HTML's 'Copy pending' output there.");
        }
        KitQueue queue = KitQueue.parse(Files.readString(file));
        if (queue.ids().isEmpty()) throw new IOException(file + " does not contain any kit ID.");
        return queue;
    }

    private OrderMachine.Context context() {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) {
            return new OrderMachine.Context(false, false, 0, false, false);
        }
        boolean kitbotOnline = mc.getNetworkHandler().getPlayerListEntry(ChatPatterns.KITBOT) != null;
        int free = freeSlots();
        // The ender chest is only searched for when needed: it is ~1300 blocks per query.
        boolean enderInReach = autoEnder.get() && free < KitQueue.MAX_BATCH && EnderDepositor.findInReach(mc).isPresent();
        // If a screen is already open by hand, requesting the deposit now is exactly what empties
        // shulkers into the wrong container (spec §6): OrderMachine must not even try.
        boolean screenOpen = !(mc.player.currentScreenHandler instanceof PlayerScreenHandler);
        return new OrderMachine.Context(true, kitbotOnline, free, enderInReach, screenOpen);
    }

    private int freeSlots() {
        if (mc.player == null) return 0;
        int free = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) free++;
        }
        return free;
    }

    private void run(List<Action> actions) {
        for (Action action : actions) {
            switch (action) {
                case Action.SendCommand command -> {
                    if (mc.player != null) ChatUtils.sendPlayerMsg(command.command(), false);
                    else warning(KitText.SEND_FAILED_NO_PLAYER, "command", command.command());
                }
                case Action.Deposit deposit -> {
                    if (!depositor.start(mc, System.currentTimeMillis()) && machine != null) {
                        run(machine.onDepositResult(false, freeSlots()));
                    }
                }
                case Action.Notify notify -> notifyUser(notify);
                case Action.LearnCourier learn -> {
                    List<String> couriers = new ArrayList<>(knownCouriers.get());
                    if (!couriers.contains(learn.name())) {
                        couriers.add(learn.name());
                        knownCouriers.set(couriers);
                    }
                }
                case Action.Save save -> save();
                case Action.Disable disable -> {
                    if (isActive()) toggle();
                }
            }
        }
    }

    private void notifyUser(Action.Notify notify) {
        if (!notify.alert()) {
            info(notify.message());
            return;
        }
        warning(notify.message());
        MeteorToast.Builder toast = new MeteorToast.Builder("Xploits").text(Texts.render(notify.message())).icon(Items.SHULKER_BOX);
        // Meteor build 86's MeteorToast.update() calls mc.getSoundManager().play(customSound) without a null
        // check, and vanilla dereferences it -> NPE on the render thread. Never pass null: mute with a
        // zero-volume instance built the same way Meteor builds its default toast sound (same pitch, volume 0).
        if (!notifySound.get()) {
            toast.sound(PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), 1.2f, 0f));
        }
        mc.getToastManager().add(toast.build());
    }

    private void save() {
        try {
            store.save(progress);
        } catch (IOException e) {
            errorPrivate(new PositionedMsg(Msg.of(KitText.SAVE_FAILED, "detail", String.valueOf(e.getMessage())),
                Msg.of(KitText.SAVE_FAILED_LOG)));
        }
    }
}
