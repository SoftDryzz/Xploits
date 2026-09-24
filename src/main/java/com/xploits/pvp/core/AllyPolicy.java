package com.xploits.pvp.core;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Is this player one of ours? (spec §13). A pure decision: the adapter only collects the names
 * from the other modules and asks.
 *
 * <p>"Ours" are the Meteor friends, the {@code kit-requester} couriers and the {@code users} list of
 * {@code auto-tpy}. The treatment is the same Meteor gives its friends list:
 * <b>unconditional</b>. It is not "do not start it but answer if they hit you": ours are never
 * attacked, even if they hit you.
 *
 * <p><b>This decides whom auto-pvp picks, and nothing else.</b> auto-pvp does not attack: it enables
 * modules, and the five it manages pick their own target with the only social filter they know, the
 * Meteor friends list. That the couriers and the users list are also respected there is the job of
 * {@link FriendLedger} (spec §14); without that sync, this decision stays in the director's target and
 * the courier ends up webbed by the module the director just enabled.
 *
 * <p>The {@code FRIEND} case is exactly the negation of {@code Friends.get().shouldAttack()}, the
 * only social filter {@code TargetUtils.getPlayerTarget} applied: by absorbing it here, the selection
 * predicate keeps discarding friends as before, and the reason shown to the player comes from a
 * single place for the three sets.
 */
public final class AllyPolicy {
    /** Whose side the player looked at is on, and why they are not attacked. */
    public enum Allegiance {
        /** Not in any of the three lists: a valid target. */
        STRANGER(null),
        /** Meteor friend (.friends add). */
        FRIEND(PvpText.ALLY_FRIEND),
        /** Courier from kit-requester's known-couriers list. */
        COURIER(PvpText.ALLY_COURIER),
        /** Name from auto-tpy's users list. */
        TPY_USER(PvpText.ALLY_TPY_USER);

        private final PvpText reason;

        Allegiance(PvpText reason) {
            this.reason = reason;
        }

        /** true if they are one of ours and auto-pvp must never attack them. */
        public boolean isOurs() {
            return this != STRANGER;
        }

        /** Why they are not attacked, for the warning and for the status. Empty for {@link #STRANGER}. */
        public PvpText reason() {
            return reason == null ? PvpText.NOTHING : reason;
        }
    }

    private AllyPolicy() {}

    /**
     * A source list ready to be asked: trimmed, without blank entries and without nulls.
     *
     * <p>The trimming is done <b>once per list and per tick</b>, here, and not once per player
     * looked at inside {@link #of}. The selection predicate is evaluated over every entity in the
     * world, so trimming inside came to a linear scan of the list —and a {@code trim()} per
     * entry— for every player in sight: with the default lists it makes no difference, with two
     * hundred names it does. Normalized once, {@link #of} asks by hash.
     *
     * <p>What is forgiven is the surrounding spaces: a Minecraft name cannot contain spaces —Meteor
     * refuses to add a friend whose name has them—, so " StormAegis44 " in the list cannot mean
     * anything but that player. Forgiving them can only stop attacking someone, never attack one
     * more; in {@code AutoTpyPolicy} the error would fall on the opposite side —accepting one TPA
     * too many—, and that is why nothing is forgiven there.
     */
    public static Set<String> names(Collection<String> list) {
        if (list == null) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (String entry : list) {
            if (entry == null) continue;
            String name = entry.trim();
            // A blank entry cannot match anyone: the looked-at name is rejected in of() if it
            // arrives empty, so keeping it would only take up room.
            if (!name.isEmpty()) result.add(name);
        }
        return result;
    }

    /**
     * @param name                 name of the player looked at (the profile one, as auto-pvp shows it)
     * @param meteorFriend         the adapter already checked the Meteor friends
     * @param kitRequesterCouriers kit-requester's known-couriers setting, passed through {@link #names}
     * @param autoTpyUsers         auto-tpy's users setting, passed through {@link #names}
     */
    public static Allegiance of(String name, boolean meteorFriend,
                                Set<String> kitRequesterCouriers, Set<String> autoTpyUsers) {
        if (name == null) return Allegiance.STRANGER;
        String candidate = name.trim();
        // An unreadable name protects nobody: no real player has a blank name, and taking as ours
        // someone we cannot identify would mean no longer defending against anyone who manages to
        // make the name arrive empty.
        if (candidate.isEmpty()) return Allegiance.STRANGER;

        if (meteorFriend) return Allegiance.FRIEND;
        // Exact, case-sensitive comparison, which is what the descriptions of both lists promise and
        // what AutoTpyPolicy and CourierPolicy already do with those same lists. A
        // "StormAegis44" is not a "stormaegis44".
        if (contains(kitRequesterCouriers, candidate)) return Allegiance.COURIER;
        if (contains(autoTpyUsers, candidate)) return Allegiance.TPY_USER;
        return Allegiance.STRANGER;
    }

    private static boolean contains(Set<String> list, String candidate) {
        return list != null && list.contains(candidate);
    }
}
