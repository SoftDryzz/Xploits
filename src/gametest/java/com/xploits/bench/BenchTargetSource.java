package com.xploits.bench;

import com.xploits.printer.core.GridBox;
import com.xploits.printer.core.Guards;
import com.xploits.printer.core.Pos;
import com.xploits.restock.TargetSource;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link BenchSchematic} placed at F as restock's {@link TargetSource}: never a refusal, never a change; unknown cells
 * read as unknown, a cell above the top layer as unknown too (restock counts every layer). Client thread only.
 */
final class BenchTargetSource implements TargetSource {
    private final BenchSchematic schematic;
    private final BlockPos origin;
    private final GridBox box;

    BenchTargetSource(BenchSchematic schematic, BlockPos origin) {
        this.schematic = schematic;
        this.origin = origin.toImmutable();
        this.box = GridBox.of(pos(origin.add(schematic.min())), pos(origin.add(schematic.max())));
    }

    @Override
    public Optional<Guards.Refusal> refusal() {
        return Optional.empty();
    }

    @Override
    public boolean changed() {
        return false;
    }

    @Override
    public List<GridBox> boxes() {
        return List.of(box);
    }

    @Override
    public BlockState target(BlockPos pos) {
        Vec3i o = offset(pos);
        if (!schematic.inside(o)) return null;
        Block block = schematic.target(o);
        return block == null ? null : block.getDefaultState();
    }

    @Override
    public Map<BlockState, Long> wholeBuild() {
        Map<BlockState, Long> counts = new HashMap<>();
        for (Vec3i cell : schematic.cells()) {
            if (!schematic.inside(cell)) continue;
            Block block = schematic.target(cell);
            if (block != null) counts.merge(block.getDefaultState(), 1L, Long::sum);
        }
        return counts;
    }

    private Vec3i offset(BlockPos pos) {
        return new Vec3i(pos.getX() - origin.getX(), pos.getY() - origin.getY(), pos.getZ() - origin.getZ());
    }

    private static Pos pos(BlockPos b) {
        return new Pos(b.getX(), b.getY(), b.getZ());
    }
}
