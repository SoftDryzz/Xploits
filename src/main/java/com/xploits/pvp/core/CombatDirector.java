package com.xploits.pvp.core;

import com.xploits.shared.core.i18n.Msg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The combat judgement (spec §4, redesigned in {@code 2026-09-22-autopvp-decide-bien}). It decides
 * two orthogonal things from the snapshot and returns the <b>union</b> of what each one asks for,
 * filtered by what the player carries:
 *
 * <ul>
 *   <li>the <b>offensive phase</b>, derived from the target (§4), and</li>
 *   <li>the <b>defensive posture</b>, derived from you ({@link DefensivePolicy}, §5).</li>
 * </ul>
 *
 * <p>The mistake this fixes is having put them into a single {@code enum}: "I am being crystalled"
 * and "he is surrounded" are true at the same time, and one enum forces a choice between attacking and
 * defending yourself.
 *
 * <p>It knows nothing about enabling or disabling: that is the adapter's job, which also keeps track
 * of which modules it took ({@link ModuleLedger}).
 */
public final class CombatDirector {
    /**
     * Maximum distance at which a target counts for classifying (§9: from 16 to 10). No managed
     * module goes beyond 10 -{@code AutoWeb} and {@code CrystalAura} are the longest and they stop
     * there-, so the 10-16 band was of no use: it produced phases with a name and without
     * modules, and a target "in combat" that nothing could be done to.
     */
    public static final double CLASSIFY_TARGET_RANGE = 10.0;

    /**
     * Distance at which crystals really land (§4.1, §2). It is not the {@code target-range} of
     * {@code CrystalAura} (10), which is only whom it looks at, but the reach at which the aura places
     * and breaks: closer than this, someone with the elytra deployed is a normal fight and not a
     * chase.
     *
     * <p><b>4.5, not 5.5</b> (minor M2). Checked in the
     * {@code meteor-client:1.21.11-SNAPSHOT} sources: {@code CrystalAura} ships with {@code place-range}
     * {@code defaultValue(4.5)} and {@code break-range} {@code defaultValue(4.5)}; the 10 is
     * {@code target-range}. The earlier 5.5 came from no checked fact and stretched by one block too many
     * both the {@code CHASE} gate and the range in which the hostiles that keep the aura on
     * are counted (§4.4).
     */
    public static final double CRYSTAL_RANGE = 4.5;

    /**
     * How many of the target's four horizontal neighbours must be mineable to call it
     * {@code SURROUNDED} (minor M1).
     *
     * <p>{@code EntityUtils.getCityBlock()} returns the <b>closest</b> mineable neighbour of the
     * four, or {@code null}: checked in the sources, it walks the four horizontal directions, keeps
     * the one with the smallest distance and does not count how many there were. That is,
     * {@code getCityBlock(player) != null} does not answer "does it have a surround?" but "is there
     * <b>one</b> mineable block next to it?", and an enemy standing by the obsidian wall of any base
     * satisfies that, or one standing by the obsidian your own {@code auto-trap} has just placed: the
     * director declared {@code SURROUNDED} and started mining the wall.
     *
     * <p>Three out of four does tell them apart. A whole surround is four; one you have already broken
     * a side of is three, and it is still a surround worth opening. With two it is already half
     * exposed -the aura gets in without mining anything- and a base corner gives two without anyone
     * having surrounded themselves. Requiring all four would leave out the most common case, which is
     * to keep mining the surround you had already started.
     */
    public static final int SURROUND_MIN_SIDES = 3;

    /**
     * Default {@code target-range} of {@code AutoAnvil} (§2). {@code BURROWED} requires it (§4.1):
     * someone burrowed at 12 is not a phase, it is an obstacle -the thing to do is close in, not turn
     * off the aura and stand still-.
     */
    public static final double AUTO_ANVIL_TARGET_RANGE = 4.0;

    /**
     * Default {@code target-range} of {@code AutoTrap} (§2). It is the gate through which
     * {@code auto-trap} enters {@code SURFACE} (§4.2) and {@code BURROWED}: enabling something that
     * does not reach is the same silent failure already fixed for the inventory (§10).
     */
    public static final double AUTO_TRAP_TARGET_RANGE = 3.0;

    /**
     * Default {@code place-range} of {@code AutoWeb} (§2, checked: {@code defaultValue(4)}).
     * It is the upper bound the {@code auto-web} gate was missing (important I5).
     *
     * <p>{@code SURFACE} goes up to {@code approach + 1}, that is up to 7 with the default
     * setting, and the gate did not look above: between 4 and 7 the director enabled {@code auto-web}
     * and the module placed nothing, which is exactly the silent failure §10 forbids -"no
     * module is enabled beyond its real reach"- and the only managed one that had been
     * left without that bound. The {@code target-range} of {@code AutoWeb} is 10, but that only says
     * whom it looks at; what decides whether the web reaches is {@code place-range}.
     */
    public static final double AUTO_WEB_PLACE_RANGE = 4.0;

