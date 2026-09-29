package com.xploits.bench.core;

import com.xploits.pvp.recorder.core.CombatEvent.AttackerKind;
import com.xploits.pvp.recorder.core.FightRecord.DamageEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Task B0b: tells the finishing blows of crystal-aura++ (the override crystals of task B0a) apart from its
 * ordinary hits, tick by tick, from what the client sees. The recorder's damage events do not say which
 * crystal caused them, so the bench watches the ids the module has marked as finishing crystals
 * ({@code CrystalAuraPlusPlus#finishingCrystalIds}): the tick one of them is gone from the world it has
 * exploded, and the health and totems we carried on the tick before are the ones the blow met.
 *
 * <p>Pure: {@link #observe} takes the tick's facts, nothing else. A crystal is followed from the first tick
 * its id is marked; it counts as exploded as soon as it is no longer present (a crystal already gone the
 * first time it is seen counts on that tick). The health after the blow is the lowest health plus
 * absorption over {@value #WINDOW} ticks from the explosion: the damage packet, the totem's pop and the
 * health update land on the client one or two ticks apart, so a single sample could still be the health
 * from before. A dead player reads 0; a totem that saved us reads its own health (above 0).
 */
public final class FinishingTracker {
    /** Ticks, the explosion's own included, over which the lowest health is taken. */
    public static final int WINDOW = 3;
    /** How many earlier health samples a blow remembers as the health it may have come from. */
    private static final int HISTORY = 3;

    /**
     * One finishing crystal that exploded. {@code healthBefore}: the health plus absorption on the tick
     * before it (the value the recorder's damage event then shows as {@code before}); {@code earlier}: the
     * samples before that, newest first, in case the explosion was seen a tick or two late; {@code totems}:
     * carried on the tick before it, offhand and spares together; {@code tick}: the clock the recorder's
     * damage events use, on the tick the explosion was seen.
     */
    public record Blow(long tick, double healthBefore, List<Double> earlier, double healthAfter, int totems,
                       int totemsAfter, boolean targetHadTotem) {
        /** A blow that leaves the totem count as it was, against a target that holds a totem. */
        public Blow(long tick, double healthBefore, List<Double> earlier, double healthAfter, int totems) {
            this(tick, healthBefore, earlier, healthAfter, totems, totems, true);
        }

        /** Whether it cost us a totem: fewer carried at the lowest point of its window than before it. */
        public boolean popped() {
            return totemsAfter < totems;
        }

        /**
         * Whether it broke the rule of a pop-grade blow (task B0c, owner's decision 2026-09-29): a target that
         * holds a totem is a pop, never a kill, and a pop may take us below the reserve but never below
         * {@value FinishingTracker#POP_FLOOR}, and never pops us. The kind is inferred from the target's totem, not read from
         * the module.
         */
        public boolean popGradeViolation() {
            return targetHadTotem && (healthAfter < POP_FLOOR || popped());
        }
    }

    /** The lowest health plus absorption a pop-grade finishing blow may leave us at. */
    public static final double POP_FLOOR = 2.0;

    /** How far apart, in ticks, a blow and the recorder's damage event for it may be read. */
    public static final int TICK_TOLERANCE = 3;
    /** How far apart the health before a blow and the event's {@code before} may be. */
    public static final double HEALTH_TOLERANCE = 0.05;

    /** Every id marked so far: one is followed from the tick it is first marked. */
    private final Set<Integer> seen = new HashSet<>();
    /** The marked ids that were still in the world on the last tick. */
    private final Set<Integer> following = new HashSet<>();
    private final List<Double> history = new ArrayList<>();
    private int lastTotems;
    private final List<Open> open = new ArrayList<>();
    private final List<Blow> closed = new ArrayList<>();

    private static final class Open {
        final long tick;
        final double before;
        final List<Double> earlier;
        final int totems;
        final boolean targetHadTotem;
        double min;
        int minTotems;
        int left = WINDOW - 1;

        Open(long tick, double before, List<Double> earlier, int totems, boolean targetHadTotem, double now, int nowTotems) {
            this.tick = tick;
            this.before = before;
            this.earlier = earlier;
            this.totems = totems;
            this.targetHadTotem = targetHadTotem;
            this.min = now;
            this.minTotems = Math.min(totems, nowTotems);
        }

        Blow blow() {
            return new Blow(tick, before, List.copyOf(earlier), min, totems, minTotems, targetHadTotem);
        }
    }

    /**
     * One tick's read.
     *
     * @param marked  the finishing crystal ids the module knows now
     * @param present which of those ids are still in the world
     * @param health  our health plus absorption now (0 when dead)
     * @param totems  the totems we carry now
     * @param tick    the recorder's clock now (the one its damage events carry)
     */
    public void observe(Set<Integer> marked, Set<Integer> present, double health, int totems, long tick) {
        observe(marked, present, health, totems, tick, true);
    }

    /**
     * The same, with {@code targetTotem}: whether the target held a totem on this tick (the next tick's
     * blow is against the target as it was on the tick before, {@link Blow#popGradeViolation}). Returns whether
     * this tick is inside a blow's window, the explosion's own tick and the last one included.
     */
    public boolean observe(Set<Integer> marked, Set<Integer> present, double health, int totems, long tick,
                           boolean targetTotem) {
        boolean inWindow = !open.isEmpty();
        for (Open blow : open) {
            blow.min = Math.min(blow.min, health);
            blow.minTotems = Math.min(blow.minTotems, totems);
            blow.left--;
        }
        for (Integer id : marked) {
            if (!seen.add(id)) continue;
            if (present.contains(id)) following.add(id);
            else inWindow |= explode(health, tick, totems);
        }
        for (Integer id : new ArrayList<>(following)) {
            if (present.contains(id)) continue;
            following.remove(id);
            inWindow |= explode(health, tick, totems);
        }
        closeDone();
        history.addFirst(health);
        while (history.size() > HISTORY + 1) history.removeLast();
        lastTotems = totems;
        lastTargetTotem = targetTotem;
        return inWindow;
    }

    /** Whether the target held a totem on the last tick observed; a blow met the target as it was then. */
    private boolean lastTargetTotem = true;

    private boolean explode(double now, long tick, int totemsNow) {
        if (history.isEmpty()) return false;
        double before = history.getFirst();
        List<Double> earlier = new ArrayList<>(history.subList(1, history.size()));
        open.add(new Open(tick, before, earlier, lastTotems, lastTargetTotem, now, totemsNow));
        return true;
    }

    private void closeDone() {
        open.removeIf(blow -> {
            if (blow.left > 0) return false;
            closed.add(blow.blow());
            return true;
        });
    }

    /** Every blow so far, the ones still being measured closed at their lowest health so far. */
    public List<Blow> blows() {
        List<Blow> all = new ArrayList<>(closed);
        for (Open blow : open) all.add(blow.blow());
        return List.copyOf(all);
    }

    /** {@code finishing_blows}: how many override crystals exploded. */
    public int count() {
        return blows().size();
    }

    /** {@code min_health_after_finishing_hit}: the lowest health after any blow; empty without one. */
    public OptionalDouble minHealthAfter() {
        return blows().stream().mapToDouble(Blow::healthAfter).min();
    }

    /** {@code finishing_pops}: how many blows cost us a totem. */
    public int pops() {
        return (int) blows().stream().filter(Blow::popped).count();
    }

    /** {@code finishing_pop_grade_violations}: blows against a target holding a totem that broke a pop-grade blow's rule. */
    public int popGradeViolations() {
        return (int) blows().stream().filter(Blow::popGradeViolation).count();
    }

    /** {@code totems_at_finishing_hit_min}: the fewest totems carried at any blow; empty without one. */
    public OptionalInt minTotems() {
        return blows().stream().mapToInt(Blow::totems).min();
    }

    /**
     * The ticks of the recorder's damage events that were finishing hits: the events from one of our own
     * crystals ({@code by == SELF}) read within {@value #TICK_TOLERANCE} ticks of a blow whose health before
     * (or one of the samples just before it) is the event's own {@code before}.
     */
    public static Set<Long> hitTicks(List<DamageEvent> damage, List<Blow> blows) {
        Set<Long> ticks = new HashSet<>();
        for (Blow blow : blows) {
            for (DamageEvent event : damage) {
                if (event.by() != AttackerKind.SELF) continue;
                if (Math.abs(event.tick() - blow.tick()) > TICK_TOLERANCE) continue;
                if (near(event.before(), blow.healthBefore()) || blow.earlier().stream().anyMatch(h -> near(event.before(), h))) {
                    ticks.add(event.tick());
                }
            }
        }
        return ticks;
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) <= HEALTH_TOLERANCE;
    }
}
