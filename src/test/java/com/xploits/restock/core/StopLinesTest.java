package com.xploits.restock.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Final review m2: a stop says itself first; the unpack it was finishing for reads as a line after it. */
class StopLinesTest {
    @Test
    void theStopComesFirstThenTheDrainedLineThenTheAlsoLineThenWhatEndingLeftToSay() {
        assertEquals(List.of(RestockText.STOPPED, RestockText.UNPACK_DRAINED, RestockText.STOPPED_ALSO,
                RestockText.PRINTER_LEFT_PAUSED, RestockText.BORROWED_LEFT),
            StopLines.order(RestockText.STOPPED, RestockText.UNPACK_DRAINED, RestockText.STOPPED_ALSO,
                List.of(RestockText.PRINTER_LEFT_PAUSED, RestockText.BORROWED_LEFT)));
    }

    @Test
    void linesThatDoNotApplyAreLeftOut() {
        assertEquals(List.of(RestockText.STOPPED),
            StopLines.order(RestockText.STOPPED, null, null, List.of()));
        assertEquals(List.of(RestockText.STOPPED, RestockText.UNPACK_DRAINED),
            StopLines.order(RestockText.STOPPED, RestockText.UNPACK_DRAINED, null, List.of()));
        assertEquals(List.of(RestockText.STOPPED, RestockText.STOPPED_ALSO, RestockText.BORROWED_LEFT),
            StopLines.order(RestockText.STOPPED, null, RestockText.STOPPED_ALSO, List.of(RestockText.BORROWED_LEFT)));
    }
}
