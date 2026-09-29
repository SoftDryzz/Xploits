package com.xploits.pvp.crystal.core;

import com.xploits.pvp.crystal.core.CrystalSettings.AutoSwitch;
import com.xploits.pvp.crystal.core.CrystalSettings.PauseMode;
import com.xploits.pvp.crystal.core.SelfBudget.Verdict;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * What crystal-aura++ does, tick by tick: Meteor's CrystalAura with its default settings (spec P1, Q1,
 * line numbers from the 1.21.11 sources), plus the self-damage budget (§1, P2-P4) and, with it, the targets'
 * hurt windows ({@link TargetWindows}), and nothing else. With the budget off it is Meteor's rules only. With it
 * on, the budget refuses, never for breaking a crystal we did not place (Meteor's rules only, {@code max-damage}
 * included), and a hurt window only holds a placement back, never a break. Two things it allows that Meteor's
 * rules would not:
 * <ol>
 *   <li>our OWN crystals are no longer limited by {@code max-damage} (task B1): the reserve decides them, and
 *   {@code anti-suicide} still applies. A crystal of ours that appears after its placement's wait ran out
 *   (late, Q2) is foreign for every other rule but still ours for the budget: it counts in C while it may land,
 *   and breaking it must leave the floor (task C2);</li>
 *   <li>the finishing blow (task B0a, spec Amendment 2026-09-28; condition a tightened fix round 1, owner's
 *   decision 2026-09-29): a finishing-grade crystal (kills the target or pops his totem, {@link #FINISH_MARGIN})
 *   may go through past {@code max-damage}, {@code anti-suicide}, the reserve, the floor or {@code pause-health},
 *   only while a totem of undying backs it (one in hand AND a spare: it may never spend the last one), and at most
 *   one such crystal at a time ({@link Reason#FINISHING_BLOW}). Task B0c (spec Amendment 2026-09-29) splits it by
 *   {@link FinishKind}: only a KILL (the target holds no totem in either hand and his hands are visible) may pop
 *   our totem. A crystal that would only POP his must leave the floor after its own damage, so it never pops us,
 *   and it does not take the one-at-a-time slot; since B1 and the owner's decision of 2026-09-29 it acts only at
 *   the Aggressive level, where the reserve equals the floor, so what it really adds is going on below
 *   {@code pause-health} (the ordinary rules already allow every other pop-grade crystal that leaves the floor).</li>
 * </ol>
 *
 * <p>One brain per activation (Meteor clears its state on activation and deactivation). The adapter
 * calls, in the game's order:
 * <ol>
 *   <li>{@link #breakPhase} in {@code TickEvent.Pre} at priority HIGH, once per pre-tick, numbered
 *   consecutively, with what it measures there, as Meteor does (lines 660-719): health, pauses, hands,
 *   targets and every crystal, and no candidates. It decides the break, and Meteor's gate for placing
 *   (lines 903-924: the place setting, the pause, crystals in the hotbar, the switch rules and one at a
 *   time), all with these facts;</li>
 *   <li>only if {@link #wantsPlacement()}: registers the {@code BlockIterator} scan and, in
 *   {@code BlockIterator.after} of the same pre-tick, calls {@link #placePhase} with the candidates and
 *   the health read during the scan (Meteor's candidate checks read health there, line 953);</li>
 *   <li>does each action returned, in order;</li>
 *   <li>{@link #attackSent()} each time an attack packet goes out, which with {@code rotate} on is in the
 *   {@code Rotations} callback (Q1: the counter goes up there, line 891);</li>
 *   <li>{@link #placed(long, int)} when a placement packet goes out, in the same client tick;</li>
 *   <li>{@link #crystalAdded} on {@code EntityAddedEvent} for an end crystal (ownership, then fast-break),
 *   and {@link #crystalRemoved} on {@code EntityRemovedEvent};</li>
 *   <li>{@link #targetHurt} for each full-hit damage packet on another player, before the next pre-tick,
 *   and {@link #targetPopped} the same way for a totem pop read on one.</li>
 * </ol>
 * {@link #preTick} does both phases on one set of facts.
 */
public final class CrystalBrain {
    /** Meteor's attack window: {@code ticksPassed} counts to this, and the next pre-tick resets the attacks (lines 674-678). */
    public static final int ATTACK_WINDOW_LAST_TICK = 20;
    /** The minimum damage while face-placing (lines 817, 959). */
    public static final double FACE_PLACE_MIN_DAMAGE = 1.5;
    /** A pending placement lives at least this many ticks: Meteor's {@code placing} window (lines 667-669, 1055). */
    public static final int PENDING_MIN_TICKS = 5;
    /** ... or the ping in ticks plus this (Q2). */
    public static final int PENDING_PING_MARGIN = 2;
    /** The ping in ticks when the adapter does not know it (Q6). */
    public static final int UNKNOWN_PING_TICKS = 5;
    /** A latency the adapter could not read, for {@link #pingTicks}. */
    public static final int UNKNOWN_LATENCY = -1;
    /** A damage packet with no direct source entity, for {@link #targetHurt}. */
    public static final int NO_SOURCE = -1;
    /** Milliseconds in a tick. */
    private static final int TICK_MS = 50;
    /**
     * After a pending placement expires, a crystal appearing at its spot within this many ticks is counted
     * as a late own crystal (Q2); after that the spot is forgotten, so a crystal someone else puts there
     * later is not counted.
     */
    public static final int LATE_OWN_WINDOW = 20;
    /** Meteor keeps rotating to its last rotation until this many pre-ticks after it ({@code getLastRotationStopDelay}, lines 656-658, 725). */
    public static final int LAST_ROTATION_STOP_DELAY = 10;
    /** Anti-weakness sets the switch timer to this (line 837). */
    public static final int ANTI_WEAKNESS_SWITCH_TICKS = 1;
    /**
     * Task B0a (spec Amendment 2026-09-28): a crystal is finishing-grade for a target when its predicted
     * damage to that target is at least this many times the target's reported health plus absorption — it
     * kills the target outright, or pops his totem if he holds one.
     */
    public static final double FINISH_MARGIN = 1.25;

    private static final long NO_TICK = -1;

    private CrystalSettings settings = CrystalSettings.defaults();
    /** Every crystal standing, and the ones gone within the disappearance window, by id. */
    private final Map<Integer, Known> known = new LinkedHashMap<>();
    private final List<Pending> pending = new ArrayList<>();
    private final List<Late> late = new ArrayList<>();

    private long now = NO_TICK;
    private int ticksPassed;
    private int attacks;
    private int switchTimer;
    private boolean rotated;
    private int ticksSinceRotation = LAST_ROTATION_STOP_DELAY;
    private List<String> targets = List.of();
    private boolean facePlacing;
    private boolean holding;
    private int lateOwn;
    /** Task B1 (log only): crystals of ours (late ones included) that stood over {@link #STUCK_TICKS} pre-ticks without one attack of ours. */
    private int stuckStanding;
    /** Standing this many pre-ticks without an attack of ours counts a crystal as stuck ({@link #stuckStandingCrystals}). */
    static final int STUCK_TICKS = 20;
    private Decision lastDecision = Decision.none(Reason.NOTHING_TO_DO);
    /** The targets' hurt windows our crystals opened (with the budget on, they hold placements back). */
    private final TargetWindows windows = new TargetWindows();
    /** Whether each target's reported health can be trusted (task B0a): fed the same hits as {@link #windows}. */
    private final HealthTrust healthTrust = new HealthTrust();
    /** Full hits handed over since the last pre-tick; the next one counts them from the last. */
    private final List<ReadHit> hits = new ArrayList<>();
    /** Totem pops handed over since the last pre-tick (task B0a), by target name; the next one counts them. */
    private final Set<String> popped = new HashSet<>();
    /**
     * Every player seen (not only this pre-tick's targets), by name, health plus absorption: refreshed each
     * pre-tick before it is used as "now" for a hit read since the last one, and kept as "before" until then
     * (task B0a, {@link HealthTrust}). Also read for a finishing-grade candidate's own margin check.
     */
    private Map<String, Double> seenHealth = Map.of();
    /**
     * The players seen a finishing crystal would kill, not only pop (task B0c, {@link TargetView#killable}):
     * no totem in either hand and the hands visible. Refreshed with {@link #seenHealth}.
     */
    private Set<String> killable = Set.of();
    /**
     * The pre-tick the windows were last forgotten at: a crystal whose placement was first decided then or before is
     * not a landing ({@link TargetWindows#landed}), as the pre-ticks since it may not all have been counted.
     */
    private long landingSince = NO_TICK;
    private int deferred;
    /** The placement decided this tick, until the adapter says it was sent. */
    private Placement decided;

    /** The hands read at HIGH: Meteor's switch rules and the placing hand use them (lines 693-694). */
    private CrystalTick.Hands hands;
    /**
     * The totems carried, read at HIGH (task B0a fix round 1): half of condition a of the finishing-blow
     * override, together with {@code hands.totemInHand()} ({@link #totemBacksIt}).
     */
    private int totems;
    private Action breakAction;
    private Action placeAction;
    /** Meteor's doPlace passed its checks up to the scan this pre-tick (lines 903-924). */
    private boolean placeGate;
    private boolean placeDone = true;
    /**
     * Task B0a: the placing gate is open only because {@code pause-health} alone would otherwise have closed
     * it and a finishing blow might still bypass it ({@link #finishingBlowPossible}); set inside
     * {@link #placeGateOpen} at HIGH and read later by {@link #placeBest} at the scan, since the two run in
     * different calls of the same pre-tick. While true, an ordinary (non-override) placement still must not
     * happen: {@code pause-health} still pauses it.
     */
    private boolean placeHealthPausedOnly;

    /** This pre-tick's budget questions, for {@link #holding()}. */
    private int asked;
    private int allowed;
    private Reason firstRefusal;

    /**
     * One pre-tick with both phases on the same facts: {@link #breakPhase} with the tick, then
     * {@link #placePhase} with its health and candidates.
     *
     * @return what to do now, in order: nothing, or one action with {@code rotate} on (lines 717-718), or
     *         up to a break and a placement with it off
     */
    public List<Action> preTick(CrystalSettings settings, CrystalTick tick) {
        List<Action> actions = new ArrayList<>(2);
        begin(settings, tick).ifPresent(actions::add);
        placePhase(tick.health(), tick.candidates()).ifPresent(actions::add);
        return List.copyOf(actions);
    }

    /**
     * The start of a pre-tick, at HIGH: update the state as Meteor does at the start of its pre-tick (lines
     * 663-708), find the targets, break (lines 770-875) within the budget, and decide Meteor's gate for
     * placing (lines 716-718, 903-924) with these same facts.
     *
     * @param tick what was measured at HIGH, with no candidates: {@link #placePhase} takes them
     * @return the break, the anti-weakness swap, or nothing
     */
    public Optional<Action> breakPhase(CrystalSettings settings, CrystalTick tick) {
        Objects.requireNonNull(tick, "tick");
        if (!tick.candidates().isEmpty()) throw new IllegalArgumentException("candidates go to the place phase");
        return begin(settings, tick);
    }

    /** Whether this pre-tick's placing gate is open, so Meteor would scan for a spot now (lines 931-932). */
    public boolean wantsPlacement() {
        return placeGate && !placeDone;
    }

    /**
     * This pre-tick's placement, from the {@code BlockIterator} scan (lines 931-1008): the best candidate
     * that passes Meteor's checks and then the budget. At most once per pre-tick, after
     * {@link #breakPhase}; nothing when the gate is closed.
     *
     * @param health     your health plus absorption, read during the scan
     * @param candidates the scan's spots, in its order
     */
    public Optional<Action> placePhase(double health, List<Candidate> candidates) {
        Damage.check(health, "health");
        List<Candidate> spots = List.copyOf(candidates);
        Set<Long> seen = new HashSet<>();
        for (Candidate c : spots) {
            if (!seen.add(c.pos())) throw new IllegalArgumentException("candidate twice");
        }
        if (now == NO_TICK || placeDone) throw new IllegalStateException("the place phase follows the break phase, once");
        placeDone = true;
        if (placeGate) placeAction = placeBest(health, spots).orElse(null);
        conclude();
        return Optional.ofNullable(placeAction);
    }

    private Optional<Action> begin(CrystalSettings settings, CrystalTick tick) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(tick, "tick");
        if (now != NO_TICK && tick.tick() <= now) {
            throw new IllegalArgumentException("pre-tick " + tick.tick() + " after " + now);
        }
        this.settings = settings;
        long previous = now;
        now = tick.tick();
        decided = null;
        // The hits read since the last pre-tick count from it, never later than they arrived. With the budget off
        // nothing is kept, so turning it on again never reuses a window that missed a hit in the meantime.
        Map<String, Double> nowHealth = healthByName(tick.targets());
        if (settings.selfBudget()) countHits(previous, nowHealth);
        else forgetWindows();
        seenHealth = nowHealth;
        killable = killableNames(tick.targets());

        // Crystals first seen now appeared after the previous pre-tick, when Meteor's EntityAdded would have
        // matched them against the placements pending then.
        Set<Integer> listed = new HashSet<>();
        for (CrystalSeen c : tick.crystals()) {
            listed.add(c.id());
            Known k = known.get(c.id());
            if (k == null) appeared(c, previous);
            else if (k.live()) k.seen = c;
        }
        for (Known k : known.values()) {
            if (k.removedTick == CrystalView.NEVER && (k.reportedGone || !listed.contains(k.seen.id()))) {
                k.removedTick = now;
                // One of ours, gone while known: how long it took, from the first decision to place it to now.
                if (k.ours && k.placedTick > landingSince) windows.landed(now, now - k.placedTick);
            }
        }
        for (Known k : known.values()) {
            if (k.mine && !k.stuckCounted && k.live() && k.attempts == 0 && now - k.since > STUCK_TICKS) {
                k.stuckCounted = true;
                stuckStanding++;
            }
        }
        known.values().removeIf(k -> k.removedTick != CrystalView.NEVER
            && now - k.removedTick >= SelfBudget.DISAPPEARANCE_WINDOW);
        expirePending();
        windows.expire(now);

        rotated = false;
        // Meteor counts on without bound; past the delay the count no longer matters, so it stops there.
        ticksSinceRotation = Math.min(ticksSinceRotation + 1, LAST_ROTATION_STOP_DELAY);
        if (ticksPassed < ATTACK_WINDOW_LAST_TICK) ticksPassed++;
        else {
            ticksPassed = 0;
            attacks = 0;
        }
        if (switchTimer > 0) switchTimer--;

        findTargets(tick.targets());
        hands = tick.hands();
        totems = tick.totems();
        asked = 0;
        allowed = 0;
        firstRefusal = null;
        breakAction = null;
        placeAction = null;
        placeGate = false;
        placeDone = false;
        if (!targets.isEmpty()) {
            if (!rotated) breakAction = breakBest(tick).orElse(null);
            placeGate = !rotated && placeGateOpen(tick);
        }
        conclude();
        return Optional.ofNullable(breakAction);
    }

    /** {@link #holding()} and {@link #lastDecision()} from what this pre-tick has decided so far. */
    private void conclude() {
        holding = settings.selfBudget() && asked > 0 && allowed == 0;
        if (placeAction != null) lastDecision = placeAction.decision();
        else if (breakAction != null) lastDecision = breakAction.decision();
        else if (targets.isEmpty()) lastDecision = Decision.none(Reason.NO_TARGETS);
        else lastDecision = Decision.none(holding ? firstRefusal : Reason.NOTHING_TO_DO);
    }

    /**
     * An end crystal was added to the world (lines 731-744): it is ours if one of our placements is pending
     * at its spot (ownership first, Q2; one whose wait ran out is late: foreign for Meteor's rules, but our own
     * for the floor, {@link Known#breakView}), then fast-break may attack it at once. Fast-break needs damage
     * above {@code min-damage} (never the face-place minimum), and checks no pause, no timer and not the
     * {@code break} setting; it uses the previous pre-tick's targets. The budget reads {@code health} as
     * it is now (P4). Task B0a: if the ordinary checks refuse it, the finishing-blow override may still
     * fast-break it (the kill-grade tier, and the pop-grade one at Aggressive, {@link #fastBreakOverride}; see
     * {@link #breakFinishing} for the same tiers at a pre-tick's own break phase). Fast-break itself has never read any of the settings-level pauses at
     * all, not even {@code pause-health} (pre-existing, Meteor's own design (line 740), unrelated to this
     * task) — so, unlike {@link #breakBest}/{@link #placeGateOpen}, there is no pause-health carve-out to
     * apply here: neither the ordinary nor the override path is ever paused by it.
     *
     * @param settings the settings now: Meteor reads them live here (lines 740-742 and {@code getBreakDamage}),
     *                 so a change made since the last pre-tick already applies
     * @param health   your health plus absorption now
     * @param hands    your hands now (anti-weakness reads them when attacking, lines 826-835)
     * @param totems   the totems of undying you carry now, hands included (task C2, final review M4): read fresh,
     *                 not the last pre-tick's, so a pop since then can never let a finishing blow spend the last one
     */
    public Optional<Action> crystalAdded(CrystalSettings settings, CrystalSeen crystal, double health, CrystalTick.Hands hands,
                                         int totems) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(crystal, "crystal");
        Objects.requireNonNull(hands, "hands");
        Damage.check(health, "health");
        if (totems < 0) throw new IllegalArgumentException("totems " + totems);
        // Before the first pre-tick Meteor has no targets and nothing pending; the crystal is seen then.
        if (now == NO_TICK || known.containsKey(crystal.id())) return Optional.empty();
        this.settings = settings;
        this.totems = totems;
        Known k = appeared(crystal, now);

        if (!settings.fastBreak() || rotated || attacks >= settings.attackFrequency()) return Optional.empty();

        Optional<Action> action = fastBreakOrdinary(k, health, hands);
        if (action.isEmpty() && settings.selfBudget() && settings.finishingBlow()) {
            action = fastBreakOverride(k, health, hands);
        }
        action.ifPresent(a -> lastDecision = a.decision());
        return action;
    }

    /**
     * {@link #crystalAdded(CrystalSettings, CrystalSeen, double, CrystalTick.Hands, int)} with the last settings given
     * to the brain, by a pre-tick or by an earlier {@code crystalAdded} (tests).
     */
    Optional<Action> crystalAdded(CrystalSeen crystal, double health, CrystalTick.Hands hands) {
        return crystalAdded(settings, crystal, health, hands);
    }

    /** The same with the totems the last pre-tick read (tests that do not care about a fresher count). */
    Optional<Action> crystalAdded(CrystalSettings settings, CrystalSeen crystal, double health, CrystalTick.Hands hands) {
        return crystalAdded(settings, crystal, health, hands, totems);
    }

    /** Meteor's fast-break (lines 740-743, {@code getBreakDamage}), unchanged. */
    private Optional<Action> fastBreakOrdinary(Known k, double health, CrystalTick.Hands hands) {
        float damage = breakDamage(k, health);
        if (!(damage > settings.minDamage())) return Optional.empty();
        Reason reason = Reason.BUDGET_OFF;
        if (settings.selfBudget()) {
            Verdict v = budget(health).breakAllowed(k.breakView(now));
            if (!v.allowed()) return Optional.empty();
            reason = v.reason();
        }
        return attack(k, hands, reason);
    }

    /**
     * Tier 2 of the finishing blow, fast-break included (task B0a): the same checks as {@link #breakFinishing}'s
     * override tier, for this one crystal — Meteor's max-damage and anti-suicide skipped (never the other
     * rules), finishing-grade, condition a (a totem in hand now and a spare, {@link #totemBacksIt}), condition b (no other override crystal
     * pending, standing, attacked or within the disappearance window, excluding this crystal itself) and
     * condition c (nothing else we can see could take the totem first).
     */
    private Optional<Action> fastBreakOverride(Known k, double health, CrystalTick.Hands hands) {
        if (!k.ours || !totemBacksIt(hands)) return Optional.empty();
        float damage = breakDamageIgnoringSelfDamage(k);
        if (!(damage > settings.minDamage())) return Optional.empty();
        FinishKind kind = finishKind(k.seen.targetDamage());
        if (kind == FinishKind.NONE) return Optional.empty();
        SelfBudget budget = budget(health);
        if (kind == FinishKind.KILL && breakOverrideHolds(k, FinishKind.KILL, budget)) {
            return attack(k, hands, Reason.FINISHING_BLOW, FinishKind.KILL);
        }
        // A kill-grade crystal whose kill rule failed is checked by the pop rule (fix round 1), only at Aggressive.
        // After B1 this POP branch is unreachable for our own crystals: the pop rule (health - C - own >= 2, C >=
        // I) is never looser than the ordinary break rule (health - I - own >= 2), which fast-break tries first
        // (fastBreakOrdinary), so whatever it would take is already taken there. Kept as a guard.
        if (!popMayGoBelowReserve() || !breakOverrideHolds(k, FinishKind.POP, budget)) return Optional.empty();
        return attack(k, hands, Reason.FINISHING_BLOW, FinishKind.POP);
    }

    /** An end crystal left the world (lines 747-752). */
    public void crystalRemoved(int id) {
        Known k = known.get(id);
        if (k != null && k.removedTick == CrystalView.NEVER) k.reportedGone = true;
    }

    /**
     * A full hit on another player (research-external M1: the server sends the damage packet for full hits only),
     * read since the last pre-tick. It is kept until the next pre-tick, which counts it as read at the last one,
     * whenever it was handed over in between (even during a pre-tick, after its break phase). So the adapter must
     * hand over, before a pre-tick's break phase, every hit read before that pre-tick: one handed over later counts
     * from that pre-tick, one tick short. If its direct source is one of our crystals (one placed in time: a late
     * own crystal, foreign for ownership, counts as another source), and we measured that
     * crystal's raw damage to the player (when we attacked it, or when it was last seen), the player's window opens
     * at that size ({@link TargetWindows}); anything else (another source, one we do not know, no measurement)
     * closes it. The same hit also judges the target's health trust (task B0a, {@link HealthTrust}), from our
     * crystal's predicted damage instead of the raw one. A hit handed over before the first pre-tick is dropped:
     * nothing is ours yet.
     *
     * @param target         the player's name, as the targets are named
     * @param directSourceId the id of the entity that dealt the damage directly (the crystal for an explosion), or
     *                       {@link #NO_SOURCE}
     */
    public void targetHurt(String target, int directSourceId) {
        hits.add(new ReadHit(Objects.requireNonNull(target, "target"), directSourceId));
    }

    /**
     * A pop of {@code target}'s totem read since the last pre-tick (task B0a, spec Amendment 2026-09-28),
     * handed over the same way as {@link #targetHurt}: kept until the next pre-tick, which counts it as read
     * at the last one. A pop read in the same span as a hit makes that hit unjudged for {@link HealthTrust}
     * (trust is left unchanged): a pop also resets the target's health to 1 plus absorption, so no real drop
     * can be measured from a hit landing around it.
     */
    public void targetPopped(String target) {
        popped.add(Objects.requireNonNull(target, "target"));
    }

    /**
     * At the start of a pre-tick, before the crystals are updated: the hits handed over since {@code previous}
     * open or close the targets' windows as read at {@code previous}, and judge each target's health trust from
     * {@code seenHealth} (still {@code previous}'s values here) against {@code nowHealth}. Only a hit from one of
     * our crystals placed in time opens a window at its size; before the first pre-tick nothing is ours, so they
     * are dropped.
     */
    private void countHits(long previous, Map<String, Double> nowHealth) {
        if (previous != NO_TICK) {
            for (ReadHit hit : hits) {
                Known k = known.get(hit.directSourceId);
                OptionalDouble raw = OptionalDouble.empty();
                double predicted = Double.NaN;
                if (k != null && k.ours) {
                    Double measuredRaw = k.seen.targetRaw().get(hit.target);
                    if (measuredRaw != null) raw = OptionalDouble.of(measuredRaw);
                    Double measuredPredicted = k.seen.targetDamage().get(hit.target);
                    if (measuredPredicted != null) predicted = measuredPredicted;
                }
                windows.fullHit(hit.target, previous, raw);
                double before = seenHealth.getOrDefault(hit.target, Double.NaN);
                healthTrust.hit(hit.target, predicted, before, popped.contains(hit.target));
            }
        }
        // Every pending hit reads this pre-tick's health: it may arrive some pre-ticks after the damage packet.
        healthTrust.advance(nowHealth, popped);
        hits.clear();
        popped.clear();
    }

    private static Set<String> killableNames(List<TargetView> seen) {
        Set<String> names = new HashSet<>();
        for (TargetView t : seen) {
            if (t.killable()) names.add(t.name());
        }
        return Set.copyOf(names);
    }

    /** Every player seen this pre-tick, by name, health plus absorption. */
    private static Map<String, Double> healthByName(List<TargetView> seen) {
        Map<String, Double> health = new LinkedHashMap<>();
        for (TargetView t : seen) health.put(t.name(), t.totalHealth());
        return health;
    }

    /**
     * Forgets every target's hurt window, and the hits handed over and not yet counted, and every landing learned,
     * including those of the crystals placed until now and not yet gone. The adapter calls it when it skips a pre-tick
     * while the client ticks on (the ticks since a hit or a placement would come out short, so a window could seem
     * open after the server's has closed, and a crystal seem faster than it is); the brain does the same at every
     * pre-tick with the budget off, so it learns nothing then. Also drops any totem pop handed over and not yet
     * counted (task B0a): stale across a gap, the same way an uncounted hit is.
     */
    public void forgetWindows() {
        windows.clear();
        hits.clear();
        popped.clear();
        healthTrust.forgetPending();
        landingSince = now;
    }

    /**
     * Spots held back since activation because a target's hurt window would have swallowed them: one for each spot
     * skipped that way, which may be several in one pre-tick.
     */
    public int deferredForTargetWindow() {
        return deferred;
    }

    /**
     * The ids of our crystals whose placement or break went through the finishing-blow override (task B0a,
     * review focus 5: so the bench can tell a finishing hit from an ordinary one of our own), read-only and
     * still remembered — standing, attacked or within the disappearance window, exactly the crystals
     * {@link #known} still keeps (its own cleanup already drops one past that window).
     */
    public Set<Integer> finishingCrystalIds() {
        return finishingCrystalKinds().keySet();
    }

    /** The ids among {@link #finishingCrystalIds} that we attacked (task T2, read-only, for the bench). */
    public Set<Integer> finishingCrystalsAttacked() {
        Set<Integer> ids = new HashSet<>();
        for (Known k : known.values()) {
            if (k.finish != FinishKind.NONE && k.attackedTick != CrystalView.NEVER) ids.add(k.seen.id());
        }
        return Set.copyOf(ids);
    }

    /**
     * {@link #finishingCrystalIds} with the kind of each (task B0c, read-only, for the bench): {@link
     * FinishKind#KILL} for a crystal that went through the kill-grade override, {@link FinishKind#POP} for one
     * that went through the pop-grade one. Never {@link FinishKind#NONE}.
     */
    public Map<Integer, FinishKind> finishingCrystalKinds() {
        Map<Integer, FinishKind> kinds = new LinkedHashMap<>();
        for (Known k : known.values()) {
            if (k.finish != FinishKind.NONE) kinds.put(k.seen.id(), k.finish);
        }
        return Map.copyOf(kinds);
    }

    /**
     * How many pre-ticks can pass between deciding one of our placements and its crystal exploding (task
     * R3-16): the slowest of our own crystals' recent landings ({@link TargetWindows#landed}, the same
     * measurement R3-11 already learns for the targets' hurt windows), or {@link
     * TargetWindows#LANDING_SAMPLE_MAX_TICKS} while too few are known yet to trust. That constant is already
     * the ceiling {@link TargetWindows} itself discards a landing past, so no landing it would otherwise have
     * learned from is ever underestimated by falling back to it.
     *
     * <p>Fix round 1 (review-r3-16.md): a landing slower than that ceiling (a lag spike) is exactly the one
     * {@link TargetWindows#landed} leaves out of the samples this reads, by design, for the hurt-window hold
     * this method does not serve. Left alone, that outlier would simply vanish here too, never raising this
     * bound past whatever the ordinary, faster samples already established.
     *
     * <p>Fix round 2: flooring at the ceiling was not enough — a lag spike genuinely slower than the ceiling
     * must widen this bound to at least its own length, not just up to it, since this bound is safety-critical
     * (unlike the hurt-window hold {@link TargetWindows} was originally built for, where undershooting only
     * loses value). {@link TargetWindows#recentOutlier} reports the largest such landing still within the
     * landings' own age window, capped only at {@link TargetWindows#LANDING_OUTLIER_CAP_TICKS} so an
     * implausibly slow one is still a large, finite bound rather than being read verbatim; this method takes
     * the larger of that and the ordinary learned bound, so an outlier can only ever raise this, never lower
     * it below what the ordinary samples alone would already give.
     */
    public int landingTicksBound() {
        int learned = windows.slowestLanding(now).orElse(TargetWindows.LANDING_SAMPLE_MAX_TICKS);
        return Math.max(learned, windows.recentOutlier(now).orElse(0));
    }

    /** An attack packet went out (Q1: Meteor counts it there, line 891, not when it decides). */
    public void attackSent() {
        attacks++;
    }

    /**
     * The placement decided this tick went out (lines 1049-1057). It stays pending until a crystal appears
     * at its spot or for max(5, ping + 2) ticks, whichever comes first (Q2). It replaces whatever was pending,
     * or remembered as late, at that spot, as Meteor overwrites its placing spot and timer (lines 1054-1057):
     * Meteor places on the same base every tick until the crystal arrives, and only one crystal can come of
     * it. That crystal's landing still counts from the first of those placements: a placement the server missed is
     * what made it slower.
     *
     * @param pingTicks your ping in ticks, rounded up; {@link #UNKNOWN_PING_TICKS} when unknown
     */
    public void placed(long pos, int pingTicks) {
        if (decided == null || decided.pos != pos) throw new IllegalStateException("that placement was not decided this tick");
        if (pingTicks < 0) throw new IllegalArgumentException("ping " + pingTicks);
        long first = now;
        for (Pending p : pending) {
            if (p.pos == pos) first = p.firstTick;
        }
        pending.removeIf(p -> p.pos == pos);
        late.removeIf(l -> l.pos == pos);
        pending.add(new Pending(pos, decided.budgetSelfDamage, now, Math.max(PENDING_MIN_TICKS, pingTicks + PENDING_PING_MARGIN),
            first, decided.finish));
        decided = null;
    }

    /**
     * The ping {@link #placed} takes (Q6): your latency in the player list, in ticks of 50 ms rounded up, or
     * {@link #UNKNOWN_PING_TICKS} when it could not be read.
     *
     * @param latencyMs your latency in milliseconds, or any negative value ({@link #UNKNOWN_LATENCY}) when unknown
     */
    public static int pingTicks(int latencyMs) {
        if (latencyMs < 0) return UNKNOWN_PING_TICKS;
        // In long: the server sends the latency, and near Integer.MAX_VALUE the int sum would wrap to a negative ping.
        return (int) ((latencyMs + (long) TICK_MS - 1) / TICK_MS);
    }

    /**
     * Q3: whether this pre-tick at least one crystal or candidate passed Meteor's checks and the budget
     * refused every one of them. False when nothing passed Meteor's checks, so a real misconfiguration still
     * shows as idle. (Refusing because Meteor's own aura is on is the adapter's to add.)
     */
    public boolean holding() {
        return holding;
    }

    /** Crystals that appeared at one of our spots after its pending placement expired (Q2), since activation. */
    public int lateOwnCrystals() {
        return lateOwn;
    }

    /**
     * Log only (task B1 review): how many crystals of ours, late own ones included (they are foreign to the
     * budget, Q2, and past max-damage they are never broken by us), stood for more than {@link #STUCK_TICKS}
     * pre-ticks without one attack of ours, since activation. Counted once per crystal; never a position.
     */
    public int stuckStandingCrystals() {
        return stuckStanding;
    }

    /** The last thing decided, or why nothing was. */
    public Decision lastDecision() {
        return lastDecision;
    }

    /**
     * How many of the previous pre-tick's targets have a trusted reported health right now (task B0b, read-only,
     * so the bench can log whether trust formed).
     */
    public int trustedTargetCount() {
        int count = 0;
        for (String t : targets) {
            if (healthTrust.trusted(t)) count++;
        }
        return count;
    }

    /** The previous pre-tick's targets, in order: fast-break measures a new crystal against these. */
    public List<String> targets() {
        return targets;
    }

    /**
     * Whether to keep rotating to the last rotation now (lines 722-727): {@code rotate} on, fewer than
     * {@link #LAST_ROTATION_STOP_DELAY} pre-ticks since it, and no rotation this tick. The adapter asks at
     * the end of the pre-tick, as Meteor does at priority {@code LOWEST - 666}.
     */
    public boolean holdLastRotation() {
        return settings.rotate() && ticksSinceRotation < LAST_ROTATION_STOP_DELAY && !rotated;
    }

    // Targets and face-place

    /** Players only (§1), not creative, alive, not friends, within target-range (lines 1222-1253). */
    private void findTargets(List<TargetView> seen) {
        List<String> names = new ArrayList<>();
        boolean face = false;
        for (TargetView t : seen) {
            if (t.creative() || !t.alive() || t.friend() || !Reach.inTargetRange(t.squaredDistance(), settings.targetRange())) {
                continue;
            }
            names.add(t.name());
            if (t.totalHealth() <= settings.facePlaceHealth() || t.lowestArmorPercent() <= settings.facePlaceDurability()) {
                face = true;
            }
        }
        targets = List.copyOf(names);
        facePlacing = settings.facePlace() && face;
    }

    private double minimumDamage() {
        return facePlacing ? Math.min(settings.minDamage(), FACE_PLACE_MIN_DAMAGE) : settings.minDamage();
    }

    /**
     * Meteor's self-damage checks: max-damage and anti-suicide (lines 812, 953), on Meteor's own prediction
     * ({@code selfDamage}, never the budget's exact one), so with the budget off ++ is Meteor.
     *
     * <p>Task B1: with the budget on, {@code ours} (a placement of ours, a break of a crystal of ours) skips
     * max-damage, since the reserve is what limits our self damage then; anti-suicide stays. A foreign crystal
     * never consults the budget, so its break keeps max-damage.
     */
    private boolean tooHurtful(double selfDamage, double health, boolean ours) {
        boolean reserveDecides = ours && settings.selfBudget();
        return (!reserveDecides && selfDamage > settings.maxDamage())
            || (settings.antiSuicide() && selfDamage >= health);
    }

    /** Every pause but {@code pause-health} (lines 1153-1160): still enforced for every crystal, override included. */
    private boolean pausedExceptHealth(CrystalTick tick, PauseMode process) {
        if (tick.usingItem() && settings.pauseOnUse().pauses(process)) return true;
        if (settings.pauseOnLag() && tick.lagging()) return true;
        if (tick.pauseModuleActive()) return true;
        return settings.pauseOnMine().pauses(process) && tick.mining();
    }

    /**
     * {@code pause-health} alone (line 1161, split out task B0a): the one pause a finishing blow may bypass,
     * and only while a totem backs it ({@link #finishingBlowPossible}).
     */
    private boolean pausedForHealth(CrystalTick tick) {
        return tick.health() <= settings.pauseHealth();
    }

    /**
     * Condition a of the finishing-blow override (task B0a; tightened fix round 1, owner's decision
     * 2026-09-29): a totem of undying in either hand now, AND at least one more carried besides — the
     * override may never spend the last totem. {@link CrystalTick#totems} already counts the whole inventory,
     * hands included (verified in the adapter: {@code InvUtils.find} sums every slot, 0 to the inventory's own
     * size, so the totem in hand is already inside that count), so "one more besides the one in hand" is
     * simply {@code totems >= 2}. Kept in one place: {@code hands.totemInHand()} and {@link #totems} are read
     * together only here — by the pause-health pre-check ({@link #finishingBlowPossible}) and every tier-2 /
     * fast-break-override decision point ({@link #placeFinishing}, {@link #breakFinishing}, {@link
     * #fastBreakOverride}) — so tightening or loosening condition a further is a one-line change.
     *
     * <p>{@code hands} is a parameter, not always {@link #hands}: fast-break ({@link #crystalAdded}) reads its
     * hands fresh, its own parameter, the same way Meteor's own anti-weakness does there, and so is {@link #totems}
     * (task C2, final review M4: {@link #crystalAdded} takes the count the adapter reads at that moment, so a pop
     * since the last pre-tick can never let it spend the last totem); the other facts fast-break does not
     * re-measure (e.g. the targets it measures against, {@code fastBreakMeasuresAgainstThePreviousPreTicksTargets})
     * are still the last pre-tick's.
     */
    private boolean totemBacksIt(CrystalTick.Hands hands) {
        return hands.totemInHand() && totems >= 2;
    }

    /**
     * Owner's decision 2026-09-29: the pop-grade finishing override (popping the target's totem without killing
     * him) may take us below the reserve only at {@link RiskLevel#AGGRESSIVE}; Safe, Balanced and Custom (counted
     * as not Aggressive, a conservative ruling) leave a pop-grade crystal to the normal budget. The kill-grade
     * override is unchanged at every level.
     *
     * <p>What it really adds (final review M1): at Aggressive the reserve equals the floor (2), and the pop rule
     * asks for the floor too (health - C - own >= 2), so the normal budget already allows every crystal it would;
     * the only thing left is that it may act at or below {@code pause-health}, where the ordinary rules are paused.
     */
    private boolean popMayGoBelowReserve() {
        return settings.risk() == RiskLevel.AGGRESSIVE;
    }

    /**
     * The cheap pre-check for the finishing-blow override (task B0a): {@code self-budget} and {@code
     * finishing-blow} on, and condition a ({@link #totemBacksIt}) already holds.
     */
    private boolean finishingBlowPossible(CrystalTick.Hands hands) {
        return settings.selfBudget() && settings.finishingBlow() && totemBacksIt(hands);
    }

    // Break

    /**
     * Meteor's {@code getBreakDamage} (lines 791-822): 0 when Meteor would not break it, the damage to the
     * targets otherwise.
     */
    private float breakDamage(Known k, double health) {
        if (k.waiting(now)) return 0;
        if (k.attempts > settings.breakAttempts()) return 0;
        if (!k.seen.inBreakRange()) return 0;
        if (tooHurtful(k.seen.selfDamage(), health, k.ours)) return 0;
        float damage = k.seen.damageTo(targets);
        return damage < minimumDamage() ? 0 : damage;
    }

    /**
     * {@link #breakDamage} with Meteor's max-damage and anti-suicide skipped (task B0a): every other rule
     * (waiting, attempts, range, min-damage/face-place minimum) still applies. Only the finishing-blow
     * override reads this.
     */
    private float breakDamageIgnoringSelfDamage(Known k) {
        if (k.waiting(now)) return 0;
        if (k.attempts > settings.breakAttempts()) return 0;
        if (!k.seen.inBreakRange()) return 0;
        float damage = k.seen.damageTo(targets);
        return damage < minimumDamage() ? 0 : damage;
    }

    /** Lines 770-789, then the next best crystal while the budget refuses (P2); task B0a's finishing blow first. */
    private Optional<Action> breakBest(CrystalTick tick) {
        if (!settings.breakCrystals() || switchTimer > 0 || attacks >= settings.attackFrequency()) return Optional.empty();
        if (pausedExceptHealth(tick, PauseMode.BREAK)) return Optional.empty();
        boolean healthPausedOnly = pausedForHealth(tick);
        if (healthPausedOnly && !finishingBlowPossible(tick.hands())) return Optional.empty();

        if (settings.selfBudget() && settings.finishingBlow()) {
            List<Scored<Known>> overrideAble = new ArrayList<>();
            for (CrystalSeen c : tick.crystals()) {
                Known k = known.get(c.id());
                if (k == null || !k.live()) continue;
                float damage = breakDamageIgnoringSelfDamage(k);
                if (damage > 0) overrideAble.add(new Scored<>(k, damage, k.seen.budgetSelfDamage()));
            }
            overrideAble.sort(byDamage(true));
            Optional<Action> finishing = breakFinishing(tick, overrideAble, healthPausedOnly);
            if (finishing.isPresent()) return finishing;
        }
        if (healthPausedOnly) return Optional.empty();

        List<Scored<Known>> able = new ArrayList<>();
        for (CrystalSeen c : tick.crystals()) {
            Known k = known.get(c.id());
            if (k == null || !k.live()) continue;
            float damage = breakDamage(k, tick.health());
            if (damage > 0) able.add(new Scored<>(k, damage, k.seen.budgetSelfDamage()));
        }
        able.sort(byDamage(settings.selfBudget()));

        SelfBudget budget = null;
        for (Scored<Known> s : able) {
            Reason reason = Reason.BUDGET_OFF;
            if (settings.selfBudget()) {
                if (budget == null) budget = budget(tick.health());
                Verdict v = budget.breakAllowed(s.item.breakView(now));
                if (!answered(v)) continue;
                reason = v.reason();
            }
            return attack(s.item, tick.hands(), reason);
        }
        return Optional.empty();
    }

    /**
     * Tiers 1-3 of the finishing blow for a break (task B0a; B0c: tier 2 is kill-grade, tier 3
     * pop-grade, which also takes a kill-grade crystal whose kill rule failed). {@code overrideAble} already
     * skipped Meteor's max-damage and anti-suicide, since the override may bypass those too; tier 1 (the normal budget) puts
     * them back for its own pick, since only tier 2 (the override) may bypass them. Among the finishing-grade
     * crystals of ours: tier 1's normal-budget pick, by damage, unless {@code healthPausedOnly} (then only the
     * override may act, since an ordinary break stays paused); else tier 2's override pick, gated by a totem
     * in hand plus a spare (condition a), no other override crystal in flight (condition b) and the floor
     * holding without this crystal's own share (condition c); else tier 3, the pop rule. Empty when none: the
     * caller's unchanged loop, over Meteor-gated candidates, decides as before.
     */
    private Optional<Action> breakFinishing(CrystalTick tick, List<Scored<Known>> overrideAble, boolean healthPausedOnly) {
        List<Scored<Known>> finishing = new ArrayList<>();
        for (Scored<Known> s : overrideAble) {
            if (s.item.ours && isFinishingGrade(s.item.seen.targetDamage())) finishing.add(s);
        }
        if (finishing.isEmpty()) return Optional.empty();

        if (!healthPausedOnly) {
            SelfBudget budget = null;
            for (Scored<Known> s : finishing) {
                if (tooHurtful(s.item.seen.selfDamage(), tick.health(), true)) continue;
                if (budget == null) budget = budget(tick.health());
                Verdict v = budget.breakAllowed(s.item.breakView(now));
                if (answered(v)) return attack(s.item, tick.hands(), v.reason());
            }
        }

        if (!totemBacksIt(tick.hands())) return Optional.empty();
        SelfBudget budget = budget(tick.health());
        // Tier 2: kill-grade crystals by the kill rule (task B0c).
        for (Scored<Known> s : finishing) {
            if (finishKind(s.item.seen.targetDamage()) != FinishKind.KILL) continue;
            if (breakOverrideHolds(s.item, FinishKind.KILL, budget)) {
                return attack(s.item, tick.hands(), Reason.FINISHING_BLOW, FinishKind.KILL);
            }
        }
        // Tier 3: every finishing-grade crystal by the pop rule, a kill-grade one whose kill rule failed
        // included (fix round 1): it never pops us and takes no slot. Only at Aggressive (owner's decision
        // 2026-09-29): elsewhere a pop-grade crystal follows the normal budget (tier 1) alone.
        if (!popMayGoBelowReserve()) return Optional.empty();
        for (Scored<Known> s : finishing) {
            if (breakOverrideHolds(s.item, FinishKind.POP, budget)) {
                return attack(s.item, tick.hands(), Reason.FINISHING_BLOW, FinishKind.POP);
            }
        }
        return Optional.empty();
    }

    /**
     * Whether the override of this {@code kind} may break {@code k} now, given condition a already holds (task
     * B0c). Kill-grade (B0a's override): no other kill-grade override crystal in flight (condition b) and
     * {@code health - C(without this crystal) >= FLOOR} (condition c): it may pop us, and take us below the
     * reserve. Pop-grade: it must never pop us, so {@code health - C(without this crystal) - its own self damage
     * >= FLOOR}; it does not take the one-at-a-time slot.
     */
    private boolean breakOverrideHolds(Known k, FinishKind kind, SelfBudget budget) {
        double left = budget.health() - budget.worstCaseWithoutCrystal(k.view(now), now);
        return switch (kind) {
            case KILL -> overrideAvailable(k.seen.id(), null) && left >= SelfBudget.FLOOR;
            case POP -> left - k.seen.budgetSelfDamage() >= SelfBudget.FLOOR;
            case NONE -> false;
        };
    }

    /** Meteor's {@code doBreak(crystal)} (lines 824-875): anti-weakness, then the attack. */
    private Optional<Action> attack(Known k, CrystalTick.Hands hands, Reason reason) {
        return attack(k, hands, reason, FinishKind.NONE);
    }

    /** The same, {@code finish} marking the crystal as one of the finishing-blow override's (task B0a, condition b; task B0c: the kind). A mark is never lowered: a pop-grade break does not unmark a kill-grade crystal. */
    private Optional<Action> attack(Known k, CrystalTick.Hands hands, Reason reason, FinishKind finish) {
        if (settings.antiWeakness() && hands.weakened()
            && (!hands.strengthened() || hands.strengthAmplifier() <= hands.weaknessAmplifier())
            && !hands.mainHandBreaksWeakened()) {
            if (!hands.hotbarBreaksWeakened()) return Optional.empty();
            switchTimer = ANTI_WEAKNESS_SWITCH_TICKS;
            return Optional.of(new Action(Decision.swapWeapon(k.seen.id()), Action.Hand.MAIN, false));
        }
        rotateNow();
        k.attempts++;
        k.attackedTick = now;
        if (finish.ordinal() > k.finish.ordinal()) k.finish = finish;
        return Optional.of(new Action(Decision.breakCrystal(k.seen.id(), reason), crystalHand(hands), false));
    }

    // Place

    /** Meteor's doPlace up to the scan (lines 903-924), at HIGH. */
    private boolean placeGateOpen(CrystalTick tick) {
        if (!settings.place()) return false;
        if (pausedExceptHealth(tick, PauseMode.PLACE)) return false;
        placeHealthPausedOnly = pausedForHealth(tick);
        if (placeHealthPausedOnly && !finishingBlowPossible(hands)) return false;
        if (!hands.crystalsInHotbar()) return false;
        if (settings.autoSwitch() != AutoSwitch.NONE) {
            if (settings.noGapSwitch() && settings.autoSwitch() == AutoSwitch.NORMAL && !hands.offhandCrystals()
                && hands.gappleInHand()) return false;
            if (settings.noBowSwitch() && hands.bowInHand()) return false;
        } else if (!hands.mainHandCrystals() && !hands.offhandCrystals()) return false;

        // One at a time, by Meteor's rules (lines 921-924) — but only for as long as we might actually act on
        // it (task F1, owner's decision 2026-09-29, task-f1-report.md): once our own budget refuses the break
        // and the finishing-blow override will not take it either, we know, with certainty, that we will
        // never intentionally break this crystal ourselves right now — waiting for "our turn to break it
        // first" is then a dead end, not real one-at-a-time discipline, and would otherwise stall the rest of
        // the fight behind a crystal that will stand forever. Budget off: unchanged, Meteor parity — there is
        // no budget or override to consult, so every crystal Meteor would break still stops placing, exactly
        // as before.
        SelfBudget gateBudget = null;
        for (CrystalSeen c : tick.crystals()) {
            Known k = known.get(c.id());
            if (k == null || !k.live() || breakDamage(k, tick.health()) <= 0) continue;
            if (!settings.selfBudget()) return false;
            if (gateBudget == null) gateBudget = budget(tick.health());
            if (gateBudget.breakAllowed(k.breakView(now)).allowed()) return false;
            if (settings.finishingBlow() && overrideWouldTakeItNow(k, tick, gateBudget)) return false;
        }
        return true;
    }

    /**
     * Whether the finishing-blow override would break {@code k} right now (task F1, owner's decision
     * 2026-09-29): the same conditions {@link #breakFinishing}'s tier 2 checks for one crystal — ours,
     * finishing-grade, a totem in hand plus a spare (condition a, {@link #totemBacksIt}), no other override
     * crystal pending, late or known (condition b, {@link #overrideAvailable}) and the floor holding without
     * this crystal's own share (condition c, {@link SelfBudget#worstCaseWithoutCrystal}). Read-only: used only
     * by {@link #placeGateOpen} to decide whether its one-at-a-time rule should still wait on this crystal,
     * never to act on it itself — {@link #breakBest}/{@link #breakFinishing} are still what actually break it.
     */
    private boolean overrideWouldTakeItNow(Known k, CrystalTick tick, SelfBudget budget) {
        if (!k.ours || !totemBacksIt(tick.hands())) return false;
        if (!(breakDamageIgnoringSelfDamage(k) > 0)) return false;
        // Only kill-grade (task B0c): a pop-grade break needs at least what the ordinary budget needs (its C is
        // never below I, and it also subtracts the crystal's own share), so wherever the budget refused this
        // crystal, the pop-grade rule refuses it too; it never keeps the gate waiting.
        return finishKind(k.seen.targetDamage()) == FinishKind.KILL
            && breakOverrideHolds(k, FinishKind.KILL, budget);
    }

    /**
     * The scan (lines 931-1008), then the next best spot while the budget refuses (§1); task B0a's finishing
     * blow first. While {@link #placeHealthPausedOnly}, only the override tier may place at all.
     */
    private Optional<Action> placeBest(double health, List<Candidate> candidates) {
        if (settings.selfBudget() && settings.finishingBlow()) {
            Optional<Action> finishing = placeFinishing(health, candidates, placeHealthPausedOnly);
            if (finishing.isPresent()) return finishing;
        }
        if (placeHealthPausedOnly) return Optional.empty();

        List<Scored<Candidate>> able = new ArrayList<>();
        double minimum = minimumDamage();
        for (Candidate c : candidates) {
            if (!c.inRange() || tooHurtful(c.selfDamage(), health, true)) continue;
            float damage = c.damageTo(targets);
            if (damage < minimum || boxTaken(c)) continue;
            // Meteor keeps a spot only if it beats the best so far, which starts at 0 (lines 927, 972, 982).
            if (damage > 0) able.add(new Scored<>(c, damage, c.budgetSelfDamage()));
        }
        able.sort(byDamage(settings.selfBudget()));

        SelfBudget shared = null;
        for (Scored<Candidate> s : able) {
            Reason reason = Reason.BUDGET_OFF;
            if (settings.selfBudget()) {
                // Held back, not refused: the budget is not asked, so it does not count for holding().
                if (swallowed(s.item)) {
                    deferred++;
                    continue;
                }
                long pos = s.item.pos();
                SelfBudget budget;
                // Placing again where we are still waiting for a crystal replaces that placement (see
                // placed): only one crystal can come of the two, so the pending one does not count here.
                if (pendingAt(pos)) budget = budget(health, pos);
                else budget = shared != null ? shared : (shared = budget(health, null));
                Verdict v = budget.placeAllowed(s.item.budgetSelfDamage());
                if (!answered(v)) continue;
                reason = v.reason();
            }
            return Optional.of(place(s.item, reason, FinishKind.NONE));
        }
        return Optional.empty();
    }

    /**
     * Tiers 1-3 of the finishing blow for a place (task B0a; B0c: tier 2 is kill-grade, tier 3
     * pop-grade, which also takes a kill-grade spot whose kill rule failed). Spots are gathered with Meteor's
     * max-damage and anti-suicide skipped, since the override may bypass those too (every other Meteor rule — range,
     * min-damage/face-place minimum, the box — still applies); tier 1 (the normal budget) puts max-damage and
     * anti-suicide back for its own pick, since only tier 2 (the override) may bypass them. Among the
     * finishing-grade spots (never one a target's hurt window would swallow): tier 1's normal-budget pick, by
     * damage, unless {@code healthPausedOnly} (then only the override may act); else tier 2's override pick,
     * gated by a totem in hand plus a spare (condition a), no other override crystal in flight at that spot
     * (condition b) and the floor holding without this spot's own pending placement there (condition c); else
     * tier 3, the pop rule. Empty when none.
     */
    private Optional<Action> placeFinishing(double health, List<Candidate> candidates, boolean healthPausedOnly) {
        double minimum = minimumDamage();
        List<Scored<Candidate>> overrideAble = new ArrayList<>();
        for (Candidate c : candidates) {
            if (!c.inRange()) continue;
            float damage = c.damageTo(targets);
            if (damage < minimum || boxTaken(c)) continue;
            if (damage > 0) overrideAble.add(new Scored<>(c, damage, c.budgetSelfDamage()));
        }
        overrideAble.sort(byDamage(true));

        List<Scored<Candidate>> finishing = new ArrayList<>();
        for (Scored<Candidate> s : overrideAble) {
            if (!swallowed(s.item) && isFinishingGrade(s.item.targetDamage())) finishing.add(s);
        }
        if (finishing.isEmpty()) return Optional.empty();

        if (!healthPausedOnly) {
            SelfBudget shared = null;
            for (Scored<Candidate> s : finishing) {
                if (tooHurtful(s.item.selfDamage(), health, true)) continue;
                long pos = s.item.pos();
                SelfBudget budget;
                if (pendingAt(pos)) budget = budget(health, pos);
                else budget = shared != null ? shared : (shared = budget(health, null));
                Verdict v = budget.placeAllowed(s.item.budgetSelfDamage());
                if (answered(v)) return Optional.of(place(s.item, v.reason(), FinishKind.NONE));
            }
        }

        if (!totemBacksIt(hands)) return Optional.empty();
        // Tier 2: kill-grade spots by the kill rule (task B0c).
        for (Scored<Candidate> s : finishing) {
            if (finishKind(s.item.targetDamage()) != FinishKind.KILL) continue;
            long pos = s.item.pos();
            SelfBudget budget = pendingAt(pos) ? budget(health, pos) : budget(health, null);
            if (overrideAvailable(null, pos) && budget.health() - budget.worstCase() >= SelfBudget.FLOOR) {
                return Optional.of(place(s.item, Reason.FINISHING_BLOW, FinishKind.KILL));
            }
        }
        // Tier 3: every finishing-grade spot by the pop rule, a kill-grade one whose kill rule failed included
        // (fix round 1): it never pops us and takes no slot. Only at Aggressive (owner's decision 2026-09-29):
        // elsewhere a pop-grade spot follows the normal budget (tier 1) alone.
        if (!popMayGoBelowReserve()) return Optional.empty();
        for (Scored<Candidate> s : finishing) {
            long pos = s.item.pos();
            SelfBudget budget = pendingAt(pos) ? budget(health, pos) : budget(health, null);
            if (budget.health() - budget.worstCase() - s.item.budgetSelfDamage() >= SelfBudget.FLOOR) {
                return Optional.of(place(s.item, Reason.FINISHING_BLOW, FinishKind.POP));
            }
        }
        return Optional.empty();
    }

    /**
     * Whether a crystal placed on this spot now would be wasted: every target it hurts is in a window one of our
     * crystals opened, which surely swallows it ({@link TargetWindows#swallows}). A target it hurts that is not, or
     * whose exact raw damage was not measured, takes the hit, so the spot is placed.
     */
    private boolean swallowed(Candidate c) {
        return swallowed(c.targetDamage(), c.targetRaw());
    }

    private boolean swallowed(Map<String, Double> damage, Map<String, Double> exactRaw) {
        boolean hurtsOne = false;
        for (String target : targets) {
            if (!(damage.getOrDefault(target, 0.0) > 0)) continue;
            Double raw = exactRaw.get(target);
            if (raw == null || !windows.swallows(target, raw, now)) return false;
            hurtsOne = true;
        }
        return hurtsOne;
    }

    /**
     * Whether a finishing-grade crystal exists in {@code targetDamage} for at least one of this pre-tick's
     * targets (task B0a, spec Amendment 2026-09-28): the target's reported health is trusted ({@link
     * HealthTrust}, never confirmed on a server that hides or spoofs it), no hurt window of ours is open for
     * it ({@link TargetWindows#openByUs}, its damage would be swallowed or cut), and the predicted damage to
     * it is at least {@link #FINISH_MARGIN} times its reported health plus absorption — it kills the target,
     * or pops his totem if he holds one.
     */
    private boolean isFinishingGrade(Map<String, Double> targetDamage) {
        return finishKind(targetDamage) != FinishKind.NONE;
    }

    /**
     * {@link #isFinishingGrade} with the kind (task B0c, spec Amendment 2026-09-29): {@link FinishKind#KILL} if
     * the crystal is finishing-grade for at least one target that it kills (no totem in either hand, hands
     * visible: {@link TargetView#killable}); else {@link FinishKind#POP} if it is finishing-grade for one that
     * holds a totem or whose hands are not visible; else {@link FinishKind#NONE}.
     */
    private FinishKind finishKind(Map<String, Double> targetDamage) {
        FinishKind kind = FinishKind.NONE;
        for (String t : targets) {
            Double predicted = targetDamage.get(t);
            if (predicted == null || !(predicted > 0)) continue;
            if (!healthTrust.trusted(t)) continue;
            if (windows.openByUs(t, now)) continue;
            Double health = seenHealth.get(t);
            if (health == null || !Double.isFinite(health) || health < 0) continue;
            if (predicted < FINISH_MARGIN * health) continue;
            if (killable.contains(t)) return FinishKind.KILL;
            kind = FinishKind.POP;
        }
        return kind;
    }

    /**
     * Condition b of the finishing-blow override (task B0a): no other crystal of ours that went through it is
     * pending, late (Q2: its placement expired but it has not yet appeared or fully vanished, fix round 1),
     * standing, attacked or within the disappearance window — at most one at a time, since a totem saves one
     * lethal hit and a second would kill. Excludes the very crystal or spot under decision now: re-breaking a
     * crystal we placed through the override, or re-placing at a spot whose own pending or late placement we
     * are replacing (Meteor overwrites it, {@link #placed}), is not "another" one.
     *
     * <p>Fix round 1 (review-b0a.md): a pending override placement that expired (Q2) without its crystal
     * appearing used to vanish from every check here the moment {@link #expirePending} moved it from
     * {@link #pending} to {@link #late} — freeing the slot for a second override crystal while the first, a
     * real crystal that may still detonate near us under lag or a high ping, was still a live, uncounted
     * threat. {@link Late#finish} closes that gap: the slot stays blocked for as long as the placement is
     * remembered at all, in whichever of {@link #pending}, {@link #late} or {@link #known} it currently lives
     * in (its own aging-out rules — the pending lifetime, {@link #LATE_OWN_WINDOW}, or {@link
     * SelfBudget#DISAPPEARANCE_WINDOW} once it is known and gone — are exactly when it is finally forgotten).
     *
     * @param excludeCrystalId the id of the crystal being broken now, or {@code null} for a place
     * @param excludeSpot      the spot being placed on now, or {@code null} for a break
     */
    private boolean overrideAvailable(Integer excludeCrystalId, Long excludeSpot) {
        for (Pending p : pending) {
            if (excludeSpot != null && p.pos == excludeSpot) continue;
            if (p.finish == FinishKind.KILL) return false;
        }
        for (Late l : late) {
            if (excludeSpot != null && l.pos == excludeSpot) continue;
            if (l.finish == FinishKind.KILL) return false;
        }
        for (Known k : known.values()) {
            if (excludeCrystalId != null && k.seen.id() == excludeCrystalId) continue;
            if (k.finish == FinishKind.KILL) return false;
        }
        return true;
    }

    /** Meteor's placement tail (lines 1032-1057 up to the packet): rotate, remember it, build the action. */
    private Action place(Candidate c, Reason reason, FinishKind finish) {
        rotateNow();
        decided = new Placement(c.pos(), c.budgetSelfDamage(), finish);
        boolean swap = settings.autoSwitch() == AutoSwitch.NORMAL && !hands.offhandCrystals() && !hands.mainHandCrystals();
        return new Action(Decision.place(c.pos(), reason), crystalHand(hands), swap);
    }

    /** Whether a placement of ours at this spot is pending or late: placing there again replaces it ({@link #placed}). */
    private boolean pendingAt(long pos) {
        for (Pending p : pending) {
            if (p.pos == pos) return true;
        }
        for (Late l : late) {
            if (l.pos == pos) return true;
        }
        return false;
    }

    /**
     * Any entity in the box above the base, except spectators (the adapter leaves them out) and crystals we
     * attacked that are still in Meteor's wait (Q1, line 1257).
     */
    private boolean boxTaken(Candidate c) {
        if (c.otherEntityInBox()) return true;
        for (int id : c.crystalsInBox()) {
            Known k = known.get(id);
            if (k == null || !k.waiting(now)) return true;
        }
        return false;
    }

    // Shared

    /** The budget now: every crystal known (standing, or gone within the window) and our pending placements. */
    private SelfBudget budget(double health) {
        return budget(health, null);
    }

    /** The same, leaving out the placement pending at {@code replacedSpot}, if any. */
    private SelfBudget budget(double health, Long replacedSpot) {
        List<CrystalView> views = new ArrayList<>(known.size());
        for (Known k : known.values()) views.add(k.view(now));
        List<Double> selfDamages = new ArrayList<>(pending.size());
        for (Pending p : pending) {
            if (replacedSpot == null || p.pos != replacedSpot) selfDamages.add(p.budgetSelfDamage);
        }
        // Task C2 (final review I1): a placement whose wait ran out may still land (Q2), so it counts as pending
        // until its crystal appears or the late window ends, like a pending one; placing again at its spot replaces it.
        for (Late l : late) {
            if (replacedSpot == null || l.pos != replacedSpot) selfDamages.add(l.budgetSelfDamage);
        }
        return SelfBudget.of(now, health, views, selfDamages, settings.budgetReserve(), settings.safeSelfDamage());
    }

    /** Records a budget answer for {@link #holding()}; true if allowed. */
    private boolean answered(Verdict v) {
        asked++;
        if (v.allowed()) {
            allowed++;
            return true;
        }
        if (firstRefusal == null) firstRefusal = v.reason();
        return false;
    }

    /** With {@code rotate} on, an attack or placement is this tick's one action (lines 717-718, 755-766). */
    private void rotateNow() {
        if (!settings.rotate()) return;
        rotated = true;
        ticksSinceRotation = 0;
    }

    /** The hand holding end crystals: the offhand first, else the main hand (lines 885-886, 1036-1043). */
    private static Action.Hand crystalHand(CrystalTick.Hands hands) {
        return hands.offhandCrystals() ? Action.Hand.OFF : Action.Hand.MAIN;
    }

    /**
     * A crystal first seen at {@code at} (the pre-tick at or before it appeared): ours if one of our
     * placements is still pending at its spot, which that crystal then settles; if the spot's placement has
     * expired, a late own crystal, treated as foreign (Q2) in every other way — {@code ours} stays {@code
     * false}, exactly as before this task — but its own lateness is still a real landing sample (task R3-16
     * fix round 3): {@code at - firstTick} is how long it took, at least ({@code at} is only when we noticed
     * it, never earlier than it actually appeared), so it is recorded as a lower bound on that crystal's own
     * landing, never a foreign one's and never invented when there is no matching placement to measure from
     * ({@link TargetWindows#landed} already routes anything past its ordinary ceiling into the outlier bucket
     * fix round 2 reads, no further change needed there).
     *
     * <p>Fix round 1 (review-b0a.md): a late-own crystal placed through the finishing-blow override is the one
     * exception to "treated as foreign in every other way" — {@link Late#finish} still carries onto {@link
     * Known#finish} (checked and confirmed deliberately: only {@code finish}, not {@code ours}
     * itself, which Q2's own foreign treatment must keep false; {@link #overrideAvailable}'s known-loop reads
     * only {@code finish}, never {@code ours}, so this alone is enough to keep condition b's "one at a
     * time" counting it once it is known, continuing the block {@link Late#finish} already gave it while it
     * was only late).
     */
    private Known appeared(CrystalSeen c, long at) {
        boolean ours = false;
        long placedTick = CrystalView.NEVER;
        FinishKind override = FinishKind.NONE;
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (p.pos == c.pos() && at - p.tick < p.lifetime) {
                it.remove();
                ours = true;
                placedTick = p.firstTick;
                override = p.finish;
                break;
            }
        }
        boolean lateMatched = false;
        if (!ours) {
            for (Iterator<Late> it = late.iterator(); it.hasNext(); ) {
                Late l = it.next();
                if (l.pos == c.pos()) {
                    it.remove();
                    lateOwn++;
                    lateMatched = true;
                    windows.landed(at, at - l.firstTick);
                    override = l.finish;
                    break;
                }
            }
        }
        Known k = new Known(c, ours, placedTick);
        k.since = at;
        k.mine = ours || lateMatched;
        k.finish = override;
        known.put(c.id(), k);
        return k;
    }

    private void expirePending() {
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (now - p.tick >= p.lifetime) {
                it.remove();
                late.add(new Late(p.pos, p.budgetSelfDamage, now, p.firstTick, p.finish));
            }
        }
        late.removeIf(l -> now - l.since >= LATE_OWN_WINDOW);
    }

    /**
     * Highest target damage first (Meteor's float, {@code Float.compare}). With the self-budget off, ties
     * keep their original order, so the first spot found wins as in Meteor (strict {@code >}, lines 927,
     * 972, 982). With it on, only among spots whose target damage is EXACTLY equal ({@code Float.compare
     * == 0}; no epsilon window, so a spot with less target damage is never preferred) the tie is broken by
     * {@code budgetSelfDamage} ascending — the crystal that hurts us least — and beyond that ties still
     * keep their original order: both orders are stable sorts on the input list.
     */
    private static Comparator<Scored<?>> byDamage(boolean selfBudget) {
        Comparator<Scored<?>> byTargetDamage = (a, b) -> Float.compare(b.damage, a.damage);
        return selfBudget ? byTargetDamage.thenComparingDouble(s -> s.budgetSelfDamage) : byTargetDamage;
    }

    private record Scored<T>(T item, float damage, double budgetSelfDamage) {}

    /** A placement decided this tick, with the self damage the budget counts for it, and whether it went through the override. */
    private record Placement(long pos, double budgetSelfDamage, FinishKind finish) {}

    /**
     * A placement pending: {@code tick} starts its lifetime; {@code firstTick} is the first of the placements on that
     * base since the last crystal came of one, which its crystal's landing counts from; {@code finish}
     * (task B0a) whether it went through the finishing-blow override, carried to the {@link Known} it settles.
     */
    private record Pending(long pos, double budgetSelfDamage, long tick, int lifetime, long firstTick, FinishKind finish) {}

    /**
     * A placement's own {@code firstTick} carried along, so a late-own crystal's own lateness can still be
     * measured; {@code finish} (task B0a fix round 1) carried from {@link Pending#finish}, so condition
     * b's "one at a time" keeps blocking while an override crystal's placement has expired (Q2) but its
     * crystal has not yet been confirmed gone: neither appeared (still in {@link #late}, see
     * {@link #overrideAvailable}) nor its own {@link #LATE_OWN_WINDOW} elapsed with nothing appearing.
     */
    private record Late(long pos, double budgetSelfDamage, long since, long firstTick, FinishKind finish) {}

    /** A full hit handed over by {@link #targetHurt}, not yet counted. */
    private record ReadHit(String target, int directSourceId) {}

    /** What we know of one crystal: the latest measurement and what we did to it. */
    private static final class Known {
        CrystalSeen seen;
        final boolean ours;
        /** For one of ours, the pre-tick that first decided its placement; {@link CrystalView#NEVER} otherwise. */
        final long placedTick;
        int attempts;
        /** The pre-tick it was first seen at, and whether it is one of ours (late ones too): for the stuck count, log only. */
        long since;
        boolean mine;
        boolean stuckCounted;
        long attackedTick = CrystalView.NEVER;
        long removedTick = CrystalView.NEVER;
        /** Removed from the world since the last pre-tick, which will stamp it. */
        boolean reportedGone;
        /**
         * Task B0a/B0c: which finishing-blow override its placement or break went through. Only {@link
         * FinishKind#KILL} takes the one-at-a-time slot ({@link #overrideAvailable}).
         */
        FinishKind finish = FinishKind.NONE;

        Known(CrystalSeen seen, boolean ours, long placedTick) {
            this.seen = seen;
            this.ours = ours;
            this.placedTick = placedTick;
        }

        boolean live() {
            return removedTick == CrystalView.NEVER && !reportedGone;
        }

        /** In Meteor's {@code removed}: attacked, standing, before the 5th pre-tick after the attack. */
        boolean waiting(long now) {
            return live() && attackedTick != CrystalView.NEVER && now - attackedTick < CrystalView.ATTACK_WAIT_TICKS;
        }

        /** As the budget sees it now; one removed since the last pre-tick counts as gone from it. */
        CrystalView view(long now) {
            return view(now, ours);
        }

        /**
         * The view a break is judged by (task C2, final review I1): a late own crystal ({@code mine} but not
         * {@code ours}) is foreign to Meteor's rules and the ownership of everything else, but it is our own
         * bomb, so the budget must still leave the floor when breaking it.
         */
        CrystalView breakView(long now) {
            return view(now, ours || mine);
        }

        private CrystalView view(long now, boolean own) {
            long removed = removedTick != CrystalView.NEVER ? removedTick : reportedGone ? now : CrystalView.NEVER;
            return new CrystalView(seen.id(), seen.pos(), seen.targetDamage(), seen.selfDamage(), seen.budgetSelfDamage(),
                seen.distance(), seen.inBreakRange(), own, attempts, attackedTick, removed);
        }
    }
}
