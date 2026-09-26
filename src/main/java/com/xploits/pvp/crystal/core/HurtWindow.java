package com.xploits.pvp.crystal.core;

/**
 * Our own hurt cooldown, credited conservatively (spec, Round 2 (a)).
 *
 * <p>After a full hit the server sets our {@code timeUntilRegen} to 20; while it is above 10, a new hit whose
 * {@code amount} (after difficulty and shield, before armour) is not above the last full hit's does nothing
 * ({@code LivingEntity.damage}). The client cannot see that timer: its own restarts on any health drop. The only
 * reliable start is the {@code EntityDamageS2CPacket} for us, which the server sends on full hits only.
 *
 * <p>One of our actions counts 0 self damage in the budget only if all of these hold:
 * <ol>
 *   <li>the last such packet was a hit by one of our own crystals ({@code player_explosion}, cause us, direct
 *   that crystal), whose raw damage {@code R_last} we had measured: the caller decides this and only then calls
 *   {@link #ownHit};</li>
 *   <li>{@code 20 - k > 10 + RTT + 2}, with {@code k} the pre-ticks since the pre-tick at or before the packet
 *   and the full round trip in ticks, so we are surely still inside the server's window when it lands;</li>
 *   <li>we are not blocking with a shield;</li>
 *   <li>{@code R_new + 0.5 <= R_last - 0.5}, both unrounded ({@link RawExplosion});</li>
 *   <li>nothing hit us again since: another damage packet ({@link #otherHit}), or a health drop the hit cannot
 *   explain ({@link #health}).</li>
 * </ol>
 *
 * <p>The credit relaxes only the budget; Meteor's checks keep the full damage, so every action is still one
 * Meteor allows. Documented limitation: a datapack that adds damage types to {@code bypasses_cooldown} breaks the
 * assumption; vanilla adds none, and our crystals hit us as {@code player_explosion}.
 */
public final class HurtWindow {
    /** The server's {@code timeUntilRegen} right after a full hit. */
    public static final int REGEN_TICKS = 20;
    /** A hit is compared with the last one while {@code timeUntilRegen} is above this. */
    public static final int COOLDOWN_ABOVE = 10;
    /** Ticks kept in hand on top of the round trip. */
    public static final int MARGIN_TICKS = 2;
    /** epsilon_r: the raw damage margin, taken off both sides. */
    public static final double RAW_MARGIN = 0.5;
    /** The round trip in ticks when it is not known ({@link CrystalBrain#UNKNOWN_PING_TICKS}). */
    public static final int UNKNOWN_RTT_TICKS = 5;
    /**
     * The hit's own health drop is expected up to this many pre-ticks after the one the packet is stamped with:
     * the server sends the damage packet first and our health at the end of our player's tick.
     */
    public static final int SETTLE_TICKS = 2;
    /**
     * The most difficulty can scale an explosion's damage to a player (hard: 3/2); armour, enchantments and
     * effects only lower it. So one hit of raw {@code R} never takes more than {@code 1.5 * R} health.
     */
    public static final double HARDEST_SCALING = 1.5;
    /** No hit: nothing is credited. */
    public static final long NONE = -1;

    private long hitTick = NONE;
    private double lastRaw = RawExplosion.UNKNOWN;
    private int rttTicks = UNKNOWN_RTT_TICKS;
    private boolean cancelled;
    /** The last health seen, before or after the hit; NaN before any. */
    private double lastHealth = Double.NaN;
    /** Health lost since the hit, summed over every drop seen (healing in between does not undo a drop). */
    private double dropped;

    /**
     * The rule, conditions 2 to 5 (condition 1 is the caller's).
     *
     * @param hitTick   the pre-tick at or before our own crystal's damage packet, or {@link #NONE}
     * @param lastRaw   R_last: the raw damage we measured for that crystal
     * @param rttTicks  the round trip in ticks, rounded up ({@link #UNKNOWN_RTT_TICKS} when unknown)
     * @param cancelled whether anything cancelled it since (condition 5)
     * @param at        the pre-tick the action is judged at: this one for an action decided in it, the next one
     *                  for one sent between two pre-ticks (it goes out after this one)
     * @param shielding whether we are blocking with a shield
     * @param newRaw    R_new: the raw damage of the action's crystal
     */
    public static boolean credits(long hitTick, double lastRaw, int rttTicks, boolean cancelled, long at,
                                  boolean shielding, double newRaw) {
        if (hitTick == NONE || cancelled) return false;
        if (at < hitTick) throw new IllegalArgumentException("judged at " + at + " before the hit at " + hitTick);
        if (rttTicks < 0) throw new IllegalArgumentException("round trip " + rttTicks);
        long k = at - hitTick;
        if (!(REGEN_TICKS - k > COOLDOWN_ABOVE + rttTicks + MARGIN_TICKS)) return false;
        if (shielding) return false;
        if (!RawExplosion.known(lastRaw) || !RawExplosion.known(newRaw)) return false;
        return newRaw + RAW_MARGIN <= lastRaw - RAW_MARGIN;
    }

    /**
     * Our own crystal hit us in full (condition 1 already checked): a new window, stamped with the pre-tick at or
     * before the packet, replacing any earlier one. With no health seen before it, a drop could not be told
     * apart, so it credits nothing.
     *
     * @param raw      R_last, or {@link RawExplosion#UNKNOWN}
     * @param rttTicks the round trip in ticks now, rounded up ({@link #UNKNOWN_RTT_TICKS} when unknown)
     */
    public void ownHit(long tick, double raw, int rttTicks) {
        if (tick < 0) throw new IllegalArgumentException("tick " + tick);
        if (rttTicks < 0) throw new IllegalArgumentException("round trip " + rttTicks);
        hitTick = tick;
        lastRaw = RawExplosion.check(raw, "raw damage");
        this.rttTicks = rttTicks;
        dropped = 0;
        cancelled = Double.isNaN(lastHealth) || !RawExplosion.known(raw);
    }

    /** Any other damage packet for us: no credit until our own crystal opens a new window. */
    public void otherHit() {
        cancelled = true;
    }

    /**
     * Our health (with absorption) as seen at this pre-tick. A drop cancels the credit unless the hit explains
     * it: seen within {@link #SETTLE_TICKS} of the packet, and all the drops together no more than the hit can
     * deal ({@link #HARDEST_SCALING} times R_last plus the margin).
     */
    public void health(long tick, double health) {
        Damage.check(health, "health");
        if (hitTick != NONE && !cancelled && health < lastHealth) {
            dropped += lastHealth - health;
            if (tick > hitTick + SETTLE_TICKS || dropped > HARDEST_SCALING * (lastRaw + RAW_MARGIN)) cancelled = true;
        }
        lastHealth = health;
    }

    /** {@link #credits(long, double, int, boolean, long, boolean, double) The rule} for the window seen so far. */
    public boolean credits(long at, boolean shielding, double newRaw) {
        return credits(hitTick, lastRaw, rttTicks, cancelled, at, shielding, newRaw);
    }
}
