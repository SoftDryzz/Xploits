package com.xploits.restock;

import com.xploits.printer.core.BlockFacts;
import com.xploits.restock.core.RestockNeeds;
import com.xploits.restock.core.StateItems;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Falling;
import net.minecraft.block.Waterloggable;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EmptyBlockView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** What the cores need to know about block states and the inventory (measured, not decided). Client thread. */
final class StateFacts {
    private final Map<BlockState, BlockFacts> facts = new IdentityHashMap<>();
    private final Map<BlockState, Integer> items = new IdentityHashMap<>();

    /** A block state's facts; states are singletons, so the cache is by identity. */
    BlockFacts of(BlockState state) {
        return facts.computeIfAbsent(state, StateFacts::measure);
    }

    /** The items one block of this state consumes ({@link StateItems}). */
    int perBlock(BlockState state) {
        Integer n = items.get(state);
        if (n == null) {
            n = StateItems.count(of(state).item(), properties(state));
            items.put(state, n);
        }
        return n;
    }

    List<RestockNeeds.Counted> counted(Map<BlockState, Long> wholeBuild) {
        List<RestockNeeds.Counted> out = new ArrayList<>();
        wholeBuild.forEach((state, n) -> out.add(new RestockNeeds.Counted(of(state).item(), perBlock(state), n)));
        return out;
    }

    private static BlockFacts measure(BlockState state) {
        Block block = state.getBlock();
        Item item = block.asItem();
        return new BlockFacts(Registries.BLOCK.getId(block).toString(), Registries.ITEM.getId(item).toString(),
            state.isAir(), state.getProperties().isEmpty(), item instanceof BlockItem bi && bi.getBlock() == block,
            state.isFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN), state.hasBlockEntity(), block instanceof Falling,
            block instanceof Waterloggable, state.isReplaceable(), !state.getFluidState().isEmpty(),
            state.getHardness(EmptyBlockView.INSTANCE, BlockPos.ORIGIN) < 0);
    }

    /** The state's properties by name, values as Minecraft names them ({@code State.getEntries}, {@code Property.name}). */
    static Map<String, String> properties(BlockState state) {
        Map<String, String> m = new HashMap<>();
        for (Map.Entry<Property<?>, Comparable<?>> e : state.getEntries().entrySet()) {
            m.put(e.getKey().getName(), name(e.getKey(), e.getValue()));
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String name(Property<T> property, Comparable<?> value) {
        return property.name((T) value);
    }

    static String itemId(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()).toString();
    }

    /** Item id → count over the hotbar and the main inventory (slots 0–35), what litematica-printer takes from. */
    static Map<String, Integer> carried(PlayerInventory inventory) {
        Map<String, Integer> m = new TreeMap<>();
        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty()) m.merge(itemId(stack), stack.getCount(), Integer::sum);
        }
        return m;
    }
}
