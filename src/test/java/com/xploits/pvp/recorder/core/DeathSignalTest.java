package com.xploits.pvp.recorder.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeathSignalTest {
    /** One tick: whether the entity changed, whether a death message was read, whether you are alive. */
    private record Tick(boolean respawned, boolean message, boolean alive) {
    }

    private static final Tick ALIVE = new Tick(false, false, true);
    private static final Tick DEAD = new Tick(false, false, false);
    private static final Tick MESSAGE_ALIVE = new Tick(false, true, true);
    private static final Tick MESSAGE_DEAD = new Tick(false, true, false);
    private static final Tick RESPAWN = new Tick(true, false, true);
    private static final Tick RESPAWN_WITH_MESSAGE = new Tick(true, true, true);

    private static List<Boolean> run(DeathSignal signal, Tick... ticks) {
        List<Boolean> reported = new ArrayList<>();
        for (Tick t : ticks) reported.add(signal.tick(t.respawned(), t.message(), t.alive()));
        return reported;
    }

    private static List<Boolean> run(Tick... ticks) {
        return run(new DeathSignal(), ticks);
    }

    @Test
    void deathMessageFirstIsReportedOnItsTickAndTheHealthEdgeAfterItIsTheSameDeath() {
        assertEquals(List.of(false, true, false, false, false),
            run(ALIVE, MESSAGE_ALIVE, DEAD, DEAD, RESPAWN));
    }

    @Test
    void healthEdgeFirstIsReportedOnItsTickAndTheLateMessageIsTheSameDeath() {
        assertEquals(List.of(false, true, false, false),
            run(ALIVE, DEAD, MESSAGE_DEAD, RESPAWN));
    }

    @Test
    void bothSignsOnOneTickAreOneDeath() {
        assertEquals(List.of(false, true, false), run(ALIVE, MESSAGE_DEAD, DEAD));
    }

    @Test
    void immediateRespawnWithNoDeadTickIsReportedFromTheMessage() {
        assertEquals(List.of(false, true, false, false), run(ALIVE, MESSAGE_ALIVE, RESPAWN, ALIVE));
    }

    @Test
    void aMessageReadOnTheRespawnTickBelongsToTheEntityThatDied() {
        assertEquals(List.of(false, true, false), run(ALIVE, RESPAWN_WITH_MESSAGE, ALIVE));
    }

    @Test
    void aMessageOnTheRespawnTickForADeathAlreadyReportedIsNotReportedAgain() {
        assertEquals(List.of(false, true, false, false), run(ALIVE, DEAD, RESPAWN_WITH_MESSAGE, ALIVE));
    }

    @Test
    void theNextLifeDiesAgain() {
        assertEquals(List.of(false, true, false, false, true, false),
            run(ALIVE, DEAD, RESPAWN, ALIVE, DEAD, RESPAWN));
    }

    @Test
    void theNextLifeDiesAgainAfterAnImmediateRespawn() {
        assertEquals(List.of(false, true, false, true), run(ALIVE, MESSAGE_ALIVE, RESPAWN, MESSAGE_ALIVE));
    }

    @Test
    void aFirstTickAlreadyDeadIsNotADeath() {
        // Turned on while on the death screen: no alive tick was seen, so there is no edge.
        assertEquals(List.of(false, false), run(DEAD, DEAD));
    }

    @Test
    void theEdgeOnTheRespawnTickIsIgnored() {
        // The previous tick's alive belongs to the old entity; a new one dead on arrival is not a death.
        assertEquals(List.of(false, false), run(ALIVE, new Tick(true, false, false)));
    }

    @Test
    void resetForgetsTheReportedDeathAndThePreviousAlive() {
        DeathSignal signal = new DeathSignal();
        assertEquals(List.of(false, true), run(signal, ALIVE, DEAD));
        signal.reset();
        assertEquals(List.of(false, true), run(signal, DEAD, MESSAGE_DEAD));
    }
}