    /**
     * Hysteresis band of the near bounds: {@code BURROWED} and the two of {@code SURROUNDED}
     * (important I1). Half a block, and <b>always inwards</b>.
     *
     * <p>{@code SURFACE} and {@code APPROACH} already had a band ({@link #APPROACH_BAND}); these
     * three did not, and they are bare thresholds on a distance that moves. Jumping next to someone
     * burrowed at 3.9 raises your Y by up to 1.25 and the distance to 4.08 for about six ticks -more
     * than the two of {@link #BLOCK_HOLD_TICKS}-: the phase oscillated and the {@code auto-anvil}
     * sequence was aborted mid-fall. Against someone burrowed, jumping is normal, and {@code SURROUNDED}
     * had the same gap twice over with its two bounds. Half a block more than covers that excursion: at
     * 3.9 of horizontal distance, going up 1.25 lengthens it by 0.20.
     *
     * <p><b>Inwards</b> means it is <b>entered</b> at {@code limit - band} and
     * <b>left</b> at {@code limit}, not at {@code limit + band}. The band of {@link #APPROACH_BAND}
     * can be symmetric because {@code APPROACH} enables nothing; these two do, and going past the
     * real limit by half a block would enable a module beyond its reach, which is what §10
     * forbids. With {@code auto-city} it would also be worse than a silent failure: outside its
     * {@code break-range} or its {@code target-range} it turns itself off <b>with an error in chat</b>,
     * and the ledger would turn it back on -the storm of twenty enables per second that the two
     * bounds of §4.2.1 exist to avoid-.
     */
    public static final double NEAR_LIMIT_BAND = 0.5;

    /**
     * Half width of the distance band between {@code SURFACE} and {@code APPROACH} (§6):
     * {@code APPROACH} is entered above {@code approach + 1} and it goes back to
     * {@code SURFACE} at {@code approach - 1}. The hysteresis of these two phases is of distance,
     * not of time: at sprint speed (5.6 b/s, 0.28 blocks per tick) crossing the two blocks of
     * the band takes seven ticks, which is the slack needed, and it does not cost the first combo as
     * waiting that long with the target already inside would.
     */
    public static final double APPROACH_BAND = 1.0;

    /**
     * Consecutive ticks without a target before falling to {@code NO_COMBAT} (§6). Half a second.
     *
     * <p>"There is nobody this tick" and "the fight is over" are not the same: the target disappears
     * for an instant because of a server lag spike, because they step behind a block or because the
     * module that picks them does not see them that tick. The earlier version fell to {@code NO_COMBAT}
     * without any slack, and since it also took the dwell as fulfilled whenever the state was
     * {@code NO_COMBAT}, <b>every flicker went through there and dodged the whole protection</b>.
     *
     * <p>Ten ticks more than cover a half-second lag spike, which is the biggest one you can bear while
     * fighting, and they cost nothing when the fight really is over: the only thing that happens if it
     * waits too long is that the modules stay on half a second with nobody in front, and all of them are
     * limited by their own reach. While the grace lasts, decisions keep being made with the last thing
     * seen of the target (see {@link #rememberTarget}), which is what keeps the flicker from turning
     * anything off.
     */
    public static final int TARGET_GRACE_TICKS = 10;

    /**
     * Slack for the transitions that come from reading a block: {@code BURROWED} and
     * {@code SURROUNDED}, in both directions (§6). Two ticks.
     *
     * <p>"Is burrowed" and "has a surround" are read from the world, not from a speed or an
     * animation: either the block is there or it is not. The only cause of flicker is the block really
     * changing -someone breaks it, places it- or arriving one tick out of date, so it is enough to
     * require the reading to repeat once. The spec sets it in §6: "a block change
     * (BURROWED) is a clean signal and allows 2 ticks".
     */
    public static final int BLOCK_HOLD_TICKS = 2;

    /**
     * Slack for <b>entering</b> {@code CHASE} (§6). Four ticks, 0.2 s.
     *
     * <p>A take-off has a couple of ticks of ambiguity while the elytra opens, and entering
     * {@code CHASE} turns off everything offensive, so it is not wise to do it on a single tick of
     * evidence. Four ticks stay below the shortest crystal cycle §9 measures
     * (half a second is 2-5 cycles, that is 4-10 ticks per cycle), so getting it wrong does not
     * cost a whole cycle; and the aura, which is the expensive one to turn off, no longer depends on the phase (§4.4).
     */
    public static final int GLIDE_ENTER_HOLD_TICKS = 4;

