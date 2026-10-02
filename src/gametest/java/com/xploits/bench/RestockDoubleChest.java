package com.xploits.bench;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3i;

import java.util.List;

/**
 * CHECK {@code restock-double-chest} (deferred L25, A1 review m3): a double chest marked from beyond its other half.
 * The mark keeps the lesser half, as the mark key does, and that half holds the stone. The spot it was marked from is on
 * the chest's long axis, four blocks beyond the other half: from where the walk stops there, no face of the stored half
 * can be aimed at within reach, and the other half's top is hit a little under the 4.5 reach (about 4.3, the most this
 * floor and walk allow with a margin both ways). Restock walks there, finds no aim at the stored half, falls back to the
 * other half and clicks it once — the server sees the click on that half — takes the stone the stored half holds through
 * the double chest's one screen, closes it and comes back: nothing found unusable, one trip, the printer off and on
 * once, nothing lost.
 */
final class RestockDoubleChest implements Scenario {
    /** The lesser half, which the mark keeps: its partner is east (a left half facing north). */
    static final Vec3i STORED = new Vec3i(-4, 0, 6);
    /** The greater half, its partner west. */
    static final Vec3i OTHER = new Vec3i(-3, 0, 6);
    /** On the chest's long axis, four blocks beyond the other half. */
    static final Vec3i STAND = new Vec3i(1, 0, 6);
    /** 25 s at 20 tps, inside the 30 s budget with the arrangement and the final check. */
    private static final int RUN_TICKS = 500;

    private RestockScene scene;

    @Override
    public String name() {
        return "restock-double-chest";
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
        scene = new RestockScene(BenchSchematic.WALL, List.of(),
            List.of(new RestockScene.Chest(STORED, List.of(RestockScene.stack(0, Items.STONE, 64),
                    RestockScene.stack(1, Items.STONE, 64)), half(ChestType.LEFT)),
                new RestockScene.Chest(OTHER, List.of(), half(ChestType.RIGHT))),
            List.of(new RestockScene.MarkAt(STORED, STAND)));
        scene.arrange(bench);
    }

    @Override
    public Metrics act(Bench bench) {
        scene.start(bench, true, false);
        scene.run(bench, RUN_TICKS, () -> scene.trips(bench) >= 1);
        int unusable = bench.fromClient(client -> scene.restock().unusable());
        RestockScene.Outcome o = scene.finish(bench);
        List<BlockPos> clicked = PlaceJudge.clicked();
        BlockPos other = scene.origin().add(OTHER);
        BlockPos stored = scene.origin().add(STORED);
        long onOther = clicked.stream().filter(other::equals).count();
        long onStored = clicked.stream().filter(stored::equals).count();
        Bench.check(unusable == 0, "containers found unusable: " + unusable
            + ", none expected (the other half opens the double chest)");
        Bench.check(onOther == 1 && clicked.size() == 1, "the server saw " + clicked.size() + " container click(s), "
            + onOther + " on the other half and " + onStored + " on the stored half; one on the other half expected");
        Bench.check(o.trips() == 1, "trips back at the build: " + o.trips() + ", one expected");
        Bench.check(o.on() && o.reason().isEmpty(), "restock ended with " + RestockScene.words(o.reason()));
        Bench.check(o.printer().equals(List.of(false, true)),
            "the print mode was switched " + o.printer() + ", off then on expected");
        Bench.check(o.player().getOrDefault("minecraft:stone", 0L) >= 10,
            "the player carries " + o.player().getOrDefault("minecraft:stone", 0L) + " stone, the wall needs ten");
        Bench.check(o.home(), "the player is not back where the trip started");
        Bench.check(o.interacts() == 1, "container clicks sent: " + o.interacts() + ", one expected");
        Bench.check(o.closes() == 1, "screens closed: " + o.closes() + ", one expected");
        RestockScene.checkClean(o);
        return Metrics.none();
    }

    /** One half of a double chest facing north: the left half's partner is east, the right half's west. */
    private static BlockState half(ChestType type) {
        return Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.NORTH).with(ChestBlock.CHEST_TYPE, type);
    }
}
