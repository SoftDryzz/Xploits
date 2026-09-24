package com.xploits.travel.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resolving a destination is pure arithmetic -a starting point and a few numbers- and that is why it
 * is tested without starting the game. Here live the two player decisions the core has to honour to
 * the letter: that on a diagonal the distance is blocks travelled, and that an offset is an offset and
 * not coordinates.
 */
class DestinationTest {
    private static final double TOLERANCE = 0.001;

    /**
     * A starting point that is not the world origin, on purpose: with (0, 0) an offset and absolute
     * coordinates give the same point, and the tests would accept both.
     */
    private static final Waypoint ORIGIN = new Waypoint(1_234, -567);

    @Test
    void theFourCardinalAxesStillPointExactlyWhereTheyDid() {
        // The cardinal ones go through the unit vector like all the others since there are diagonals,
        // and hypot(1, 0) is exactly 1.0, so no rounding error has crept into them.
        assertEquals(ORIGIN.x() + 1_000, Destination.highway(Axis.X_PLUS, 1_000).resolve(ORIGIN).x(), 0);
        assertEquals(ORIGIN.z(), Destination.highway(Axis.X_PLUS, 1_000).resolve(ORIGIN).z(), 0);
        assertEquals(ORIGIN.x() - 1_000, Destination.highway(Axis.X_MINUS, 1_000).resolve(ORIGIN).x(), 0);
        assertEquals(ORIGIN.z() + 1_000, Destination.highway(Axis.Z_PLUS, 1_000).resolve(ORIGIN).z(), 0);
        assertEquals(ORIGIN.x(), Destination.highway(Axis.Z_PLUS, 1_000).resolve(ORIGIN).x(), 0);
        assertEquals(ORIGIN.z() - 1_000, Destination.highway(Axis.Z_MINUS, 1_000).resolve(ORIGIN).z(), 0);
    }

    @Test
    void theFourDiagonalAxesPointWhereTheySay() {
        double half = 10_000 * Math.sqrt(0.5);

        assertEquals(ORIGIN.x() + half, Destination.highway(Axis.X_PLUS_Z_PLUS, 10_000).resolve(ORIGIN).x(), TOLERANCE);
        assertEquals(ORIGIN.z() + half, Destination.highway(Axis.X_PLUS_Z_PLUS, 10_000).resolve(ORIGIN).z(), TOLERANCE);

        assertEquals(ORIGIN.x() + half, Destination.highway(Axis.X_PLUS_Z_MINUS, 10_000).resolve(ORIGIN).x(), TOLERANCE);
        assertEquals(ORIGIN.z() - half, Destination.highway(Axis.X_PLUS_Z_MINUS, 10_000).resolve(ORIGIN).z(), TOLERANCE);

        assertEquals(ORIGIN.x() - half, Destination.highway(Axis.X_MINUS_Z_PLUS, 10_000).resolve(ORIGIN).x(), TOLERANCE);
        assertEquals(ORIGIN.z() + half, Destination.highway(Axis.X_MINUS_Z_PLUS, 10_000).resolve(ORIGIN).z(), TOLERANCE);

        assertEquals(ORIGIN.x() - half, Destination.highway(Axis.X_MINUS_Z_MINUS, 10_000).resolve(ORIGIN).x(), TOLERANCE);
        assertEquals(ORIGIN.z() - half, Destination.highway(Axis.X_MINUS_Z_MINUS, 10_000).resolve(ORIGIN).z(), TOLERANCE);
    }

    @Test
    void theHighwayDistanceIsBlocksFlownOnAllEightAxes() {
        // The decision that governs the diagonals: 20 000 is the same stretch of fireworks wherever
        // the axis points. Without normalising the vector, a diagonal would fly 28 284 -41 % more-
        // with the same number set, and the player would be stranded halfway.
        for (Axis axis : Axis.values()) {
            Waypoint destination = Destination.highway(axis, 20_000).resolve(ORIGIN);
            assertEquals(20_000, ORIGIN.distanceTo(destination), TOLERANCE,
                axis + " does not travel the blocks it says");
        }
    }

