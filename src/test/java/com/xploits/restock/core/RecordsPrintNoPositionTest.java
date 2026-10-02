package com.xploits.restock.core;

import com.xploits.printer.core.Aim;
import com.xploits.printer.core.Face;
import com.xploits.printer.core.GridBox;
import com.xploits.printer.core.Point;
import com.xploits.printer.core.Pos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deferred L6 (spec §3: no coordinates anywhere): a record that holds a position never prints it, should it ever reach a
 * log line or an exception's message — it says {@code (position)} instead. Every other field still shows.
 */
class RecordsPrintNoPositionTest {
    private static final Pos AT = new Pos(12345, 67, -6789);
    private static final Pos STAND = new Pos(12344, 67, -6788);
    private static final Point HIT = new Point(12345.5, 67.9, -6788.5);
    private static final GridBox BOX = GridBox.of(AT, STAND);

    @Test
    void noRecordPrintsAPosition() {
        List<Object> records = List.of(
            Source.mark("minecraft:overworld", AT, STAND, Map.of("minecraft:stone", 64), Map.of()),
            Source.stash("minecraft:overworld", AT, Map.of("minecraft:stone", 64), Map.of()),
            new MarkBook.Mark("minecraft:overworld", AT, STAND),
            new ContainerAim.Aiming(Face.EAST, HIT, new Aim.Rotation(90f, 10f)),
            new ContainerAim.Pick(AT, new ContainerAim.Aiming(Face.EAST, HIT, new Aim.Rotation(90f, 10f))),
            new PlacementWatch.Other(7, true, BOX),
            new PlacementWatch.View(7, true, AT, "NONE", "NONE", Map.of("main", BOX),
                List.of(new PlacementWatch.Other(8, true, BOX))),
            new RestockTrip.Plan("minecraft:stone", AT, Optional.of(STAND), STAND, true),
            new RestockTrip.GoTo(AT),
            new RestockTrip.GoToward(AT.x(), AT.z()),
            new RestockTrip.Aim(AT),
            new RestockTrip.ClickContainer(AT),
            new RestockTrip.NeedSource("minecraft:stone", AT, RestockTrip.Failure.STALE),
            new RestockTrip.Facts(false, false, true, true, 3.5, false, Optional.of(STAND), RestockTrip.Aiming.HELD,
                RestockTrip.Click.NONE, true, true, new TakePlan.Done(true), 64, true));
        for (Object r : records) {
            String printed = r.toString();
            String name = r.getClass().getSimpleName();
            assertFalse(printed.contains("12345") || printed.contains("12344") || printed.contains("6789")
                || printed.contains("6788"), name + " prints a position: " + name);
            assertTrue(printed.startsWith(name + "["), name + " still names itself");
            assertTrue(printed.contains(HiddenPositions.HIDDEN), name + " says a position is there");
        }
    }

    @Test
    void everyOtherFieldStillShows() {
        String source = Source.mark("minecraft:overworld", AT, STAND, Map.of("minecraft:stone", 64), Map.of()).toString();
        assertTrue(source.contains("MARK") && source.contains("minecraft:overworld") && source.contains("stone=64"), source);
        String plan = new RestockTrip.Plan("minecraft:stone", AT, Optional.empty(), STAND, true).toString();
        assertTrue(plan.contains("minecraft:stone") && plan.contains("stand=none") && plan.contains("printerPaused=true"),
            plan);
        String need = new RestockTrip.NeedSource("minecraft:stone", AT, RestockTrip.Failure.FILLED_ONLY).toString();
        assertTrue(need.contains("FILLED_ONLY"), need);
        String facts = new RestockTrip.Facts(true, false, true, true, 3.5, false, Optional.empty(), RestockTrip.Aiming.HELD,
            RestockTrip.Click.SENT, true, true, new TakePlan.Click(4), 64, true).toString();
        assertTrue(facts.contains("movementKeys=true") && facts.contains("spot=none") && facts.contains("HELD")
            && facts.contains("SENT") && facts.contains("carried=64"), facts);
    }
}
