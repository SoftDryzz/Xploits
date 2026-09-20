package com.xploits.kitrequester;

import com.xploits.XploitsAddon;
import com.xploits.kitrequester.core.Action;
import com.xploits.kitrequester.core.KitQueue;
import com.xploits.kitrequester.core.OrderMachine;
import com.xploits.kitrequester.core.Progress;
import com.xploits.kitrequester.core.ProgressStore;
import com.xploits.kitrequester.inventory.EnderDepositor;
import com.xploits.shared.chat.ChatPatterns;
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
 * Adaptador: traduce eventos de Meteor a OrderMachine y ejecuta sus acciones. No decide nada (spec §3).
 * No hace falta un handler propio de reconexión: Meteor ya llama a {@link #onDeactivate()} al salir del
 * servidor (aquí se guarda el progreso) y a {@link #onActivate()} al volver (aquí se recargan
 * kits-queue.txt y progress.json y se llama a {@code machine.onJoin}).
 */
public class KitRequester extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> intervalSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("interval-seconds")
        .description("Segundos entre pedidos, contados desde que SnifferBuddy acepta el anterior. Mínimo 300.")
        .defaultValue(300)
        .min(300)
        .sliderRange(300, 1800)
        .build()
    );

    private final Setting<List<String>> knownCouriers = sgGeneral.add(new StringListSetting.Builder()
        .name("known-couriers")
        .description("Couriers de SnifferBuddy cuya TPA se acepta. Nombres exactos; distinguen mayúsculas.")
        .defaultValue("StormAegis44", "ValorKnight27", "IronSentri08")
        .build()
    );

    private final Setting<Boolean> trustUnknownCouriers = sgGeneral.add(new BoolSetting.Builder()
        .name("trust-unknown-couriers")
        .description("Aceptar un courier nuevo si envía READY y TPA durante la espera del pedido. "
            + "Riesgo: cualquiera que imite el mensaje puede teletransportarse a ti y quedar en la lista.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> autoEnder = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-ender")
        .description("Con el inventario lleno, vaciar shulkers en un ender chest al alcance (no camina).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> notifySound = sgGeneral.add(new BoolSetting.Builder()
        .name("notify-sound")
        .description("Sonido en los avisos importantes: pausa, error y cola terminada.")
        .defaultValue(true)
        .build()
    );

    private final Path folder = MeteorClient.FOLDER.toPath().resolve("xploits");
    private final ProgressStore store = new ProgressStore(folder.resolve("progress.json"));
    private final EnderDepositor depositor = new EnderDepositor();

    private Progress progress;
    private OrderMachine machine;

    public KitRequester() {
        super(XploitsAddon.CATEGORY, "kit-requester", "Pide kits a SnifferBuddy en lotes de 5 y acepta la TPA del courier.");
    }

    @Override
    public void onActivate() {
        try {
            KitQueue queue = loadQueue();
            progress = store.load();
            machine = newMachine(queue);
        } catch (IOException e) {
            error("%s", e.getMessage());
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
     * Alimenta a {@link EnderDepositor} con cada interacción de bloque, sea propia (el interact que
     * {@code start()} manda al ender chest) o del jugador (abrir otro contenedor a mano mientras se
     * espera respuesta del servidor). Es la única señal para atar la operación a un contenedor
     * concreto (spec §6); sin ella, EnderDepositor no puede distinguir su ender chest de cualquier
     * otro que se abra en la misma ventana de tiempo.
     */
    @EventHandler
    private void onInteractBlock(InteractBlockEvent event) {
        depositor.onInteractBlock(mc, event.result.getBlockPos(), System.currentTimeMillis());
    }

    /**
     * Los cofres de vagoneta y de barca son entidades: {@code InteractBlockEvent} nunca se dispara
     * para ellos, así que sin este gancho un clic ahí pasaba desapercibido para
     * {@link EnderDepositor#start} (spec §6.1, tercera corrección).
     */
    @EventHandler
    private void onInteractEntity(InteractEntityEvent event) {
        depositor.onInteractEntity(System.currentTimeMillis());
    }

    /** Prioridad máxima para ver el mensaje antes de que BetterChat u otros lo modifiquen (spec §2.3). */
    @EventHandler(priority = EventPriority.HIGHEST)
    private void onMessage(ReceiveMessageEvent event) {
        if (machine == null) return;
        ChatPatterns.classify(event.getMessage().getString())
            .ifPresent(chatEvent -> run(machine.onChat(chatEvent, System.currentTimeMillis())));
    }

    public String status() {
        return machine == null ? "KitRequester está desactivado." : machine.status(System.currentTimeMillis());
    }

    /** Couriers configurados; AutoTPY los deja en manos de este módulo mientras esté activo. */
    public Set<String> knownCouriers() {
        return Set.copyOf(knownCouriers.get());
    }

    public String reload() {
        if (machine == null) return "KitRequester está desactivado.";
        OrderMachine.State state = machine.state();
        if (state != OrderMachine.State.IDLE && state != OrderMachine.State.PAUSED) {
            return "Solo se puede recargar en IDLE o PAUSED (ahora: " + state + ").";
        }
        try {
            machine = newMachine(loadQueue());
        } catch (IOException e) {
            return e.getMessage();
        }
        return "Cola recargada. " + machine.status(System.currentTimeMillis());
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
            throw new IOException("No existe " + file + ". Pega ahí la salida de 'Copiar pendientes' del HTML.");
        }
        KitQueue queue = KitQueue.parse(Files.readString(file));
        if (queue.ids().isEmpty()) throw new IOException(file + " no contiene ningún ID de kit.");
        return queue;
    }

    private OrderMachine.Context context() {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) {
            return new OrderMachine.Context(false, false, 0, false, false);
        }
        boolean kitbotOnline = mc.getNetworkHandler().getPlayerListEntry(ChatPatterns.KITBOT) != null;
        int free = freeSlots();
        // Solo se busca el ender chest cuando hace falta: son ~1300 bloques por consulta.
        boolean enderInReach = autoEnder.get() && free < KitQueue.MAX_BATCH && EnderDepositor.findInReach(mc).isPresent();
        // Si ya hay una pantalla abierta a mano, pedir el depósito ahora es justo lo que vacía
        // shulkers en el contenedor equivocado (spec §6): OrderMachine no debe ni intentarlo.
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
                    else warning("No se pudo enviar %s: no hay jugador en el mundo.", command.command());
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
            info("%s", notify.message());
            return;
        }
        warning("%s", notify.message());
        MeteorToast.Builder toast = new MeteorToast.Builder("Xploits").text(notify.message()).icon(Items.SHULKER_BOX);
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
            error("No se pudo guardar el progreso: %s", e.getMessage());
        }
    }
}
