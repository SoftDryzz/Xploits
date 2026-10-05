package com.xploits.bench;

import com.xploits.bench.core.InventoryLedger;
import com.xploits.restock.core.RestockText;
import com.xploits.restock.core.UnpackPlan;
import net.minecraft.item.Items;
import net.minecraft.util.math.Vec3i;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CHECK {@code restock-shulker-no-room} (rulings R54, R60; owner ruling R40): the wall needs stone and every one of the
 * player's 36 slots is full (a pickaxe and dirt). Two marked chests, neither opened before: the nearer one holds stone
 * only inside a shulker box, the farther one nothing. With no free hotbar slot and one more, restock cannot carry a
 * box: it opens the nearer chest and passes it over without a word and without marking it stale, tries the farther one
 * (stale, said), and only then — nothing else left — says that the stone is only inside boxes and the hotbar had no
 * room, and goes back to the build. Restock does not stop. Then the bench puts a hotbar stack and a main-inventory
 * stack away into an unmarked chest, as the player would; within a minute (restock's "tried again" for a material found
 * nowhere) restock goes to the nearer chest again, carries the box — so it was never marked stale — and unpacks it at
 * the build.
 */
final class RestockShulkerNoRoom implements Scenario {
    /** The nearer chest (about six blocks): stone only inside a box. Behind F, off the wall's line (the walker's). */
    static final Vec3i BOXES = RestockShulkerBorrowed.CHEST;
    static final Vec3i BOXES_STAND = RestockShulkerBorrowed.STAND;
    /** The farther chest (about seven blocks): empty. */
    static final Vec3i EMPTY = new Vec3i(-6, 0, -4);
    static final Vec3i EMPTY_STAND = new Vec3i(-5, 0, -4);
    /** Unmarked: where the bench puts the player's stacks away. */
    static final Vec3i STORAGE = new Vec3i(6, 0, -6);
    /** A hotbar slot and a main-inventory slot the bench empties. */
    private static final int HOTBAR_FREED = 8;
    private static final int MAIN_FREED = 35;
    private static final String STONE = "minecraft:stone";
    private static final String BOX = "minecraft:shulker_box";
    private static final int FIRST_TICKS = 600;
    /**
     * The session tick at which restock looks again for a material found nowhere: {@code RestockSession.watchSources}
     * retries at {@code tick % NOWHERE_RETRY_TICKS == 0} (1200), counted from the session's start (T0), not from the
     * "nowhere" line. Restock's own constant is private; this mirrors it.
     */
    private static final int RETRY_TICK = 1200;
    /** After the retry: the second trip, the carry's answer wait and the unpack take about 180 ticks; room to spare. */
    private static final int AFTER_RETRY_TICKS = 400;
    /** Left of the budget for the end readings and the sync watch's last comparison (20 ticks). */
    private static final int END_TICKS = 100;

    private RestockScene scene;

    @Override
    public String name() {
        return "restock-shulker-no-room";
    }

    @Override
    public Kind kind() {
        return Kind.CHECK;
    }

    @Override
    public int seconds() {
        return 110;
    }

    @Override
    public void arrange(Bench bench) {
        List<RestockScene.Stack> kit = new ArrayList<>();
        kit.add(RestockScene.stack(1, Items.DIAMOND_PICKAXE, 1));
        for (int slot = 0; slot < 36; slot++) {
            if (slot != 1) kit.add(RestockScene.stack(slot, Items.DIRT, 64));
        }
        scene = new RestockScene(BenchSchematic.WALL, kit,
            List.of(new RestockScene.Chest(BOXES,
                    List.of(RestockScene.shulker(0, "", List.of(RestockScene.stack(0, Items.STONE, 64))))),
                new RestockScene.Chest(EMPTY, List.of()), new RestockScene.Chest(STORAGE, List.of())),
            List.of(new RestockScene.MarkAt(BOXES, BOXES_STAND), new RestockScene.MarkAt(EMPTY, EMPTY_STAND)));
        scene.arrange(bench);
        scene.selectSlot(bench, 0);
    }

