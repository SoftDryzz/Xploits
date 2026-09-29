package com.xploits.bench;

import com.xploits.bench.core.FightResult;
import com.xploits.bench.core.GappleSchedule;
import com.xploits.bench.core.MinHealthAfterOwnHit;
import com.xploits.pvp.crystal.CrystalAuraPlusPlus;
import com.xploits.pvp.recorder.FightRecorder;
import com.xploits.pvp.recorder.core.FightRecord;
import com.xploits.pvp.recorder.core.FightTracker;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.server.MinecraftServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.OptionalDouble;

/**
 * A fight-mode run (task A1, opt-in): unlike {@link MeasureRun}, our own loss and the sparring's (death, or
 * running out of totems — task A4 requirement 1, {@link com.xploits.bench.core.FightResult#outOfTotems})
 * end the run with a {@code result} instead of ERROR (requirement 3), both players carry
 * {@value Arena#FIGHT_TOTEMS} totems (requirement 1 and 2), and every pop of either player schedules the
 * enchanted golden apple's effects {@value com.xploits.bench.core.GappleSchedule#DELAY_TICKS} ticks later
 * (requirement 4). It runs the scenario's nominal length, or stops early the tick either side loses,
 * whichever comes first — a fight in progress is never static, so it never settles ({@code Settle}
 * requirement 5): there is always a chance our own loss, the sparring's, or the next pop is still ahead.
 *
 * <p>Pop detection is event-based, read once per bench tick rather than by polling the offhand stack (fix
 * round 1): our own pops come from {@link FightRecorder#live()}'s {@code yourPops}, the same live count the
 * fight recorder itself builds from the totem-use entity status (35, {@code EntityStatuses.USE_TOTEM_OF_UNDYING})
 * packet for our own player — the identical signal {@code FightRecorder.drainPackets} turns into
 * {@code CombatEvent.Popped(null)} and, eventually, {@code record.self().pops()}
 * ({@link MeasureRun#selfPops}) — so it counts every pop exactly once even when two lethal hits land in the
 * same tick or AutoTotem's own refill lands within the same tick as the pop, neither of which a once-per-tick
 * poll of the offhand stack could tell apart (that was fix round 1's own finding: a prior version of this
 * class polled the offhand and could under-count both cases). The sparring's own pop counter
 * ({@link Sparring#damage}) is likewise updated immediately, not polled. Both drive the gapple schedule and
 * the {@code pops_taken} / {@code pops_dealt} metrics directly, rather than waiting for the closed
 * {@link FightRecord}s: those records are only read once, at the close, for {@code self_damage},
 * {@code damage_dealt} and {@code min_health_after_own_hit} (task A1 requirement 3), which need the
 * recorder's own attribution ({@code AttackerKind.SELF}) that a live count cannot see. At the close,
 * {@link #finish} cross-checks the live {@code pops_taken} tally against {@code MeasureRun.selfPops(records)}
 * — the same underlying counter, read live versus read from what was saved — and fails loudly, naming both
 * counts, on any disagreement, rather than silently trusting either one.
 *
 * <p>A1 wires this class only enough to prove the building blocks work (one temporary probe run, removed
 * before the commit — see the task report); A2 gives the sparring a script that attacks back, and A3 turns
 * this into the three real fight scenarios.
 */
final class FightMeasureRun {
    private static final Logger LOG = LoggerFactory.getLogger("xploits-bench");

    private final Bench bench;
    private final Class<? extends Module> underTest;

    private double minHealth = Double.POSITIVE_INFINITY;
    private int lastOwnPops;
    private int popsTaken;
    private int firstPopTakenTick = -1;
    private int lastSparringPops;
    private final GappleSchedule ourGapple = new GappleSchedule();
    private final GappleSchedule sparringGapple = new GappleSchedule();
    private int placementsAtT0;
    private int nominalSeconds;

    // Task B0b: the finishing blow, the totems we carried, and the near-death moment.
    private final FinishingWatch watch;
    private boolean diedWithTotem;
    /** The bench tick the measured part starts at: T0, or the near-death moment. */
    private int measureFrom;
    private boolean measuring = true;
    private boolean warmupMissed;
    private boolean trustMissed;
    private int popsTakenBase;
    private int sparringPopsBase;
    private int sparringOthersBase;
    private boolean logTrustNext;
    private double sparringDamageBase;
    private int firstBlowTick = -1;
    private int finishingEventsExcluded;
    private int finishingAmbiguous;
    private int finishingBlows;
    /** Finishing blows that hurt us (task T2). */
    private int finishingHits;
    /** Whether this run had a near-death moment ({@link #play(int, int, Moment)}). */
    private boolean nearDeath;
    /** Crystal placement packets sent from T0 to the close; set once, at {@link #finish}. */
    private int placementsSent;

