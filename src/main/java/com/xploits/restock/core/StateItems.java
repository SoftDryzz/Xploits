package com.xploits.restock.core;

import java.util.List;
import java.util.Map;

/**
 * How many items one block state of the schematic consumes (restock spec §3 "Counting": fetch more, never less). The
 * adapter passes the id of the item that places the block ({@code Block.asItem()}, {@link #NO_ITEM} when none) and the
 * state's properties as Minecraft names them. Names and values read in the 1.21.11 yarn jar ({@code Properties},
 * {@code SlabType}, {@code DoubleBlockHalf}, {@code BedPart}): only slabs say {@code type=double}; only two-block things
 * (doors, tall plants, dripleaf) say {@code half=upper} (stairs and trapdoors say {@code top}); only beds have a
 * {@code part}.
 */
public final class StateItems {
    /** The item id of a block no item places (air, fluids, piston heads, fire). */
    public static final String NO_ITEM = "minecraft:air";

    /** Integer properties whose value is the number of items the block holds. */
    private static final List<String> COUNTED = List.of("candles", "pickles", "layers", "eggs", "flower_amount",
        "segment_amount");

    private StateItems() {
    }

    public static int count(String item, Map<String, String> properties) {
        if (item.equals(NO_ITEM)) return 0;
        if ("double".equals(properties.get("type"))) return 2;
        if ("upper".equals(properties.get("half"))) return 0;
        if ("head".equals(properties.get("part"))) return 0;
        for (String name : COUNTED) {
            String value = properties.get(name);
            if (value == null) continue;
            try {
                return Math.max(1, Integer.parseInt(value));
            } catch (NumberFormatException e) {
                return 1;
            }
        }
        return 1;
    }
}
