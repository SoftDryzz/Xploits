package com.xploits.pvp.crystal.core;

import java.util.HashSet;
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
 * rather than a false failure. Judged, the hit decides: trusted if the reported health actually dropped by at
 * least {@link #TRUST_DROP_SHARE} of {@code min(d, before)}, untrusted otherwise. Not judged (source not
 * ours, no measured damage, {@code d} too small, or a pop in the span): trust is left exactly as it was — the
 * latest judged hit is what decides.
 *
 * <p>A non-finite or negative reported health (a hostile server) never throws and is never left unchanged:
 * the target becomes untrusted outright, the same cautious reading {@link ServerValues} and {@link
 * MovementReach} give a broken game value elsewhere in this core. That check runs before the pop check
 * ({@link #judge}), so it wins the overlap of the two: a pop alongside odd health still forces untrusted,
 * never merely "unchanged" the way an ordinary pop alone leaves it (task B0a review round 1, minor).
 */
public final class HealthTrust {
    /** {@code d} below this is not a measured hit: too small a crystal to judge a drop from. */
    public static final double TRUST_MIN_DAMAGE = 1.0;
    /** The share of {@code min(d, before)} the reported health must actually drop by to confirm a real one. */
    public static final double TRUST_DROP_SHARE = 0.5;

    private final Set<String> trusted = new HashSet<>();

    /** Whether {@code target}'s reported health can be trusted now. Untrusted until a judged hit confirms it. */
    public boolean trusted(String target) {
        return trusted.contains(Objects.requireNonNull(target, "target"));
    }

    /**
     * Judges one full hit on {@code target}. Bad health (see the class javadoc) is checked first and wins over
     * a pop in the same span: {@code popped} true does not rescue an odd {@code before}/{@code now} back to
     * "unchanged", it is still forced untrusted.
     *
     * @param d       the predicted damage of the crystal that dealt it, or any value below
     *                {@link #TRUST_MIN_DAMAGE} (including one that is not a valid number) when it was not one
     *                of our crystals, or we had no prediction for it
     * @param before  the target's reported health plus absorption at the pre-tick the hit counts from
     * @param now     the target's reported health plus absorption at the pre-tick that judges it
     * @param popped  whether a pop of the target was read in the same span
     */
    public void judge(String target, double d, double before, double now, boolean popped) {
        Objects.requireNonNull(target, "target");
        if (!validHealth(before) || !validHealth(now)) {
            trusted.remove(target);
            return;
        }
        if (popped || !(d >= TRUST_MIN_DAMAGE)) return;
        if (now <= before - TRUST_DROP_SHARE * Math.min(d, before)) trusted.add(target);
        else trusted.remove(target);
    }

    private static boolean validHealth(double value) {
        return Double.isFinite(value) && value >= 0;
    }
}