    /**
     * Slack for <b>leaving</b> {@code CHASE} because the target has stopped gliding (§6).
     * Ten ticks, half a second: "quite a bit more to leave, because of the bounces on landing".
     *
     * <p>Landing with an elytra does not end the glide at once: it skims the ground and the flag
     * turns on and off several times in a row for a few tenths of a second. Ten consecutive ticks
     * without gliding are longer than any of those bounces. Waiting is cheap because {@code CHASE} no
     * longer enables anything -{@code auto-web}, with {@code place-range} 4 and a 10-tick prediction,
     * never places at elytra speed (§4.2)- and the aura keeps working on its own.
     *
     * <p>It does not apply when what changes is the distance: if they are still gliding but have got
     * into crystal range, they have come at you, and that is a clean position signal handled
     * with {@link #BLOCK_HOLD_TICKS}.
     */
    public static final int GLIDE_EXIT_HOLD_TICKS = 10;

    /**
     * Consecutive ticks below the minimum that an already enabled module holds before being released
     * (spec §6.2). Checked as correct in §9: not to be touched.
     */
    public static final int RESOURCE_RELEASE_DWELL_TICKS = 20;

    /**
     * Maximum real distance to the surround block to classify {@code SURROUNDED} (spec §4.2.1,
     * second correction). Checked against the {@code meteor-client:1.21.11-SNAPSHOT} sources
     * (`AutoCity.java`): the module turns itself off -inside its own
     * {@code onActivate()}/{@code onTick()}, with an error in chat- if the surround block is
     * more than {@code break-range} (by default **4.5**, the default value this constant fixes)
     * away from you, checked with {@code PlayerUtils.squaredDistanceTo(targetPos)} on the block's
     * {@code BlockPos}. The other limit of {@code auto-city} -{@code target-range}, against the target, not
     * the block- is {@link #AUTO_CITY_TARGET_RANGE}: both are needed at once (third
     * correction), this constant alone is no longer enough.
     *
     * <p><b>4.5 is the default {@code break-range} setting; the user can change it in
     * Meteor.</b> This constant does not read it live -the core imports nothing from
     * {@code meteordevelopment}-, so if someone raises or lowers their {@code break-range} the director
     * keeps comparing against 4.5, not against the real configured value. It is the same treatment
     * {@link #AUTO_CITY_TARGET_RANGE} gets.
     *
     * <p><b>The comparison is against the real distance to the block, not to the target</b>
     * ({@link CombatSnapshot#cityBlockDistance()}). Using the distance to the target as a proxy -what
     * the first correction did- is wrong: the surround block is a horizontal neighbour
     * of the target (`EntityUtils.getCityBlock()`) and can be on the side opposite to where you are,
     * so a close target does not guarantee a close block. Real counterexample: player at
     * (0.5, 0, 0.5), target at (4.5, 0, 0.5) -distance 4.0, inside the old limit-, surround block at
     * (5, 0, 0) -squared distance 20.5, above 4.5² = 20.25-: the earlier version
     * declared SURROUNDED and auto-city turned itself off, with an error, every tick. Besides,
     * {@code PlayerUtils.squaredDistanceTo(BlockPos)} measures to the block's minimum corner, not to its
     * center, so not even a "conservative" bound based on the target can properly bound the
     * real distance to the block.
     */
    public static final double AUTO_CITY_BREAK_RANGE = 4.5;

    /**
     * Maximum real distance to the target (not to the block) to classify {@code SURROUNDED} (spec
     * §4.2.1, third correction). {@link #AUTO_CITY_BREAK_RANGE} alone is not enough: checked
     * in the {@code meteor-client:1.21.11-SNAPSHOT} sources (`AutoCity.onTick()` first calls
     * {@code TargetUtils.isBadTarget(target, targetRange.get())}, which requires
     * {@code PlayerUtils.isWithin(target, targetRange)}, <b>before</b> looking at the block at all),
     * {@code auto-city} also turns itself off -same unconditional `toggle()`, same error in
     * chat- if the target itself is more than {@code target-range} away (by default **5.5**, the default
     * value this constant fixes), with the distance to the block not mattering at all.
     *
     * <p>The surround block is a horizontal neighbour of the target measured to its minimum corner
     * ({@code EntityUtils.getCityBlock()} / {@code PlayerUtils.squaredDistanceTo(BlockPos)}), so
     * a block at &le; {@link #AUTO_CITY_BREAK_RANGE} (4.5) allows a target up to
     * &asymp;6.4: without this bound, that gap between 5.5 and ~6.4 declared {@code SURROUNDED} again with the
     * target out of the real reach of {@code auto-city}, which turned itself off every tick and the
     * ledger turned it back on -the same storm of twenty enables and twenty errors per
     * second that the {@link #AUTO_CITY_BREAK_RANGE} correction had already removed for the opposite
     * case (target close, block far)-.
     *
     * <p>5.5 is the default {@code target-range} setting; same treatment as
     * {@link #AUTO_CITY_BREAK_RANGE}: it is not read live from the user's real setting.
     */
    public static final double AUTO_CITY_TARGET_RANGE = 5.5;

    private CombatState state = CombatState.NO_COMBAT;
    private CombatState pending;
    private int pendingTicks;
    private int ticksInState;

