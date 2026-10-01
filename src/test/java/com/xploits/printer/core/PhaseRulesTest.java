package com.xploits.printer.core;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import static com.xploits.printer.core.Fixtures.air;
import static com.xploits.printer.core.Fixtures.block;
import static com.xploits.printer.core.Fixtures.cell;
import static com.xploits.printer.core.Fixtures.outside;
import static com.xploits.printer.core.Fixtures.stone;
import static com.xploits.printer.core.Fixtures.water;
import static com.xploits.printer.core.PhaseRules.Contents;
import static com.xploits.printer.core.PhaseRules.NeverBreak;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Printer spec §5.1: the phase-1 allow-list, matches, wrong and never-break, and the tables of item 4b. */
class PhaseRulesTest {
    private static Target want(BlockFacts b) {
        return Target.of(b);
    }

    @Test
    void phaseOneTakesPlainFullBlocksAndDyedCarpets() {
        assertTrue(PhaseRules.phaseOne(stone()));
        assertTrue(PhaseRules.phaseOne(block("glass").build()));
        assertTrue(PhaseRules.phaseOne(block("waxed_copper_block").build()));
        assertTrue(PhaseRules.phaseOne(block("fletching_table").build()));
        assertTrue(PhaseRules.phaseOne(block("red_carpet").notFull().build()));
    }

    @Test
    void phaseOneLeavesEverythingElseForLater() {
        assertFalse(PhaseRules.phaseOne(block("oak_stairs").props().notFull().build()), "properties");
        assertFalse(PhaseRules.phaseOne(block("crafting_table").build()), "interactive");
        assertFalse(PhaseRules.phaseOne(block("barrel").props().blockEntity().build()), "block entity");
        assertFalse(PhaseRules.phaseOne(block("spawner").blockEntity().build()), "block entity alone");
        assertFalse(PhaseRules.phaseOne(block("sand").falling().build()), "falling");
        assertFalse(PhaseRules.phaseOne(block("dirt").build()), "changes by itself");
        assertFalse(PhaseRules.phaseOne(block("copper_block").build()), "oxidises");
        assertFalse(PhaseRules.phaseOne(block("lime_concrete_powder").build()), "turns into concrete");
        assertFalse(PhaseRules.phaseOne(block("moss_carpet").notFull().build()), "not a dyed carpet, not full");
        assertFalse(PhaseRules.phaseOne(block("short_grass").notFull().replaceable().build()), "replaceable");
        assertFalse(PhaseRules.phaseOne(block("barrier").noItem().build()), "its item does not place it");
        assertFalse(PhaseRules.phaseOne(block("copper_grate").waterloggable().build()), "waterloggable");
        assertFalse(PhaseRules.phaseOne(water()), "fluid");
        assertFalse(PhaseRules.phaseOne(air()), "air");
    }

    /**
     * Ruling P5: each of these differs from a valid phase-1 block (a plain full block) in exactly one condition, so
     * dropping that one condition from {@code phaseOne} turns exactly its assert red.
     */
    @Test
    void eachPhaseOneConditionIsHeldByABlockThatFailsOnlyIt() {
        assertTrue(PhaseRules.phaseOne(block("plain_full_block").build()), "the control passes");
        assertFalse(PhaseRules.phaseOne(block("oak_log").props().build()), "a full cube with properties only");
        BlockFacts airOnly = new BlockFacts("minecraft:plain_full_block", "minecraft:plain_full_block", true, true, true,
            true, false, false, false, false, false, false);
        assertFalse(PhaseRules.phaseOne(airOnly), "air only");
        assertFalse(PhaseRules.phaseOne(block("plain_full_block").fluid().build()), "a fluid only");
        assertFalse(PhaseRules.phaseOne(block("plain_full_block").replaceable().build()), "replaceable only");
    }

