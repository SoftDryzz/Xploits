package com.xploits.bench;

import com.xploits.bench.core.InventoryLedger;
import net.minecraft.item.Items;
import net.minecraft.util.math.Vec3i;

import java.util.List;
import java.util.Map;

/**
 * CHECK {@code restock-shulker-borrowed} (restock spec §2 phase B, §6; owner ruling R44): the wall needs stone, the player
 * carries only a diamond pickaxe, and the marked chest holds one shulker box with 64 stone in it. Restock walks to the
 * chest, carries the box back, unpacks it at the build (sets it down, takes the stone, breaks it, picks it up) and only
 * then gives the (fake) print mode back; the box is noted as borrowed. The take is proven by the loose count: no stone
 * loose at T0, the box's 64 loose after the unpack and the borrowed box empty (the deep count stays the nothing-lost
 * check). Then the bench builds the wall itself: with the build done, one last trip puts the now empty box back into
 * its chest, which then holds that box alone. Four block interactions (the chest, the placement, the box, the chest
 * again) and one dig, all passing the server's re-check; nothing lost, nothing on the ground, no box left standing; the
 * player back on F.
 */
final class RestockShulkerBorrowed implements Scenario {
    /**
     * Behind F, away from the wall (three blocks in front of it): the bench's walker goes in straight lines, and the wall
     * is built before the last trip — a walk through it would be set back by the server.
     */
    static final Vec3i CHEST = new Vec3i(0, 0, -6);
    static final Vec3i STAND = new Vec3i(0, 0, -5);
    private static final String STONE = "minecraft:stone";
    private static final String BOX = "minecraft:shulker_box";
    private static final int FIRST_TICKS = 700;
    private static final int RETURN_TICKS = 500;

    private RestockScene scene;

    @Override
    public String name() {
        return "restock-shulker-borrowed";
    }

    @Override
    public Kind kind() {
        return Kind.CHECK;
    }

    @Override
    public int seconds() {
        return 60;
    }

    @Override
    public void arrange(Bench bench) {
        scene = new RestockScene(BenchSchematic.WALL, List.of(RestockScene.stack(1, Items.DIAMOND_PICKAXE, 1)),
            List.of(new RestockScene.Chest(CHEST,
                List.of(RestockScene.shulker(0, "", List.of(RestockScene.stack(0, Items.STONE, 64)))))),
            List.of(new RestockScene.MarkAt(CHEST, STAND)));
        scene.arrange(bench);
        scene.selectSlot(bench, 0);
    }

    @Override
    public Metrics act(Bench bench) {
        // Restock still off: the stone is only inside the box in the chest.
        Map<String, Long> looseAtT0 = scene.loose(bench);
        scene.start(bench, true, false);
        scene.run(bench, FIRST_TICKS, () -> scene.trips(bench) >= 1 && scene.unpackPhase(bench).isEmpty()
            && bench.fromClient(client -> scene.printer().history()).size() >= 2);
        int borrowedAfterUnpack = scene.borrowed(bench);
        Map<String, Long> loose = scene.loose(bench);
        Map<String, Long> inBoxes = scene.inBoxes(bench);
        int standingAfterUnpack = scene.standingShulkers(bench);
        scene.fillBuild(bench);
        scene.run(bench, RETURN_TICKS, () -> scene.trips(bench) >= 2 && scene.phase(bench).isEmpty());
        int borrowedAtEnd = scene.borrowed(bench);
        Map<String, Long> chest = scene.chest(bench, CHEST);
        RestockScene.Outcome o = scene.finish(bench);
        Bench.check(looseAtT0.getOrDefault(STONE, 0L) == 0, "the player carried " + looseAtT0.getOrDefault(STONE, 0L)
            + " stone loose at T0, none expected (only in the chest's box)");
        Bench.check(borrowedAfterUnpack == 1, "borrowed boxes after the unpack: " + borrowedAfterUnpack + ", one expected");
        Bench.check(loose.getOrDefault(STONE, 0L) == 64, "after the unpack the player carries "
            + loose.getOrDefault(STONE, 0L) + " stone loose, the box's 64 expected (taken out of it)");
        Bench.check(inBoxes.isEmpty(), "after the unpack the borrowed box still holds " + InventoryLedger.words(inBoxes)
            + ", nothing expected");
        Bench.check(loose.getOrDefault(BOX, 0L) == 1, "after the unpack the player carries "
            + loose.getOrDefault(BOX, 0L) + " shulker box(es), the borrowed one expected");
        Bench.check(standingAfterUnpack == 0, standingAfterUnpack + " shulker box(es) left standing after the unpack");
        Bench.check(o.on() && o.reason().isEmpty(), "restock ended with " + RestockScene.words(o.reason()));
        Bench.check(o.trips() == 2, "trips back at the build: " + o.trips()
            + ", two expected (the carry, and the last trip that gives the box back)");
        Bench.check(o.printer().equals(List.of(false, true, false, true)),
            "the print mode was switched " + o.printer() + ", off and on around each trip expected");
        Bench.check(borrowedAtEnd == 0, "borrowed boxes still carried: " + borrowedAtEnd + ", none expected");
        Bench.check(chest.equals(Map.of(BOX, 1L)), "the chest holds " + InventoryLedger.words(chest)
            + ", the borrowed box alone and empty expected");
        Bench.check(o.player().getOrDefault(BOX, 0L) == 0 && o.player().getOrDefault(STONE, 0L) == 64,
            "the player ends with " + o.player().getOrDefault(BOX, 0L) + " shulker box(es) and "
                + o.player().getOrDefault(STONE, 0L) + " stone, none and the box's 64 expected");
        Bench.check(o.interacts() == 4, "block interactions sent: " + o.interacts()
            + ", four expected (the chest, the placement, the box, the chest again)");
        Bench.check(o.judge().startsWith("4 interaction(s) and 1 dig start(s) judged"), "the server judged " + o.judge());
        Bench.check(o.clicks() == 3 && o.closes() == 3, "slot clicks " + o.clicks() + " and closes " + o.closes()
            + ", three each expected (the carry, the take, the give-back)");
        // Ruling R37's excuse is for a screen the server closed by itself; nothing here should ever be.
        Bench.check(o.container().endsWith("already closed by the server 0"),
            "the server closed a container screen by itself: " + o.container());
        Bench.check(o.home(), "the player is not back where the trips started");
        RestockScene.checkClean(o);
        return Metrics.none();
    }
}
