package com.xploits.pvp.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which names must be written to the Meteor friends list and which must be removed (spec §14), in
 * pure logic: the adapter passes it what it wants to sync and what is really in the list right now,
 * and carries out what this returns. It knows nothing about Meteor or Minecraft.
 *
 * <p><b>Why it is needed.</b> {@link AllyPolicy} only governs the target {@code
 * auto-pvp} picks, and {@code auto-pvp} does not attack: it enables modules. The five it manages pick
 * their own target —{@code crystal-aura} calls {@code Friends.get().shouldAttack()} itself, and {@code
 * auto-trap}, {@code auto-web}, {@code auto-anvil} and {@code auto-city} call {@code
 * TargetUtils.getPlayerTarget()}, whose only social filter is that same {@code shouldAttack}
 * (checked against the meteor-client 1.21.11 sources)—, so <b>the only mechanism all five respect is
 * the Meteor friends list</b>. Syncing ours to that list is what keeps a courier standing next to you
 * from ending up webbed by the module {@code auto-pvp} has just enabled.
 *
 * <p><b>And it is a list that is not ours.</b> {@code Friends} is global Meteor configuration, it
 * persists in {@code friends.nbt} and the player maintains it by hand. Hence the one invariant that
 * rules over all the others: <b>a friend the player already had is never removed</b>. This ledger
 * exists to tell them apart: only what came in through here leaves the list.
 *
 * <p>The line is the one of {@code ModuleLedger} and {@code BorrowedModule}: what was taken is
 * recorded, and <b>if the player moves it by hand afterwards, their decision is more recent than our
 * record</b> and wins. A friend we added and the player removes stops being ours and is not added
 * again while the activation lasts.
 *
 * <h2>Case: two different comparisons, on purpose</h2>
 *
 * <ul>
 *   <li><b>To decide whether an addition is needed</b>, the check is case-<b>insensitive</b>, because
 *       that is what Meteor does: {@code Friends.add} rejects the name if {@code get(name) != null},
 *       and {@code Friends.get(String)} compares with {@code equalsIgnoreCase}. With "stormaegis44"
 *       already in the list, adding "StormAegis44" returns {@code false}; asking for it would be asking
 *       for something that will not happen, and recording it as ours would record the player's friend
 *       as ours.</li>
 *   <li><b>To decide whether something is still ours</b>, the <b>exact</b> name is required. If what
 *       is in the list is no longer written the way we wrote it, we cannot prove it is ours, and when
 *       in doubt it is released: at worst one name too many stays in the player's list —annoying—,
 *       one of theirs is never removed —irreversible—. And it really happens:
 *       {@code Friend.updateInfo()} replaces the name with Mojang's canonical one, and it is called by
 *       the Friends tab of the ClickGUI and the {@code .reload} command.</li>
 * </ul>
 */
public final class FriendLedger {
    /** The names this ledger put in the friends list and that are still there, as they were written. */
    private final Set<String> added = new LinkedHashSet<>();

    /**
     * Names that are not tried again in this activation: either the player removed them by hand after
     * we added them, or Meteor refused to add them. It is the same memory as
     * {@code released} in {@code ModuleLedger}, and it is forgotten the same way, with {@link #reset()}.
     */
    private final Set<String> abandoned = new LinkedHashSet<>();

    /** What has to be done to the friends list right now. */
    public record Result(List<String> toAdd, List<String> toRemove) {
        public Result {
            toAdd = List.copyOf(toAdd);
            toRemove = List.copyOf(toRemove);
        }

        /** true if nothing has to be touched, which is the normal case and the one that does not write to disk. */
        public boolean isEmpty() {
            return toAdd.isEmpty() && toRemove.isEmpty();
        }
    }

