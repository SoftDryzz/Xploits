package com.xploits.restock.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Owner ruling R44, "carried shulkers first": when a material the build needs runs out, a shulker box the player carries
 * that holds it is unpacked at the build before any container trip. With {@code use-carried-shulkers} off only the boxes
 * restock borrowed count. A box in the main inventory is unpacked only while a hotbar slot is free (owner ruling R43
 * moves it there with one click); none while the inventory has no free slot at all (the take from it would fit nothing).
 * A material whose unpack gave nothing in this session goes to the containers instead (Review Focus 3). Pure.
 */
public final class UnpackChoice {
    /** One box the player carries: its slot (0–8 the hotbar, 9–35 the main inventory), its kind, what it holds, and whether restock borrowed one of that kind. */
    public record Carried(int slot, BorrowedShulkers.Kind kind, Map<String, Integer> contents, boolean borrowed) {
        public Carried {
            Objects.requireNonNull(kind, "kind");
            contents = Map.copyOf(contents);
            if (slot < 0 || slot > 35) throw new IllegalArgumentException("slot " + slot);
        }

        boolean hotbar() {
            return slot < 9;
        }
    }

    /** What the player carries: loose item counts (boxes count as items), the boxes that hold something, the empty slots of the 36 and of the hotbar. */
    public record Inventory(Map<String, Integer> loose, List<Carried> shulkers, int emptySlots, int emptyHotbar) {
        public Inventory {
            loose = Map.copyOf(loose);
            shulkers = List.copyOf(shulkers);
        }
    }

    public sealed interface Choice permits Unpack, None {
    }

    /** Unpack the box in {@code slot}, of {@code kind}: it holds {@code material}. */
    public record Unpack(String material, int slot, BorrowedShulkers.Kind kind) implements Choice {
    }

    /** Nothing to unpack; {@code noHotbarRoom}: due materials a carried box holds, but only in the main inventory with the hotbar full. */
    public record None(List<String> noHotbarRoom) implements Choice {
        public None {
            noHotbarRoom = List.copyOf(noHotbarRoom);
        }
    }

    private UnpackChoice() {
    }

    public static Choice choose(List<String> due, Inventory inv, boolean useCarried, Set<String> gaveUp) {
        if (inv.emptySlots() < 1) return new None(List.of());
        List<String> noRoom = new ArrayList<>();
        for (String m : due) {
            if (gaveUp.contains(m)) continue;
            Carried best = null;
            boolean blocked = false;
            for (Carried c : inv.shulkers()) {
                if (!usable(c, useCarried) || c.contents().getOrDefault(m, 0) <= 0) continue;
                if (!c.hotbar() && inv.emptyHotbar() < 1) {
                    blocked = true;
                    continue;
                }
                if (best == null || better(c, best, m)) best = c;
            }
            if (best != null) return new Unpack(m, best.slot(), best.kind());
            if (blocked) noRoom.add(m);
        }
        return new None(noRoom);
    }

    /**
     * What the player has for a container trip's take (its fetch need): the loose items, plus what the boxes restock
     * would unpack first hold — never a material whose unpack gave nothing, never a box it could not unpack now.
     */
    public static Map<String, Integer> available(Inventory inv, boolean useCarried, Set<String> gaveUp) {
        Map<String, Integer> m = new TreeMap<>(inv.loose());
        if (inv.emptySlots() < 1) return m;
        for (Carried c : inv.shulkers()) {
            if (!usable(c, useCarried) || (!c.hotbar() && inv.emptyHotbar() < 1)) continue;
            c.contents().forEach((item, n) -> {
                if (!gaveUp.contains(item)) m.merge(item, n, Integer::sum);
            });
        }
        return m;
    }

    private static boolean usable(Carried c, boolean useCarried) {
        return c.borrowed() || useCarried;
    }

    /** The hotbar first (no click to move it), then the one holding the most of it, then the lower slot. */
    private static boolean better(Carried c, Carried best, String m) {
        if (c.hotbar() != best.hotbar()) return c.hotbar();
        int a = c.contents().getOrDefault(m, 0);
        int b = best.contents().getOrDefault(m, 0);
        if (a != b) return a > b;
        return c.slot() < best.slot();
    }
}
