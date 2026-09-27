package com.xploits.bench;

import com.xploits.bench.core.Parity;
import com.xploits.pvp.crystal.CrystalAuraPlusPlus;
import com.xploits.pvp.crystal.core.CrystalSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * CHECK {@code capp-budget-off-parity} (crystal-aura++ R2-4): with {@code self-budget} off, crystal-aura++ is
 * Meteor's crystal-aura. Meteor's aura and crystal-aura++ without the budget take turns on the Still arena, each
 * run exactly as a {@code ca-still} / {@code capp-still} run (fresh world, standard loadout, recorder on,
 * {@code pause-on-lag} off, the other aura off, 30 s); odd runs are Meteor's, even runs crystal-aura++'s, so
 * neither side gets the later worlds. After the last run the medians of {@link Parity#METRICS} must match
 * within the larger of each metric's noise floor and 15 % of Meteor's value ({@link Parity}); any metric
 * beyond that fails the CHECK. So the offense copy is faithful, and every difference the {@code capp-*} pairs
 * show comes from the budget alone.
 *
 * <p>Each run also checks that crystal-aura++ shows exactly the settings of {@link CrystalSetting}, in its
 * groups and order: the list the core's coverage guard holds to a test each, so no setting reaches the module
 * without one.
 *
 * <p>Each run's numbers go to the report; the comparison is logged and, when it fails, is the failure's message.
 */
final class CappBudgetOffParity implements Scenario {
    private static final Logger LOG = LoggerFactory.getLogger("xploits-bench");
    static final String NAME = "capp-budget-off-parity";

    private final CrystalAuraMeasure meteor = CrystalAuraMeasure.meteor(NAME + "-meteor", Still::new);
    private final CrystalAuraMeasure plusPlus = CrystalAuraMeasure.plusPlusWithoutBudget(NAME + "-capp", Still::new);
    private final List<Map<String, Double>> meteorRuns = new ArrayList<>();
    private final List<Map<String, Double>> plusPlusRuns = new ArrayList<>();
    /** Runs started so far, the one in progress included. */
    private int started;
    private CrystalAuraMeasure current;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Kind kind() {
        return Kind.CHECK;
    }

    @Override
    public int seconds() {
        return 30;
    }

    @Override
    public int runs() {
        return 2 * Parity.RUNS;
    }

    @Override
    public boolean simulatesPing() {
        // R3-12: its inner runs are crystal-aura MEASURE runs (Meteor's aura and crystal-aura++ without the
        // budget), so they play over the same simulated ping.
        return true;
    }

    @Override
    public void arrange(Bench bench) {
        started++;
        current = started % 2 == 1 ? meteor : plusPlus;
        checkSettingsList(bench);
        current.arrange(bench);
    }

    @Override
    public Metrics act(Bench bench) {
        Metrics metrics = current.act(bench);
        boolean isMeteor = current == meteor;
        (isMeteor ? meteorRuns : plusPlusRuns).add(metrics.values());
        LOG.info("[bench] {} run {}: {}", NAME, started, (isMeteor ? "Meteor " : "crystal-aura++ without the budget ")
            + describe(metrics.values()));
        if (started == runs()) compare();
        return metrics;
    }

    /** The last run: both sides complete, then every metric within its gap. */
    private void compare() {
        if (meteorRuns.size() != Parity.RUNS || plusPlusRuns.size() != Parity.RUNS) {
            throw new BenchException(NAME + " has " + plusPlusRuns.size() + " crystal-aura++ runs and "
                + meteorRuns.size() + " Meteor runs finished, not " + Parity.RUNS + " each");
        }
        Map<String, Double> floors = new LinkedHashMap<>();
        for (String name : Parity.METRICS) floors.put(name, Metrics.definition(name).floor());
        Parity.Outcome outcome = Parity.judge(plusPlusRuns, meteorRuns, floors);
        String details = outcome.metrics().stream().map(Parity.Metric::detail).collect(Collectors.joining("; "));
        LOG.info("[bench] {}: {}", NAME, details);
        Bench.check(outcome.matches(), "crystal-aura++ without the budget is not Meteor's crystal-aura on "
            + String.join(", ", outcome.failed()) + ": " + details);
    }

    /** crystal-aura++ shows exactly {@link CrystalSetting}'s settings, in its groups and order. */
    private static void checkSettingsList(Bench bench) {
        List<String> shown = bench.fromClient(client -> {
            List<String> names = new ArrayList<>();
            for (SettingGroup group : Modules.get().get(CrystalAuraPlusPlus.class).settings) {
                for (Setting<?> setting : group) names.add(group.name + "/" + setting.name);
            }
            return names;
        });
        List<String> expected = Stream.of(CrystalSetting.values()).map(s -> s.group().title() + "/" + s.id()).toList();
        Bench.check(shown.equals(expected), "crystal-aura++ shows the settings " + shown + ", not " + expected);
    }

    private static String describe(Map<String, Double> values) {
        return values.entrySet().stream().map(e -> e.getKey() + " " + String.format(java.util.Locale.ROOT, "%.2f", e.getValue()))
            .collect(Collectors.joining("; "));
    }
}
