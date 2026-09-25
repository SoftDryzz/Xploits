package com.xploits.pvp.profile.core;

import com.xploits.pvp.core.ManagedModule;
import com.xploits.pvp.core.ManagedModules;
import com.xploits.shared.core.i18n.Msg;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Order, {@code use}/{@code next} switching, {@code save}/{@code delete} mutations (design §2). */
class ProfileBookTest {
    private static final Set<String> ALL = ManagedModules.ALL.stream().map(ManagedModule::name).collect(Collectors.toUnmodifiableSet());

    // --- order ---

    @Test
    void defaultsHoldExactlyTheThreeBuiltInsInOrderWithBalancedActive() {
        ProfileBook book = ProfileBook.defaults();
        assertEquals(List.of("balanced", "aggressive", "defensive"),
            book.profiles().stream().map(PvpProfile::name).toList());
        assertEquals("balanced", book.activeName());
        assertEquals(BuiltInProfiles.BALANCED, book.active());
    }

    @Test
    void aSavedPlayerProfileSitsAfterTheBuiltInsSortedByName() {
        ProfileBook book = ProfileBook.defaults();
        book = applied(book.save("zeta", 16, 6, 12.0, ALL));
        book = applied(book.save("alpha", 16, 6, 12.0, ALL));

        assertEquals(List.of("balanced", "aggressive", "defensive", "alpha", "zeta"),
            book.profiles().stream().map(PvpProfile::name).toList());
    }

    // --- use / next ---

    @Test
    void useSwitchesTheActiveProfileAndSaysWhichOneItIs() {
        ProfileBook.Outcome outcome = ProfileBook.defaults().use("aggressive");
        ProfileBook.Applied applied = assertInstanceOf(ProfileBook.Applied.class, outcome);
        assertEquals("aggressive", applied.book().activeName());
        assertEquals(List.of(Msg.of(ProfileText.PROFILE_ACTIVE, "name", "aggressive")), applied.messages());
    }

    @Test
    void useOfAnUnknownNameIsRejectedWithTheKnownNames() {
        ProfileBook.Outcome outcome = ProfileBook.defaults().use("no-such-profile");
        ProfileBook.Rejected rejected = assertInstanceOf(ProfileBook.Rejected.class, outcome);
        assertEquals(ProfileText.PROFILE_UNKNOWN, rejected.reason().key());
        assertEquals("no-such-profile", rejected.reason().args().get("name"));
        assertTrue(((String) rejected.reason().args().get("list")).contains("balanced"));
    }

    @Test
    void deleteOfAnUnknownNameIsRejected() {
        assertInstanceOf(ProfileBook.Rejected.class, ProfileBook.defaults().delete("no-such-profile"));
    }

    @Test
    void nextCyclesThroughTheOrderedList() {
        ProfileBook book = ProfileBook.defaults(); // balanced
        book = book.next().book(); // aggressive
        assertEquals("aggressive", book.activeName());
        book = book.next().book(); // defensive
        assertEquals("defensive", book.activeName());
    }

    @Test
    void nextWrapsFromTheLastProfileBackToTheFirst() {
        ProfileBook book = applied(ProfileBook.defaults().use("defensive"));
        ProfileBook.Applied applied = book.next();
        assertEquals("balanced", applied.book().activeName());
        assertEquals(List.of(Msg.of(ProfileText.PROFILE_ACTIVE, "name", "balanced")), applied.messages());
    }

    // --- save ---

    @Test
    void saveOfANewNameAddsAPlayerProfileWithTheGivenValues() {
        ProfileBook book = applied(ProfileBook.defaults().save("my-style", 20, 3, 10.0, Set.of(ManagedModules.CRYSTAL_AURA.name())));
        PvpProfile saved = book.profiles().stream().filter(p -> p.name().equals("my-style")).findFirst().orElseThrow();
        assertEquals(20, saved.targetRange());
        assertEquals(3, saved.approachDistance());
        assertEquals(10.0, saved.threatMargin());
        assertEquals(Set.of(ManagedModules.CRYSTAL_AURA.name()), saved.allowed());
    }

    @Test
    void saveOnlyReportsSavedWhenNothingElseIsWrong() {
        ProfileBook.Outcome outcome = ProfileBook.defaults().save("my-style", 16, 6, 12.0, ALL);
        ProfileBook.Applied applied = assertInstanceOf(ProfileBook.Applied.class, outcome);
        assertEquals(List.of(Msg.of(ProfileText.PROFILE_SAVED, "name", "my-style")), applied.messages());
    }

    @Test
    void saveOfAnEmptyAllowedSetAlsoWarnsNothingIsAllowed() {
        ProfileBook.Applied applied = assertInstanceOf(ProfileBook.Applied.class,
            ProfileBook.defaults().save("watch-only", 16, 6, 12.0, Set.of()));
        assertTrue(applied.messages().contains(Msg.of(ProfileText.PROFILE_NOTHING_ALLOWED, "name", "watch-only")));
    }

    @Test
    void saveWithoutCrystalAuraAlsoWarnsAboutTheAutobreak() {
        Set<String> withoutAura = ALL.stream().filter(m -> !m.equals(ManagedModules.CRYSTAL_AURA.name())).collect(Collectors.toSet());
        ProfileBook.Applied applied = assertInstanceOf(ProfileBook.Applied.class,
            ProfileBook.defaults().save("no-aura", 16, 6, 12.0, withoutAura));
        assertTrue(applied.messages().contains(Msg.of(ProfileText.PROFILE_NO_AUTOBREAK, "name", "no-aura")));
    }

