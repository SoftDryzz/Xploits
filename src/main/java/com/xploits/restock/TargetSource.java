package com.xploits.restock;

import com.xploits.printer.core.GridBox;
import com.xploits.printer.core.Guards;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What restock counts (restock spec §3 "Litematica source"; the printer's seam I9): Litematica's selected placement in
 * the game, a built-in schematic in the bench. One source is one placement as it was when the source was made; when it
 * {@link #changed()}, the session makes a new one. Client thread only.
 */
public interface TargetSource {
    /** Why this placement cannot be counted; empty when it can. */
    Optional<Guards.Refusal> refusal();

    /** The selected placement is no longer the one this source was made from: count again with a new source. */
    boolean changed();

    /** The enabled sub-region boxes. */
    List<GridBox> boxes();

    /** The target state at {@code pos} (air included); null when unknown — unknown is never air. */
    BlockState target(BlockPos pos);

    /** Every block state of the enabled sub-regions with its count. */
    Map<BlockState, Long> wholeBuild();

    /** A source that only refuses: no Litematica, or one whose API differs from the one built against. */
    static TargetSource refusing(Guards.Refusal refusal) {
        return new TargetSource() {
            @Override
            public Optional<Guards.Refusal> refusal() {
                return Optional.of(refusal);
            }

            @Override
            public boolean changed() {
                return false;
            }

            @Override
            public List<GridBox> boxes() {
                return List.of();
            }

            @Override
            public BlockState target(BlockPos pos) {
                return null;
            }

            @Override
            public Map<BlockState, Long> wholeBuild() {
                return Map.of();
            }
        };
    }
}
