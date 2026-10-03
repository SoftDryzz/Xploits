package com.xploits.bench;

import com.xploits.restock.core.RestockReason;
import com.xploits.restock.core.ShulkersLeft;
import net.minecraft.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    /** "At once": the server's health update reaches the client in a tick or two (no simulated ping in a CHECK). */
    private static final int AT_ONCE_TICKS = 5;
    /** After the stop, for the server to handle the ABORT sent in its tick. */
    private static final int SETTLE_TICKS = 5;
    private static final Logger LOG = LoggerFactory.getLogger("xploits-bench");

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
        int stopTicks = scene.ticksUntilOff(bench, STOP_TICKS);
        LOG.info("[bench] {}: restock stopped {} tick(s) after the health drop (log only)", name(), stopTicks);
        // The ABORT went out in the stop's tick; a few more ticks for the server to handle it.
        for (int i = 0; i < SETTLE_TICKS; i++) scene.tick(bench);
        boolean mining = scene.serverMining(bench);
        int standing = scene.standingShulkers(bench);
        ShulkersLeft left = scene.lastShulkersLeft(bench);
        Optional<RestockReason> drained = scene.lastDrained(bench);
        RestockScene.Outcome o = scene.finish(bench);
        Bench.check(!o.on() && o.reason().equals(Optional.of(RestockReason.LOW_HEALTH)),
            "with low health mid-dig restock ended with " + RestockScene.words(o.reason()) + ", LOW_HEALTH expected");
        Bench.check(stopTicks <= AT_ONCE_TICKS, "restock stopped " + stopTicks + " tick(s) after the health drop, "
            + "within " + AT_ONCE_TICKS + " expected (at once: the server's health reaches the client in a tick or"
            + " two)");
        Bench.check(!mining, "the server still holds restock's dig after the stop (no ABORT reached it)");
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
        Bench.check(o.clicks() == 1 && o.closes() == 1, "slot clicks " + o.clicks() + " and closes " + o.closes()
            + ", one each expected (the take from the box and its close)");
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