    @Override
    public Metrics act(Bench bench) {
        ChatLines chat = ChatLines.start(bench);
        scene.start(bench, true, false);
        scene.run(bench, FIRST_TICKS, () -> scene.trips(bench) >= 1 && scene.phase(bench).isEmpty());
        boolean onAfterFirst = scene.on(bench);
        int tripsAfterFirst = scene.trips(bench);
        Map<String, Long> boxesAfterFirst = scene.chest(bench, BOXES);
        List<Integer> staleAfterFirst = chat.at(RestockText.TRIP_STALE, "material", "stone");
        List<Integer> boxesLine = chat.at(RestockText.NOWHERE_AFTER_TRIP_BOXES, "material", "stone");
        boolean putAway = scene.putAway(bench, STORAGE, HOTBAR_FREED, MAIN_FREED);
        // Bounded by the retry itself (it ends early once the unpack is done), within the run's budget.
        int again = Math.min(Math.max(0, RETRY_TICK - bench.sinceT0()) + AFTER_RETRY_TICKS,
            budgetTicks() - bench.ticksUsed() - END_TICKS);
        scene.run(bench, again, () -> scene.trips(bench) >= 2 && scene.unpackPhase(bench).isEmpty()
            && bench.fromClient(client -> scene.printer().history()).size() >= 4);
        Optional<UnpackPlan.Phase> unpacking = scene.unpackPhase(bench);
        int standing = scene.standingShulkers(bench);
        int borrowed = scene.borrowed(bench);
        Map<String, Long> loose = scene.loose(bench);
        Map<String, Long> inBoxes = scene.inBoxes(bench);
        Map<String, Long> boxesAtEnd = scene.chest(bench, BOXES);
        List<Integer> stale = chat.at(RestockText.TRIP_STALE, "material", "stone");
        List<Integer> boxesLines = chat.at(RestockText.NOWHERE_AFTER_TRIP_BOXES, "material", "stone");
        List<Integer> hotbarFull = chat.at(RestockText.NOWHERE_HOTBAR_FULL, "material", "stone");
        List<Integer> nowhereAfter = chat.at(RestockText.NOWHERE_AFTER_TRIP, "material", "stone");
        // Which of the two start lines it is depends on whether restock holds the printer off (final review m3).
        List<Integer> unpacked = new ArrayList<>(chat.at(RestockText.UNPACK_STARTED, "material", "stone"));
        unpacked.addAll(chat.at(RestockText.UNPACK_STARTED_PRINTER, "material", "stone"));
        RestockScene.Outcome o = scene.finish(bench);
        // The first trip, with no room to carry a box (ruling R54).
        Bench.check(onAfterFirst && tripsAfterFirst == 1, "after the first trip restock is "
            + (onAfterFirst ? "on" : "off") + " with " + tripsAfterFirst + " trip(s), on with one expected");
        Bench.check(boxesAfterFirst.equals(Map.of(BOX, 1L, STONE, 64L)), "after the first trip the nearer chest holds "
            + InventoryLedger.words(boxesAfterFirst) + ", its box of stone expected (nothing could be carried)");
        Bench.check(staleAfterFirst.size() == 1, "\"no stone any more\" said " + staleAfterFirst.size()
            + " time(s) on the first trip, once expected (the farther, empty chest; never the one with the box)");
        Bench.check(boxesLine.size() == 1 && boxesLine.get(0) > staleAfterFirst.get(0),
            "the line that the stone is only inside boxes and the hotbar had no room was said at " + boxesLine
                + ", once and after the farther chest failed expected (ruling R60: only when nothing else is left)");
        Bench.check(putAway, "the bench could not put the player's two stacks away");
        // Within a minute, with a free hotbar slot and one more: the box is carried and unpacked.
        Bench.check(o.on() && o.reason().isEmpty(), "restock ended with " + RestockScene.words(o.reason()));
        Bench.check(o.trips() == 2, "trips back at the build: " + o.trips() + ", two expected");
        Bench.check(unpacking.isEmpty(), "the unpacking did not finish: " + unpacking.map(Enum::name).orElse(""));
        Bench.check(o.printer().equals(List.of(false, true, false, true)),
            "the print mode was switched " + o.printer() + ", off and on around each trip expected");
        Bench.check(stale.size() == 1 && boxesLines.size() == 1 && hotbarFull.isEmpty() && nowhereAfter.isEmpty(),
            "said: \"no stone any more\" " + stale.size() + " time(s), \"only inside boxes\" after a trip "
                + boxesLines.size() + ", \"only inside boxes\" before one " + hotbarFull.size() + ", \"no other"
                + " container\" " + nowhereAfter.size() + "; once, once, never, never expected");
        Bench.check(unpacked.size() == 1, "\"unpacking a box with stone\" said " + unpacked.size()
            + " time(s), once expected");
        Bench.check(boxesAtEnd.isEmpty(), "the nearer chest holds " + InventoryLedger.words(boxesAtEnd)
            + ", nothing expected (its box carried)");
        Bench.check(loose.getOrDefault(STONE, 0L) == 64, "the player carries " + loose.getOrDefault(STONE, 0L)
            + " stone loose, the box's 64 expected (taken out of it)");
        Bench.check(inBoxes.isEmpty(), "the borrowed box still holds " + InventoryLedger.words(inBoxes)
            + ", nothing expected");
        Bench.check(loose.getOrDefault(BOX, 0L) == 1 && borrowed == 1, "the player carries "
            + loose.getOrDefault(BOX, 0L) + " shulker box(es), " + borrowed
            + " borrowed; the one borrowed box expected");
        Bench.check(standing == 0, standing + " shulker box(es) left standing");
        Bench.check(o.interacts() == 5, "block interactions sent: " + o.interacts()
            + ", five expected (both chests, the nearer one again, the placement, the box)");
        Bench.check(o.judge().startsWith("5 interaction(s) and 1 dig start(s) judged"),
            "the server judged " + o.judge());
        Bench.check(o.clicks() == 2 && o.closes() == 4, "slot clicks " + o.clicks() + " and closes " + o.closes()
            + ", two clicks (the carry, the take) and four closes (three chests, the box) expected");
        Bench.check(o.container().contains("already closed by the server 0,"),
            "the server closed a container screen by itself: " + o.container());
        Bench.check(o.home(), "the player is not back where the unpacking began");
        RestockScene.checkClean(o);
        return Metrics.none();
    }
}
