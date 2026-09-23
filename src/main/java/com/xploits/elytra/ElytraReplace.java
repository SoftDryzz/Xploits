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
 * Cambia la elytra puesta por una de repuesto antes de que se rompa (spec §4), y avisa cuando no
 * hay ninguna válida (spec §5). Funciona vueles como vueles: no depende de ElytraFly.
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
     * Porcentaje que tenía la elytra puesta cuando se mandó un cambio todavía sin confirmar, o
     * {@code null} si no hay ninguno pendiente. InvUtils.move() no confirma que el servidor haya
     * aceptado el clic, así que el éxito no se anuncia al mandar el movimiento: se anuncia cuando
     * se observa que el porcentaje de la puesta ha subido por encima de este valor.
     */
    private Integer pendingSwapWornPercent;

    /** Si ya se avisó de que el cambio pendiente no está prendiendo, para no repetir el aviso cada 2 s. */
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

        // La confirmación del cambio pendiente se comprueba siempre, aunque este tick no vaya a
        // actuar: es la única señal fiable de que el clic prendió y no debe esperar a que se
        // cierre un cofre o el chat.
        confirmPendingSwap(wornPercent);

        // Solo actuar con la pantalla del jugador (inventario propio, o ninguna abierta): el
        // motivo real es que el clic no se pierda, y eso depende del handler, no de la pantalla.
        // Un cofre, la ClickGUI o el menú de pausa usan otro handler y quedan fuera; el chat no
        // abre ningún handler propio, así que no bloquea (spec §6).
        if (!(mc.player.currentScreenHandler instanceof PlayerScreenHandler)) return;
        // Con un ítem cogido en el cursor, InvUtils.move() no puede devolver la elytra vieja a su
        // slot: se queda en el cursor y vanilla la tira al suelo al cerrar el inventario.
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
     * Si hay un cambio pendiente de confirmar y el porcentaje de la puesta ha subido por encima
     * del que tenía cuando se mandó el movimiento, es la única señal fiable de que el cambio
     * prendió: se anuncia el éxito, que hasta ahora quedaba pendiente, y se olvida.
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
        // Si ya había un cambio pendiente sin confirmar, este es un segundo intento: avisa una
        // sola vez de que no está prendiendo, antes de mandarlo otra vez.
        if (pendingSwapWornPercent != null && !pendingSwapStuckWarned) {
            pendingSwapStuckWarned = true;
            warning(ElytraText.SWAP_NOT_TAKING, "percent", result.wornPercent());
        }

        InvUtils.move().from(result.slot()).toArmor(CHEST_ARMOR_INDEX);
        pendingSwapWornPercent = result.wornPercent();
        // Aquí NO se llama a policy.reset(): borraría el lastSwapAt que decide() acaba de poner y
        // el módulo repetiría el movimiento en cada tick. El aviso de "sin repuesto" se rearma
        // solo, dentro de decide(). El aviso de éxito se anuncia en confirmPendingSwap(), no aquí:
        // este método solo sabe que el movimiento se mandó, no que el servidor lo haya aceptado.
    }

    private void warnNoSpare(int wornPercent, List<ElytraCandidate> candidates, long now) {
        if (!policy.shouldWarnNoSpare(wornPercent, now)) return;

        Msg message = noSpareMessage(wornPercent, candidates);
        warning(message);

        MeteorToast.Builder toast = new MeteorToast.Builder("Xploits").text(Texts.render(message)).icon(Items.ELYTRA);
        // MeteorToast.update() llama a play(customSound) sin comprobar el nulo y vanilla lo dereferencia:
        // NPE en el hilo de render. Nunca pasar null; se silencia con volumen cero, igual que KitRequester.
        if (!notifySound.get()) {
            toast.sound(PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), 1.2f, 0f));
        }
        mc.getToastManager().add(toast.build());
    }

    /**
     * NO_SPARE tiene dos causas distintas y el mensaje debe decir la que corresponde: que ningún
     * candidato llegue al mínimo, o que alguno lo alcance pero ninguno esté estrictamente mejor
     * que la puesta (spec §4.3).
     */
    private Msg noSpareMessage(int wornPercent, List<ElytraCandidate> candidates) {
        boolean anyReachesMinimum = candidates.stream().anyMatch(candidate -> candidate.percent() >= minSpare.get());
        if (!anyReachesMinimum) {
            return Msg.of(ElytraText.NO_SPARE_ABOVE_MINIMUM, "worn", wornPercent, "minimum", minSpare.get());
        }
        return Msg.of(ElytraText.NO_SPARE_BETTER, "worn", wornPercent, "minimum", minSpare.get());
    }
}
