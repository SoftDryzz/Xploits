package com.xploits.restock.core;

import com.xploits.printer.core.Guards;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Every reason restock says, and how the printer's guards map onto them. */
class RestockReasonTest {
    /** Guards reasons that only the own printer can produce: unreachable with restock's inputs. */
    private static final Set<Guards.Reason> PRINTER_ONLY = Set.of(Guards.Reason.LITEMATICA_PRINTER_ON,
        Guards.Reason.PLACEMENT_CHANGED, Guards.Reason.LAYER_RANGE_CHANGED, Guards.Reason.OTHER_PLACEMENT_OVERLAPS,
        Guards.Reason.BREAK_SPEED_MISMATCH, Guards.Reason.LOOP, Guards.Reason.NO_HOTBAR_ROOM,
        Guards.Reason.CURSOR_NOT_EMPTY, Guards.Reason.FINISHED, Guards.Reason.LEFTOVERS,
        Guards.Reason.MATERIAL_MISSING, Guards.Reason.NOTHING_REACHABLE, Guards.Reason.NOTHING_KNOWN);

    @Test
    void everyGuardReasonMapsToTheSameNameOrToInternal() {
        for (Guards.Reason r : Guards.Reason.values()) {
            RestockReason mapped = RestockReason.of(r);
            if (PRINTER_ONLY.contains(r)) assertEquals(RestockReason.INTERNAL, mapped, r.name());
            else if (r == Guards.Reason.PLACEMENT_NOT_SENT) assertEquals(RestockReason.CLICK_NOT_SENT, mapped);
            else assertEquals(r.name(), mapped.name());
        }
    }

    @Test
    void theEffectsRestockActsOn() {
        assertEquals(RestockReason.Effect.STOP, RestockReason.PLAYER_NEAR.effect());
        assertEquals(RestockReason.Effect.STOP, RestockReason.PLAYER_MOVED.effect());
        assertEquals(RestockReason.Effect.STOP, RestockReason.INTERNAL.effect());
        assertEquals(RestockReason.Effect.REFUSE, RestockReason.NO_BARITONE.effect());
        assertEquals(RestockReason.Effect.REFUSE, RestockReason.EASY_PLACE_RESTRICTION.effect(),
            "for restock only a refusal: later, the cancelled click is CONTAINER_REFUSED");
        assertEquals(RestockReason.Effect.PAUSE, RestockReason.LAG.effect());
        assertEquals(RestockReason.Effect.PAUSE, RestockReason.OTHER_ROTATION.effect());
    }

    @Test
    void aRefusalKeepsItsDetail() {
        assertEquals(new RestockReason.Refusal(RestockReason.CONFLICTING_MODULE, "scaffold"),
            RestockReason.of(new Guards.Refusal(Guards.Reason.CONFLICTING_MODULE, "scaffold")));
    }
}
