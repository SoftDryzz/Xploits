package com.xploits.restock;

import com.xploits.restock.core.TakePlan;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The container screen restock opened (restock spec §3 "Take"; kit-requester's {@code EnderDepositor} binding): its
 * container slots come first (a chest or barrel: rows × 9; a shulker box: 27), the player's 36 after; a click goes to the
 * {@code syncId} restock adopted and to nothing else, and only the screen with that {@code syncId} is closed. Client thread.
 */
final class ContainerScreen {
    private static final int SHULKER_SLOTS = 27;

    private ContainerScreen() {
    }

    /** How many container slots this handler has; −1 when it is not a container restock takes from. */
    static int containerSlots(ScreenHandler h) {
        if (h instanceof GenericContainerScreenHandler g) return g.getRows() * 9;
        if (h instanceof ShulkerBoxScreenHandler) return SHULKER_SLOTS;
        return -1;
    }

    static boolean contentSeen(ScreenHandler h) {
        int n = containerSlots(h);
        for (int i = 0; i < n; i++) {
            if (!h.slots.get(i).getStack().isEmpty()) return true;
        }
        return false;
    }

    /** Phase A's slots: no reserve, nothing carried. */
    static List<TakePlan.Slot> slots(ClientPlayerEntity p) {
        return slots(p, 0, false, Set.of());
    }

    /**
     * The container's non-empty slots for {@link TakePlan}: each with the room the player's 36 slots have for exactly
     * that stack keeping {@code reserve} empty slots free ({@link TakePlan#room}), whether it holds items of its own
     * (Meteor's {@code Utils.hasItems}: a shulker box, or any block item, with contents — ruling R34), and — with
     * {@code carry} — what a filled shulker box holds, with its carry room ({@link TakePlan#carryRoom}). Never contents
     * for a box whose own item is in {@code neverCarry} (the build places that box: carried, the printer could place it
     * with its contents, pre-flight 17-3).
     */
    static List<TakePlan.Slot> slots(ClientPlayerEntity p, int reserve, boolean carry, Set<String> neverCarry) {
        ScreenHandler h = p.currentScreenHandler;
        PlayerInventory inv = p.getInventory();
        int n = containerSlots(h);
        int carryRoom = carryRoom(inv);
        List<TakePlan.Slot> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ItemStack stack = h.slots.get(i).getStack();
            if (stack.isEmpty()) continue;
            String item = StateFacts.itemId(stack);
            boolean holds = Utils.hasItems(stack);
            Map<String, Integer> inside = carry && holds && !neverCarry.contains(item)
                ? ShulkerInventory.contents(stack) : Map.of();
            if (!inside.isEmpty()) out.add(new TakePlan.Slot(i, item, stack.getCount(), carryRoom, inside));
            else out.add(new TakePlan.Slot(i, item, stack.getCount(), room(inv, stack, reserve), holds));
        }
        return out;
    }

    /** What a QUICK_MOVE of {@code stack} can put into the player's 36 slots: same stacks topped up, empty slots filled. */
    static int room(PlayerInventory inventory, ItemStack stack) {
        return room(inventory, stack, 0);
    }

    /** As {@link #room(PlayerInventory, ItemStack)}, {@code reserve} empty slots kept free ({@link TakePlan#room}). */
    static int room(PlayerInventory inventory, ItemStack stack, int reserve) {
        int same = 0;
        int empty = 0;
        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
            ItemStack s = inventory.getStack(i);
            if (s.isEmpty()) empty++;
            else if (ItemStack.areItemsAndComponentsEqual(s, stack)) {
                same += Math.max(0, s.getMaxCount() - s.getCount());
            }
        }
        return TakePlan.room(same, empty, stack.getMaxCount(), stack.getCount(), reserve);
    }

    /** The empty container slots of this screen. */
    static int freeSlots(ScreenHandler h) {
        int n = containerSlots(h);
        int free = 0;
        for (int i = 0; i < n; i++) {
            if (h.slots.get(i).getStack().isEmpty()) free++;
        }
        return free;
    }

    /**
     * {@link TakePlan#carryRoom} over the player's 36 slots: 1 while a hotbar slot and one more slot are empty. The
     * trip carries a box only then (ruling R54), and the session chooses a source with the block only inside boxes only
     * then (R40, Task B7).
     */
    static int carryRoom(PlayerInventory inv) {
        int empty = 0;
        int emptyHotbar = 0;
        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
            if (!inv.getStack(i).isEmpty()) continue;
            empty++;
            if (i < PlayerInventory.HOTBAR_SIZE) emptyHotbar++;
        }
        return TakePlan.carryRoom(emptyHotbar, empty);
    }

    /** The loose stacks restock could take, by item id: an empty shulker box is one, a filled one is not (ruling R34). */
    static Map<String, Integer> loose(ScreenHandler h) {
        Map<String, Integer> m = new TreeMap<>();
        int n = containerSlots(h);
        for (int i = 0; i < n; i++) {
            ItemStack stack = h.slots.get(i).getStack();
            if (stack.isEmpty() || Utils.hasItems(stack)) continue;
            m.merge(StateFacts.itemId(stack), stack.getCount(), Integer::sum);
        }
        return m;
    }

    /** What the shulker boxes in it hold, by item id (read from the items, as stash-keeper does). */
    static Map<String, Integer> nested(ScreenHandler h) {
        Map<String, Integer> m = new TreeMap<>();
        int n = containerSlots(h);
        for (int i = 0; i < n; i++) {
            ItemStack stack = h.slots.get(i).getStack();
            if (stack.isEmpty() || !Utils.isShulker(stack.getItem())) continue;
            ItemStack[] contents = new ItemStack[27];
            Utils.getItemsInContainerItem(stack, contents);
            for (ItemStack inside : contents) {
                if (inside != null && !inside.isEmpty()) m.merge(StateFacts.itemId(inside), inside.getCount(), Integer::sum);
            }
        }
        return m;
    }

    /** One QUICK_MOVE of a slot of the screen restock adopted; nothing when another screen is open. */
    static void quickMove(MinecraftClient mc, int syncId, int slot) {
        ClientPlayerEntity p = mc.player;
        if (p == null || p.currentScreenHandler.syncId != syncId) return;
        PacketWatch.get().asOurs(() -> mc.interactionManager.clickSlot(syncId, slot, 0, SlotActionType.QUICK_MOVE, p));
    }

    /** Closes the screen only if it is the one restock adopted. */
    static void close(ClientPlayerEntity p, int syncId) {
        if (syncId < 0 || p.currentScreenHandler.syncId != syncId) return;
        PacketWatch.get().asOurs(p::closeHandledScreen);
    }
}
