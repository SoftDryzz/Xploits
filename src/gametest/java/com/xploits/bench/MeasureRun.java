package com.xploits.bench;

import com.xploits.pvp.recorder.FightRecorder;
import com.xploits.pvp.recorder.core.CombatEvent.AttackerKind;
import com.xploits.pvp.recorder.core.FightOutcome;
import com.xploits.pvp.recorder.core.FightRecord;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.CrystalAura;

import java.util.List;

/**
 * What every MEASURE run shares (spec {@code 2026-09-25-ingame-bench}, §Run timeline, §Metrics): the
 * recorder reset, T0 with the module under test, our health sampled every tick from T0, and the close
 * with the rules that make a run ERROR:
 * <ul>
 *   <li>our death, or a record that ends LOST;</li>
 *   <li>a record that dropped damage events;</li>
 *   <li>no record at all although a crystal placement packet was sent (a failed save).</li>
 * </ul>
 * The totals are summed over <b>all</b> the new records: a lull can split one run into two.
 */
final class MeasureRun {
    private final Bench bench;
    private final Class<? extends Module> underTest;
    private int placementsAtT0;
    private int placementsAtClose;
    private double minHealth = Double.POSITIVE_INFINITY;
    private boolean sampling;
    private List<FightRecord> records;

    /**
     * Resets the recorder and makes sure the module under test is off; call it in {@code arrange} before
     * the loadout. The module itself is reset by the scenario (it may have overrides).
     */
    MeasureRun(Bench bench, Class<? extends Module> underTest) {
        this.bench = bench;
        this.underTest = underTest;
        bench.meteor(FightRecorder.class);
        bench.onClient(client -> {
            Module module = Modules.get().get(underTest);
            if (module.isActive()) module.disable();
            PlacementCounter.get();
        });
    }

    /** CrystalAura reset, with the one override every scenario gets: {@code pause-on-lag} off. */
    static CrystalAura crystalAura(Bench bench) {
        CrystalAura aura = bench.meteor(CrystalAura.class);
        bench.setting(aura, "Pause", "pause-on-lag", false);
        return aura;
    }

    /**
     * T0: the recorder, then the module under test, in one client call; the placement count is taken
     * just before. Our health is sampled now and after every tick until {@link #close}.
     */
    void start() {
        if (bench.fromClient(client -> Modules.get().get(underTest).isActive())) {
            throw new BenchException(underTest.getSimpleName() + " was on before T0");
        }
        placementsAtT0 = bench.fromClient(client -> PlacementCounter.get().sent());
        bench.start(true, underTest);
        sampling = true;
        sample();
        bench.everyTick(() -> {
            if (sampling) sample();
        });
    }

    /** Our health plus absorption on the client; our death ends the run as ERROR. */
    private void sample() {
        double[] health = bench.fromClient(client -> client.player == null ? null
            : new double[] {client.player.isDead() ? 0 : client.player.getHealth() + client.player.getAbsorptionAmount(),
                client.player.isDead() ? 1 : 0});
        if (health == null) throw new BenchException("the client has no player");
        if (health[1] > 0) throw new BenchException("the player died");
        minHealth = Math.min(minHealth, health[0]);
    }

    /**
     * The close (§Run timeline 5): sampling stops and the placement count is taken, then the recorder is
     * turned off, one tick passes, and the new records are read and checked.
     */
    List<FightRecord> close() {
        sampling = false;
        placementsAtClose = bench.fromClient(client -> PlacementCounter.get().sent());
        records = bench.finish();
        for (FightRecord record : records) {
            if (record.outcome() == FightOutcome.LOST) throw new BenchException("a record ends LOST");
            if (record.damageEventsDropped() > 0) {
                throw new BenchException("a record dropped " + record.damageEventsDropped() + " damage events");
            }
        }
        int placed = placementsSent();
        if (records.isEmpty() && placed > 0) {
            throw new BenchException("no fight was recorded although " + placed + " crystal placements were sent");
        }
        return records;
    }

    /** Crystal placement packets sent from T0 to the close. */
    int placementsSent() {
        return placementsAtClose - placementsAtT0;
    }

    /** The lowest health plus absorption seen from T0 to the close. */
    double minHealth() {
        return minHealth;
    }

    // --- Totals over all the new records ----------------------------------------------------------

    /** Health lost to our own crystals: {@code before - after} of every damage event by SELF. */
    double selfDamage() {
        return records().stream().flatMap(r -> r.damage().stream())
            .filter(d -> d.by() == AttackerKind.SELF).mapToDouble(d -> d.before() - d.after()).sum();
    }

    int selfPops() {
        return records().stream().mapToInt(r -> r.self().pops()).sum();
    }

    double damageTaken() {
        return records().stream().mapToDouble(r -> r.self().damageTaken()).sum();
    }

    /** Crystal placement packets the records counted. */
    int crystalsPlaced() {
        return records().stream().mapToInt(r -> r.self().crystalsPlaced()).sum();
    }

    private List<FightRecord> records() {
        if (records == null) throw new BenchException("the records were read before the close");
        return records;
    }
}
