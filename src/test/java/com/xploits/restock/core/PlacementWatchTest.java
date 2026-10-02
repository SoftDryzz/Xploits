package com.xploits.restock.core;

import com.xploits.printer.core.GridBox;
import com.xploits.printer.core.Guards;
import com.xploits.printer.core.Pos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Restock spec §3: only the selected placement is counted; any change of it is counted again (no stop). */
class PlacementWatchTest {
    private static GridBox box(int x1, int y1, int z1, int x2, int y2, int z2) {
        return GridBox.of(new Pos(x1, y1, z1), new Pos(x2, y2, z2));
    }

    private static final GridBox MAIN = box(0, 0, 0, 2, 2, 2);

    private static PlacementWatch.View view(boolean enabled, Pos origin, String rotation, Map<String, GridBox> regions,
                                            List<PlacementWatch.Other> others) {
        return new PlacementWatch.View(7L, enabled, origin, rotation, "NONE", regions, others);
    }

    private static PlacementWatch.View plain(List<PlacementWatch.Other> others) {
        return view(true, new Pos(0, 0, 0), "NONE", Map.of("main", MAIN), others);
    }

    @Test
    void refusalsAtStart() {
        assertEquals(Optional.of(Guards.Reason.NO_PLACEMENT), PlacementWatch.refusal(null, 100));
        assertEquals(Optional.of(Guards.Reason.PLACEMENT_DISABLED), PlacementWatch.refusal(
            view(false, new Pos(0, 0, 0), "NONE", Map.of("main", MAIN), List.of()), 100));
        assertEquals(Optional.of(Guards.Reason.NO_ENABLED_REGION), PlacementWatch.refusal(
            view(true, new Pos(0, 0, 0), "NONE", Map.of(), List.of()), 100));
        assertEquals(Optional.of(Guards.Reason.PLACEMENT_TOO_LARGE), PlacementWatch.refusal(plain(List.of()), 26));
        assertEquals(Optional.empty(), PlacementWatch.refusal(plain(List.of()), 27));
        assertEquals(Optional.of(Guards.Reason.PLACEMENT_OVERLAP), PlacementWatch.refusal(plain(List.of(
            new PlacementWatch.Other(8L, true, box(2, 2, 2, 5, 5, 5)))), 100));
        assertEquals(Optional.empty(), PlacementWatch.refusal(plain(List.of(
            new PlacementWatch.Other(8L, false, box(2, 2, 2, 5, 5, 5)),
            new PlacementWatch.Other(9L, true, box(3, 0, 0, 5, 2, 2)))), 100), "disabled, or touching nothing");
    }

    @Test
    void anyChangeOfTheSelectedPlacementIsCountedAgain() {
        PlacementWatch watch = new PlacementWatch(plain(List.of()));
        assertFalse(watch.changed(plain(List.of())));
        assertTrue(watch.changed(null), "deselected");
        assertTrue(watch.changed(view(true, new Pos(1, 0, 0), "NONE", Map.of("main", box(1, 0, 0, 3, 2, 2)), List.of())),
            "moved");
        assertTrue(watch.changed(view(true, new Pos(0, 0, 0), "CLOCKWISE_90", Map.of("main", MAIN), List.of())), "rotated");
        assertTrue(watch.changed(view(false, new Pos(0, 0, 0), "NONE", Map.of("main", MAIN), List.of())), "disabled");
        assertTrue(watch.changed(view(true, new Pos(0, 0, 0), "NONE", Map.of("main", MAIN, "roof", box(0, 3, 0, 2, 3, 2)),
            List.of())), "a sub-region enabled");
        assertTrue(watch.changed(new PlacementWatch.View(8L, true, new Pos(0, 0, 0), "NONE", "NONE", Map.of("main", MAIN),
            List.of())), "another placement selected");
    }

    @Test
    void anotherPlacementComingToOverlapIsCountedAgain() {
        PlacementWatch watch = new PlacementWatch(plain(List.of(new PlacementWatch.Other(8L, false, box(2, 2, 2, 5, 5, 5)))));
        assertFalse(watch.changed(plain(List.of(new PlacementWatch.Other(8L, false, box(2, 2, 2, 5, 5, 5))))));
        assertTrue(watch.changed(plain(List.of(new PlacementWatch.Other(8L, true, box(2, 2, 2, 5, 5, 5))))), "enabled");
        assertTrue(watch.changed(plain(List.of(new PlacementWatch.Other(9L, true, box(-3, 0, 0, 0, 0, 0))))), "moved onto it");
        assertFalse(watch.changed(plain(List.of(new PlacementWatch.Other(9L, true, box(-3, 0, 0, -1, 0, 0))))), "next to it");
    }
}
