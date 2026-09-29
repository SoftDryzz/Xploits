package com.xploits.bench;

import com.xploits.bench.core.FinishingTracker;
import com.xploits.pvp.crystal.CrystalAuraPlusPlus;
import com.xploits.pvp.crystal.core.FinishKind;
import com.xploits.pvp.recorder.core.FightRecord;
import com.xploits.pvp.recorder.FightRecorder;
import com.xploits.pvp.recorder.core.FightRecord.DamageEvent;
import meteordevelopment.meteorclient.systems.modules.Modules;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Task B0b: what a run watches every tick to tell crystal-aura++'s finishing blows apart from its ordinary hits
 * ({@link FinishingTracker}, the pure part): the finishing crystals the module has marked
 * ({@link CrystalAuraPlusPlus#finishingCrystalIds}) and which are still in the world, our health and the totems
 * we carry, whether the target holds a totem, the recorder's own clock (the one its damage events carry), and,
 * log only, on how many pre-ticks the module trusted a target. With Meteor's aura (or anything else under test)
 * it only counts our totems. Both the fight runs ({@link FightMeasureRun}) and the older measures
 * ({@link MeasureRun}) use it, so their finishing-blow metrics mean the same.
 */
final class FinishingWatch {
    private static Field recorderTickField;

    private final Bench bench;
    private final boolean plusPlus;
    private final FinishingTracker tracker = new FinishingTracker();
    private int totems = -1;
    private int trustedTicks;
    private int firstTrustedTick = -1;

    FinishingWatch(Bench bench, boolean plusPlus) {
        this.bench = bench;
        this.plusPlus = plusPlus;
    }

    private record Read(Map<Integer, FinishKind> marked, Set<Integer> present, int totems, int trusted, long recorderTick) {
    }

    private int lastTrusted;

    /** One tick's read, after the tick (crystal-aura++ only; anything else just counts our totems). */
    void observe(double health) {
        Read read = bench.fromClient(client -> {
            Map<Integer, FinishKind> marked = Map.of();
            Set<Integer> present = new HashSet<>();
            int trusted = 0;
            if (plusPlus) {
                CrystalAuraPlusPlus module = Modules.get().get(CrystalAuraPlusPlus.class);
                marked = module.finishingCrystalKinds();
                for (Integer id : marked.keySet()) {
                    if (client.world != null && client.world.getEntityById(id) != null) present.add(id);
                }
                trusted = module.trustedTargets();
            }
            return new Read(marked, present, client.player == null ? 0 : Arena.totemsCarried(client.player), trusted,
                plusPlus ? recorderTick(Modules.get().get(FightRecorder.class)) : 0);
        });
        if (plusPlus) {
            tracker.observe(read.marked(), read.present(), health, read.totems(), read.recorderTick());
            lastTrusted = read.trusted();
            if (read.trusted() > 0) {
                trustedTicks++;
                if (firstTrustedTick < 0) firstTrustedTick = bench.sinceT0();
            }
        }
        totems = read.totems();
    }

    /** Forgets the blows so far (the near-death moment: warm-up blows do not count). */
    void reset() {
        tracker.reset();
    }

    /** How many targets crystal-aura++ trusted on the last tick observed. */
    int lastTrusted() {
        return lastTrusted;
    }

    /** The recorder's own tick count, the clock its damage events carry (a private counter read in place). */
    private static long recorderTick(FightRecorder recorder) {
        try {
            if (recorderTickField == null) {
                recorderTickField = FightRecorder.class.getDeclaredField("tick");
                recorderTickField.setAccessible(true);
            }
            return recorderTickField.getLong(recorder);
        } catch (ReflectiveOperationException e) {
            throw new BenchException("cannot read the recorder tick: " + e);
        }
    }

    /** Whether the module under test is crystal-aura++, the only one with finishing blows to watch. */
    boolean active() {
        return plusPlus;
    }

    /** The totems we carried on the last tick observed; -1 before the first. */
    int totems() {
        return totems;
    }

    int trustedTicks() {
        return trustedTicks;
    }

    int firstTrustedTick() {
        return firstTrustedTick;
    }

    /** The recorder's damage events of {@code records}, in order: the list the indexes of a resolution refer to. */
    static List<DamageEvent> events(List<FightRecord> records) {
        return records.stream().flatMap(r -> r.damage().stream()).toList();
    }

    /** The blows among what was watched, given the closed records' events ({@link FinishingTracker#resolve}). */
    FinishingTracker.Resolution resolve(List<FightRecord> records) {
        return tracker.resolve(events(records));
    }

    /**
     * The lowest health for the reserve rules, from ordinary hits only (S1-S3): the samples outside the
     * confirmed blows' windows, and the health after every event that is not a finishing hit, so an ordinary
     * breach inside a blow's window still shows. {@code raw} when this is not crystal-aura++.
     */
    double ordinaryMinHealth(List<FightRecord> records, double raw) {
        if (!plusPlus) return raw;
        List<DamageEvent> damage = events(records);
        FinishingTracker.Resolution r = tracker.resolve(damage);
        double min = tracker.minHealthOutside(r.blows()).orElse(Double.POSITIVE_INFINITY);
        for (int i = 0; i < damage.size(); i++) {
            if (r.excluded().contains(i) || !(damage.get(i).after() > 0)) continue;
            min = Math.min(min, damage.get(i).after());
        }
        return Double.isFinite(min) ? min : raw;
    }

    /**
     * The finishing-blow metrics, only when a blow happened (a crystal the module marked that exploded and
     * dealt us damage): how many, the lowest health after one, the fewest totems carried at one, how many the
     * module marked kill-grade and pop-grade, how many cost us a totem, and how many broke the pop-grade rule.
     */
    void put(Metrics metrics, FinishingTracker.Resolution r) {
        if (r.count() == 0) return;
        metrics.put(Metrics.FINISHING_BLOWS, r.count());
        metrics.put(Metrics.MIN_HEALTH_AFTER_FINISHING_HIT, r.minHealthAfter().orElseThrow());
        metrics.put(Metrics.TOTEMS_AT_FINISHING_HIT_MIN, r.minTotems().orElseThrow());
        metrics.put(Metrics.FINISHING_KILL_BLOWS, r.kills());
        metrics.put(Metrics.FINISHING_POP_BLOWS, r.popBlows());
        metrics.put(Metrics.FINISHING_POPS, r.pops());
        metrics.put(Metrics.FINISHING_POP_GRADE_VIOLATIONS, r.popGradeViolations());
    }
}