    /** The distance series used to decide whether the target is really moving away (§4.3). */
    private final RetreatWatch retreat = new RetreatWatch();

    /**
     * The last snapshot in which there was a target, and how many consecutive ticks it has been without one.
     * Together they are the grace of §6: while it lasts, decisions keep being made with the last thing
     * seen of the target instead of taking the fight as over.
     */
    private CombatSnapshot lastSeenTarget;
    private int missingTargetTicks;

    /**
     * The modules the {@link Plan} of the previous tick returned in {@code enable()}. It is the
     * memory needed for the hysteresis of the resource filter (spec §6.2): without it,
     * {@code planFor} could not know whether a module was already on.
     *
     * <p>It is only forgotten in {@link #reset()}. It used to be overwritten also on entering
     * {@code NO_COMBAT}, which is entered without waiting: a target that left range for one tick and came
     * back wiped the whole fight's resource memory. Since then it is kept in
     * {@code NO_COMBAT}, but <b>only the offensive half</b> (important I4): see
     * {@link #rememberEnabled}.
     */
    private Set<ManagedModule> previouslyEnabled = Set.of();

    /**
     * Consecutive ticks each module has been below its minimum while it stays on through
     * hysteresis (spec §6.2). It only has an entry while the module is in its grace window;
     * it is cleared as soon as it has enough again or its dwell runs out.
     */
    private final Map<ManagedModule, Integer> belowMinimumTicks = new HashMap<>();

    /**
     * The physical phase the director is in right now. It is never {@code OUT_OF_RESOURCES}: that
     * phase only appears in the {@link Plan} {@link #tick} returns, not here (spec §4.2).
     */
    public CombatState state() {
        return state;
    }

    /** Ticks the director has been in the current phase, counting from the last change. */
    public int ticksInState() {
        return ticksInState;
    }

    /** Whether the target is moving away steadily right now (§4.3). */
    public boolean targetRetreating() {
        return retreat.retreating();
    }

    /** Forgets the phase, the counters and which modules it had on. Called when the module is turned on. */
    public void reset() {
        state = CombatState.NO_COMBAT;
        pending = null;
        pendingTicks = 0;
        ticksInState = 0;
        previouslyEnabled = Set.of();
        belowMinimumTicks.clear();
        retreat.reset();
        lastSeenTarget = null;
        missingTargetTicks = 0;
    }

    /** A whole cycle of the judgement with the default defensive margin. */
    public Plan tick(CombatSnapshot snapshot, int approachDistance) {
        return tick(snapshot, approachDistance, DefensivePolicy.THREAT_MARGIN);
    }

    /**
     * Runs a whole cycle of the judgement. In order:
     *
     * <ol>
     *   <li><b>Grace on losing the target</b> (§6): if there is no target this tick -or it is beyond
     *       {@link #CLASSIFY_TARGET_RANGE}- but there was one less than
     *       {@link #TARGET_GRACE_TICKS} ago, it classifies with the last thing seen of it. The
     *       snapshot's own half (resources, health, hole) is always this tick's: what is
     *       remembered is the enemy, not you.</li>
     *   <li><b>Offensive phase</b> with the precedence of §4.1, adopted when the candidate
     *       holds for the ticks <b>that</b> transition asks for (§6: slack per transition, not a
     *       global one). {@code MIN_DWELL_TICKS} no longer exists: it made the director take longer to
     *       correct its mistake than to make it, and it must never delay a transition that turns the
     *       aura back on.</li>
     *   <li><b>Defensive posture</b> ({@link DefensivePolicy}), which does not depend on the phase.</li>
     *   <li>The <b>union</b> of what the two axes ask for, filtered by resources with hysteresis and by
     *       the totem floor, which since §7.1 only stands with the {@code anti-suicide} of
     *       {@code crystal-aura} off.</li>
     * </ol>
     *
     * @param snapshot         the situation of this tick, already translated into simple values (spec §5)
     * @param approachDistance distance from which the target is considered far, not close
     * @param threatMargin     health you must have left, after subtracting what is already aimed at you, to
     *                         stay {@code CALM} (§5). The spec leaves the threshold open and as a
     *                         module setting ({@code threat-margin});
     *                         {@link DefensivePolicy#THREAT_MARGIN} is only its default value, the
     *                         one the short signature uses. Same treatment as {@code approach-distance}: the
     *                         player sets the number, the decision still belongs to the core
     * @return this tick's plan: the phase it reports (it can be {@code OUT_OF_RESOURCES}
     *     even though the physical phase is still another), the posture, the modules to enable, those
     *     left out with their reason and the warnings
     */
    public Plan tick(CombatSnapshot snapshot, int approachDistance, double threatMargin) {
        CombatSnapshot effective = rememberTarget(snapshot);

        CombatState candidate = classify(effective, approachDistance, state);
        if (candidate != state) {
            pendingTicks = candidate == pending ? pendingTicks + 1 : 1;
            pending = candidate;
            if (pendingTicks >= holdTicksFor(state, candidate, effective)) enter(candidate);
        } else {
            pending = null;
            pendingTicks = 0;
        }

        ticksInState++;
        boolean retreating = retreat.update(effective);
        Plan plan = planFor(state, effective, retreating, threatMargin);
        previouslyEnabled = rememberEnabled(plan);
        return plan;
    }

