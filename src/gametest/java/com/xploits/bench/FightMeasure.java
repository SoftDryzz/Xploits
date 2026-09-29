package com.xploits.bench;

import com.xploits.pvp.crystal.CrystalAuraPlusPlus;
import com.xploits.pvp.crystal.core.CrystalSetting;
import com.xploits.pvp.crystal.core.Reason;
import com.xploits.pvp.crystal.core.RiskLevel;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.CrystalAura;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Task A3: the real fights {@code exchange}, {@code hole-standoff} and {@code near-death} ({@code city} runs
 * separately, {@link CityMeasure}, since auto-pvp drives our own side there rather than the aura directly) —
 * the fight-mode twin of {@link CrystalAuraMeasure}: fight loadout ({@link Arena#fightLoadout()}), finite
 * totems on both sides, gapples after every pop, our death and the sparring's death outcomes rather than
 * errors (task A1, {@link FightMeasureRun}). Each is judged against its Meteor twin the same way
 * ({@link Scenario#compareWith}); crystal-aura++ runs at the {@code risk} level its scenario name says, exactly
 * as {@link CrystalAuraMeasure}. Runs 30 s (task A1 requirement 5), or less if either side loses first
 * (task A4 requirement 1).
 *
 * <p>Task A4 requirement 2: a crystal-aura++ run also logs its own decisions across the fight, the same
 * evidence {@link CrystalAuraMeasure} already logs for the older scenarios ({@link DecisionTally}) — log
 * only, never a metric.
 */
final class FightMeasure implements Scenario {
    private static final Logger LOG = LoggerFactory.getLogger("xploits-bench");

    private final String name;
    private final Class<? extends Module> aura;
    private final Class<? extends Module> other;
    private final String compareWith;
    private final Supplier<Script> script;
    /** crystal-aura++'s {@code risk} level; null for Meteor's aura. */
    private final RiskLevel risk;
    /** {@code near-death} only (task A3): sets both players' starting health and the opponent's totem before
     * T0; null for every other fight. */
    private final NearDeathSetup startingLow;
    /** The script this run's {@link #arrange} built, read back in {@link #act} to log its behaviour counters
     * (task A3's own "Proof" requirement) once the run is over. */
    private Script builtScript;
    /** Runs arranged so far, the one in progress included: the {@code n} of the counters log line. */
    private int runs;

    private FightMeasure(String name, Class<? extends Module> aura, Class<? extends Module> other, String compareWith,
                         Supplier<Script> script, RiskLevel risk, NearDeathSetup startingLow) {
        this.name = name;
        this.aura = aura;
        this.other = other;
        this.compareWith = compareWith;
        this.script = script;
        this.risk = risk;
        this.startingLow = startingLow;
    }

    /** Meteor's CrystalAura. */
    static FightMeasure meteor(String name, Supplier<Script> script) {
        return new FightMeasure(name, CrystalAura.class, CrystalAuraPlusPlus.class, null, script, null, null);
    }

    /** crystal-aura++ at Safe, judged against the Meteor scenario {@code compareWith}. */
    static FightMeasure plusPlus(String name, String compareWith, Supplier<Script> script) {
        return plusPlus(name, compareWith, script, RiskLevel.SAFE);
    }

    /** crystal-aura++ at the level {@code risk}, judged against the Meteor scenario {@code compareWith}. */
    static FightMeasure plusPlus(String name, String compareWith, Supplier<Script> script, RiskLevel risk) {
        return new FightMeasure(name, CrystalAuraPlusPlus.class, CrystalAura.class, compareWith, script,
            Objects.requireNonNull(risk, "risk"), null);
    }

    /**
     * Task A3 ({@code near-death}), redesigned in task B0b ({@link NearDeathSetup}): a warm-up with both players
     * healthy, then the near-death moment ({@value NearDeathSetup#HEALTH} health, no absorption); the opponent's
     * totem is stripped at the moment unless {@code totem}, so our first successful hit against it is a real
     * kill rather than a pop.
     */
    FightMeasure startingLow(boolean totem) {
        return new FightMeasure(name, aura, other, compareWith, script, risk, new NearDeathSetup(totem));
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
        return startingLow == null ? 30 : NearDeathSetup.WARMUP_TICKS / 20 + 30;
    }

    @Override
    public Optional<RiskLevel> risk() {
        return Optional.ofNullable(risk);
    }

    @Override
    public boolean simulatesPing() {
        return true;
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
        }
        bench.onClient(client -> {
            Module module = Modules.get().get(other);
            if (module == null) throw new BenchException("no module " + other.getSimpleName());
            if (module.isActive()) module.disable();
        });
        bench.arena().fightLoadout();
        builtScript = script.get();
        bench.spawnForFight(builtScript);
    }

    @Override
    public Metrics act(Bench bench) {
        if (bench.fromClient(client -> Modules.get().get(other).isActive())) {
            throw new BenchException(other.getSimpleName() + " was on before T0");
        }
        DecisionTally tally = aura == CrystalAuraPlusPlus.class ? DecisionTally.start(bench) : null;
        FightMeasureRun run = new FightMeasureRun(bench, aura);
        Metrics metrics = startingLow == null ? run.play(seconds() * 20).metrics()
            : run.play(NearDeathSetup.WARMUP_TICKS, 30 * 20,
                startingLow.moment(bench, builtScript, aura == CrystalAuraPlusPlus.class)).metrics();
        // Script#close() otherwise only runs at teardown (Sparring#despawn, after this method returns): a
        // cycle still pending at exactly the last tick (TIME_UP) would log one short of spawned() ==
        // explosions() + brokenFirst() + abandoned() if read before it. Calling it here is safe even though
        // Sparring will call it again at teardown: every close() this task wrote (CrystalAttack, SurroundMiner)
        // is idempotent past its first call (it only acts while a crystal is still pending).
        bench.onServer(srv -> builtScript.close());
        Fights.logCounters(name, runs, builtScript);
        if (tally != null) tally.log(bench, name, runs, run.placementsSent());
        if (aura == CrystalAuraPlusPlus.class) run.logFinishing(name, runs);
        return metrics;
    }

    /**
     * The near-death fights (task A3, redesigned in task B0b): both players start healthy, so our aura lands
     * an ordinary hit first and crystal-aura++ can come to trust the target's health as it would in real
     * play ({@code HealthTrust}); then, once that hit has landed and settled, the near-death moment: both at
     * {@value #HEALTH} health with no absorption, our fight loadout's totems back, the opponent's totems
     * stripped unless {@code totem}. The same for both auras.
     */
    private record NearDeathSetup(boolean totem) {
        static final float HEALTH = 6f;
        /** The longest the warm-up may take (10 s) before the moment comes anyway, with a metric saying so. */
        static final int WARMUP_TICKS = 200;
        /** Ticks after our hit before the moment: the target's hurt window has closed and its health read. */
        static final int SETTLE_TICKS = 14;
        /** Past this many ticks after our hit the moment comes even if things have not gone quiet. */
        static final int CLEAR_BOUND_TICKS = 60;
        /** Ticks without a placement sent, and with no end crystal around, that make it quiet. */
        static final int QUIET_TICKS = 6;
        /** How far around us an end crystal counts. */
        static final double CLEAR_RADIUS = 24;

        FightMeasureRun.Moment moment(Bench bench, Script script, boolean plusPlus) {
            String player = bench.player();
            Sparring sparring = bench.sparring();
            return new FightMeasureRun.Moment() {
                private int landedAt = -1;
                private int lastSent = -1;
                private int lastPlacementAt;

                @Override
                public boolean ready(int waited) {
                    int sent = bench.fromClient(client -> PlacementCounter.get().sent());
                    if (sent != lastSent) {
                        lastSent = sent;
                        lastPlacementAt = waited;
                    }
                    if (landedAt < 0 && bench.sparringStats().hitsFromOthers() > 0) landedAt = waited;
                    if (landedAt < 0) return false;
                    int since = waited - landedAt;
                    if (since < SETTLE_TICKS) return false;
                    // crystal-aura++ has to trust the target before the finishing blow can apply, as in real
                    // play, where trust always comes from earlier hits; a further hit may still form it.
                    if (plusPlus && !trusting()) return false;
                    // Quiet first: no placement just sent and no end crystal around, so nothing decided while both
                    // were healthy explodes after the health drop, which no aura could have planned for.
                    if (since >= CLEAR_BOUND_TICKS) return true;
                    return waited - lastPlacementAt >= QUIET_TICKS && crystalsAround(bench, player) == 0;
                }

                @Override
                public boolean landed() {
                    return landedAt >= 0;
                }

                @Override
                public boolean expectsTrust() {
                    return plusPlus;
                }

                @Override
                public boolean trusting() {
                    return bench.fromClient(client -> Modules.get().get(CrystalAuraPlusPlus.class).trustedTargets() > 0);
                }

                @Override
                public void apply(MinecraftServer server) {
                    ServerPlayerEntity p = Arena.player(server, player);
                    p.clearStatusEffects();
                    p.setHealth(HEALTH);
                    p.setAbsorptionAmount(0f);
                    bench.arena().restoreTotems(p);
                    sparring.clearStatusEffects();
                    sparring.setHealth(HEALTH);
                    sparring.setAbsorptionAmount(0f);
                    if (totem) sparring.rearmTotems();
                    else {
                        // No totem in either hand, and a visible item in the main hand: his hands read as visible,
                        // so a finishing blow on him is a kill, never a pop (task B0c). Both auras alike.
                        sparring.disarmTotem();
                        sparring.holdVisibleItem();
                    }
                    // Whatever is still standing is cleared, for both auras alike (logged): the near-death moment
                    // starts from no crystals at all.
                    int cleared = 0;
                    for (EndCrystalEntity crystal : server.getOverworld().getEntitiesByClass(EndCrystalEntity.class,
                        p.getBoundingBox().expand(CLEAR_RADIUS), Entity::isAlive)) {
                        crystal.discard();
                        cleared++;
                    }
                    LOG.info("[bench] near-death moment: {} end crystal(s) cleared (log only)", cleared);
                    if (script instanceof GatedScript gated) gated.release();
                }
            };
        }
    }

    /** End crystals within {@link NearDeathSetup#CLEAR_RADIUS} of our player. */
    private static int crystalsAround(Bench bench, String player) {
        return bench.fromServer(srv -> srv.getOverworld().getEntitiesByClass(EndCrystalEntity.class,
            Arena.player(srv, player).getBoundingBox().expand(NearDeathSetup.CLEAR_RADIUS), Entity::isAlive).size());
    }

    /**
     * Task A4 requirement 2: crystal-aura++'s own hold/defer/cost facts for a fight run, the same kind
     * {@link CrystalAuraMeasure} already logs for the older scenarios ({@code CrystalAuraMeasure.java}
     * ~243-251) — {@code FightMeasure}/{@code FightMeasureRun} logged none of it before this. Every count
     * here comes from the module or the brain ({@link CrystalAuraPlusPlus#lastDecision()},
     * {@link CrystalAuraPlusPlus#holding()}, {@link CrystalAuraPlusPlus#deferredForTargetWindow()}), or from
     * the bench's own independent packet/entity counters ({@link PlacementCounter},
     * {@link CrystalAppearanceCounter}) — never from the recorder's {@code FightRecord.SelfTotals}, whose
     * {@code attacks} field counts only entity-interaction packets aimed at a player, not a crystal (Meteor
     * breaking a crystal while {@code attacks: 0} is exactly this: a crystal break is a {@code CrystalBroken}
     * event there, a separate field). Log only, never a metric (no JSON/Acceptance change).
     */
    private static final class DecisionTally {
        private final int crystalsSeenAtT0;
        private final int entityInteractionsAtT0;
        private final Map<Reason, Integer> reasons = new EnumMap<>(Reason.class);
        private int ticks;
        private int holdingTicks;

        private DecisionTally(int crystalsSeenAtT0, int entityInteractionsAtT0) {
            this.crystalsSeenAtT0 = crystalsSeenAtT0;
            this.entityInteractionsAtT0 = entityInteractionsAtT0;
        }

        /** Starts tallying from now: every tick from here on, until the run's own {@link #log}. */
        static DecisionTally start(Bench bench) {
            DecisionTally tally = new DecisionTally(
                bench.fromClient(client -> CrystalAppearanceCounter.get().appeared()),
                bench.fromClient(client -> PlacementCounter.get().entityInteractionsSent()));
            bench.everyTick(() -> bench.onClient(client -> {
                CrystalAuraPlusPlus plusPlus = Modules.get().get(CrystalAuraPlusPlus.class);
                tally.ticks++;
                tally.reasons.merge(plusPlus.lastDecision().reason(), 1, Integer::sum);
                if (plusPlus.holding()) tally.holdingTicks++;
            }));
            return tally;
        }

        void log(Bench bench, String name, int runs, int placementsSent) {
            int deferred = bench.fromClient(client -> Modules.get().get(CrystalAuraPlusPlus.class).deferredForTargetWindow());
            int crystalsSeen = bench.fromClient(client -> CrystalAppearanceCounter.get().appeared()) - crystalsSeenAtT0;
            int entityInteractions = bench.fromClient(client -> PlacementCounter.get().entityInteractionsSent()) - entityInteractionsAtT0;
            LOG.info("[bench] {} run {}: crystal-aura++'s lastDecision() reason over {} pre-tick(s): {} (log only, not a metric)",
                name, runs, ticks, reasonCounts());
            LOG.info("[bench] {} run {}: crystal-aura++ was holding() (something passed Meteor's checks but the "
                + "budget refused all of it) on {} of {} pre-tick(s) (log only, not a metric)",
                name, runs, holdingTicks, ticks);
            LOG.info("[bench] {} run {}: crystal-aura++ held {} placement(s) for the target's hurt window this "
                + "activation (log only, not a metric)", name, runs, deferred);
            LOG.info("[bench] {} run {}: {} placement(s) sent, {} end crystal(s) appeared in the world since T0 "
                + "(ours and the opponent's combined: the brain's own ownership tracking is private), {} "
                + "entity-interaction packet(s) sent (a crystal break or any other attack: the recorder's own "
                + "attacks field counts only an interaction aimed at a player, never a crystal) (log only, not a metric)",
                name, runs, placementsSent, crystalsSeen, entityInteractions);
        }

        /** {@code REASON=count} for every {@link Reason} seen at least once, in the enum's own order. */
        private String reasonCounts() {
            StringBuilder text = new StringBuilder();
            for (Reason reason : Reason.values()) {
                int count = reasons.getOrDefault(reason, 0);
                if (count == 0) continue;
                if (!text.isEmpty()) text.append(", ");
                text.append(reason).append('=').append(count);
            }
            return text.isEmpty() ? "none" : text.toString();
        }
    }
}
