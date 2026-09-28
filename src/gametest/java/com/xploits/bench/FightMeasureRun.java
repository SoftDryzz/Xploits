package com.xploits.bench;

import com.xploits.bench.core.FightResult;
import com.xploits.bench.core.GappleSchedule;
import com.xploits.bench.core.MinHealthAfterOwnHit;
import com.xploits.pvp.recorder.FightRecorder;
import com.xploits.pvp.recorder.core.FightRecord;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.item.Items;

import java.util.List;
import java.util.OptionalDouble;

/**
 * A fight-mode run (task A1, opt-in): unlike {@link MeasureRun}, our death and the sparring's death end the
 * run with a {@code result} instead of ERROR (requirement 3), both players carry
 * {@value Arena#FIGHT_TOTEMS} totems (requirement 1 and 2), and every pop of either player schedules the
 * enchanted golden apple's effects {@value com.xploits.bench.core.GappleSchedule#DELAY_TICKS} ticks later
 * (requirement 4). It runs the scenario's nominal length, or stops early the tick either player dies,
 * whichever comes first — a fight in progress is never static, so it never settles ({@code Settle}
 * requirement 5): there is always a chance our own death, the sparring's, or the next pop is still ahead.
 *
 * <p>Pop detection is a live poll, once per bench tick: our own offhand totem's presence (vanilla removes a
 * popped totem from the hand at once; AutoTotem's own refill lands a tick or more later, so the transition
 * is real and not always instant) and the sparring's own pop counter (updated inside
 * {@link Sparring#damage}, immediately). Both drive the gapple schedule and the {@code pops_taken} /
 * {@code pops_dealt} metrics directly, rather than waiting for the closed {@link FightRecord}s: those
 * records are only read once, at the close, for {@code self_damage}, {@code damage_dealt} and
 * {@code min_health_after_own_hit} (task A1 requirement 3), which need the recorder's own attribution
 * ({@code AttackerKind.SELF}) that a live poll cannot see.
 *
 * <p>A1 wires this class only enough to prove the building blocks work (one temporary probe run, removed
 * before the commit — see the task report); A2 gives the sparring a script that attacks back, and A3 turns
 * this into the three real fight scenarios.
 */
final class FightMeasureRun {
    private final Bench bench;
    private final Class<? extends Module> underTest;

    private double minHealth = Double.POSITIVE_INFINITY;
    private boolean weHadTotem = true;
    private int popsTaken;
    private int firstPopTakenTick = -1;
    private int lastSparringPops;
    private final GappleSchedule ourGapple = new GappleSchedule();
    private final GappleSchedule sparringGapple = new GappleSchedule();
    private int placementsAtT0;
    private int nominalSeconds;

    FightMeasureRun(Bench bench, Class<? extends Module> underTest) {
        this.bench = bench;
        this.underTest = underTest;
        bench.meteor(FightRecorder.class);
        bench.onClient(client -> {
            Module module = Modules.get().get(underTest);
            if (module.isActive()) module.disable();
            PlacementCounter.get();
        });
    }

    /** How the fight ended. */
    enum End {
        WE_DIED, SPARRING_DIED, TIME_UP
    }

    /** One run's outcome: how it ended ({@link End}), and the metrics task A1 requirement 3 lists. */
    record Outcome(End end, Metrics metrics) {
    }

    /**
     * T0, then up to {@code nominalTicks} more ticks, stopping the instant either player dies. Returns the
     * outcome and the metrics.
     */
    Outcome play(int nominalTicks) {
        if (bench.fromClient(client -> Modules.get().get(underTest).isActive())) {
            throw new BenchException(underTest.getSimpleName() + " was on before T0");
        }
        nominalSeconds = Math.max(1, nominalTicks / 20);
        placementsAtT0 = bench.fromClient(client -> PlacementCounter.get().sent());
        bench.start(true, underTest);
        weHadTotem = true;
        lastSparringPops = 0;
        End end = sample();
        if (end == null) {
            for (int tick = 1; tick <= nominalTicks; tick++) {
                bench.ticks(1);
                end = sample();
                if (end != null) break;
            }
        }
        return finish(end == null ? End.TIME_UP : end);
    }

