package com.xploits.bench;

import com.xploits.bench.core.GappleSchedule;
import com.xploits.pvp.core.CrystalModule;
import com.xploits.pvp.core.Plan;
import com.xploits.pvp.crystal.CrystalAuraPlusPlus;
import com.xploits.pvp.crystal.core.Decision;
import com.xploits.pvp.recorder.FightRecorder;
import com.xploits.pvp.recorder.core.FightTracker;
import meteordevelopment.meteorclient.systems.modules.Modules;
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
    static final int RUN_TICKS = 600;
    /** A drop in how far we have got, within one tick, larger than this while walking: something put us back. */
    private static final double PULLED_BACK = 0.15;
    private static final Logger LOG = LoggerFactory.getLogger("xploits-bench");

    /** Who drives the crystal aura: {@code auto-pvp}, as the owner played, or nobody (the aura on by hand). */
    enum Driver { AUTO_PVP, BY_HAND }

    record Variant(String name, int attackers, Driver driver, boolean surroundCenters, boolean walkOut, String summary,
                   boolean ownersSettings) {
        Variant(String name, int attackers, Driver driver, boolean surroundCenters, boolean walkOut, String summary) {
            this(name, attackers, driver, surroundCenters, walkOut, summary, false);
        }
    }

    static final List<Variant> VARIANTS = List.of(
        new Variant("worst-2", 2, Driver.AUTO_PVP, true, false,
            "2 attackers; your setup (your surround, then auto-pvp driving crystal-aura++); you stay put"),
        new Variant("worst-2-walk", 2, Driver.AUTO_PVP, true, true,
            "2 attackers; your setup; once the hole breaks you hold forward through the gap"),
        new Variant("worst-2-walk-nocenter", 2, Driver.AUTO_PVP, false, true,
            "the same, with surround's center set to Never"),
        new Variant("worst-2-byhand", 2, Driver.BY_HAND, true, false,
            "2 attackers; your surround and crystal-aura++ on by hand, no auto-pvp; you stay put"),
        new Variant("worst-3", 3, Driver.AUTO_PVP, true, false,
            "3 attackers; your setup; you stay put"),
        new Variant("worst-2-yours", 2, Driver.AUTO_PVP, true, false,
            "2 attackers; your setup with your own crystal-aura++ settings (min-damage 0.9, max-damage 36, place and"
                + " break range 6, pause-health 1, anti-suicide off); you stay put", true));

    private final Variant variant;
    private AutoPvpScene scene;
    private final List<LabHarass> harassment = new ArrayList<>();

    LabWorstCase(Variant variant) {
        this.variant = variant;
    }

    /** The variants {@code -Pbench.lab} names, in the order given; {@code all} is every one. */
    static List<LabWorstCase> selected(String names) {
        if (names.strip().equals("all")) return VARIANTS.stream().map(LabWorstCase::new).toList();
        List<LabWorstCase> chosen = new ArrayList<>();
        for (String name : names.split(",")) {
            String wanted = name.strip();
            if (wanted.isEmpty()) continue;
            Variant v = VARIANTS.stream().filter(x -> x.name().equals(wanted)).findFirst()
                .orElseThrow(() -> new AssertionError("no lab variant is called " + wanted));
            chosen.add(new LabWorstCase(v));
        }
        return chosen;
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
        scene = AutoPvpScene.arrange(bench, "balanced", CrystalModule.XPLOITS);
        if (variant.ownersSettings()) {
            // The owner's own crystal-aura++ settings, as he plays with them.
            CrystalAuraPlusPlus aura = bench.fromClient(client -> Modules.get().get(CrystalAuraPlusPlus.class));
            bench.setting(aura, "General", "min-damage", 0.9);
            bench.setting(aura, "General", "max-damage", 36.0);
            bench.setting(aura, "General", "anti-suicide", false);
            bench.setting(aura, "Place", "place-range", 6.0);
            bench.setting(aura, "Break", "break-range", 6.0);
            bench.setting(aura, "Pause", "pause-health", 1.0);
        }
        Surround surround = bench.meteor(Surround.class);
        if (!variant.surroundCenters()) bench.setting(surround, "General", "center", Surround.Center.Never);
        bench.meteor(FightRecorder.class);
        bench.arena().fightLoadout();
        bench.arena().give(2, Items.ENCHANTED_GOLDEN_APPLE, 64);
        // auto-pvp only turns auto-city on with a pickaxe in the hotbar; he had one.
        bench.arena().give(3, Items.NETHERITE_PICKAXE, 1);

        CrystalAttack face = new CrystalAttack(CrystalAttack.Mode.HEAD, new Vec3i(0, 0, -3), Fights.HEAD_CELLS);
        LabHarass harass = new LabHarass();
        harassment.add(harass);
        bench.spawnForFight(new ComposedFight(new SurroundMiner(new Vec3i(2, 0, 0), Direction.EAST),
            List.of(new HoleWalls(Vec3i.ZERO), new SelfSurround(), new Autobreak())));
        bench.spawnMoreForFight(new ComposedFight(face,
            List.of(new SelfSurround(), new Autobreak(face), harass, new SpotBlock())), "Sparring2");
        if (variant.attackers() >= 3) {
            CrystalAttack feet = new CrystalAttack(CrystalAttack.Mode.FEET, new Vec3i(-3, 0, 0), Fights.FEET_CELLS);
            bench.spawnMoreForFight(new ComposedFight(feet,
                List.of(new SelfSurround(), new Autobreak(feet), new HoleFill())), "Sparring3");
        }
    }

    /** One tick of what we see of ourselves, client side. */
    private record Self(boolean dead, double health, boolean inHole, boolean inWeb, boolean surroundOn, double x, double z) {
    }

    /** One second of the trace. */
    private record Row(int second, double health, int ourPops, int theirPops, int theyAlive, String hole, boolean web,
                       String surround, double moved, int pulledBack, String autoPvp, boolean plusPlusOn, int placed,
                       String aura, int holding) {
    }

    @Override
    public Metrics act(Bench bench) {
        // His own surround first, as he had it, then the aura's driver.
        bench.onClient(client -> Modules.get().get(Surround.class).enable());
        if (variant.driver() == Driver.AUTO_PVP) scene.start(bench, true);
        else bench.start(true, CrystalAuraPlusPlus.class);

        Self start = self(bench);
        List<Sparring> them = bench.sparrings();
        GappleSchedule[] theirGapples = new GappleSchedule[them.size()];
        int[] theirLastPops = new int[them.size()];
        for (int i = 0; i < them.size(); i++) theirGapples[i] = new GappleSchedule();
        int placedAtStart = bench.fromClient(client -> PlacementCounter.get().sent());

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
            if (variant.walkOut() && breachTick >= 0 && walkTick < 0 && !now.dead()) {
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
                rows.add(new Row((tick + 19) / 20, now.health(), ourPops, theirPops, alive, hole, secondWeb,
                    now.surroundOn() ? "on" : "off", secondMoved, secondPulled, autoPvp, plusPlusOn, placed - placedBefore,
                    aura, secondHolding));
                placedBefore = placed;
                secondReasons.clear();
                secondHolding = 0;
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

        bench.finish();
        List<Path> fights = bench.newFightFiles();
        for (Sparring s : them) Fights.logCounters(variant.name() + " " + s.getGameProfile().name(), 1, s.script());
        int webs = harassment.stream().mapToInt(LabHarass::webs).sum();
        int roofs = harassment.stream().mapToInt(LabHarass::roofs).sum();
        write(rows, end, tick, deathTick, breachTick, walkTick, maxMoved, pulledBackTotal, webTicks, brokenTicks,
            brokenWithSurroundOn, webs, roofs, fights);
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
        return new Self(dead, dead ? 0 : client.player.getHealth() + client.player.getAbsorptionAmount(),
            PlayerUtils.isInHole(false), web, Modules.get().get(Surround.class).isActive(),
            client.player.getX(), client.player.getZ());
    }

    private void write(List<Row> rows, String end, int ticks, int deathTick, int breachTick, int walkTick, double maxMoved,
                       int pulledBack, int webTicks, int brokenTicks, int brokenWithSurroundOn, int webs, int roofs,
                       List<Path> fights) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(variant.name()).append("\n\n").append(variant.summary()).append(".\n\n");
        md.append("| s | health | your pops | their pops | they alive | your hole | web | surround | moved | pulled back"
            + " | auto-pvp | ++ on | placed | ++ decided (ticks) | ++ held by reserve |\n"
            + "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            md.append(String.format(Locale.ROOT, "| %d | %.1f | %d | %d | %d | %s | %s | %s | %.2f | %d | %s | %s | %d | %s | %d |%n",
                r.second(), r.health(), r.ourPops(), r.theirPops(), r.theyAlive(), r.hole(), r.web() ? "yes" : "", r.surround(),
                r.moved(), r.pulledBack(), r.autoPvp(), r.plusPlusOn() ? "yes" : "no", r.placed(), r.aura(), r.holding()));
        }
        md.append("\n**End:** ").append(end).append(String.format(Locale.ROOT, " after %.1f s.", ticks / 20.0)).append("\n\n");
        md.append(String.format(Locale.ROOT, "- Hole first broken at: %s.%n",
            breachTick < 0 ? "never" : String.format(Locale.ROOT, "%.1f s", breachTick / 20.0)));
        md.append(String.format(Locale.ROOT, "- Ticks with the hole broken: %d, %d of them with surround still on.%n",
            brokenTicks, brokenWithSurroundOn));
        md.append(String.format(Locale.ROOT, "- Ticks in a cobweb: %d. Webs placed on you: %d; roof blocks: %d.%n", webTicks, webs, roofs));
        if (variant.walkOut()) {
            md.append(String.format(Locale.ROOT, "- Walking out from: %s. Got at most %.2f blocks from the start;"
                    + " pulled back %d time(s).%n",
                walkTick < 0 ? "never (the hole never broke)" : String.format(Locale.ROOT, "%.1f s", walkTick / 20.0), maxMoved, pulledBack));
        } else {
            md.append(String.format(Locale.ROOT, "- Moved at most %.2f blocks from the start.%n", maxMoved));
        }
        md.append("- Death: ").append(deathTick < 0 ? "no" : String.format(Locale.ROOT, "at %.1f s", deathTick / 20.0)).append(".\n");
        md.append("- Fight recorder file(s): ").append(fights.isEmpty() ? "none"
            : String.join(", ", fights.stream().map(p -> p.getFileName().toString()).toList())).append(".\n");
        try {
            Path out = Path.of(System.getProperty(OUT_PROPERTY, "build/bench-lab"));
            Files.createDirectories(out);
            Files.writeString(out.resolve(variant.name() + ".md"), md.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BenchException("the lab trace could not be written (" + e.getClass().getSimpleName() + ")");
        }
    }
}
