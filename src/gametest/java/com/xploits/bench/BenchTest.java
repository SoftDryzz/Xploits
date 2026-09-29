package com.xploits.bench;

import com.xploits.bench.BenchReport.Run;
import com.xploits.bench.BenchReport.Status;
import com.xploits.bench.core.MeteorCache;
import com.xploits.bench.core.PingDelay;
import com.xploits.bench.core.Profile;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.client.option.InactivityFpsLimit;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.gen.WorldPreset;
import net.minecraft.world.gen.WorldPresets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The bench's only entrypoint (spec {@code 2026-09-25-ingame-bench}). It runs the scenarios asked for,
 * each run in its own fresh world, catches what a run throws, rewrites the report after every run, and
 * at the end fails the task (an {@link AssertionError} ends the client with exit code 1) if any
 * scenario is FAIL or ERROR.
 *
 * <p>Which scenarios it plays is the run's {@link Profile} (R3-9): the everyday run by default, the full run
 * with {@code -Pbench.full}, the named ones with {@code -Pbench.only}. A {@code ca-*} MEASURE is served from
 * the Meteor cache ({@link MeteorCache}) while its key matches, and cached again whenever it is measured DONE;
 * {@code -Pbench.fresh} measures every one of them again, and so does {@code -Pbench.verifySettle}, which must
 * verify Meteor's settle shortcut too ({@link MeteorCache#bypass}).
 *
 * <p>Nothing it prints carries a position: names, statuses and bench-written messages only. An
 * exception from outside the bench is described by its class alone.
 */
public class BenchTest implements FabricClientGameTest {
    private static final Logger LOG = LoggerFactory.getLogger("xploits-bench");
    private static final String BENCH_PACKAGE = "com.xploits.bench.";

