package com.xploits.pvp.profile.core;

import com.xploits.pvp.core.ManagedModules;
import com.xploits.shared.core.PositionedMsg;
import com.xploits.shared.core.i18n.Msg;
import com.xploits.testing.TempFolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** use/next persist the active name; a locked file keeps it in memory and warns once (precise rules). */
class ProfileSessionTest {
    private Path dir;
    private Path file;

    @BeforeEach
    void createTempFolder() throws IOException {
        dir = TempFolder.create();
        file = dir.resolve("profiles.json");
    }

    @AfterEach
    void deleteTempFolder() {
        TempFolder.delete(dir);
    }

    private static List<Object> keys(List<PositionedMsg> warnings) {
        return warnings.stream().map(w -> (Object) w.chat().key()).toList();
    }

    @Test
    void usePersistsTheActiveNameAndAsksToApplyIt() {
        ProfileSession session = new ProfileSession(new ProfileStore(file));
        ProfileSession.Outcome outcome = session.use("aggressive");

        assertEquals(BuiltInProfiles.AGGRESSIVE, outcome.apply().orElseThrow());
        assertEquals(List.of(Msg.of(ProfileText.PROFILE_ACTIVE, "name", "aggressive")), outcome.infos());
        assertTrue(outcome.warnings().isEmpty());
        assertEquals("aggressive", new ProfileStore(file).load().book().activeName());
    }

    @Test
    void nextPersistsTheActiveName() {
        ProfileSession session = new ProfileSession(new ProfileStore(file));
        session.next();
        assertEquals("aggressive", new ProfileStore(file).load().book().activeName());
    }

    @Test
    void unknownNameChangesNothing() {
        ProfileSession session = new ProfileSession(new ProfileStore(file));
        ProfileSession.Outcome outcome = session.use("nope");
        assertTrue(outcome.apply().isEmpty());
        assertFalse(outcome.ok());
        assertEquals(List.of(ProfileText.PROFILE_UNKNOWN), keys(outcome.warnings()));
        assertEquals("balanced", session.book().activeName());
        assertFalse(Files.exists(file));
    }

    @Test
    void aLockedFileSwitchesInMemoryAndWarnsOncePerSession() throws IOException {
        Files.writeString(file, "{ not json");
        ProfileSession session = new ProfileSession(new ProfileStore(file));
        PositionedMsg load = session.takeLoadWarning().orElseThrow();
        assertEquals(ProfileText.PROFILE_FILE_CORRUPT, load.chat().key());
        assertEquals(Msg.of(ProfileText.PROFILE_FILE_CORRUPT_LOG), load.log());
        assertTrue(session.takeLoadWarning().isEmpty());

        ProfileSession.Outcome first = session.use("defensive");
        assertEquals(BuiltInProfiles.DEFENSIVE, first.apply().orElseThrow());
        assertEquals(List.of(ProfileText.PROFILE_FILE_LOCKED), keys(first.warnings()));
        assertEquals("defensive", session.book().activeName());

        ProfileSession.Outcome second = session.next();
        assertTrue(second.apply().isPresent());
        assertTrue(second.warnings().isEmpty());
        assertEquals("{ not json", Files.readString(file));
    }

    @Test
    void saveWhileLockedIsRefusedAndChangesNothing() throws IOException {
        Files.writeString(file, "{ not json");
        ProfileSession session = new ProfileSession(new ProfileStore(file));
        ProfileSession.Outcome outcome = session.save("mine", 16, 6, 12.0, Set.of());
        assertFalse(outcome.ok());
        assertEquals(List.of(ProfileText.PROFILE_FILE_LOCKED), keys(outcome.warnings()));
        assertEquals(3, session.book().profiles().size());
    }