    /**
     * The resource memory for the next tick. It is computed AFTER the plan: {@code planFor()}
     * needs to see what was on in the previous tick, not what this one has just decided.
     *
     * <p>The freeze in {@code NO_COMBAT} belongs to the <b>offensive axis</b> and only to it (important
     * I4). It exists because the phase enters {@code NO_COMBAT} without waiting, and a target that left
     * range for one tick and came back wiped the whole fight's resource memory (spec §6.2). But it was
     * being applied to both axes, and <b>the defensive axis does decide in {@code NO_COMBAT}</b>:
     * without obsidian and threatened, {@code hole-filler} went in and out seven times in eighty ticks,
     * with its chat line every time, because its grace window never got to start.
     *
     * <p>So the defensive part is always updated -its axis is deciding- and the offensive part is
     * kept as is while the physical phase is {@code NO_COMBAT}, which is where its axis decides
     * nothing.
     */
    private Set<ManagedModule> rememberEnabled(Plan plan) {
        if (state != CombatState.NO_COMBAT) return Set.copyOf(plan.enable());

        Set<ManagedModule> memory = new LinkedHashSet<>(plan.enable());
        for (ManagedModule module : previouslyEnabled) {
            if (!ManagedModules.isDefensive(module)) memory.add(module);
        }
        return Set.copyOf(memory);
    }

    /**
     * The grace of §6, made into a snapshot: while the target has been lost for fewer than
     * {@link #TARGET_GRACE_TICKS} ticks, this tick's snapshot is returned with the enemy's half
     * replaced by the last one really seen.
     *
     * <p>Replacing the snapshot -instead of freezing the phase- is what makes the grace really
     * protect: the phase comes out of the normal classification by itself, and the modules that depend
     * on the reach to the target ({@code auto-trap} at 3, {@code auto-anvil} at 4) also stay on during
     * the flicker instead of dropping for a tick and coming back.
     */
    private CombatSnapshot rememberTarget(CombatSnapshot now) {
        boolean lost = !now.hasTarget() || now.targetDistance() > CLASSIFY_TARGET_RANGE;
        if (!lost) {
            lastSeenTarget = now;
            missingTargetTicks = 0;
            return now;
        }

        // On the tenth consecutive tick without seeing it the grace is over: the nine before are decided
        // with the last thing seen of it.
        missingTargetTicks++;
        if (lastSeenTarget == null || missingTargetTicks >= TARGET_GRACE_TICKS) {
            lastSeenTarget = null;
            return now;
        }

        CombatSnapshot seen = lastSeenTarget;
        return new CombatSnapshot(true, seen.targetDistance(), seen.targetSurroundSides(),
            seen.cityBlockDistance(), seen.targetBurrowed(), seen.targetGliding(),
            now.selfGliding(), now.selfTotems(), now.resources(),
            seen.targetId(), now.hostilesInCrystalRange(),
            now.selfTotalHealth(), now.incomingDamage(), now.selfInHole(), now.selfOnGround(),
            now.selfYChanged(), now.crystalAuraAntiSuicide());
    }

    private void enter(CombatState next) {
        if (next != state) {
            state = next;
            ticksInState = 0;
        }
        pending = null;
        pendingTicks = 0;
    }