    /**
     * What {@code build.gradle.kts} passes to the run as system properties. {@code project} is the project's
     * folder, whose files the Meteor cache's key reads ({@link MeteorCache#inputs}); null leaves the cache off.
     * {@code pingMs} is the round trip (R3-12, {@link PingDelay}) every crystal-aura MEASURE plays over
     * ({@link Scenario#simulatesPing}); {@code -Pbench.ping} overrides {@link PingDelay#BENCH_PING_MS}.
     */
    record Config(Path out, Path baseline, List<String> only, boolean updateBaseline, boolean full, boolean fresh,
                  Path project, int pingMs, Shard shard) {
        Profile profile() {
            return Profile.of(full, !only.isEmpty());
        }

        /**
         * Task A5: {@code -Pbench.shard=k/n} — this client plays shard {@code k} of {@code n}
         * ({@link com.xploits.bench.core.ShardPlan}) of the selection, and its report records {@code commit}
         * and {@code treeClean} so {@link com.xploits.bench.core.ReportMerge} can check every shard ran the
         * same code.
         */
        record Shard(int k, int n, String commit, boolean treeClean) {
        }

        static Config fromSystemProperties() {
            String out = System.getProperty("xploits.bench.out");
            if (out == null || out.isBlank()) {
                throw new AssertionError("xploits.bench.out is not set: run the bench with ./gradlew runClientGameTest");
            }
            String baseline = System.getProperty("xploits.bench.baseline");
            String only = System.getProperty("xploits.bench.only", "");
            List<String> names = Arrays.stream(only.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
            String project = System.getProperty("xploits.bench.project");
            return new Config(Path.of(out), baseline == null ? null : Path.of(baseline), names,
                Boolean.getBoolean("xploits.bench.update-baseline"), Boolean.getBoolean("xploits.bench.full"),
                Boolean.getBoolean("xploits.bench.fresh"), project == null || project.isBlank() ? null : Path.of(project),
                PingDelay.effective(pingOverride()), shardFromSystemProperties());
        }

        /** The Gradle property {@code -Pbench.ping}, or null when it was not passed. */
        private static Integer pingOverride() {
            String value = System.getProperty("xploits.bench.ping");
            if (value == null || value.isBlank()) return null;
            try {
                return Integer.valueOf(value.strip());
            } catch (NumberFormatException e) {
                throw new AssertionError("xploits.bench.ping is not a number: " + value);
            }
        }

        /** {@code -Pbench.shard=k/n}, or null when this run is not sharded. */
        private static Shard shardFromSystemProperties() {
            String label = System.getProperty("xploits.bench.shard");
            if (label == null || label.isBlank()) return null;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)/(\\d+)").matcher(label.strip());
            if (!m.matches()) throw new AssertionError("xploits.bench.shard is not k/n: " + label);
            int k = Integer.parseInt(m.group(1));
            int n = Integer.parseInt(m.group(2));
            if (n < 1 || n > com.xploits.bench.core.ShardPlan.MAX_SHARDS || k < 1 || k > n) {
                throw new AssertionError("xploits.bench.shard must be 1 <= k <= n <= "
                    + com.xploits.bench.core.ShardPlan.MAX_SHARDS + ": " + label);
            }
            String commit = System.getProperty("xploits.bench.commit");
            if (commit == null || commit.isBlank()) {
                throw new AssertionError("xploits.bench.commit is not set: required with -Pbench.shard");
            }
            return new Shard(k, n, commit, Boolean.getBoolean("xploits.bench.tree-clean"));
        }
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty(Shots.FOLDER_PROPERTY) != null) {
            takeShots(ctx);
            return;
        }
        String lab = System.getProperty(LabWorstCase.PROPERTY);
        if (lab != null && !lab.isBlank()) {
            runLab(ctx, lab);
            return;
        }
        Config config = Config.fromSystemProperties();
        Profile profile = config.profile();
        List<Scenario> canonical = select(Scenarios.all(), config.only());
        List<Scenario> selected = canonical;
        // Task A5: -Pbench.shard=k/n cuts the canonical (unsharded) selection down to this client's own
        // slice, never splitting a compare group (ShardPlan); the report keeps the full canonical order too,
        // so ReportMerge can put every shard's scenarios back where an unsharded run would have them.
        if (config.shard() != null) {
            selected = shardOf(canonical, config.shard());
        }
        List<Scenario> played = selected.stream().filter(s -> BenchReport.plays(profile, s)).toList();
        Baseline baseline = loadBaseline(config.baseline());
        BenchReport report = new BenchReport(version("xploits"), version("meteor-client"), config.out(), baseline,
            config.only(), Scenarios.judged(), profile);
        boolean verifySettle = Boolean.getBoolean(CrystalAuraMeasure.VERIFY_SETTLE);
        if (config.shard() != null) {
            Config.Shard shard = config.shard();
            report.shard(shard.k() + "/" + shard.n(), shard.commit(), shard.treeClean(), config.fresh(), config.pingMs(),
                verifySettle, canonical.stream().map(Scenario::name).toList());
            LOG.info("[bench] shard {}/{}: {} of the {} canonical scenario(s), commit {}, tree {}",
                shard.k(), shard.n(), selected.size(), canonical.size(), shard.commit(), shard.treeClean() ? "clean" : "dirty");
        }
        LOG.info("[bench] {}: {} scenario(s) selected, {} to play, {} skipped; the baseline has {} scenario(s)",
            profile.label(), selected.size(), played.size(), selected.size() - played.size(), baseline.size());
        Optional<String> bypass = MeteorCache.bypass(config.fresh(), verifySettle);
        MeteorCache.Key cacheKey = cacheKey(config, bypass);
        if (verifySettle) {
            LOG.info("[bench] settle verification on: a settled crystal-aura run runs on to its full length and is checked");
        }
        // Every planned scenario is in the report from the start, PENDING: a client that stops mid-bench
        // leaves a report that says so, and the Gradle side (benchVerify) fails on it.
        report.plan(selected);
        write(report);
        keepFullFrameRate(ctx);