    /** One tick's poll: health, pop detection on both sides, and the gapple schedules. Null while the fight goes on. */
    private End sample() {
        int tick = bench.ticksUsed();
        double[] us = bench.fromClient(client -> client.player == null ? null
            : new double[] {client.player.isDead() ? 0 : client.player.getHealth() + client.player.getAbsorptionAmount(),
                client.player.isDead() ? 1 : 0,
                client.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING) ? 1 : 0});
        if (us == null) throw new BenchException("the client has no player");
        boolean weDied = us[1] > 0;
        boolean weHaveTotemNow = us[2] > 0;
        if (weHadTotem && !weHaveTotemNow) {
            popsTaken++;
            if (firstPopTakenTick < 0) firstPopTakenTick = bench.sinceT0();
            ourGapple.pop(tick);
        }
        weHadTotem = weHaveTotemNow;
        if (weDied) {
            ourGapple.death();
            return End.WE_DIED;
        }
        minHealth = Math.min(minHealth, us[0]);

        int sparringPopsNow = bench.sparringStats().pops();
        if (sparringPopsNow > lastSparringPops) {
            lastSparringPops = sparringPopsNow;
            sparringGapple.pop(tick);
        }
        if (bench.sparringDied()) {
            sparringGapple.death();
            return End.SPARRING_DIED;
        }

        if (ourGapple.due(tick)) {
            String player = bench.player();
            bench.onServer(srv -> GappleEffects.apply(Arena.player(srv, player)));
        }
        if (sparringGapple.due(tick)) bench.onServer(srv -> GappleEffects.apply(bench.sparring()));
        return null;
    }

    private Outcome finish(End end) {
        List<FightRecord> records = bench.finish();
        for (FightRecord record : records) {
            if (record.damageEventsDropped() > 0) {
                throw new BenchException("a record dropped " + record.damageEventsDropped() + " damage events");
            }
        }
        Sparring.Stats sparring = bench.sparringStats();
        if (sparring.damageTaken() > sparring.rawDamage() + 1e-3) {
            throw new BenchException("the sparring lost more health than it was dealt");
        }
        int result = FightResult.result(end == End.WE_DIED, end == End.SPARRING_DIED);
        int popsDealt = sparring.pops();
        int netPops = FightResult.netPops(popsDealt, popsTaken);
        List<FightRecord.DamageEvent> damage = records.stream().flatMap(r -> r.damage().stream()).toList();
        OptionalDouble minAfterOwnHit = MinHealthAfterOwnHit.of(damage);
        int placementsSent = bench.fromClient(client -> PlacementCounter.get().sent()) - placementsAtT0;

        Metrics metrics = new Metrics()
            .put(Metrics.RESULT, result)
            .put(Metrics.POPS_DEALT, popsDealt)
            .put(Metrics.POPS_TAKEN, popsTaken)
            .put(Metrics.NET_POPS, netPops)
            .put(Metrics.DAMAGE_DEALT, sparring.damageTaken())
            .put(Metrics.SELF_DAMAGE, MeasureRun.selfDamage(records))
            .put(Metrics.MIN_HEALTH, minHealth)
            .put(Metrics.PLACEMENTS_PER_S, (double) placementsSent / nominalSeconds);
        if (firstPopTakenTick >= 0) metrics.put(Metrics.FIRST_POP_TAKEN_S, firstPopTakenTick / 20.0);
        if (minAfterOwnHit.isPresent()) metrics.put(Metrics.MIN_HEALTH_AFTER_OWN_HIT, minAfterOwnHit.getAsDouble());
        return new Outcome(end, metrics);
    }
}
