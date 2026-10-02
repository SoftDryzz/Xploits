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
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3i;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * One restock run on the bench (restock spec §6), shared by every restock CHECK. Arrange: the bare loadout, a kit, the
 * scene's chests with their contents, all confirmed on the client. T0: the rules recorder and the server's judge on, the
 * bench seams handed to restock, the marks seeded, stash-keeper's index out of the way, every item of the player and the
 * chests counted on the server, restock on, the sync watch over the build, the chests and a margin. Offsets from F only;
 * nothing here prints a position.
 */
final class RestockScene {
    /** Blocks around the build and the chests, each way, that the sync watch also covers. */
    private static final int MARGIN = 2;
    /** Ticks the client gets to show what the server was given. */
    private static final int CLIENT_TICKS = 40;
    private static final String DIMENSION = "minecraft:overworld";

    /** One stack: a slot (of the player's 36, or of a chest's 27), the item, the count. */
    record Stack(int slot, Item item, int count) {
    }

    record Chest(Vec3i at, List<Stack> contents) {
        Chest {
            contents = List.copyOf(contents);
        }
    }

    /** A marked container and the spot it was marked from. */
    record MarkAt(Vec3i container, Vec3i stand) {
    }

    /**
     * What a run left, never a position: whether restock is still on and its last reason, the trips done, what left the
     * player and the chests without being found again, what the player carries at the end, items on the ground, whether
     * the player is back at F, the rules check, the walking clicks, the interactions, the server's re-check, the sync
     * watch, every print-mode switch, and whether the printer marker is still on disk.
     */
    record Outcome(boolean on, Optional<RestockReason> reason, int trips, Map<String, Long> lost,
                   Map<String, Long> player, int drops, boolean home, boolean rulesClean, String rules,
                   int walkingClicks, int interacts, boolean judgeClean, String judge, SyncWatch.Result sync,
                   List<Boolean> printer, boolean markerLeft, int judgedPlaces, int clicks, int closes,
                   boolean containerClean, String container, boolean serverHome, boolean screenClear) {
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

    // --- arrange --------------------------------------------------------------------------------------------------

    void arrange(Bench bench) {
        bench.arena().bare();
        origin = bench.arena().at(Vec3i.ZERO);
        name = bench.player();
        bench.onServer(srv -> {
            PlayerInventory inventory = Arena.player(srv, name).getInventory();
            for (Stack s : kit) inventory.setStack(s.slot(), new ItemStack(s.item(), s.count()));
            ServerWorld w = srv.getOverworld();
            for (Chest c : chests) {
                BlockPos pos = origin.add(c.at());
                w.setBlockState(pos, Blocks.CHEST.getDefaultState());
                if (w.getBlockEntity(pos) instanceof ChestBlockEntity be) {
                    for (Stack s : c.contents()) be.setStack(s.slot(), new ItemStack(s.item(), s.count()));
                    be.markDirty();
                }
            }
        });
        Map<String, Long> expected = bench.fromServer(srv -> carried(Arena.player(srv, name)));
        awaitClient(bench, client -> carried(client.player).equals(expected) && chests.stream()
            .allMatch(c -> client.world.getBlockState(origin.add(c.at())).isOf(Blocks.CHEST)), "the restock scene");
    }

    // --- T0 and the run -------------------------------------------------------------------------------------------

    /** T0. {@code printing}: the fake print mode at the start. {@code litematica}: restock reads Litematica, not the bench. */
    void start(Bench bench, boolean printing, boolean litematica) {
        restock = bench.meteor(Restock.class);
        bench.setting(restock, "Material", "use-stash-keeper", false);
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
        Outcome outcome = new Outcome(on, reason, trips, InventoryLedger.lost(atT0, atEnd, Map.of()), player, drops, home,
            recorder.violations().isEmpty(), recorder.violationWords(), recorder.walkingClicks(), recorder.interacts(),
            judged, PlaceJudge.words(), sync.result(), switched, markerLeft, judgedPlaces, recorder.clicks(),
            recorder.closes(), containerClean, PlaceJudge.containerWords(), serverHome, screenClear);
        bench.finish();
        return outcome;
    }

    /** What every restock run must satisfy, whatever it fetched (restock spec §6). */
    static void checkClean(Outcome o) {
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
    }

    static String words(Optional<RestockReason> reason) {
        return reason.map(Enum::name).orElse("no reason");
    }

    // --- helpers (server or client thread as named) ---------------------------------------------------------------

    /** Server thread: every item of the player and of the scene's chests, by id, shulker contents included. */
    private Map<String, Long> everything(MinecraftServer srv) {
        Map<String, Long> counts = new TreeMap<>(carried(Arena.player(srv, name)));
        ServerWorld w = srv.getOverworld();
        for (Chest c : chests) {
            if (w.getBlockEntity(origin.add(c.at())) instanceof ChestBlockEntity be) {
                for (int i = 0; i < be.size(); i++) add(counts, be.getStack(i));
            }
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
        ContainerComponent contents = stack.get(DataComponentTypes.CONTAINER);
        if (contents != null) for (ItemStack inner : contents.iterateNonEmpty()) add(counts, inner);
    }

    /** The build's box and the chests, plus {@value #MARGIN} blocks each way, from the floor up; fixed for the run. */
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
        List<BlockPos> cells = new ArrayList<>();
        for (int y = -1; y <= maxY + MARGIN; y++) {
            for (int x = minX - MARGIN; x <= maxX + MARGIN; x++) {
                for (int z = minZ - MARGIN; z <= maxZ + MARGIN; z++) cells.add(origin.add(x, y, z));
            }
        }
        return List.copyOf(cells);
    }

    /** The kit's and the chests' items in order, stone always among them, for the sync watch. */
    private List<Item> items() {
        Set<Item> items = new LinkedHashSet<>();
        for (Stack s : kit) items.add(s.item());
        for (Chest c : chests) for (Stack s : c.contents()) items.add(s.item());
        items.add(Items.STONE);
        return List.copyOf(items);
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
