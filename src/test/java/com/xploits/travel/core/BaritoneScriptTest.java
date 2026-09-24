package com.xploits.travel.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaritoneScriptTest {
    private static final String PREFIX = "#";

    private static BaritoneScript.FlightSettings flying() {
        return new BaritoneScript.FlightSettings(true, true, true, 1.0, "");
    }

    private static boolean any(List<String> commands, String fragment) {
        return commands.stream().anyMatch(c -> c.contains(fragment));
    }

    @Test
    void everyCommandCarriesThePrefix() {
        List<String> commands = BaritoneScript.preparation(PREFIX, flying());
        assertFalse(commands.isEmpty());
        for (String command : commands) {
            assertTrue(command.startsWith(PREFIX), "no prefix: " + command);
        }
        assertTrue(BaritoneScript.launch(PREFIX).startsWith(PREFIX));
        assertTrue(BaritoneScript.cancel(PREFIX).startsWith(PREFIX));
        assertTrue(BaritoneScript.goTo(PREFIX, new Waypoint(1, 2)).startsWith(PREFIX));
    }

    @Test
    void aDifferentPrefixIsHonoured() {
        assertTrue(BaritoneScript.launch(">").startsWith(">"));
        for (String command : BaritoneScript.preparation(">", flying())) {
            assertTrue(command.startsWith(">"), "no prefix: " + command);
        }
    }

    @Test
    void preparationTurnsOffBaritoneOwnElytraSwap() {
        assertTrue(any(BaritoneScript.preparation(PREFIX, flying()), "elytraAutoSwap false"));
    }

    @Test
    void preparationSilencesTheTermsNoticeAndDisablesPrediction() {
        List<String> commands = BaritoneScript.preparation(PREFIX, flying());
        assertTrue(any(commands, "elytraTermsAccepted true"));
        assertTrue(any(commands, "elytraPredictTerrain false"));
    }

    @Test
    void preparationCarriesTheFourRequestedSettings() {
        List<String> commands = BaritoneScript.preparation(PREFIX, flying());
        assertTrue(any(commands, "elytraAutoJump true"));
        assertTrue(any(commands, "elytraAllowEmergencyLand true"));
        assertTrue(any(commands, "elytraConserveFireworks true"));
        assertTrue(any(commands, "elytraFireworkSpeed 1"));
    }

    @Test
    void preparationCensorsCoordinatesBeforeAnyGoalIsSent() {
        List<String> commands = BaritoneScript.preparation(PREFIX, flying());
        assertEquals("#set censorCoordinates true", commands.get(0));
        assertEquals("#set censorRanCommands true", commands.get(1));
    }

    @Test
    void restorationLeavesTheCensorsOn() {
        // Baritone saves #set to disk: turning them off would undo a censor the player already had.
        List<String> restoration = BaritoneScript.restoration(PREFIX, flying());
        assertFalse(any(restoration, "censorCoordinates"));
        assertFalse(any(restoration, "censorRanCommands"));
    }

    @Test
    void anEmptySeedIsNotWritten() {
        assertFalse(any(BaritoneScript.preparation(PREFIX, flying()), "elytraNetherSeed"));
    }

    @Test
    void aSeedThatIsGivenIsWritten() {
        BaritoneScript.FlightSettings withSeed =
            new BaritoneScript.FlightSettings(true, true, true, 1.0, "12345");
        assertTrue(any(BaritoneScript.preparation(PREFIX, withSeed), "elytraNetherSeed 12345"));
    }

    @Test
    void restorationPutsBackEverythingPreparationTouched() {
        BaritoneScript.FlightSettings resting =
            new BaritoneScript.FlightSettings(false, false, false, 2.0, "");
        List<String> restoration = BaritoneScript.restoration(PREFIX, resting);

        assertTrue(any(restoration, "elytraAutoSwap true"), "Baritone's own swap has to be given back");
        assertTrue(any(restoration, "elytraAutoJump false"));
        assertTrue(any(restoration, "elytraAllowEmergencyLand false"));
        assertTrue(any(restoration, "elytraConserveFireworks false"));
        assertTrue(any(restoration, "elytraFireworkSpeed 2"));
    }

    @Test
    void preparationSetsElytraAutoSwapBeforeThePlayersOwnSettings() {
        // Spec §10, by name: elytraAutoSwap false has to go before the player's settings, so that
        // Baritone never gets to touch the elytra with its own swap on.
        List<String> commands = BaritoneScript.preparation(PREFIX, flying());

        int autoSwapIndex = indexOfContaining(commands, "elytraAutoSwap false");
        int autoJumpIndex = indexOfContaining(commands, "elytraAutoJump");

        assertTrue(autoSwapIndex >= 0, "elytraAutoSwap false is missing");
        assertTrue(autoJumpIndex >= 0, "the player's setting is missing");
        assertTrue(autoSwapIndex < autoJumpIndex,
            "elytraAutoSwap false must go before the player's settings (spec §10)");
    }

    private static int indexOfContaining(List<String> commands, String fragment) {
        for (int i = 0; i < commands.size(); i++) {
            if (commands.get(i).contains(fragment)) return i;
        }
        return -1;
    }

    @Test
    void theGoalCarriesTheCoordinatesRounded() {
        String command = BaritoneScript.goTo(PREFIX, new Waypoint(1234.7, -987.2));
        assertTrue(command.contains("1235"), command);
        assertTrue(command.contains("-987"), command);
    }

    @Test
    void theVerbsAreExactlyWhatWouldBePublishedToTheServersChat() {
        // Without pinning the exact vocabulary, launch() returning "#foo" instead of "#elytra" passed
        // every test all the same. These strings are exactly what would be published in 6b6t's chat
        // if Baritone failed to intercept them: the contract that matters most.
        assertEquals("#elytra", BaritoneScript.launch(PREFIX));
        assertEquals("#cancel", BaritoneScript.cancel(PREFIX));
        assertEquals("#goal 1 2", BaritoneScript.goTo(PREFIX, new Waypoint(1, 2)));
        assertTrue(any(BaritoneScript.preparation(PREFIX, flying()), "#set elytraAutoJump true"));
    }

    @Test
    void anEmptyPrefixIsRejectedInsteadOfPublishingPlainChat() {
        // With an empty prefix the commands would go out to the server as plain chat, and the safety
        // net that cancels packets with Baritone's prefix would not recognise them as its own: it
        // would let the whole chat through. The core is the only funnel, so it checks here.
        assertThrows(IllegalArgumentException.class, () -> BaritoneScript.launch(""));
        assertThrows(IllegalArgumentException.class, () -> BaritoneScript.cancel(""));
        assertThrows(IllegalArgumentException.class, () -> BaritoneScript.goTo("", new Waypoint(1, 2)));
        assertThrows(IllegalArgumentException.class, () -> BaritoneScript.preparation("", flying()));
        assertThrows(IllegalArgumentException.class, () -> BaritoneScript.restoration("", flying()));
    }

    /**
     * Baritone's four out-of-the-box values, pinned here because they are an external and expensive
     * fact: if the resting state is not its own, the restoration does not restore, it
     * <b>reconfigures</b>. Baritone persists its settings to disk, so a single trip would leave every
     * #elytra the player runs by hand afterwards with values they never chose -and with no way to link
     * it to the addon-.
     *
     * <p>Read from the bytecode of baritone-standalone-fabric-1.17.0.jar (Settings class, obfuscated as
     * baritone/e.class, with javap -p -c). Two of the four were not the ones the module declared:
     * elytraConserveFireworks is FALSE, not true, and elytraFireworkSpeed is 1.2d, not 1.
     */
    @Test
    void theRestingValuesAreBaritonesOwnFactoryDefaults() {
        List<String> commands = BaritoneScript.restoration(PREFIX, BaritoneScript.baritoneDefaults());
        assertTrue(any(commands, "#set elytraAutoJump false"), commands.toString());
        assertTrue(any(commands, "#set elytraAllowEmergencyLand true"), commands.toString());
        assertTrue(any(commands, "#set elytraConserveFireworks false"), commands.toString());
        assertTrue(any(commands, "#set elytraFireworkSpeed 1.2"), commands.toString());
    }

    /**
     * And the 1.2 has to come out with its decimal. The formatter drops the decimals of whole numbers
     * so that "1" does not come out as "1.0"; if that trim took the 1.2 down with it, the restoration
     * would write a value different from the one it claims to restore.
     */
    @Test
    void theFactoryFireworkSpeedKeepsItsDecimal() {
        List<String> commands = BaritoneScript.restoration(PREFIX, BaritoneScript.baritoneDefaults());
        assertFalse(any(commands, "elytraFireworkSpeed 1 "), commands.toString());
        assertTrue(commands.contains("#set elytraFireworkSpeed 1.2"), commands.toString());
    }
}
