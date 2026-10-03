package com.xploits.bench;

import com.xploits.bench.core.InventoryLedger;
import com.xploits.printer.core.Pos;
import com.xploits.restock.MarkStore;
import com.xploits.restock.Mover;
import com.xploits.restock.PrinterMarker;
import com.xploits.restock.Restock;
import com.xploits.restock.TargetSource;
import com.xploits.restock.core.MarkBook;
import com.xploits.restock.core.RestockReason;
import com.xploits.restock.core.RestockTrip;
import com.xploits.restock.core.ShulkersLeft;
import com.xploits.restock.core.UnpackPlan;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3i;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * One restock run on the bench (restock spec §6), shared by every restock CHECK. Arrange: the bare loadout, a kit, the
 * scene's chests with their contents (shulker boxes with theirs), all confirmed on the client. T0: the rules recorder
 * and the server's judge on, the bench seams handed to restock, the marks seeded, stash-keeper's index out of the way,
 * every item of the player and the chests counted on the server, restock on, the sync watch over the build, the
 * chests, F's neighbourhood (where a shulker box is set down) and a margin. Offsets from F only; nothing here prints a
 * position.
 */
final class RestockScene {
    /** Blocks around the build and the chests, each way, that the sync watch also covers. */
    private static final int MARGIN = 2;
    /**
     * Blocks around F, each way, where a shulker box restock set down is looked for: the cells a box can be set on are
     * at most three blocks from the player, who is at most a few blocks from F.
     */
    private static final int SHULKER_AREA = 8;
    /** Ticks the client gets to show what the server was given. */
    private static final int CLIENT_TICKS = 40;
    private static final String DIMENSION = "minecraft:overworld";

    /**
     * One stack: a slot (of the player's 36, or of a chest's 27), the item, the count; a box may hold stacks (each at
     * its own slot of the box) and carry a name ("" for none).
     */
    record Stack(int slot, Item item, int count, List<Stack> inside, String name) {
        Stack {
            inside = List.copyOf(inside);
            Objects.requireNonNull(name, "name");
        }

        Stack(int slot, Item item, int count) {
            this(slot, item, count, List.of(), "");
        }
    }

    /** A chest of the scene: where, what it holds, and its state (one half of a double chest names its type and facing). */
    record Chest(Vec3i at, List<Stack> contents, BlockState state) {
        Chest {
            contents = List.copyOf(contents);
        }

        /** A single chest. */
        Chest(Vec3i at, List<Stack> contents) {
            this(at, contents, Blocks.CHEST.getDefaultState());
        }
    }

    /** A marked container and the spot it was marked from. */
    record MarkAt(Vec3i container, Vec3i stand) {
    }

    /**
     * What a run left, never a position: whether restock is still on and its last reason, the trips done, what left the
     * player and the chests without being found again (a shulker box standing around F, with its contents, and items on
     * the ground count as found: {@code drops} and {@link #standingShulkers} say where they are), what the player
     * carries at the end, items on the ground, whether the player is back at F, the rules check, the walking clicks,
     * the interactions, the server's re-check, the sync watch, every print-mode switch, whether the printer marker
     * is still on disk, and owner ruling R43's clicks in the player's own inventory the server's judge let through.
     */
    record Outcome(boolean on, Optional<RestockReason> reason, int trips, Map<String, Long> lost,
                   Map<String, Long> player, int drops, boolean home, boolean rulesClean, String rules,
                   int walkingClicks, int interacts, boolean judgeClean, String judge, SyncWatch.Result sync,
                   List<Boolean> printer, boolean markerLeft, int judgedPlaces, int clicks, int closes,
                   boolean containerClean, String container, boolean serverHome, boolean screenClear, int ownMoves) {
    }

    private final BenchSchematic schematic;
    private final List<Stack> kit;
    private final List<Chest> chests;
    private final List<MarkAt> marks;
    private BlockPos origin;
    private String name;
    private Restock restock;
    private PacketRecorder recorder;
    private BenchPrintSwitch printer;
    private Mover mover;
    private SyncWatch sync;
    private Map<String, Long> atT0;

    RestockScene(BenchSchematic schematic, List<Stack> kit, List<Chest> chests, List<MarkAt> marks) {
        this.schematic = schematic;
        this.kit = List.copyOf(kit);
        this.chests = List.copyOf(chests);
        this.marks = List.copyOf(marks);
    }

