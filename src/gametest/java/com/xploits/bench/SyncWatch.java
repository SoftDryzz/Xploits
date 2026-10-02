package com.xploits.bench;

import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The client against the server (lab Task 10b, the owner's request of 2026-09-30; printer spec §10, M5): no ghost block,
 * no item lost from the inventory by mistake, never an item left on the cursor. Once a second ({@value #EVERY} ticks) it
 * compares the client with the server: the block states of the cells the caller names around the client's feet block,
 * the counts of the items the caller names in the whole inventory (offhand, armour and the contents of shulker boxes
 * included), the cursor and the selected hotbar slot; once more {@value #EVERY} ticks after the run. The client runs a
 * little behind the server, so one mismatch is no ghost: a cell counts only when it mismatches with the same pair of
 * states at two comparisons running, an item's count or the slot only when they differ at two running.
 *
 * <p>Between two comparisons the cells that mismatched are read again every tick on both sides. A ghost whose pair held
 * at every one of those ticks is a steady ghost, the sure kind: a cell mined and refilled every tick can show the same
 * pair twice, a second apart, without ever being stuck.
 *
 * <p>The lab passes surround++'s box around the feet block and four combat items; a printer CHECK passes the build and
 * its margin, and the items of its kit. Absolute positions are compared in memory only: nothing written carries one,
 * nor our player's name.
 */
final class SyncWatch {
    /** How often the client is compared with the server: once a second. */
    static final int EVERY = 20;
    /**
     * The tick within each second the comparison is made at. The lab opponents' own block changes (webs, spot blocks,
     * hole fills, crystal attacks) fall every 10, 20 or 40 ticks from T0, just before a comparison made on a multiple of
     * 20, which would catch every one of them on its way to the client; five ticks later they have arrived.
     */
    static final int PHASE = 5;
    static final String HEADER = "| variant | run | ghost cells (client-only / server-only / different) | largest per sample"
        + " | steady ghost cells | final ghost cells | largest inventory difference, client minus server (obsidian,"
        + " crying, crystals, totems) | cursor samples non-empty | slot mismatches | slot back at T0 |\n"
        + "|---|---|---|---|---|---|---|---|---|---|\n";

    /**
     * What the run left, for a CHECK: never a position. Clean = no steady ghost during the run, no ghost after it, no
     * item count different after it, the cursor never seen holding anything, the slots never different at two
     * comparisons running, and the slot held at T0 back on both sides.
     */
    record Result(int samples, int steadyGhosts, int finalGhosts, Map<String, Integer> finalDifference, int cursorSamples,
                  boolean finalCursorEmpty, int slotMismatches, boolean slotBackAtT0) {
        Result {
            finalDifference = Map.copyOf(finalDifference);
        }

        boolean clean() {
            return steadyGhosts == 0 && finalGhosts == 0 && finalDifference.values().stream().allMatch(d -> d == 0)
                && cursorSamples == 0 && finalCursorEmpty && slotMismatches == 0 && slotBackAtT0;
        }

        String words() {
            List<String> difference = new ArrayList<>();
            new TreeMap<>(finalDifference).forEach((item, d) -> difference.add(item + " " + signed(d)));
            return steadyGhosts + " steady ghost cell(s) during the run and " + finalGhosts + " ghost cell(s) after it;"
                + " after it, client minus server: " + String.join(", ", difference) + "; cursor not empty at "
                + cursorSamples + " comparison(s)" + (finalCursorEmpty ? "" : " and after the run") + "; slots different at "
                + slotMismatches + " comparison pair(s)" + (slotBackAtT0 ? "" : "; the slot held at T0 is not back");
        }
    }

    /** One side's view at one comparison: the cells' states, the items' counts, the cursor's item ("" if empty), the slot. */
    private record Side(BlockState[] box, int[] counts, String cursor, int slot) {
    }

    /** The client's view and the cells it was read at (kept in memory only). */
    private record ClientView(List<BlockPos> cells, Side side) {
    }

    /** A cell the two sides disagree on: what the client shows and what the server has. */
    private record Mismatch(BlockState client, BlockState server) {
        /** "client-only" (a block the server does not have), "server-only", or "different" (two different blocks). */
        String kind() {
            if (server.isAir()) return "client-only";
            if (client.isAir()) return "server-only";
            return "different";
        }

        String describe() {
            return "client " + blockName(client) + ", server " + blockName(server);
        }
    }

    /**
     * What one comparison found: the ghosts (cells with the same pair as at the comparison before), those of them whose
     * pair held at every tick in between, each item's difference (client minus server) where it differed at the
     * comparison before too (0 otherwise), both cursors and both slots, and whether the slots differed at both comparisons.
     */
    private record Sample(Map<BlockPos, Mismatch> ghosts, Set<BlockPos> steady, int[] difference, String clientCursor,
                          String serverCursor, int clientSlot, int serverSlot, boolean slotMismatch) {
    }

    private final Bench bench;
    private final Function<BlockPos, List<BlockPos>> cellsAround;
    private final List<Item> items;
    private final List<String> itemNames;
    /** Our player's name, to find it on the server; never written. */
    private final String name;
    private final int t0ClientSlot;
    private final int t0ServerSlot;
    private int samples;
    /** The cells that mismatched at the last comparison, with their pair. */
    private Map<BlockPos, Mismatch> lastMismatches = Map.of();
    /** Of those, the cells whose pair changed at some tick since. */
    private final Set<BlockPos> changedSince = new HashSet<>();
    private int[] lastDifference;
    private boolean lastSlotsDiffered;
    // The run's totals.
    private final Map<BlockPos, Mismatch> ghosts = new HashMap<>();
    private final Set<BlockPos> steadyGhosts = new HashSet<>();
    private int mostAtOneSample;
    private final int[] largestDifference;
    private int desyncSamples;
    private int cursorSamples;
    private int clientCursorSamples;
    private int serverCursorSamples;
    private final Set<String> cursorItems = new TreeSet<>();
    private int slotMismatches;
    /** The comparison after the run; null before it. */
    private Sample after;

    /**
     * At T0: the slot each side holds. {@code cellsAround} gives, on the client thread, the absolute cells to compare
     * around the client's feet block (always in the same order); {@code itemNames} names {@code items} in the same order.
     */
    SyncWatch(Bench bench, Function<BlockPos, List<BlockPos>> cellsAround, List<Item> items, List<String> itemNames) {
        if (items.size() != itemNames.size()) throw new BenchException("every compared item needs its name");
        this.bench = bench;
        this.cellsAround = cellsAround;
        this.items = List.copyOf(items);
        this.itemNames = List.copyOf(itemNames);
        this.lastDifference = new int[items.size()];
        this.largestDifference = new int[items.size()];
        this.name = bench.player();
        this.t0ClientSlot = bench.fromClient(client -> {
            if (client.player == null) throw new BenchException("the client has no player");
            return client.player.getInventory().getSelectedSlot();
        });
        this.t0ServerSlot = bench.fromServer(srv -> Arena.player(srv, name).getInventory().getSelectedSlot());
    }

    /** After every tick of the run ({@code tick} counts from 1 at T0): a comparison, or the cells to watch. */
    void tick(int tick) {
        if (tick % EVERY != PHASE) {
            track();
            return;
        }
        Sample now = compare();
        samples++;
        now.ghosts().forEach(ghosts::putIfAbsent);
        steadyGhosts.addAll(now.steady());
        mostAtOneSample = Math.max(mostAtOneSample, now.ghosts().size());
        boolean desync = false;
        for (int k = 0; k < largestDifference.length; k++) {
            int d = now.difference()[k];
            if (d == 0) continue;
            desync = true;
            if (Math.abs(d) > Math.abs(largestDifference[k])) largestDifference[k] = d;
        }
        if (desync) desyncSamples++;
        if (!now.clientCursor().isEmpty() || !now.serverCursor().isEmpty()) cursorSamples++;
        if (!now.clientCursor().isEmpty()) {
            clientCursorSamples++;
            cursorItems.add(now.clientCursor());
        }
        if (!now.serverCursor().isEmpty()) {
            serverCursorSamples++;
            cursorItems.add(now.serverCursor());
        }
        if (now.slotMismatch()) slotMismatches++;
    }

    /** After the run: {@value #EVERY} more ticks, the cells still watched, then one last comparison. */
    void finalCheck() {
        for (int i = 0; i < EVERY; i++) {
            bench.ticks(1);
            track();
        }
        after = compare();
    }

    /** The run's result for a CHECK; only after {@link #finalCheck()}. */
    Result result() {
        if (after == null) throw new BenchException("the sync result was read before the last comparison");
        Map<String, Integer> difference = new LinkedHashMap<>();
        for (int k = 0; k < items.size(); k++) difference.put(itemNames.get(k), after.difference()[k]);
        return new Result(samples, steadyGhosts.size(), after.ghosts().size(), difference, cursorSamples,
            after.clientCursor().isEmpty() && after.serverCursor().isEmpty(), slotMismatches, backAtT0());
    }

    /** Reads both sides and compares them; what was seen becomes the comparison before for the next one. */
    private Sample compare() {
        ClientView client = bench.fromClient(c -> {
            if (c.player == null || c.world == null) throw new BenchException("the client has no player");
            List<BlockPos> cells = List.copyOf(cellsAround.apply(c.player.getBlockPos()));
            return new ClientView(cells, side(c.player, c.world, cells));
        });
        Side server = bench.fromServer(srv -> {
            ServerPlayerEntity player = Arena.player(srv, name);
            return side(player, player.getEntityWorld(), client.cells());
        });
        Map<BlockPos, Mismatch> mismatches = new HashMap<>();
        for (int i = 0; i < client.cells().size(); i++) {
            BlockState c = client.side().box()[i];
            BlockState s = server.box()[i];
            if (!c.equals(s)) mismatches.put(client.cells().get(i), new Mismatch(c, s));
        }
        Map<BlockPos, Mismatch> ghostsNow = new HashMap<>();
        Set<BlockPos> steadyNow = new HashSet<>();
        mismatches.forEach((cell, pair) -> {
            if (!pair.equals(lastMismatches.get(cell))) return;
            ghostsNow.put(cell, pair);
            if (!changedSince.contains(cell)) steadyNow.add(cell);
        });
        int[] difference = new int[items.size()];
        int[] confirmed = new int[items.size()];
        for (int k = 0; k < difference.length; k++) {
            difference[k] = client.side().counts()[k] - server.counts()[k];
            if (difference[k] != 0 && lastDifference[k] != 0) confirmed[k] = difference[k];
        }
        boolean slotsDiffer = client.side().slot() != server.slot();
        boolean slotMismatch = slotsDiffer && lastSlotsDiffered;
        lastMismatches = mismatches;
        changedSince.clear();
        lastDifference = difference;
        lastSlotsDiffered = slotsDiffer;
        return new Sample(ghostsNow, steadyNow, confirmed, client.side().cursor(), server.cursor(), client.side().slot(),
            server.slot(), slotMismatch);
    }

    /** A tick between two comparisons: the cells that mismatched at the last one, read again on both sides. */
    private void track() {
        List<BlockPos> cells = lastMismatches.keySet().stream().filter(cell -> !changedSince.contains(cell)).toList();
        if (cells.isEmpty()) return;
        List<BlockState> client = bench.fromClient(c -> {
            if (c.world == null) throw new BenchException("the client has no world");
            return cells.stream().map(c.world::getBlockState).toList();
        });
        List<BlockState> server = bench.fromServer(srv -> {
            World world = Arena.player(srv, name).getEntityWorld();
            return cells.stream().map(world::getBlockState).toList();
        });
        for (int i = 0; i < cells.size(); i++) {
            if (!new Mismatch(client.get(i), server.get(i)).equals(lastMismatches.get(cells.get(i)))) changedSince.add(cells.get(i));
        }
    }

    /** One side's view, on that side's own thread. */
    private Side side(PlayerEntity player, World world, List<BlockPos> cells) {
        BlockState[] box = new BlockState[cells.size()];
        for (int i = 0; i < box.length; i++) box[i] = world.getBlockState(cells.get(i));
        int[] counts = new int[items.size()];
        PlayerInventory inventory = player.getInventory();
        // size() is the 36 main slots plus the equipment ones, armour and offhand (EQUIPMENT_SLOTS, 1.21.11).
        for (int slot = 0; slot < inventory.size(); slot++) count(inventory.getStack(slot), counts);
        ItemStack cursor = player.currentScreenHandler.getCursorStack();
        return new Side(box, counts, cursor.isEmpty() ? "" : itemName(cursor.getItem()), inventory.getSelectedSlot());
    }

    /** One stack into the counts, and what a shulker box (any item with a container) holds, as if carried loose. */
    private void count(ItemStack stack, int[] counts) {
        if (stack.isEmpty()) return;
        int k = items.indexOf(stack.getItem());
        if (k >= 0) counts[k] += stack.getCount();
        ContainerComponent contents = stack.get(DataComponentTypes.CONTAINER);
        if (contents != null) for (ItemStack inner : contents.iterateNonEmpty()) count(inner, counts);
    }

    /** "3 (2 client-only, 1 server-only, 0 different)". */
    private static String split(Map<BlockPos, Mismatch> cells, String clientOnly, String serverOnly, String different) {
        int[] kinds = new int[3];
        for (Mismatch m : cells.values()) {
            switch (m.kind()) {
                case "client-only" -> kinds[0]++;
                case "server-only" -> kinds[1]++;
                default -> kinds[2]++;
            }
        }
        return cells.size() + " (" + kinds[0] + clientOnly + kinds[1] + serverOnly + kinds[2] + different + ")";
    }

    /** The pairs the ghost cells had, most frequent first: "client obsidian, server air ×2". */
    private static String pairs(Map<BlockPos, Mismatch> cells) {
        Map<String, Integer> byPair = new TreeMap<>();
        for (Mismatch m : cells.values()) byPair.merge(m.describe(), 1, Integer::sum);
        return byPair.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .map(e -> e.getKey() + " ×" + e.getValue()).reduce((a, b) -> a + "; " + b).orElse("");
    }

    private String differences(int[] difference) {
        List<String> parts = new ArrayList<>();
        for (int k = 0; k < difference.length; k++) parts.add(itemNames.get(k) + " " + signed(difference[k]));
        return String.join(", ", parts);
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : String.valueOf(value);
    }

    private static String blockName(BlockState state) {
        return Registries.BLOCK.getId(state.getBlock()).getPath();
    }

    private static String itemName(Item item) {
        return Registries.ITEM.getId(item).getPath();
    }

    private boolean backAtT0() {
        return after.clientSlot() == t0ClientSlot && after.serverSlot() == t0ServerSlot;
    }

    private String slotAtT0Words() {
        if (backAtT0()) return "yes (slot " + t0ClientSlot + ")";
        return "no (slot " + t0ClientSlot + " at T0, " + after.clientSlot() + " at the end"
            + (after.clientSlot() == after.serverSlot() ? "" : ", " + after.serverSlot() + " on the server") + ")";
    }

    /** The lab trace's line. */
    String traceLine() {
        StringBuilder line = new StringBuilder("- Sync: ").append(samples)
            .append(" comparison(s) of the client with the server during the fight, one a second, and one ")
            .append(EVERY).append(" ticks after it. Ghost cells: ")
            .append(split(ghosts, " client-only, ", " server-only, ", " with two different blocks"))
            .append(", at most ").append(mostAtOneSample).append(" at one comparison, ").append(steadyGhosts.size())
            .append(" of them the same at every tick in between");
        if (!ghosts.isEmpty()) line.append(" (").append(pairs(ghosts)).append(")");
        line.append(". Inventory, the largest difference (client minus server) of an item whose counts differed at two"
            + " comparisons running: ").append(differences(largestDifference)).append(" (").append(desyncSamples)
            .append(" comparison(s) with any). Cursor not empty at ").append(cursorSamples).append(" comparison(s) (")
            .append(clientCursorSamples).append(" on the client, ").append(serverCursorSamples).append(" on the server)");
        if (!cursorItems.isEmpty()) line.append(": ").append(String.join(", ", cursorItems));
        line.append(". Selected slot different on the two sides at two comparisons running: ").append(slotMismatches)
            .append(" time(s). After the fight: ghost cells ")
            .append(split(after.ghosts(), " client-only, ", " server-only, ", " with two different blocks"))
            .append(", ").append(after.steady().size()).append(" steady");
        if (!after.ghosts().isEmpty()) line.append(" (").append(pairs(after.ghosts())).append(")");
        line.append("; inventory ").append(differences(after.difference())).append("; cursor ")
            .append(after.clientCursor().isEmpty() && after.serverCursor().isEmpty() ? "empty on both sides"
                : "holding " + (after.clientCursor().isEmpty() ? "nothing" : after.clientCursor()) + " on the client, "
                + (after.serverCursor().isEmpty() ? "nothing" : after.serverCursor()) + " on the server")
            .append("; selected slot ").append(after.clientSlot() == after.serverSlot() ? "the same on both sides"
                : "different on the two sides").append(", back at the one held at T0: ").append(slotAtT0Words())
            .append(".\n");
        return line.toString();
    }

    /** The lab run's row in its sync table ({@code LabWorstCase.SYNC}). */
    String row(String variant, int run) {
        String cursor = cursorSamples + (cursorItems.isEmpty() ? "" : " (" + String.join(", ", cursorItems) + ")");
        List<String> largest = new ArrayList<>();
        for (int d : largestDifference) largest.add(signed(d));
        return String.format(Locale.ROOT, "| %s | %d | %s | %d | %d | %s | %s | %s | %d | %s |%n", variant, run,
            split(ghosts, " / ", " / ", ""), mostAtOneSample, steadyGhosts.size(), split(after.ghosts(), " / ", " / ", ""),
            String.join(", ", largest), cursor, slotMismatches, slotAtT0Words());
    }
}
