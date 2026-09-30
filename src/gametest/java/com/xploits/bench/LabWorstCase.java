package com.xploits.bench;

import com.xploits.bench.core.GappleSchedule;
import com.xploits.pvp.core.CrystalModule;
import com.xploits.pvp.core.Plan;
import com.xploits.pvp.core.ShellModule;
import com.xploits.pvp.crystal.CrystalAuraPlusPlus;
import com.xploits.pvp.crystal.core.Decision;
import com.xploits.pvp.crystal.core.RiskLevel;
import com.xploits.pvp.recorder.FightRecorder;
import com.xploits.pvp.recorder.core.FightTracker;
import com.xploits.pvp.shell.SurroundPlusPlus;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.CrystalAura;
import meteordevelopment.meteorclient.systems.modules.combat.SelfTrap;
import meteordevelopment.meteorclient.systems.modules.combat.Surround;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The lab's worst cases (the owner, 2026-09-30: "simulate the worst situations: they mine me, insta-place
 * crystals, web me, several people"). Not bench scenarios: {@link Scenarios} does not list them and they judge
 * nothing; only {@code ./gradlew runClientGameTest -Pbench.lab=<names>} runs them, one run each, and each writes a
 * per-second trace to {@code build/bench-lab/<name>.md}, to see what really happens to our player.
 *
 * <p>Our side is the owner's own setup in the fight he lost: in a 1x1 obsidian hole, the fight loadout (real
 * armour, {@value Arena#FIGHT_TOTEMS} totems, AutoTotem) plus enchanted golden apples nobody gives him for free,
 * his own {@code surround} on before anything else, then {@code auto-pvp} driving {@code crystal-aura++} (or the
 * aura alone, by hand). The opponents fight like hacked clients, all at once: one mines our hole's east wall and
 * puts a crystal in the gap the same tick, again every time it is refilled; one face-places a crystal at our head
 * every half second while it webs our feet and head and roofs us in ({@link LabHarass}); a third crystals our feet
 * from the other side and fills the holes around us. They protect themselves too: each surrounds itself once hit,
 * breaks crystals near it, carries {@value Arena#FIGHT_TOTEMS} totems and eats after every pop.
 *
 * <p>Nothing written carries a position: distances, counts and states only.
 */
final class LabWorstCase implements Scenario {
    /** The system property {@code -Pbench.lab} sets: the variants to run, by name, comma separated. */
    static final String PROPERTY = "xploits.lab";
    /** Where the traces go ({@code build/bench-lab}). */
    static final String OUT_PROPERTY = "xploits.lab.out";
    /** The system property {@code -Pbench.lab.runs} sets: how many times each variant runs (1 when absent). */
    static final String RUNS_PROPERTY = "xploits.lab.runs";
    /** The one-line-per-run table every run appends to, next to the traces. */
    static final String SUMMARY = "summary.md";
    static final int RUN_TICKS = 1200;
    /** A drop in how far we have got, within one tick, larger than this while walking: something put us back. */
    private static final double PULLED_BACK = 0.15;
    private static final Logger LOG = LoggerFactory.getLogger("xploits-bench");

    /**
     * Who drives the crystal aura: {@code auto-pvp}, as the owner played, or nobody (crystal-aura++ on by hand), or
     * Meteor's own crystal-aura on by hand, for comparison: it has no reserve.
     */
    enum Driver { AUTO_PVP, BY_HAND, METEOR_BY_HAND }

    /**
     * The crystal aura's settings in a variant, on whichever aura it runs (crystal-aura++ mirrors Meteor's names; only
     * the walls ranges and the face-place group are named differently). Ranges stop at 6 because that is where
     * Meteor's own sliders stop: past it the server refuses the placement anyway.
     */
    enum AuraConfig {
        DEFAULTS("the aura's defaults"),
        OWNER("yours: min-damage 0.9, max-damage 36, place and break range 6, pause-health 1, anti-suicide off"),
        OWNER_MAX_FACE("yours with every range at its maximum (place, break and both walls ranges 6, target range 16)"
            + " and face-placing at any health (face-place-health 36)"),
        COMPETITIVE("a competitive config, ThunderHack's defaults: min-damage 6, max-damage 10, anti-suicide on, place"
            + " and break range 5, walls range 3.5, face-place-health 5");

        final String description;

        AuraConfig(String description) {
            this.description = description;
        }
    }

    /** Which wall-mining attacker the first opponent is. */
    enum City {
        /** The bench's own {@link SurroundMiner}: instant, and its crystal spawned without the base rule (the first runs). */
        SURROUND_MINER("the bench's instant city, crystal without the base rule"),
        /** {@link LabCityAttack} at vanilla speed. */
        VANILLA("an honest city at vanilla speed, " + LabCityAttack.VANILLA_TICKS + " ticks a block"),
        /** {@link LabCityAttack} breaking again at once: the worst case. */
        INSTANT("an honest city breaking again at once, the worst case");

        final String description;

        City(String description) {
            this.description = description;
        }
    }

    /** What a variant changes from the plain setup. */
    enum Tweak {
        /** crystal-aura++ at {@code risk} Aggressive (reserve 2). */
        AGGRESSIVE,
        /** Meteor's self-trap on, {@code top-mode} AntiFacePlace (the four blocks around the head), re-placing for good. */
        SELF_TRAP_FACE,
        /** Meteor's self-trap on, {@code top-mode} Full (the four blocks around the head and the one above), re-placing
         * for good. */
        SELF_TRAP_FULL,
        /** Surround's {@code center} set to Never. */
        NO_CENTER,
        /** Surround's {@code toggle-on-y-change} off. */
        NO_Y_TOGGLE,
        /** Surround's {@code double-height} on: obsidian at head height too, where the face-placed crystals go. */
        DOUBLE_HEIGHT,
        /** Once the hole breaks, the player holds forward through the gap. */
        WALK_OUT,
        /**
         * surround++ instead of Meteor's surround (and no self-trap): turned on by hand with the {@link Driver#BY_HAND}
         * driver, and by auto-pvp itself, through {@code shell-module} {@code xploits++}, with {@link Driver#AUTO_PVP}.
         */
        SURROUND_PP
    }

    record Variant(String name, int attackers, Driver driver, AuraConfig config, Set<Tweak> tweaks, City city, String summary) {
        /** The variants from before the honest city: the bench's own. */
        Variant(String name, int attackers, Driver driver, AuraConfig config, Set<Tweak> tweaks, String summary) {
            this(name, attackers, driver, config, tweaks, City.SURROUND_MINER, summary);
        }

        boolean has(Tweak tweak) {
            return tweaks.contains(tweak);
        }
    }

    /** Surround at head height too and never switched off by a change of height: the head-covering defence. */
    private static final Set<Tweak> DOUBLE = Set.of(Tweak.DOUBLE_HEIGHT, Tweak.NO_Y_TOGGLE);

    private static Set<Tweak> doubleAnd(Tweak tweak) {
        return Set.of(Tweak.DOUBLE_HEIGHT, Tweak.NO_Y_TOGGLE, tweak);
    }

    static final List<Variant> VARIANTS = List.of(
        new Variant("yours", 2, Driver.AUTO_PVP, AuraConfig.OWNER, Set.of(),
            "your setup: your surround as it comes, auto-pvp driving crystal-aura++ with your settings"),
        new Variant("yours-double", 2, Driver.AUTO_PVP, AuraConfig.OWNER, DOUBLE,
            "your setup, surround at head height too and never switched off by a change of height"),
        new Variant("max-face", 2, Driver.AUTO_PVP, AuraConfig.OWNER_MAX_FACE, DOUBLE,
            "yours-double with every range at its maximum and face-placing at any health"),
        new Variant("max-face-aggressive", 2, Driver.AUTO_PVP, AuraConfig.OWNER_MAX_FACE, doubleAnd(Tweak.AGGRESSIVE),
            "max-face with crystal-aura++ at risk Aggressive (reserve 2)"),
        new Variant("meteor-max-face", 2, Driver.METEOR_BY_HAND, AuraConfig.OWNER_MAX_FACE, DOUBLE,
            "max-face with Meteor's crystal-aura on by hand instead (no reserve)"),
        new Variant("competitive", 2, Driver.AUTO_PVP, AuraConfig.COMPETITIVE, DOUBLE,
            "yours-double with a competitive aura config instead of yours"),
        new Variant("meteor-competitive", 2, Driver.METEOR_BY_HAND, AuraConfig.COMPETITIVE, DOUBLE,
            "the competitive config on Meteor's crystal-aura by hand"),
        new Variant("yours-3", 3, Driver.AUTO_PVP, AuraConfig.OWNER, Set.of(),
            "your setup against 3 attackers"),
        new Variant("yours-trap-face", 2, Driver.AUTO_PVP, AuraConfig.OWNER, Set.of(Tweak.SELF_TRAP_FACE),
            "your setup plus Meteor's self-trap covering the four blocks around your head"),
        new Variant("yours-trap-full", 2, Driver.AUTO_PVP, AuraConfig.OWNER, Set.of(Tweak.SELF_TRAP_FULL),
            "your setup plus Meteor's self-trap covering around your head and above it"),
        new Variant("yours-3-double", 3, Driver.AUTO_PVP, AuraConfig.OWNER, DOUBLE,
            "yours-double against 3 attackers"),
        new Variant("yours-3-trap-full", 3, Driver.AUTO_PVP, AuraConfig.OWNER, Set.of(Tweak.SELF_TRAP_FULL),
            "yours-trap-full against 3 attackers"),
        new Variant("yours-walk", 2, Driver.AUTO_PVP, AuraConfig.OWNER, Set.of(Tweak.WALK_OUT),
            "your setup; once the hole breaks you hold forward through the gap"),
        new Variant("yours-walk-nocenter", 2, Driver.AUTO_PVP, AuraConfig.OWNER, Set.of(Tweak.WALK_OUT, Tweak.NO_CENTER),
            "yours-walk with surround's center set to Never"),
        new Variant("meteor-shell-2-43", 2, Driver.AUTO_PVP, AuraConfig.OWNER, Set.of(Tweak.SELF_TRAP_FACE), City.VANILLA,
            "the baseline: Meteor's surround plus self-trap around your head, auto-pvp driving crystal-aura++ with your settings;"
                + " 2 opponents"),
        new Variant("meteor-shell-2-0", 2, Driver.AUTO_PVP, AuraConfig.OWNER, Set.of(Tweak.SELF_TRAP_FACE), City.INSTANT,
            "the baseline against 2 opponents, the city breaking again at once"),
        new Variant("meteor-shell-3-43", 3, Driver.AUTO_PVP, AuraConfig.OWNER, Set.of(Tweak.SELF_TRAP_FACE), City.VANILLA,
            "the baseline against 3 opponents"),
        new Variant("meteor-shell-3-0", 3, Driver.AUTO_PVP, AuraConfig.OWNER, Set.of(Tweak.SELF_TRAP_FACE), City.INSTANT,
            "the baseline against 3 opponents, the city breaking again at once"),
        new Variant("meteor-shell-2-43-walk", 2, Driver.AUTO_PVP, AuraConfig.OWNER, Set.of(Tweak.SELF_TRAP_FACE, Tweak.WALK_OUT),
            City.VANILLA, "meteor-shell-2-43; once the hole breaks you hold forward through the gap"),
        new Variant("shell-hand-2-43", 2, Driver.BY_HAND, AuraConfig.OWNER, Set.of(Tweak.SURROUND_PP), City.VANILLA,
            "surround++ and crystal-aura++ on by hand, no auto-pvp; 2 opponents, an honest city at vanilla speed"),
        new Variant("shell-2-43", 2, Driver.AUTO_PVP, AuraConfig.OWNER, Set.of(Tweak.SURROUND_PP), City.VANILLA,
            "surround++ driven by auto-pvp (shell-module xploits++) instead of Meteor's surround and self-trap; 2 opponents"),
        new Variant("shell-2-0", 2, Driver.AUTO_PVP, AuraConfig.OWNER, Set.of(Tweak.SURROUND_PP), City.INSTANT,
            "surround++ against 2 opponents, the city breaking again at once"),
        new Variant("shell-3-43", 3, Driver.AUTO_PVP, AuraConfig.OWNER, Set.of(Tweak.SURROUND_PP), City.VANILLA,
            "surround++ against 3 opponents"),
        new Variant("shell-3-0", 3, Driver.AUTO_PVP, AuraConfig.OWNER, Set.of(Tweak.SURROUND_PP), City.INSTANT,
            "surround++ against 3 opponents, the city breaking again at once"),
        new Variant("shell-2-43-walk", 2, Driver.AUTO_PVP, AuraConfig.OWNER, Set.of(Tweak.SURROUND_PP, Tweak.WALK_OUT),
            City.VANILLA, "shell-2-43; once the hole breaks you hold forward through the gap"));

    private final Variant variant;
    /** Which run of the variant this is, from 1; the trace's name carries it when a variant runs more than once. */
    private final int run;
    private final int runs;
    private AutoPvpScene scene;
    private final List<LabHarass> harassment = new ArrayList<>();
    private final List<LabCrystalAttack> attacks = new ArrayList<>();
    private final LabHeadMiner headMiner = new LabHeadMiner();
    private LabCityAttack city;

    LabWorstCase(Variant variant, int run, int runs) {
        this.variant = variant;
        this.run = run;
        this.runs = runs;
    }

    /**
     * The variants {@code -Pbench.lab} names, in the order given ({@code all} is every one), each {@code -Pbench.lab.runs}
     * times in a row.
     */
    static List<LabWorstCase> selected(String names) {
        int runs = Math.max(1, Integer.getInteger(RUNS_PROPERTY, 1));
        List<Variant> chosen = new ArrayList<>();
        if (names.strip().equals("all")) {
            chosen.addAll(VARIANTS);
        } else {
            for (String name : names.split(",")) {
                String wanted = name.strip();
                if (wanted.isEmpty()) continue;
                chosen.add(VARIANTS.stream().filter(x -> x.name().equals(wanted)).findFirst()
                    .orElseThrow(() -> new AssertionError("no lab variant is called " + wanted)));
            }
        }
        List<LabWorstCase> result = new ArrayList<>();
        for (Variant v : chosen) {
            for (int r = 1; r <= runs; r++) result.add(new LabWorstCase(v, r, runs));
        }
        return result;
    }

    /** The trace's file name: the variant's, with the run when it runs more than once. */
    private String fileName() {
        return runs == 1 ? variant.name() : variant.name() + "-r" + run;
    }

    @Override
    public String name() {
        return variant.name();
    }

    @Override
    public Kind kind() {
        return Kind.CHECK;
    }

    @Override
    public int seconds() {
        return RUN_TICKS / 20 + 15;
    }

    @Override
    public void arrange(Bench bench) {
        scene = AutoPvpScene.arrange(bench, "balanced", CrystalModule.XPLOITS,
            variant.has(Tweak.SURROUND_PP) ? ShellModule.XPLOITS : ShellModule.METEOR);
        configure(bench);
        Surround surround = bench.meteor(Surround.class);
        if (variant.has(Tweak.NO_CENTER)) bench.setting(surround, "General", "center", Surround.Center.Never);
        if (variant.has(Tweak.NO_Y_TOGGLE)) bench.setting(surround, "Toggles", "toggle-on-y-change", false);
        if (variant.has(Tweak.DOUBLE_HEIGHT)) bench.setting(surround, "General", "double-height", true);
        SelfTrap selfTrap = bench.meteor(SelfTrap.class);
        if (variant.has(Tweak.SELF_TRAP_FACE) || variant.has(Tweak.SELF_TRAP_FULL)) {
            bench.setting(selfTrap, "General", "top-mode",
                variant.has(Tweak.SELF_TRAP_FULL) ? SelfTrap.TopMode.Full : SelfTrap.TopMode.AntiFacePlace);
            bench.setting(selfTrap, "General", "turn-off", false);
        }
        bench.meteor(FightRecorder.class);
        bench.arena().fightLoadout();
        bench.arena().give(2, Items.ENCHANTED_GOLDEN_APPLE, 64);
        // auto-pvp only turns auto-city on with a pickaxe in the hotbar; he had one.
        bench.arena().give(3, Items.NETHERITE_PICKAXE, 1);
        // Crying obsidian in the hotbar for every honest-city variant, whichever defence runs: the same hand for both.
        if (variant.city() != City.SURROUND_MINER) bench.arena().give(4, Items.CRYING_OBSIDIAN, 64);

        LabHarass harass = new LabHarass();
        harassment.add(harass);
        LabCrystalAttack second = new LabCrystalAttack(new Vec3i(0, 0, -3));
        attacks.add(second);
        Script first;
        if (variant.city() == City.SURROUND_MINER) {
            first = new SurroundMiner(new Vec3i(2, 0, 0), Direction.EAST);
        } else {
            city = new LabCityAttack(new Vec3i(2, 0, 0), variant.city() == City.VANILLA ? LabCityAttack.VANILLA_TICKS : 0);
            first = city;
        }
        bench.spawnForFight(new ComposedFight(first,
            List.of(new HoleWalls(Vec3i.ZERO), new SelfSurround(), new Autobreak())));
        bench.spawnMoreForFight(new ComposedFight(second,
            List.of(new SelfSurround(), new Autobreak(), harass, new SpotBlock(), headMiner)), "Sparring2");
        if (variant.attackers() >= 3) {
            LabCrystalAttack third = new LabCrystalAttack(new Vec3i(-3, 0, 0));
            attacks.add(third);
            bench.spawnMoreForFight(new ComposedFight(third,
                List.of(new SelfSurround(), new Autobreak(), new HoleFill())), "Sparring3");
        }
    }

    /** The variant's aura settings ({@link AuraConfig}) on whichever aura it runs, and its risk level. */
    private void configure(Bench bench) {
        boolean meteor = variant.driver() == Driver.METEOR_BY_HAND;
        Module aura = bench.fromClient(client -> meteor
            ? Modules.get().get(CrystalAura.class) : Modules.get().get(CrystalAuraPlusPlus.class));
        String placeWalls = meteor ? "walls-range" : "place-walls-range";
        String breakWalls = meteor ? "walls-range" : "break-walls-range";
        String facePlace = meteor ? "Face Place" : "Place";
        switch (variant.config()) {
            case DEFAULTS -> { }
            case OWNER, OWNER_MAX_FACE -> {
                bench.setting(aura, "General", "min-damage", 0.9);
                bench.setting(aura, "General", "max-damage", 36.0);
                bench.setting(aura, "General", "anti-suicide", false);
                bench.setting(aura, "Place", "place-range", 6.0);
                bench.setting(aura, "Break", "break-range", 6.0);
                bench.setting(aura, "Pause", "pause-health", 1.0);
                if (variant.config() == AuraConfig.OWNER_MAX_FACE) {
                    bench.setting(aura, "General", "target-range", 16.0);
                    bench.setting(aura, "Place", placeWalls, 6.0);
                    bench.setting(aura, "Break", breakWalls, 6.0);
                    bench.setting(aura, facePlace, "face-place-health", 36.0);
                }
            }
            case COMPETITIVE -> {
                bench.setting(aura, "General", "min-damage", 6.0);
                bench.setting(aura, "General", "max-damage", 10.0);
                bench.setting(aura, "General", "anti-suicide", true);
                bench.setting(aura, "Place", "place-range", 5.0);
                bench.setting(aura, "Break", "break-range", 5.0);
                bench.setting(aura, "Place", placeWalls, 3.5);
                bench.setting(aura, "Break", breakWalls, 3.5);
                bench.setting(aura, facePlace, "face-place-health", 5.0);
            }
        }
        if (variant.has(Tweak.AGGRESSIVE)) bench.setting(aura, "Safety", "risk", RiskLevel.AGGRESSIVE);
    }

    /** One tick of what we see of ourselves, client side. */
    private record Self(boolean dead, double health, boolean inHole, boolean inWeb, boolean surroundOn, double x, double z,
                        boolean yChanged, boolean onGround) {
    }

    /** One second of the trace. */
    private record Row(int second, double health, int ourPops, int theirPops, int theyAlive, String hole, boolean web,
                       String surround, double moved, int pulledBack, String autoPvp, boolean plusPlusOn, int placed,
                       String aura, int holding, int surroundOffs, int yChangedTicks, int onGroundTicks) {
    }

    @Override
    public Metrics act(Bench bench) {
        // His own surround first, as he had it (and the self-trap, where the variant has one), then the aura's driver.
        if (!variant.has(Tweak.SURROUND_PP)) {
            bench.onClient(client -> Modules.get().get(Surround.class).enable());
        } else if (variant.driver() != Driver.AUTO_PVP) {
            // auto-pvp turns surround++ on itself with shell-module xploits++; by hand otherwise.
            bench.onClient(client -> Modules.get().get(SurroundPlusPlus.class).enable());
        }
        if (variant.has(Tweak.SELF_TRAP_FACE) || variant.has(Tweak.SELF_TRAP_FULL)) {
            bench.onClient(client -> Modules.get().get(SelfTrap.class).enable());
        }
        switch (variant.driver()) {
            case AUTO_PVP -> scene.start(bench, true);
            case BY_HAND -> bench.start(true, CrystalAuraPlusPlus.class);
            case METEOR_BY_HAND -> bench.start(true, CrystalAura.class);
        }

        Self start = self(bench);
        List<Sparring> them = bench.sparrings();
        GappleSchedule[] theirGapples = new GappleSchedule[them.size()];
        int[] theirLastPops = new int[them.size()];
        for (int i = 0; i < them.size(); i++) theirGapples[i] = new GappleSchedule();
        int placedAtStart = bench.fromClient(client -> PlacementCounter.get().sent());
        int blocksAtStart = bench.fromClient(LabWorstCase::blocks);

        List<Row> rows = new ArrayList<>();
        int deathTick = -1;
        int breachTick = -1;
        int walkTick = -1;
        int pulledBackTotal = 0;
        int webTicks = 0;
        int brokenTicks = 0;
        int brokenWithSurroundOn = 0;
        double maxMoved = 0;
        boolean wasInHole = start.inHole();
        double lastMoved = 0;
        // The second being built.
        double secondMoved = 0;
        int secondPulled = 0;
        boolean secondWeb = false;
        int secondBrokenTicks = 0;
        int secondHolding = 0;
        int secondSurroundOffs = 0;
        int secondYChanged = 0;
        int secondOnGround = 0;
        int surroundOffsTotal = 0;
        boolean surroundWasOn = start.surroundOn();
        Map<String, Integer> secondReasons = new HashMap<>();
        int placedBefore = placedAtStart;
        int tick = 0;
        String end = "time up";
        while (tick < RUN_TICKS) {
            bench.ticks(1);
            tick++;
            Self now = self(bench);
            if (now.dead()) {
                deathTick = tick;
                end = "you died";
            }
            double moved = Math.hypot(now.x() - start.x(), now.z() - start.z());
            if (walkTick >= 0 && moved < lastMoved - PULLED_BACK) {
                secondPulled++;
                pulledBackTotal++;
            }
            lastMoved = moved;
            maxMoved = Math.max(maxMoved, moved);
            secondMoved = Math.max(secondMoved, moved);
            if (now.inWeb()) {
                webTicks++;
                secondWeb = true;
            }
            if (surroundWasOn && !now.surroundOn()) {
                secondSurroundOffs++;
                surroundOffsTotal++;
            }
            surroundWasOn = now.surroundOn();
            if (now.yChanged()) secondYChanged++;
            if (now.onGround()) secondOnGround++;
            if (!now.inHole()) {
                brokenTicks++;
                secondBrokenTicks++;
                if (now.surroundOn()) brokenWithSurroundOn++;
            }
            // What crystal-aura++ decided this tick, or why it did nothing, and whether its reserve held it back.
            String decided = bench.fromClient(client -> {
                CrystalAuraPlusPlus aura = Modules.get().get(CrystalAuraPlusPlus.class);
                if (!aura.isActive()) return "off";
                Decision d = aura.lastDecision();
                return d == null ? "none" : d.kind().name().toLowerCase(Locale.ROOT) + " " + d.reason().name().toLowerCase(Locale.ROOT);
            });
            secondReasons.merge(decided, 1, Integer::sum);
            if (bench.fromClient(client -> Modules.get().get(CrystalAuraPlusPlus.class).holding())) secondHolding++;
            if (breachTick < 0 && wasInHole && !now.inHole()) breachTick = tick;
            wasInHole = now.inHole();
            if (variant.has(Tweak.WALK_OUT) && breachTick >= 0 && walkTick < 0 && !now.dead()) {
                // Face the gap (east) and hold forward, as a person trying to get out would.
                bench.command("rotate " + Bench.PLAYER + " -90 0");
                bench.holdKey(options -> options.forwardKey, true);
                walkTick = tick;
            }

            // They eat after every pop, as we would have to.
            int alive = 0;
            for (int i = 0; i < them.size(); i++) {
                Sparring s = them.get(i);
                int pops = bench.fromServer(srv -> s.stats(-1).pops());
                if (pops > theirLastPops[i]) {
                    theirLastPops[i] = pops;
                    theirGapples[i].pop(bench.ticksUsed());
                }
                boolean dead = bench.fromServer(srv -> s.isDead());
                if (dead) theirGapples[i].death();
                else alive++;
                if (theirGapples[i].due(bench.ticksUsed())) bench.onServer(srv -> GappleEffects.apply(s));
            }

            if (tick % 20 == 0 || deathTick >= 0 || alive == 0) {
                int ourPops = bench.fromClient(client -> Modules.get().get(FightRecorder.class).live()
                    .map(FightTracker.LiveFight::yourPops).orElse(0));
                int theirPops = Arrays.stream(theirLastPops).sum();
                int placed = bench.fromClient(client -> PlacementCounter.get().sent());
                String autoPvp = variant.driver() == Driver.AUTO_PVP
                    ? bench.fromClient(client -> scene.autoPvp.currentPlan().map(LabWorstCase::describe).orElse("no plan"))
                    : "off";
                boolean plusPlusOn = bench.fromClient(client -> Modules.get().get(CrystalAuraPlusPlus.class).isActive());
                String hole = secondBrokenTicks == 0 ? "whole" : secondBrokenTicks >= 20 ? "broken" : "broken " + secondBrokenTicks + "/20";
                String aura = secondReasons.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(2).map(e -> e.getKey() + " ×" + e.getValue()).reduce((a, b) -> a + ", " + b).orElse("");
                String surroundColumn = variant.has(Tweak.SURROUND_PP)
                    ? bench.fromClient(client -> Modules.get().get(SurroundPlusPlus.class).status()
                        .map(st -> (st.headCovered() ? "head covered" : "head open") + ", " + st.openThreats() + " open, "
                            + st.underAttack() + " mined")
                        .orElse("off"))
                    : now.surroundOn() ? "on" : "off";
                rows.add(new Row((tick + 19) / 20, now.health(), ourPops, theirPops, alive, hole, secondWeb,
                    surroundColumn, secondMoved, secondPulled, autoPvp, plusPlusOn, placed - placedBefore,
                    aura, secondHolding, secondSurroundOffs, secondYChanged, secondOnGround));
                placedBefore = placed;
                secondReasons.clear();
                secondHolding = 0;
                secondSurroundOffs = 0;
                secondYChanged = 0;
                secondOnGround = 0;
                secondMoved = 0;
                secondPulled = 0;
                secondWeb = false;
                secondBrokenTicks = 0;
            }
            if (deathTick >= 0) break;
            if (alive == 0) {
                end = "they all died";
                break;
            }
        }
        if (walkTick >= 0) bench.holdKey(options -> options.forwardKey, false);

        int blocksUsed = blocksAtStart - bench.fromClient(LabWorstCase::blocks);
        int ourPopsEnd = rows.isEmpty() ? 0 : rows.getLast().ourPops();
        int theirPopsEnd = Arrays.stream(theirLastPops).sum();
        int headCrystals = attacks.stream().mapToInt(LabCrystalAttack::atHead).sum();
        String shellCounts = variant.has(Tweak.SURROUND_PP) ? bench.fromClient(client -> {
            SurroundPlusPlus shell = Modules.get().get(SurroundPlusPlus.class);
            return String.format(Locale.ROOT, "- surround++: %d obsidian and %d crying obsidian placed, %d crystal(s) broken,"
                    + " centred %d time(s), %d walk(s) to a hole, %d aura override(s); the decision took %d us a tick on"
                    + " average, %d at worst.%n", shell.placedObsidian(), shell.placedCrying(), shell.crystalsBroken(),
                shell.centred(), shell.walks(), shell.auraOverrides(), shell.averageMicros(), shell.worstMicros());
        }) : "";
        String conflicts = variant.has(Tweak.SURROUND_PP)
            ? String.valueOf((int) bench.<Integer, RuntimeException>fromClient(client -> Modules.get().get(SurroundPlusPlus.class).auraOverrides())) : "-";
        bench.finish();
        List<Path> fights = bench.newFightFiles();
        for (Sparring s : them) Fights.logCounters(variant.name() + " " + s.getGameProfile().name(), 1, s.script());
        int webs = harassment.stream().mapToInt(LabHarass::webs).sum();
        int roofs = harassment.stream().mapToInt(LabHarass::roofs).sum();
        write(rows, end, tick, deathTick, breachTick, walkTick, maxMoved, pulledBackTotal, webTicks, brokenTicks,
            brokenWithSurroundOn, surroundOffsTotal, webs, roofs, fights, blocksUsed, headCrystals, shellCounts);
        summarize(end, tick, ourPopsEnd, theirPopsEnd, blocksUsed, headCrystals, pulledBackTotal, conflicts);
        LOG.info("[bench] lab {}: {} after {} s; hole broken at {}; moved at most {} blocks; pulled back {} time(s)",
            variant.name(), end, String.format(Locale.ROOT, "%.1f", tick / 20.0),
            breachTick < 0 ? "never" : String.format(Locale.ROOT, "%.1f s", breachTick / 20.0),
            String.format(Locale.ROOT, "%.2f", maxMoved), pulledBackTotal);
        return Metrics.none();
    }

    private static String describe(Plan plan) {
        return plan.state().name().toLowerCase(Locale.ROOT) + " / " + plan.posture().name().toLowerCase(Locale.ROOT);
    }

    private static Self self(Bench bench) {
        Self self = bench.fromClient(LabWorstCase::read);
        if (self == null) throw new BenchException("the client has no player");
        return self;
    }

    private static Self read(MinecraftClient client) {
        if (client.player == null || client.world == null) return null;
        BlockPos feet = client.player.getBlockPos();
        boolean web = client.world.getBlockState(feet).isOf(Blocks.COBWEB) || client.world.getBlockState(feet.up()).isOf(Blocks.COBWEB);
        boolean dead = client.player.isDead();
        // The same comparison Surround's toggle-on-y-change makes (Surround.java:310): last tick's Y against this one's.
        return new Self(dead, dead ? 0 : client.player.getHealth() + client.player.getAbsorptionAmount(),
            PlayerUtils.isInHole(false), web, Modules.get().get(Surround.class).isActive(),
            client.player.getX(), client.player.getZ(), client.player.lastY != client.player.getY(), client.player.isOnGround());
    }

    private void write(List<Row> rows, String end, int ticks, int deathTick, int breachTick, int walkTick, double maxMoved,
                       int pulledBack, int webTicks, int brokenTicks, int brokenWithSurroundOn, int surroundOffs, int webs,
                       int roofs, List<Path> fights, int blocksUsed, int headCrystals, String shellCounts) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(variant.name()).append("\n\n").append(variant.summary()).append(".\n\n");
        md.append("Aura settings: ").append(variant.config().description).append(
            variant.has(Tweak.AGGRESSIVE) ? "; risk Aggressive" : "").append(".\n\n");
        md.append("First opponent's city: ").append(variant.city().description).append(".\n\n");
        md.append("| s | health | your pops | their pops | they alive | your hole | web | surround (or surround++) | moved | pulled back"
            + " | auto-pvp | ++ on | placed | ++ decided (ticks) | ++ held by reserve | surround went off | y changed | on ground |\n"
            + "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            md.append(String.format(Locale.ROOT, "| %d | %.1f | %d | %d | %d | %s | %s | %s | %.2f | %d | %s | %s | %d | %s | %d | %d | %d | %d |%n",
                r.second(), r.health(), r.ourPops(), r.theirPops(), r.theyAlive(), r.hole(), r.web() ? "yes" : "", r.surround(),
                r.moved(), r.pulledBack(), r.autoPvp(), r.plusPlusOn() ? "yes" : "no", r.placed(), r.aura(), r.holding(),
                r.surroundOffs(), r.yChangedTicks(), r.onGroundTicks()));
        }
        md.append("\n**End:** ").append(end).append(String.format(Locale.ROOT, " after %.1f s.", ticks / 20.0)).append("\n\n");
        md.append(String.format(Locale.ROOT, "- Hole first broken at: %s.%n",
            breachTick < 0 ? "never" : String.format(Locale.ROOT, "%.1f s", breachTick / 20.0)));
        md.append(String.format(Locale.ROOT, "- Ticks with the hole broken: %d, %d of them with surround still on."
            + " Surround went off %d time(s).%n", brokenTicks, brokenWithSurroundOn, surroundOffs));
        md.append(String.format(Locale.ROOT, "- Ticks in a cobweb: %d. Webs placed on you: %d; roof blocks: %d.%n", webTicks, webs, roofs));
        if (variant.has(Tweak.WALK_OUT)) {
            md.append(String.format(Locale.ROOT, "- Walking out from: %s. Got at most %.2f blocks from the start;"
                    + " pulled back %d time(s).%n",
                walkTick < 0 ? "never (the hole never broke)" : String.format(Locale.ROOT, "%.1f s", walkTick / 20.0), maxMoved, pulledBack));
        } else {
            md.append(String.format(Locale.ROOT, "- Moved at most %.2f blocks from the start.%n", maxMoved));
        }
        md.append("- Death: ").append(deathTick < 0 ? "no" : String.format(Locale.ROOT, "at %.1f s", deathTick / 20.0)).append(".\n");
        for (int i = 0; i < attacks.size(); i++) {
            md.append("- Crystal attacker ").append(i + 2).append(": ").append(attacks.get(i).counts()).append(".\n");
        }
        md.append("- Head blocks of yours it mined: ").append(headMiner.mined()).append(".\n");
        if (city != null) md.append("- City attacker: ").append(city.counts()).append(".\n");
        md.append(String.format(Locale.ROOT, "- Obsidian and crying obsidian used: %d. Crystals put at your head: %d.%n",
            blocksUsed, headCrystals));
        md.append(shellCounts);
        md.append("- Fight recorder file(s): ").append(fights.isEmpty() ? "none"
            : String.join(", ", fights.stream().map(p -> p.getFileName().toString()).toList())).append(".\n");
        try {
            Path out = Path.of(System.getProperty(OUT_PROPERTY, "build/bench-lab"));
            Files.createDirectories(out);
            Files.writeString(out.resolve(fileName() + ".md"), md.toString(), StandardCharsets.UTF_8);
            // The game's run folder is emptied on every launch: the fight file goes next to its trace.
            for (int i = 0; i < fights.size(); i++) {
                Files.copy(fights.get(i), out.resolve(fileName() + "-fight" + (i == 0 ? "" : "-" + (i + 1)) + ".json"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new BenchException("the lab trace could not be written (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Obsidian and crying obsidian in our whole inventory. Client thread. */
    private static int blocks(MinecraftClient client) {
        if (client.player == null) return 0;
        return client.player.getInventory().count(Items.OBSIDIAN) + client.player.getInventory().count(Items.CRYING_OBSIDIAN);
    }

    /**
     * One line per run in {@value #SUMMARY}, next to the traces, so the acceptance can be read at a glance (spec §9). The
     * header is written with the first line. {@code conflicts} is what surround++ counts (its aura overrides), "-" without it.
     */
    private void summarize(String end, int ticks, int ourPops, int theirPops, int blocksUsed, int headCrystals, int pulledBack,
                           String conflicts) {
        try {
            Path out = Path.of(System.getProperty(OUT_PROPERTY, "build/bench-lab"));
            Files.createDirectories(out);
            Path summary = out.resolve(SUMMARY);
            StringBuilder line = new StringBuilder();
            if (!Files.exists(summary)) {
                line.append("| variant | run | end | s | your pops | their pops | blocks used | head crystals | pulled back"
                    + " | conflicts |\n|---|---|---|---|---|---|---|---|---|---|\n");
            }
            line.append(String.format(Locale.ROOT, "| %s | %d | %s | %.1f | %d | %d | %d | %d | %d | %s |%n", variant.name(), run,
                end, ticks / 20.0, ourPops, theirPops, blocksUsed, headCrystals, pulledBack, conflicts));
            Files.writeString(summary, line.toString(), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new BenchException("the lab summary could not be written (" + e.getClass().getSimpleName() + ")");
        }
    }
}
