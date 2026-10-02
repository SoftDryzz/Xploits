package com.xploits.bench;

import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.world.ChunkSchematic;
import fi.dy.masa.litematica.world.ChunkSchematicState;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;

/**
 * The bench's door to Litematica (restock spec §6), loaded only in the Litematica profile. It makes the tiny
 * schematic a CHECK needs from a {@link BenchSchematic}, in memory — never a file copied from an instance — places it
 * enabled and rendered, selects it, moves it, and reads Litematica's schematic world back. The same calls spike S1/S2 ran
 * in this gametest. Client thread only.
 */
final class BenchLitematica {
    private BenchLitematica() {
    }

    /** {@code schematic} placed with its lowest corner at F + {@code schematic.min()}; every cell must be known. */
    static SchematicPlacement place(BenchSchematic schematic, BlockPos origin) {
        BlockPos min = origin.add(schematic.min());
        AreaSelection selection = new AreaSelection();
        selection.setName("bench");
        String region = selection.createNewSubRegionBox(min, "bench");
        selection.getSubRegionBox(region).setPos2(origin.add(schematic.max()));
        selection.setExplicitOrigin(min);
        LitematicaSchematic made = LitematicaSchematic.createEmptySchematic(selection, "bench");
        LitematicaBlockStateContainer container = made.getSubRegionContainer(region);
        for (Vec3i cell : schematic.cells()) {
            Block block = schematic.target(cell);
            if (block == null) throw new BenchException("a Litematica schematic has no unknown cells");
            container.set(cell.getX() - schematic.min().getX(), cell.getY() - schematic.min().getY(),
                cell.getZ() - schematic.min().getZ(), block.getDefaultState());
        }
        SchematicPlacement placement = SchematicPlacement.createFor(made, min, "bench", true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
        DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(placement);
        return placement;
    }

    static void remove(SchematicPlacement placement) {
        DataManager.getSchematicPlacementManager().removeSchematicPlacement(placement);
    }

    /** Moves the placement's origin (its lowest corner) to {@code min}. */
    static void moveTo(SchematicPlacement placement, BlockPos min) {
        placement.setOrigin(min, message -> {
        });
    }

    /** Easy Place with its post-rewrite and the placement restriction, all three on or all three off (spike S2's fields). */
    static void easyPlaceRestriction(boolean on) {
        Configs.Generic.EASY_PLACE_MODE.setBooleanValue(on);
        Configs.Generic.EASY_PLACE_POST_REWRITE.setBooleanValue(on);
        Configs.Generic.PLACEMENT_RESTRICTION.setBooleanValue(on);
    }

    /** Litematica's main rendering, as its config screen switches it (spike S2: the schematic world stays written). */
    static void rendering(boolean on) {
        Configs.Visuals.ENABLE_RENDERING.setBooleanValue(on);
    }

    /** What Litematica's schematic world holds at {@code pos} once its chunk is written ({@code FILLED}); null before. */
    static BlockState written(BlockPos pos) {
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        if (world == null) return null;
        ChunkSchematic chunk = world.getChunkSource().getChunkIfExists(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null || !chunk.getState().atLeast(ChunkSchematicState.FILLED)) return null;
        return chunk.getBlockState(pos);
    }
}
