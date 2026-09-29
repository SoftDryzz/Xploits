package com.xploits.pvp.crystal.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Whether a target's reported health can be trusted (task B0a, spec Amendment 2026-09-28). Servers that hide
 * or spoof health would otherwise make every crystal look like a finishing blow: HealthHider sends a fixed
 * value (no drop ever measured), AntiHealthIndicator randomises it and can hide absorption (a real hit's drop
 * then reads too small). A target starts untrusted; only a judged hit that shows a real drop confirms it.
 *
 * <p>Fed by {@link CrystalBrain} from the same full hits it already reads for {@link TargetWindows}. A hit is
 * judged only when its direct source is one of our crystals, with a measured predicted damage {@code d
 * = }{@link CrystalSeen#targetDamage()}{@code .get(target) >= }{@link #TRUST_MIN_DAMAGE}, and no pop of the
 * target was read in the same span (between the pre-tick before the hit and the pre-tick that counts it): a
 * pop resets health to 1 plus absorption, so no drop from it can be measured, and it makes that hit unjudged
 * rather than a false failure. Judged, the hit decides.
 *
 * <p>The health arrives on the main thread after the damage packet was queued on the network thread, so a hit is
 * judged over a window (task T1): the pre-tick that counts it and the {@link #TRUST_JUDGE_TICKS} after it. The
 * FIRST change of the target's reported value after the hit decides: trusted if it is a drop of at least
 * {@link #TRUST_DROP_SHARE} of {@code min(d, before)}, untrusted if it is any other change; a window that ends
 * with no change at all is untrusted too. While a hit is pending, trust stays as it was. A real health update
 * arrives once, so a randomising server gets one draw per hit, not one per pre-tick of the window. A pop read
 * in the window leaves the hit unjudged (trust unchanged). A newer judgeable hit on the same target replaces a
 * pending older one (its drop is inside the newer one's {@code before} or adds to it), so the latest judged hit
 * still decides; a hit that cannot be judged (source not ours, no measured damage, {@code d} too small) leaves
 * a pending older one running, so unrelated hits cannot shield a target from the verdict of ours.
 *
 * <p>Residual risk, stated plainly: HealthHider (a fixed value) and hidden absorption (a drop too small) never
 * produce a qualifying first change, so they never trust. A server that randomises the reported health
 * (AntiHealthIndicator) can still produce a qualifying first change by chance, with some probability p per
 * hit; trust then lasts until its next judged hit, and the finishing-blow override keeps its other gates
 * (a totem and a spare, the floor, one at a time). This class cannot tell such a draw from a real drop.
 *
 * <p>A non-finite or negative reported health (a hostile server) never throws and is never left unchanged:
 * the target becomes untrusted outright, the same cautious reading {@link ServerValues} and {@link
 * MovementReach} give a broken game value elsewhere in this core. That check runs before the pop check
 * ({@link #hit}), so it wins the overlap of the two: a pop alongside odd health still forces untrusted,
 * never merely "unchanged" the way an ordinary pop alone leaves it (task B0a review round 1, minor).
 */
public final class HealthTrust {
    /** {@code d} below this is not a measured hit: too small a crystal to judge a drop from. */
    public static final double TRUST_MIN_DAMAGE = 1.0;
    /** The share of {@code min(d, before)} the reported health must actually drop by to confirm a real one. */
    public static final double TRUST_DROP_SHARE = 0.5;

    /** Pre-ticks after the one that counts a hit in which its health drop may still arrive. */
    public static final int TRUST_JUDGE_TICKS = 3;

    private final Set<String> trusted = new HashSet<>();
    private final Map<String, Pending> pending = new HashMap<>();

    /** Whether {@code target}'s reported health can be trusted now. Untrusted until a judged hit confirms it. */
    public boolean trusted(String target) {
        return trusted.contains(Objects.requireNonNull(target, "target"));
    }

    /**
     * Opens the judgement of one full hit on {@code target}, read at the pre-tick that counts it. Bad
     * {@code before} (see the class javadoc) forces untrusted at once and drops a pending hit. A pop in the
     * span leaves the hit unjudged and removes a pending older one (trust unchanged). A {@code d} below
     * {@link #TRUST_MIN_DAMAGE} is unjudgeable and touches nothing: a pending older hit keeps its window.
     * Otherwise it replaces any pending older hit. Call {@link #advance} after the hits of a pre-tick, which
     * also observes this one at its own pre-tick.
     *
     * @param d      the predicted damage of the crystal that dealt it, or any value below {@link #TRUST_MIN_DAMAGE}
     *               (including one that is not a valid number) when it was not one of our crystals, or unmeasured
     * @param before the target's reported health plus absorption at the pre-tick the hit counts from
     * @param popped whether a pop of the target was read in the span up to the pre-tick that counts it
     */
    public void hit(String target, double d, double before, boolean popped) {
        Objects.requireNonNull(target, "target");
        if (!validHealth(before)) {
            pending.remove(target);
            trusted.remove(target);
            return;
        }
        if (popped) {
            pending.remove(target);
            return;
        }
        if (!(d >= TRUST_MIN_DAMAGE)) return;
        pending.put(target, new Pending(d, before));
    }

    /**
     * One pre-tick's reading for every pending hit: a pop of the target read since the last call leaves its hit
     * unjudged; a bad reported health forces untrusted; the first reading that differs from {@code before} decides
     * (a drop of at least {@link #TRUST_DROP_SHARE} of {@code min(d, before)} trusts, any other value untrusts);
     * and a window that has seen {@link #TRUST_JUDGE_TICKS} pre-ticks past the first with no change ends
     * untrusted. A target missing from {@code health} reads as bad health.
     */
    public void advance(Map<String, Double> health, Set<String> poppedTargets) {
        Objects.requireNonNull(health, "health");
        for (String t : Objects.requireNonNull(poppedTargets, "poppedTargets")) pending.remove(t);
        var it = pending.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            String target = e.getKey();
            Pending p = e.getValue();
            double now = health.getOrDefault(target, Double.NaN);
            if (!validHealth(now)) {
                trusted.remove(target);
                it.remove();
            } else if (now != p.before) {
                if (now <= p.before - TRUST_DROP_SHARE * Math.min(p.d, p.before)) trusted.add(target);
                else trusted.remove(target);
                it.remove();
            } else if (++p.seen > TRUST_JUDGE_TICKS) {
                trusted.remove(target);
                it.remove();
            }
        }
    }

    /** Forgets every pending hit without a verdict (trust unchanged), e.g. across a gap in the pre-ticks. */
    public void forgetPending() {
        pending.clear();
    }

    private static boolean validHealth(double value) {
        return Double.isFinite(value) && value >= 0;
    }

    private static final class Pending {
        final double d;
        final double before;
        /** Pre-ticks past the counting one that have read no drop. */
        int seen;

        Pending(double d, double before) {
            this.d = d;
            this.before = before;
        }
    }
}
