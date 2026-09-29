package com.xploits.pvp.recorder.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where each end crystal appeared (packed block position), for the bench only, bounded to the latest {@code
 * capacity} crystals. A crystal that explodes is removed from the world before its damage packet is read, so
 * the cell has to be remembered from when it appeared. In memory only; never logged or written.
 */
public final class CrystalCells {
    private final Map<Integer, Long> cells;

    public CrystalCells(int capacity) {
        this.cells = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, Long> eldest) {
                return size() > capacity;
            }
        };
    }

    public void remember(int id, long cell) {
        cells.put(id, cell);
    }

    public void clear() {
        cells.clear();
    }

    public int size() {
        return cells.size();
    }

    /** The cell of crystal {@code id}: {@code live} when the crystal is still in the world, else the remembered one, else none. */
    public long resolve(int id, long live) {
        if (live != HitSource.NO_CELL) return live;
        Long remembered = cells.get(id);
        return remembered == null ? HitSource.NO_CELL : remembered;
    }
}
