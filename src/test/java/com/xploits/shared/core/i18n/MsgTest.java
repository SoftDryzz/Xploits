package com.xploits.shared.core.i18n;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MsgTest {
    enum K implements MessageKey {
        NO_FIREWORKS, HELLO;

        @Override
        public String area() {
            return "test";
        }
    }

    @Test
    void idIsAreaDotKebabName() {
        assertEquals("test.no-fireworks", K.NO_FIREWORKS.id());
    }

    @Test
    void ofPairsNamesAndValues() {
        Msg m = Msg.of(K.NO_FIREWORKS, "needed", 12, "have", 3);
        assertEquals(Map.of("needed", 12, "have", 3), m.args());
    }

    @Test
    void equalityIsKeyAndArgs() {
        assertEquals(Msg.of(K.HELLO, "a", 1), Msg.of(K.HELLO, "a", 1));
        assertNotEquals(Msg.of(K.HELLO, "a", 1), Msg.of(K.HELLO, "a", 2));
        assertNotEquals(Msg.of(K.HELLO), Msg.of(K.NO_FIREWORKS));
    }

    @Test
    void rejectsProgrammingMistakes() {
        assertThrows(IllegalArgumentException.class, () -> Msg.of(K.HELLO, "a"));
        assertThrows(IllegalArgumentException.class, () -> Msg.of(K.HELLO, 1, 2));
        assertThrows(NullPointerException.class, () -> Msg.of(K.HELLO, "a", null));
        assertThrows(NullPointerException.class, () -> Msg.of(null));
        assertThrows(IllegalArgumentException.class, () -> Msg.of(K.HELLO, "a", 1, "a", 2));
    }
}