    @Test
    void onlyTheFourDiagonalsSplitTheDistanceBetweenBothCoordinates() {
        assertFalse(Axis.X_PLUS.isDiagonal());
        assertFalse(Axis.X_MINUS.isDiagonal());
        assertFalse(Axis.Z_PLUS.isDiagonal());
        assertFalse(Axis.Z_MINUS.isDiagonal());
        assertTrue(Axis.X_PLUS_Z_PLUS.isDiagonal());
        assertTrue(Axis.X_PLUS_Z_MINUS.isDiagonal());
        assertTrue(Axis.X_MINUS_Z_PLUS.isDiagonal());
        assertTrue(Axis.X_MINUS_Z_MINUS.isDiagonal());
    }

    @Test
    void everyAxisNameKeepsTheIdentifierMeteorWritesToDisk() {
        // EnumSetting.save writes get().toString() and load looks it up comparing toString(); if it
        // does not find it, parse assigns nothing and the setting stays at its default value. That is,
        // renaming an axis -or giving it a prettier toString()- silently changes the axis for whoever
        // had that one saved. This test is the one that turns that change red before it reaches
        // anybody's disk.
        assertEquals("X_PLUS", Axis.X_PLUS.toString());
        assertEquals("X_MINUS", Axis.X_MINUS.toString());
        assertEquals("Z_PLUS", Axis.Z_PLUS.toString());
        assertEquals("Z_MINUS", Axis.Z_MINUS.toString());
        assertEquals("X_PLUS_Z_PLUS", Axis.X_PLUS_Z_PLUS.toString());
        assertEquals("X_PLUS_Z_MINUS", Axis.X_PLUS_Z_MINUS.toString());
        assertEquals("X_MINUS_Z_PLUS", Axis.X_MINUS_Z_PLUS.toString());
        assertEquals("X_MINUS_Z_MINUS", Axis.X_MINUS_Z_MINUS.toString());
    }

    @Test
    void absoluteCoordinatesIgnoreWhereTheTripStarts() {
        Waypoint destination = Destination.coordinates(5_000, -3_000).resolve(ORIGIN);
        assertEquals(5_000, destination.x(), TOLERANCE);
        assertEquals(-3_000, destination.z(), TOLERANCE);
    }

    @Test
    void aRelativeDestinationIsAnOffsetFromWhereTheTripStarts() {
        Waypoint destination = Destination.relative(5_000, -3_000).resolve(ORIGIN);
        assertEquals(ORIGIN.x() + 5_000, destination.x(), TOLERANCE);
        assertEquals(ORIGIN.z() - 3_000, destination.z(), TOLERANCE);
    }

    @Test
    void theSameTwoNumbersMeanTwoDifferentPlacesInTheTwoModes() {
        // The failure that offset-x/offset-z's own settings avoid upstream: the same two numbers are
        // one place in the world in COORDINATES and a completely different one in RELATIVE. The core
        // telling them apart is half of the guarantee; the other half is the adapter not reading them
        // from the same pair of settings.
        Waypoint absolute = Destination.coordinates(5_000, -3_000).resolve(ORIGIN);
        Waypoint offset = Destination.relative(5_000, -3_000).resolve(ORIGIN);

        assertTrue(absolute.distanceTo(offset) > 1,
            "an offset and coordinates cannot resolve to the same point: " + absolute.distanceTo(offset));
    }

    @Test
    void aZeroOffsetIsTheOriginItself() {
        // An offset of (0, 0) is a destination equal to the origin, which is the same case as
        // coordinates set where the player is or a highway distance of 0. It is not rejected: a
        // zero-block trip is exactly what was asked for, delivered as it is, and there is no pattern
        // being drawn straight behind anyone's back. The planner's
        // aRelativeDestinationOfZeroBehavesLikeAnyOtherDestinationEqualToTheOrigin checks it too.
        Waypoint destination = Destination.relative(0, 0).resolve(ORIGIN);
        assertEquals(ORIGIN.x(), destination.x(), 0);
        assertEquals(ORIGIN.z(), destination.z(), 0);
    }

    @Test
    void onlyTheHighwayModeMakesThePatternStayInsideACorridor() {
        // highway() is what the planner looks at to cap the amplitude and to forbid the decoy. The
        // relative mode is a standalone destination like the coordinates one: there is no corridor to
        // leave there, so nothing is capped.
        assertTrue(Destination.highway(Axis.X_PLUS_Z_MINUS, 1).highway());
        assertFalse(Destination.coordinates(1, 1).highway());
        assertFalse(Destination.relative(1, 1).highway());
    }
}
