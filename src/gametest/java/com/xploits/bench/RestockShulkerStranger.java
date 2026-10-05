package com.xploits.bench;

import com.xploits.restock.core.RestockReason;
import com.xploits.restock.core.ShulkersLeft;
import net.minecraft.item.Items;

import java.util.List;
import java.util.Optional;

/**
 * CHECK {@code restock-shulker-stranger} (owner ruling R42, "a stranger near: finish the break and the pick-up, then
 * stop"): as {@code restock-shulker-attacked}, but mid-dig a player who is not a Meteor friend appears five blocks
 * away. Restock keeps digging, picks the box up and selects the slot it began with, and only then stops with
 * PLAYER_NEAR: no box standing, nothing on the ground, the box and its stone carried, the printer left off.
 */
final class RestockShulkerStranger implements Scenario {
    private static final int TO_DIG_TICKS = 400;
    private static final int FINISH_TICKS = 300;

    private RestockScene scene;

    @Override
    public String name() {
        return "restock-shulker-stranger";
    }

    @Override
    public Kind kind() {
        return Kind.CHECK;
    }

    @Override
    public int seconds() {
        return 30;
    }

    @Override
    public void arrange(Bench bench) {
        scene = new RestockScene(BenchSchematic.WALL,
            List.of(RestockScene.shulker(0, "", List.of(RestockScene.stack(0, Items.STONE, 64)))), List.of(),
            List.of());
        scene.arrange(bench);
        scene.selectSlot(bench, 0);
    }

    @Override
    public Metrics act(Bench bench) {
        scene.start(bench, true, false);
        scene.run(bench, TO_DIG_TICKS, () -> scene.unpackDigging(bench));
        Bench.check(scene.unpackDigging(bench), "restock never started digging the box");
        bench.spawn(new Still());
        scene.run(bench, FINISH_TICKS, () -> false);
        int standing = scene.standingShulkers(bench);
        ShulkersLeft left = scene.lastShulkersLeft(bench);
        Optional<RestockReason> drained = scene.lastDrained(bench);
        RestockScene.Outcome o = scene.finish(bench);
        Bench.check(!o.on() && o.reason().equals(Optional.of(RestockReason.PLAYER_NEAR)),
            "with a stranger near mid-dig restock ended with " + RestockScene.words(o.reason())
                + ", PLAYER_NEAR expected");
        Bench.check(drained.equals(Optional.of(RestockReason.PLAYER_NEAR)),
            "the break and the pick-up did not finish first: " + drained.map(Enum::name).orElse("no finishing"));
        Bench.check(standing == 0, standing + " shulker box(es) left standing, none expected");
        Bench.check(left.equals(ShulkersLeft.NONE), "the stop said something is left out: " + left);
        Bench.check(o.player().getOrDefault("minecraft:shulker_box", 0L) == 1
                && o.player().getOrDefault("minecraft:stone", 0L) == 64,
            "the player carries " + o.player() + ": the box and its 64 stone expected");
        Bench.check(o.printer().equals(List.of(false)), "the print mode was switched " + o.printer()
            + ", off and left off expected");
        Bench.check(o.interacts() == 2, "block interactions sent: " + o.interacts() + ", two expected");
        Bench.check(o.clicks() == 1 && o.closes() == 1, "slot clicks " + o.clicks() + " and closes " + o.closes()
            + ", one each expected (the take from the box and its close)");
        Bench.check(o.judge().startsWith("2 interaction(s) and 1 dig start(s) judged"),
            "the server judged " + o.judge());
        Bench.check(o.container().contains("already closed by the server 0,"),
            "the server closed a container screen by itself: " + o.container());
        RestockScene.checkClean(o);
        return Metrics.none();
    }
}
