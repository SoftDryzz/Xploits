package com.xploits.stash.core;

import com.xploits.shared.core.i18n.Msg;

/**
 * The identity of a container (spec §4.2). The ender chest has a key of its own because there is
 * only one and it travels with you; double chests always use the lesser of their two positions, so
 * that both halves give the same key.
 */
public record ContainerKey(String dimension, int x, int y, int z) {
    /** The ender chest's single key: it is not at any coordinates. */
    public static final ContainerKey ENDER = new ContainerKey("", Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);

    public static ContainerKey block(String dimension, int x, int y, int z) {
        return new ContainerKey(dimension, x, y, z);
    }

    /** Returns the canonical key of a double chest: the lesser position by x, then y, then z. */
    public static ContainerKey doubleChest(String dimension, int x1, int y1, int z1, int x2, int y2, int z2) {
        if (x1 != x2) return x1 < x2 ? block(dimension, x1, y1, z1) : block(dimension, x2, y2, z2);
        if (y1 != y2) return y1 < y2 ? block(dimension, x1, y1, z1) : block(dimension, x2, y2, z2);
        return z1 <= z2 ? block(dimension, x1, y1, z1) : block(dimension, x2, y2, z2);
    }

    public boolean isEnder() {
        return equals(ENDER);
    }

    /** A stable form, to use as the key in JSON and to read at a glance in notices. */
    public String id() {
        return isEnder() ? "ender" : dimension + "@" + x + "," + y + "," + z;
    }

    /**
     * Where it is, without coordinates (console spec §7): the dimension and, if the player is in the
     * same one, how many blocks away on the plane. For the console and its file, which cannot carry
     * positions.
     */
    public Msg withoutPosition(String currentDimension, Double playerX, Double playerZ) {
        if (isEnder()) return Msg.of(StashText.WHERE_ENDER);
        String shortName = dimension.startsWith("minecraft:") ? dimension.substring("minecraft:".length()) : dimension;
        if (playerX == null || playerZ == null || !dimension.equals(currentDimension)) return Msg.of(StashText.WHERE_DIMENSION, "dimension", shortName);
        double dx = x - playerX;
        double dz = z - playerZ;
        return Msg.of(StashText.WHERE_DISTANCE, "dimension", shortName, "blocks", Math.round(Math.sqrt(dx * dx + dz * dz)));
    }
}
