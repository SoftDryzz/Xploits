package com.xploits.bench.core;

import com.xploits.pvp.crystal.core.FinishKind;
import com.xploits.pvp.recorder.core.CombatEvent.AttackerKind;
import com.xploits.pvp.recorder.core.FightRecord.DamageEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Task B0b (fix round 1): tells the finishing blows of crystal-aura++ apart from its ordinary hits. The
 * recorder's damage events do not say which crystal caused them, so the bench watches the ids the module has
 * marked as finishing crystals ({@code CrystalAuraPlusPlus#finishingCrystalKinds}, each with its real kind)
 * and, in two steps:
 * <ol>
 *   <li>tick by tick ({@link #observe}): a marked crystal gone from the world has exploded; that is a
 *   <i>candidate</i>, with the health and totems we carried on the tick before, and, over {@value #WINDOW}
 *   ticks, the lowest health and fewest totems (the damage packet, the totem's pop and the health update land
 *   one or two ticks apart);</li>
 *   <li>at the close ({@link #resolve}): a candidate is a <i>blow</i> only when the recorder has an event of
 *   ours for it, so one the opponent broke, or that dealt us nothing, is not one. Exactly one event per
 *   candidate: the nearest tick within {@value #TICK_TOLERANCE} whose {@code before} is the health before the
 *   candidate, never one already taken. If another event of ours falls on that same tick (two crystals
 *   exploding together) the two cannot be told apart: the blow counts but no event is excluded, so an
 *   ordinary hit is never hidden from the reserve rules.</li>
 * </ol>
 * Pure: {@link #observe} takes the tick's facts, nothing else. A crystal's kind is the highest it was ever
 * seen with (a crystal seen KILL stays KILL).
 *
 * <p>Offense and safety apart (task T2): a candidate is a marked crystal that WE attacked and that left the world
 * ({@link Resolution#offense}: {@code finishing_blows}, whether or not it hurt us); a <i>blow</i>, or hit
 * ({@link Resolution#blows}: {@code finishing_hits}), is a candidate the recorder has an event for. Every safety
 * measure reads the hits only. An event is taken for a candidate only when it is not later than
 * {@value #LATE_TOLERANCE} tick after it (an ordinary hit landing 2 ticks after a crystal that did not hurt us
 * must not be borrowed; the damage packet does not trail the removal by more).
 */
public final class FinishingTracker {
    /** Ticks, the explosion's own included, over which the lowest health is taken. */
    public static final int WINDOW = 3;
    /** How far apart, in ticks, a candidate and the recorder's event for it may be read. */
    public static final int TICK_TOLERANCE = 3;
    /** How many ticks after the candidate the recorder's event may be read (task T2, minor of the B0b re-review). */
    public static final int LATE_TOLERANCE = 1;
    /** How far apart the health before a candidate and the event's {@code before} may be. */
    public static final double HEALTH_TOLERANCE = 0.05;
    /** The lowest health plus absorption a pop-grade finishing blow may leave us at. */
    public static final double POP_FLOOR = 2.0;

    /**
     * One finishing crystal that exploded and dealt us damage. {@code tick}: the recorder's clock on the tick
     * the explosion was seen; {@code healthBefore}: health plus absorption on the tick before; {@code
     * healthAfter}: the lowest over the window; {@code totems}/{@code totemsAfter}: carried on the tick
     * before / lowest over the window; {@code kill}: the module marked it kill-grade (else pop-grade).
     */
    public record Blow(long tick, double healthBefore, double healthAfter, int totems, int totemsAfter, boolean kill) {
        /** Whether it cost us a totem: fewer carried at the lowest point of its window than before it. */
        public boolean popped() {
            return totemsAfter < totems;
        }

        /**
         * Whether it broke the rule of a pop-grade blow (task B0c): a crystal marked POP may take us below the
         * reserve but never below {@value FinishingTracker#POP_FLOOR}, and never pops us. Whatever the target holds.
         */
        public boolean popGradeViolation() {
            return !kill && (healthAfter < POP_FLOOR || popped());
        }
    }

    /** The blows that have an event (the hits), the offense (all candidates), and the indexes (in the list given to {@link #resolve}) to leave out. */
    public record Resolution(List<Blow> blows, Set<Integer> excluded, int ambiguous, List<Blow> offense) {
        /** Every marked crystal of ours that we attacked and that went off: {@code finishing_blows}. */
        public int offenseCount() {
            return offense.size();
        }

        /** The kill-grade ones among {@link #offense}. */
        public int offenseKills() {
            return (int) offense.stream().filter(Blow::kill).count();
        }

        /** {@code finishing_hits}. */
        public int count() {
            return blows.size();
        }

        /** {@code min_health_after_finishing_hit}: the lowest health after any blow; empty without one. */
        public OptionalDouble minHealthAfter() {
            return blows.stream().mapToDouble(Blow::healthAfter).min();
        }

        /** {@code totems_at_finishing_hit_min}: the fewest totems carried at any blow; empty without one. */
        public OptionalInt minTotems() {
            return blows.stream().mapToInt(Blow::totems).min();
        }

        public int kills() {
            return (int) blows.stream().filter(Blow::kill).count();
        }

        public int popBlows() {
            return count() - kills();
        }

        /** {@code finishing_pops}: how many blows cost us a totem. */
        public int pops() {
            return (int) blows.stream().filter(Blow::popped).count();
        }

        /** {@code finishing_pop_grade_violations}. */
        public int popGradeViolations() {
            return (int) blows.stream().filter(Blow::popGradeViolation).count();
        }
    }

    private static final class Candidate {
        final long tick;
        final double before;
        final int totems;
        final boolean kill;
        double min;
        int minTotems;
        int left = WINDOW - 1;

        Candidate(long tick, double before, int totems, boolean kill, double now, int nowTotems) {
            this.tick = tick;
            this.before = before;
            this.totems = totems;
            this.kill = kill;
            this.min = now;
            this.minTotems = Math.min(totems, nowTotems);
        }

        Blow blow() {
            return new Blow(tick, before, min, totems, minTotems, kill);
        }
    }

    /** Every id marked so far, with the highest kind it was seen with. */
    private final Map<Integer, FinishKind> kinds = new HashMap<>();
    /** The marked ids that were still in the world on the last tick. */
    private final Set<Integer> following = new HashSet<>();
    private double lastHealth = Double.NaN;
    private int lastTotems;
    private final List<Candidate> open = new ArrayList<>();
    private final List<Candidate> closed = new ArrayList<>();
    private final List<long[]> sampleTicks = new ArrayList<>();
    private final List<Double> sampleHealth = new ArrayList<>();

    /**
     * One tick's read.
     *
     * @param marked  the finishing crystals the module knows now, with their kind
     * @param present which of those ids are still in the world
     * @param health  our health plus absorption now (0 when dead)
     * @param totems  the totems we carry now
     * @param tick    the recorder's clock now (the one its damage events carry)
     */
    public void observe(Map<Integer, FinishKind> marked, Set<Integer> present, double health, int totems, long tick) {
        observe(marked, present, marked.keySet(), health, totems, tick);
    }

    /**
     * The same, with {@code attacked}: which of the marked ids we attacked ourselves
     * ({@code CrystalAuraPlusPlus#finishingCrystalsAttacked}). Only one of those leaving the world is a candidate.
     */
    public void observe(Map<Integer, FinishKind> marked, Set<Integer> present, Set<Integer> attacked, double health,
                        int totems, long tick) {
        for (Candidate c : open) {
            c.min = Math.min(c.min, health);
            c.minTotems = Math.min(c.minTotems, totems);
            c.left--;
        }
        for (Map.Entry<Integer, FinishKind> e : marked.entrySet()) {
            int id = e.getKey();
            boolean first = !kinds.containsKey(id);
            FinishKind was = kinds.get(id);
            if (was != FinishKind.KILL) kinds.put(id, e.getValue());
            if (!first) continue;
            if (present.contains(id)) following.add(id);
            else if (attacked.contains(id)) explode(id, health, tick, totems);
        }
        for (Integer id : new ArrayList<>(following)) {
            if (present.contains(id)) continue;
            following.remove(id);
            if (attacked.contains(id)) explode(id, health, tick, totems);
        }
        open.removeIf(c -> {
            if (c.left > 0) return false;
            closed.add(c);
            return true;
        });
        sampleTicks.add(new long[] {tick});
        sampleHealth.add(health);
        lastHealth = health;
        lastTotems = totems;
    }

    private void explode(int id, double now, long tick, int totemsNow) {
        if (Double.isNaN(lastHealth)) return;
        open.add(new Candidate(tick, lastHealth, lastTotems, kinds.get(id) == FinishKind.KILL, now, totemsNow));
    }

    /** Forgets the candidates so far (the near-death moment: warm-up blows do not count); ids stay known. */
    public void reset() {
        open.clear();
        closed.clear();
        sampleTicks.clear();
        sampleHealth.clear();
    }

    private List<Candidate> candidates() {
        List<Candidate> all = new ArrayList<>(closed);
        all.addAll(open);
        all.sort((a, b) -> Long.compare(a.tick, b.tick));
        return all;
    }

    /**
     * The blows among the candidates, given the recorder's damage events ({@code damage}, in the order the
     * caller will index them): see the class comment. Deterministic; each event is taken at most once.
     */
    public Resolution resolve(List<DamageEvent> damage) {
        boolean[] taken = new boolean[damage.size()];
        List<Blow> blows = new ArrayList<>();
        Set<Integer> excluded = new HashSet<>();
        int ambiguous = 0;
        List<Blow> offense = new ArrayList<>();
        for (Candidate c : candidates()) {
            offense.add(c.blow());
            int best = -1;
            long bestGap = Long.MAX_VALUE;
            for (int i = 0; i < damage.size(); i++) {
                DamageEvent e = damage.get(i);
                if (taken[i] || e.by() != AttackerKind.SELF || !(e.before() > e.after())) continue;
                long gap = Math.abs(e.tick() - c.tick);
                if (gap > TICK_TOLERANCE || e.tick() - c.tick > LATE_TOLERANCE || Math.abs(e.before() - c.before) > HEALTH_TOLERANCE) continue;
                if (gap < bestGap) {
                    best = i;
                    bestGap = gap;
                }
            }
            if (best < 0) continue;
            taken[best] = true;
            blows.add(c.blow());
            long at = damage.get(best).tick();
            boolean together = false;
            for (int i = 0; i < damage.size(); i++) {
                if (i != best && damage.get(i).by() == AttackerKind.SELF && damage.get(i).tick() == at) together = true;
            }
            if (together) ambiguous++;
            else excluded.add(best);
        }
        return new Resolution(List.copyOf(blows), Set.copyOf(excluded), ambiguous, List.copyOf(offense));
    }

    /**
     * The lowest health sampled outside the windows of {@code blows}: what the reserve rules read as the lowest
     * health, without the ticks a finishing blow is being measured on. Empty without a sample.
     */
    public OptionalDouble minHealthOutside(List<Blow> blows) {
        double min = Double.POSITIVE_INFINITY;
        boolean any = false;
        for (int i = 0; i < sampleHealth.size(); i++) {
            long tick = sampleTicks.get(i)[0];
            boolean inside = false;
            for (Blow b : blows) {
                if (tick >= b.tick() && tick < b.tick() + WINDOW) inside = true;
            }
            if (inside) continue;
            min = Math.min(min, sampleHealth.get(i));
            any = true;
        }
        return any ? OptionalDouble.of(min) : OptionalDouble.empty();
    }

    /**
     * The lowest {@code after} among the events that are not finishing hits, for the ordinary minimum (S1-S3).
     * The ledger writes 0 after a hit that popped or killed: such an ordinary hit counts as 0 when it falls inside
     * a blow's window (the samples there are skipped, so it would vanish), and is otherwise left to the samples.
     */
    public static OptionalDouble ordinaryEventMin(List<DamageEvent> damage, Resolution resolution) {
        double min = Double.POSITIVE_INFINITY;
        for (int i = 0; i < damage.size(); i++) {
            if (resolution.excluded().contains(i)) continue;
            DamageEvent e = damage.get(i);
            if (!(e.after() > 0) && !insideAWindow(e.tick(), resolution.blows())) continue;
            min = Math.min(min, e.after());
        }
        return Double.isFinite(min) ? OptionalDouble.of(min) : OptionalDouble.empty();
    }

    private static boolean insideAWindow(long tick, List<Blow> blows) {
        for (Blow b : blows) {
            if (tick >= b.tick() && tick < b.tick() + WINDOW) return true;
        }
        return false;
    }
}
