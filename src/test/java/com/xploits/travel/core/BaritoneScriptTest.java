package com.xploits.travel.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            assertTrue(command.startsWith(PREFIX), "sin prefijo: " + command);
        }
        assertTrue(BaritoneScript.launch(PREFIX).startsWith(PREFIX));
        assertTrue(BaritoneScript.cancel(PREFIX).startsWith(PREFIX));
        assertTrue(BaritoneScript.goTo(PREFIX, new Waypoint(1, 2)).startsWith(PREFIX));
    }

    @Test
    void aDifferentPrefixIsHonoured() {
        assertTrue(BaritoneScript.launch(">").startsWith(">"));
        for (String command : BaritoneScript.preparation(">", flying())) {
            assertTrue(command.startsWith(">"), "sin prefijo: " + command);
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

        assertTrue(any(restoration, "elytraAutoSwap true"), "hay que devolver el cambio propio de Baritone");
        assertTrue(any(restoration, "elytraAutoJump false"));
        assertTrue(any(restoration, "elytraConserveFireworks false"));
    }

    @Test
    void theGoalCarriesTheCoordinatesRounded() {
        String command = BaritoneScript.goTo(PREFIX, new Waypoint(1234.7, -987.2));
        assertTrue(command.contains("1235"), command);
        assertTrue(command.contains("-987"), command);
    }
}
