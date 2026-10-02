package com.xploits.printer.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Printer spec §5.2–5.3: hit point, facing, rotation; Review Focus 1. */
class AimTest {
    private static final Point EYE = new Point(0.5, 1.62, 0.5);

    @Test
    void aFaceIsSeenOnlyFromItsOuterSide() {
        assertTrue(Aim.facesEye(new Pos(0, -1, 3), Face.UP, EYE));
        assertFalse(Aim.facesEye(new Pos(0, 1, 3), Face.UP, EYE), "a top face above the eye");
        assertFalse(Aim.facesEye(new Pos(0, 0, 3), Face.UP, new Point(0.5, 1.0, 0.5)), "an eye on the plane sees nothing");
        assertTrue(Aim.facesEye(new Pos(1, 0, 2), Face.NORTH, EYE));
        assertFalse(Aim.facesEye(new Pos(1, 0, 2), Face.SOUTH, EYE));
        assertTrue(Aim.facesEye(new Pos(1, 0, 2), Face.WEST, EYE));
        assertFalse(Aim.facesEye(new Pos(1, 0, 2), Face.EAST, EYE));
        assertFalse(Aim.facesEye(new Pos(1, 0, 2), Face.DOWN, EYE));
    }

    @Test
    void theHitPointIsTheFacesNearestPointKeptOffItsEdges() {
        assertPoint(new Point(0.5, 0.0, 3.1), Aim.hitPoint(new Pos(0, -1, 3), Face.UP, EYE, 0.1));
        assertPoint(new Point(1.1, 0.0, 2.1), Aim.hitPoint(new Pos(1, -1, 2), Face.UP, EYE, 0.1));
        assertPoint(new Point(1.1, 0.9, 2.0), Aim.hitPoint(new Pos(1, 0, 2), Face.NORTH, EYE, 0.1));
        assertPoint(new Point(1.0, 0.9, 2.1), Aim.hitPoint(new Pos(1, 0, 2), Face.WEST, EYE, 0.1));
        assertPoint(new Point(4.9, 1.0, 0.5), Aim.hitPoint(new Pos(4, 0, 0), Face.UP, new Point(9.0, 1.62, 0.5), 0.1));
    }

    @Test
    void rotationsMatchTheHandWorkedTable() {
        assertRotation(0.0f, 31.92608f, Aim.rotation(EYE, new Point(0.5, 0.0, 3.1), 0f));
        assertRotation(-20.556046f, 43.471912f, Aim.rotation(EYE, new Point(1.1, 0.0, 2.1), 0f));
        assertRotation(-21.801409f, 24.021042f, Aim.rotation(EYE, new Point(1.1, 0.9, 2.0), 0f));
        assertRotation(-90.0f, 13.412357f, Aim.rotation(EYE, new Point(3.1, 1.0, 0.5), 0f));
        assertRotation(-180.0f, 13.412357f, Aim.rotation(EYE, new Point(0.5, 1.0, -2.1), 0f));
        assertRotation(90.0f, 23.30489f, Aim.rotation(EYE, new Point(-2.1, 0.5, 0.5), 0f));
    }

    @Test
    void yawStaysContinuousWithAWoundUpCamera() {
        Point hit = new Point(0.5, 0.0, 3.1);
        assertRotation(360.0f, 31.92608f, Aim.rotation(EYE, hit, 350f));
        assertRotation(7200.0f, 31.92608f, Aim.rotation(EYE, hit, 7200.5f));
        assertRotation(0.0f, 31.92608f, Aim.rotation(EYE, hit, -179f));
        assertRotation(-720.0f, 31.92608f, Aim.rotation(EYE, hit, -719.0f));
    }

    @Test
    void wrapDegreesLandsInMinus180To180() {
        assertEquals(10.0, Aim.wrapDegrees(-350));
        assertEquals(-180.0, Aim.wrapDegrees(180));
        assertEquals(-180.0, Aim.wrapDegrees(-180));
        assertEquals(-180.0, Aim.wrapDegrees(540));
        assertEquals(179.5, Aim.wrapDegrees(-540.5));
        assertEquals(0.5, Aim.wrapDegrees(7200.5));
    }

    @Test
    void aFlatLookIsPlusZeroNotMinusZero() {
        Aim.Rotation r = Aim.rotation(new Point(0.5, 1.5, 0.5), new Point(0.5, 1.5, 3.0), 0f);
        assertEquals(new Aim.Rotation(0.0f, 0.0f), r);
    }

    private static void assertPoint(Point expected, Point actual) {
        assertEquals(expected.x(), actual.x(), 1e-9);
        assertEquals(expected.y(), actual.y(), 1e-9);
        assertEquals(expected.z(), actual.z(), 1e-9);
    }

    private static void assertRotation(float yaw, float pitch, Aim.Rotation r) {
        assertEquals(yaw, r.yaw(), 1e-4f);
        assertEquals(pitch, r.pitch(), 1e-4f);
    }
}
