package com.xploits.pvp.core;

import java.util.List;

/**
 * The catalog of the modules the director manages (spec §6, extended by redesign §5). The names are
 * checked against the meteor-client 1.21.11 sources. The "usual" modules (auto-totem, auto-armor,
 * offhand, auto-weapon) are NOT here on purpose: they belong to the player and the director does not
 * touch them.
 *
 * <p>It is split into two halves, one per axis of redesign §3: the <b>offensive</b> ones, picked by
 * the target's phase, and the <b>defensive</b> ones ({@link #DEFENSIVE}), picked by your posture.
 * {@code surround} now belongs only to the posture: it left {@code APPROACH}, where it locked you in
 * obsidian while you ran, re-centered you on the block fighting against your input and spent the
 * obsidian {@code auto-trap} was going to need.
 *
 * <p>The split is not decorative: there are structures -the director's resource memory and the
 * {@link ModuleLedger}'s memory of what the player released by hand- that were written for a single
 * axis and applied to both (importants I4 and I6). Knowing which axis each module belongs to is what
 * lets each of them be indexed by its own.
 *
 * <p>{@code self-trap}, {@code self-web} and {@code burrow} are left out of the catalog on purpose
 * (§5): they lock you in, and if the judgement is wrong your own client immobilizes you in a fight you
 * were winning.
 */
public final class ManagedModules {
    // The fourth parameter is turnsItselfOff (spec §7): crystal-aura and auto-web only turn off
    // because the player turns them off by hand; the other three offensive ones can turn themselves
    // off without the player touching them -not all three for the same reason, nor all by default: see
    // the table in spec §7-, and ModuleLedger needs to know so as not to mistake that shutdown for a manual one.
    // The last one is reactive, false for all five: they act whenever there is a target, not only when
    // a threat shows up.
    public static final ManagedModule CRYSTAL_AURA = new ManagedModule("crystal-aura", Resource.CRYSTALS, 1, false, false);
    public static final ManagedModule AUTO_TRAP = new ManagedModule("auto-trap", Resource.OBSIDIAN, 8, true, false);
    public static final ManagedModule AUTO_WEB = new ManagedModule("auto-web", Resource.WEBS, 1, false, false);
    public static final ManagedModule AUTO_ANVIL = new ManagedModule("auto-anvil", Resource.ANVILS, 1, true, false);
    public static final ManagedModule AUTO_CITY = new ManagedModule("auto-city", Resource.PICKAXE, 1, true, false);

    /**
     * {@code surround} has had {@code turnsItselfOff == false} since critical C2, and that is what
     * gives you back the power to turn it off by hand.
     *
     * <p>With the flag at {@code true} the debounce of §8 did not apply to it: the ledger released
     * ownership and took it back on the second pass of the same tick, so every time you turned it off
     * it came back within the same tick and the only way out was to turn off the whole {@code auto-pvp}.
     *
     * <p>The flag only holds together with the condition the posture added at the same time (§5): not
     * asking for {@code surround} while your Y is changing. Checked against {@code Surround.java}
     * of {@code meteor-client:1.21.11-SNAPSHOT}, its three self-shutdowns are
     * {@code toggle-on-y-change} ({@code defaultValue(true)}), {@code toggle-on-complete}
     * ({@code defaultValue(false)}) and {@code toggle-on-death} ({@code defaultValue(true)}). With the
     * default settings only the first remains, and it fires in {@code TickEvent.Pre} with
     * {@code prevY != getY()}, that is <b>one tick after</b> the movement: that is why the posture's
     * {@code selfOnGround && selfInHole} was not enough -when you land in the hole you are already
     * on the ground and inside, the posture asked for it, and the module turned itself off on the next
     * tick-. With {@code selfYChanged} in front, that tick is excluded and an observed shutdown can only
     * be yours. {@code toggle-on-death} does not count: dying turns off {@code auto-pvp} and releases everything.
     */
    public static final ManagedModule SURROUND = new ManagedModule("surround", Resource.OBSIDIAN, 4, false, false);