    static Stack stack(int slot, Item item, int count) {
        return new Stack(slot, item, count);
    }

    /** A plain shulker box in {@code slot} holding {@code inside} (each at its slot of the box), named unless "". */
    static Stack shulker(int slot, String name, List<Stack> inside) {
        return new Stack(slot, Items.SHULKER_BOX, 1, inside, name);
    }

    /**
     * The stack as an item: a box with its contents in their slots ({@code DataComponentTypes.CONTAINER}) and its
     * name.
     */
    static ItemStack itemOf(Stack s) {
        ItemStack stack = new ItemStack(s.item(), s.count());
        if (!s.inside().isEmpty()) {
            int size = s.inside().stream().mapToInt(Stack::slot).max().orElse(0) + 1;
            List<ItemStack> slots = new ArrayList<>(Collections.nCopies(size, ItemStack.EMPTY));
            for (Stack in : s.inside()) slots.set(in.slot(), itemOf(in));
            stack.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(slots));
        }
        if (!s.name().isEmpty()) stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(s.name()));
        return stack;
    }

    // --- arrange --------------------------------------------------------------------------------------------------

    void arrange(Bench bench) {
        bench.arena().bare();
        origin = bench.arena().at(Vec3i.ZERO);
        name = bench.player();
        bench.onServer(srv -> {
            PlayerInventory inventory = Arena.player(srv, name).getInventory();
            for (Stack s : kit) inventory.setStack(s.slot(), itemOf(s));
            ServerWorld w = srv.getOverworld();
            for (Chest c : chests) {
                BlockPos pos = origin.add(c.at());
                w.setBlockState(pos, c.state());
                if (w.getBlockEntity(pos) instanceof ChestBlockEntity be) {
                    for (Stack s : c.contents()) be.setStack(s.slot(), itemOf(s));
                    be.markDirty();
                }
            }
        });
        Map<String, Long> expected = bench.fromServer(srv -> carried(Arena.player(srv, name)));
        awaitClient(bench, client -> carried(client.player).equals(expected) && chests.stream()
            .allMatch(c -> client.world.getBlockState(origin.add(c.at())).equals(c.state())), "the restock scene");
    }

    /** Arrange: the hotbar slot the run starts with, selected on the client and synced by vanilla's own tick. */
    void selectSlot(Bench bench, int slot) {
        bench.onClient(client -> client.player.getInventory().setSelectedSlot(slot));
        awaitClient(bench, client -> client.player.getInventory().getSelectedSlot() == slot, "the selected slot");
        bench.ticks(2);
    }

    // --- T0 and the run -------------------------------------------------------------------------------------------

    /** T0. {@code printing}: the fake print mode at the start. {@code litematica}: restock reads Litematica, not the bench. */
    void start(Bench bench, boolean printing, boolean litematica) {
        start(bench, printing, litematica, true);
    }

    /** T0, with {@code use-carried-shulkers} as given (on by default; the negative variants turn it off). */
    void start(Bench bench, boolean printing, boolean litematica, boolean useCarriedShulkers) {
        restock = bench.meteor(Restock.class);
        bench.setting(restock, "Material", "use-stash-keeper", false);
        bench.setting(restock, "Material", "use-carried-shulkers", useCarriedShulkers);
        recorder = PacketRecorder.start(bench);
        PlaceJudge.start(name);
        bench.atDespawn(PlaceJudge::stop);
        printer = new BenchPrintSwitch(printing);
        TargetSource source = litematica ? null : new BenchTargetSource(schematic, origin);
        bench.onClient(client -> {
            mover = new BenchMover(client);
            restock.useForBench(source, mover, printer);
            MarkStore.clear();
            for (MarkAt m : marks) {
                MarkStore.Result marked = MarkStore.toggle(new MarkBook.Mark(DIMENSION, pos(origin.add(m.container())),
                    pos(origin.add(m.stand()))));
                if (marked != MarkStore.Result.MARKED) throw new BenchException("a scene mark was not saved: " + marked);
            }
            try {
                Files.deleteIfExists(PrinterMarker.file());
            } catch (IOException e) {
                throw new BenchException("an old printer marker could not be removed");
            }
        });
        bench.atDespawn(() -> bench.onClient(client -> {
            restock.useForBench(null, null, null);
            MarkStore.clear();
        }));
        atT0 = bench.fromServer(srv -> everything(srv));
        bench.start(false, Restock.class);
        sync = new SyncWatch(bench, feet -> watched(), items(), itemNames());
    }

    /** Restock on again after it turned itself off (the Litematica CHECK's phases); T0 stays the first one. */
    void enableAgain(Bench bench) {
        bench.onClient(client -> restock.enable());
    }

    /** One tick, then the sync watch. */
    void tick(Bench bench) {
        bench.ticks(1);
        sync.tick(bench.sinceT0());
    }

    /** Up to {@code maxTicks} ticks, ending early when restock turns itself off or {@code until} holds. */
    void run(Bench bench, int maxTicks, BooleanSupplier until) {
        for (int i = 0; i < maxTicks && on(bench) && !until.getAsBoolean(); i++) tick(bench);
    }

    boolean on(Bench bench) {
        return bench.fromClient(client -> restock.isActive());
    }

    int trips(Bench bench) {
        return bench.fromClient(client -> restock.tripsDone());
    }

    Optional<RestockTrip.Phase> phase(Bench bench) {
        return bench.fromClient(client -> restock.tripPhase());
    }

    /** Shulker boxes standing as blocks around F, read on the server. */
    int standingShulkers(Bench bench) {
        return bench.fromServer(srv -> {
            ServerWorld w = srv.getOverworld();
            int n = 0;
            for (BlockPos b : BlockPos.iterate(origin.add(-SHULKER_AREA, -1, -SHULKER_AREA),
                origin.add(SHULKER_AREA, 4, SHULKER_AREA))) {
                if (w.getBlockState(b).getBlock() instanceof ShulkerBoxBlock) n++;
            }
            return n;
        });
    }

    /**
     * Server thread read: what the player carries loose — every slot and the cursor, a shulker box counted as one item
     * and never what it holds. The deep count ({@link #carried}) stays the nothing-lost check; this one tells a take
     * out of a box from a box that came back as it went.
     */
    Map<String, Long> loose(Bench bench) {
        return bench.fromServer(srv -> {
            ServerPlayerEntity player = Arena.player(srv, name);
            Map<String, Long> counts = new TreeMap<>();
            PlayerInventory inventory = player.getInventory();
            for (int slot = 0; slot < inventory.size(); slot++) addLoose(counts, inventory.getStack(slot));
            addLoose(counts, player.currentScreenHandler.getCursorStack());
            return counts;
        });
    }

    /** Server thread read: what the shulker boxes the player carries hold, by id; empty when every box is empty. */
    Map<String, Long> inBoxes(Bench bench) {
        return bench.fromServer(srv -> {
            ServerPlayerEntity player = Arena.player(srv, name);
            Map<String, Long> counts = new TreeMap<>();
            PlayerInventory inventory = player.getInventory();
            for (int slot = 0; slot < inventory.size(); slot++) addInside(counts, inventory.getStack(slot));
            addInside(counts, player.currentScreenHandler.getCursorStack());
            return counts;
        });
    }

    Optional<UnpackPlan.Phase> unpackPhase(Bench bench) {
        return bench.fromClient(client -> restock.unpackPhase());
    }

    boolean unpackDigging(Bench bench) {
        return bench.fromClient(client -> restock.unpackDigging());
    }

    ShulkersLeft lastShulkersLeft(Bench bench) {
        return bench.fromClient(client -> restock.lastShulkersLeft());
    }

    Optional<RestockReason> lastDrained(Bench bench) {
        return bench.fromClient(client -> restock.lastDrained());
    }

    /** The borrowed shulker boxes the player still carries, as restock counts them. */
    int borrowed(Bench bench) {
        return bench.fromClient(client -> restock.borrowedCount());
    }

    /**
     * The bench builds the whole schematic itself, on the server: the build is done (the fake printer places nothing).
     */
    void fillBuild(Bench bench) {
        bench.onServer(srv -> {
            ServerWorld w = srv.getOverworld();
            schematic.blocks().forEach((cell, block) -> w.setBlockState(origin.add(cell), block.getDefaultState()));
        });
    }

    /**
     * Server thread (rulings R70, R71, trigger (b)): the first shulker box holding items in the player's slots 0–35
     * goes back into the first free slot of the chest at {@code chest}, as a player putting a just-carried box back
     * would, or a server refusing the carry. The open chest screen sends both changes to the client. False when there
     * is no such box, no chest there or no free slot (nothing moved).
     */
    boolean putCarriedBoxBack(Bench bench, Vec3i chest) {
        return bench.fromServer(srv -> {
            if (!(srv.getOverworld().getBlockEntity(origin.add(chest)) instanceof ChestBlockEntity be)) return false;
            PlayerInventory inventory = Arena.player(srv, name).getInventory();
            int box = -1;
            for (int slot = 0; slot < PlayerInventory.MAIN_SIZE && box < 0; slot++) {
                ItemStack stack = inventory.getStack(slot);
                ContainerComponent inside = stack.get(DataComponentTypes.CONTAINER);
                if (Block.getBlockFromItem(stack.getItem()) instanceof ShulkerBoxBlock && inside != null
                    && inside.iterateNonEmpty().iterator().hasNext()) {
                    box = slot;
                }
            }
            int free = -1;
            for (int slot = 0; slot < be.size() && free < 0; slot++) {
                if (be.getStack(slot).isEmpty()) free = slot;
            }
            if (box < 0 || free < 0) return false;
            be.setStack(free, inventory.removeStack(box));
            be.markDirty();
            return true;
        });
    }

    /** Server thread read: what the chest at {@code at} holds, by id, what its shulker boxes hold included. */
    Map<String, Long> chest(Bench bench, Vec3i at) {
        return bench.fromServer(srv -> {
            Map<String, Long> counts = new TreeMap<>();
            if (srv.getOverworld().getBlockEntity(origin.add(at)) instanceof ChestBlockEntity be) {
                for (int i = 0; i < be.size(); i++) add(counts, be.getStack(i));
            }
            return counts;
        });
    }

    /** Server thread read: what the player's slot {@code slot} holds, by id, a box's contents included. */
    Map<String, Long> slotHolds(Bench bench, int slot) {
        return bench.fromServer(srv -> {
            Map<String, Long> counts = new TreeMap<>();
            add(counts, Arena.player(srv, name).getInventory().getStack(slot));
            return counts;
        });
    }

    /**
     * Server thread (ruling R54): the stacks in the player's {@code slots} go into the first free slots of the chest at
     * {@code chest}, as a player putting them away by hand would (nothing is lost: the chest is the scene's, and every
     * item of it is counted). With no screen open the player's own screen sends the emptied slots to the client. False
     * when there is no chest there, a slot is empty or the chest has no room for them all (nothing moved).
     */
    boolean putAway(Bench bench, Vec3i chest, int... slots) {
        return bench.fromServer(srv -> {
            if (!(srv.getOverworld().getBlockEntity(origin.add(chest)) instanceof ChestBlockEntity be)) return false;
            PlayerInventory inventory = Arena.player(srv, name).getInventory();
            List<Integer> free = new ArrayList<>();
            for (int slot = 0; slot < be.size(); slot++) {
                if (be.getStack(slot).isEmpty()) free.add(slot);
            }
            if (free.size() < slots.length) return false;
            for (int slot : slots) {
                if (inventory.getStack(slot).isEmpty()) return false;
            }
            for (int i = 0; i < slots.length; i++) be.setStack(free.get(i), inventory.removeStack(slots[i]));
            be.markDirty();
            return true;
        });
    }

    Restock restock() {
        return restock;
    }

    BenchPrintSwitch printer() {
        return printer;
    }

    Mover mover() {
        return mover;
    }

    BlockPos origin() {
        return origin;
    }

    void setServerBlock(Bench bench, Vec3i offset, Block block) {
        bench.onServer(srv -> srv.getOverworld().setBlockState(origin.add(offset), block.getDefaultState()));
    }

    // --- the end --------------------------------------------------------------------------------------------------

    /** The last sync comparison first, so every reading below describes the same end state, then the bench's close. */
    Outcome finish(Bench bench) {
        sync.finalCheck();
        boolean on = on(bench);
        Optional<RestockReason> reason = bench.fromClient(client -> restock.lastReason());
        int trips = trips(bench);
        List<Boolean> switched = bench.fromClient(client -> printer.history());
        Map<String, Long> atEnd = bench.fromServer(srv -> everything(srv));
        Map<String, Long> player = bench.fromServer(srv -> carried(Arena.player(srv, name)));
        int drops = bench.fromServer(srv -> srv.getOverworld().getEntitiesByType(EntityType.ITEM,
            new Box(origin).expand(24), e -> true).size());
        boolean home = bench.fromServer(srv -> Arena.player(srv, name).getBlockPos().equals(origin));
        boolean markerLeft = Files.exists(PrinterMarker.file());
        boolean serverHome = bench.fromServer(srv -> {
            ServerPlayerEntity p = Arena.player(srv, name);
            return p.currentScreenHandler == p.playerScreenHandler;
        });
        boolean screenClear = bench.fromClient(client -> client.currentScreen == null);
        PlaceJudge.stop();
        int judgedPlaces = PlaceJudge.places();
        boolean judged = judgedPlaces == recorder.interacts() && PlaceJudge.verdicts().stream().allMatch(PlaceJudge.Verdict::ok);
        List<PlaceJudge.ContainerVerdict> seen = PlaceJudge.containerVerdicts();
        int seenClicks = (int) seen.stream().filter(v -> !v.close()).count();
        int seenCloses = seen.size() - seenClicks;
        boolean containerClean = seenClicks == recorder.clicks() && seenCloses == recorder.closes()
            && seen.stream().allMatch(PlaceJudge.ContainerVerdict::ok);
        int ownMoves = PlaceJudge.ownMoves();
        Outcome outcome = new Outcome(on, reason, trips, InventoryLedger.lost(atT0, atEnd, Map.of()), player, drops, home,
            recorder.violations().isEmpty(), recorder.violationWords(), recorder.walkingClicks(), recorder.interacts(),
            judged, PlaceJudge.words(), sync.result(), switched, markerLeft, judgedPlaces, recorder.clicks(),
            recorder.closes(), containerClean, PlaceJudge.containerWords(), serverHome, screenClear, ownMoves);
        bench.finish();
        return outcome;
    }

    /** What every restock run must satisfy (restock spec §6), with no click in the player's own inventory. */
    static void checkClean(Outcome o) {
        checkClean(o, 0);
    }

    /**
     * What every restock run must satisfy, whatever it fetched (restock spec §6); {@code ownMoves}: owner ruling R43's
     * clicks in the player's own inventory the run makes, as the server's judge let them through (0 but in one CHECK).
     */
    static void checkClean(Outcome o, int ownMoves) {
        Bench.check(o.rulesClean(), "restock broke an anticheat rule: " + o.rules());
        Bench.check(o.walkingClicks() == 0, "container clicks while walking, in " + o.walkingClicks() + " tick(s)");
        Bench.check(o.judgeClean(), "the server judged " + o.judgedPlaces() + " of " + o.interacts()
            + " interaction(s) sent, and its re-check: " + o.judge());
        Bench.check(o.containerClean(), "the server's container check failed (the client sent " + o.clicks()
            + " slot click(s) and " + o.closes() + " close(s)): " + o.container());
        Bench.check(o.serverHome(), "the server still has a container screen open for the player");
        Bench.check(o.screenClear(), "the client still shows a screen");
        Bench.check(o.lost().isEmpty(), "items lost: " + InventoryLedger.words(o.lost()));
        Bench.check(o.drops() == 0, "items on the ground: " + o.drops());
        Bench.check(o.sync().clean(), "the client and the server disagree: " + o.sync().words());
        Bench.check(!o.markerLeft(), "the printer marker was left on disk");
        Bench.check(o.ownMoves() == ownMoves, "clicks in the player's own inventory seen by the server: " + o.ownMoves()
            + ", " + ownMoves + " expected");
    }

    static String words(Optional<RestockReason> reason) {
        return reason.map(Enum::name).orElse("no reason");
    }

    // --- helpers (server or client thread as named) ---------------------------------------------------------------

    /**
     * Server thread: every item of the player and of the scene's chests, by id, shulker contents included; and (phase
     * B) a shulker box standing around F with its contents, and anything lying on the ground — found, not lost.
     */
    private Map<String, Long> everything(MinecraftServer srv) {
        Map<String, Long> counts = new TreeMap<>(carried(Arena.player(srv, name)));
        ServerWorld w = srv.getOverworld();
        for (Chest c : chests) {
            if (w.getBlockEntity(origin.add(c.at())) instanceof ChestBlockEntity be) {
                for (int i = 0; i < be.size(); i++) add(counts, be.getStack(i));
            }
        }
        // Phase B: a box restock set down and left standing, with its contents, and anything lying on the ground.
        for (BlockPos b : BlockPos.iterate(origin.add(-SHULKER_AREA, -1, -SHULKER_AREA),
            origin.add(SHULKER_AREA, 4, SHULKER_AREA))) {
            if (w.getBlockEntity(b) instanceof ShulkerBoxBlockEntity box) {
                counts.merge(Registries.ITEM.getId(w.getBlockState(b).getBlock().asItem()).toString(), 1L, Long::sum);
                for (int i = 0; i < box.size(); i++) add(counts, box.getStack(i));
            }
        }
        for (ItemEntity e : w.getEntitiesByType(EntityType.ITEM, new Box(origin).expand(24), x -> true)) {
            add(counts, e.getStack());
        }
        return counts;
    }

    /** Either thread, for its own player: every item carried, by id — inventory, armour, offhand, cursor, shulker contents. */
    static Map<String, Long> carried(PlayerEntity player) {
        Map<String, Long> counts = new TreeMap<>();
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) add(counts, inventory.getStack(slot));
        add(counts, player.currentScreenHandler.getCursorStack());
        return counts;
    }

    private static void add(Map<String, Long> counts, ItemStack stack) {
        if (stack.isEmpty()) return;
        counts.merge(Registries.ITEM.getId(stack.getItem()).toString(), (long) stack.getCount(), Long::sum);
        addInside(counts, stack);
    }

    /** The stack itself only, never what it holds. */
    private static void addLoose(Map<String, Long> counts, ItemStack stack) {
        if (stack.isEmpty()) return;
        counts.merge(Registries.ITEM.getId(stack.getItem()).toString(), (long) stack.getCount(), Long::sum);
    }

    /** What the stack holds ({@code DataComponentTypes.CONTAINER}), recursively, never the stack itself. */
    private static void addInside(Map<String, Long> counts, ItemStack stack) {
        ContainerComponent contents = stack.get(DataComponentTypes.CONTAINER);
        if (contents != null) for (ItemStack inner : contents.iterateNonEmpty()) add(counts, inner);
    }

    /**
     * The build's box, the chests and F ± 1, plus {@value #MARGIN} blocks each way, from the floor up; fixed for the
     * run. With the margin, F ± 3 is where a shulker box restock sets down can stand.
     */
    private List<BlockPos> watched() {
        int minX = schematic.min().getX();
        int maxX = schematic.max().getX();
        int maxY = schematic.max().getY();
        int minZ = schematic.min().getZ();
        int maxZ = schematic.max().getZ();
        for (Chest c : chests) {
            minX = Math.min(minX, c.at().getX());
            maxX = Math.max(maxX, c.at().getX());
            maxY = Math.max(maxY, c.at().getY());
            minZ = Math.min(minZ, c.at().getZ());
            maxZ = Math.max(maxZ, c.at().getZ());
        }
        // Phase B: every restock CHECK watches a little more; the sync watch only gets stricter.
        minX = Math.min(minX, -1);
        maxX = Math.max(maxX, 1);
        minZ = Math.min(minZ, -1);
        maxZ = Math.max(maxZ, 1);
        List<BlockPos> cells = new ArrayList<>();
        for (int y = -1; y <= maxY + MARGIN; y++) {
            for (int x = minX - MARGIN; x <= maxX + MARGIN; x++) {
                for (int z = minZ - MARGIN; z <= maxZ + MARGIN; z++) cells.add(origin.add(x, y, z));
            }
        }
        return List.copyOf(cells);
    }

    /** The kit's and the chests' items in order, what their boxes hold included, stone always among them (sync). */
    private List<Item> items() {
        Set<Item> items = new LinkedHashSet<>();
        for (Stack s : kit) addItems(items, s);
        for (Chest c : chests) for (Stack s : c.contents()) addItems(items, s);
        items.add(Items.STONE);
        return List.copyOf(items);
    }

    /** A stack's item and, for a shulker box, the items it holds, recursively. */
    private static void addItems(Set<Item> items, Stack s) {
        items.add(s.item());
        for (Stack in : s.inside()) addItems(items, in);
    }

    private List<String> itemNames() {
        return items().stream().map(item -> Registries.ITEM.getId(item).getPath()).toList();
    }

    private static Pos pos(BlockPos b) {
        return new Pos(b.getX(), b.getY(), b.getZ());
    }

    private static void awaitClient(Bench bench, Predicate<MinecraftClient> ready, String what) {
        for (int i = 0; i < CLIENT_TICKS; i++) {
            if (bench.fromClient(client -> client.player != null && client.world != null && ready.test(client))) return;
            bench.ticks(1);
        }
        throw new BenchException("the client did not get " + what + " within " + CLIENT_TICKS + " ticks");
    }
}