    /**
     * The precedence of §4.1: the first one that holds wins.
     *
     * <p>Three changes from the earlier judgement. {@code selfGliding()} <b>no longer triggers</b>
     * {@code CHASE}: on this server people fly almost all the time, so the director spent
     * most of the time in the phase that does the least, and your flying says nothing about the enemy.
     * Only their flying counts, and only if they are also out of crystal range: flying and right next to
     * you is a normal fight and the crystals get to them just the same. {@code BURROWED} requires anvil
     * range, because someone burrowed at 12 is an obstacle, not a phase. And {@code SURFACE} and
     * {@code APPROACH} are separated by a distance band, not a bare threshold, which is
     * where half the oscillation came from.
     *
     * <p>The <b>three near bounds</b> have had a band since important I1: the one of
     * {@code BURROWED} and the two of {@code SURROUNDED} were bare thresholds on a distance that
     * moves, and a jump -the normal movement next to someone burrowed- crossed them for about six
     * ticks. Their band goes <b>inwards</b>, unlike the {@code approach} one: see
     * {@link #NEAR_LIMIT_BAND}.
     *
     * <p>And {@code SURROUNDED} no longer settles for one mineable block next to the target, which is all
     * {@code getCityBlock() != null} says: it asks for {@link #SURROUND_MIN_SIDES} of the four
     * sides (minor M1).
     *
     * @param current the current phase, used only to know on which side of the band it has to
     *                leave; the rest of the classification does not depend on it
     */
    static CombatState classify(CombatSnapshot s, int approachDistance, CombatState current) {
        if (!s.hasTarget() || s.targetDistance() > CLASSIFY_TARGET_RANGE) return CombatState.NO_COMBAT;
        if (s.targetGliding() && s.targetDistance() > CRYSTAL_RANGE) return CombatState.CHASE;

        // The three near bounds, with an inward band (I1): to enter you have to be half a
        // block under the real limit; to leave, go past the real limit. Not for a single tick is
        // anything enabled beyond its reach (§10) and a jump no longer makes the phase oscillate.
        double anvilLimit = current == CombatState.BURROWED
            ? AUTO_ANVIL_TARGET_RANGE
            : AUTO_ANVIL_TARGET_RANGE - NEAR_LIMIT_BAND;
        if (s.targetBurrowed() && s.targetDistance() <= anvilLimit) return CombatState.BURROWED;

        boolean alreadyCity = current == CombatState.SURROUNDED;
        double cityBlockLimit = alreadyCity
            ? AUTO_CITY_BREAK_RANGE
            : AUTO_CITY_BREAK_RANGE - NEAR_LIMIT_BAND;
        double cityTargetLimit = alreadyCity
            ? AUTO_CITY_TARGET_RANGE
            : AUTO_CITY_TARGET_RANGE - NEAR_LIMIT_BAND;
        if (s.targetSurroundSides() >= SURROUND_MIN_SIDES && s.cityBlockDistance() <= cityBlockLimit
            && s.targetDistance() <= cityTargetLimit) return CombatState.SURROUNDED;

        // The band: to enter APPROACH you have to go past approach + 1; to go back to
        // SURFACE you have to drop below approach - 1. In between, the phase you were already in rules,
        // so a target standing right at approach cannot make anything oscillate.
        double exit = current == CombatState.APPROACH
            ? approachDistance - APPROACH_BAND
            : approachDistance + APPROACH_BAND;
        if (s.targetDistance() > exit) return CombatState.APPROACH;
        return CombatState.SURFACE;
    }

    /**
     * How many consecutive ticks the candidate has to hold to be adopted (§6): a slack per
     * transition, not a global one. Half a second is 2-5 crystal cycles, that is 15-40 damage, so
     * charging ten ticks to every transition alike was expensive exactly where it was not
     * needed.
     */
    private static int holdTicksFor(CombatState from, CombatState to, CombatSnapshot s) {
        // Releasing late is not acceptable and engaging late costs the first combo: both ends
        // go without waiting. The TARGET_GRACE_TICKS grace already sits in front of NO_COMBAT, so
        // when the candidate gets this far the fight really is over.
        if (to == CombatState.NO_COMBAT || from == CombatState.NO_COMBAT) return 0;

        if (from == CombatState.CHASE) {
            // If they are still gliding, what has changed is the distance -they have come at you-, and
            // that is a clean position signal, not a landing bounce.
            return s.targetGliding() ? BLOCK_HOLD_TICKS : GLIDE_EXIT_HOLD_TICKS;
        }
        if (to == CombatState.CHASE) return GLIDE_ENTER_HOLD_TICKS;
        if (to == CombatState.BURROWED || from == CombatState.BURROWED
            || to == CombatState.SURROUNDED || from == CombatState.SURROUNDED) return BLOCK_HOLD_TICKS;

        // SURFACE <-> APPROACH: the hysteresis is the distance band, not time.
        return 0;
    }

