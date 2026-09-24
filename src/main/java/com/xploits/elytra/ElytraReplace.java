package com.xploits.elytra;

import com.xploits.XploitsAddon;
import com.xploits.elytra.core.ElytraCandidate;
import com.xploits.elytra.core.ElytraPolicy;
import com.xploits.elytra.core.ElytraText;
import com.xploits.shared.Texts;
import com.xploits.shared.core.i18n.Msg;
import com.xploits.shared.XploitsModule;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.sound.SoundEvents;

import java.util.ArrayList;
import java.util.List;

/**
 * Swaps the worn elytra for a spare before it breaks (spec §4), and warns when there is no valid
 * one (spec §5). It works however you fly: it does not depend on ElytraFly.
 */
public class ElytraReplace extends XploitsModule {
    private static final int FIRST_SLOT = 0;
    private static final int LAST_SLOT = 35;
    private static final int CHEST_ARMOR_INDEX = 2;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> swapBelow = sgGeneral.add(new IntSetting.Builder()
        .name("swap-below")
        .description(Texts.startupText(ElytraText.SETTING_SWAP_BELOW))
        .defaultValue(10)
        .range(1, 99)
        .sliderRange(1, 99)
        .build()
    );

    private final Setting<Integer> minSpare = sgGeneral.add(new IntSetting.Builder()
        .name("min-spare")
        .description(Texts.startupText(ElytraText.SETTING_MIN_SPARE))
        .defaultValue(50)
        .range(1, 100)
        .sliderRange(1, 100)
        .build()
    );

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("notify")
        .description(Texts.startupText(ElytraText.SETTING_NOTIFY))
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifySound = sgGeneral.add(new BoolSetting.Builder()
        .name("notify-sound")
        .description(Texts.startupText(ElytraText.SETTING_NOTIFY_SOUND))
        .defaultValue(true)
        .build()
    );

    private final ElytraPolicy policy = new ElytraPolicy();

    /**
     * The percentage the worn elytra had when a swap not yet confirmed was sent, or {@code null}
     * if none is pending. InvUtils.move() does not confirm that the server accepted the click, so
     * success is not announced when the move is sent: it is announced when the worn one's
     * percentage is seen to have risen above this value.
     */
    private Integer pendingSwapWornPercent;

    /** Whether the pending swap was already reported as not taking, so as not to repeat the warning every 2 s. */
    private boolean pendingSwapStuckWarned;

    public ElytraReplace() {
        super(XploitsAddon.CATEGORY, "elytra-replace", Texts.startupText(ElytraText.MODULE_DESC));
    }

    @Override
    public void onActivate() {
        policy.reset();
        pendingSwapWornPercent = null;
        pendingSwapStuckWarned = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || !mc.player.isAlive()) return;

        ItemStack worn = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        Integer wornPercent = worn.getItem() == Items.ELYTRA
            ? ElytraPolicy.percentOf(worn.getDamage(), worn.getMaxDamage())
            : null;

        // The pending swap's confirmation is always checked, even if this tick is not going to
        // act: it is the only reliable sign that the click took, and it must not wait for a chest
        // or the chat to close.
        confirmPendingSwap(wornPercent);

        // Only act with the player's own screen (own inventory, or none open): the real reason is
        // that the click must not be lost, and that depends on the handler, not the screen. A
        // chest, the ClickGUI or the pause menu use another handler and are left out; the chat
        // opens no handler of its own, so it does not block (spec §6).
        if (!(mc.player.currentScreenHandler instanceof PlayerScreenHandler)) return;
        // With an item held on the cursor, InvUtils.move() cannot put the old elytra back in its
        // slot: it stays on the cursor and vanilla drops it on the ground when the inventory closes.
        if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) return;

        long now = System.currentTimeMillis();
        List<ElytraCandidate> candidates = spares();
        ElytraPolicy.Result result = policy.decide(wornPercent, candidates, swapBelow.get(), minSpare.get(), now);

        switch (result.decision()) {
            case SWAP -> swap(result);
            case NO_SPARE -> warnNoSpare(result.wornPercent(), candidates, now);
            case OK, NOT_WEARING -> { }
        }
    }

    /**
     * If a swap is waiting for confirmation and the worn one's percentage has risen above what it
     * had when the move was sent, that is the only reliable sign that the swap took: the success,
     * held back until now, is announced and the swap forgotten.
     */
    private void confirmPendingSwap(Integer wornPercent) {
        if (pendingSwapWornPercent == null) return;
        if (wornPercent == null || wornPercent <= pendingSwapWornPercent) return;

        if (notify.get()) {
            info(ElytraText.SWAPPED, "percent", pendingSwapWornPercent);
        }
        pendingSwapWornPercent = null;
        pendingSwapStuckWarned = false;
    }

    /** Loose elytras in the inventory, not counting the worn one or those inside shulkers (spec §6). */
    private List<ElytraCandidate> spares() {
        List<ElytraCandidate> candidates = new ArrayList<>();
        for (int slot = FIRST_SLOT; slot <= LAST_SLOT; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.getItem() != Items.ELYTRA) continue;
            candidates.add(new ElytraCandidate(slot, ElytraPolicy.percentOf(stack.getDamage(), stack.getMaxDamage())));
        }
        return candidates;
    }

    private void swap(ElytraPolicy.Result result) {
        // If a swap was already pending without confirmation, this is a second attempt: warn only
        // once that it is not taking, before sending it again.
        if (pendingSwapWornPercent != null && !pendingSwapStuckWarned) {
            pendingSwapStuckWarned = true;
            warning(ElytraText.SWAP_NOT_TAKING, "percent", result.wornPercent());
        }

        InvUtils.move().from(result.slot()).toArmor(CHEST_ARMOR_INDEX);
        pendingSwapWornPercent = result.wornPercent();
        // policy.reset() is NOT called here: it would erase the lastSwapAt that decide() has just
        // set and the module would repeat the move on every tick. The "no spare" warning rearms on
        // its own, inside decide(). The success notice is announced in confirmPendingSwap(), not
        // here: this method only knows that the move was sent, not that the server accepted it.
    }

    private void warnNoSpare(int wornPercent, List<ElytraCandidate> candidates, long now) {
        if (!policy.shouldWarnNoSpare(wornPercent, now)) return;

        Msg message = noSpareMessage(wornPercent, candidates);
        warning(message);

        MeteorToast.Builder toast = new MeteorToast.Builder("Xploits").text(Texts.render(message)).icon(Items.ELYTRA);
        // MeteorToast.update() calls play(customSound) without a null check and vanilla dereferences it:
        // an NPE on the render thread. Never pass null; it is silenced with zero volume, as KitRequester does.
        if (!notifySound.get()) {
            toast.sound(PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), 1.2f, 0f));
        }
        mc.getToastManager().add(toast.build());
    }

    /**
     * NO_SPARE has two different causes and the message must name the right one: that no
     * candidate reaches the minimum, or that some do but none is strictly better than the worn
     * one (spec §4.3).
     */
    private Msg noSpareMessage(int wornPercent, List<ElytraCandidate> candidates) {
        boolean anyReachesMinimum = candidates.stream().anyMatch(candidate -> candidate.percent() >= minSpare.get());
        if (!anyReachesMinimum) {
            return Msg.of(ElytraText.NO_SPARE_ABOVE_MINIMUM, "worn", wornPercent, "minimum", minSpare.get());
        }
        return Msg.of(ElytraText.NO_SPARE_BETTER, "worn", wornPercent, "minimum", minSpare.get());
    }
}
