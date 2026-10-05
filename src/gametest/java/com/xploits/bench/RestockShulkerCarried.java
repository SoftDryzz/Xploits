package com.xploits.bench;

import com.xploits.bench.core.InventoryLedger;
import com.xploits.restock.core.UnpackPlan;
import net.minecraft.item.Items;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CHECK {@code restock-shulker-carried} (owner ruling R44, "carried shulkers first"): the wall needs stone, the player
 * carries none loose but a shulker box of 64 stone in the hotbar and a diamond pickaxe, and no container is marked.
 * Restock switches the (fake) print mode off, sets the box down beside the player, opens it and takes the stone, breaks
 * it with the pickaxe, picks it up, selects the slot it began with and switches the print mode on again — with no trip.
 * The take is proven by the loose count: no stone loose at T0, the box's 64 loose at the end and the box empty (the
 * deep count, contents included, stays the nothing-lost check). Two block interactions (the placement, the box) and
 * one dig, all passing the server's re-check; no click in the player's own inventory; the server never closes the
 * box's screen by itself; nothing lost, nothing on the ground, no box left standing.
 */
final class RestockShulkerCarried implements Scenario {
    private static final int RUN_TICKS = 400;

    private RestockScene scene;

    @Override
    public String name() {
        return "restock-shulker-carried";
    }

    @Override
    public Kind kind() {
        return Kind.CHECK;
    }

    @Override
    public int seconds() {
        return 20;
    }

    @Override
    public void arrange(Bench bench) {
        scene = new RestockScene(BenchSchematic.WALL, List.of(RestockScene.stack(1, Items.DIAMOND_PICKAXE, 1),
            RestockScene.shulker(2, "", List.of(RestockScene.stack(0, Items.STONE, 64)))), List.of(), List.of());
        scene.arrange(bench);
        scene.selectSlot(bench, 0);
    }

    @Override
    public Metrics act(Bench bench) {
        // Restock still off: the stone is only inside the box.
        Map<String, Long> looseAtT0 = scene.loose(bench);
        scene.start(bench, true, false);
        scene.run(bench, RUN_TICKS, () -> bench.fromClient(client -> scene.printer().history()).size() >= 2);
        Optional<UnpackPlan.Phase> unpacking = scene.unpackPhase(bench);
        int standing = scene.standingShulkers(bench);
        Map<String, Long> loose = scene.loose(bench);
        Map<String, Long> inBoxes = scene.inBoxes(bench);
        int borrowed = scene.borrowed(bench);
        RestockScene.Outcome o = scene.finish(bench);
        Bench.check(o.on() && o.reason().isEmpty(), "restock ended with " + RestockScene.words(o.reason()));
        Bench.check(o.trips() == 0, "trips: " + o.trips() + ", none expected (the box was carried)");
        Bench.check(unpacking.isEmpty(), "the unpacking did not finish: " + unpacking.map(Enum::name).orElse(""));
        Bench.check(o.printer().equals(List.of(false, true)),
            "the print mode was switched " + o.printer() + ", off for the unpacking and on after it expected");
        Bench.check(o.interacts() == 2,
            "block interactions sent: " + o.interacts() + ", two expected (the placement, the box)");
        Bench.check(o.judge().startsWith("2 interaction(s) and 1 dig start(s) judged"),
            "the server judged " + o.judge());
        Bench.check(o.clicks() == 1 && o.closes() == 1,
            "slot clicks " + o.clicks() + " and closes " + o.closes() + ", one each expected (the take from the box)");
        // Ruling R37's excuse is for a screen the server closed by itself; nothing in an unpack should ever be.
        Bench.check(o.container().contains("already closed by the server 0,"),
            "the server closed a container screen by itself (a box broken with its screen open?): " + o.container());
        Bench.check(o.player().getOrDefault("minecraft:shulker_box", 0L) == 1, "the player carries "
            + o.player().getOrDefault("minecraft:shulker_box", 0L) + " shulker box(es), one expected");
        Bench.check(o.player().getOrDefault("minecraft:stone", 0L) == 64, "the player has "
            + o.player().getOrDefault("minecraft:stone", 0L) + " stone, loose or in a box, the box's 64 expected");
        Bench.check(looseAtT0.getOrDefault("minecraft:stone", 0L) == 0, "the player carried "
            + looseAtT0.getOrDefault("minecraft:stone", 0L) + " stone loose at T0, none expected (only in the box)");
        Bench.check(loose.getOrDefault("minecraft:stone", 0L) == 64, "the player carries "
            + loose.getOrDefault("minecraft:stone", 0L) + " stone loose, the box's 64 expected (taken out of it)");
        Bench.check(inBoxes.isEmpty(), "the carried box still holds " + InventoryLedger.words(inBoxes)
            + ", nothing expected");
        Bench.check(standing == 0, standing + " shulker box(es) left standing");
        Bench.check(borrowed == 0, "borrowed boxes: " + borrowed + ", none expected (owner ruling R44: the player's own"
            + " boxes are never noted)");
        Bench.check(o.home(), "the player is not back where the unpacking began");
        RestockScene.checkClean(o);
        return Metrics.none();
    }
}
