package com.xploits.bench;

import com.xploits.bench.core.Settle;
import com.xploits.pvp.crystal.CrystalAuraPlusPlus;
import com.xploits.pvp.crystal.core.CrystalSetting;
import com.xploits.pvp.crystal.core.RiskLevel;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.CrystalAura;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.EndCrystalEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Objects;
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
 * Round 2 (b)); those that run in the full bench are judged against each other too. crystal-aura++ runs at
 * the {@code risk} level its scenario names ({@link #plusPlus(String, String, Supplier, RiskLevel)}): the
 * {@code capp-X} ones at Safe, the default, and {@code capp-balanced-X} and {@code capp-aggressive-X} at those
 * levels (R2-5), each set and read back before T0. The fight situations (R3-5: above, below, approach,
 * strafe) run the same way, only as healing twins ({@link Scenarios}).
 *
 * <p>Each run turns the other aura off before T0, so only the aura under test acts.
 *
 * <p>A run lasts 30 s, unless it settles first (R3-6, {@link Settle}): natural regeneration off, a static script
 * ({@link Script#isStatic}), and {@value Settle#SETTLE_TICKS} ticks in a row with no end crystal in the world
 * (the server's or the client's), no block or entity interaction sent (so no placement and no attack), and
 * neither our health plus absorption nor the sparring's nor its pops changing. Nothing can happen after that, so the run ends there
 * with the numbers it would have had at 30 s, and says so in one log line.
 *
 * <p>Metrics: {@code damage_dealt}, {@code sparring_pops}, {@code first_pop_s} (only in a run that
 * popped), {@code no_pop_runs} (1 for a run without a pop), {@code self_damage}, {@code self_pops},
 * {@code min_health} and {@code placements_per_s}, the placements over the nominal 30 s even when the run
 * settled earlier. A crystal-aura++ run also logs how many placements it held
 * back for the sparring's hurt window (R3-3); that count is not a metric.
 */
final class CrystalAuraMeasure implements Scenario {
    private static final Logger LOG = LoggerFactory.getLogger("xploits-bench");

    private final String name;
    private final Class<? extends Module> aura;
    private final Class<? extends Module> other;
    private final String compareWith;
    private final Supplier<Script> script;
    private final boolean regeneration;
    /** crystal-aura++'s {@code self-budget}; always on but for the CHECK {@code capp-budget-off-parity}. */
    private final boolean selfBudget;
    /** crystal-aura++'s {@code risk} level; null for Meteor's aura. */
    private final RiskLevel risk;
    private MeasureRun run;
    /** Runs arranged so far, the one in progress included: the {@code n} of the settle line. */
    private int runs;

    private CrystalAuraMeasure(String name, Class<? extends Module> aura, Class<? extends Module> other,
                               String compareWith, Supplier<Script> script, boolean regeneration, boolean selfBudget,
                               RiskLevel risk) {
        this.name = name;
        this.aura = aura;
        this.other = other;
        this.compareWith = compareWith;
        this.script = script;
        this.regeneration = regeneration;
        this.selfBudget = selfBudget;
        this.risk = risk;
    }

    /** Meteor's CrystalAura. */
    static CrystalAuraMeasure meteor(String name, Supplier<Script> script) {
        return new CrystalAuraMeasure(name, CrystalAura.class, CrystalAuraPlusPlus.class, null, script, false, true, null);
    }

    /**
     * crystal-aura++ at its default level, Safe, judged against the Meteor scenario {@code compareWith} on the
     * same arena and script.
     */
    static CrystalAuraMeasure plusPlus(String name, String compareWith, Supplier<Script> script) {
        return plusPlus(name, compareWith, script, RiskLevel.SAFE);
    }

    /** crystal-aura++ at the level {@code risk}, judged against the Meteor scenario {@code compareWith}. */
    static CrystalAuraMeasure plusPlus(String name, String compareWith, Supplier<Script> script, RiskLevel risk) {
        return new CrystalAuraMeasure(name, CrystalAuraPlusPlus.class, CrystalAura.class, compareWith, script, false, true,
            Objects.requireNonNull(risk, "risk"));
    }

    /**
     * crystal-aura++ with {@code self-budget} off: Meteor's offense and nothing else, for the CHECK
     * {@code capp-budget-off-parity} ({@link CappBudgetOffParity}), which does the judging. Its level is the
     * default; with the budget off no level changes anything.
     */
    static CrystalAuraMeasure plusPlusWithoutBudget(String name, Supplier<Script> script) {
        return new CrystalAuraMeasure(name, CrystalAuraPlusPlus.class, CrystalAura.class, null, script, false, false,
            RiskLevel.SAFE);
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
            script, true, selfBudget, risk);
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
    public Optional<RiskLevel> risk() {
        return Optional.ofNullable(risk);
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
        runs++;
        if (aura == CrystalAura.class) {
            MeasureRun.crystalAura(bench);
        } else {
            CrystalAuraPlusPlus plusPlus = MeasureRun.crystalAuraPlusPlus(bench);
            CrystalSetting level = CrystalSetting.RISK;
            bench.setting(plusPlus, level.group().title(), level.id(), risk);
            Object took = bench.fromClient(client -> plusPlus.settings.getGroup(level.group().title()).get(level.id()).get());
            if (took != risk) throw new BenchException("crystal-aura++ runs at the risk level " + took + ", not " + risk);
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
        waitOut(bench);
        if (aura == CrystalAuraPlusPlus.class) {
            int held = bench.fromClient(client -> Modules.get().get(CrystalAuraPlusPlus.class).deferredForTargetWindow());
            LOG.info("[bench] {}: crystal-aura++ held {} placement(s) for the target's hurt window (log only, not a metric)",
                name, held);
        }
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

    /**
     * The run's {@link #seconds()}, tick by tick, or less once it has settled ({@link Settle}); a run that cannot
     * settle waits them in one go, as before.
     */
    private void waitOut(Bench bench) {
        int nominal = seconds() * 20;
        Settle settle = new Settle(regeneration, bench.sparring().script().isStatic());
        if (!settle.possible()) {
            bench.ticks(nominal);
            return;
        }
        settle.observe(observe(bench));
        for (int tick = 1; tick <= nominal; tick++) {
            bench.ticks(1);
            if (settle.observe(observe(bench)) && tick < nominal) {
                LOG.info("[bench] {} run {}: settled after {} s, the remaining {} s could not change anything", name, runs,
                    String.format(Locale.ROOT, "%.2f", tick / 20.0),
                    String.format(Locale.ROOT, "%.2f", (nominal - tick) / 20.0));
                return;
            }
        }
    }

    /**
     * What {@link Settle} looks at, now: the end crystals the server and the client have, the block and entity
     * interaction packets sent so far (every placement and every attack, and more), our health plus absorption on
     * the client, and the sparring's health plus absorption and its pops on the server.
     */
    private static Settle.Observation observe(Bench bench) {
        ClientSide client = bench.fromClient(mc -> {
            if (mc.player == null || mc.world == null) throw new BenchException("the client has no player");
            int crystals = 0;
            for (Entity entity : mc.world.getEntities()) {
                if (entity instanceof EndCrystalEntity) crystals++;
            }
            return new ClientSide(crystals, PlacementCounter.get().blockInteractionsSent(),
                PlacementCounter.get().entityInteractionsSent(),
                (double) mc.player.getHealth() + mc.player.getAbsorptionAmount());
        });
        int serverCrystals = bench.fromServer(srv -> srv.getOverworld().getEntitiesByType(EntityType.END_CRYSTAL, e -> true).size());
        Sparring.Stats sparring = bench.sparringStats();
        return new Settle.Observation(client.crystals() + serverCrystals, client.blockInteractions(), client.entityInteractions(),
            client.health(), (double) sparring.health() + sparring.absorption(), sparring.pops());
    }

    /** What {@link #observe} reads on the client, in one call. */
    private record ClientSide(int crystals, int blockInteractions, int entityInteractions, double health) {
    }
}
