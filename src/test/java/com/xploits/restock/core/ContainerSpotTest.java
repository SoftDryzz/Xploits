package com.xploits.restock.core;

import com.xploits.printer.core.Face;
import com.xploits.printer.core.Point;
import com.xploits.printer.core.Pos;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Restock spec §3: near a container stash-keeper remembers, the spot to stand on — two passable blocks over a full top
 * face — nearest the player, from which an open face of the container is within reach. Hand-worked from the eye at the
 * feet + (0.5, 1.62, 0.5): from (4, 64, 0) the east face's hit point (1, 64.9, 0.5) is √(3.5² + 0.72²) ≈ 3.573 away.
 */
class ContainerSpotTest {
    private static final Pos CHEST = new Pos(0, 64, 0);
    /** The player, ten blocks east, on the floor. */
    private static final Point PLAYER = new Point(10.5, 64, 0.5);

    /** A flat floor whose top is y 64; the blocks listed stand on it. */
    private record Flat(Set<Pos> solid) implements ContainerSpot.World {
        @Override
        public boolean standable(Pos feet) {
            return feet.y() == 64 && !solid.contains(feet) && !solid.contains(feet.offset(Face.UP));
        }

        @Override
        public boolean open(Pos block) {
            return block.y() >= 64 && !solid.contains(block);
        }
    }

    @Test
    void theSpotNearestThePlayerThatReachesAFace() {
        assertEquals(Optional.of(new Pos(4, 64, 0)),
            ContainerSpot.choose(CHEST, PLAYER, 4.5, 0.1, new Flat(Set.of(CHEST))));
    }

    @Test
    void aCoveredFaceIsNotUsed() {
        // East and top covered: (4, 64, 0) sees no open face; (4, 64, -1) reaches the north face at about 3.705 and
        // (4, 64, 1) the south face as far; both are 37 (squared) from the player, and the lower z wins.
        Flat covered = new Flat(Set.of(CHEST, new Pos(1, 64, 0), new Pos(0, 65, 0)));
        assertEquals(Optional.of(new Pos(4, 64, -1)), ContainerSpot.choose(CHEST, PLAYER, 4.5, 0.1, covered));
    }

    @Test
    void noStandableSpotIsEmpty() {
        ContainerSpot.World none = new ContainerSpot.World() {
            @Override
            public boolean standable(Pos feet) {
                return false;
            }

            @Override
            public boolean open(Pos block) {
                return true;
            }
        };
        assertEquals(Optional.empty(), ContainerSpot.choose(CHEST, PLAYER, 4.5, 0.1, none));
    }

    @Test
    void neverTheContainerItselfNorTheBlockUnderIt() {
        ContainerSpot.World anything = new ContainerSpot.World() {
            @Override
            public boolean standable(Pos feet) {
                return true;
            }

            @Override
            public boolean open(Pos block) {
                return true;
            }
        };
        // From on top of the chest the nearest candidates are four blocks away by one; y, then x, then z: (-1, 64, 0).
        assertEquals(Optional.of(new Pos(-1, 64, 0)),
            ContainerSpot.choose(CHEST, new Point(0.5, 64, 0.5), 4.5, 0.1, anything));
    }

    @Test
    void reachIsMeasuredToTheHitPoint() {
        Point eye = new Point(4.5, 65.62, 0.5);
        Flat flat = new Flat(Set.of(CHEST));
        assertEquals(true, ContainerSpot.reachable(CHEST, eye, 3.58, 0.1, flat));
        assertEquals(false, ContainerSpot.reachable(CHEST, eye, 3.57, 0.1, flat));
    }
}