    FightMeasureRun(Bench bench, Class<? extends Module> underTest) {
        this.bench = bench;
        this.underTest = underTest;
        this.watch = new FinishingWatch(bench, underTest == CrystalAuraPlusPlus.class);
        bench.meteor(FightRecorder.class);
        bench.onClient(client -> {
            Module module = Modules.get().get(underTest);
            if (module.isActive()) module.disable();
            // A new run, the previous module now off: the cells of earlier placements must not attribute its hits.
            PlacementCounter.get().resetCells();
        });
    }

    /**
     * How the fight ended: a real death, or a side using up its last totem (task A4 requirement 1) while
     * still alive — both count as that side having lost.
     */
    enum End {
        WE_DIED, WE_OUT_OF_TOTEMS, SPARRING_DIED, SPARRING_OUT_OF_TOTEMS, TIME_UP
    }

    /** One run's outcome: how it ended ({@link End}), and the metrics task A1 requirement 3 lists. */
    record Outcome(End end, Metrics metrics) {
    }

    /**
     * Task B0b: the near-death fights' two-step start. {@link #ready} says, after {@code waited} warm-up
     * ticks, whether the warm-up hit has landed and things have settled; {@link #landed} whether our hit
     * ever landed; {@link #apply} sets the near-death moment (server side, the same for both auras).
     */
    interface Moment {
        boolean ready(int waited);

        boolean landed();

        /** Whether the aura under test is one that trusts targets, and the moment waits for it to. */
        boolean expectsTrust();

        /** Whether it does trust the target now. Client thread; only asked when {@link #expectsTrust}. */
        boolean trusting();

        void apply(MinecraftServer server);
    }

    /** Crystal placement packets sent from T0 to the close (task A4 requirement 2, log only): valid only
     * after {@link #play} returns. */
    int placementsSent() {
        return placementsSent;
    }

    /**
     * T0, then up to {@code nominalTicks} more ticks, stopping the instant either side loses (task A4
     * requirement 1: a real death, or running out of totems). Returns the outcome and the metrics.
     */
    Outcome play(int nominalTicks) {
        return play(0, nominalTicks, null);
    }

    /**
     * Task B0b: the same, with a warm-up first when {@code moment} is given: up to {@code warmupTicks} ticks
     * while both sides are healthy and our aura lands a hit (the trust the finishing blow needs), then the
     * near-death moment ({@link Moment#apply}), then {@code measureTicks} measured from it: every time,
     * count and damage below is from the moment on. A warm-up that never lands a hit says so: {@link
     * Metrics#WARMUP_MISSED}, and the run goes on to the moment anyway.
     */
    Outcome play(int warmupTicks, int measureTicks, Moment moment) {
        if (bench.fromClient(client -> Modules.get().get(underTest).isActive())) {
            throw new BenchException(underTest.getSimpleName() + " was on before T0");
        }
        nominalSeconds = Math.max(1, (warmupTicks + measureTicks) / 20);
        placementsAtT0 = bench.fromClient(client -> PlacementCounter.get().sent());
        bench.start(true, underTest);
        lastOwnPops = 0;
        lastSparringPops = 0;
        measureFrom = bench.ticksUsed() - bench.sinceT0();
        nearDeath = moment != null;
        End end = sample();
        if (end == null && moment != null) {
            measuring = false;
            int waited = 0;
            while (!moment.ready(waited)) {
                if (waited >= warmupTicks) {
                    warmupMissed = !moment.landed();
                    trustMissed = moment.expectsTrust() && !moment.trusting();
                    break;
                }
                bench.ticks(1);
                waited++;
                end = sample();
                if (end != null) break;
            }
            if (end == null) {
                bench.onServer(moment::apply);
                measuring = true;
                measureFrom = bench.ticksUsed();
                popsTakenBase = popsTaken;
                Sparring.Stats now = bench.sparringStats();
                sparringPopsBase = now.pops();
                sparringOthersBase = now.popsFromOthers();
                watch.reset();
                logTrustNext = true;
                sparringDamageBase = now.damageTaken();
                lastSparringPops = now.pops();
                minHealth = Double.POSITIVE_INFINITY;
                LOG.info("[bench] near-death moment after {} warm-up tick(s), warm-up hit {}; crystal-aura++ trusted "
                    + "the target on {} pre-tick(s) so far (log only)", waited, moment.landed() ? "landed" : "MISSED",
                    watch.trustedTicks());
            }
        }
        if (end == null) {
            for (int tick = 1; tick <= measureTicks; tick++) {
                bench.ticks(1);
                end = sample();
                if (end != null) break;
            }
        }
        return finish(end == null ? End.TIME_UP : end);
    }

