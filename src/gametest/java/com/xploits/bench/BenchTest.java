package com.xploits.bench;

import com.xploits.bench.BenchReport.Run;
import com.xploits.bench.BenchReport.Status;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.gen.WorldPreset;
import net.minecraft.world.gen.WorldPresets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The bench's only entrypoint (spec {@code 2026-09-25-ingame-bench}). It runs the scenarios asked for,
 * each run in its own fresh world, catches what a run throws, rewrites the report after every run, and
 * at the end fails the task (an {@link AssertionError} ends the client with exit code 1) if any
 * scenario is FAIL or ERROR.
 *
 * <p>Nothing it prints carries a position: names, statuses and bench-written messages only. An
 * exception from outside the bench is described by its class alone.
 */
public class BenchTest implements FabricClientGameTest {
    private static final Logger LOG = LoggerFactory.getLogger("xploits-bench");
    private static final String BENCH_PACKAGE = "com.xploits.bench.";

    /** What {@code build.gradle.kts} passes to the run as system properties. */
    record Config(Path out, Path baseline, List<String> only, boolean updateBaseline) {
        static Config fromSystemProperties() {
            String out = System.getProperty("xploits.bench.out");
            if (out == null || out.isBlank()) {
                throw new AssertionError("xploits.bench.out is not set: run the bench with ./gradlew runClientGameTest");
            }
            String baseline = System.getProperty("xploits.bench.baseline");
            String only = System.getProperty("xploits.bench.only", "");
            List<String> names = Arrays.stream(only.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
            return new Config(Path.of(out), baseline == null ? null : Path.of(baseline), names,
                Boolean.getBoolean("xploits.bench.update-baseline"));
        }
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        Config config = Config.fromSystemProperties();
        List<Scenario> selected = select(Scenarios.all(), config.only());
        BenchReport report = new BenchReport(version("xploits"), version("meteor-client"), config.out());
        LOG.info("[bench] {} scenario(s) selected", selected.size());

        for (Scenario scenario : selected) {
            for (int i = 1; i <= scenario.runs(); i++) {
                Run run = runOnce(ctx, scenario);
                LOG.info("[bench] {} run {}/{}: {}{}", scenario.name(), i, scenario.runs(), run.status(),
                    run.error() == null ? "" : " (" + run.error() + ")");
                report.add(scenario, run);
                write(report);
            }
        }
        write(report);

        LOG.info("[bench] {}; report in {}", report.summary(), report.jsonFile().getFileName());
        if (report.failed()) throw new AssertionError("bench failed: " + report.summary());
    }

    /** All the scenarios, or those named in {@code only}; a name that matches nothing is an error. */
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

    private static void write(BenchReport report) {
        try {
            report.write();
        } catch (IOException e) {
            throw new AssertionError("the bench report could not be written (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * One run in a fresh world (§Run timeline). The teardown runs even when the scenario threw; a
     * teardown step that fails, or a module it leaves on, turns a passing run into ERROR.
     */
    private static Run runOnce(ClientGameTestContext ctx, Scenario scenario) {
        String phase = "world";
        Run run;
        try (TestSingleplayerContext world = ctx.worldBuilder().adjustSettings(BenchTest::superflat).create()) {
            world.getClientWorld().waitForChunksRender();
            Bench bench = new Bench(ctx, world.getServer(), scenario.budgetTicks());
            try {
                phase = "prepare";
                bench.prepare();
                phase = "arrange";
                scenario.arrange(bench);
                phase = "act";
                Metrics metrics = scenario.act(bench);
                run = scenario.kind() == Scenario.Kind.CHECK
                    ? new Run(Status.PASS, null, Map.of())
                    : new Run(Status.DONE, null, metrics.values());
            } catch (RuntimeException | AssertionError e) {
                run = failure(scenario, phase, e);
            }
            phase = "teardown";
            List<String> leftovers = bench.teardown();
            if (!leftovers.isEmpty() && !run.status().fails()) {
                run = new Run(Status.ERROR, "teardown: " + String.join("; ", leftovers), Map.of());
            }
            phase = "world close";
        } catch (RuntimeException | AssertionError e) {
            // The world could not be created or closed.
            run = failure(scenario, phase, e);
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
        return new Run(checkFailed ? Status.FAIL : Status.ERROR, error, Map.of());
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
