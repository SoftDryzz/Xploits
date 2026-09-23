package com.xploits.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.xploits.console.core.Nivel;
import com.xploits.kitrequester.KitRequester;
import com.xploits.pvp.AutoPvp;
import com.xploits.shared.ComandoBase;
import com.xploits.shared.core.TextoConPosicion;
import com.xploits.stash.StashKeeper;
import com.xploits.stash.core.StashIndex;
import com.xploits.sweep.NetherSweep;
import com.xploits.travel.AutoTravel;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class XploitsCommand extends ComandoBase {
    private static final int MAX_HITS = 10;

    public XploitsCommand() {
        super("xploits", "Estado del addon, búsqueda en el stash y recarga.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("status").executes(context -> {
            kitRequester().ifPresent(kr -> responder(Nivel.INFO, kr.name, TextoConPosicion.igual(kr.status())));
            return SINGLE_SUCCESS;
        }));
        builder.then(literal("reload").executes(context -> {
            kitRequester().ifPresent(kr -> responder(Nivel.INFO, kr.name, TextoConPosicion.igual(kr.reload())));
            return SINGLE_SUCCESS;
        }));
        builder.then(literal("stash").executes(context -> {
            keeper().ifPresent(this::stashStatus);
            return SINGLE_SUCCESS;
        }));
        builder.then(literal("find").then(argument("item", StringArgumentType.greedyString()).executes(context -> {
            find(StringArgumentType.getString(context, "item"));
            return SINGLE_SUCCESS;
        })));
        builder.then(literal("pvp").executes(context -> {
            pvp().ifPresent(module -> responder(Nivel.INFO, module.name, TextoConPosicion.igual(module.status())));
            return SINGLE_SUCCESS;
        }));
        builder.then(literal("travel")
            .executes(context -> {
                travel().ifPresent(module -> responder(Nivel.INFO, module.name, module.status()));
                return SINGLE_SUCCESS;
            })
            .then(literal("go").executes(context -> {
                travel().ifPresent(this::travelGo);
                return SINGLE_SUCCESS;
            }))
            .then(literal("stop").executes(context -> {
                travel().ifPresent(this::travelStop);
                return SINGLE_SUCCESS;
            })));
        builder.then(literal("sweep")
            .executes(context -> {
                sweep().ifPresent(module -> responder(Nivel.INFO, module.name, TextoConPosicion.igual(module.status())));
                return SINGLE_SUCCESS;
            })
            .then(literal("go").executes(context -> {
                sweep().ifPresent(this::sweepGo);
                return SINGLE_SUCCESS;
            }))
            .then(literal("stop").executes(context -> {
                sweep().ifPresent(this::sweepStop);
                return SINGLE_SUCCESS;
            })));
    }

    /**
     * Lanza el viaje y enseña lo que el módulo conteste: el mensaje del lanzamiento, o el motivo por
     * el que no se vuela.
     *
     * <p>El motivo de un rechazo de ruta es lo más valioso que sale por aquí -nombra el ajuste que
     * hay que tocar, su valor actual y la salida concreta-, así que llega al chat de una pieza: una
     * sola llamada, sin recortar, sin partir en líneas y sin resumir. Y el texto va como
     * <b>argumento</b> de un {@code "%s"}, nunca como cadena de formato: {@code ChatUtils} se lo pasa
     * tal cual a {@code String.format}, así que un motivo que llevara un porcentaje reventaría la
     * llamada y el jugador se quedaría sin ver justo el mensaje que tenía que leer. El mismo motivo
     * por el que el resto de subcomandos de este archivo escriben {@code info("%s", ...)}.
     *
     * <p>Un rechazo sale en amarillo: si no hay viaje en marcha después de pedirlo, no se ha volado.
     */
    private void travelGo(AutoTravel autoTravel) {
        TextoConPosicion message = autoTravel.start();
        responder(autoTravel.isTravelling() ? Nivel.INFO : Nivel.AVISO, autoTravel.name, message);
    }

    /** Corta el viaje. Si no había ninguno en marcha, lo que contesta el módulo es un aviso. */
    private void travelStop(AutoTravel autoTravel) {
        boolean travelling = autoTravel.isTravelling();
        String message = autoTravel.stop();
        responder(travelling ? Nivel.INFO : Nivel.AVISO, autoTravel.name, TextoConPosicion.igual(message));
    }

    /**
     * Lanza el barrido y enseña lo que conteste {@link NetherSweep#start()}: el resumen del
     * lanzamiento, o el motivo entero por el que no se vuela.
     *
     * <p>Igual que en {@link #travelGo}, el motivo de un rechazo aquí nombra el ajuste que hay que
     * tocar y a cuánto ponerlo, y puede llevar un {@code %} -por ejemplo si el rechazo cita
     * {@code baritone-prefix} tal como lo dejó el jugador-. Por eso va como <b>argumento</b> de un
     * {@code "%s"}, nunca como cadena de formato: pasarlo directo a {@code info(mensaje)} acabaría en
     * {@code String.format(mensaje)} y, con un motivo que llevara un porcentaje, en una excepción que
     * se traga el mensaje justo cuando el jugador más lo necesita.
     */
    private void sweepGo(NetherSweep sweep) {
        String message = sweep.start();
        responder(sweep.isSweeping() ? Nivel.INFO : Nivel.AVISO, sweep.name, TextoConPosicion.igual(message));
    }

    /** Corta el barrido. Si no había ninguno en marcha, lo que contesta el módulo es un aviso. */
    private void sweepStop(NetherSweep sweep) {
        boolean sweeping = sweep.isSweeping();
        String message = sweep.stop();
        responder(sweeping ? Nivel.INFO : Nivel.AVISO, sweep.name, TextoConPosicion.igual(message));
    }

    private void stashStatus(StashKeeper stashKeeper) {
        if (!stashKeeper.isActive()) {
            warning("stash-keeper está desactivado: el índice no está cargado en memoria. Actívalo para consultarlo.");
            return;
        }
        responder(Nivel.INFO, stashKeeper.name, TextoConPosicion.igual(stashKeeper.status()));
    }

    private void find(String query) {
        if (query.strip().length() < 2) {
            warning("Hace falta al menos un par de letras para buscar, por ejemplo \"obsi\".");
            return;
        }

        Optional<StashKeeper> maybeKeeper = keeper();
        if (maybeKeeper.isEmpty()) return;
        StashKeeper stashKeeper = maybeKeeper.get();
        if (!stashKeeper.isActive()) {
            warning("stash-keeper está desactivado: el índice no está cargado en memoria. Actívalo para poder buscar.");
            return;
        }

        Set<String> ids = resolve(query);
        if (ids.isEmpty()) {
            warning("No conozco ningún ítem que se parezca a \"%s\".", query);
            return;
        }

        List<StashIndex.Hit> hits = stashKeeper.index().find(ids);
        if (hits.isEmpty()) {
            warning("No he visto \"%s\" en ningún contenedor. Recuerda que un cofre solo entra en el índice cuando lo abres, "
                + "y que los shulkers que llevas encima tampoco están indexados.", query);
            return;
        }

        String dimension = MeteorClient.mc.world == null ? null : MeteorClient.mc.world.getRegistryKey().getValue().toString();
        Double x = MeteorClient.mc.player == null ? null : MeteorClient.mc.player.getX();
        Double z = MeteorClient.mc.player == null ? null : MeteorClient.mc.player.getZ();
        info("%d %s con \"%s\":", hits.size(), hits.size() == 1 ? "sitio" : "sitios", query);
        for (StashIndex.Hit hit : hits.subList(0, Math.min(MAX_HITS, hits.size()))) {
            String where = hit.insideShulker() == null ? "" : " · en shulker \"" + hit.insideShulker() + "\"";
            String chat = String.format("  %s x%d · %s%s · visto %s",
                shortId(hit.itemId()), hit.count(), hit.key().id(), where, ago(hit.seenAt()));
            String registro = String.format("  %s x%d · %s%s · visto %s",
                shortId(hit.itemId()), hit.count(), hit.key().sinPosicion(dimension, x, z), where, ago(hit.seenAt()));
            responder(Nivel.INFO, stashKeeper.name, new TextoConPosicion(chat, registro));
        }
        if (hits.size() > MAX_HITS) info("  ...y %d más.", hits.size() - MAX_HITS);
    }

    /** Casa la consulta contra el id del ítem y contra su nombre traducido, para que "obsidiana" funcione. */
    private static Set<String> resolve(String query) {
        String needle = query.toLowerCase().strip();
        Set<String> ids = new LinkedHashSet<>();
        for (Item item : Registries.ITEM) {
            String id = Registries.ITEM.getId(item).toString();
            String name = item.getName().getString().toLowerCase();
            if (id.toLowerCase().contains(needle) || name.contains(needle)) ids.add(id);
        }
        return ids;
    }

    private static String shortId(String itemId) {
        return itemId.startsWith("minecraft:") ? itemId.substring("minecraft:".length()) : itemId;
    }

    private static String ago(long seenAt) {
        Duration d = Duration.ofMillis(Math.max(0, System.currentTimeMillis() - seenAt));
        if (d.toMinutes() < 1) return "hace un momento";
        if (d.toHours() < 1) return "hace " + d.toMinutes() + " min";
        if (d.toDays() < 1) return "hace " + d.toHours() + " h";
        long days = d.toDays();
        return "hace " + days + (days == 1 ? " día" : " días");
    }

    /** Devuelve el módulo, o avisa de que no está registrado y no devuelve nada. */
    private Optional<KitRequester> kitRequester() {
        KitRequester module = Modules.get().get(KitRequester.class);
        if (module == null) {
            warning("El módulo kit-requester no está registrado.");
            return Optional.empty();
        }
        return Optional.of(module);
    }

    /** Devuelve el módulo, o avisa de que no está registrado y no devuelve nada. */
    private Optional<StashKeeper> keeper() {
        StashKeeper module = Modules.get().get(StashKeeper.class);
        if (module == null) {
            warning("El módulo stash-keeper no está registrado.");
            return Optional.empty();
        }
        return Optional.of(module);
    }

    /** Devuelve el módulo, o avisa de que no está registrado y no devuelve nada. */
    private Optional<AutoPvp> pvp() {
        AutoPvp module = Modules.get().get(AutoPvp.class);
        if (module == null) {
            warning("El módulo auto-pvp no está registrado.");
            return Optional.empty();
        }
        return Optional.of(module);
    }

    /** Devuelve el módulo, o avisa de que no está registrado y no devuelve nada. */
    private Optional<AutoTravel> travel() {
        AutoTravel module = Modules.get().get(AutoTravel.class);
        if (module == null) {
            warning("El módulo auto-travel no está registrado.");
            return Optional.empty();
        }
        return Optional.of(module);
    }

    /** Devuelve el módulo, o avisa de que no está registrado y no devuelve nada. */
    private Optional<NetherSweep> sweep() {
        NetherSweep module = Modules.get().get(NetherSweep.class);
        if (module == null) {
            warning("El módulo nether-sweep no está registrado.");
            return Optional.empty();
        }
        return Optional.of(module);
    }
}