    /**
     * What the offensive phase asks for (§4.2), with each module's real reach already checked first (§10:
     * no module is enabled beyond its reach).
     *
     * <p>{@code APPROACH} and {@code CHASE} ask for nothing and are reporting labels: between
     * 6 and 16 blocks there is nothing useful to enable, and {@code auto-web}, with {@code place-range} 4 and
     * a 10-tick prediction, never places at elytra speed -enabling it was pretending to do
     * something-. {@code surround} does not appear either: it is defensive and the posture asks for it (§5).
     */
    private static List<ManagedModule> offensiveModules(CombatState state, CombatSnapshot s, boolean retreating) {
        if (!s.hasTarget()) return List.of();

        List<ManagedModule> modules = new ArrayList<>();
        switch (state) {
            case SURFACE -> {
                modules.add(ManagedModules.CRYSTAL_AURA);
                if (s.targetDistance() <= AUTO_TRAP_TARGET_RANGE) modules.add(ManagedModules.AUTO_TRAP);
                // §4.3, corrected by importants I2 and I5: the web is for stopping them from
                // leaving, and only while it reaches.
                //
                // The gate had both sides wrong. Above there was no bound: SURFACE goes up to 7
                // and AutoWeb's place-range is 4, so between 4 and 7 it was enabled and placed
                // nothing (§10). Below there was the "or is more than 3 blocks away", a bare threshold
                // that with the target hovering at 3.0 gave 39 state changes in 40 ticks and that
                // also short-circuited with an || the dead band RetreatWatch looks after.
                //
                // That second half did not need a band: it needed to go. Its reason was that
                // beyond 3 the cell it webs would no longer be the next crystal's, and that is
                // false -the aura's place-range is 4.5, so in the whole 3-4 band left
                // under the new bound the aura still wants that cell-. Without it there is no threshold
                // to cross and nothing oscillates: RetreatWatch rules, and it already brings its band.
                if (retreating && s.targetDistance() <= AUTO_WEB_PLACE_RANGE) modules.add(ManagedModules.AUTO_WEB);
            }
            case SURROUNDED -> {
                modules.add(ManagedModules.AUTO_CITY);
                modules.add(ManagedModules.CRYSTAL_AURA);
            }
            case BURROWED -> {
                modules.add(ManagedModules.AUTO_ANVIL);
                // The trap is for when they come out of the burrow, but AutoTrap works at 3 and BURROWED
                // goes up to 4: in between it does not reach, so it is not enabled.
                if (s.targetDistance() <= AUTO_TRAP_TARGET_RANGE) modules.add(ManagedModules.AUTO_TRAP);
            }
            default -> { }
        }
        return modules;
    }

    private Plan planFor(CombatState state, CombatSnapshot snapshot, boolean retreating, double threatMargin) {
        List<ManagedModule> offensive = offensiveModules(state, snapshot, retreating);

        Set<ManagedModule> wanted = new LinkedHashSet<>(offensive);
        // §4.4, corrected by critical C1: the aura is wanted whenever there is some hostile in crystal
        // range, protected or not.
        //
        // The earlier rule asked "can I crystal someone?" to decide "do I need the
        // aura?", and those are two different questions: the aura does two things and breaking costs no crystals
        // (§2). The other one being protected saves them from your crystals; it does not save you from
        // theirs. The fight that lost: you are at 3.5 from someone who is losing, they burrow -the
        // server's standard move-, the phase goes to BURROWED, they keep placing
        // crystals on you from inside the burrow and, since "protected" did not count, the ledger turned off your
        // autobreak against the only one who could kill you. It leans the way §10 says: leaving it on
        // too long costs a few crystals; turning it off costs the fight.
        if (snapshot.hostilesInCrystalRange() > 0) wanted.add(ManagedModules.CRYSTAL_AURA);

        CombatPosture posture = DefensivePolicy.postureFor(snapshot, threatMargin);
        wanted.addAll(DefensivePolicy.modulesFor(posture, snapshot));

        List<ManagedModule> enable = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        List<Msg> warnings = new ArrayList<>();
        // The share-out of a shared resource (I3): what is left of each resource as it gets
        // set aside, and who took it, so the reason can say so.
        Map<Resource, Integer> remaining = new HashMap<>();
        Map<Resource, List<String>> claimedBy = new HashMap<>();

        for (ManagedModule module : inSharePriorityOrder(wanted)) {
            if (module.equals(ManagedModules.CRYSTAL_AURA)) {
                // The totem floor, now only as an emergency net. It is §7 through another door: it was
                // a life decision taken with an item counter, and Meteor already takes it with the exact
                // damage. For the PLACE half, anti-suicide (defaultValue(true)) refuses to
                // place a crystal that kills you; for the BREAK half, the floor took away your
                // autobreak exactly when you carry no totems, which is when it is needed most.
                //
                // But anti-suicide is only a default value: if the player has turned it off, that
                // protection does not exist, and then -and only then- the floor stays in place. The
                // reason says it in full so the player knows what to turn off or on.
                if (snapshot.selfTotems() <= 0 && !snapshot.crystalAuraAntiSuicide()) {
                    skipped.add(new Skipped(module, Msg.of(PvpText.TOTEM_FLOOR)));
                    continue;
                }
                // §7: the aura stays out of the resource filter. It is the only managed one with
                // a useful half at zero cost -CrystalAura ships with break at true and only-own at false,
                // and breaking spends none of your crystals-, so turning it off for running out of
                // crystals takes away exactly what keeps you alive when you have nothing to
                // answer with. It is enabled anyway and reported as a warning, not as an omission.
                belowMinimumTicks.remove(module);
                enable.add(module);
                if (snapshot.amountOf(Resource.CRYSTALS) < ManagedModules.CRYSTAL_AURA.minimum()) {
                    warnings.add(Msg.of(PvpText.AURA_NO_CRYSTALS));
                }
                continue;
            }
            if (hasEnough(module, snapshot, enable, remaining, claimedBy)) continue;
            skipped.add(new Skipped(module, shortageReason(module, snapshot, remaining, claimedBy)));
        }

        // OUT_OF_RESOURCES is how it is reported, not a place to live in (spec §4.2), and it is measured only
        // on the offensive half: the posture having raised an anti-bed does not mean you
        // can fight, and there being nothing to fight -APPROACH, CHASE- is not
        // running out of resources either.
        boolean offensiveUp = enable.stream().anyMatch(offensive::contains);
        CombatState reported = !offensive.isEmpty() && !offensiveUp ? CombatState.OUT_OF_RESOURCES : state;
        return new Plan(reported, posture, enable, skipped, warnings);
    }

