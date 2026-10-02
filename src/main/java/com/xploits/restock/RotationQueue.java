package com.xploits.restock;

import meteordevelopment.meteorclient.utils.player.Rotations;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Meteor's global rotation queue, read by reflection (spike S6, VERIFIED: {@code private static final List<Rotation>
 * rotations} in {@code Rotations.java}): an accessor mixin would add a file to the package the bench's Meteor cache
 * hashes. A failed lookup is a refusal at enable naming the Meteor build, never a silent degrade.
 */
final class RotationQueue {
    private static final Field QUEUE = find();

    private RotationQueue() {
    }

    private static Field find() {
        try {
            Field f = Rotations.class.getDeclaredField("rotations");
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    static boolean readable() {
        return read() != null;
    }

    /** The queue, or null when it cannot be read. */
    static List<?> read() {
        if (QUEUE == null) return null;
        try {
            return QUEUE.get(null) instanceof List<?> list ? list : null;
        } catch (IllegalAccessException e) {
            return null;
        }
    }
}