        Path cacheFolder = config.out().resolve(MeteorCache.FOLDER);
        for (Scenario scenario : played) {
            long start = System.nanoTime();
            String key = cacheKey != null && cacheable(scenario) ? cacheKey.of(scenario.name()) : null;
            Path cacheFile = key == null ? null : MeteorCache.file(cacheFolder, scenario.name());
            if (key != null && bypass.isEmpty()) {
                MeteorCache.Lookup lookup = MeteorCache.read(cacheFile, scenario.name(), key, scenario.runs());
                if (lookup.hit()) {
                    report.cached(scenario, lookup.entry().runs(), lookup.entry().measured());
                    write(report);
                    LOG.info("[bench] {}: {} (cached, measured {}) in {} s", scenario.name(), report.status(scenario.name()),
                        lookup.entry().measured(), seconds(start));
                    continue;
                }
                LOG.info("[bench] {}: measured, not served from the Meteor cache ({})", scenario.name(), lookup.miss());
            } else if (key != null) {
                LOG.info("[bench] {}: measured, not served from the Meteor cache ({})", scenario.name(), bypass.get());
            }
            for (int i = 1; i <= scenario.runs(); i++) {
                Run run = runOnce(ctx, scenario, config.out(), config.pingMs());
                LOG.info("[bench] {} run {} of {}: {}{}", scenario.name(), i, scenario.runs(), run.status(),
                    run.error() == null ? "" : " (" + run.error() + ")");
                report.add(scenario, run);
                write(report);
            }
            if (key != null && report.status(scenario.name()) == Status.DONE) store(cacheFile, scenario, key, report);
            LOG.info("[bench] {}: {} in {} s", scenario.name(), report.status(scenario.name()), seconds(start));
        }
        write(report);

        if (config.updateBaseline()) updateBaseline(report, config.baseline());
        hygiene(report, config);

