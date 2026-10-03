package com.xploits.bench;

import com.xploits.bench.core.InventoryLedger;
import net.minecraft.item.Items;
import net.minecraft.util.math.Vec3i;

import java.util.List;
import java.util.Map;

/**
 * CHECK {@code restock-shulker-trigger-b} (rulings R70, R71, trigger (b); owner ruling R44: the player's own box is
 * never handed to a container): as {@code restock-shulker-borrowed}, but the player also carries an own empty plain
 * box, and while restock's chest screen waits for the server's answer to the carry, the server puts the carried box
 * back into the chest — as a server refusing the click, or a player taking it back, would. The carry came back, so in
 * that visit nothing of that kind goes back: the own empty box stays with the player (with the carry's entry still in
 * the ledger until the visit's close, R50's count alone would take it for the borrowed one). The visit makes exactly
 * one slot click (the carry) and one close; at its close the ledger forgets the carry; the trip goes home with nothing;
 * the chest ends holding its box of stone, and nothing is borrowed.
 */
final class RestockShulkerTriggerB implements Scenario {
    private static final Vec3i CHEST = RestockShulkerBorrowed.CHEST;
    private static final Vec3i STAND = RestockShulkerBorrowed.STAND;
    /** The player's own empty plain box. */
    private static final int OWN_BOX = 2;
    private static final String STONE = "minecraft:stone";
    private static final String BOX = "minecraft:shulker_box";
    private static final int TO_CARRY_TICKS = 400;
    private static final int BACK_TICKS = 400;

    private RestockScene scene;

    @Override
    public String name() {
        return "restock-shulker-trigger-b";
    }

    @Override
    public Kind kind() {
        return Kind.CHECK;
    }

    @Override
    public int seconds() {
        return 45;
    }

    @Override
    public void arrange(Bench bench) {
        scene = new RestockScene(BenchSchematic.WALL, List.of(RestockScene.stack(1, Items.DIAMOND_PICKAXE, 1),
            RestockScene.shulker(OWN_BOX, "", List.of())),
            List.of(new RestockScene.Chest(CHEST,
                List.of(RestockScene.shulker(0, "", List.of(RestockScene.stack(0, Items.STONE, 64)))))),
            List.of(new RestockScene.MarkAt(CHEST, STAND)));
        scene.arrange(bench);
        scene.selectSlot(bench, 0);
    }

    @Override
    public Metrics act(Bench bench) {
        Map<String, Long> ownAtT0 = scene.slotHolds(bench, OWN_BOX);
        scene.start(bench, true, false);
        // Every tick: once the carry has reached the server (a box holding items in the player's slots), it goes back
        // into the chest at once, inside the carry's answer wait.
        boolean[] putBack = new boolean[1];
        scene.run(bench, TO_CARRY_TICKS, () -> putBack[0] = scene.putCarriedBoxBack(bench, CHEST));
        Bench.check(putBack[0], "restock never carried the box out of the chest");
        scene.run(bench, BACK_TICKS, () -> scene.trips(bench) >= 1 && scene.phase(bench).isEmpty());
        int borrowed = scene.borrowed(bench);
        Map<String, Long> own = scene.slotHolds(bench, OWN_BOX);
        Map<String, Long> chest = scene.chest(bench, CHEST);
        RestockScene.Outcome o = scene.finish(bench);
        Bench.check(ownAtT0.equals(Map.of(BOX, 1L)), "slot " + OWN_BOX + " held " + ownAtT0
            + " at T0, the player's own empty box expected");
        Bench.check(o.clicks() == 1 && o.closes() == 1, "slot clicks " + o.clicks() + " and closes " + o.closes()
            + ", one each expected (the carry, and the close once its answer came)");
        Bench.check(own.equals(Map.of(BOX, 1L)), "slot " + OWN_BOX + " holds " + own
            + ": the player's own empty box expected (owner ruling R44: it is never handed to a container)");
        Bench.check(chest.equals(Map.of(BOX, 1L, STONE, 64L)), "the chest holds " + InventoryLedger.words(chest)
            + ", its box with the 64 stone alone expected");
        Bench.check(borrowed == 0, "borrowed boxes still carried: " + borrowed + ", none expected");
        Bench.check(o.on() && o.reason().isEmpty(), "restock ended with " + RestockScene.words(o.reason()));
        Bench.check(o.trips() == 1, "trips back at the build: " + o.trips() + ", one expected");
        Bench.check(o.printer().equals(List.of(false, true)),
            "the print mode was switched " + o.printer() + ", off then on expected");
        Bench.check(o.player().getOrDefault(BOX, 0L) == 1 && o.player().getOrDefault(STONE, 0L) == 0,
            "the player ends with " + o.player().getOrDefault(BOX, 0L) + " shulker box(es) and "
                + o.player().getOrDefault(STONE, 0L) + " stone, the own box alone expected");
        Bench.check(o.interacts() == 1, "block interactions sent: " + o.interacts() + ", one expected (the chest)");
        Bench.check(o.container().contains("already closed by the server 0,"),
            "the server closed a container screen by itself: " + o.container());
        Bench.check(o.home(), "the player is not back where the trip started");
        RestockScene.checkClean(o);
        return Metrics.none();
    }
}
