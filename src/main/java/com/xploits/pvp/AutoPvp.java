package com.xploits.pvp;

import com.xploits.XploitsAddon;
import com.xploits.pvp.core.CombatDirector;
import com.xploits.pvp.core.CombatSnapshot;
import com.xploits.pvp.core.CombatState;
import com.xploits.pvp.core.ManagedModule;
import com.xploits.pvp.core.ManagedModules;
import com.xploits.pvp.core.ModuleLedger;
import com.xploits.pvp.core.Plan;
import com.xploits.pvp.core.Resource;
import com.xploits.pvp.core.Skipped;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvents;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Dirige los módulos de combate de Meteor según la fase de la pelea (spec §1). No ejecuta ninguna
 * acción de combate: solo enciende y apaga, y solo apaga lo que encendió él (spec §7).
 */
public class AutoPvp extends Module {
    private static final int FIRST_SLOT = 0;
    /** Último slot de la hotbar (spec §6): lo que ven {@code InvUtils.findInHotbar}/{@code testInHotbar}. */
    private static final int HOTBAR_LAST_SLOT = 8;
    /** Último slot del inventario completo (spec §6): lo que ve {@code InvUtils.find}. */
    private static final int INVENTORY_LAST_SLOT = 35;

    /** Módulos de combate que auto-pvp nunca toca, los lleves encendidos o no (spec §7). */
    private static final List<String> ALWAYS_YOURS = List.of("auto-totem", "auto-armor", "offhand", "auto-weapon");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> targetRange = sgGeneral.add(new IntSetting.Builder()
        .name("target-range")
        .description("A cuántos bloques se busca un objetivo.")
        .defaultValue(16)
        .range(4, 64)
        .sliderRange(4, 64)
        .build()
    );

    private final Setting<Integer> approachDistance = sgGeneral.add(new IntSetting.Builder()
        .name("approach-distance")
        .description("Más lejos de esta distancia la fase es de acercamiento; más cerca, de superficie. "
            + "Tope en 6: EntityUtils.getCityBlock() de Meteor no ve rodeado más allá de esa distancia (spec §4.2), "
            + "y un approach-distance mayor dejaría una franja donde nunca se detecta RODEADO.")
        .defaultValue(6)
        .range(2, 6)
        .sliderRange(2, 6)
        .build()
    );

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("notify")
        .description("Aviso local al cambiar de fase. SIN_RECURSOS avisa siempre, lo apagues o no (spec §4.1).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifySound = sgGeneral.add(new BoolSetting.Builder()
        .name("notify-sound")
        .description("Sonido en el aviso fuerte de SIN_RECURSOS.")
        .defaultValue(true)
        .build()
    );

    private final CombatDirector director = new CombatDirector();
    private final ModuleLedger ledger = new ModuleLedger();

    private Plan lastPlan;
    private String lastTargetName;
    private Double lastTargetDistance;
    private CombatState lastReported = CombatState.SIN_COMBATE;

    public AutoPvp() {
        super(XploitsAddon.CATEGORY, "auto-pvp", "Dirige los módulos de combate según la fase de la pelea.");
    }

    @Override
    public void onActivate() {
        director.reset();
        ledger.reset();
        lastPlan = null;
        lastTargetName = null;
        lastTargetDistance = null;
        lastReported = CombatState.SIN_COMBATE;
        warnAlreadyActiveManagedModules();
    }

    @Override
    public void onDeactivate() {
        releaseAll();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || !mc.player.isAlive()) {
            releaseAll();
            return;
        }

        PlayerEntity target = TargetUtils.getPlayerTarget(targetRange.get(), SortPriority.LowestDistance);
        lastTargetName = target == null ? null : target.getGameProfile().name();

        CombatSnapshot snapshot = snapshot(target);
        lastTargetDistance = snapshot.hasTarget() ? snapshot.targetDistance() : null;
        Plan plan = director.tick(snapshot, approachDistance.get());
        lastPlan = plan;

