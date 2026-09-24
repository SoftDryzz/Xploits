package com.xploits.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.xploits.console.core.Level;
import com.xploits.kitrequester.KitRequester;
import com.xploits.pvp.AutoPvp;
import com.xploits.pvp.recorder.FightRecorder;
import com.xploits.pvp.recorder.core.FightRecord;
import com.xploits.pvp.recorder.core.FightStore;
import com.xploits.pvp.recorder.core.FightSummary;
import com.xploits.pvp.recorder.core.RecorderText;
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

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class XploitsCommand extends XploitsCommandBase {
    private static final int MAX_HITS = 10;
    private static final int LIST_LIMIT = 10;

    public XploitsCommand() {
        super("xploits", Texts.startupText(LanguageText.COMMAND_DESC));
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("status").executes(context -> {
            kitRequester().ifPresent(kr -> reply(Level.INFO, kr.name, PositionedMsg.same(kr.status())));
            return SINGLE_SUCCESS;
        }));
        builder.then(literal("reload").executes(context -> {
            kitRequester().ifPresent(kr -> reply(Level.INFO, kr.name, PositionedMsg.same(kr.reload())));
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
        builder.then(literal("pvp")
            .executes(context -> {
                pvp().ifPresent(module -> reply(Level.INFO, module.name, PositionedMsg.same(module.status())));
                return SINGLE_SUCCESS;
            })
            .then(literal("review")
                .executes(context -> {
                    review(1);
                    return SINGLE_SUCCESS;
                })
                .then(argument("n", IntegerArgumentType.integer(1, FightStore.KEEP)).executes(context -> {
                    review(IntegerArgumentType.getInteger(context, "n"));
                    return SINGLE_SUCCESS;
                })))
            .then(literal("fights").executes(context -> {
                fights();
                return SINGLE_SUCCESS;
            })));
        builder.then(literal("travel")
            .executes(context -> {
                travel().ifPresent(module -> reply(Level.INFO, module.name, module.status()));
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
                sweep().ifPresent(module -> reply(Level.INFO, module.name, PositionedMsg.same(module.status())));
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
     * Starts the trip and shows whatever the module answers: the launch message, or the reason it
     * does not fly.
     *
     * <p>The reason for a route rejection is the most valuable thing that comes out of here -it names
     * the setting to change, its current value and the concrete way out-, so it reaches the chat in
     * one piece: a single call, not trimmed, not split into lines and not summarised. It arrives as a
     * {@link PositionedMsg} (coordinates only in the chat half) and {@link #reply} translates it and
     * passes it as an argument, never as a format string: a reason with a percent sign cannot break
     * the call.
     *
     * <p>A rejection comes out in yellow: if no trip is running after asking for one, nothing flew.
     */
    private void travelGo(AutoTravel autoTravel) {
        PositionedMsg message = autoTravel.start();
        reply(autoTravel.isTravelling() ? Level.INFO : Level.WARNING, autoTravel.name, message);
    }

    /** Stops the trip. If none was running, what the module answers is a warning. */
    private void travelStop(AutoTravel autoTravel) {
        boolean travelling = autoTravel.isTravelling();
        Msg message = autoTravel.stop();
        reply(travelling ? Level.INFO : Level.WARNING, autoTravel.name, PositionedMsg.same(message));
    }

    /**
     * Starts the sweep and shows whatever {@link NetherSweep#start()} answers: the launch summary, or
     * the whole reason it does not fly.
     *
     * <p>As in {@link #travelGo}, the reason for a rejection here names the setting to change and what
     * to set it to, and arrives whole: it is a {@link Msg} translated into the player's language, and
     * the translated text is never used as a format string.
     */
    private void sweepGo(NetherSweep sweep) {
        Msg message = sweep.start();
        reply(sweep.isSweeping() ? Level.INFO : Level.WARNING, sweep.name, PositionedMsg.same(message));
    }

    /** Stops the sweep. If none was running, what the module answers is a warning. */
    private void sweepStop(NetherSweep sweep) {
        boolean sweeping = sweep.isSweeping();
        Msg message = sweep.stop();
        reply(sweeping ? Level.INFO : Level.WARNING, sweep.name, PositionedMsg.same(message));
    }

    private void stashStatus(StashKeeper stashKeeper) {
        if (!stashKeeper.isActive()) {
            warning(Msg.of(CommandText.STASH_OFF_STATUS));
            return;
        }
        reply(Level.INFO, stashKeeper.name, PositionedMsg.same(stashKeeper.status()));
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
                "place", hit.key().withoutPosition(dimension, x, z), "shulker", where, "ago", ago(hit.seenAt()));
            reply(Level.INFO, stashKeeper.name, new PositionedMsg(chat, log));
        }
        if (hits.size() > MAX_HITS) info(Msg.of(CommandText.FIND_MORE, "count", hits.size() - MAX_HITS));
    }

    /**
     * The full breakdown of fight {@code n} (1 = most recent), sent as the recorder. Works whether the
     * module is on or off: it only reads {@link FightStore}. Empty history, an out-of-range {@code n} and
     * a corrupt file each get their own warning instead of a stack trace.
     */
    private void review(int n) {
        Optional<FightRecorder> maybeRecorder = recorder();
        if (maybeRecorder.isEmpty()) return;
        FightRecorder recorder = maybeRecorder.get();
        FightStore store = recorder.store();

        List<Path> files;
        try {
            files = store.list();
        } catch (IOException e) {
            reportCorrupt(recorder, store.folder(), e);
            return;
        }
        if (files.isEmpty()) {
            reply(Level.WARNING, recorder.name, PositionedMsg.same(Msg.of(RecorderText.REVIEW_NONE)));
            return;
        }
        if (n > files.size()) {
            reply(Level.WARNING, recorder.name, PositionedMsg.same(Msg.of(RecorderText.REVIEW_OUT_OF_RANGE, "count", files.size())));
            return;
        }

        Path file = files.get(n - 1);
        FightRecord record;
        try {
            record = store.load(file);
        } catch (IOException e) {
            reportCorrupt(recorder, file, e);
            return;
        }
        for (Msg line : FightSummary.review(record, n)) reply(Level.INFO, recorder.name, PositionedMsg.same(line));
    }

    /** The last {@value #LIST_LIMIT} fights, newest first, one line each. */
    private void fights() {
        Optional<FightRecorder> maybeRecorder = recorder();
        if (maybeRecorder.isEmpty()) return;
        FightRecorder recorder = maybeRecorder.get();
        FightStore store = recorder.store();

        List<Path> files;
        try {
            files = store.list();
        } catch (IOException e) {
            reportCorrupt(recorder, store.folder(), e);
            return;
        }
        if (files.isEmpty()) {
            reply(Level.WARNING, recorder.name, PositionedMsg.same(Msg.of(RecorderText.REVIEW_NONE)));
            return;
        }

        List<Path> shown = files.subList(0, Math.min(LIST_LIMIT, files.size()));
        reply(Level.INFO, recorder.name, PositionedMsg.same(Msg.of(RecorderText.LIST_HEADER, "count", shown.size())));
        int number = 1;
        for (Path file : shown) {
            FightRecord record;
            try {
                record = store.load(file);
            } catch (IOException e) {
                reportCorrupt(recorder, file, e);
                return;
            }
            reply(Level.INFO, recorder.name, PositionedMsg.same(FightSummary.listLine(number++, record, ago(record.endedAt()))));
        }
    }

    /** A corrupt fight file, said once: the name and the detail stay in chat, never in the console or its log. */
    private void reportCorrupt(FightRecorder recorder, Path file, IOException e) {
        Msg chat = Msg.of(RecorderText.REVIEW_CORRUPT, "file", file.getFileName().toString(), "detail", String.valueOf(e.getMessage()));
        Msg log = Msg.of(RecorderText.REVIEW_CORRUPT_LOG);
        reply(Level.WARNING, recorder.name, new PositionedMsg(chat, log));
    }

    /** Matches the query against the item id and its translated name, so that a translated name such as "obsidiana" works. */
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

    /** Returns the module, or warns that it is not registered and returns nothing. */
    private Optional<KitRequester> kitRequester() {
        KitRequester module = Modules.get().get(KitRequester.class);
        if (module == null) {
            warning(Msg.of(CommandText.MODULE_NOT_REGISTERED, "module", "kit-requester"));
            return Optional.empty();
        }
        return Optional.of(module);
    }

    /** Returns the module, or warns that it is not registered and returns nothing. */
    private Optional<StashKeeper> keeper() {
        StashKeeper module = Modules.get().get(StashKeeper.class);
        if (module == null) {
            warning(Msg.of(CommandText.MODULE_NOT_REGISTERED, "module", "stash-keeper"));
            return Optional.empty();
        }
        return Optional.of(module);
    }

    /** Returns the module, or warns that it is not registered and returns nothing. */
    private Optional<AutoPvp> pvp() {
        AutoPvp module = Modules.get().get(AutoPvp.class);
        if (module == null) {
            warning(Msg.of(CommandText.MODULE_NOT_REGISTERED, "module", "auto-pvp"));
            return Optional.empty();
        }
        return Optional.of(module);
    }

    /** Returns the module, or warns that it is not registered and returns nothing. */
    private Optional<FightRecorder> recorder() {
        FightRecorder module = Modules.get().get(FightRecorder.class);
        if (module == null) {
            warning(Msg.of(CommandText.MODULE_NOT_REGISTERED, "module", "fight-recorder"));
            return Optional.empty();
        }
        return Optional.of(module);
    }

    /** Returns the module, or warns that it is not registered and returns nothing. */
    private Optional<AutoTravel> travel() {
        AutoTravel module = Modules.get().get(AutoTravel.class);
        if (module == null) {
            warning(Msg.of(CommandText.MODULE_NOT_REGISTERED, "module", "auto-travel"));
            return Optional.empty();
        }
        return Optional.of(module);
    }

    /** Returns the module, or warns that it is not registered and returns nothing. */
    private Optional<NetherSweep> sweep() {
        NetherSweep module = Modules.get().get(NetherSweep.class);
        if (module == null) {
            warning(Msg.of(CommandText.MODULE_NOT_REGISTERED, "module", "nether-sweep"));
            return Optional.empty();
        }
        return Optional.of(module);
    }
}
