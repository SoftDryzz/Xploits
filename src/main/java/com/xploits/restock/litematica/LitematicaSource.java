package com.xploits.restock.litematica;

import com.xploits.printer.core.BuildIndex;
import com.xploits.printer.core.GridBox;
import com.xploits.printer.core.Guards;
import com.xploits.printer.core.Pos;
import com.xploits.restock.TargetSource;
import com.xploits.restock.core.PlacementWatch;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.ILitematicaBlockStatePalette;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.world.ChunkSchematic;
import fi.dy.masa.litematica.world.ChunkSchematicState;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Litematica's selected placement as what restock counts (restock spec §3). A position is known only while main rendering
 * is on and its schematic chunk is at least {@code FILLED}; the state is read from that same chunk object, so a rebuild
 * that swaps the chunk in between cannot hand out its empty placeholder as air (spike S2). The whole build's counts come
 * from the regions' own block counts, so they never wait for a chunk to load.
 */
final class LitematicaSource implements TargetSource {
    private final List<GridBox> boxes;
    private final PlacementWatch watch;
    private final Guards.Refusal refusal;
    private final Map<BlockState, Long> wholeBuild;

    LitematicaSource(long maxVolume) {
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        SchematicPlacement selected = manager.getSelectedSchematicPlacement();
        PlacementWatch.View view = view(manager, selected);
        Optional<Guards.Reason> refused = PlacementWatch.refusal(view, maxVolume);
        Guards.Refusal problem = refused.map(r -> new Guards.Refusal(r, r == Guards.Reason.PLACEMENT_TOO_LARGE
            ? String.valueOf(BuildIndex.volume(List.copyOf(view.regions().values()))) : "")).orElseGet(LitematicaSource::easyPlace);
        this.refusal = problem;
        this.watch = problem == null ? new PlacementWatch(view) : null;
        this.boxes = problem == null ? List.copyOf(view.regions().values()) : List.of();
        this.wholeBuild = problem == null ? count(selected, view.regions().keySet()) : Map.of();
    }

    @Override
    public Optional<Guards.Refusal> refusal() {
        return Optional.ofNullable(refusal);
    }

    @Override
    public boolean changed() {
        if (watch == null) return false;
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        return watch.changed(view(manager, manager.getSelectedSchematicPlacement()));
    }

    @Override
    public List<GridBox> boxes() {
        return boxes;
    }

    @Override
    public BlockState target(BlockPos pos) {
        if (!Configs.Visuals.ENABLE_RENDERING.getBooleanValue()) return null;
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        if (world == null) return null;
        ChunkSchematic chunk = world.getChunkSource().getChunkIfExists(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null || !chunk.getState().atLeast(ChunkSchematicState.FILLED)) return null;
        return chunk.getBlockState(pos);
    }

    @Override
    public Map<BlockState, Long> wholeBuild() {
        return wholeBuild;
    }

    /** The selected placement's fingerprint; null when none is selected. */
    static PlacementWatch.View view(SchematicPlacementManager manager, SchematicPlacement selected) {
        if (selected == null) return null;
        Map<String, GridBox> regions = new TreeMap<>();
        selected.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).forEach((name, box) -> {
            GridBox grid = grid(box);
            if (grid != null) regions.put(name, grid);
        });
        List<PlacementWatch.Other> others = new ArrayList<>();
        for (SchematicPlacement p : manager.getAllSchematicsPlacements()) {
            if (p == selected) continue;
            GridBox enclosing = grid(p.getEclosingBox());
            if (enclosing != null) others.add(new PlacementWatch.Other(System.identityHashCode(p), p.isEnabled(), enclosing));
        }
        BlockPos origin = selected.getOrigin();
        return new PlacementWatch.View(System.identityHashCode(selected), selected.isEnabled(),
            new Pos(origin.getX(), origin.getY(), origin.getZ()), selected.getRotation().name(),
            selected.getMirror().name(), regions, others);
    }

    private static GridBox grid(Box box) {
        if (box == null || box.getPos1() == null || box.getPos2() == null) return null;
        BlockPos a = box.getPos1();
        BlockPos b = box.getPos2();
        return GridBox.of(new Pos(a.getX(), a.getY(), a.getZ()), new Pos(b.getX(), b.getY(), b.getZ()));
    }

    /** Easy Place's restriction cancels clicks from the camera's crosshair, a container's included (the printer's I1, N-M4). */
    static Guards.Refusal easyPlace() {
        if (Configs.Generic.EASY_PLACE_MODE.getBooleanValue() && Configs.Generic.EASY_PLACE_POST_REWRITE.getBooleanValue()
            && Configs.Generic.PLACEMENT_RESTRICTION.getBooleanValue()) {
            return new Guards.Refusal(Guards.Reason.EASY_PLACE_RESTRICTION, "");
        }
        return null;
    }

    /** Every block state of the enabled sub-regions with its count, from the regions' own block counts. */
    private static Map<BlockState, Long> count(SchematicPlacement placement, Set<String> regions) {
        Map<BlockState, Long> m = new HashMap<>();
        LitematicaSchematic schematic = placement.getSchematic();
        for (String name : regions) {
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(name);
            if (container == null) continue;
            long[] counts = container.getBlockCounts();
            ILitematicaBlockStatePalette palette = container.getPalette();
            int n = Math.min(counts.length, palette.getPaletteSize());
            for (int i = 0; i < n; i++) {
                BlockState state = palette.getBlockState(i);
                if (state != null && counts[i] > 0) m.merge(state, counts[i], Long::sum);
            }
        }
        return Map.copyOf(m);
    }
}
