package com.xploits.bench;

import com.xploits.restock.core.RestockReason;
import com.xploits.restock.core.ShulkersLeft;
import net.minecraft.item.Items;

import java.util.List;
import java.util.Optional;

/**
 * CHECK {@code restock-shulker-attacked} (owner ruling R42, "attacked or low health: stop at once"): the player carries
 * a box of stone in the selected slot and no tool, so the hand digs the box for about 3 s. Mid-dig the server lowers
 * the player's health below {@code min-health}: restock stops at once with LOW_HEALTH — the dig aborted in that same
 * tick, the box left standing where it is — and the stop says how many boxes stand and how far (never where). Nothing
 * is lost: the box and its contents are where restock says; nothing on the ground; no rule broken.
 */
final class RestockShulkerAttacked implements Scenario {
    private static final int TO_DIG_TICKS = 400;
    private static final int STOP_TICKS = 40;

    private RestockScene scene;

    @Override
    public String name() {
        return "restock-shulker-attacked";
    }

    @Override
    public Kind kind() {
        return Kind.CHECK;
    }

    @Override
    public int seconds() {
        return 25;
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
        String name = bench.player();
        bench.atDespawn(() -> bench.onServer(srv -> Arena.player(srv, name).setHealth(20f)));
        scene.start(bench, true, false);
        scene.run(bench, TO_DIG_TICKS, () -> scene.unpackDigging(bench));
        Bench.check(scene.unpackDigging(bench), "restock never started digging the box");
        bench.onServer(srv -> Arena.player(srv, name).setHealth(6f));
        scene.run(bench, STOP_TICKS, () -> false);
        int standing = scene.standingShulkers(bench);
        ShulkersLeft left = scene.lastShulkersLeft(bench);
        Optional<RestockReason> drained = scene.lastDrained(bench);
        RestockScene.Outcome o = scene.finish(bench);
        Bench.check(!o.on() && o.reason().equals(Optional.of(RestockReason.LOW_HEALTH)),
            "with low health mid-dig restock ended with " + RestockScene.words(o.reason()) + ", LOW_HEALTH expected");
        Bench.check(drained.isEmpty(), "it finished the dig first: " + drained.map(Enum::name).orElse(""));
        Bench.check(standing == 1, standing + " shulker box(es) standing, the one restock set down expected");
        Bench.check(left.standing() == 1 && left.nearestStanding() >= 0 && left.nearestStanding() <= 3,
            "the stop said " + left.standing() + " box(es) standing, the nearest " + left.nearestStanding()
                + " blocks away; one within three expected");
        Bench.check(left.onGround() < 0 && !left.lost() && !left.unchecked() && left.borrowed() == 0
                && !left.breaking(), "the stop said more than a standing box: " + left);
        Bench.check(o.printer().equals(List.of(false)), "the print mode was switched " + o.printer()
            + ", off and left off expected");
        Bench.check(o.interacts() == 2, "block interactions sent: " + o.interacts() + ", two expected");
        Bench.check(o.judge().startsWith("2 interaction(s) and 1 dig start(s) judged"),
            "the server judged " + o.judge());
        Bench.check(o.container().contains("already closed by the server 0,"),
            "the server closed a container screen by itself: " + o.container());
        Bench.check(o.player().getOrDefault("minecraft:stone", 0L) == 64,
            "the player carries " + o.player().getOrDefault("minecraft:stone", 0L) + " stone, the box's 64 expected");
        RestockScene.checkClean(o);
        return Metrics.none();
    }
}