    /** One tick's read: health, pop detection on both sides (events, not a poll), and the gapple schedules.
     * Null while the fight goes on. */
    private End sample() {
        int tick = bench.ticksUsed();
        double[] us = bench.fromClient(client -> client.player == null ? null
            : new double[] {client.player.isDead() ? 0 : client.player.getHealth() + client.player.getAbsorptionAmount(),
                client.player.isDead() ? 1 : 0});
        if (us == null) throw new BenchException("the client has no player");
        boolean weDied = us[1] > 0;
        if (weDied && watch.totems() >= 1) diedWithTotem = true;
        watch.observe(us[0]);
        if (logTrustNext && underTest == CrystalAuraPlusPlus.class) {
            logTrustNext = false;
            LOG.info("[bench] first tick after health was forced to near death: crystal-aura++ trusts {} target(s) "
                + "(log only)", watch.lastTrusted());
        }

        int ownPopsNow = bench.fromClient(client -> Modules.get().get(FightRecorder.class).live()
            .map(FightTracker.LiveFight::yourPops).orElse(0));
        if (ownPopsNow > lastOwnPops) {
            popsTaken += ownPopsNow - lastOwnPops;
            lastOwnPops = ownPopsNow;
            if (firstPopTakenTick < 0 && measuring) firstPopTakenTick = bench.ticksUsed() - measureFrom;
            ourGapple.pop(tick);
        }
        if (weDied) {
            ourGapple.death();
            return End.WE_DIED;
        }
        minHealth = Math.min(minHealth, us[0]);
        // Task A4 requirement 1: our own totem supply is the fight loadout's fixed FIGHT_TOTEMS (Arena);
        // once every one is used we have lost, whether or not a real lethal hit ever lands before the time
        // limit. We are still alive here (the totem just saved us), so the gapple schedule stays armed.
        if (FightResult.outOfTotems(popsTaken - popsTakenBase, Arena.FIGHT_TOTEMS)) {
            return End.WE_OUT_OF_TOTEMS;
        }

        Sparring.Stats sparringNow = bench.sparringStats();
        int sparringPopsNow = sparringNow.pops();
        if (sparringPopsNow > lastSparringPops) {
            lastSparringPops = sparringPopsNow;
            sparringGapple.pop(tick);
        }
        // Our first kill or pop only: one the sparring's own crystals caused does not count (task B0b).
        if (measuring && firstBlowTick < 0
            && (sparringNow.popsFromOthers() > sparringOthersBase || sparringNow.killedByOthers())) {
            firstBlowTick = bench.ticksUsed() - measureFrom;
        }
        if (bench.sparringDied()) {
            sparringGapple.death();
            return End.SPARRING_DIED;
        }
        if (FightResult.outOfTotems(sparringPopsNow - sparringPopsBase, Arena.FIGHT_TOTEMS)) {
            return End.SPARRING_OUT_OF_TOTEMS;
        }

        if (ourGapple.due(tick)) {
            String player = bench.player();
            bench.onServer(srv -> GappleEffects.apply(Arena.player(srv, player)));
        }
        if (sparringGapple.due(tick)) bench.onServer(srv -> GappleEffects.apply(bench.sparring()));
        return null;
    }

    /** Task B0b, log only: what the finishing-blow bookkeeping saw. */
    void logFinishing(String scenario, int run) {
        LOG.info("[bench] {} run {}: {} finishing blow(s), {} of them hit us, {} recorder event(s) taken out of "
            + "min_health_after_own_hit ({} blow(s) shared their tick with another own hit and hid none); the target was trusted on {} pre-tick(s), first {} tick(s) after T0; died "
            + "holding a totem: {} (log only, not a metric)", scenario, run, finishingBlows, finishingHits,
            finishingEventsExcluded, finishingAmbiguous, watch.trustedTicks(), watch.firstTrustedTick(), diedWithTotem);
    }

