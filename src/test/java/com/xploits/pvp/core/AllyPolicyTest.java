package com.xploits.pvp.core;

import com.xploits.pvp.core.AllyPolicy.Allegiance;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllyPolicyTest {
    private static final Set<String> NO_COURIERS = Set.of();
    private static final Set<String> NO_USERS = Set.of();

    @Test
    void aStrangerIsATarget() {
        assertEquals(Allegiance.STRANGER,
            AllyPolicy.of("Mallory", false, Set.of("StormAegis44"), Set.of("xto2002")));
        assertFalse(AllyPolicy.of("Mallory", false, Set.of("StormAegis44"), Set.of("xto2002")).isOurs());
    }

    @Test
    void theKitRequesterCourierIsNotAttacked() {
        Allegiance allegiance = AllyPolicy.of("StormAegis44", false, Set.of("StormAegis44"), NO_USERS);
        assertEquals(Allegiance.COURIER, allegiance);
        assertTrue(allegiance.isOurs());
        assertEquals(PvpText.ALLY_COURIER, allegiance.reason());
    }

    @Test
    void autoTpysUsersListIsNotAttacked() {
        Allegiance allegiance = AllyPolicy.of("Dryzzical", false, NO_COURIERS, Set.of("Dryzzical"));
        assertEquals(Allegiance.TPY_USER, allegiance);
        assertTrue(allegiance.isOurs());
        assertEquals(PvpText.ALLY_TPY_USER, allegiance.reason());
    }

    @Test
    void aMeteorFriendIsNotAttacked() {
        Allegiance allegiance = AllyPolicy.of("Dryzzical", true, NO_COURIERS, NO_USERS);
        assertEquals(Allegiance.FRIEND, allegiance);
        assertTrue(allegiance.isOurs());
        assertEquals(PvpText.ALLY_FRIEND, allegiance.reason());
    }

    /** The treatment is unconditional: whatever the phase or the provocation, they are still ours. */
    @Test
    void aStrangersReasonIsEmpty() {
        assertEquals(PvpText.NOTHING, Allegiance.STRANGER.reason());
        assertFalse(Allegiance.STRANGER.isOurs());
    }

    @Test
    void friendTakesPrecedenceOverTheOtherLists() {
        assertEquals(Allegiance.FRIEND,
            AllyPolicy.of("StormAegis44", true, Set.of("StormAegis44"), Set.of("StormAegis44")));
    }

    @Test
    void namesAreCaseSensitive() {
        assertEquals(Allegiance.STRANGER,
            AllyPolicy.of("stormaegis44", false, Set.of("StormAegis44"), NO_USERS));
        assertEquals(Allegiance.STRANGER,
            AllyPolicy.of("DRYZZICAL", false, NO_COURIERS, Set.of("Dryzzical")));
    }

    @Test
    void extraSpacesInTheListDoNotDropProtection() {
        assertEquals(Allegiance.COURIER,
            AllyPolicy.of("StormAegis44", false, AllyPolicy.names(List.of("  StormAegis44 ")), NO_USERS));
        assertEquals(Allegiance.TPY_USER,
            AllyPolicy.of("Dryzzical", false, NO_COURIERS, AllyPolicy.names(List.of("Dryzzical\t"))));
    }

    @Test
    void blankEntriesMatchNobody() {
        assertEquals(Allegiance.STRANGER, AllyPolicy.of("Mallory", false,
            AllyPolicy.names(List.of("", "   ")), AllyPolicy.names(List.of("\t"))));
    }

    @Test
    void anEmptyOrNullNameIsATargetAndDoesNotCrash() {
        assertEquals(Allegiance.STRANGER, AllyPolicy.of(null, false, Set.of(""), Set.of("")));
        assertEquals(Allegiance.STRANGER, AllyPolicy.of("", false, Set.of(""), Set.of("")));
        assertEquals(Allegiance.STRANGER, AllyPolicy.of("   ", false, Set.of("   "), Set.of("   ")));
    }

    @Test
    void nullListsAreTreatedAsEmpty() {
        assertEquals(Allegiance.STRANGER, AllyPolicy.of("StormAegis44", false, null, null));
        assertEquals(Allegiance.FRIEND, AllyPolicy.of("Dryzzical", true, null, null));
    }

    // --- names(): trimming is done once per list and per tick, not once per player looked at ---

    @Test
    void namesTrimsSpacesAndDropsBlankEntries() {
        assertEquals(Set.of("StormAegis44", "Dryzzical"),
            AllyPolicy.names(List.of("  StormAegis44 ", "Dryzzical\t", "", "   ")));
    }

    @Test
    void namesHandlesNullEntriesAndANullListWithoutFailing() {
        assertEquals(Set.of("StormAegis44"), AllyPolicy.names(Arrays.asList(null, "StormAegis44")));
        assertTrue(AllyPolicy.names(null).isEmpty());
    }

    @Test
    void namesDoesNotMergeNamesThatOnlyMatchInLowercase() {
        assertEquals(Set.of("StormAegis44", "stormaegis44"),
            AllyPolicy.names(List.of("StormAegis44", " stormaegis44")));
    }

    /** The player's name is trimmed too: what decides is who they are, not how it arrived written. */
    @Test
    void thePlayersNameIsTrimmedToo() {
        assertEquals(Allegiance.COURIER,
            AllyPolicy.of(" StormAegis44 ", false, Set.of("StormAegis44"), NO_USERS));
    }
}
