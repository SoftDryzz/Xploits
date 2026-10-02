package com.xploits.restock;

import com.xploits.restock.core.BorrowedShulkers;
import com.xploits.restock.core.UnpackChoice;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Shulker boxes as restock's cores read them (phase B): a box's kind (item and custom name), what it holds — read from
 * the item as stash-keeper does (Meteor's {@code Utils.getItemsInContainerItem}, only after {@code Utils.isShulker}) —
 * whether it holds anything (Meteor's {@code Utils.hasItems}, ruling R34's test), and the boxes the player carries.
 * Client thread.
 */
final class ShulkerInventory {
    private static final int BOX_SLOTS = 27;

    private ShulkerInventory() {
    }

    static boolean isBox(ItemStack stack) {
        return !stack.isEmpty() && Utils.isShulker(stack.getItem());
    }

    static BorrowedShulkers.Kind kind(ItemStack stack) {
        Text name = stack.getCustomName();
        return new BorrowedShulkers.Kind(StateFacts.itemId(stack), name == null ? "" : name.getString());
    }

    /** What a box holds, by item id; empty for anything that is not a box. */
    static Map<String, Integer> contents(ItemStack stack) {
        if (!isBox(stack)) return Map.of();
        ItemStack[] inside = new ItemStack[BOX_SLOTS];
        Utils.getItemsInContainerItem(stack, inside);
        Map<String, Integer> m = new TreeMap<>();
        for (ItemStack s : inside) {
            if (s != null && !s.isEmpty()) m.merge(StateFacts.itemId(s), s.getCount(), Integer::sum);
        }
        return m;
    }

    /**
     * The player's 36 slots for {@link UnpackChoice}: loose items, the boxes that hold something, the empty slots. Of
     * a kind's filled boxes at most min(ledger entries, filled boxes) are marked borrowed, the lower slots first (M5,
     * R50's count: the player's own boxes of a kind are presumed the empty ones first, as in the give-back) — so with
     * {@code use-carried-shulkers} off, a box of a borrowed kind beyond what was borrowed is never unpacked.
     */
    static UnpackChoice.Inventory choiceInventory(PlayerInventory inv, BorrowedShulkers ledger) {
        Map<BorrowedShulkers.Kind, Integer> filled = new HashMap<>();
        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
            ItemStack stack = inv.getStack(i);
            if (isBox(stack) && Utils.hasItems(stack)) filled.merge(kind(stack), 1, Integer::sum);
        }
        Map<BorrowedShulkers.Kind, Integer> borrowedLeft = new HashMap<>();
        filled.forEach((k, n) -> borrowedLeft.put(k, ledger.carried(Map.of(k, n))));
        List<UnpackChoice.Carried> boxes = new ArrayList<>();
        int empty = 0;
        int emptyHotbar = 0;
        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) {
                empty++;
                if (i < PlayerInventory.HOTBAR_SIZE) emptyHotbar++;
                continue;
            }
            if (!isBox(stack) || !Utils.hasItems(stack)) continue;
            BorrowedShulkers.Kind kind = kind(stack);
            int left = borrowedLeft.getOrDefault(kind, 0);
            if (left > 0) borrowedLeft.put(kind, left - 1);
            boxes.add(new UnpackChoice.Carried(i, kind, contents(stack), left > 0));
        }
        return new UnpackChoice.Inventory(StateFacts.carried(inv), boxes, empty, emptyHotbar);
    }

    /** The boxes carried in slots 0–35, by kind (any contents). */
    static Map<BorrowedShulkers.Kind, Integer> kinds(PlayerInventory inv) {
        Map<BorrowedShulkers.Kind, Integer> m = new HashMap<>();
        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
            ItemStack stack = inv.getStack(i);
            if (isBox(stack)) m.merge(kind(stack), 1, Integer::sum);
        }
        return m;
    }

    /** The boxes in slots 0–35, each with its inventory index as its slot. */
    static List<BorrowedShulkers.Held> held(PlayerInventory inv) {
        List<BorrowedShulkers.Held> out = new ArrayList<>();
        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
            ItemStack stack = inv.getStack(i);
            if (isBox(stack)) out.add(new BorrowedShulkers.Held(i, kind(stack), !Utils.hasItems(stack)));
        }
        return out;
    }

    /**
     * The boxes in the player's part of a container screen (its slots after the container's), with their screen
     * slots.
     */
    static List<BorrowedShulkers.Held> heldInScreen(ScreenHandler h, int containerSlots) {
        List<BorrowedShulkers.Held> out = new ArrayList<>();
        for (int s = containerSlots; s < h.slots.size(); s++) {
            ItemStack stack = h.slots.get(s).getStack();
            if (isBox(stack)) out.add(new BorrowedShulkers.Held(s, kind(stack), !Utils.hasItems(stack)));
        }
        return out;
    }
}
