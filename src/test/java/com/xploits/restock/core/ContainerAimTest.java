package com.xploits.restock.core;

import com.xploits.printer.core.Face;
import com.xploits.printer.core.PlacePlanner;
import com.xploits.printer.core.Point;
import com.xploits.printer.core.Pos;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Restock spec §3: look at the container before clicking it (reach ≤ 4.5), on a face the eye can see. From the eye
 * (4.5, 65.62, 0.5): the east face's hit (1, 64.9, 0.5) is ≈ 3.573 away, yaw 90°, pitch atan(0.72 / 3.5) ≈ 11.6244°;
 * the top face's hit (0.9, 65, 0.5) is √(3.6² + 0.62²) ≈ 3.653 away.
 */
class ContainerAimTest {
    private static final Pos CHEST = new Pos(0, 64, 0);
    private static final Point EYE = new Point(4.5, 65.62, 0.5);
    private static final PlacePlanner.RayOracle ALL = (block, side, rotation) -> true;

    @Test
    void theNearestVisibleFaceAndItsRotation() {
        ContainerAim.Aiming a = ContainerAim.choose(CHEST, EYE, 0f, 4.5, 0.1, ALL).orElseThrow();
        assertEquals(Face.EAST, a.side());
        assertEquals(1.0, a.hit().x(), 1e-9);
        assertEquals(64.9, a.hit().y(), 1e-9);
        assertEquals(0.5, a.hit().z(), 1e-9);
        assertEquals(90.0f, a.rotation().yaw());
        assertEquals(11.6244f, a.rotation().pitch(), 1e-3f);
    }

    @Test
    void aFaceTheRayDoesNotSeeIsSkipped() {
        PlacePlanner.RayOracle notEast = (block, side, rotation) -> side != Face.EAST;
        assertEquals(Face.UP, ContainerAim.choose(CHEST, EYE, 0f, 4.5, 0.1, notEast).orElseThrow().side());
    }

    @Test
    void reachIsMeasuredToTheHitPoint() {
        assertEquals(Face.EAST, ContainerAim.choose(CHEST, EYE, 0f, 3.6, 0.1, ALL).orElseThrow().side());
        assertEquals(Optional.empty(), ContainerAim.choose(CHEST, EYE, 0f, 3.6, 0.1,
            (block, side, rotation) -> side != Face.EAST), "the top face is 3.653 away");
        assertEquals(Optional.empty(), ContainerAim.choose(CHEST, EYE, 0f, 3.5, 0.1, ALL));
    }

    @Test
    void theYawStaysContinuousWithAWoundUpCamera() {
        // 7200.5 + wrap(90 − 7200.5) = 7200.5 + 89.5: twenty turns plus a quarter, never a 7000° snap.
        assertEquals(7290.0f, ContainerAim.choose(CHEST, EYE, 7200.5f, 4.5, 0.1, ALL).orElseThrow().rotation().yaw());
    }

    @Test
    void aFaceTurnedAwayFromTheEyeIsNeverAimedAt() {
        PlacePlanner.RayOracle onlyWest = (block, side, rotation) -> side == Face.WEST;
        assertEquals(Optional.empty(), ContainerAim.choose(CHEST, EYE, 0f, 6.0, 0.1, onlyWest));
    }
}