    @Test
    void saveOverAKnownNameOverwritesInPlaceInsteadOfAddingASecondEntry() {
        ProfileBook book = applied(ProfileBook.defaults().save("balanced", 30, 5, 20.0, Set.of()));
        assertEquals(List.of("balanced", "aggressive", "defensive"),
            book.profiles().stream().map(PvpProfile::name).toList());
        assertEquals(30, book.active().targetRange());
    }

    @Test
    void saveWithABadNameIsRejectedWithoutChangingTheBook() {
        ProfileBook.Outcome outcome = ProfileBook.defaults().save("Not Valid!", 16, 6, 12.0, ALL);
        ProfileBook.Rejected rejected = assertInstanceOf(ProfileBook.Rejected.class, outcome);
        assertEquals(ProfileText.PROFILE_BAD_NAME, rejected.reason().key());
    }

    @Test
    void savingBeyondTheLimitOfNewPlayerProfilesIsRejected() {
        ProfileBook book = ProfileBook.defaults();
        for (int i = 0; i < ProfileBook.PROFILE_LIMIT; i++) {
            book = applied(book.save("p" + i, 16, 6, 12.0, ALL));
        }
        assertEquals(ProfileBook.PROFILE_LIMIT, book.profiles().size() - BuiltInProfiles.ALL.size());

        ProfileBook.Outcome outcome = book.save("one-too-many", 16, 6, 12.0, ALL);
        ProfileBook.Rejected rejected = assertInstanceOf(ProfileBook.Rejected.class, outcome);
        assertEquals(ProfileText.PROFILE_LIMIT_REACHED, rejected.reason().key());
    }

    @Test
    void overwritingAnExistingPlayerProfileAtTheLimitIsNotRejected() {
        ProfileBook book = ProfileBook.defaults();
        for (int i = 0; i < ProfileBook.PROFILE_LIMIT; i++) {
            book = applied(book.save("p" + i, 16, 6, 12.0, ALL));
        }
        assertInstanceOf(ProfileBook.Applied.class, book.save("p0", 20, 6, 12.0, ALL));
    }

    @Test
    void anOutOfRangeValueStillThrowsBecauseTheSettingsAlwaysClampIt() {
        ProfileBook book = ProfileBook.defaults();
        assertThrows(IllegalArgumentException.class, () -> book.save("bad-range", 3, 6, 12.0, ALL));
    }

    // --- delete: built-in resets, player profile is removed ---

    @Test
    void deletingABuiltInResetsItToFactoryValuesInsteadOfRemovingIt() {
        ProfileBook book = applied(ProfileBook.defaults().save("balanced", 30, 5, 20.0, Set.of()));
        assertEquals(30, book.profiles().stream().filter(p -> p.name().equals("balanced")).findFirst().orElseThrow().targetRange());

        ProfileBook.Outcome outcome = book.delete("balanced");
        ProfileBook.Applied applied = assertInstanceOf(ProfileBook.Applied.class, outcome);
        assertEquals(3, applied.book().profiles().size());
        assertEquals(BuiltInProfiles.BALANCED, applied.book().profiles().stream().filter(p -> p.name().equals("balanced")).findFirst().orElseThrow());
        assertEquals(List.of(Msg.of(ProfileText.PROFILE_RESET, "name", "balanced")), applied.messages());
    }

    @Test
    void deletingAPlayerProfileRemovesItAndReportsDeleted() {
        ProfileBook book = applied(ProfileBook.defaults().save("my-style", 16, 6, 12.0, ALL));
        assertEquals(4, book.profiles().size());

        ProfileBook.Outcome outcome = book.delete("my-style");
        ProfileBook.Applied applied = assertInstanceOf(ProfileBook.Applied.class, outcome);
        assertEquals(3, applied.book().profiles().size());
        assertEquals(List.of(Msg.of(ProfileText.PROFILE_DELETED, "name", "my-style")), applied.messages());
    }

    @Test
    void deletingTheActivePlayerProfileFallsBackToBalanced() {
        ProfileBook book = applied(ProfileBook.defaults().save("my-style", 16, 6, 12.0, ALL));
        book = applied(book.use("my-style"));
        assertEquals("my-style", book.activeName());

        ProfileBook after = applied(book.delete("my-style"));
        assertEquals("balanced", after.activeName());
    }

    // --- modified: delegates to the active profile ---

    @Test
    void modifiedComparesAgainstTheActiveProfile() {
        ProfileBook book = ProfileBook.defaults(); // active: balanced (16, 6, 12.0, all)
        assertFalse(book.modified(16, 6, 12.0, ALL));
        assertTrue(book.modified(17, 6, 12.0, ALL));
    }

    // --- of(): the same structural validation save/delete rely on ---

    @Test
    void ofRejectsAFileMissingABuiltIn() {
        List<PvpProfile> withoutDefensive = List.of(BuiltInProfiles.BALANCED, BuiltInProfiles.AGGRESSIVE);
        assertThrows(IllegalArgumentException.class, () -> ProfileBook.of(withoutDefensive, "balanced"));
    }

    @Test
    void ofRejectsAnActiveNameNotInTheList() {
        assertThrows(IllegalArgumentException.class, () -> ProfileBook.of(BuiltInProfiles.ALL, "ghost"));
    }

    @Test
    void ofRejectsADuplicateName() {
        List<PvpProfile> duplicated = List.of(BuiltInProfiles.BALANCED, BuiltInProfiles.BALANCED, BuiltInProfiles.AGGRESSIVE, BuiltInProfiles.DEFENSIVE);
        assertThrows(IllegalArgumentException.class, () -> ProfileBook.of(duplicated, "balanced"));
    }

    private static ProfileBook applied(ProfileBook.Outcome outcome) {
        return assertInstanceOf(ProfileBook.Applied.class, outcome).book();
    }
}
