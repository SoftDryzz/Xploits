package com.xploits.console;

import com.xploits.console.core.GameSnapshot;
import com.xploits.console.core.TerminalText;
import com.xploits.elytra.core.ElytraPolicy;
import com.xploits.pvp.AutoPvp;
import com.xploits.pvp.core.Resource;
import com.xploits.shared.Texts;
import com.xploits.shared.XploitsModule;
import com.xploits.shared.XploitsSettings;
import com.xploits.sweep.NetherSweep;
import com.xploits.travel.AutoTravel;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the game and builds the header's snapshot (console spec §9). Game thread only: everything it
 * touches (player, world, inventory, the modules' fields) belongs to that thread.
 *
 * <p>Without a player, everything is unknown except the modules: never zero.
 */
final class SnapshotCollector {
    private static final int ACTIVITY_WIDTH = 30;

    private SnapshotCollector() {
    }

    static GameSnapshot capture() {
        MinecraftClient mc = MeteorClient.mc;
        List<GameSnapshot.ModuleStatus> modules = new ArrayList<>();
        for (Module m : Modules.get().getAll()) {
            if (!(m instanceof XploitsModule x) || m instanceof ConsoleModule || m instanceof XploitsSettings) continue;
            String activity = m.isActive() ? TerminalText.truncate(x.activity(), ACTIVITY_WIDTH) : "";
            modules.add(new GameSnapshot.ModuleStatus(m.name, m.isActive(), activity));
        }
        if (mc.player == null || mc.world == null) return GameSnapshot.withoutPlayer(modules, Texts.current());

        AutoPvp pvp = Modules.get().get(AutoPvp.class);
        Optional<AutoPvp.Neighbourhood> neighbourhood = pvp == null ? Optional.empty() : pvp.neighbourhood();
        Map<Resource, Integer> resources = pvp == null ? null : pvp.hotbarResources().orElse(null);
        AutoTravel travel = Modules.get().get(AutoTravel.class);
        NetherSweep sweep = Modules.get().get(NetherSweep.class);

        ItemStack chest = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        int elytra = chest.getItem() == Items.ELYTRA ? ElytraPolicy.percentOf(chest.getDamage(), chest.getMaxDamage()) : -1;

        return new GameSnapshot(
            mc.world.getRegistryKey().getValue().toString(),
            neighbourhood.map(AutoPvp.Neighbourhood::loaded).orElse(null),
            neighbourhood.map(AutoPvp.Neighbourhood::friendly).orElse(null),
            InvUtils.find(Items.FIREWORK_ROCKET).count(),
            elytra,
            travel == null ? null : travel.progress().orElse(null),
            sweep == null ? null : sweep.progress().orElse(null),
            (double) PlayerUtils.getTotalHealth(),
            mc.player.getArmor(),
            resources == null ? null : resources.getOrDefault(Resource.OBSIDIAN, 0),
            resources == null ? null : resources.getOrDefault(Resource.CRYSTALS, 0),
            resources == null ? null : resources.getOrDefault(Resource.WEBS, 0),
            resources == null ? null : resources.getOrDefault(Resource.ANVILS, 0),
            modules,
            Texts.current());
    }
}
