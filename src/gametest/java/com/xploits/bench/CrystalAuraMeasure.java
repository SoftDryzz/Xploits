package com.xploits.bench;

import com.xploits.pvp.crystal.CrystalAuraPlusPlus;
import com.xploits.pvp.crystal.core.CrystalSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.CrystalAura;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * MEASURE {@code ca-still}, {@code ca-circler} and {@code ca-defender} (spec {@code
 * 2026-09-25-ingame-bench}, §Scenarios, §Metrics): Meteor's CrystalAura, reset with {@code pause-on-lag}
 * off, against a scripted sparring for 30 s, from the standard loadout (AutoTotem Strict) with the
 * recorder on. And {@code capp-still}, {@code capp-circler} and {@code capp-defender} (crystal-aura++
 * spec §4, P6): crystal-aura++ the same way, on the same arenas and scripts, each judged against its
 * {@code ca-} twin ({@link #compareWith}). Each of them also has a healing twin, {@code <name>-regen}
 * ({@link #healing}), on the same arena and script with natural regeneration on (crystal-aura++ spec,
 * Round 2 (b)); those that run in the full bench are judged against each other too.
 *
 * <p>Each run turns the other aura off before T0, so only the aura under test acts.
 *
 * <p>Metrics: {@code damage_dealt}, {@code sparring_pops}, {@code first_pop_s} (only in a run that
 * popped), {@code no_pop_runs} (1 for a run without a pop), {@code self_damage}, {@code self_pops},
 * {@code min_health} and {@code placements_per_s}.
 */
final class CrystalAuraMeasure implements Scenario {
    private final String name;
    private final Class<? extends Module> aura;
    private final Class<? extends Module> other;
    private final String compareWith;
    private final Supplier<Script> script;
    private final boolean regeneration;
    /** crystal-aura++'s {@code self-budget}; always on but for the CHECK {@code capp-budget-off-parity}. */
    private final boolean selfBudget;
    private MeasureRun run;

    private CrystalAuraMeasure(String name, Class<? extends Module> aura, Class<? extends Module> other,
                               String compareWith, Supplier<Script> script, boolean regeneration, boolean selfBudget) {
        this.name = name;
        this.aura = aura;
        this.other = other;
        this.compareWith = compareWith;
        this.script = script;
        this.regeneration = regeneration;
        this.selfBudget = selfBudget;
    }

    /** Meteor's CrystalAura. */
    static CrystalAuraMeasure meteor(String name, Supplier<Script> script) {
        return new CrystalAuraMeasure(name, CrystalAura.class, CrystalAuraPlusPlus.class, null, script, false, true);
    }

    /** crystal-aura++, judged against the Meteor scenario {@code compareWith} on the same arena and script. */
    static CrystalAuraMeasure plusPlus(String name, String compareWith, Supplier<Script> script) {
        return new CrystalAuraMeasure(name, CrystalAuraPlusPlus.class, CrystalAura.class, compareWith, script, false, true);
    }

    /**
     * crystal-aura++ with {@code self-budget} off: Meteor's offense and nothing else, for the CHECK
     * {@code capp-budget-off-parity} ({@link CappBudgetOffParity}), which does the judging.
     */
    static CrystalAuraMeasure plusPlusWithoutBudget(String name, Supplier<Script> script) {
        return new CrystalAuraMeasure(name, CrystalAuraPlusPlus.class, CrystalAura.class, null, script, false, false);
    }

    /** The suffix of a healing twin's name. */
    static final String HEALING = "-regen";

    /**
     * This scenario's healing twin: {@code <name>-regen}, the same aura, arena, script, loadout and timing,
     * with natural regeneration on; a crystal-aura++ one is judged against {@code <compareWith>-regen}.
     */
    CrystalAuraMeasure healing() {
        if (regeneration) throw new IllegalStateException(name + " already heals");
        return new CrystalAuraMeasure(name + HEALING, aura, other, compareWith == null ? null : compareWith + HEALING,
            script, true, selfBudget);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Kind kind() {
        return Kind.MEASURE;
    }

    @Override
    public int seconds() {
        return 30;
    }

    @Override
    public boolean naturalRegeneration() {
        return regeneration;
    }

    @Override
    public Optional<String> compareWith() {
        return Optional.ofNullable(compareWith);
    }

    @Override
    public void arrange(Bench bench) {
        if (aura == CrystalAura.class) {
            MeasureRun.crystalAura(bench);
        } else {
            CrystalAuraPlusPlus plusPlus = MeasureRun.crystalAuraPlusPlus(bench);
            if (!selfBudget) {
                CrystalSetting budget = CrystalSetting.SELF_BUDGET;
                bench.setting(plusPlus, budget.group().title(), budget.id(), false);
                if (bench.fromClient(client -> (Boolean) plusPlus.settings.getGroup(budget.group().title()).get(budget.id()).get())) {
                    throw new BenchException("crystal-aura++ kept its self-budget on");
                }
            }
        }
        run = new MeasureRun(bench, aura);
        bench.onClient(client -> {
            Module module = Modules.get().get(other);
            if (module == null) throw new BenchException("no module " + other.getSimpleName());
            if (module.isActive()) module.disable();
        });
        bench.arena().loadout(false);
        bench.spawn(script.get());
    }

    @Override
    public Metrics act(Bench bench) {
        if (bench.fromClient(client -> Modules.get().get(other).isActive())) {
            throw new BenchException(other.getSimpleName() + " was on before T0");
        }
        run.start();
        bench.ticks(seconds() * 20);
        Sparring.Stats sparring = bench.sparringStats();
        run.close();

        // The sparring cannot lose more than it was dealt (§Proving the bench works, sparring validity).
        if (sparring.damageTaken() > sparring.rawDamage() + 1e-3) {
            throw new BenchException("the sparring lost more health than it was dealt");
        }
        Metrics metrics = new Metrics()
            .put(Metrics.DAMAGE_DEALT, sparring.damageTaken())
            .put(Metrics.SPARRING_POPS, sparring.pops());
        if (sparring.firstPopTick() >= 0) metrics.put(Metrics.FIRST_POP_S, sparring.firstPopTick() / 20.0);
        return metrics
            .put(Metrics.NO_POP_RUNS, sparring.pops() == 0 ? 1 : 0)
            .put(Metrics.SELF_DAMAGE, run.selfDamage())
            .put(Metrics.SELF_POPS, run.selfPops())
            .put(Metrics.MIN_HEALTH, run.minHealth())
            .put(Metrics.PLACEMENTS_PER_S, (double) run.crystalsPlaced() / seconds());
    }
}
