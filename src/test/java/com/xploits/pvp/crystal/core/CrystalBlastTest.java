package com.xploits.pvp.crystal.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.7.2: which blocks an end crystal's explosion can break, from vanilla's rule (yarn 1.21.11, read in the bytecode of
 * {@code ExplosionImpl.getBlocksToDestroy}): a ray starts at {@code 6.0f * (0.7f + nextFloat() * 0.6f)}, at most
 * 7.7999997 (the largest {@code nextFloat()} is 1 - 2^-24), and loses {@code (resistance + 0.3f) * 0.3f} in the block at
 * its first step, the explosion's own position, before it is checked. So the block breaks when {@code 7.7999997 -
 * (r + 0.3) * 0.3 > 0}: r below 7.8 / 0.3 - 0.3 = 25.7. Worked in float by hand: 25.699997 still breaks, 25.7 does not.
 * Every other step has lost at least 0.22500001 more, so the first is the most a crystal can ever do.
 */
class CrystalBlastTest {
    @Test
    void theLargestResistanceACrystalBreaksIsJustBelow25Point7() {
        assertTrue(CrystalBlast.canBreak(25.699997f), "the largest resistance that still breaks, in float");
        assertFalse(CrystalBlast.canBreak(25.7f), "25.7 survives the strongest ray");
        assertFalse(CrystalBlast.canBreak(Math.nextUp(25.7f)));
    }

    @Test
    void blocksACrystalBreaks() {
        // Blast resistances from the jar's Blocks: stone, cobblestone, deepslate, iron bars 6; end stone 9;
        // heavy core 10; netherrack 0.4; glass 0.3; air or a block with no resistance 0.
        for (float r : new float[] {6f, 9f, 10f, 0.4f, 0.3f, 0f}) assertTrue(CrystalBlast.canBreak(r), "resistance " + r);
    }

    @Test
    void blocksACrystalNeverBreaks() {
        // Obsidian, crying obsidian, the anvils, respawn anchor, netherite block, reinforced deepslate, ancient debris,
        // enchanting table 1200; ender chest 600; water and lava 100 (a waterlogged block takes the max with its fluid);
        // vault and trial spawner 50; bedrock 3600000.
        for (float r : new float[] {1200f, 600f, 100f, 50f, 3_600_000f}) {
            assertFalse(CrystalBlast.canBreak(r), "resistance " + r);
        }
    }

    @Test
    void aResistanceThatIsNotANumberCountsAsBreakable() {
        assertTrue(CrystalBlast.canBreak(Float.NaN), "not known: the cautious answer");
        assertFalse(CrystalBlast.canBreak(Float.POSITIVE_INFINITY));
        assertTrue(CrystalBlast.canBreak(Float.NEGATIVE_INFINITY));
    }
}