        apply(plan);
        reportPhaseChange(plan);
        lastReported = plan.state();
    }

    /** Avisa del cambio de fase: SIN_RECURSOS siempre y fuerte (spec §4.1, §6); el resto, si notify lo permite. */
    private void reportPhaseChange(Plan plan) {
        if (plan.state() == lastReported) return;

        if (plan.state() == CombatState.SIN_RECURSOS) {
            warnOutOfResources(plan);
        } else if (notify.get()) {
            info("%s%s", plan.state(), lastTargetName == null ? "" : " · " + lastTargetName);
        }
    }

    @SuppressWarnings("deprecation") // Uso de AbstractBlock.AbstractBlockState#blocksMovement, igual que EntityUtils.isAboveWater en meteor-client
    private CombatSnapshot snapshot(PlayerEntity target) {
        Inventory inventory = inventory();
        if (target == null) {
            return new CombatSnapshot(false, 0, false, false, false,
                mc.player.isGliding(), inventory.totems(), inventory.resources());
        }
        // "Enterrado" exige que el bloque bloquee el movimiento, no solo que no sea aire (spec
        // §4.3): !isAir() también es verdadero con agua, hierba alta, nieve, alfombras o
        // carteles, y sobre todo con la telaraña que pone auto-web en esta misma posición -que es
        // exactamente el módulo que RODEADO/SUPERFICIE encienden justo antes-. blocksMovement() lo
        // distingue: la obsidiana y el bedrock de un burrow lo cumplen, la telaraña no (verificado
        // contra AbstractBlock.AbstractBlockState#blocksMovement en las fuentes de Yarn 1.21.11:
        // excluye COBWEB explícitamente y en general solo es true para bloques "solid").
        boolean burrowed = mc.world.getBlockState(target.getBlockPos()).blocksMovement();
        return new CombatSnapshot(true, mc.player.distanceTo(target),
            EntityUtils.getCityBlock(target) != null, burrowed, target.isGliding(),
            mc.player.isGliding(), inventory.totems(), inventory.resources());
    }

    /** Lo que se lee del inventario para el snapshot: un solo barrido de los 36 slots para todo. */
    private record Inventory(Map<Resource, Integer> resources, int totems) {}

    private Inventory inventory() {
        Map<Resource, Integer> counts = new EnumMap<>(Resource.class);
        int totems = mc.player.getOffHandStack().getItem() == Items.TOTEM_OF_UNDYING ? 1 : 0;
        for (int slot = FIRST_SLOT; slot <= INVENTORY_LAST_SLOT; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty()) continue;
            if (stack.getItem() == Items.TOTEM_OF_UNDYING) { totems++; continue; }
            Resource resource = resourceOf(stack);
            if (resource == null) continue;
            // CRÍTICO (spec §6): cada recurso solo cuenta en el rango donde el módulo que lo usa
            // de verdad busca. crystal-aura, auto-trap, surround, auto-anvil y auto-web llaman a
            // InvUtils.findInHotbar/testInHotbar, que solo miran la hotbar (slots 0-8); auto-city
            // llama a InvUtils.find, que mira el inventario completo. Contar la mochila para los
            // cinco primeros diría "tomados" a un módulo que en realidad no ve nada y no hace nada.
            if (slot > lastSlotFor(resource)) continue;
            counts.merge(resource, stack.getCount(), Integer::sum);
        }
        return new Inventory(counts, totems);
    }

    /** El último slot que cuenta para este recurso (spec §6): ver el comentario en {@link #inventory()}. */
    private static int lastSlotFor(Resource resource) {
        return resource == Resource.PICKAXE ? INVENTORY_LAST_SLOT : HOTBAR_LAST_SLOT;
    }

    private static Resource resourceOf(ItemStack stack) {
        if (stack.getItem() == Items.END_CRYSTAL) return Resource.CRYSTALS;
        if (stack.getItem() == Items.OBSIDIAN) return Resource.OBSIDIAN;
        if (stack.getItem() == Items.COBWEB) return Resource.WEBS;
        if (stack.getItem() == Items.ANVIL) return Resource.ANVILS;
        if (stack.getItem() == Items.NETHERITE_PICKAXE || stack.getItem() == Items.DIAMOND_PICKAXE) return Resource.PICKAXE;
        return null;
    }

    /**
     * Enciende lo que pide el plan y apaga lo que tomó y ya no pide; nunca lo que no es suyo (spec
     * §7). La decisión de propiedad es de {@link ModuleLedger}, lógica pura y con tests propios
     * (spec §12): aquí solo se reúnen los nombres reales y se ejecuta lo que decide.
     */
    private void apply(Plan plan) {
        Set<String> wanted = new LinkedHashSet<>();
        for (ManagedModule module : plan.enable()) wanted.add(module.name());

        Set<String> active = new LinkedHashSet<>();
        for (ManagedModule module : ManagedModules.ALL) {
            Module m = byName(module.name());
            if (m != null && m.isActive()) active.add(module.name());
        }

        ModuleLedger.Result result = ledger.apply(director.state(), wanted, active);

        for (String name : result.toDisable()) {
            Module module = byName(name);
            if (module != null) module.disable();
        }
        for (String name : result.toEnable()) {
            Module module = byName(name);
            if (module != null) module.enable();
        }
        if (notify.get()) {
            for (String name : result.newlyReleased()) {
                info("%s ya no es mío: lo apagaste tú y no lo vuelvo a tomar en esta fase.", name);
            }
        }
    }

    /** I1: si algo que dirige ya estaba encendido al activar auto-pvp, es del jugador y hay que decirlo. */
    private void warnAlreadyActiveManagedModules() {
        for (ManagedModule managed : ManagedModules.ALL) {
            Module module = byName(managed.name());
            if (module == null || !module.isActive()) continue;

            if (managed.equals(ManagedModules.CRYSTAL_AURA)) {
                warning("crystal-aura ya estaba encendido: es tuyo, no lo apagaré ni contra un enterrado.");
            } else {
                warning("%s ya estaba encendido: es tuyo, no lo tocaré mientras no lo sueltes tú.", managed.name());
            }
        }
    }

    private void releaseAll() {
        for (String name : ledger.owned()) {
            Module module = byName(name);
            if (module != null && module.isActive()) module.disable();
        }
        ledger.reset();
        director.reset();
        lastPlan = null;
        lastReported = CombatState.SIN_COMBATE;
    }

    private static Module byName(String name) {
        return Modules.get().get(name);
    }

    /** I5: SIN_RECURSOS es el único aviso fuerte (spec §4.1, §6): chat en warning() y toast con sonido. */
    private void warnOutOfResources(Plan plan) {
        String message = outOfResourcesMessage(plan);
        warning("%s", message);

        MeteorToast.Builder toast = new MeteorToast.Builder("Xploits").text(message).icon(Items.BARRIER);
        // MeteorToast.update() llama a play(customSound) sin comprobar el nulo y vanilla lo dereferencia:
        // NPE en el hilo de render. Nunca pasar null; se silencia con volumen cero, igual que ElytraReplace.
        if (!notifySound.get()) {
            toast.sound(PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), 1.2f, 0f));
        }
        mc.getToastManager().add(toast.build());
    }

    private static String outOfResourcesMessage(Plan plan) {
        String reasons = plan.skipped().stream()
            .map(skipped -> skipped.module().name() + " (" + skipped.reason() + ")")
            .collect(Collectors.joining(", "));
        if (reasons.isEmpty()) return "Sin recursos: no hay ningún módulo de esta fase que puedas sostener.";
        return "Sin recursos para pelear: " + reasons + ".";
    }

    public String status() {
        if (!isActive()) return "auto-pvp está apagado.";
        if (lastPlan == null) return "auto-pvp encendido, todavía sin leer la situación.";

        Set<String> owned = ledger.owned();

        StringBuilder sb = new StringBuilder();
        sb.append(lastPlan.state()).append(" desde hace ").append(director.ticksInState() / 20L).append(" s");
        if (lastTargetName != null) {
            sb.append(" · objetivo ").append(lastTargetName);
            if (lastTargetDistance != null) {
                sb.append(" a ").append(String.format(Locale.forLanguageTag("es"), "%.1f", lastTargetDistance)).append(" bloques");
            }
        }
        sb.append("\n  tomados:      ").append(owned.isEmpty() ? "ninguno" : String.join(", ", owned));
        for (Skipped skipped : lastPlan.skipped()) {
            sb.append("\n  no encendido: ").append(skipped.module().name()).append(" — ").append(skipped.reason());
        }
        List<String> yours = yourActiveModules(owned);
        sb.append("\n  tuyos:        ").append(yours.isEmpty() ? "ninguno" : String.join(", ", yours)).append(" (no los toco)");
        return sb.toString();
    }

    /** I6: qué módulos de combate llevas activos que el director no controla, no una lista fija. */
    private List<String> yourActiveModules(Set<String> owned) {
        List<String> result = new ArrayList<>();
        for (ManagedModule managed : ManagedModules.ALL) {
            if (owned.contains(managed.name())) continue;
            Module module = byName(managed.name());
            if (module != null && module.isActive()) result.add(managed.name());
        }
        for (String name : ALWAYS_YOURS) {
            Module module = byName(name);
            if (module != null && module.isActive()) result.add(name);
        }
        return result;
    }
}
