package com.xploits.printer.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.xploits.printer.core.BuildIndex.Status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Printer spec §5.7 (N-M8): the next spot is global — lowest actionable layer, then distance; Review Focus 5. */
class StandSpotTest {
    private static final String STONE = "minecraft:stone";
    private static final Set<String> CARRIED = Set.of(STONE);
    /** Flat ground: every y = 0 position can be stood on; the floor at y = -1 is the only support. */
    private static final StandSpot.World FLAT = new StandSpot.World() {
        @Override
        public boolean standable(Pos feet) {
            return feet.y() == 0;
        }

        @Override
        public boolean support(Pos block) {
            return block.y() == -1;
        }
    };

    private static BuildIndex row(Status status) {
        BuildIndex index = new BuildIndex(List.of(GridBox.of(new Pos(0, 0, 5), new Pos(2, 0, 5))));
        for (int x = 0; x <= 2; x++) index.set(new Pos(x, 0, 5), status, STONE);
        return index;
    }

    @Test
    void walksToTheNearestSpotThatReachesTheLowestLayer() {
        // From (0,0,1) the eye (0.5,1.62,1.5) reaches the floor face under (0,0,5) at 3.9477; from (0,0,0) it is 4.8771.
        StandSpot spots = new StandSpot(PrinterLimits.DEFAULTS);
        assertEquals(Optional.of(new Pos(0, 0, 1)),
            spots.next(row(Status.MISSING), CARRIED, true, new Point(0.5, 0, 0.5), 4.5, FLAT));
    }

    @Test
    void anUnreachableSpotIsNotOfferedAgain() {
        StandSpot spots = new StandSpot(PrinterLimits.DEFAULTS);
        spots.unreachable(new Pos(0, 0, 1));
        // (-1,0,1) and (1,0,1) tie at distance² 2; the lower x wins. From (-1,0,1): 3.9930 to (0.1, 0, 5.1).
        assertEquals(Optional.of(new Pos(-1, 0, 1)),
            spots.next(row(Status.MISSING), CARRIED, true, new Point(0.5, 0, 0.5), 4.5, FLAT));
        assertEquals(1, spots.streak());
    }

    @Test
    void threeUnreachableSpotsInARowExhaustTheWalk() {
        StandSpot spots = new StandSpot(PrinterLimits.DEFAULTS);
        spots.unreachable(new Pos(0, 0, 1));
        spots.unreachable(new Pos(-1, 0, 1));
        assertFalse(spots.exhausted());
        spots.unreachable(new Pos(1, 0, 1));
        assertTrue(spots.exhausted());
        spots.arrived();
        assertFalse(spots.exhausted());
    }

    @Test
    void nothingCarriedNoSpot() {
        assertEquals(Optional.empty(), new StandSpot(PrinterLimits.DEFAULTS)
            .next(row(Status.MISSING), Set.of(), true, new Point(0.5, 0, 0.5), 4.5, FLAT));
    }

    @Test
    void aWrongBlockIsReachedFromItsOwnFacesAndOnlyWithFixWrongBlocks() {
        BuildIndex index = row(Status.AIR_TARGET);
        index.set(new Pos(1, 0, 5), Status.WRONG, STONE);
        StandSpot spots = new StandSpot(PrinterLimits.DEFAULTS);
        // From (0,0,1) the north face of (1,0,5) is at 3.6233.
        assertEquals(Optional.of(new Pos(0, 0, 1)), spots.next(index, CARRIED, true, new Point(0.5, 0, 0.5), 4.5, FLAT));
        assertEquals(Optional.empty(), spots.next(index, CARRIED, false, new Point(0.5, 0, 0.5), 4.5, FLAT));
    }

    @Test
    void aSpotNeverStandsInATarget() {
        BuildIndex index = new BuildIndex(List.of(GridBox.of(new Pos(0, 0, 1), new Pos(2, 0, 5))));
        for (Pos p : index.nextToScan(100)) index.set(p, Status.AIR_TARGET, null);
        index.set(new Pos(0, 0, 5), Status.MISSING, STONE);
        index.set(new Pos(0, 0, 1), Status.MISSING, STONE);
        // The player stands on (0,0,5) itself: it is a target, so the ties at distance² 1 decide, the lower x first.
        assertEquals(Optional.of(new Pos(-1, 0, 5)), new StandSpot(PrinterLimits.DEFAULTS)
            .next(index, CARRIED, true, new Point(0.5, 0, 5.5), 4.5, FLAT));
    }
}