    // The four of the defensive axis (redesign §5). None of them immobilizes you: they cover specific
    // ways of killing you. hole-filler places obsidian, and one is enough for it: filling a single hole
    // is already of some use, unlike auto-trap, which needs the whole trap. The three anti- ones also
    // place one block each when their threat shows up -checked in the meteor-client 1.21.11 sources,
    // all three with InvUtils.findInHotbar-: anti-anvil obsidian between you and the anvil, anti-bed
    // string where the bed would go and anti-anchor any slab over your head. One is enough for each.
    // Without it they place nothing, so the resource filter treats them like any other placer.
    //
    // The three are reactive: they spend only when their threat appears, so a still stack says nothing
    // about them and ActionWatch leaves them out. hole-filler is not: it fills holes near the target
    // whenever there is one.
    //
    // All four have turnsItselfOff == false: they are passive, without a "done, I turn off" like
    // auto-trap's after placing the trap nor a toggle-on-* like surround's. Since §8 the flag
    // matters little: the ModuleLedger debounce already absorbs a single shutdown, so a
    // flicker that was not the player's would not count them as released either.
    public static final ManagedModule HOLE_FILLER = new ManagedModule("hole-filler", Resource.OBSIDIAN, 1, false, false);
    public static final ManagedModule ANTI_ANVIL = new ManagedModule("anti-anvil", Resource.OBSIDIAN, 1, false, true);
    public static final ManagedModule ANTI_BED = new ManagedModule("anti-bed", Resource.STRING, 1, false, true);
    public static final ManagedModule ANTI_ANCHOR = new ManagedModule("anti-anchor", Resource.SLABS, 1, false, true);

    public static final List<ManagedModule> ALL =
        List.of(CRYSTAL_AURA, AUTO_TRAP, AUTO_WEB, SURROUND, AUTO_ANVIL, AUTO_CITY,
            HOLE_FILLER, ANTI_ANVIL, ANTI_BED, ANTI_ANCHOR);

    /**
     * Those of the defensive axis (§5): the five the <b>posture</b> asks for, not the phase. It lets
     * each of the system's two memories be indexed by its own axis (importants I4 and I6): the
     * director's resource memory, which freezes while the phase is {@code NO_COMBAT} even though the
     * defensive axis does decide there, and the {@link ModuleLedger}'s memory of what was released by
     * hand, which was forgotten when the offensive phase changed even though what was released was
     * defensive and you had not changed anything.
     */
    public static final List<ManagedModule> DEFENSIVE =
        List.of(HOLE_FILLER, SURROUND, ANTI_ANVIL, ANTI_BED, ANTI_ANCHOR);

    /**
     * The order in which the modules share out a shared resource (important I3).
     *
     * <p>Four placers live off the same obsidian stack -{@code hole-filler} (1), {@code surround} (4),
     * {@code anti-anvil} (1) and {@code auto-trap} (8)- and the resource filter compared them one by one
     * against the total. In a hole, threatened and with the enemy on top of you, all four come up at
     * once: with eight obsidian they ask for fourteen between them, all four swap to the same stack on
     * the same tick and <b>none completes its job</b>, without {@code skipped} saying anything.
     *
     * <p>The order is <b>defensive before offensive and cheap before expensive</b>, and the two rules
     * agree. §10 rules -lean towards staying alive-: with eight obsidian, filling the hole through
     * which you are going to be crystalled (1) and locking in your feet (4) keep you alive and there
     * are still three to spare; spending them on the other one's trap (8) leaves you without both
     * things. And ordering by price is what gets the most complete jobs out of the same stack.
     * {@code anti-anvil} goes after {@code surround} and before {@code auto-trap}: it is defensive and
     * needs one of those three, but it only spends when an anvil is falling on you, while the hole and your feet are
     * how you get crystalled every cycle.
     *
     * <p>Those not listed here share with nobody -each one is the only consumer of its resource:
     * {@code anti-bed} the string, {@code anti-anchor} the slabs- so the order does not affect them.
     */
    public static final List<ManagedModule> SHARED_RESOURCE_PRIORITY =
        List.of(HOLE_FILLER, SURROUND, ANTI_ANVIL, AUTO_TRAP);

    /** Whether the module belongs to the defensive axis (§5), that is whether the posture asks for it and not the phase. */
    public static boolean isDefensive(ManagedModule module) {
        return DEFENSIVE.contains(module);
    }

    /** The same by name, for the {@link ModuleLedger}, which only handles names. */
    public static boolean isDefensive(String name) {
        for (ManagedModule module : DEFENSIVE) {
            if (module.name().equals(name)) return true;
        }
        return false;
    }

    private ManagedModules() {}
}
