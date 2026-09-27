package com.xploits.pvp.crystal.core;

import com.xploits.pvp.crystal.core.CrystalSettings.AutoSwitch;
import com.xploits.pvp.crystal.core.CrystalSettings.PauseMode;
import com.xploits.pvp.crystal.core.Refusal.Change;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure pieces the crystal-aura++ adapter decides with: ping, refusal episodes and setting values. */
class CrystalAdapterFactsTest {
    @Test
    void pingIsTheLatencyInTicksRoundedUpAndFiveWhenUnknown() {
        assertEquals(CrystalBrain.UNKNOWN_PING_TICKS, CrystalBrain.pingTicks(CrystalBrain.UNKNOWN_LATENCY));
        assertEquals(5, CrystalBrain.pingTicks(-7));
        assertEquals(0, CrystalBrain.pingTicks(0));
        assertEquals(1, CrystalBrain.pingTicks(1));
        assertEquals(1, CrystalBrain.pingTicks(50));
        assertEquals(2, CrystalBrain.pingTicks(51));
        assertEquals(3, CrystalBrain.pingTicks(150));
        assertEquals(4, CrystalBrain.pingTicks(151));
    }

    @Test
    void aHugeLatencyFromTheServerDoesNotWrapAroundToANegativePing() {
        // The server sends the player list latency; a negative ping would make placed() throw in a tick handler.
        assertEquals(42_949_673, CrystalBrain.pingTicks(Integer.MAX_VALUE));
        assertTrue(CrystalBrain.pingTicks(Integer.MAX_VALUE - 10) >= 0);
    }

    @Test
    void aRefusalWarnsOnceWhenItStartsAndEndsWhenMeteorsAuraIsOff() {
        Refusal r = new Refusal();
        assertEquals(Change.NONE, r.update(false));
        assertFalse(r.refusing());

        assertEquals(Change.STARTED, r.update(true));
        assertTrue(r.refusing());
        assertEquals(Change.NONE, r.update(true), "one warning per episode");
        assertEquals(Change.NONE, r.update(true));
        assertTrue(r.refusing());

        assertEquals(Change.ENDED, r.update(false));
        assertFalse(r.refusing());
        assertEquals(Change.NONE, r.update(false));

        assertEquals(Change.STARTED, r.update(true), "a new episode warns again");
    }

    @Test
    void settingValuesAreMeteorsNamesBecauseTheyAreSavedAndTyped() {
        assertEquals(List.of("None", "Normal"), Arrays.stream(AutoSwitch.values()).map(Object::toString).toList());
        assertEquals(List.of("Both", "Place", "Break", "None"), Arrays.stream(PauseMode.values()).map(Object::toString).toList());
        assertEquals(List.of("Both", "Packet", "Client", "None"), Arrays.stream(SwingMode.values()).map(Object::toString).toList());
    }

    @Test
    void swingModeSaysWhoSeesTheSwingAsMeteorsDoes() {
        assertTrue(SwingMode.BOTH.client());
        assertTrue(SwingMode.BOTH.packet());
        assertFalse(SwingMode.PACKET.client());
        assertTrue(SwingMode.PACKET.packet());
        assertTrue(SwingMode.CLIENT.client());
        assertFalse(SwingMode.CLIENT.packet());
        assertFalse(SwingMode.NONE.client());
        assertFalse(SwingMode.NONE.packet());
    }

    @Test
    void refusingBecauseOfMeteorsAuraHasItsOwnReason() {
        assertEquals(Reason.METEOR_AURA_ON, Decision.none(Reason.METEOR_AURA_ON).reason());
    }
}
