package com.xploits.bench;

import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3i;

import java.util.List;

/**
 * Task A3: a 1x1 hole's four cardinal walls, obsidian, at the level of the feet block anchored at
 * {@code anchor} — the "1x1 obsidian hole, walls on four sides" geometry both {@code hole-standoff} and
 * {@code city} build (around the sparring's own anchor, and around F, our own feet block, respectively). Also
 * makes the floor beneath each wall obsidian, so a crystal placed into a mined-out gap (e.g. {@code
 * SurroundMiner}'s) always has a valid base to sit on, matching vanilla's own end-crystal placement rule
 * ({@code EndCrystalItem.useOnBlock}, obsidian or bedrock only, verified in task A2's report) — this floor
 * layer is unused by {@code hole-standoff} (its attack face-places on the wall block itself, one level up), but
 * harmless there too: it only replaces four columns of the arena's already blast-proof general floor.
 *
 * <p>Build-only: nothing to do on any tick.
 */
public final class HoleWalls implements FightBehaviour {
    private final Vec3i anchor;

    /** @param anchor the hole's centre, an offset from F (its own feet block, or F itself for our own hole) */
    public HoleWalls(Vec3i anchor) {
        this.anchor = anchor;
    }

    @Override
    public void build(Arena arena, ServerWorld world) {
        int x = anchor.getX();
        int y = anchor.getY();
        int z = anchor.getZ();
        for (Vec3i side : List.of(new Vec3i(x - 1, y, z), new Vec3i(x + 1, y, z),
            new Vec3i(x, y, z - 1), new Vec3i(x, y, z + 1))) {
            arena.fill(world, side.getX(), side.getY(), side.getZ(), side.getX(), side.getY(), side.getZ(), Blocks.OBSIDIAN);
            arena.fill(world, side.getX(), side.getY() - 1, side.getZ(), side.getX(), side.getY() - 1, side.getZ(),
                Blocks.OBSIDIAN);
        }
    }

    @Override
    public void tick(Sparring sparring, Script.Tick tick) {
    }
}
