package com.xploits.bench;

import com.xploits.printer.core.BuildIndex;
import com.xploits.restock.core.RestockReason;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * CHECK {@code restock-litematica} (restock spec §3 "Litematica source", §6; Litematica profile only): the real
 * Litematica adapter against a row whose schematic the test makes itself — stone, air, stone — with stone already
 * standing in the near cell. The player carries a stack, so nothing is due and restock only counts. Four phases:
 * <ol>
 *   <li>Easy Place on with its post-rewrite and the placement restriction: restock refuses to start
 *       (EASY_PLACE_RESTRICTION) — its container click would be cancelled by Litematica's own check.</li>
 *   <li>Rendering on: the placement is read as written — one stone in place, one missing, one cell that must be air.</li>
 *   <li>Main rendering off: every target is unknown, and unknown is never built.</li>
 *   <li>Rendering back on, the placement moved four blocks and back: each time restock counts again at once, the
 *       moved placement with nothing in place, and never stops for it.</li>
 * </ol>
 */
final class RestockLitematica implements Scenario {
    private static final BenchSchematic SCHEMATIC = BenchSchematic.ROW.with(BenchSchematic.MIDDLE, Blocks.AIR);
    private static final Vec3i NEAR_STONE = new Vec3i(-1, 0, 2);
    private static final int MOVE = 4;
    private static final int WRITE_TICKS = 40;
    private static final int COUNT_TICKS = 100;

    private RestockScene scene;
    private SchematicPlacement placement;

    @Override
    public String name() {
        return "restock-litematica";
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
        scene = new RestockScene(SCHEMATIC, List.of(RestockScene.stack(0, Items.STONE, 64)), List.of(), List.of());
        scene.arrange(bench);
        scene.setServerBlock(bench, NEAR_STONE, Blocks.STONE);
        BlockPos origin = scene.origin();
        BlockPos near = origin.add(NEAR_STONE);
        for (int i = 0; i < WRITE_TICKS && !bench.fromClient(client -> client.world.getBlockState(near).isOf(Blocks.STONE)); i++) {
            bench.ticks(1);
        }
        Bench.check(bench.fromClient(client -> client.world.getBlockState(near).isOf(Blocks.STONE)),
            "the client never saw the stone already in place");
        placement = bench.fromClient(client -> BenchLitematica.place(SCHEMATIC, origin));
        bench.atDespawn(() -> bench.onClient(client -> {
            BenchLitematica.remove(placement);
            BenchLitematica.rendering(true);
        }));
        awaitWritten(bench, near, Blocks.STONE, "the placement");
    }

    @Override
    public Metrics act(Bench bench) {
        BlockPos origin = scene.origin();
        BlockPos min = origin.add(SCHEMATIC.min());

        // 0. Easy Place with the placement restriction: a refusal at once.
        bench.onClient(client -> BenchLitematica.easyPlaceRestriction(true));
        bench.atDespawn(() -> bench.onClient(client -> BenchLitematica.easyPlaceRestriction(false)));
        scene.start(bench, true, true);
        scene.tick(bench);
        Optional<RestockReason> refused = bench.fromClient(client -> scene.restock().lastReason());
        Bench.check(!scene.on(bench) && refused.equals(Optional.of(RestockReason.EASY_PLACE_RESTRICTION)),
            "with Easy Place's placement restriction on restock ended with " + RestockScene.words(refused)
                + ", expected EASY_PLACE_RESTRICTION");
        bench.onClient(client -> BenchLitematica.easyPlaceRestriction(false));

        // 1. The placement as written.
        scene.enableAgain(bench);
        BuildIndex.Counts read = awaitCounts(bench, c -> c.matches() == 1 && c.missing() == 1 && c.airTargets() == 1);
        Bench.check(read.matches() == 1 && read.missing() == 1 && read.airTargets() == 1 && read.unknown() == 0,
            "the placement was read as " + words(read) + "; one in place, one missing, one air target expected");

        // 2. Unknown: main rendering off.
        bench.onClient(client -> BenchLitematica.rendering(false));
        BuildIndex.Counts unknown = awaitCounts(bench, c -> c.unknown() == 3);
        Bench.check(unknown.unknown() == 3 && unknown.matches() == 0 && unknown.missing() == 0,
            "with rendering off the placement was read as " + words(unknown) + "; three unknown expected");

        // 3. The placement moves, then comes back: counted again each time, never a stop.
        bench.onClient(client -> BenchLitematica.rendering(true));
        awaitCounts(bench, c -> c.matches() == 1 && c.missing() == 1);
        bench.onClient(client -> BenchLitematica.moveTo(placement, min.add(0, 0, MOVE)));
        awaitWritten(bench, origin.add(NEAR_STONE).add(0, 0, MOVE), Blocks.STONE, "the moved placement");
        BuildIndex.Counts moved = awaitCounts(bench, c -> c.matches() == 0 && c.missing() == 2);
        Bench.check(scene.on(bench), "restock stopped when the placement moved: " +
            RestockScene.words(bench.fromClient(client -> scene.restock().lastReason())));
        Bench.check(moved.matches() == 0 && moved.missing() == 2,
            "the moved placement was read as " + words(moved) + "; two missing expected");
        bench.onClient(client -> BenchLitematica.moveTo(placement, min));
        awaitWritten(bench, origin.add(NEAR_STONE), Blocks.STONE, "the placement moved back");
        BuildIndex.Counts back = awaitCounts(bench, c -> c.matches() == 1 && c.missing() == 1);
        Bench.check(back.matches() == 1 && back.missing() == 1, "the placement moved back was read as " + words(back));

        RestockScene.Outcome o = scene.finish(bench);
        Bench.check(o.on() && o.reason().isEmpty(), "restock ended with " + RestockScene.words(o.reason()));
        Bench.check(o.trips() == 0 && o.printer().isEmpty(), "restock went on a trip with a stack in hand");
        RestockScene.checkClean(o);
        return Metrics.none();
    }

    /** Ticks until restock's counts satisfy {@code wanted} or {@value #COUNT_TICKS} pass; the last counts read. */
    private BuildIndex.Counts awaitCounts(Bench bench, Predicate<BuildIndex.Counts> wanted) {
        Optional<BuildIndex.Counts> counts = Optional.empty();
        for (int i = 0; i < COUNT_TICKS; i++) {
            counts = bench.fromClient(client -> scene.restock().counts());
            if (counts.isPresent() && wanted.test(counts.get())) return counts.get();
            scene.tick(bench);
        }
        if (counts.isEmpty()) throw new BenchException("restock had no counts after " + COUNT_TICKS + " ticks");
        return counts.get();
    }

    private static String words(BuildIndex.Counts c) {
        return "in place " + c.matches() + ", missing " + c.missing() + ", air targets " + c.airTargets() + ", unknown "
            + c.unknown() + ", not scanned yet " + c.unscanned();
    }

    private static void awaitWritten(Bench bench, BlockPos pos, Block block, String what) {
        for (int i = 0; i < WRITE_TICKS; i++) {
            boolean written = bench.fromClient(client -> {
                BlockState state = BenchLitematica.written(pos);
                return state != null && state.isOf(block);
            });
            if (written) return;
            bench.ticks(1);
        }
        throw new BenchException("Litematica did not write " + what + " within " + WRITE_TICKS + " ticks");
    }
}