        LOG.info("[bench] {}; report in {}", report.summary(), report.jsonFile().getFileName());
        report.recommendations().forEach(line -> LOG.info("[bench] {}", line));
        if (report.failed()) throw new AssertionError("bench failed: " + report.summary());
    }

    /**
     * {@code -Pshots} ({@code tools/shots.ps1}): the README's screenshots ({@link Shots}) instead of the bench, in
     * one run of their own, with no report; {@code benchVerify} does not run after it.
     */
    private static void takeShots(ClientGameTestContext ctx) {
        Shots.frame(ctx);
        Run run = runOnce(ctx, new Shots(), Shots.folder(), 0);
        LOG.info("[bench] shots: {}{}", run.status(), run.error() == null ? "" : " (" + run.error() + ")");
        if (run.status().fails()) throw new AssertionError("shots failed: " + run.error());
    }

    /**
     * {@code -Pbench.lab=<names>}: the lab's worst cases ({@link LabWorstCase}) instead of the bench, one run each,
     * each writing its own trace; no report, and {@code benchVerify} does not run after it. A run that fails is
     * logged and the others still run; the task fails at the end if any did.
     */
    private static void runLab(ClientGameTestContext ctx, String names) {
        keepFullFrameRate(ctx);
        List<String> failed = new ArrayList<>();
        for (LabWorstCase variant : LabWorstCase.selected(names)) {
            Run run = runOnce(ctx, variant, Path.of(System.getProperty(LabWorstCase.OUT_PROPERTY, "build/bench-lab")), 0);
            LOG.info("[bench] lab {}: {}{}", variant.name(), run.status(), run.error() == null ? "" : " (" + run.error() + ")");
            if (run.status().fails()) failed.add(variant.name());
        }
        if (!failed.isEmpty()) throw new AssertionError("lab run(s) failed: " + String.join(", ", failed));
    }

    /** Only Meteor's own MEASUREs, {@code ca-*}, are cached ({@link MeteorCache#cacheable}). */
    static boolean cacheable(Scenario scenario) {
        return MeteorCache.cacheable(scenario.kind() == Scenario.Kind.MEASURE, scenario.name(), scenario.risk().isPresent(),
            scenario.compareWith().isPresent());
    }

    /**
     * The Meteor cache's key over the Meteor jar the game loaded, the Minecraft version, the round trip every
     * {@code ca-*} plays over now (R3-12, {@link PingDelay}: a different ping is a different Meteor result), and
     * the project files that can change what Meteor does in the bench ({@link MeteorCache#inputs}:
     * {@code src/gametest}, the build files, the mixin configs and their packages, the recorder); null, which
     * leaves the cache off and every {@code ca-*} measured, when any of them cannot be read. Computed when the
     * run bypasses the cache too ({@code bypass}): what that run measures is cached again.
     */
    private static MeteorCache.Key cacheKey(Config config, Optional<String> bypass) {
        try {
            if (config.project() == null) throw new BenchException("xploits.bench.project is not set");
            Path jar = meteorJar();
            MeteorCache.Key key = MeteorCache.Key.of(Files.readAllBytes(jar), version("minecraft"), config.pingMs(),
                MeteorCache.inputs(config.project()));
            LOG.info("[bench] Meteor cache on: keyed on {}, Minecraft {}, {} ms simulated ping, src/gametest, the build"
                + " files, the mixins and the recorder{}", jar.getFileName(), version("minecraft"), config.pingMs(),
                bypass.map(flag -> "; " + flag + " measures every ca-* again").orElse(""));
            return key;
        } catch (IOException | RuntimeException e) {
            LOG.warn("[bench] Meteor cache off, every ca-* is measured: {}", describe(e));
            return null;
        }
    }

    /** The one jar Meteor was loaded from. */
    private static Path meteorJar() {
        ModContainer meteor = FabricLoader.getInstance().getModContainer("meteor-client")
            .orElseThrow(() -> new BenchException("Meteor is not loaded"));
        ModOrigin origin = meteor.getOrigin();
        if (origin.getKind() != ModOrigin.Kind.PATH || origin.getPaths().size() != 1
            || !Files.isRegularFile(origin.getPaths().getFirst())) {
            throw new BenchException("Meteor is not loaded from one jar");
        }
        return origin.getPaths().getFirst();
    }

    /** Caches a {@code ca-*} MEASURE that finished DONE; a cache that cannot be written only costs a measure next time. */
    private static void store(Path file, Scenario scenario, String key, BenchReport report) {
        try {
            MeteorCache.write(file, new MeteorCache.Entry(scenario.name(), key, LocalDate.now().toString(),
                report.doneRuns(scenario.name())));
            LOG.info("[bench] {}: cached for the next run", scenario.name());
        } catch (IOException | RuntimeException e) {
            LOG.warn("[bench] {}: could not be cached: {}", scenario.name(), describe(e));
        }
    }

    /** The seconds since {@code start}, one decimal. */
    private static String seconds(long start) {
        return String.format(java.util.Locale.ROOT, "%.1f", (System.nanoTime() - start) / 1e9);
    }

    /**
     * With its default {@code inactivityFpsLimit} (AFK), Minecraft drops to 10 frames per second once there
     * has been no input for 10 minutes ({@code InactivityFpsLimiter}, LONG_AFK), and a gametest tick waits
     * for a frame: a bench longer than that would run at half speed from then on, and the next world load
     * times out. It also lifts the 30 fps cap of SHORT_AFK, which starts after one minute without input:
     * every scenario but those of the first minute would otherwise run capped, so all of them now run
     * uncapped. Nobody types during the bench, so only a minimized window may limit the frame rate. The
     * option belongs to the gametest's own run folder, not to the player's game.
     */
    private static void keepFullFrameRate(ClientGameTestContext ctx) {
        ctx.runOnClient(client -> client.options.getInactivityFpsLimit().setValue(InactivityFpsLimit.MINIMIZED));
    }

    /** The committed baseline; a missing file compares nothing, a broken one ends the bench before any run. */
    private static Baseline loadBaseline(Path file) {
        try {
            return Baseline.load(file);
        } catch (BenchException e) {
            throw new AssertionError(describe(e));
        }
    }

    /** {@code -Pbench.updateBaseline}: every DONE MEASURE's medians go into the committed baseline. */
    private static void updateBaseline(BenchReport report, Path file) {
        if (file == null) throw new AssertionError("xploits.bench.baseline is not set: run the bench with ./gradlew runClientGameTest");
        // Re-read: only the entries this run replaces change, whatever the file holds now.
        Baseline baseline = loadBaseline(file);
        int put = report.updateBaseline(baseline);
        try {
            baseline.write(file);
        } catch (IOException e) {
            throw new AssertionError("the bench baseline could not be written (" + e.getClass().getSimpleName() + ")");
        }
        LOG.info("[bench] baseline updated with {} scenario(s)", put);
    }

    /**
     * The hygiene scan, after the last write (§Proving the bench works): a report line that looks like a
     * position is ERROR. The committed baseline, when there is one, is scanned too. The result is written
     * into the report, {@code "hygiene": "clean"} or the lines found, which the Gradle side requires; a
     * clean result is scanned once more after that write, so the marker cannot hide a line it added.
     */
    private static void hygiene(BenchReport report, Config config) {
        List<String> hits = scan(config);
        report.hygiene(hits);
        write(report);
        if (hits.isEmpty()) {
            hits = scan(config);
            if (!hits.isEmpty()) {
                report.hygiene(hits);
                write(report);
            }
        }
        if (hits.isEmpty()) {
            LOG.info("[bench] hygiene: no line looks like a position");
        } else {
            for (String hit : hits) LOG.error("[bench] hygiene: {} looks like a position", hit);
        }
    }

    private static List<String> scan(Config config) {
        try {
            List<String> hits = new ArrayList<>(Hygiene.scan(config.out()));
            if (config.baseline() != null && Files.isRegularFile(config.baseline())) {
                hits.addAll(Hygiene.scanFile(config.baseline(), "baseline.json"));
            }
            return hits;
        } catch (IOException e) {
            throw new AssertionError("the bench report could not be scanned (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * All the scenarios, or those named in {@code only}; a name that matches nothing is an error. The run's
     * profile then decides which of them it plays ({@link BenchReport#plays}).
     */
    static List<Scenario> select(List<Scenario> all, List<String> only) {
        if (only.isEmpty()) return all;
        List<Scenario> selected = new ArrayList<>();
        for (String name : only) {
            Scenario match = all.stream().filter(s -> s.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no bench scenario is called " + name));
            if (!selected.contains(match)) selected.add(match);
        }
        return selected;
    }

    /**
     * Task A5: {@code canonical}, cut down to shard {@code shard.k()} of {@code shard.n()}
     * ({@link com.xploits.bench.core.ShardPlan}), in {@code canonical}'s own order.
     */
    private static List<Scenario> shardOf(List<Scenario> canonical, Config.Shard shard) {
        List<com.xploits.bench.core.ShardPlan.Item> items = canonical.stream()
            .map(s -> new com.xploits.bench.core.ShardPlan.Item(s.name(), s.runs(), s.seconds(), s.compareWith().orElse(null)))
            .toList();
        java.util.Set<String> names = new java.util.HashSet<>(
            com.xploits.bench.core.ShardPlan.shard(items, shard.k(), shard.n()));
        return canonical.stream().filter(s -> names.contains(s.name())).toList();
    }

    private static void write(BenchReport report) {
        try {
            report.write();
        } catch (IOException e) {
            throw new AssertionError("the bench report could not be written (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * One run in a fresh world (§Run timeline). The teardown runs even when the scenario threw; a
     * teardown step that fails, or a module it leaves on, turns a passing run into ERROR, and is added to
     * the error of a run that already failed. So is a world that could not be closed, or a teardown that
     * threw: a run that had already failed keeps its status and whether our player died, so a death is
     * never lost (it makes a crystal-aura++ verdict REJECT, not INCOMPLETE).
     */
    private static Run runOnce(ClientGameTestContext ctx, Scenario scenario, Path out, int pingMs) {
        String phase = "world";
        Run run = null;
        // R3-12: set before the world (and so the integrated server's local connection) is created, which is
        // when the bench-only pipeline mixin reads it; every other scenario keeps today's lock-step (0).
        int roundTrip = scenario.simulatesPing() ? pingMs : 0;
        System.setProperty(PingDelay.ACTIVE_PROPERTY, Integer.toString(PingDelay.eachWayMs(roundTrip)));
        try (TestSingleplayerContext world = ctx.worldBuilder().adjustSettings(BenchTest::superflat).create()) {
            world.getClientWorld().waitForChunksRender();
            Bench bench = new Bench(ctx, world.getServer(), scenario.budgetTicks(), out);
            try {
                phase = "prepare";
                bench.prepare(scenario.naturalRegeneration());
                phase = "arrange";
                scenario.arrange(bench);
                phase = "act";
                Metrics metrics = scenario.act(bench);
                // A CHECK's numbers, when it judged some, go to the report with its run.
                run = scenario.kind() == Scenario.Kind.CHECK
                    ? new Run(Status.PASS, null, metrics.values())
                    : new Run(Status.DONE, null, metrics.values());
            } catch (RuntimeException | AssertionError e) {
                run = failure(scenario, phase, e);
            }
            phase = "teardown";
            List<String> leftovers = bench.teardown();
            if (!leftovers.isEmpty()) {
                String teardown = "teardown: " + String.join("; ", leftovers);
                // A failed run keeps its status and its first error; what the teardown left is added to it.
                run = run.status().fails()
                    ? new Run(run.status(), run.error() + "; " + teardown, run.metrics(), run.died())
                    : new Run(Status.ERROR, teardown, Map.of());
            }
            phase = "world close";
        } catch (RuntimeException | AssertionError e) {
            // The world could not be created or closed, or the teardown threw.
            Run failed = failure(scenario, phase, e);
            run = run != null && run.status().fails()
                ? new Run(run.status(), run.error() + "; " + failed.error(), run.metrics(), run.died())
                : failed;
        }
        return run;
    }

    /**
     * A CHECK's own assertion is FAIL; anything else is ERROR. Only a message the bench wrote goes to the
     * report (see {@link #describe}).
     */
    private static Run failure(Scenario scenario, String phase, Throwable e) {
        boolean checkFailed = scenario.kind() == Scenario.Kind.CHECK && e instanceof AssertionError && fromBench(e);
        String error = describe(e);
        if (!fromBench(e) || !(e instanceof AssertionError || e instanceof BenchException)) {
            error = error + " during " + phase;
        }
        logTrace(e);
        return new Run(checkFailed ? Status.FAIL : Status.ERROR, error, Map.of(), e instanceof PlayerDied);
    }

    /**
     * The exception's class, and its message only when the bench wrote it: a {@link BenchException} or
     * an {@link AssertionError} thrown from bench code. Any other message could carry a position or a
     * command, and is left out.
     */
    static String describe(Throwable e) {
        String type = e.getClass().getSimpleName();
        if (fromBench(e) && (e instanceof AssertionError || e instanceof BenchException) && e.getMessage() != null) {
            return type + ": " + e.getMessage();
        }
        return type;
    }

    private static boolean fromBench(Throwable e) {
        if (e instanceof BenchException) return true;
        StackTraceElement[] trace = e.getStackTrace();
        return trace.length > 0 && trace[0].getClassName().startsWith(BENCH_PACKAGE);
    }

    /** Logs where an exception came from: classes and frames, never messages (they may hold a position). */
    private static void logTrace(Throwable e) {
        StringBuilder trace = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            trace.append(t == e ? "" : "\ncaused by ").append(describe(t));
            for (StackTraceElement frame : t.getStackTrace()) trace.append("\n    at ").append(frame);
        }
        LOG.error("[bench] {}", trace);
    }

    /** A superflat world, whatever the builder's defaults become. */
    private static void superflat(WorldCreator creator) {
        RegistryEntry<WorldPreset> flat = creator.getGeneratorOptionsHolder().getCombinedRegistryManager()
            .getOrThrow(RegistryKeys.WORLD_PRESET).getOrThrow(WorldPresets.FLAT);
        creator.setWorldType(new WorldCreator.WorldType(flat));
    }

    private static String version(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
    }
}
