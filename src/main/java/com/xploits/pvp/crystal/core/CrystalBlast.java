package com.xploits.pvp.crystal.core;

/**
 * Which blocks an end crystal's explosion can break (0.7.2, crystal-aura++ under a roof: a roof a crystal can blow away
 * may be gone before the next crystal explodes, so it cannot be trusted to stop a jump).
 *
 * <p>Vanilla's rule, yarn 1.21.11, read in the jar's bytecode:
 * <ul>
 *   <li>An end crystal explodes with power 6 ({@code EndCrystalEntity.damage}: {@code createExplosion(this, source,
 *   null, x, y, z, 6.0f, false, BLOCK)}). The null behaviour becomes an {@code EntityExplosionBehavior} of the crystal,
 *   which overrides neither resistance hook, so a block's resistance is {@code ExplosionBehavior}'s: {@code
 *   max(block.getBlastResistance(), fluid.getBlastResistance())}, nothing for air with no fluid.</li>
 *   <li>{@code ExplosionImpl.getBlocksToDestroy}: each ray starts at {@code power * (0.7f + nextFloat() * 0.6f)}, at most
 *   {@code 7.7999997f} (the largest {@code nextFloat()} is {@code 1 - 2^-24}). At every step, the block there takes
 *   {@code (resistance + 0.3f) * 0.3f} off the ray, then breaks if what is left is above 0; the ray then moves 0.3 and
 *   loses {@code 0.22500001f}. The first step is the explosion's own position, so the most any block can face is the
 *   full ray less its own reduction.</li>
 * </ul>
 * So a crystal breaks a block only when {@code 7.7999997 - (r + 0.3) * 0.3 > 0}: r below {@code 7.8 / 0.3 - 0.3 =
 * 25.7}. In float, 25.699997 still breaks and 25.7 does not. Stone, cobblestone and deepslate (6), end stone (9),
 * netherrack (0.4) and glass (0.3) break; vault and trial spawner (50), water and lava (100), ender chest (600),
 * obsidian, crying obsidian, the anvils, respawn anchor and netherite block (1200) and bedrock (3600000) never do.
 */
public final class CrystalBlast {
    /** A block with this blast resistance or more survives even a crystal's strongest ray, at its own position. */
    public static final float BREAKS_BELOW = 25.7f;

    private CrystalBlast() {
    }

    /**
     * Whether an end crystal's explosion can break a block of this blast resistance (the block's and its fluid's, the
     * larger). A resistance that is not a number is not known, and counts as breakable: the cautious answer.
     */
    public static boolean canBreak(float blastResistance) {
        return !(blastResistance >= BREAKS_BELOW);
    }
}
