package com.xploits.pvp.core;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FriendLedgerTest {
    private static final Set<String> NO_FRIENDS = Set.of();
    private static final boolean DISTRUST_UNKNOWN = false;
    private static final boolean TRUST_UNKNOWN = true;

    @Test
    void aNewNameIsAddedAndRecordedAsOurs() {
        FriendLedger ledger = new FriendLedger();

        FriendLedger.Result result = ledger.reconcile(Set.of("StormAegis44"), NO_FRIENDS);

        assertEquals(List.of("StormAegis44"), result.toAdd());
        assertTrue(result.toRemove().isEmpty());
        assertEquals(Set.of("StormAegis44"), ledger.added());
    }

    /** The invariant that rules over all: what the player already had is never touched. */
    @Test
    void aFriendThePlayerAlreadyHadIsNeitherAddedNorRemoved() {
        FriendLedger ledger = new FriendLedger();

        FriendLedger.Result added = ledger.reconcile(Set.of("StormAegis44"), Set.of("StormAegis44"));
        assertTrue(added.toAdd().isEmpty(), "it is already there: there is nothing to add");
        assertTrue(ledger.added().isEmpty(), "and above all, it is not ours");

        FriendLedger.Result released = ledger.release(Set.of("StormAegis44"));
        assertTrue(released.toRemove().isEmpty(), "we did not put it there: we do not take it out");
    }

    @Test
    void releasingRemovesOursAndOnlyOurs() {
        FriendLedger ledger = new FriendLedger();
        ledger.reconcile(Set.of("StormAegis44"), Set.of("Dryzzical"));

        FriendLedger.Result released = ledger.release(Set.of("Dryzzical", "StormAegis44"));

        assertEquals(List.of("StormAegis44"), released.toRemove());
        assertTrue(ledger.added().isEmpty());
    }

    /**
     * Same rule as {@code BorrowedModule}: if the player moves it by hand after us, their
     * decision is more recent than our record and wins.
     */
    @Test
    void ifThePlayerRemovesOneWeAddedItStopsBeingOursAndIsNotReAdded() {
        FriendLedger ledger = new FriendLedger();
        ledger.reconcile(Set.of("StormAegis44"), NO_FRIENDS);

        // The player removes it from their list; the source lists still ask for it.
        FriendLedger.Result after = ledger.reconcile(Set.of("StormAegis44"), NO_FRIENDS);
        assertTrue(after.toAdd().isEmpty(), "what the player has just decided is not argued with");
        assertTrue(after.toRemove().isEmpty());
        assertTrue(ledger.added().isEmpty());

        // And it is still not added in the following reconciliations of this same activation.
        assertTrue(ledger.reconcile(Set.of("StormAegis44"), NO_FRIENDS).toAdd().isEmpty());
    }

    @Test
    void aNameLeavingTheSourceListIsRemovedFromFriends() {
        FriendLedger ledger = new FriendLedger();
        ledger.reconcile(Set.of("StormAegis44", "Dryzzical"), NO_FRIENDS);

        FriendLedger.Result result =
            ledger.reconcile(Set.of("Dryzzical"), Set.of("StormAegis44", "Dryzzical"));

        assertEquals(List.of("StormAegis44"), result.toRemove());
        assertEquals(Set.of("Dryzzical"), ledger.added());
    }

    /**
     * Friends.add rejects the name if get(name) != null, and get compares with equalsIgnoreCase: asking
     * for the addition would be asking for something Meteor is going to reject, and recording it would
     * record the player's friend as ours.
     */
    @Test
    void presenceIsCheckedCaseInsensitively() {
        FriendLedger ledger = new FriendLedger();

        FriendLedger.Result result = ledger.reconcile(Set.of("StormAegis44"), Set.of("stormaegis44"));

        assertTrue(result.toAdd().isEmpty());
        assertTrue(ledger.added().isEmpty());
    }

    /**
     * And ownership goes the other way: it requires the exact name. If what is there is no longer
     * written the way we wrote it, we cannot prove it is ours, and when in doubt it is released without removing anything.
     */
    @Test
    void ownershipRequiresTheExactName() {
        FriendLedger ledger = new FriendLedger();
        ledger.reconcile(Set.of("StormAegis44"), NO_FRIENDS);

        FriendLedger.Result result = ledger.release(Set.of("stormaegis44"));

        assertTrue(result.toRemove().isEmpty(), "it is not provably ours: it is not removed");
        assertTrue(ledger.added().isEmpty());
    }

    @Test
    void reconcilingTwiceWithoutChangesDoesNothing() {
        FriendLedger ledger = new FriendLedger();
        ledger.reconcile(Set.of("StormAegis44"), NO_FRIENDS);

        FriendLedger.Result second = ledger.reconcile(Set.of("StormAegis44"), Set.of("StormAegis44"));

        assertTrue(second.isEmpty(), "without changes nothing is written, and every write goes to disk");
        assertEquals(Set.of("StormAegis44"), ledger.added());
    }

    @Test
    void anAddRejectedByMeteorStopsBeingOursAndIsNotRetried() {
        FriendLedger ledger = new FriendLedger();
        ledger.reconcile(Set.of("StormAegis44"), NO_FRIENDS);

        ledger.disown("StormAegis44");
        assertTrue(ledger.added().isEmpty());

        FriendLedger.Result result = ledger.reconcile(Set.of("StormAegis44"), NO_FRIENDS);
        assertTrue(result.isEmpty(), "it is neither retried nor, above all, removed");
    }

    @Test
    void resetForgetsWhatWasAddedAndWhatWasAbandoned() {
        FriendLedger ledger = new FriendLedger();
        ledger.reconcile(Set.of("StormAegis44"), NO_FRIENDS);
        ledger.reconcile(Set.of("StormAegis44"), NO_FRIENDS); // the player removed it: it is abandoned

        ledger.reset();

        assertTrue(ledger.added().isEmpty());
        assertEquals(List.of("StormAegis44"), ledger.reconcile(Set.of("StormAegis44"), NO_FRIENDS).toAdd(),
            "a new activation starts from zero again");
    }

    @Test
    void releasingWithoutHavingAddedAnythingRemovesNothing() {
        FriendLedger ledger = new FriendLedger();

        assertTrue(ledger.release(Set.of("StormAegis44", "Dryzzical")).isEmpty());
    }

    @Test
    void reconcilingWithNullsDoesNotCrash() {
        FriendLedger ledger = new FriendLedger();

        assertTrue(ledger.reconcile(null, null).isEmpty());
        assertEquals(List.of("StormAegis44"), ledger.reconcile(Set.of("StormAegis44"), null).toAdd());
    }

    // --- syncable: what is synced and what is not ------------------------------------------------

    @Test
    void syncableMergesBothListsAndTrimsSpaces() {
        Set<String> result = FriendLedger.syncable(
            Set.of(" StormAegis44 "), DISTRUST_UNKNOWN, Set.of("Dryzzical\t"));

        assertEquals(Set.of("StormAegis44", "Dryzzical"), result);
    }

    /**
     * The one-chat-line attack path (spec §14.2): with trust-unknown-couriers on,
     * anyone who imitates a READY gets into known-couriers on their own. Nothing from there goes to the
     * Meteor friends list, which is global and persists to disk.
     */
    @Test
    void syncableSyncsNoCourierWithTrustUnknownCouriers() {
        Set<String> result = FriendLedger.syncable(
            Set.of("StormAegis44", "Mallory"), TRUST_UNKNOWN, Set.of("Dryzzical"));

        assertEquals(Set.of("Dryzzical"), result, "the users list is only written by hand: that one is synced");
    }

    @Test
    void syncableDropsWhatMeteorCouldNotSave() {
        Set<String> result = FriendLedger.syncable(
            Arrays.asList(null, "", "   ", "Storm Aegis", "Storm\tAegis", "StormAegis44"),
            DISTRUST_UNKNOWN, Set.of());

        assertEquals(Set.of("StormAegis44"), result);
    }

    @Test
    void syncableTreatsNullListsAsEmpty() {
        assertTrue(FriendLedger.syncable(null, DISTRUST_UNKNOWN, null).isEmpty());
        assertEquals(Set.of("Dryzzical"), FriendLedger.syncable(null, DISTRUST_UNKNOWN, Set.of("Dryzzical")));
    }

    @Test
    void aNameInBothListsIsSyncedOnlyOnce() {
        FriendLedger ledger = new FriendLedger();
        Set<String> wanted = FriendLedger.syncable(Set.of("Dryzzical"), DISTRUST_UNKNOWN, Set.of("Dryzzical"));

        assertEquals(List.of("Dryzzical"), ledger.reconcile(wanted, NO_FRIENDS).toAdd());
    }

    @Test
    void turningSyncOffReleasesWhatWasAddedWithoutTouchingAnythingElse() {
        FriendLedger ledger = new FriendLedger();
        ledger.reconcile(Set.of("StormAegis44"), Set.of("Dryzzical"));

        // The adapter passes the empty set as soon as sync-friends is turned off.
        FriendLedger.Result result = ledger.reconcile(Set.of(), Set.of("Dryzzical", "StormAegis44"));

        assertEquals(List.of("StormAegis44"), result.toRemove());
        assertFalse(result.toRemove().contains("Dryzzical"));
    }
}
