package com.xploits.pvp.core;

import java.util.Map;

/**
 * The situation translated into simple values (spec §5). It is the boundary: from here on all the
 * judgement is pure logic and can be tested without starting the game.
 *
 * <p>The target's health is not here on purpose and still is not (redesign §10): no transition needs
 * it and not every server sends it. <b>Yours is</b>: the client always knows it, and the two defensive
 * decisions of the redesign (§5) are taken with it and with the damage already aimed at you, not with
 * a proxy.
 *
 * <p>{@code cityBlockDistance} is the real distance from the player to the surround block -not to the
 * target- (spec §4.2.1, corrected): {@code targetDistance} does not work as a proxy because the block
 * is a horizontal neighbour of the target and can be on the side opposite to where you are, so being
 * close to the target does not guarantee being close to the block. It only makes sense when
 * {@code targetSurroundSides} reaches the minimum; otherwise its value is not used.
 *
 * @param hasTarget               whether the director has a target picked this tick
 * @param targetDistance          real distance to the target
 * @param targetSurroundSides     how many of the <b>four</b> horizontal neighbours of the target, at
 *                                the height of its feet, are of the kind {@code
 *                                EntityUtils.getCityBlock()} considers mineable (obsidian, netherite
 *                                block, crying obsidian, anchor and ancient debris).
 *                                Redesign §4.2.1, corrected by M1: {@code getCityBlock() != null}
 *                                does not measure "has a surround", it measures "there is <b>one</b>
 *                                mineable block next to it", so an enemy standing by the obsidian wall
 *                                of any base -or by the obsidian your own {@code
 *                                auto-trap} has just placed- classified as {@code SURROUNDED} and the
 *                                director started mining the wall. Counting the four sides does
 *                                tell a surround from a wall, and deciding how many are
 *                                needed is the core's job ({@link CombatDirector#SURROUND_MIN_SIDES})
 * @param cityBlockDistance       real distance to the surround block, not to the target
 * @param targetBurrowed          whether the target is <b>protected</b> inside a block.
 *                                Redesign §4.1: the question is not "is there something solid at its
 *                                feet?" but "does it protect it from a crystal?", so the adapter must
 *                                measure it by blast resistance (&ge; 600, the threshold
 *                                {@code PlayerUtils.isInHole} already uses) and with the full cube, not
 *                                with {@code blocksMovement()}: a bottom slab gives 0.833 of half-side
 *                                and {@code blocksMovement()} accepted it, so standing
 *                                on a slab, a stair, a chest or a trapdoor was
 *                                classified as BURROWED
 * @param targetGliding           whether the <b>target</b> has its elytra deployed
 * @param selfGliding             whether you have your elytra deployed. <b>It no longer classifies
 *                                anything</b> (redesign §4.1): on this server people fly almost all the
 *                                time and your flying says nothing about the enemy. Kept only for reporting
 * @param selfTotems              totems you carry
 * @param resources               how much of each resource you carry
 * @param targetId                stable identity of the target (the name will do) or {@code null} if
 *                                there is none. Only used to know when the distance series of
 *                                {@link RetreatWatch} stops referring to the same player
 * @param hostilesInCrystalRange  how many hostiles are in crystal range, <b>protected or not</b>.
 *                                Redesign §4.4, corrected by critical C1: the question that decides
 *                                whether you want the aura is not "can I crystal someone?" but
 *                                "is there someone who can crystal me?". The other one being
 *                                burrowed or surrounded saves them from your crystals; it does not
 *                                save you from theirs, and breaking costs you no crystal (§2). It counts
 *                                hostiles, not allies: {@link AllyPolicy} decides who is which
 * @param selfTotalHealth         your health plus absorption ({@code PlayerUtils.getTotalHealth()})
 * @param incomingDamage          the damage <b>already aimed at you</b>
 *                                ({@code PlayerUtils.possibleHealthReductions()}): placed crystals,
 *                                players with a sword at &le;5, beds in the Nether and falling
 * @param selfInHole              whether you are in a hole ({@code PlayerUtils.isInHole(false)})
 * @param selfOnGround            whether you are touching the ground
 * @param selfYChanged            whether your height has changed this tick or the previous one. It is
 *                                literally the condition under which {@code Surround} turns itself
 *                                off ({@code toggle-on-y-change}, {@code defaultValue(true)}:
 *                                {@code prevY != getY()} checked in {@code TickEvent.Pre}), and
 *                                that is why the posture does not ask for {@code surround} while it is
 *                                true (§5, critical C2): asking for it on a tick in which the module is
 *                                about to turn itself off is what made it impossible to tell its
 *                                self-shutdown from you turning it off
 * @param selfHoleBreached        whether an enemy has just mined out a side of the full hole you were
 *                                standing in (task R3-13 fix 2). {@code selfInHole} alone
 *                                ({@code PlayerUtils.isInHole(false)}) reads false the instant any one of
 *                                the four side neighbours or the one below stops protecting you, which is
 *                                exactly the tick {@code surround} is needed to patch the breach -and the
 *                                tick it used to stop being requested. The adapter remembers the feet
 *                                block ({@code player.getBlockPos()}) of the last tick {@code selfInHole}
 *                                was true and sets this true while you are back on the ground, still on
 *                                that same block, with your height unchanged and {@code selfInHole} now
 *                                false, for up to {@link CombatDirector#BREACH_MEMORY_TICKS} ticks after
 *                                leaving the hole; it is forgotten sooner if you step off that block,
 *                                your height changes, you die, the world changes or auto-pvp deactivates
 * @param crystalAuraProtectsYou  whether the latched crystal aura already keeps you from suiciding
 *                                (redesign §7, corrected by task R3-13 fix 1): Meteor's
 *                                {@code crystal-aura} answers with its {@code anti-suicide} alone
 *                                ({@code defaultValue(true)}, refuses to place or break a crystal whose
 *                                damage to yourself would kill you), but {@code crystal-aura++} answers
 *                                with {@code anti-suicide} <b>or</b> its own {@code self-budget}: with
 *                                {@code self-budget} on, it never places a crystal that would leave you
 *                                below its {@code reserve} (&ge; 2, {@link
 *                                com.xploits.pvp.crystal.core.SelfBudget#FLOOR}) -a stronger guarantee
 *                                than anti-suicide, since it also protects the totem-less placements
 *                                anti-suicide alone would still refuse. <b>Both are only default
 *                                values</b>: if the player turns every protection the latched aura offers
 *                                off, this reads false and the totem floor is needed again
 */
