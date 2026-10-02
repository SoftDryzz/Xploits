package com.xploits.restock.core;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M5 (ruling R64): which filled shulker boxes the player carries count as the ones restock borrowed — with
 * {@code use-carried-shulkers} off only those are unpacked ({@link UnpackChoice}). Two boxes of one kind (item and
 * custom name) cannot be told apart, so per kind at most min(ledger entries, filled boxes) count
 * ({@link BorrowedShulkers#carried}), the lower slots first; the player's own boxes of a kind are presumed the empty
 * ones first (R50's count, as in the give-back), so an empty box is never marked and takes no mark from a filled one,
 * and a box of a kind the ledger does not hold is never marked. Pure.
 */
public final class BorrowedMarks {
    private BorrowedMarks() {
    }

    /**
     * {@code held}: the boxes carried in slots 0–35, in any order. The slots of the filled ones that count as borrowed.
     */
    public static Set<Integer> of(List<BorrowedShulkers.Held> held, BorrowedShulkers ledger) {
        List<BorrowedShulkers.Held> filled = held.stream().filter(h -> !h.empty())
            .sorted(Comparator.comparingInt(BorrowedShulkers.Held::slot)).toList();
        Map<BorrowedShulkers.Kind, Integer> count = new HashMap<>();
        for (BorrowedShulkers.Held h : filled) count.merge(h.kind(), 1, Integer::sum);
        Map<BorrowedShulkers.Kind, Integer> left = new HashMap<>();
        count.forEach((kind, n) -> left.put(kind, ledger.carried(Map.of(kind, n))));
        Set<Integer> marked = new HashSet<>();
        for (BorrowedShulkers.Held h : filled) {
            int n = left.getOrDefault(h.kind(), 0);
            if (n <= 0) continue;
            left.put(h.kind(), n - 1);
            marked.add(h.slot());
        }
        return Set.copyOf(marked);
    }
}
