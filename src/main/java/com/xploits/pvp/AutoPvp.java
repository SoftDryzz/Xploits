package com.xploits.pvp;

import com.xploits.XploitsAddon;
import com.xploits.pvp.core.CombatDirector;
import com.xploits.pvp.core.CombatSnapshot;
import com.xploits.pvp.core.CombatState;
import com.xploits.pvp.core.ManagedModule;
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
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Dirige los módulos de combate de Meteor según la fase de la pelea (spec §1). No ejecuta ninguna
 * acción de combate: solo enciende y apaga, y solo apaga lo que encendió él (spec §7).
 */
public class AutoPvp extends Module {
    private static final int FIRST_SLOT = 0;
    private static final int LAST_SLOT = 35;

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
        .description("Más lejos de esta distancia la fase es de acercamiento; más cerca, de superficie.")
        .defaultValue(6)
        .range(2, 32)
        .sliderRange(2, 32)
        .build()
    );

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("notify")
        .description("Aviso local al cambiar de fase.")
        .defaultValue(true)
        .build()
    );

    private final CombatDirector director = new CombatDirector();
    private final Set<String> owned = new LinkedHashSet<>();

    private Plan lastPlan;
    private String lastTargetName;
    private CombatState lastReported = CombatState.SIN_COMBATE;

    public AutoPvp() {
        super(XploitsAddon.CATEGORY, "auto-pvp", "Dirige los módulos de combate según la fase de la pelea.");
    }

    @Override
    public void onActivate() {
        director.reset();
        owned.clear();
        lastPlan = null;
        lastTargetName = null;
        lastReported = CombatState.SIN_COMBATE;
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
        Plan plan = director.tick(snapshot, approachDistance.get());
        lastPlan = plan;

        apply(plan);

        if (notify.get() && plan.state() != lastReported) {
            info("%s%s", plan.state(), lastTargetName == null ? "" : " · " + lastTargetName);
        }
        lastReported = plan.state();
    }

    private CombatSnapshot snapshot(PlayerEntity target) {
        Inventory inventory = inventory();
        if (target == null) {
            return new CombatSnapshot(false, 0, false, false, false,
                mc.player.isGliding(), inventory.totems(), inventory.resources());
        }
        boolean burrowed = !mc.world.getBlockState(target.getBlockPos()).isAir();
        return new CombatSnapshot(true, mc.player.distanceTo(target),
            EntityUtils.getCityBlock(target) != null, burrowed, target.isGliding(),
            mc.player.isGliding(), inventory.totems(), inventory.resources());
    }

    /** Lo que se lee del inventario para el snapshot: un solo barrido de los 36 slots para ambas cosas. */
    private record Inventory(Map<Resource, Integer> resources, int totems) {}

    private Inventory inventory() {
        Map<Resource, Integer> counts = new EnumMap<>(Resource.class);
        int totems = mc.player.getOffHandStack().getItem() == Items.TOTEM_OF_UNDYING ? 1 : 0;
        for (int slot = FIRST_SLOT; slot <= LAST_SLOT; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty()) continue;
            if (stack.getItem() == Items.TOTEM_OF_UNDYING) { totems++; continue; }
            Resource resource = resourceOf(stack);
            if (resource != null) counts.merge(resource, stack.getCount(), Integer::sum);
        }
        return new Inventory(counts, totems);
    }

    private static Resource resourceOf(ItemStack stack) {
        if (stack.getItem() == Items.END_CRYSTAL) return Resource.CRYSTALS;
        if (stack.getItem() == Items.OBSIDIAN) return Resource.OBSIDIAN;
        if (stack.getItem() == Items.COBWEB) return Resource.WEBS;
        if (stack.getItem() == Items.ANVIL) return Resource.ANVILS;
        if (stack.getItem() == Items.NETHERITE_PICKAXE || stack.getItem() == Items.DIAMOND_PICKAXE) return Resource.PICKAXE;
        return null;
    }

    /** Enciende lo que pide el plan y apaga lo que tomó y ya no pide. Nunca toca lo que no es suyo. */
    private void apply(Plan plan) {
        Set<String> wanted = new LinkedHashSet<>();
        for (ManagedModule module : plan.enable()) wanted.add(module.name());

        for (String name : new ArrayList<>(owned)) {
            Module module = byName(name);
            if (module == null) { owned.remove(name); continue; }
            // Si el jugador lo apagó a mano, deja de ser nuestro: manda él (spec §7).
            if (!module.isActive()) { owned.remove(name); continue; }
            if (!wanted.contains(name)) { module.disable(); owned.remove(name); }
        }

        for (String name : wanted) {
            Module module = byName(name);
            if (module == null || module.isActive()) continue;
            module.enable();
            owned.add(name);
        }
    }

    private void releaseAll() {
        for (String name : new ArrayList<>(owned)) {
            Module module = byName(name);
            if (module != null && module.isActive()) module.disable();
        }
        owned.clear();
        director.reset();
        lastPlan = null;
        lastReported = CombatState.SIN_COMBATE;
    }

    private static Module byName(String name) {
        return Modules.get().get(name);
    }

    public String status() {
        if (!isActive()) return "auto-pvp está apagado.";
        if (lastPlan == null) return "auto-pvp encendido, todavía sin leer la situación.";

        StringBuilder sb = new StringBuilder();
        sb.append(lastPlan.state());
        if (lastTargetName != null) sb.append(" · objetivo ").append(lastTargetName);
        sb.append("\n  tomados:      ").append(owned.isEmpty() ? "ninguno" : String.join(", ", owned));
        for (Skipped skipped : lastPlan.skipped()) {
            sb.append("\n  no encendido: ").append(skipped.module().name()).append(" — ").append(skipped.reason());
        }
        sb.append("\n  tuyos:        auto-totem, auto-armor, offhand, auto-weapon (no los toco)");
        return sb.toString();
    }
}
