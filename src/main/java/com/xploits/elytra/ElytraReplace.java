package com.xploits.elytra;

import com.xploits.XploitsAddon;
import com.xploits.elytra.core.Decision;
import com.xploits.elytra.core.ElytraCandidate;
import com.xploits.elytra.core.ElytraPolicy;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvents;

import java.util.ArrayList;
import java.util.List;

/**
 * Cambia la elytra puesta por una de repuesto antes de que se rompa (spec §4), y avisa cuando no
 * hay ninguna válida (spec §5). Funciona vueles como vueles: no depende de ElytraFly.
 */
public class ElytraReplace extends Module {
    private static final int FIRST_SLOT = 0;
    private static final int LAST_SLOT = 35;
    private static final int CHEST_ARMOR_INDEX = 2;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> swapBelow = sgGeneral.add(new IntSetting.Builder()
        .name("swap-below")
        .description("Cambia la elytra puesta cuando baje de este porcentaje.")
        .defaultValue(10)
        .range(1, 99)
        .sliderRange(1, 99)
        .build()
    );

    private final Setting<Integer> minSpare = sgGeneral.add(new IntSetting.Builder()
        .name("min-spare")
        .description("Solo se pone una elytra de repuesto si tiene al menos este porcentaje.")
        .defaultValue(50)
        .range(1, 100)
        .sliderRange(1, 100)
        .build()
    );

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("notify")
        .description("Aviso local al cambiar de elytra.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifySound = sgGeneral.add(new BoolSetting.Builder()
        .name("notify-sound")
        .description("Sonido en el aviso de que no hay repuesto.")
        .defaultValue(true)
        .build()
    );

    private final ElytraPolicy policy = new ElytraPolicy();

    public ElytraReplace() {
        super(XploitsAddon.CATEGORY, "elytra-replace", "Cambia la elytra antes de que se rompa y avisa si no hay repuesto.");
    }

    @Override
    public void onActivate() {
        policy.reset();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || !mc.player.isAlive()) return;
        // Mover la pechera con un cofre abierto es pedir que el clic acabe donde no debe (spec §6).
        if (mc.currentScreen != null && !(mc.currentScreen instanceof InventoryScreen)) return;

        ItemStack worn = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        Integer wornPercent = worn.getItem() == Items.ELYTRA
            ? ElytraPolicy.percentOf(worn.getDamage(), worn.getMaxDamage())
            : null;

        ElytraPolicy.Result result = policy.decide(wornPercent, spares(), swapBelow.get(), minSpare.get(),
            System.currentTimeMillis());

        switch (result.decision()) {
            case SWAP -> swap(result);
            case NO_SPARE -> warnNoSpare(result.wornPercent());
            case OK, NOT_WEARING -> { }
        }
    }

    /** Elytras sueltas del inventario, sin contar la puesta ni las que van dentro de shulkers (spec §6). */
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
        InvUtils.move().from(result.slot()).toArmor(CHEST_ARMOR_INDEX);
        // Aquí NO se llama a policy.reset(): borraría el lastSwapAt que decide() acaba de poner y
        // el módulo repetiría el movimiento en cada tick. El aviso se rearma solo, dentro de decide().
        if (notify.get()) {
            info("Elytra cambiada: la puesta estaba al %d %%.", result.wornPercent());
        }
    }

    private void warnNoSpare(int wornPercent) {
        if (!policy.shouldWarnNoSpare(wornPercent)) return;

        String message = String.format("Elytra al %d %% y ningún repuesto por encima del %d %%.",
            wornPercent, minSpare.get());
        warning("%s", message);

        MeteorToast.Builder toast = new MeteorToast.Builder("Xploits").text(message).icon(Items.ELYTRA);
        // MeteorToast.update() llama a play(customSound) sin comprobar el nulo y vanilla lo dereferencia:
        // NPE en el hilo de render. Nunca pasar null; se silencia con volumen cero, igual que KitRequester.
        if (!notifySound.get()) {
            toast.sound(PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), 1.2f, 0f));
        }
        mc.getToastManager().add(toast.build());
    }
}