    @Test
    void matchesComparesTheBlockOnlyNeverItsWorldSetProperties() {
        // The adapter gives both leaves the same id whatever their distance/persistent values.
        assertTrue(PhaseRules.matches(block("oak_leaves").props().build(), block("oak_leaves").props().build()));
        assertFalse(PhaseRules.matches(stone(), block("cobblestone").build()));
        // The same waterloggable block, dry in the schematic and wet in the world: same id, so a match.
        assertTrue(PhaseRules.matches(block("copper_grate").props().waterloggable().build(),
            block("copper_grate").props().waterloggable().fluid().build()), "id only");
        assertTrue(PhaseRules.ignoredProperty("snowy"));
        assertTrue(PhaseRules.ignoredProperty("distance"));
        assertTrue(PhaseRules.ignoredProperty("note"));
        assertTrue(PhaseRules.ignoredProperty("north"));
        assertFalse(PhaseRules.ignoredProperty("facing"));
        assertFalse(PhaseRules.ignoredProperty("half"));
    }

    @Test
    void placingIntoAPositionNeedsTheTableAndTheWorldToAgree() {
        assertTrue(PhaseRules.placeableInto(air()));
        assertTrue(PhaseRules.placeableInto(water()));
        assertTrue(PhaseRules.placeableInto(block("short_dry_grass").notFull().replaceable().build()));
        // Minecraft says replaceable, the table does not know it: broken first, never placed into.
        assertFalse(PhaseRules.placeableInto(block("modded_grass").notFull().replaceable().build()));
        // The table says replaceable, Minecraft does not: the same.
        assertFalse(PhaseRules.placeableInto(block("vine").notFull().build()));
        assertFalse(PhaseRules.placeableInto(stone()));
    }

    @Test
    void aSupportIsSolidUnlessEitherSourceSaysReplaceable() {
        assertTrue(PhaseRules.support(stone()));
        assertTrue(PhaseRules.support(block("oak_slab").props().notFull().build()));
        assertFalse(PhaseRules.support(air()));
        assertFalse(PhaseRules.support(water()));
        assertFalse(PhaseRules.support(block("short_grass").notFull().replaceable().build()));
        assertFalse(PhaseRules.support(block("modded_grass").notFull().replaceable().build()), "clicking it would place into it");
        assertFalse(PhaseRules.support(block("vine").notFull().build()), "in the replaceable table");
        assertFalse(PhaseRules.support(block("crafting_table").build()), "interactive");
        assertFalse(PhaseRules.support(block("oak_door").props().notFull().build()), "interactive by suffix");
        assertFalse(PhaseRules.support(block("waterlogged_thing").props().fluid().build()), "holds a fluid");
    }

    @Test
    void theTablesHoldTheirFamilies() {
        assertTrue(PhaseRules.interactive("minecraft:chest"));
        assertTrue(PhaseRules.interactive("minecraft:trapped_chest"));
        assertTrue(PhaseRules.interactive("minecraft:oxidized_copper_chest"));
        assertTrue(PhaseRules.interactive("minecraft:chipped_anvil"));
        assertTrue(PhaseRules.interactive("minecraft:red_shulker_box"));
        assertTrue(PhaseRules.interactive("minecraft:potted_poppy"));
        assertTrue(PhaseRules.interactive("minecraft:note_block"));
        assertFalse(PhaseRules.interactive("minecraft:stone"));
        assertFalse(PhaseRules.interactive("minecraft:fletching_table"));
        assertTrue(PhaseRules.changesByItself("minecraft:dirt"));
        assertTrue(PhaseRules.changesByItself("minecraft:weathered_cut_copper"));
        assertTrue(PhaseRules.changesByItself("minecraft:brain_coral_block"));
        assertTrue(PhaseRules.changesByItself("minecraft:ice"));
        assertFalse(PhaseRules.changesByItself("minecraft:packed_ice"));
        assertFalse(PhaseRules.changesByItself("minecraft:waxed_weathered_cut_copper"));
        assertTrue(PhaseRules.carpet("minecraft:light_blue_carpet"));
        assertFalse(PhaseRules.carpet("minecraft:moss_carpet"));
        assertTrue(PhaseRules.naturalConversion("minecraft:copper_block", "minecraft:oxidized_copper"));
        assertTrue(PhaseRules.naturalConversion("minecraft:exposed_chiseled_copper", "minecraft:weathered_chiseled_copper"));
        assertFalse(PhaseRules.naturalConversion("minecraft:oxidized_copper", "minecraft:copper_block"), "only forwards");
        assertTrue(PhaseRules.naturalConversion("minecraft:fire_coral_block", "minecraft:dead_fire_coral_block"));
        assertTrue(PhaseRules.naturalConversion("minecraft:black_concrete_powder", "minecraft:black_concrete"));
        assertTrue(PhaseRules.naturalConversion("minecraft:dirt", "minecraft:grass_block"));
        assertTrue(PhaseRules.naturalConversion("minecraft:mud", "minecraft:clay"));
        assertFalse(PhaseRules.naturalConversion("minecraft:stone", "minecraft:cobblestone"));
        assertTrue(PhaseRules.needsSupportBelow("minecraft:torch"));
        assertTrue(PhaseRules.needsSupportBelow("minecraft:pink_carpet"));
        assertTrue(PhaseRules.needsSupportBelow("minecraft:oak_pressure_plate"));
        assertFalse(PhaseRules.needsSupportBelow("minecraft:stone"));
        assertTrue(PhaseRules.hangsOnSide("minecraft:wall_torch"));
        assertTrue(PhaseRules.hangsOnSide("minecraft:ladder"));
        assertTrue(PhaseRules.hangsOnSide("minecraft:oak_wall_sign"));
        assertFalse(PhaseRules.hangsOnSide("minecraft:torch"));
        assertTrue(PhaseRules.hangsBelow("minecraft:lantern"));
        assertTrue(PhaseRules.hangsBelow("minecraft:weathered_copper_lantern"));
        assertTrue(PhaseRules.hangsBelow("minecraft:pointed_dripstone"));
        assertFalse(PhaseRules.hangsBelow("minecraft:stone"));
    }