    @Test
    void saveSplitsInfoFromWarnings() {
        ProfileSession session = new ProfileSession(new ProfileStore(file));
        ProfileSession.Outcome outcome = session.save("mine", 16, 6, 12.0, Set.of());
        assertTrue(outcome.ok());
        assertEquals(List.of(Msg.of(ProfileText.PROFILE_SAVED, "name", "mine")), outcome.infos());
        assertEquals(List.of(ProfileText.PROFILE_NOTHING_ALLOWED, ProfileText.PROFILE_NO_AUTOBREAK), keys(outcome.warnings()));
        assertTrue(outcome.apply().isEmpty());
        assertEquals(4, new ProfileStore(file).load().book().profiles().size());
    }

    @Test
    void deletingTheActiveProfileAppliesBalanced() {
        ProfileSession session = new ProfileSession(new ProfileStore(file));
        session.save("mine", 20, 4, 10.0, Set.of(ManagedModules.CRYSTAL_AURA.name()));
        session.use("mine");
        ProfileSession.Outcome outcome = session.delete("mine");
        assertEquals(BuiltInProfiles.BALANCED, outcome.apply().orElseThrow());
        assertEquals("balanced", new ProfileStore(file).load().book().activeName());
    }

    /** Deleting the active built-in resets it to factory values in place (same name): those factory
     *  values must also be applied, or the player is left staring at "aggressive*" forever. */
    @Test
    void deletingTheActiveBuiltInAppliesItsFactoryValuesAndSaysProfileActiveAfterTheResetMessage() {
        ProfileSession session = new ProfileSession(new ProfileStore(file));
        session.save("aggressive", 40, 2, 20.0, Set.of(ManagedModules.CRYSTAL_AURA.name()));
        session.use("aggressive");

        ProfileSession.Outcome outcome = session.delete("aggressive");

        assertEquals(BuiltInProfiles.AGGRESSIVE, outcome.apply().orElseThrow());
        assertEquals(List.of(Msg.of(ProfileText.PROFILE_RESET, "name", "aggressive"),
            Msg.of(ProfileText.PROFILE_ACTIVE, "name", "aggressive")), outcome.infos());
        assertEquals("aggressive", session.book().activeName());
        assertEquals(BuiltInProfiles.AGGRESSIVE, session.book().active());
    }

    /** Deleting a built-in that is active but already untouched (already at factory values) is a no-op
     *  to apply: nothing actually changed, so there is nothing to reapply and no extra message. */
    @Test
    void deletingTheActiveBuiltInAlreadyAtFactoryValuesAppliesNothing() {
        ProfileSession session = new ProfileSession(new ProfileStore(file));
        ProfileSession.Outcome outcome = session.delete("balanced");

        assertTrue(outcome.apply().isEmpty());
        assertEquals(List.of(Msg.of(ProfileText.PROFILE_RESET, "name", "balanced")), outcome.infos());
        assertEquals("balanced", session.book().activeName());
    }

    @Test
    void aFailedWriteOnSaveKeepsTheBookAndGivesAPathFreeLogHalf() {
        ProfileStore failing = new ProfileStore(file, (from, to) -> {
            throw new IOException("disk full at " + to);
        });
        ProfileSession session = new ProfileSession(failing);
        ProfileSession.Outcome outcome = session.save("mine", 16, 6, 12.0, Set.of(ManagedModules.CRYSTAL_AURA.name()));
        assertFalse(outcome.ok());
        assertEquals(ProfileText.PROFILE_SAVE_FAILED, outcome.warnings().getFirst().chat().key());
        assertEquals(Msg.of(ProfileText.PROFILE_SAVE_FAILED_LOG), outcome.warnings().getFirst().log());
        assertEquals(3, session.book().profiles().size());
    }

    @Test
    void resetFileUnlocksAndWritesTheBookInMemory() throws IOException {
        Files.writeString(file, "{ not json");
        ProfileSession session = new ProfileSession(new ProfileStore(file));
        session.use("aggressive");
        ProfileSession.Outcome outcome = session.resetFile();
        assertEquals(List.of(Msg.of(ProfileText.PROFILE_FILE_RESET)), outcome.infos());
        assertEquals("aggressive", new ProfileStore(file).load().book().activeName());
        assertTrue(session.save("mine", 16, 6, 12.0, Set.of(ManagedModules.CRYSTAL_AURA.name())).ok());
    }
}