    /**
     * The hysteresis of the resource filter (spec §6.2): without it, a resource that is spent
     * during the fight (auto-trap's obsidian, for example) crosses the minimum again and again and the
     * module is turned on and off on every tick.
     *
     * <p>A module that was not on in the previous tick needs the full minimum, without
     * grace. One that was stays on while it has been below the minimum for fewer than
     * {@link #RESOURCE_RELEASE_DWELL_TICKS} consecutive ticks; once it reaches them, it is
     * released. It is a dwell in time, not a split threshold: with {@code minimum() == 1}
     * -most of the managed ones- a threshold at half rounded to the same minimum and gave no
     * grace at all; counting ticks works the same for all of them.
     */
    private boolean hasEnough(ManagedModule module, CombatSnapshot snapshot, List<ManagedModule> enable,
                              Map<Resource, Integer> remaining, Map<Resource, List<String>> claimedBy) {
        int have = left(module.needs(), snapshot, remaining);
        if (have >= module.minimum()) {
            belowMinimumTicks.remove(module);
            enable.add(module);
            claim(module, snapshot, remaining, claimedBy);
            return true;
        }

        if (!previouslyEnabled.contains(module)) {
            belowMinimumTicks.remove(module);
            return false;
        }

        int ticksBelow = belowMinimumTicks.merge(module, 1, Integer::sum);
        if (ticksBelow < RESOURCE_RELEASE_DWELL_TICKS) {
            enable.add(module);
            // During the grace it also sets aside: it will keep swapping to the same stack, so
            // approving another one beyond what is left is the same I3 failure through the other door.
            claim(module, snapshot, remaining, claimedBy);
            return true;
        }

        belowMinimumTicks.remove(module);
        return false;
    }

    /**
     * The wanted modules, ordered for the I3 share-out: first those that share a
     * resource, in the priority declared in {@link ManagedModules#SHARED_RESOURCE_PRIORITY}, and
     * after them the rest in the order the two axes asked for them. Those at the back share with
     * nobody, so for them the order changes nothing.
     */
    private static List<ManagedModule> inSharePriorityOrder(Set<ManagedModule> wanted) {
        List<ManagedModule> ordered = new ArrayList<>();
        for (ManagedModule module : ManagedModules.SHARED_RESOURCE_PRIORITY) {
            if (wanted.contains(module)) ordered.add(module);
        }
        for (ManagedModule module : wanted) {
            if (!ordered.contains(module)) ordered.add(module);
        }
        return ordered;
    }

    /** What is left of the resource in this pass; the first time, everything you carry. */
    private static int left(Resource resource, CombatSnapshot snapshot, Map<Resource, Integer> remaining) {
        return remaining.computeIfAbsent(resource, snapshot::amountOf);
    }

    /**
     * Sets aside for this module what it needs at least (I3). If there is not that much -the case of the
     * grace window- it sets aside whatever is left: it is going to go for it anyway.
     */
    private static void claim(ManagedModule module, CombatSnapshot snapshot,
                              Map<Resource, Integer> remaining, Map<Resource, List<String>> claimedBy) {
        if (module.minimum() <= 0) return;
        int have = left(module.needs(), snapshot, remaining);
        remaining.put(module.needs(), Math.max(0, have - module.minimum()));
        claimedBy.computeIfAbsent(module.needs(), resource -> new ArrayList<>()).add(module.name());
    }

    /**
     * Why it is not enabled. When another module has already taken part of the same stack (I3) the reason
     * says so and names it: approving more than there is no longer holds, but keeping quiet that the stack is
     * shared does not either -the player learnt nothing and {@code skipped} came out empty-.
     */
    private static Msg shortageReason(ManagedModule module, CombatSnapshot snapshot,
                                      Map<Resource, Integer> remaining, Map<Resource, List<String>> claimedBy) {
        List<String> others = claimedBy.get(module.needs());
        if (others == null || others.isEmpty()) {
            return Msg.of(PvpText.SHORTAGE, "have", snapshot.amountOf(module.needs()), "minimum", module.minimum());
        }
        Object joined = others.getFirst();
        for (String other : others.subList(1, others.size())) {
            joined = Msg.of(PvpText.JOIN_AND, "first", joined, "second", other);
        }
        return Msg.of(PvpText.SHORTAGE_SHARED, "have", snapshot.amountOf(module.needs()), "others", joined,
            "left", left(module.needs(), snapshot, remaining), "minimum", module.minimum());
    }
}