    @Test
    void classifyFollowsItems2To3a() {
        assertEquals(Contents.OUTSIDE, PhaseRules.classify(outside(0, 0, 0, block("cobblestone").build())));
        assertEquals(Contents.UNKNOWN, PhaseRules.classify(cell(0, 0, 0, Target.UNKNOWN, block("cobblestone").build())));
        assertEquals(Contents.AIR_TARGET, PhaseRules.classify(cell(0, 0, 0, Target.AIR, block("cobblestone").build())));
        assertEquals(Contents.LATER, PhaseRules.classify(cell(0, 0, 0, want(block("oak_stairs").props().notFull().build()), air())));
        assertEquals(Contents.LATER, PhaseRules.classify(cell(0, 0, 0, want(block("dirt").build()), block("grass_block").props().build())));
        assertEquals(Contents.MATCHES, PhaseRules.classify(cell(0, 0, 0, want(stone()), stone())));
        assertEquals(Contents.CONVERTED, PhaseRules.classify(cell(0, 0, 0, want(block("mud").build()), block("clay").build())));
        assertEquals(Contents.MISSING, PhaseRules.classify(cell(0, 0, 0, want(stone()), air())));
        assertEquals(Contents.MISSING, PhaseRules.classify(cell(0, 0, 0, want(stone()), water())));
        assertEquals(Contents.MISSING, PhaseRules.classify(cell(0, 0, 0, want(stone()), block("snow").props().notFull().replaceable().build())));
        assertEquals(Contents.DIFFERENT, PhaseRules.classify(cell(0, 0, 0, want(stone()), block("cobblestone").build())));
        assertEquals(Contents.DIFFERENT, PhaseRules.classify(cell(0, 0, 0, want(stone()), block("modded_grass").notFull().replaceable().build())));
    }

    /** Six neighbours of stone, with {@code face} replaced. */
    private static Map<Face, BlockFacts> around(Face face, BlockFacts other) {
        Map<Face, BlockFacts> n = new EnumMap<>(Face.class);
        for (Face f : Face.values()) n.put(f, f == face ? other : stone());
        return n;
    }

    private static PhaseRules.BreakView view(Cell cell, Map<Face, BlockFacts> neighbours, boolean standingOn,
                                             boolean brokenBefore, int ticks) {
        return new PhaseRules.BreakView(cell, neighbours, standingOn, false, brokenBefore, ticks, 100);
    }

    private static PhaseRules.BreakView viewWithHangingEntity(Cell cell, Map<Face, BlockFacts> neighbours) {
        return new PhaseRules.BreakView(cell, neighbours, false, true, false, 23, 100);
    }

    private static final Cell WRONG = cell(0, 0, 0, Target.of(stone()), block("cobblestone").build());

