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
import java.util.Set;

/**
 * What crystal-aura++ does, tick by tick: Meteor's CrystalAura with its default settings (spec P1, Q1,
 * line numbers from the 1.21.11 sources), plus the self-damage budget (§1, P2-P4) and nothing else. Every
 * action it returns is one Meteor would also allow in the same state; the budget only refuses, and never
 * for breaking a crystal we did not place.
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
 *   and {@link #crystalRemoved} on {@code EntityRemovedEvent}.</li>
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
    private Decision lastDecision = Decision.none(Reason.NOTHING_TO_DO);
    /** The placement decided this tick, until the adapter says it was sent. */
    private Placement decided;

    /** The hands read at HIGH: Meteor's switch rules and the placing hand use them (lines 693-694). */
    private CrystalTick.Hands hands;
    private Action breakAction;
    private Action placeAction;
    /** Meteor's doPlace passed its checks up to the scan this pre-tick (lines 903-924). */
    private boolean placeGate;
    private boolean placeDone = true;

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
            }
        }
        known.values().removeIf(k -> k.removedTick != CrystalView.NEVER
            && now - k.removedTick >= SelfBudget.DISAPPEARANCE_WINDOW);
        expirePending();

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
     * at its spot (ownership first, Q2), then fast-break may attack it at once. Fast-break needs damage
     * above {@code min-damage} (never the face-place minimum), and checks no pause, no timer and not the
     * {@code break} setting; it uses the previous pre-tick's targets. The budget reads {@code health} as
     * it is now (P4).
     *
     * @param settings the settings now: Meteor reads them live here (lines 740-742 and {@code getBreakDamage}),
     *                 so a change made since the last pre-tick already applies
     * @param health   your health plus absorption now
     * @param hands    your hands now (anti-weakness reads them when attacking, lines 826-835)
     */
    public Optional<Action> crystalAdded(CrystalSettings settings, CrystalSeen crystal, double health, CrystalTick.Hands hands) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(crystal, "crystal");
        Objects.requireNonNull(hands, "hands");
        Damage.check(health, "health");
        // Before the first pre-tick Meteor has no targets and nothing pending; the crystal is seen then.
        if (now == NO_TICK || known.containsKey(crystal.id())) return Optional.empty();
        this.settings = settings;
        Known k = appeared(crystal, now);

        if (!settings.fastBreak() || rotated || attacks >= settings.attackFrequency()) return Optional.empty();
        float damage = breakDamage(k, health);
        if (!(damage > settings.minDamage())) return Optional.empty();
        Reason reason = Reason.BUDGET_OFF;
        if (settings.selfBudget()) {
            Verdict v = budget(health).breakAllowed(k.view(now));
            if (!v.allowed()) return Optional.empty();
            reason = v.reason();
        }
        Optional<Action> action = attack(k, hands, reason);
        action.ifPresent(a -> lastDecision = a.decision());
        return action;
    }

    /**
     * {@link #crystalAdded(CrystalSettings, CrystalSeen, double, CrystalTick.Hands)} with the last settings given
     * to the brain, by a pre-tick or by an earlier {@code crystalAdded} (tests).
     */
    Optional<Action> crystalAdded(CrystalSeen crystal, double health, CrystalTick.Hands hands) {
        return crystalAdded(settings, crystal, health, hands);
    }

    /** An end crystal left the world (lines 747-752). */
    public void crystalRemoved(int id) {
        Known k = known.get(id);
        if (k != null && k.removedTick == CrystalView.NEVER) k.reportedGone = true;
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
     * it.
     *
     * @param pingTicks your ping in ticks, rounded up; {@link #UNKNOWN_PING_TICKS} when unknown
     */
    public void placed(long pos, int pingTicks) {
        if (decided == null || decided.pos != pos) throw new IllegalStateException("that placement was not decided this tick");
        if (pingTicks < 0) throw new IllegalArgumentException("ping " + pingTicks);
        pending.removeIf(p -> p.pos == pos);
        late.removeIf(l -> l.pos == pos);
        pending.add(new Pending(pos, decided.budgetSelfDamage, now, Math.max(PENDING_MIN_TICKS, pingTicks + PENDING_PING_MARGIN)));
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

    /** The last thing decided, or why nothing was. */
    public Decision lastDecision() {
        return lastDecision;
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
     */
    private boolean tooHurtful(double selfDamage, double health) {
        return selfDamage > settings.maxDamage() || (settings.antiSuicide() && selfDamage >= health);
    }

    /** Pause (lines 1153-1161). */
    private boolean paused(CrystalTick tick, PauseMode process) {
        if (tick.usingItem() && settings.pauseOnUse().pauses(process)) return true;
        if (settings.pauseOnLag() && tick.lagging()) return true;
        if (tick.pauseModuleActive()) return true;
        if (settings.pauseOnMine().pauses(process) && tick.mining()) return true;
        return tick.health() <= settings.pauseHealth();
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
        if (tooHurtful(k.seen.selfDamage(), health)) return 0;
        float damage = k.seen.damageTo(targets);
        return damage < minimumDamage() ? 0 : damage;
    }

    /** Lines 770-789, then the next best crystal while the budget refuses (P2). */
    private Optional<Action> breakBest(CrystalTick tick) {
        if (!settings.breakCrystals() || switchTimer > 0 || attacks >= settings.attackFrequency()) return Optional.empty();
        if (paused(tick, PauseMode.BREAK)) return Optional.empty();

        List<Scored<Known>> able = new ArrayList<>();
        for (CrystalSeen c : tick.crystals()) {
            Known k = known.get(c.id());
            if (k == null || !k.live()) continue;
            float damage = breakDamage(k, tick.health());
            if (damage > 0) able.add(new Scored<>(k, damage));
        }
        able.sort(BY_DAMAGE);

        SelfBudget budget = null;
        for (Scored<Known> s : able) {
            Reason reason = Reason.BUDGET_OFF;
            if (settings.selfBudget()) {
                if (budget == null) budget = budget(tick.health());
                Verdict v = budget.breakAllowed(s.item.view(now));
                if (!answered(v)) continue;
                reason = v.reason();
            }
            return attack(s.item, tick.hands(), reason);
        }
        return Optional.empty();
    }

    /** Meteor's {@code doBreak(crystal)} (lines 824-875): anti-weakness, then the attack. */
    private Optional<Action> attack(Known k, CrystalTick.Hands hands, Reason reason) {
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
        return Optional.of(new Action(Decision.breakCrystal(k.seen.id(), reason), crystalHand(hands), false));
    }

    // Place

    /** Meteor's doPlace up to the scan (lines 903-924), at HIGH. */
    private boolean placeGateOpen(CrystalTick tick) {
        if (!settings.place()) return false;
        if (paused(tick, PauseMode.PLACE)) return false;
        if (!hands.crystalsInHotbar()) return false;
        if (settings.autoSwitch() != AutoSwitch.NONE) {
            if (settings.noGapSwitch() && settings.autoSwitch() == AutoSwitch.NORMAL && !hands.offhandCrystals()
                && hands.gappleInHand()) return false;
            if (settings.noBowSwitch() && hands.bowInHand()) return false;
        } else if (!hands.mainHandCrystals() && !hands.offhandCrystals()) return false;

        // One at a time, by Meteor's rules and not the budget's (lines 921-924): a crystal the budget will not
        // let us break still stops us adding another.
        for (CrystalSeen c : tick.crystals()) {
            Known k = known.get(c.id());
            if (k != null && k.live() && breakDamage(k, tick.health()) > 0) return false;
        }
        return true;
    }

    /** The scan (lines 931-1008), then the next best spot while the budget refuses (§1). */
    private Optional<Action> placeBest(double health, List<Candidate> candidates) {
        List<Scored<Candidate>> able = new ArrayList<>();
        double minimum = minimumDamage();
        for (Candidate c : candidates) {
            if (!c.inRange() || tooHurtful(c.selfDamage(), health)) continue;
            float damage = c.damageTo(targets);
            if (damage < minimum || boxTaken(c)) continue;
            // Meteor keeps a spot only if it beats the best so far, which starts at 0 (lines 927, 972, 982).
            if (damage > 0) able.add(new Scored<>(c, damage));
        }
        able.sort(BY_DAMAGE);

        SelfBudget shared = null;
        for (Scored<Candidate> s : able) {
            Reason reason = Reason.BUDGET_OFF;
            if (settings.selfBudget()) {
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
            rotateNow();
            decided = new Placement(s.item.pos(), s.item.budgetSelfDamage());
            boolean swap = settings.autoSwitch() == AutoSwitch.NORMAL && !hands.offhandCrystals() && !hands.mainHandCrystals();
            return Optional.of(new Action(Decision.place(s.item.pos(), reason), crystalHand(hands), swap));
        }
        return Optional.empty();
    }

    private boolean pendingAt(long pos) {
        for (Pending p : pending) {
            if (p.pos == pos) return true;
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
     * expired, a late own crystal, treated as foreign (Q2).
     */
    private Known appeared(CrystalSeen c, long at) {
        boolean ours = false;
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (p.pos == c.pos() && at - p.tick < p.lifetime) {
                it.remove();
                ours = true;
                break;
            }
        }
        if (!ours) {
            for (Iterator<Late> it = late.iterator(); it.hasNext(); ) {
                if (it.next().pos == c.pos()) {
                    it.remove();
                    lateOwn++;
                    break;
                }
            }
        }
        Known k = new Known(c, ours);
        known.put(c.id(), k);
        return k;
    }

    private void expirePending() {
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (now - p.tick >= p.lifetime) {
                it.remove();
                late.add(new Late(p.pos, now));
            }
        }
        late.removeIf(l -> now - l.since >= LATE_OWN_WINDOW);
    }

    /** Highest damage first; equal ones keep their order, so the first found wins as in Meteor (strict {@code >}). */
    private static final Comparator<Scored<?>> BY_DAMAGE = (a, b) -> Float.compare(b.damage, a.damage);

    private record Scored<T>(T item, float damage) {}

    /** A placement decided this tick, with the self damage the budget counts for it. */
    private record Placement(long pos, double budgetSelfDamage) {}

    private record Pending(long pos, double budgetSelfDamage, long tick, int lifetime) {}

    private record Late(long pos, long since) {}

    /** What we know of one crystal: the latest measurement and what we did to it. */
    private static final class Known {
        CrystalSeen seen;
        final boolean ours;
        int attempts;
        long attackedTick = CrystalView.NEVER;
        long removedTick = CrystalView.NEVER;
        /** Removed from the world since the last pre-tick, which will stamp it. */
        boolean reportedGone;

        Known(CrystalSeen seen, boolean ours) {
            this.seen = seen;
            this.ours = ours;
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
            long removed = removedTick != CrystalView.NEVER ? removedTick : reportedGone ? now : CrystalView.NEVER;
            return new CrystalView(seen.id(), seen.pos(), seen.targetDamage(), seen.selfDamage(), seen.budgetSelfDamage(),
                seen.distance(), seen.inBreakRange(), ours, attempts, attackedTick, removed);
        }
    }
}
