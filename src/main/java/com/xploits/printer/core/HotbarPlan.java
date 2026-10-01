package com.xploits.printer.core;

import java.util.List;

/**
 * The hotbar step before a click (printer spec §5.5, N-M7): use the selected slot, select another hotbar slot (lowest
 * first), move one main-inventory stack into the hotbar with one QUICK_MOVE (vanilla puts it in a same-item stack or an
 * empty slot), or stop — nothing is ever overwritten. Tools, food, totems and weapons are never moved.
 */
public final class HotbarPlan {
    private HotbarPlan() {
    }

    public record Slot(String item, int count) {
        public static final Slot EMPTY = new Slot("minecraft:air", 0);

        boolean holds(String material) {
            return count > 0 && item.equals(material);
        }

        boolean empty() {
            return count == 0;
        }
    }

    public sealed interface Step permits Ready, Select, QuickMove, NoRoom, NotCarried {
    }

    public record Ready(int slot) implements Step {
    }

    public record Select(int slot) implements Step {
    }

    /** One QUICK_MOVE of the main-inventory slot whose index (= its slot in the player's own handler) is given. */
    public record QuickMove(int screenSlot) implements Step {
    }

    public record NoRoom() implements Step {
    }

    public record NotCarried() implements Step {
    }

    public static Step forMaterial(List<Slot> inventory, int selected, String material) {
        if (inventory.size() != 36) throw new IllegalArgumentException("36 slots expected, got " + inventory.size());
        if (inventory.get(selected).holds(material)) return new Ready(selected);
        for (int i = 0; i < 9; i++) {
            if (inventory.get(i).holds(material)) return new Select(i);
        }
        int source = -1;
        for (int i = 9; i < 36; i++) {
            if (inventory.get(i).holds(material)) {
                source = i;
                break;
            }
        }
        if (source < 0) return new NotCarried();
        for (int i = 0; i < 9; i++) {
            if (inventory.get(i).empty()) return new QuickMove(source);
        }
        return new NoRoom();
    }
}
