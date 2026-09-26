package com.xploits.pvp.crystal.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The self-damage budget of one moment (spec §1, P2, P3): the damage crystals could still deal you,
 * split into what is already on its way ({@link #inFlight() I}) and what is standing
 * ({@link #standing() S}), and the two rules that read it.
 *
 * <ul>
 *   <li><b>I, in flight:</b> a crystal we attacked, from the attack until Meteor's 5th pre-tick after it
 *   ({@link CrystalView#ATTACK_WAIT_TICKS}), when it goes back to S if it still stands; and any crystal
 *   that has disappeared, attacked or not, for {@link #DISAPPEARANCE_WINDOW} ticks from the pre-tick it
 *   was first seen gone, because its damage may still arrive.</li>
 *   <li><b>S, standing:</b> every standing crystal that is not in I, whoever placed it, plus our pending
 *   placements. A standing crystal is always in exactly one of I or S.</li>
 *   <li>Only crystals within {@link #HAZARD_RADIUS} count: beyond it an end crystal cannot reach you.</li>
 *   <li><b>Worst case C = I + S.</b> Totems and invulnerability are never counted.</li>
 * </ul>
 *
 * <p>Every self damage here is the exact one ({@link CrystalView#budgetSelfDamage}, {@link ExplosionMath}), never
 * Meteor's truncated prediction: that one is up to a raw point short, and health would end that far below R.
 *
 * <p>These rules only add checks on top of Meteor's; they never allow what Meteor would refuse.
 * Breaking a crystal we did not place never consults the budget (P2): {@link #breakAllowed} answers
 * {@link Verdict#FOREIGN} for it without reading health, so no caller can get that wrong.
 */
public final class SelfBudget {
    /**
     * R = 5, the same as Meteor's pause-health: the {@code reserve} setting's own default, which only the level
     * Custom reads ({@link RiskLevel}). The {@code risk} setting's default is Balanced
     * ({@link RiskLevel#BALANCED}, R = 3.5), not this value.
     */
    public static final double DEFAULT_RESERVE = 5.0;
    /** F: what nothing we cause may go below. Fixed. */
    public static final double FLOOR = 2.0;
    /** Epsilon: a self damage this small may still be placed under the reserve (setting {@code safe-self-damage}). */
    public static final double DEFAULT_SAFE_SELF_DAMAGE = 0.5;
    /** An end crystal's explosion deals nothing beyond 12 blocks ({@code DamageUtils}: power 12). */
    public static final double HAZARD_RADIUS = 12.0;
    /** A crystal that has disappeared stays in I for this many ticks, counting the first one seen gone. */
    public static final int DISAPPEARANCE_WINDOW = 3;

    /** Where a crystal's self damage is counted. */
    public enum Share { IN_FLIGHT, STANDING, NOT_COUNTED }

    /** The answer of a rule. */
    public enum Verdict {
        ALLOWED(true, Reason.WITHIN_BUDGET),
        ALLOWED_SAFE(true, Reason.SAFE_SELF_DAMAGE),
        REFUSED_RESERVE(false, Reason.OVER_RESERVE),
        REFUSED_FLOOR(false, Reason.BELOW_FLOOR),
        /** Not ours: Meteor's rules only, the budget was not read. */
        FOREIGN(true, Reason.FOREIGN_CRYSTAL);

        private final boolean allowed;
        private final Reason reason;

        Verdict(boolean allowed, Reason reason) {
            this.allowed = allowed;
            this.reason = reason;
        }

        public boolean allowed() {
            return allowed;
        }

        public Reason reason() {
            return reason;
        }
    }

    private final double health;
    private final double reserve;
    private final double safeSelfDamage;
    private final double inFlight;
    private final double standing;
    /** What each crystal in I adds to it, so breaking one of them does not count it twice. */
    private final Map<Integer, Double> inFlightById;

    private SelfBudget(double health, double reserve, double safeSelfDamage, double inFlight,
                       double standing, Map<Integer, Double> inFlightById) {
        this.health = health;
        this.reserve = reserve;
        this.safeSelfDamage = safeSelfDamage;
        this.inFlight = inFlight;
        this.standing = standing;
        this.inFlightById = inFlightById;
    }

    /**
     * The budget at this moment.
     *
     * @param now            the current pre-tick
     * @param health         your health plus absorption, read now
     * @param crystals       the crystals measured, standing or just gone
     * @param pending        the budget self damage of each placement sent whose crystal has not appeared yet
     * @param reserve        R, from the {@code risk} level ({@link CrystalSettings#budgetReserve()}); never
     *                       below {@link #FLOOR}, because a placement could then leave less than the floor
     *                       and its own crystal could never be broken. So the lowest level keeps exactly F,
     *                       and the {@code reserve} setting's minimum is 2
     * @param safeSelfDamage epsilon, the {@code safe-self-damage} setting ({@link #DEFAULT_SAFE_SELF_DAMAGE})
     */
    public static SelfBudget of(long now, double health, List<CrystalView> crystals, List<Double> pending,
                                double reserve, double safeSelfDamage) {
        if (!Double.isFinite(health) || health < 0) throw new IllegalArgumentException("health " + health);
        Damage.check(reserve, "reserve");
        if (reserve < FLOOR) throw new IllegalArgumentException("reserve " + reserve + " below the floor " + FLOOR);
        Damage.check(safeSelfDamage, "safe self damage");
        checkCrystals(now, crystals);
        double in = 0;
        double stand = 0;
        Map<Integer, Double> byId = new HashMap<>();
        for (CrystalView c : crystals) {
            switch (shareOf(c, now)) {
                case IN_FLIGHT -> {
                    in += c.budgetSelfDamage();
                    byId.put(c.id(), c.budgetSelfDamage());
                }
                case STANDING -> stand += c.budgetSelfDamage();
                case NOT_COUNTED -> { }
            }
        }
        for (Double p : pending) stand += Damage.check(Objects.requireNonNull(p, "pending"), "pending self damage");
        return new SelfBudget(health, reserve, safeSelfDamage, in, stand, Map.copyOf(byId));
    }

    /** Where this crystal counts at pre-tick {@code now}. */
    public static Share shareOf(CrystalView crystal, long now) {
        if (crystal.distance() > HAZARD_RADIUS) return Share.NOT_COUNTED;
        if (!crystal.live()) {
            return now - crystal.removedTick() < DISAPPEARANCE_WINDOW ? Share.IN_FLIGHT : Share.NOT_COUNTED;
        }
        return crystal.waitingAt(now) ? Share.IN_FLIGHT : Share.STANDING;
    }

    /** Ids appear once; no tick lies after {@code now}; nothing is attacked after it disappeared. */
    static void checkCrystals(long now, List<CrystalView> crystals) {
        Set<Integer> ids = new HashSet<>();
        for (CrystalView c : crystals) {
            if (!ids.add(c.id())) throw new IllegalArgumentException("crystal twice: " + c.id());
            if (c.attackedTick() > now) throw new IllegalArgumentException("attacked in the future: " + c.id());
            if (c.removedTick() > now) throw new IllegalArgumentException("removed in the future: " + c.id());
            if (c.attacked() && !c.live() && c.attackedTick() > c.removedTick()) {
                throw new IllegalArgumentException("attacked after it disappeared: " + c.id());
            }
        }
    }

    public double health() {
        return health;
    }

    /** I: what is already on its way. */
    public double inFlight() {
        return inFlight;
    }

    /** S: what stands, plus our pending placements. */
    public double standing() {
        return standing;
    }

    /** C = I + S. */
    public double worstCase() {
        return inFlight + standing;
    }

    /**
     * Placing a crystal with this {@code budgetSelfDamage} (the spot's {@link Candidate#budgetSelfDamage}), once it
     * passed Meteor's checks: allowed if it leaves the reserve ({@code health - C - budgetSelfDamage >= R});
     * otherwise, in safe mode, if the {@code budgetSelfDamage} is tiny and it still leaves the floor
     * ({@code budgetSelfDamage <= epsilon} and {@code health - C - budgetSelfDamage >= F}).
     */
    public Verdict placeAllowed(double budgetSelfDamage) {
        Damage.check(budgetSelfDamage, "budget self damage");
        double left = health - worstCase() - budgetSelfDamage;
        if (left >= reserve) return Verdict.ALLOWED;
        if (budgetSelfDamage > safeSelfDamage) return Verdict.REFUSED_RESERVE;
        return left >= FLOOR ? Verdict.ALLOWED_SAFE : Verdict.REFUSED_FLOOR;
    }

    /**
     * Breaking this crystal, once it passed Meteor's checks (P2). Ours: allowed if
     * {@code health - I - self >= F}, where I leaves this crystal out so it is never counted twice; the
     * crystal may be one this budget has not seen (fast-break, P4). Not ours: {@link Verdict#FOREIGN},
     * always, without reading health: Meteor's rules only.
     */
    public Verdict breakAllowed(CrystalView crystal) {
        if (!crystal.live()) throw new IllegalArgumentException("crystal already gone: " + crystal.id());
        if (!crystal.ours()) return Verdict.FOREIGN;
        double others = inFlight - inFlightById.getOrDefault(crystal.id(), 0.0);
        return health - others - crystal.budgetSelfDamage() >= FLOOR ? Verdict.ALLOWED : Verdict.REFUSED_FLOOR;
    }
}