    private static Optional<NeverBreak> why(PhaseRules.BreakView v) {
        return PhaseRules.neverBreak(v);
    }

    @Test
    void aWrongBlockWithNothingAgainstItMayBeBroken() {
        assertEquals(Optional.empty(), why(view(WRONG, around(Face.UP, stone()), false, false, 23)));
    }

    @Test
    void neverBreakNamesTheFirstRuleThatHolds() {
        assertEquals(Optional.of(NeverBreak.OUTSIDE), why(view(outside(0, 0, 0, block("cobblestone").build()), around(Face.UP, stone()), false, false, 23)));
        assertEquals(Optional.of(NeverBreak.UNKNOWN), why(view(cell(0, 0, 0, Target.UNKNOWN, block("cobblestone").build()), around(Face.UP, stone()), false, false, 23)));
        assertEquals(Optional.of(NeverBreak.AIR_TARGET), why(view(cell(0, 0, 0, Target.AIR, block("cobblestone").build()), around(Face.UP, stone()), false, false, 23)));
        assertEquals(Optional.of(NeverBreak.NOT_DIFFERENT), why(view(cell(0, 0, 0, Target.of(stone()), stone()), around(Face.UP, stone()), false, false, 23)));
        assertEquals(Optional.of(NeverBreak.NOT_DIFFERENT), why(view(cell(0, 0, 0, Target.of(block("oak_stairs").props().notFull().build()), block("cobblestone").build()), around(Face.UP, stone()), false, false, 23)), "a later-phase target is never wrong");
        Cell chest = cell(0, 0, 0, Target.of(stone()), block("chest").props().blockEntity().build());
        assertEquals(Optional.of(NeverBreak.BLOCK_ENTITY), why(view(chest, around(Face.UP, stone()), false, false, 23)));
        Cell bedrock = cell(0, 0, 0, Target.of(stone()), block("bedrock").unbreakable().build());
        assertEquals(Optional.of(NeverBreak.UNBREAKABLE), why(view(bedrock, around(Face.UP, stone()), false, false, 23)));
        Map<Face, BlockFacts> five = around(Face.UP, stone());
        five.remove(Face.NORTH);
        assertEquals(Optional.of(NeverBreak.NEIGHBOURS_UNKNOWN), why(view(WRONG, five, false, false, 23)));
        assertEquals(Optional.of(NeverBreak.STANDING_ON), why(view(WRONG, around(Face.UP, stone()), true, false, 23)));
        assertEquals(Optional.of(NeverBreak.HOLDS_FALLING), why(view(WRONG, around(Face.UP, block("sand").falling().build()), false, false, 23)));
        assertEquals(Optional.of(NeverBreak.HOLDS_ATTACHED), why(view(WRONG, around(Face.UP, block("torch").notFull().build()), false, false, 23)));
        assertEquals(Optional.of(NeverBreak.HOLDS_ATTACHED), why(view(WRONG, around(Face.EAST, block("ladder").props().notFull().build()), false, false, 23)));
        assertEquals(Optional.of(NeverBreak.HOLDS_ATTACHED), why(view(WRONG, around(Face.DOWN, block("lantern").props().notFull().build()), false, false, 23)));
        assertEquals(Optional.of(NeverBreak.NEXT_TO_FLUID), why(view(WRONG, around(Face.WEST, water()), false, false, 23)));
        Cell soaked = cell(0, 0, 0, Target.of(stone()), block("cobblestone_slab").props().notFull().fluid().build());
        assertEquals(Optional.of(NeverBreak.NEXT_TO_FLUID), why(view(soaked, around(Face.UP, stone()), false, false, 23)), "holds a fluid itself");
        assertEquals(Optional.of(NeverBreak.TOO_SLOW), why(view(WRONG, around(Face.UP, stone()), false, false, 101)));
        assertEquals(Optional.of(NeverBreak.TOO_SLOW), why(view(WRONG, around(Face.UP, stone()), false, false, -1)), "no tool at all");
        assertEquals(Optional.empty(), why(view(WRONG, around(Face.UP, stone()), false, false, 100)), "the cap itself qualifies");
        assertEquals(Optional.of(NeverBreak.ALREADY_BROKEN), why(view(WRONG, around(Face.UP, stone()), false, true, 23)));
    }

