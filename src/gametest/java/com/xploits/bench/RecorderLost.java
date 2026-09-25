package com.xploits.bench;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xploits.pvp.recorder.FightRecorder;
import com.xploits.pvp.recorder.core.FightOutcome;
import com.xploits.pvp.recorder.core.FightRecord;
import com.xploits.pvp.recorder.core.FightSummary;
import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.Msg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * CHECK {@code recorder-lost} (spec {@code 2026-09-25-ingame-bench}, §Scenarios): a fight you die in is
 * saved LOST, its review renders in both languages with no missing text, and its file holds no position.
 *
 * <p>Bare loadout, recorder on. t=0: a crystal ten blocks away explodes (the fight opens) and our health
 * must drop. t=1 s: {@code /kill} (it goes through the totem). t=4 s: the client asks to respawn.
 */
final class RecorderLost implements Scenario {
    private static final int KILL_AT = 20;
    private static final int RESPAWN_AT = 80;
    /** What the catalogs render for a missing key or argument (a single left-pointing angle quote). */
    private static final char MISSING_TEXT = '\u2039';
    /** Keys that would hold a position; none may appear at any depth of a fight file. */
    private static final Set<String> POSITION_KEYS = Set.of("x", "y", "z", "pos", "position", "coord", "dimension");

    @Override
    public String name() {
        return "recorder-lost";
    }

    @Override
    public Kind kind() {
        return Kind.CHECK;
    }

    @Override
    public int seconds() {
        return 8;
    }

    @Override
    public void arrange(Bench bench) {
        bench.meteor(FightRecorder.class);
        bench.arena().bare();
    }

    @Override
    public Metrics act(Bench bench) {
        float before = bench.fromClient(client -> client.player.getHealth());
        bench.start(true);
        bench.arena().explodeCrystal(10, 0, 0);
        bench.ticks(KILL_AT);
        float after = bench.fromClient(client -> client.player.getHealth());
        Bench.check(after < before, "the crystal did not lower our health");

        bench.command("kill " + Bench.PLAYER);
        bench.ticks(RESPAWN_AT - KILL_AT);
        bench.onClient(client -> client.player.requestRespawn());
        bench.ticks(seconds() * 20 - RESPAWN_AT);
        if (!bench.fromClient(client -> client.player != null && client.player.isAlive())) {
            throw new BenchException("the player did not respawn");
        }

        List<FightRecord> fights = bench.finish();
        Bench.check(fights.size() == 1, "expected one new fight record, found " + fights.size());
        FightRecord fight = fights.getFirst();
        Bench.check(fight.outcome() == FightOutcome.LOST, "the fight ended " + fight.outcome() + ", not LOST");

        for (Language language : Language.values()) {
            List<String> problems = new ArrayList<>();
            Catalog catalog = Catalog.load(language, problems::add);
            for (Msg line : FightSummary.review(fight, 1)) {
                Bench.check(catalog.render(line).indexOf(MISSING_TEXT) < 0,
                    "the review in " + language.code() + " has missing text in " + line.key().id());
            }
            Bench.check(problems.isEmpty(), "the review in " + language.code() + " reported " + problems.size() + " text problems");
        }

        Path file = bench.newFightFiles().getFirst();
        Set<String> keys = new TreeSet<>();
        keys(parse(file), keys);
        keys.retainAll(POSITION_KEYS);
        Bench.check(keys.isEmpty(), "the fight file has position keys: " + String.join(", ", keys));
        return Metrics.none();
    }

    private static JsonElement parse(Path file) {
        try {
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            throw new BenchException("the fight file could not be read as JSON");
        }
    }

    /** Every object key at any depth, arrays included. */
    private static void keys(JsonElement element, Set<String> keys) {
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (String key : object.keySet()) {
                keys.add(key);
                keys(object.get(key), keys);
            }
        } else if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) keys(item, keys);
        }
    }
}
