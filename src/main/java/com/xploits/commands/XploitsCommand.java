package com.xploits.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.xploits.console.core.Nivel;
import com.xploits.kitrequester.KitRequester;
import com.xploits.pvp.AutoPvp;
import com.xploits.shared.XploitsCommandBase;
import com.xploits.shared.Languages;
import com.xploits.shared.Texts;
import com.xploits.shared.core.PositionedMsg;
import com.xploits.shared.core.i18n.LanguageChoice;
import com.xploits.shared.core.i18n.LanguageText;
import com.xploits.shared.core.i18n.Msg;
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

public class XploitsCommand extends XploitsCommandBase {
    private static final int MAX_HITS = 10;

    public XploitsCommand() {
        super("xploits", Texts.startupText(LanguageText.COMMAND_DESC));
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("status").executes(context -> {
            kitRequester().ifPresent(kr -> reply(Nivel.INFO, kr.name, PositionedMsg.same(kr.status())));
            return SINGLE_SUCCESS;
        }));
        builder.then(literal("reload").executes(context -> {
            kitRequester().ifPresent(kr -> reply(Nivel.INFO, kr.name, PositionedMsg.same(kr.reload())));
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
            pvp().ifPresent(module -> reply(Nivel.INFO, module.name, PositionedMsg.same(module.status())));
            return SINGLE_SUCCESS;
        }));
        builder.then(literal("travel")
            .executes(context -> {
                travel().ifPresent(module -> reply(Nivel.INFO, module.name, module.status()));
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
                sweep().ifPresent(module -> reply(Nivel.INFO, module.name, PositionedMsg.same(module.status())));
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
        builder.then(literal("language")
            .executes(context -> {
                info(Languages.describe());
                return SINGLE_SUCCESS;
            })
            .then(literal("auto").executes(context -> choose(LanguageChoice.AUTO)))
            .then(literal("es").executes(context -> choose(LanguageChoice.SPANISH)))
            .then(literal("en").executes(context -> choose(LanguageChoice.ENGLISH))));
    }

    private int choose(LanguageChoice choice) {
        Languages.choose(choice);
        return SINGLE_SUCCESS;
    }

    /**
     * Lanza el viaje y enseña lo que el módulo conteste: el mensaje del lanzamiento, o el motivo por
     * el que no se vuela.
     *
     * <p>El motivo de un rechazo de ruta es lo más valioso que sale por aquí -nombra el ajuste que
     * hay que tocar, su valor actual y la salida concreta-, así que llega al chat de una pieza: una
     * sola llamada, sin recortar, sin partir en líneas y sin resumir. Llega como {@link PositionedMsg}
     * (las coordenadas solo en la mitad del chat) y {@link #reply} lo traduce y lo pasa como
     * argumento, nunca como cadena de formato: un motivo con un porcentaje no puede romper la llamada.
     *
     * <p>Un rechazo sale en amarillo: si no hay viaje en marcha después de pedirlo, no se ha volado.
     */
    private void travelGo(AutoTravel autoTravel) {
        PositionedMsg message = autoTravel.start();
        reply(autoTravel.isTravelling() ? Nivel.INFO : Nivel.AVISO, autoTravel.name, message);
    }

    /** Corta el viaje. Si no había ninguno en marcha, lo que contesta el módulo es un aviso. */
    private void travelStop(AutoTravel autoTravel) {
        boolean travelling = autoTravel.isTravelling();
        Msg message = autoTravel.stop();
        reply(travelling ? Nivel.INFO : Nivel.AVISO, autoTravel.name, PositionedMsg.same(message));
    }

    /**
     * Lanza el barrido y enseña lo que conteste {@link NetherSweep#start()}: el resumen del
     * lanzamiento, o el motivo entero por el que no se vuela.
     *
     * <p>Igual que en {@link #travelGo}, el motivo de un rechazo aquí nombra el ajuste que hay que
     * tocar y a cuánto ponerlo, y llega entero: es un {@link Msg} que se traduce en el idioma del
     * jugador, y el texto ya traducido nunca se usa como cadena de formato.
     */
    private void sweepGo(NetherSweep sweep) {
        Msg message = sweep.start();
        reply(sweep.isSweeping() ? Nivel.INFO : Nivel.AVISO, sweep.name, PositionedMsg.same(message));
    }

    /** Corta el barrido. Si no había ninguno en marcha, lo que contesta el módulo es un aviso. */
    private void sweepStop(NetherSweep sweep) {
        boolean sweeping = sweep.isSweeping();
        Msg message = sweep.stop();
        reply(sweeping ? Nivel.INFO : Nivel.AVISO, sweep.name, PositionedMsg.same(message));
    }

    private void stashStatus(StashKeeper stashKeeper) {
        if (!stashKeeper.isActive()) {
            warning(Msg.of(CommandText.STASH_OFF_STATUS));
            return;
        }
        reply(Nivel.INFO, stashKeeper.name, PositionedMsg.same(stashKeeper.status()));
    }

    private void find(String query) {
        if (query.strip().length() < 2) {
            warning(Msg.of(CommandText.FIND_TOO_SHORT));
            return;
        }

        Optional<StashKeeper> maybeKeeper = keeper();
        if (maybeKeeper.isEmpty()) return;
        StashKeeper stashKeeper = maybeKeeper.get();
        if (!stashKeeper.isActive()) {
            warning(Msg.of(CommandText.STASH_OFF_FIND));
            return;
        }

        Set<String> ids = resolve(query);
        if (ids.isEmpty()) {
            warning(Msg.of(CommandText.FIND_UNKNOWN_ITEM, "query", query));
            return;
        }

        List<StashIndex.Hit> hits = stashKeeper.index().find(ids);
        if (hits.isEmpty()) {
            warning(Msg.of(CommandText.FIND_NOT_SEEN, "query", query));
            return;
        }

        String dimension = MeteorClient.mc.world == null ? null : MeteorClient.mc.world.getRegistryKey().getValue().toString();
        Double x = MeteorClient.mc.player == null ? null : MeteorClient.mc.player.getX();
        Double z = MeteorClient.mc.player == null ? null : MeteorClient.mc.player.getZ();
        info(Msg.of(hits.size() == 1 ? CommandText.FIND_HEADER_ONE : CommandText.FIND_HEADER_MANY, "count", hits.size(), "query", query));
        for (StashIndex.Hit hit : hits.subList(0, Math.min(MAX_HITS, hits.size()))) {
            Object where = hit.insideShulker() == null ? "" : Msg.of(CommandText.FIND_IN_SHULKER, "name", hit.insideShulker());
            Msg chat = Msg.of(CommandText.FIND_HIT, "item", shortId(hit.itemId()), "count", hit.count(),
                "place", hit.key().id(), "shulker", where, "ago", ago(hit.seenAt()));
            Msg log = Msg.of(CommandText.FIND_HIT, "item", shortId(hit.itemId()), "count", hit.count(),
                "place", hit.key().sinPosicion(dimension, x, z), "shulker", where, "ago", ago(hit.seenAt()));
            reply(Nivel.INFO, stashKeeper.name, new PositionedMsg(chat, log));
        }
        if (hits.size() > MAX_HITS) info(Msg.of(CommandText.FIND_MORE, "count", hits.size() - MAX_HITS));
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

    private static Msg ago(long seenAt) {
        Duration d = Duration.ofMillis(Math.max(0, System.currentTimeMillis() - seenAt));
        if (d.toMinutes() < 1) return Msg.of(CommandText.AGO_NOW);
        if (d.toHours() < 1) return Msg.of(CommandText.AGO_MINUTES, "n", d.toMinutes());
        if (d.toDays() < 1) return Msg.of(CommandText.AGO_HOURS, "n", d.toHours());
        long days = d.toDays();
        return days == 1 ? Msg.of(CommandText.AGO_DAY) : Msg.of(CommandText.AGO_DAYS, "n", days);
    }

    /** Devuelve el módulo, o avisa de que no está registrado y no devuelve nada. */
    private Optional<KitRequester> kitRequester() {
        KitRequester module = Modules.get().get(KitRequester.class);
        if (module == null) {
            warning(Msg.of(CommandText.MODULE_NOT_REGISTERED, "module", "kit-requester"));
            return Optional.empty();
        }
        return Optional.of(module);
    }

    /** Devuelve el módulo, o avisa de que no está registrado y no devuelve nada. */
    private Optional<StashKeeper> keeper() {
        StashKeeper module = Modules.get().get(StashKeeper.class);
        if (module == null) {
            warning(Msg.of(CommandText.MODULE_NOT_REGISTERED, "module", "stash-keeper"));
            return Optional.empty();
        }
        return Optional.of(module);
    }

    /** Devuelve el módulo, o avisa de que no está registrado y no devuelve nada. */
    private Optional<AutoPvp> pvp() {
        AutoPvp module = Modules.get().get(AutoPvp.class);
        if (module == null) {
            warning(Msg.of(CommandText.MODULE_NOT_REGISTERED, "module", "auto-pvp"));
            return Optional.empty();
        }
        return Optional.of(module);
    }

    /** Devuelve el módulo, o avisa de que no está registrado y no devuelve nada. */
    private Optional<AutoTravel> travel() {
        AutoTravel module = Modules.get().get(AutoTravel.class);
        if (module == null) {
            warning(Msg.of(CommandText.MODULE_NOT_REGISTERED, "module", "auto-travel"));
            return Optional.empty();
        }
        return Optional.of(module);
    }

    /** Devuelve el módulo, o avisa de que no está registrado y no devuelve nada. */
    private Optional<NetherSweep> sweep() {
        NetherSweep module = Modules.get().get(NetherSweep.class);
        if (module == null) {
            warning(Msg.of(CommandText.MODULE_NOT_REGISTERED, "module", "nether-sweep"));
            return Optional.empty();
        }
        return Optional.of(module);
    }
}