    @Test
    void theBlockAboveIsProtectedWhateverFamilyItStandsIn() {
        for (String id : new String[]{"lantern", "soul_lantern", "copper_lantern", "waxed_oxidized_copper_lantern", "bell",
            "amethyst_cluster", "small_amethyst_bud", "medium_amethyst_bud", "large_amethyst_bud", "glow_lichen",
            "sculk_vein", "resin_clump", "mangrove_propagule", "twisting_vines", "twisting_vines_plant",
            "candle_cake", "red_candle_cake", "chorus_plant", "chorus_flower"}) {
            assertEquals(Optional.of(NeverBreak.HOLDS_ATTACHED),
                why(view(WRONG, around(Face.UP, block(id).props().notFull().build()), false, false, 23)), id + " above");
            assertTrue(PhaseRules.needsSupportBelow("minecraft:" + id), id + " in the table");
        }
        // The hanging and side tables are consulted for the block above too (over-protective on purpose).
        assertEquals(Optional.of(NeverBreak.HOLDS_ATTACHED), why(view(WRONG, around(Face.UP, block("ladder").props().notFull().build()), false, false, 23)));
        assertEquals(Optional.of(NeverBreak.HOLDS_ATTACHED), why(view(WRONG, around(Face.UP, block("pointed_dripstone").props().notFull().build()), false, false, 23)));
    }

    @Test
    void redstoneWireAndCopperGolemStatuesAreInteractiveSoNeverASupport() {
        for (String id : new String[]{"minecraft:redstone_wire", "minecraft:copper_golem_statue",
            "minecraft:waxed_weathered_copper_golem_statue", "minecraft:oxidized_copper_golem_statue"}) {
            assertTrue(PhaseRules.interactive(id), id);
            assertFalse(PhaseRules.support(block(id).props().notFull().build()), id);
        }
    }

    @Test
    void nyliumChangesByItselfAndBecomesNetherrack() {
        for (String id : new String[]{"crimson_nylium", "warped_nylium"}) {
            assertTrue(PhaseRules.changesByItself("minecraft:" + id), id);
            assertFalse(PhaseRules.phaseOne(block(id).build()), id);
            assertTrue(PhaseRules.naturalConversion("minecraft:" + id, "minecraft:netherrack"), id);
        }
        assertFalse(PhaseRules.naturalConversion("minecraft:netherrack", "minecraft:crimson_nylium"));
    }

    @Test
    void aNeighbourWithoutAValueIsUnknownNotAnError() {
        Map<Face, BlockFacts> nulled = around(Face.UP, stone());
        nulled.put(Face.SOUTH, null);
        assertEquals(Optional.of(NeverBreak.NEIGHBOURS_UNKNOWN), why(view(WRONG, nulled, false, false, 23)));
    }

    @Test
    void aNetherPortalOnAnySideIsNeverBrokenAgainst() {
        BlockFacts portal = block("nether_portal").props().notFull().build();
        for (Face f : Face.values()) {
            assertEquals(Optional.of(NeverBreak.HOLDS_ATTACHED), why(view(WRONG, around(f, portal), false, false, 23)), f.name());
        }
    }

    @Test
    void aBlockHoldingAnItemFrameOrPaintingIsNeverBroken() {
        assertEquals(Optional.of(NeverBreak.HOLDS_HANGING_ENTITY), why(viewWithHangingEntity(WRONG, around(Face.UP, stone()))));
        // ordered with the other safety rules: before the slow-tool and loop-guard ones
        assertEquals(Optional.of(NeverBreak.HOLDS_HANGING_ENTITY), why(new PhaseRules.BreakView(WRONG, around(Face.UP, stone()),
            false, true, true, 150, 100)));
    }

    @Test
    void theLoopGuardIsTheLastRuleSoAProtectedBlockIsSkippedNotStopped() {
        Cell chest = cell(0, 0, 0, Target.of(stone()), block("chest").props().blockEntity().build());
        assertEquals(Optional.of(NeverBreak.BLOCK_ENTITY), why(view(chest, around(Face.UP, stone()), false, true, 23)));
        assertEquals(Optional.of(NeverBreak.TOO_SLOW), why(view(WRONG, around(Face.UP, stone()), false, true, 150)));
    }
}
