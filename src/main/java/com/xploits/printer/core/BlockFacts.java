package com.xploits.printer.core;

import java.util.Objects;

/**
 * What the adapter measures of one block state (printer spec §4 {@code BuildSnapshot}): its registry id, the id of the
 * item that places it ({@code "minecraft:air"} when none), and the facts no table can know.
 *
 * @param propertyFree  the state has no properties at all
 * @param itemPlacesIt  the block's item is a block item for this very block
 * @param fullCube      its collision shape is a full cube
 * @param falling       a falling block ({@code Falling})
 * @param waterloggable implements {@code Waterloggable}
 * @param replaceable   Minecraft's own {@code isReplaceable()}
 * @param fluid         its fluid state is not empty (a fluid, or a waterlogged block)
 * @param unbreakable   hardness below 0
 */
public record BlockFacts(String id, String item, boolean air, boolean propertyFree, boolean itemPlacesIt,
                         boolean fullCube, boolean blockEntity, boolean falling, boolean waterloggable,
                         boolean replaceable, boolean fluid, boolean unbreakable) {
    public static final BlockFacts AIR = new BlockFacts("minecraft:air", "minecraft:air", true, true, false, false,
        false, false, false, true, false, false);

    public BlockFacts {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(item, "item");
        if (id.isBlank()) throw new IllegalArgumentException("a block needs an id");
    }
}
