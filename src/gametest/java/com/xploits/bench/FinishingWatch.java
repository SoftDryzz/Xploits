package com.xploits.bench;

import com.xploits.bench.core.FinishingTracker;
import com.xploits.pvp.crystal.CrystalAuraPlusPlus;
import com.xploits.pvp.recorder.FightRecorder;
import com.xploits.pvp.recorder.core.FightRecord.DamageEvent;
import meteordevelopment.meteorclient.systems.modules.Modules;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
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

    private record Read(Set<Integer> marked, Set<Integer> present, int totems, int trusted, long recorderTick) {
    }

    /**
     * One tick's read, after the tick. Returns whether this tick is inside a finishing blow's window
     * (crystal-aura++ only), so the caller can leave it out of what the reserve rules judge.
     */
    boolean observe(double health) {
        Read read = bench.fromClient(client -> {
            Set<Integer> marked = Set.of();
            Set<Integer> present = new HashSet<>();
            int trusted = 0;
            if (plusPlus) {
                CrystalAuraPlusPlus module = Modules.get().get(CrystalAuraPlusPlus.class);
                marked = module.finishingCrystalIds();
                for (Integer id : marked) {
                    if (client.world != null && client.world.getEntityById(id) != null) present.add(id);
                }
                trusted = module.trustedTargets();
            }
            return new Read(marked, present, client.player == null ? 0 : Arena.totemsCarried(client.player), trusted,
                plusPlus ? recorderTick(Modules.get().get(FightRecorder.class)) : 0);
        });
        boolean inWindow = false;
        if (plusPlus) {
            Sparring sparring = bench.sparring();
            boolean targetTotem = bench.fromServer(srv -> sparring.totemsLeft() > 0);
            inWindow = tracker.observe(read.marked(), read.present(), health, read.totems(), read.recorderTick(), targetTotem);
            if (read.trusted() > 0) {
                trustedTicks++;
                if (firstTrustedTick < 0) firstTrustedTick = bench.sinceT0();
            }
        }
        totems = read.totems();
        return inWindow;
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

    /** The ticks of {@code damage} that were finishing hits ({@link FinishingTracker#hitTicks}). */
    Set<Long> hitTicks(List<DamageEvent> damage) {
        return FinishingTracker.hitTicks(damage, tracker.blows());
    }

    /**
     * The finishing-blow metrics, only when a blow happened: how many, the lowest health after one, the fewest
     * totems carried at one, how many were kill-grade and how many pop-grade (by the target's totem: the module's
     * own kinds, {@code CrystalBrain#finishingCrystalKinds}, are not read here), how many cost us a totem, and how
     * many broke a pop-grade blow's rule.
     */
    void put(Metrics metrics) {
        if (tracker.count() == 0) return;
        metrics.put(Metrics.FINISHING_BLOWS, tracker.count());
        metrics.put(Metrics.MIN_HEALTH_AFTER_FINISHING_HIT, tracker.minHealthAfter().orElseThrow());
        metrics.put(Metrics.TOTEMS_AT_FINISHING_HIT_MIN, tracker.minTotems().orElseThrow());
        metrics.put(Metrics.FINISHING_KILL_BLOWS, tracker.kills());
        metrics.put(Metrics.FINISHING_POP_BLOWS, tracker.popBlows());
        metrics.put(Metrics.FINISHING_POPS, tracker.pops());
        metrics.put(Metrics.FINISHING_POP_GRADE_VIOLATIONS, tracker.popGradeViolations());
    }

    int blows() {
        return tracker.count();
    }
}
