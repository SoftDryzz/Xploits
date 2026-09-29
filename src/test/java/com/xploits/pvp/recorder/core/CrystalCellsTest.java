package com.xploits.pvp.recorder.core;

import com.xploits.bench.core.OwnHits;
import com.xploits.pvp.recorder.core.CombatEvent.AttackerKind;
import com.xploits.pvp.recorder.core.FightRecord.DamageEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round 2 of C2: the crystal that hit us is already out of the world when its damage packet is read, so its cell
 * comes from where it appeared. A hit from a crystal we placed but never saw by id is then ours by that cell.
 */
class CrystalCellsTest {
    private static final long CELL = 4711L;

    private static DamageEvent bySparring(long tick, double before, double after) {
        return new DamageEvent(tick, DamageKind.CRYSTAL, AttackerKind.PLAYER, "sparring", before, after, false);
    }

    /** What the recorder does when it reads a hit whose crystal is gone (live cell missing). */
    private static HitSource read(CrystalCells cells, long tick, int direct) {
        return new HitSource(tick, direct, cells.resolve(direct, HitSource.NO_CELL));
    }

    @Test
    void aCrystalGoneFromTheWorldStillHasItsCellAndItsHitIsOurs() {
        CrystalCells cells = new CrystalCells(8);
        cells.remember(77, CELL);
        List<DamageEvent> damage = List.of(bySparring(10, 20, 4));
        Set<Integer> ours = OwnHits.indexes(damage, List.of(read(cells, 10, 77)), Set.of(), Set.of(CELL));
        assertEquals(Set.of(0), ours);
    }

    @Test
    void withoutTheRememberedCellTheHitStaysTheSparrings() {
        CrystalCells cells = new CrystalCells(8);
        List<DamageEvent> damage = List.of(bySparring(10, 20, 4));
        assertTrue(OwnHits.indexes(damage, List.of(read(cells, 10, 77)), Set.of(), Set.of(CELL)).isEmpty());
    }

    @Test
    void aRememberedCellOffAnyPlacedCellIsNotOurs() {
        CrystalCells cells = new CrystalCells(8);
        cells.remember(77, 9999L);
        List<DamageEvent> damage = List.of(bySparring(10, 20, 4));
        assertTrue(OwnHits.indexes(damage, List.of(read(cells, 10, 77)), Set.of(), Set.of(CELL)).isEmpty());
    }

    @Test
    void theLiveCellWinsOverTheRememberedOne() {
        CrystalCells cells = new CrystalCells(8);
        cells.remember(77, 1L);
        assertEquals(2L, cells.resolve(77, 2L));
    }

    @Test
    void itIsBoundedAndClearable() {
        CrystalCells cells = new CrystalCells(2);
        cells.remember(1, 10L);
        cells.remember(2, 20L);
        cells.remember(3, 30L);
        assertEquals(2, cells.size());
        assertEquals(HitSource.NO_CELL, cells.resolve(1, HitSource.NO_CELL));
        assertEquals(30L, cells.resolve(3, HitSource.NO_CELL));
        cells.clear();
        assertEquals(0, cells.size());
    }
}
