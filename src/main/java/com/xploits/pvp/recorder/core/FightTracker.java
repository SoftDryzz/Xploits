package com.xploits.pvp.recorder.core;

import com.xploits.shared.core.i18n.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The life of a fight, one tick at a time: idle until the first exchange, then recording until you die,
 * the fight goes quiet or it runs for ten minutes.
 *
 * <ul>
 *   <li><b>Start</b>: an exchange ({@link FightBuilder#exchange}): a hit on you that is combat damage or
 *   comes from a player who is not one of ours (mobs and falls alone never start one), a totem popping
 *   (yours, or a player's within {@link #ENGAGE_RANGE}), a crystal you place or break with a hostile within
 *   {@link #ENGAGE_RANGE}, a player you attack or damage. auto-pvp engaging (its phase is not NO_COMBAT)
 *   also starts one, with no exchange yet.</li>
 *   <li><b>End</b>: you die, LOST. An opponent died and nothing was exchanged with a living player for
 *   {@link #SETTLE_TICKS}, WON. Nothing was exchanged for {@link #QUIET_TICKS}: WON if an opponent died,
 *   else ENDED; both end the record at the last exchange. {@link #MAX_TICKS}: ENDED, truncated.</li>
 *   <li><b>Discard</b>: a fight with no exchange that did not end in your death leaves no record: walking
 *   past a stranger, or auto-pvp approaching someone who never fought back.</li>
 * </ul>
 *
 * <p>Ticks where you are dead are ignored, except the one you die on: its hits are the lethal ones. Its
 * measurements are not used (auto-pvp has already released everything by then), so the phase, modules and
 * totems at the end are those of your last tick alive.
 */
public final class FightTracker {
    /** auto-pvp's default target range. */
    public static final double ENGAGE_RANGE = 16.0;
    /** 4.5 blocks of crystal placing range plus about 3.5 of lethal blast. */
    public static final double NEAR_RANGE = 8.0;
    public static final double CRYSTAL_NEAR_RANGE = 6.0;
    /** 20 s without an exchange. */
    public static final int QUIET_TICKS = 400;
    /** 5 s after the last opponent died. */
    public static final int SETTLE_TICKS = 100;

    /**
     * Ticks after the last exchange during which the end totals still read your inventory (totems, offhand
     * totem). The server sends a pop before the inventory update that removes the totem, so on the pop's
     * own tick the totem is still counted; a quarter of a second covers that lag with room to spare.
     */
    public static final int INVENTORY_SETTLE_TICKS = 5;
    /** Four hearts. */
    public static final double BIG_HIT = 8.0;
    /** Ten minutes. */
    public static final int MAX_TICKS = 12_000;
    /**
     * Past this, non-lethal damage events only add to the totals and to {@code damageEventsDropped}; lethal
     * ones (pops and the death, a handful) are always kept.
     */
    public static final int MAX_DAMAGE_EVENTS = 300;
    /** For module changes and for auto-pvp phases, each. */
    public static final int MAX_CHANGES = 200;

    /** What one tick produced: lines to show as the fight happens, and the fight if it just ended. */
    public record Step(List<Msg> live, Optional<FightRecord> finished) {
        public Step {
            live = List.copyOf(live);
            Objects.requireNonNull(finished, "finished");
        }
    }

    private static final Step NOTHING = new Step(List.of(), Optional.empty());

    /**
     * A live snapshot of the fight in progress, for the HUD: seconds so far, pops on each side and the
     * damage taken (unseen included, same as the finished record's total).
     */
    public record LiveFight(int seconds, int yourPops, int theirPops, double damageTaken) {
    }

    private final String addonVersion;
    private FightBuilder fight;
    /**
     * Your last tick alive, kept while idle: a fight counts its first hit from its health. Forgotten on
     * death, so a dead tick after a lost fight can never open another one.
     */
    private TickInput lastAlive;

    public FightTracker(String addonVersion) {
        this.addonVersion = Objects.requireNonNull(addonVersion, "addonVersion");
    }

    public Step tick(TickInput in) {
        boolean died = in.events().stream().anyMatch(e -> e instanceof CombatEvent.SelfDied);
        if (!in.alive() && !died) {
            lastAlive = null;
            return NOTHING;
        }
        if (fight == null) {
            boolean exchange = in.events().stream().anyMatch(e -> FightBuilder.exchange(e, in, name -> false));
            if (!exchange && (died || !FightBuilder.engaged(in))) {
                remember(in, died);
                return NOTHING;
            }
            if (died) {
                // A dead tick measures nothing: the fight opens from your last tick alive, however old,
                // and with none (the fight before already ended in this death) it does not open at all.
                if (lastAlive == null) return NOTHING;
                fight = new FightBuilder(addonVersion, in.tick(), in.epochMillis(), lastAlive, lastAlive.self());
            } else {
                boolean fresh = lastAlive != null && lastAlive.tick() == in.tick() - 1;
                fight = new FightBuilder(addonVersion, in.tick(), in.epochMillis(), in, fresh ? lastAlive.self() : in.self());
            }
        }
        List<Msg> live = new ArrayList<>();
        fight.tick(in, died, live);
        remember(in, died);
        if (died) {
            FightRecord lost = fight.build(FightOutcome.LOST, false, in.epochMillis(), false);
            fight = null;
            lastAlive = null;
            return new Step(live, Optional.of(lost));
        }
        long tick = in.tick();
        if (fight.anyDied() && tick - fight.settlingSince() >= SETTLE_TICKS) return end(live, FightOutcome.WON, true, in, false);
        // Defensive: a death is an exchange, so with one the settling above always ends the fight first.
        if (tick - fight.lastExchangeTick() >= QUIET_TICKS) {
            return end(live, fight.anyDied() ? FightOutcome.WON : FightOutcome.ENDED, true, in, false);
        }
        if (tick - fight.startTick() + 1 >= MAX_TICKS) return end(live, FightOutcome.ENDED, false, in, true);
        return new Step(live, Optional.empty());
    }

    private Step end(List<Msg> live, FightOutcome outcome, boolean atLastExchange, TickInput in, boolean truncated) {
        Optional<FightRecord> record = finish(outcome, atLastExchange, in.epochMillis(), truncated);
        return new Step(live, record);
    }

    /** Cuts the fight short (you left the server, the recorder was turned off): ABORTED, if it had an exchange. */
    public Optional<FightRecord> abort(long epochMillis) {
        Optional<FightRecord> record = fight == null ? Optional.empty() : finish(FightOutcome.ABORTED, false, epochMillis, false);
        lastAlive = null;
        return record;
    }

    private Optional<FightRecord> finish(FightOutcome outcome, boolean atLastExchange, long epochMillis, boolean truncated) {
        FightBuilder done = fight;
        fight = null;
        if (done.exchanges() == 0) return Optional.empty();
        return Optional.of(done.build(outcome, atLastExchange, epochMillis, truncated));
    }

    private void remember(TickInput in, boolean died) {
        if (in.alive() && !died) lastAlive = in;
    }

    /** Whether a fight is being recorded (auto-pvp engaging counts, before any exchange). */
    public boolean fighting() {
        return fight != null;
    }

    /** Whole seconds since the fight started, or 0 when idle. */
    public int seconds() {
        return fight == null ? 0 : (int) ((fight.lastTick() - fight.startTick()) / FightBuilder.TICKS_PER_SECOND);
    }

    /** A live snapshot of the fight in progress, empty while idle. Read-only, game thread only. */
    public Optional<LiveFight> live() {
        return fight == null ? Optional.empty() : Optional.of(fight.live(seconds()));
    }

    /** Forgets the fight in progress and the state remembered from before it, without a record. */
    public void reset() {
        fight = null;
        lastAlive = null;
    }
}