    /**
     * The names of ours that can be synced, from the two source lists.
     *
     * <p><b>With {@code trust-unknown-couriers} on, no courier is synced</b> (spec
     * §14.2). That setting makes anyone who imitates the READY message and sends a TPA while an order
     * is pending add themselves to {@code known-couriers}; with the sync, that would stop being
     * "gets a teleport" and become "gets into your Meteor friends list and the five combat modules
     * stop touching them", persistently and on disk, with one chat line. While the setting is on the
     * whole list stops being trustworthy, and nothing from it is written to the friends list. The
     * {@code users} list of {@code auto-tpy} does not have that problem —it is only written by hand—
     * and is synced anyway.
     *
     * <p>What Meteor could not store is dropped too: {@code Friends.add} rejects the empty name and one
     * containing a space. Here any inner whitespace is rejected, not only the space: no Minecraft name
     * contains any, and asking for an addition that will return {@code false} only means having to
     * undo it.
     */
    public static Set<String> syncable(Collection<String> couriers, boolean trustUnknownCouriers,
                                       Collection<String> tpyUsers) {
        Set<String> result = new LinkedHashSet<>();
        if (!trustUnknownCouriers) collectWritable(result, couriers);
        collectWritable(result, tpyUsers);
        return result;
    }

    private static void collectWritable(Set<String> target, Collection<String> names) {
        if (names == null) return;
        for (String entry : names) {
            if (entry == null) continue;
            String name = entry.trim();
            if (name.isEmpty() || hasWhitespace(name)) continue;
            target.add(name);
        }
    }

    private static boolean hasWhitespace(String name) {
        for (int i = 0; i < name.length(); i++) {
            if (Character.isWhitespace(name.charAt(i))) return true;
        }
        return false;
    }

    /**
     * Decides what to touch in the friends list. It writes nothing: the adapter carries out the
     * {@link Result} and answers with {@link #disown(String)} if Meteor rejected an addition.
     *
     * @param wanted  the names that should be in the friends list because of us
     *                ({@link #syncable})
     * @param present the names really in the friends list right now, as Meteor
     *                stores them
     */
    public Result reconcile(Set<String> wanted, Set<String> present) {
        Set<String> exact = present == null ? Set.of() : present;
        Set<String> insensitive = lowercased(exact);
        Set<String> targets = wanted == null ? Set.of() : wanted;

        List<String> toRemove = new ArrayList<>();
        for (String name : new ArrayList<>(added)) {
            // It is no longer written the way we wrote it: the player moved it by hand, and that is
            // more recent than our record. It stops being ours -we will never remove it- and is not
            // added again in this activation, just like a module released by hand in ModuleLedger.
            if (!exact.contains(name)) {
                added.remove(name);
                abandoned.add(name);
                continue;
            }
            // It is still there and it is ours, but it is no longer in either source list: the
            // player took it out of known-couriers or users, and what came in through here leaves through here.
            if (!targets.contains(name)) {
                toRemove.add(name);
                added.remove(name);
            }
        }

        List<String> toAdd = new ArrayList<>();
        for (String name : targets) {
            if (abandoned.contains(name)) continue;
            // Already in the list: either it is ours and still there, or it is the player's. In both
            // cases there is nothing to add, and in the second nothing to record as our own either.
            if (insensitive.contains(lower(name))) continue;
            toAdd.add(name);
            added.add(name);
        }

        return new Result(toAdd, toRemove);
    }

    /**
     * What has to be removed when releasing the whole list —when the module is turned off—: everything
     * of ours that is still there, and nothing else. What the player removed by hand in the meantime is
     * no longer ours and does not appear here.
     */
    public Result release(Set<String> present) {
        return reconcile(Set.of(), present);
    }

    /**
     * The adapter reports that Meteor did <b>not</b> add this name: it stops counting as ours
     * —so it will never be removed— and is not tried again in this activation. With {@link #syncable}
     * and the presence check in front it should never happen; it is here because the consequence of
     * getting it wrong in this direction is removing one of the player's friends.
     */
    public void disown(String name) {
        added.remove(name);
        abandoned.add(name);
    }

    /** The names this ledger has in the friends list right now. */
    public Set<String> added() {
        return Set.copyOf(added);
    }

    /** Forgets what was added and what was abandoned. Called when the whole module is turned on or off. */
    public void reset() {
        added.clear();
        abandoned.clear();
    }

    private static Set<String> lowercased(Set<String> names) {
        Set<String> result = new LinkedHashSet<>();
        for (String name : names) {
            if (name != null) result.add(lower(name));
        }
        return result;
    }

    /**
     * Lowercase with {@code Locale.ROOT} to imitate the {@code equalsIgnoreCase} of
     * {@code Friends.get}. They are not the same operation across all of Unicode, but a Minecraft name
     * is ASCII, and where they differ the result is one addition too many that Meteor will reject and
     * {@link #disown(String)} picks up.
     */
    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
