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
import com.xploits.pvp.shell.core.ShellSnapshot;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.CrystalAura;
import meteordevelopment.meteorclient.systems.modules.combat.SelfTrap;
import meteordevelopment.meteorclient.systems.modules.combat.Surround;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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
 * <p>Every run also watches the client against the server ({@link SyncWatch}): ghost blocks, inventory counts, the
 * cursor and the selected slot, in one line of the trace and one row of {@value #SYNC}.
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
    /** The one-line-per-run table of the client against the server ({@link SyncWatch}), next to the traces. */
    static final String SYNC = "sync.md";
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
        SyncWatch sync = new SyncWatch(bench);
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
            sync.tick(tick);
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
        // After the fight's own counts, so the wait does not add to them.
        sync.finalCheck();
        bench.finish();
        List<Path> fights = bench.newFightFiles();
        for (Sparring s : them) Fights.logCounters(variant.name() + " " + s.getGameProfile().name(), 1, s.script());
        int webs = harassment.stream().mapToInt(LabHarass::webs).sum();
        int roofs = harassment.stream().mapToInt(LabHarass::roofs).sum();
        write(rows, end, tick, deathTick, breachTick, walkTick, maxMoved, pulledBackTotal, webTicks, brokenTicks,
            brokenWithSurroundOn, surroundOffsTotal, webs, roofs, fights, blocksUsed, headCrystals, shellCounts, sync.traceLine());
        summarize(end, tick, ourPopsEnd, theirPopsEnd, blocksUsed, headCrystals, pulledBackTotal, conflicts);
        recordSync(sync);
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
                       int roofs, List<Path> fights, int blocksUsed, int headCrystals, String shellCounts, String syncLine) {
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
        md.append(syncLine);
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

    /** One line per run in {@value #SYNC}, next to the traces; the header is written with the first line. */
    private void recordSync(SyncWatch sync) {
        try {
            Path out = Path.of(System.getProperty(OUT_PROPERTY, "build/bench-lab"));
            Files.createDirectories(out);
            Path table = out.resolve(SYNC);
            String line = (Files.exists(table) ? "" : SyncWatch.HEADER) + sync.row(variant.name(), run);
            Files.writeString(table, line, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new BenchException("the lab sync table could not be written (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** How often the client is compared with the server: once a second. */
    private static final int SYNC_EVERY = 20;
    /**
     * The tick within each second the comparison is made at. The opponents' own block changes (webs, spot blocks, hole
     * fills, crystal attacks) fall every 10, 20 or 40 ticks from T0, just before a comparison made on a multiple of 20,
     * which would catch every one of them on its way to the client; five ticks later they have arrived.
     */
    private static final int SYNC_PHASE = 5;
    /** The items whose counts are compared, in this order in every count array. */
    private static final List<Item> SYNC_ITEMS = List.of(Items.OBSIDIAN, Items.CRYING_OBSIDIAN, Items.END_CRYSTAL,
        Items.TOTEM_OF_UNDYING);
    private static final List<String> SYNC_ITEM_NAMES = List.of("obsidian", "crying obsidian", "end crystals", "totems");
    /** The box surround++ reads around our feet block, as offsets, in the order every box array keeps. */
    private static final List<Vec3i> SYNC_BOX = syncBox();

    private static List<Vec3i> syncBox() {
        List<Vec3i> box = new ArrayList<>();
        for (int dy = -ShellSnapshot.BELOW; dy <= ShellSnapshot.ABOVE; dy++) {
            for (int dx = -ShellSnapshot.RADIUS; dx <= ShellSnapshot.RADIUS; dx++) {
                for (int dz = -ShellSnapshot.RADIUS; dz <= ShellSnapshot.RADIUS; dz++) box.add(new Vec3i(dx, dy, dz));
            }
        }
        return List.copyOf(box);
    }

    /** One side's view at one comparison: the box's states, the items' counts, the cursor's item ("" if empty), the slot. */
    private record SyncSide(BlockState[] box, int[] counts, String cursor, int slot) {
    }

    /** The client's view and the feet block its box is read around (kept in memory only). */
    private record ClientView(BlockPos feet, SyncSide side) {
    }

    /** A cell the two sides disagree on: what the client shows and what the server has. */
    private record Mismatch(BlockState client, BlockState server) {
        /** "client-only" (a block the server does not have), "server-only", or "different" (two different blocks). */
        String kind() {
            if (server.isAir()) return "client-only";
            if (client.isAir()) return "server-only";
            return "different";
        }

        String describe() {
            return "client " + blockName(client) + ", server " + blockName(server);
        }
    }

    /**
     * What one comparison found: the ghosts (cells with the same pair as at the comparison before), those of them whose
     * pair held at every tick in between, each item's difference (client minus server) where it differed at the
     * comparison before too (0 otherwise), both cursors and both slots, and whether the slots differed at both comparisons.
     */
    private record Sample(Map<BlockPos, Mismatch> ghosts, Set<BlockPos> steady, int[] difference, String clientCursor,
                          String serverCursor, int clientSlot, int serverSlot, boolean slotMismatch) {
    }

    private static String blockName(BlockState state) {
        return Registries.BLOCK.getId(state.getBlock()).getPath();
    }

    private static String itemName(Item item) {
        return Registries.ITEM.getId(item).getPath();
    }

    /**
     * Task 10b, the owner's request (2026-09-30): surround++ must leave no ghost block, lose no block from the inventory by
     * mistake and never leave an item on the cursor. Once a second of the fight ({@value #SYNC_EVERY} ticks) it compares
     * the client with the server: the block states in the box surround++ reads around our feet block
     * ({@link ShellSnapshot#RADIUS} each way across, {@link ShellSnapshot#BELOW} below, {@link ShellSnapshot#ABOVE} above),
     * the counts of obsidian, crying obsidian, end crystals and totems in our whole inventory (offhand and armour
     * included), the cursor and the selected hotbar slot; once more {@value #SYNC_EVERY} ticks after the fight. The client
     * runs a little behind the server, so one mismatch is no ghost: a cell counts only when it mismatches with the same
     * pair of states at two comparisons running, an item's count or the slot only when they differ at two running.
     *
     * <p>Between two comparisons the cells that mismatched are read again every tick on both sides. A ghost whose pair held
     * at every one of those ticks is a steady ghost, the sure kind: a cell mined and refilled every tick can show the same
     * pair twice, a second apart, without ever being stuck.
     *
     * <p>Absolute positions are compared in memory only: nothing written carries one, nor our player's name.
     */
    private static final class SyncWatch {
        static final String HEADER = "| variant | run | ghost cells (client-only / server-only / different) | largest per sample"
            + " | steady ghost cells | final ghost cells | largest inventory difference, client minus server (obsidian,"
            + " crying, crystals, totems) | cursor samples non-empty | slot mismatches | slot back at T0 |\n"
            + "|---|---|---|---|---|---|---|---|---|---|\n";

        private final Bench bench;
        /** Our player's name, to find it on the server; never written. */
        private final String name;
        private final int t0ClientSlot;
        private final int t0ServerSlot;
        private int samples;
        /** The cells that mismatched at the last comparison, with their pair. */
        private Map<BlockPos, Mismatch> lastMismatches = Map.of();
        /** Of those, the cells whose pair changed at some tick since. */
        private final Set<BlockPos> changedSince = new HashSet<>();
        private int[] lastDifference = new int[SYNC_ITEMS.size()];
        private boolean lastSlotsDiffered;
        // The fight's totals.
        private final Map<BlockPos, Mismatch> ghosts = new HashMap<>();
        private final Set<BlockPos> steadyGhosts = new HashSet<>();
        private int mostAtOneSample;
        private final int[] largestDifference = new int[SYNC_ITEMS.size()];
        private int desyncSamples;
        private int cursorSamples;
        private int clientCursorSamples;
        private int serverCursorSamples;
        private final Set<String> cursorItems = new TreeSet<>();
        private int slotMismatches;
        /** The comparison after the fight; null before it. */
        private Sample after;

        /** At T0: the slot each side holds. */
        SyncWatch(Bench bench) {
            this.bench = bench;
            this.name = bench.player();
            this.t0ClientSlot = bench.fromClient(client -> {
                if (client.player == null) throw new BenchException("the client has no player");
                return client.player.getInventory().getSelectedSlot();
            });
            this.t0ServerSlot = bench.fromServer(srv -> Arena.player(srv, name).getInventory().getSelectedSlot());
        }

        /** After every tick of the fight ({@code tick} counts from 1 at T0): a comparison, or the cells to watch. */
        void tick(int tick) {
            if (tick % SYNC_EVERY != SYNC_PHASE) {
                track();
                return;
            }
            Sample now = compare();
            samples++;
            now.ghosts().forEach(ghosts::putIfAbsent);
            steadyGhosts.addAll(now.steady());
            mostAtOneSample = Math.max(mostAtOneSample, now.ghosts().size());
            boolean desync = false;
            for (int k = 0; k < largestDifference.length; k++) {
                int d = now.difference()[k];
                if (d == 0) continue;
                desync = true;
                if (Math.abs(d) > Math.abs(largestDifference[k])) largestDifference[k] = d;
            }
            if (desync) desyncSamples++;
            if (!now.clientCursor().isEmpty() || !now.serverCursor().isEmpty()) cursorSamples++;
            if (!now.clientCursor().isEmpty()) {
                clientCursorSamples++;
                cursorItems.add(now.clientCursor());
            }
            if (!now.serverCursor().isEmpty()) {
                serverCursorSamples++;
                cursorItems.add(now.serverCursor());
            }
            if (now.slotMismatch()) slotMismatches++;
        }

        /** After the fight: {@value #SYNC_EVERY} more ticks, the cells still watched, then one last comparison. */
        void finalCheck() {
            for (int i = 0; i < SYNC_EVERY; i++) {
                bench.ticks(1);
                track();
            }
            after = compare();
        }

        /** Reads both sides and compares them; what was seen becomes the comparison before for the next one. */
        private Sample compare() {
            ClientView client = bench.fromClient(c -> {
                if (c.player == null || c.world == null) throw new BenchException("the client has no player");
                BlockPos feet = c.player.getBlockPos();
                return new ClientView(feet, side(c.player, c.world, feet));
            });
            SyncSide server = bench.fromServer(srv -> {
                ServerPlayerEntity player = Arena.player(srv, name);
                return side(player, player.getEntityWorld(), client.feet());
            });
            Map<BlockPos, Mismatch> mismatches = new HashMap<>();
            for (int i = 0; i < SYNC_BOX.size(); i++) {
                BlockState c = client.side().box()[i];
                BlockState s = server.box()[i];
                if (!c.equals(s)) mismatches.put(client.feet().add(SYNC_BOX.get(i)), new Mismatch(c, s));
            }
            Map<BlockPos, Mismatch> ghostsNow = new HashMap<>();
            Set<BlockPos> steadyNow = new HashSet<>();
            mismatches.forEach((cell, pair) -> {
                if (!pair.equals(lastMismatches.get(cell))) return;
                ghostsNow.put(cell, pair);
                if (!changedSince.contains(cell)) steadyNow.add(cell);
            });
            int[] difference = new int[SYNC_ITEMS.size()];
            int[] confirmed = new int[SYNC_ITEMS.size()];
            for (int k = 0; k < difference.length; k++) {
                difference[k] = client.side().counts()[k] - server.counts()[k];
                if (difference[k] != 0 && lastDifference[k] != 0) confirmed[k] = difference[k];
            }
            boolean slotsDiffer = client.side().slot() != server.slot();
            boolean slotMismatch = slotsDiffer && lastSlotsDiffered;
            lastMismatches = mismatches;
            changedSince.clear();
            lastDifference = difference;
            lastSlotsDiffered = slotsDiffer;
            return new Sample(ghostsNow, steadyNow, confirmed, client.side().cursor(), server.cursor(), client.side().slot(),
                server.slot(), slotMismatch);
        }

        /** A tick between two comparisons: the cells that mismatched at the last one, read again on both sides. */
        private void track() {
            List<BlockPos> cells = lastMismatches.keySet().stream().filter(cell -> !changedSince.contains(cell)).toList();
            if (cells.isEmpty()) return;
            List<BlockState> client = bench.fromClient(c -> {
                if (c.world == null) throw new BenchException("the client has no world");
                return cells.stream().map(c.world::getBlockState).toList();
            });
            List<BlockState> server = bench.fromServer(srv -> {
                World world = Arena.player(srv, name).getEntityWorld();
                return cells.stream().map(world::getBlockState).toList();
            });
            for (int i = 0; i < cells.size(); i++) {
                if (!new Mismatch(client.get(i), server.get(i)).equals(lastMismatches.get(cells.get(i)))) changedSince.add(cells.get(i));
            }
        }

        /** One side's view, on that side's own thread. */
        private static SyncSide side(PlayerEntity player, World world, BlockPos feet) {
            BlockState[] box = new BlockState[SYNC_BOX.size()];
            for (int i = 0; i < box.length; i++) box[i] = world.getBlockState(feet.add(SYNC_BOX.get(i)));
            int[] counts = new int[SYNC_ITEMS.size()];
            PlayerInventory inventory = player.getInventory();
            // size() is the 36 main slots plus the equipment ones, armour and offhand (EQUIPMENT_SLOTS, 1.21.11).
            for (int slot = 0; slot < inventory.size(); slot++) {
                ItemStack stack = inventory.getStack(slot);
                int k = SYNC_ITEMS.indexOf(stack.getItem());
                if (k >= 0) counts[k] += stack.getCount();
            }
            ItemStack cursor = player.currentScreenHandler.getCursorStack();
            return new SyncSide(box, counts, cursor.isEmpty() ? "" : itemName(cursor.getItem()), inventory.getSelectedSlot());
        }

        /** "3 (2 client-only, 1 server-only, 0 different)". */
        private static String split(Map<BlockPos, Mismatch> cells, String clientOnly, String serverOnly, String different) {
            int[] kinds = new int[3];
            for (Mismatch m : cells.values()) {
                switch (m.kind()) {
                    case "client-only" -> kinds[0]++;
                    case "server-only" -> kinds[1]++;
                    default -> kinds[2]++;
                }
            }
            return cells.size() + " (" + kinds[0] + clientOnly + kinds[1] + serverOnly + kinds[2] + different + ")";
        }

        /** The pairs the ghost cells had, most frequent first: "client obsidian, server air ×2". */
        private static String pairs(Map<BlockPos, Mismatch> cells) {
            Map<String, Integer> byPair = new TreeMap<>();
            for (Mismatch m : cells.values()) byPair.merge(m.describe(), 1, Integer::sum);
            return byPair.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> e.getKey() + " ×" + e.getValue()).reduce((a, b) -> a + "; " + b).orElse("");
        }

        private static String differences(int[] difference) {
            List<String> parts = new ArrayList<>();
            for (int k = 0; k < difference.length; k++) parts.add(SYNC_ITEM_NAMES.get(k) + " " + signed(difference[k]));
            return String.join(", ", parts);
        }

        private static String signed(int value) {
            return value > 0 ? "+" + value : String.valueOf(value);
        }

        private boolean backAtT0() {
            return after.clientSlot() == t0ClientSlot && after.serverSlot() == t0ServerSlot;
        }

        private String slotAtT0Words() {
            if (backAtT0()) return "yes (slot " + t0ClientSlot + ")";
            return "no (slot " + t0ClientSlot + " at T0, " + after.clientSlot() + " at the end"
                + (after.clientSlot() == after.serverSlot() ? "" : ", " + after.serverSlot() + " on the server") + ")";
        }

        /** The trace's line. */
        String traceLine() {
            StringBuilder line = new StringBuilder("- Sync: ").append(samples)
                .append(" comparison(s) of the client with the server during the fight, one a second, and one ")
                .append(SYNC_EVERY).append(" ticks after it. Ghost cells: ")
                .append(split(ghosts, " client-only, ", " server-only, ", " with two different blocks"))
                .append(", at most ").append(mostAtOneSample).append(" at one comparison, ").append(steadyGhosts.size())
                .append(" of them the same at every tick in between");
            if (!ghosts.isEmpty()) line.append(" (").append(pairs(ghosts)).append(")");
            line.append(". Inventory, the largest difference (client minus server) of an item whose counts differed at two"
                + " comparisons running: ").append(differences(largestDifference)).append(" (").append(desyncSamples)
                .append(" comparison(s) with any). Cursor not empty at ").append(cursorSamples).append(" comparison(s) (")
                .append(clientCursorSamples).append(" on the client, ").append(serverCursorSamples).append(" on the server)");
            if (!cursorItems.isEmpty()) line.append(": ").append(String.join(", ", cursorItems));
            line.append(". Selected slot different on the two sides at two comparisons running: ").append(slotMismatches)
                .append(" time(s). After the fight: ghost cells ")
                .append(split(after.ghosts(), " client-only, ", " server-only, ", " with two different blocks"))
                .append(", ").append(after.steady().size()).append(" steady");
            if (!after.ghosts().isEmpty()) line.append(" (").append(pairs(after.ghosts())).append(")");
            line.append("; inventory ").append(differences(after.difference())).append("; cursor ")
                .append(after.clientCursor().isEmpty() && after.serverCursor().isEmpty() ? "empty on both sides"
                    : "holding " + (after.clientCursor().isEmpty() ? "nothing" : after.clientCursor()) + " on the client, "
                    + (after.serverCursor().isEmpty() ? "nothing" : after.serverCursor()) + " on the server")
                .append("; selected slot ").append(after.clientSlot() == after.serverSlot() ? "the same on both sides"
                    : "different on the two sides").append(", back at the one held at T0: ").append(slotAtT0Words())
                .append(".\n");
            return line.toString();
        }

        /** The run's row in {@value #SYNC}. */
        String row(String variant, int run) {
            String cursor = cursorSamples + (cursorItems.isEmpty() ? "" : " (" + String.join(", ", cursorItems) + ")");
            List<String> largest = new ArrayList<>();
            for (int d : largestDifference) largest.add(signed(d));
            return String.format(Locale.ROOT, "| %s | %d | %s | %d | %d | %s | %s | %s | %d | %s |%n", variant, run,
                split(ghosts, " / ", " / ", ""), mostAtOneSample, steadyGhosts.size(), split(after.ghosts(), " / ", " / ", ""),
                String.join(", ", largest), cursor, slotMismatches, slotAtT0Words());
        }
    }
}