    private Outcome finish(End end) {
        List<FightRecord> records = bench.finish();
        if (watch.active()) {
            // The fight ends on the tick the server sees the target die; the client removes the crystal that did it
            // a tick later, in the tick finish() just waited: read it, or the blow that ended the fight goes uncounted.
            double health = bench.fromClient(client -> client.player == null || client.player.isDead() ? 0
                : client.player.getHealth() + client.player.getAbsorptionAmount());
            watch.observe(health);
        }
        for (FightRecord record : records) {
            if (record.damageEventsDropped() > 0) {
                throw new BenchException("a record dropped " + record.damageEventsDropped() + " damage events");
            }
        }
        Sparring.Stats sparring = bench.sparringStats();
        if (sparring.damageTaken() > sparring.rawDamage() + 1e-3) {
            throw new BenchException("the sparring lost more health than it was dealt");
        }
        // Fix round 1: the live pop tally above and the closed records' own count (MeasureRun.selfPops) are
        // the same underlying counter, read at two different times; a mismatch means one of them is wrong
        // and the run must say so loudly, never pick one silently.
        int recordedSelfPops = MeasureRun.selfPops(records);
        if (popsTaken != recordedSelfPops) {
            throw new BenchException("pops_taken counted " + popsTaken + " live but the closed record(s) counted "
                + recordedSelfPops);
        }
        int result = FightResult.result(end == End.WE_DIED || end == End.WE_OUT_OF_TOTEMS,
            end == End.SPARRING_DIED || end == End.SPARRING_OUT_OF_TOTEMS);
        int popsDealt = sparring.pops() - sparringPopsBase;
        int popsTakenMeasured = popsTaken - popsTakenBase;
        int netPops = FightResult.netPops(popsDealt, popsTakenMeasured);
        List<FightRecord.DamageEvent> damage = records.stream().flatMap(r -> r.damage().stream()).toList();
        // Task B0b: the reserve rule is for ordinary own hits; a finishing hit may take us below it on purpose.
        var resolution = watch.resolve(records);
        finishingEventsExcluded = resolution.excluded().size();
        finishingAmbiguous = resolution.ambiguous();
        finishingBlows = resolution.offenseCount();
        finishingHits = resolution.count();
        // Task C2 (I2): a hit from a crystal we placed is ours even when the sparring's autobreak set it off.
        OptionalDouble minAfterOwnHit = MinHealthAfterOwnHit.of(damage, resolution.excluded(), watch.ownIndexes(damage));
        LOG.info("own crystals: {} hits on us came from a crystal of ours the recorder blamed on the opponent; at most {} of ours"
            + " went gone without an attack of ours (an upper bound that misses a crystal broken in the tick it appeared)", watch.ownHitsBlamedElsewhere(records), watch.ownCrystalsGoneUnattacked());
        placementsSent = bench.fromClient(client -> PlacementCounter.get().sent()) - placementsAtT0;

        Metrics metrics = new Metrics()
            .put(Metrics.RESULT, result)
            .put(Metrics.POPS_DEALT, popsDealt)
            .put(Metrics.POPS_TAKEN, popsTakenMeasured)
            .put(Metrics.NET_POPS, netPops)
            .put(Metrics.DAMAGE_DEALT, sparring.damageTaken() - sparringDamageBase)
            .put(Metrics.SELF_DAMAGE, watch.ownDamage(records))
            .put(Metrics.MIN_HEALTH, minHealth)
            .put(Metrics.PLACEMENTS_PER_S, (double) placementsSent / nominalSeconds);
        if (firstPopTakenTick >= 0) metrics.put(Metrics.FIRST_POP_TAKEN_S, firstPopTakenTick / 20.0);
        if (minAfterOwnHit.isPresent()) metrics.put(Metrics.MIN_HEALTH_AFTER_OWN_HIT, minAfterOwnHit.getAsDouble());
        watch.put(metrics, resolution);
        if (diedWithTotem) metrics.put(Metrics.DIED_WITH_TOTEM, 1);
        if (nearDeath) {
            if (firstBlowTick >= 0) metrics.put(Metrics.FIRST_BLOW_S, firstBlowTick / 20.0);
            metrics.put(Metrics.WARMUP_MISSED, warmupMissed ? 1 : 0);
            if (underTest == CrystalAuraPlusPlus.class) metrics.put(Metrics.TRUST_MISSED, trustMissed ? 1 : 0);
        }
        return new Outcome(end, metrics);
    }
}
