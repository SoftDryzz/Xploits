package com.xploits.bench;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.Vec3i;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/**
 * A built-in schematic for the bench (the seam that stands in for Litematica): one box of cells as offsets from F (the
 * player's feet block when the run starts), each with its target block — a cell not named is air — or unknown; and an
 * optional top layer (the cells above it are outside). Offsets only: the bench never prints a position.
 */
record BenchSchematic(Vec3i min, Vec3i max, Map<Vec3i, Block> blocks, Set<Vec3i> unknown, OptionalInt topLayer) {
    /** Five by two of stone, three blocks in front of F. */
    static final BenchSchematic WALL = filled(new Vec3i(-2, 0, 3), new Vec3i(2, 1, 3), Blocks.STONE);
    /** Three by two of stone, eight blocks in front of F. */
    static final BenchSchematic FAR_WALL = filled(new Vec3i(-1, 0, 8), new Vec3i(1, 1, 8), Blocks.STONE);
    /** Three stone blocks in a row, two in front of F. */
    static final BenchSchematic ROW = filled(new Vec3i(-1, 0, 2), new Vec3i(1, 0, 2), Blocks.STONE);
    /** The middle cell of {@link #ROW}. */
    static final Vec3i MIDDLE = new Vec3i(0, 0, 2);

    BenchSchematic {
        blocks = Map.copyOf(blocks);
        unknown = Set.copyOf(unknown);
    }

    /** Every cell between {@code a} and {@code b} (both included) set to {@code block}. */
    static BenchSchematic filled(Vec3i a, Vec3i b, Block block) {
        Vec3i lo = new Vec3i(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        Vec3i hi = new Vec3i(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        Map<Vec3i, Block> all = new HashMap<>();
        for (int x = lo.getX(); x <= hi.getX(); x++) {
            for (int y = lo.getY(); y <= hi.getY(); y++) {
                for (int z = lo.getZ(); z <= hi.getZ(); z++) all.put(new Vec3i(x, y, z), block);
            }
        }
        return new BenchSchematic(lo, hi, all, Set.of(), OptionalInt.empty());
    }

    /** This schematic with {@code cell} set to {@code block} (air included); the box grows to hold it, new cells air. */
    BenchSchematic with(Vec3i cell, Block block) {
        Map<Vec3i, Block> all = new HashMap<>(blocks);
        all.put(cell, block);
        Set<Vec3i> stillUnknown = new HashSet<>(unknown);
        stillUnknown.remove(cell);
        return new BenchSchematic(lower(min, cell), upper(max, cell), all, stillUnknown, topLayer);
    }

    /** This schematic with {@code cell} unknown, as Litematica leaves a part it has not written yet. */
    BenchSchematic unknownAt(Vec3i cell) {
        Map<Vec3i, Block> all = new HashMap<>(blocks);
        all.remove(cell);
        Set<Vec3i> nowUnknown = new HashSet<>(unknown);
        nowUnknown.add(cell);
        return new BenchSchematic(lower(min, cell), upper(max, cell), all, nowUnknown, topLayer);
    }

    /** This schematic with every cell above height {@code dy} outside. */
    BenchSchematic layersUpTo(int dy) {
        return new BenchSchematic(min, max, blocks, unknown, OptionalInt.of(dy));
    }

    /** Every cell of the box. */
    List<Vec3i> cells() {
        List<Vec3i> all = new ArrayList<>();
        for (int y = min.getY(); y <= max.getY(); y++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                for (int x = min.getX(); x <= max.getX(); x++) all.add(new Vec3i(x, y, z));
            }
        }
        return all;
    }

    boolean inBox(Vec3i c) {
        return c.getX() >= min.getX() && c.getX() <= max.getX() && c.getY() >= min.getY() && c.getY() <= max.getY()
            && c.getZ() >= min.getZ() && c.getZ() <= max.getZ();
    }

    /** In the box and not above the top layer. */
    boolean inside(Vec3i c) {
        return inBox(c) && (topLayer.isEmpty() || c.getY() <= topLayer.getAsInt());
    }

    /** The target block of a cell of the box: air when not named; null when unknown. */
    Block target(Vec3i c) {
        if (unknown.contains(c)) return null;
        return blocks.getOrDefault(c, Blocks.AIR);
    }

    private static Vec3i lower(Vec3i a, Vec3i b) {
        return new Vec3i(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
    }

    private static Vec3i upper(Vec3i a, Vec3i b) {
        return new Vec3i(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
    }
}