public record CombatSnapshot(boolean hasTarget, double targetDistance,
                             int targetSurroundSides, double cityBlockDistance,
                             boolean targetBurrowed, boolean targetGliding,
                             boolean selfGliding, int selfTotems,
                             Map<Resource, Integer> resources,
                             String targetId, int hostilesInCrystalRange,
                             double selfTotalHealth, double incomingDamage,
                             boolean selfInHole, boolean selfOnGround, boolean selfYChanged,
                             boolean selfHoleBreached, boolean crystalAuraProtectsYou) {
    /** Full health without absorption: the neutral value when nobody has measured the real one. */
    public static final double FULL_HEALTH = 20.0;

    public CombatSnapshot {
        resources = Map.copyOf(resources);
    }

    /**
     * No target and nothing carried. {@code anti-suicide} is taken as <b>off</b>, which is the
     * prudent value: with nobody having really read the setting, the totem floor stays in place.
     */
    public static CombatSnapshot none() {
        return new CombatSnapshot(false, 0, 0, 0, false, false, false, 0, Map.of(),
            null, 0, FULL_HEALTH, 0, false, false, false, false, false);
    }

    public int amountOf(Resource resource) {
        return resources.getOrDefault(resource, 0);
    }

    /** The same snapshot with another target identity. */
    public CombatSnapshot withTargetId(String id) {
        return new CombatSnapshot(hasTarget, targetDistance, targetSurroundSides, cityBlockDistance,
            targetBurrowed, targetGliding, selfGliding, selfTotems, resources,
            id, hostilesInCrystalRange, selfTotalHealth, incomingDamage,
            selfInHole, selfOnGround, selfYChanged, selfHoleBreached, crystalAuraProtectsYou);
    }

    /** The same snapshot with another count of hostiles in crystal range (§4.4, corrected by C1). */
    public CombatSnapshot withHostiles(int hostiles) {
        return new CombatSnapshot(hasTarget, targetDistance, targetSurroundSides, cityBlockDistance,
            targetBurrowed, targetGliding, selfGliding, selfTotems, resources,
            targetId, hostiles, selfTotalHealth, incomingDamage, selfInHole, selfOnGround,
            selfYChanged, selfHoleBreached, crystalAuraProtectsYou);
    }

    /** The same snapshot with another defensive reading (§5). */
    public CombatSnapshot withDefense(double totalHealth, double incoming, boolean inHole, boolean onGround) {
        return new CombatSnapshot(hasTarget, targetDistance, targetSurroundSides, cityBlockDistance,
            targetBurrowed, targetGliding, selfGliding, selfTotems, resources,
            targetId, hostilesInCrystalRange, totalHealth, incoming, inHole, onGround,
            selfYChanged, selfHoleBreached, crystalAuraProtectsYou);
    }

    /** The same snapshot with your height moving or still (§5, critical C2). */
    public CombatSnapshot withSelfYChanged(boolean changed) {
        return new CombatSnapshot(hasTarget, targetDistance, targetSurroundSides, cityBlockDistance,
            targetBurrowed, targetGliding, selfGliding, selfTotems, resources,
            targetId, hostilesInCrystalRange, selfTotalHealth, incomingDamage,
            selfInHole, selfOnGround, changed, selfHoleBreached, crystalAuraProtectsYou);
    }

    /** The same snapshot with the hole you were in just breached, or not (task R3-13 fix 2). */
    public CombatSnapshot withHoleBreached(boolean breached) {
        return new CombatSnapshot(hasTarget, targetDistance, targetSurroundSides, cityBlockDistance,
            targetBurrowed, targetGliding, selfGliding, selfTotems, resources,
            targetId, hostilesInCrystalRange, selfTotalHealth, incomingDamage,
            selfInHole, selfOnGround, selfYChanged, breached, crystalAuraProtectsYou);
    }

    /** The same snapshot at another distance from the target. */
    public CombatSnapshot withTargetDistance(double distance) {
        return new CombatSnapshot(hasTarget, distance, targetSurroundSides, cityBlockDistance,
            targetBurrowed, targetGliding, selfGliding, selfTotems, resources,
            targetId, hostilesInCrystalRange, selfTotalHealth, incomingDamage,
            selfInHole, selfOnGround, selfYChanged, selfHoleBreached, crystalAuraProtectsYou);
    }
}
