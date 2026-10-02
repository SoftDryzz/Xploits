package com.xploits.restock.core;

import com.xploits.printer.core.Point;
import com.xploits.printer.core.Pos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Restock spec §3 "Sources and choice": the nearest container that has the material, same dimension, within reach. */
class SourceChooserTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final String STONE = "minecraft:stone";
    private static final String GLASS = "minecraft:glass";
    /** The trip's start, level with the containers' centres: distances below are worked to the centre (x + 0.5). */
    private static final Point FROM = new Point(0.5, 64.5, 0.5);

    /** 10 blocks away, holds 64 stone. */
    private static final Source STASH_STONE = Source.stash(OVERWORLD, new Pos(10, 64, 0), Map.of(STONE, 64), Map.of());
    /** 5 blocks away, marked, holds 5 stone. */
    private static final Source MARK_STONE = Source.mark(OVERWORLD, new Pos(-5, 64, 0), new Pos(-4, 64, 0),
        Map.of(STONE, 5), Map.of());
    /** 2 blocks away, marked, never looked into. */
    private static final Source UNKNOWN = Source.unknownMark(OVERWORLD, new Pos(2, 64, 0), new Pos(2, 64, 1));
    /** 3 blocks away, marked, holds dirt only. */
    private static final Source MARK_DIRT = Source.mark(OVERWORLD, new Pos(3, 64, 0), new Pos(3, 64, 1),
        Map.of("minecraft:dirt", 64), Map.of());

    private static Optional<Source> nearest(List<Source> sources, String material) {
        return SourceChooser.nearest(sources, material, OVERWORLD, FROM, 64, Set.of(), Set.of(), false);
    }

    @Test
    void theNearestKnownSourceOfTheMaterialWins() {
        assertEquals(Optional.of(MARK_STONE), nearest(List.of(STASH_STONE, MARK_STONE, UNKNOWN, MARK_DIRT), STONE));
    }

    @Test
    void aKnownSourceWinsOverANearerUnknownMark() {
        assertEquals(Optional.of(STASH_STONE), nearest(List.of(UNKNOWN, STASH_STONE), STONE));
    }

    @Test
    void anUnknownMarkIsTriedWhenNothingKnownHasIt() {
        assertEquals(Optional.of(UNKNOWN), nearest(List.of(STASH_STONE, MARK_STONE, UNKNOWN, MARK_DIRT), GLASS));
    }

    @Test
    void theEnderChestAndOtherDimensionsAreNeverChosen() {
        Source nether = Source.stash(NETHER, new Pos(1, 64, 0), Map.of(STONE, 64), Map.of());
        // stash-keeper's ender chest key has no dimension and no real position.
        Source ender = Source.stash("", new Pos(0, 64, 0), Map.of(STONE, 64), Map.of());
        assertEquals(Optional.empty(), nearest(List.of(nether, ender), STONE));
    }

    @Test
    void maxDistanceIsInclusive() {
        Source at64 = Source.stash(OVERWORLD, new Pos(64, 64, 0), Map.of(STONE, 1), Map.of());
        Source at65 = Source.stash(OVERWORLD, new Pos(65, 64, 0), Map.of(STONE, 1), Map.of());
        assertEquals(Optional.of(at64), nearest(List.of(at64), STONE));
        assertEquals(Optional.empty(), nearest(List.of(at65), STONE));
    }

    @Test
    void staleAndUnusableSourcesAreSkipped() {
        List<Source> both = List.of(MARK_STONE, STASH_STONE);
        assertEquals(Optional.of(STASH_STONE), SourceChooser.nearest(both, STONE, OVERWORLD, FROM, 64, Set.of(),
            Set.of(MARK_STONE.container()), false));
        assertEquals(Optional.empty(), SourceChooser.nearest(both, STONE, OVERWORLD, FROM, 64,
            Set.of(STASH_STONE.container()), Set.of(MARK_STONE.container()), false));
    }

    @Test
    void nestedContentsCountOnlyWhenAllowed() {
        Source shulkers = Source.stash(OVERWORLD, new Pos(1, 64, 0), Map.of(), Map.of(STONE, 27));
        assertEquals(Optional.empty(), nearest(List.of(shulkers), STONE));
        assertEquals(Optional.of(shulkers), SourceChooser.nearest(List.of(shulkers), STONE, OVERWORLD, FROM, 64,
            Set.of(), Set.of(), true));
    }

    @Test
    void looseStacksComeBeforeShulkerContentsEvenFarther() {
        // Ruling R40: a loose stack is one QUICK_MOVE; a shulker box is a carry and an unpack at the build.
        Source boxes = Source.stash(OVERWORLD, new Pos(1, 64, 0), Map.of(), Map.of(STONE, 1728));
        assertEquals(Optional.of(STASH_STONE), SourceChooser.nearest(List.of(boxes, STASH_STONE), STONE, OVERWORLD, FROM,
            64, Set.of(), Set.of(), true));
    }

    @Test
    void shulkerContentsComeBeforeAMarkNobodyLookedInto() {
        Source boxes = Source.stash(OVERWORLD, new Pos(5, 64, 0), Map.of(), Map.of(STONE, 27));
        assertEquals(Optional.of(boxes), SourceChooser.nearest(List.of(UNKNOWN, boxes), STONE, OVERWORLD, FROM, 64,
            Set.of(), Set.of(), true));
    }

    @Test
    void aContainerSeenWithTheBlockOnlyInBoxesIsPassedOverWhileNoBoxCanBeCarried() {
        // Ruling R54 (R60): no stale mark. R40's nested rank then skips it while no box can be carried, and chooses it
        // again once one can.
        Map<String, Integer> boxes = Map.of(STONE, 64);
        assertTrue(SourceChooser.passedOver(RestockTrip.Failure.STALE, false, Map.of(), boxes, STONE));
        Source seen = Source.mark(OVERWORLD, new Pos(1, 64, 0), new Pos(1, 64, 1), Map.of(), boxes);
        assertEquals(Optional.empty(), nearest(List.of(seen), STONE), "skipped while no box can be carried: no loop");
        assertEquals(Optional.of(seen), SourceChooser.nearest(List.of(seen), STONE, OVERWORLD, FROM, 64, Set.of(),
            Set.of(), true), "chosen again once a box can be carried");
    }

    @Test
    void withCarryRoomOrForAnyOtherFailureTheContainerIsNotedAsBefore() {
        Map<String, Integer> boxes = Map.of(STONE, 64);
        // With carry room, a box restock may not carry (its own item the build places, pre-flight 17-3) leaves the
        // container stale, so the next choice cannot come back to it.
        assertFalse(SourceChooser.passedOver(RestockTrip.Failure.STALE, true, Map.of(), boxes, STONE));
        assertFalse(SourceChooser.passedOver(RestockTrip.Failure.FILLED_ONLY, false, Map.of(), boxes, STONE));
        assertFalse(SourceChooser.passedOver(RestockTrip.Failure.UNUSABLE, false, Map.of(), boxes, STONE));
    }

    @Test
    void onlyTheBlockItselfInsideBoxesAndNoneLooseIsPassedOver() {
        RestockTrip.Failure stale = RestockTrip.Failure.STALE;
        assertFalse(SourceChooser.passedOver(stale, false, Map.of(), Map.of(), STONE), "a container without it");
        assertFalse(SourceChooser.passedOver(stale, false, Map.of(), Map.of(GLASS, 64), STONE), "boxes of glass only");
        assertFalse(SourceChooser.passedOver(stale, false, Map.of(STONE, 1), Map.of(STONE, 64), STONE), "loose too");
    }

    @Test
    void aTieGoesToTheLowerX() {
        Source east = Source.stash(OVERWORLD, new Pos(3, 64, 0), Map.of(STONE, 1), Map.of());
        Source west = Source.stash(OVERWORLD, new Pos(-3, 64, 0), Map.of(STONE, 1), Map.of());
        assertEquals(Optional.of(west), nearest(List.of(east, west), STONE));
    }

    @Test
    void aSourceIsWhatItsKindSays() {
        assertThrows(IllegalArgumentException.class, () -> new Source(Source.Kind.STASH, OVERWORLD, new Pos(0, 0, 0),
            Optional.empty(), false, Map.of(), Map.of()), "a stash-keeper entry always has contents");
        assertThrows(IllegalArgumentException.class, () -> new Source(Source.Kind.MARK, OVERWORLD, new Pos(0, 0, 0),
            Optional.empty(), true, Map.of(), Map.of()), "a mark always has its stand spot");
        assertThrows(IllegalArgumentException.class, () -> new Source(Source.Kind.MARK, OVERWORLD, new Pos(0, 0, 0),
            Optional.of(new Pos(1, 0, 0)), false, Map.of(STONE, 1), Map.of()), "unknown contents hold nothing");
    }
}
