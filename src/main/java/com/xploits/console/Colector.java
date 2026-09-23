package com.xploits.console;

import com.xploits.console.core.Instantanea;
import com.xploits.console.core.Texto;
import com.xploits.elytra.core.ElytraPolicy;
import com.xploits.pvp.AutoPvp;
import com.xploits.pvp.core.Resource;
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
 * Lee el juego y construye la foto de la cabecera (spec consola §9). Solo desde el hilo del juego:
 * todo lo que toca -jugador, mundo, inventario, campos de los módulos- es de ese hilo.
 *
 * <p>Sin jugador, todo es desconocido salvo los módulos: nunca cero.
 */
final class Colector {
    private static final int ANCHO_AHORA = 30;

    private Colector() {
    }

    static Instantanea tomar() {
        MinecraftClient mc = MeteorClient.mc;
        List<Instantanea.EstadoModulo> modulos = new ArrayList<>();
        for (Module m : Modules.get().getAll()) {
            if (!(m instanceof XploitsModule x) || m instanceof Consola || m instanceof XploitsSettings) continue;
            String ahora = m.isActive() ? Texto.recortar(x.ahora(), ANCHO_AHORA) : "";
            modulos.add(new Instantanea.EstadoModulo(m.name, m.isActive(), ahora));
        }
        if (mc.player == null || mc.world == null) return Instantanea.sinJugador(modulos);

        AutoPvp pvp = Modules.get().get(AutoPvp.class);
        Optional<AutoPvp.Vecindario> vecindario = pvp == null ? Optional.empty() : pvp.vecindario();
        Map<Resource, Integer> recursos = pvp == null ? null : pvp.recursosEnBarra().orElse(null);
        AutoTravel travel = Modules.get().get(AutoTravel.class);
        NetherSweep sweep = Modules.get().get(NetherSweep.class);

        ItemStack pecho = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        int elytra = pecho.getItem() == Items.ELYTRA ? ElytraPolicy.percentOf(pecho.getDamage(), pecho.getMaxDamage()) : -1;

        return new Instantanea(
            mc.world.getRegistryKey().getValue().toString(),
            vecindario.map(AutoPvp.Vecindario::cargados).orElse(null),
            vecindario.map(AutoPvp.Vecindario::nuestros).orElse(null),
            InvUtils.find(Items.FIREWORK_ROCKET).count(),
            elytra,
            travel == null ? null : travel.progreso().orElse(null),
            sweep == null ? null : sweep.progreso().orElse(null),
            (double) PlayerUtils.getTotalHealth(),
            mc.player.getArmor(),
            recursos == null ? null : recursos.getOrDefault(Resource.OBSIDIAN, 0),
            recursos == null ? null : recursos.getOrDefault(Resource.CRYSTALS, 0),
            recursos == null ? null : recursos.getOrDefault(Resource.WEBS, 0),
            recursos == null ? null : recursos.getOrDefault(Resource.ANVILS, 0),
            modulos);
    }
}
