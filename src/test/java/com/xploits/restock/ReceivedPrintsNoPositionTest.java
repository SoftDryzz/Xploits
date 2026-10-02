package com.xploits.restock;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Deferred L6: what the Netty thread queued for the session prints as counts, never the changed blocks' positions. */
class ReceivedPrintsNoPositionTest {
    @Test
    void onlyCounts() {
        RestockSession.Received in = new RestockSession.Received(
            List.of(new BlockPos(12345, 67, -6789), new BlockPos(12346, 67, -6789)), List.of(), true);
        assertEquals("Received[changed=2, damage=0, setback=true]", in.toString());
    }
}
