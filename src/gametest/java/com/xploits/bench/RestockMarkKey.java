package com.xploits.bench;

import com.xploits.printer.core.Pos;
import com.xploits.restock.MarkStore;
import com.xploits.restock.Restock;
import com.xploits.restock.core.MarkBook;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * CHECK {@code restock-mark-key} (restock spec §4): with restock off, the player looks at a chest and presses the mark
 * key: one mark, of that chest, from the block the player stands on. Pressed again: unmarked. Looking at the floor:
 * not a container, nothing marked. The key goes through the game's keyboard, as a person pressing it.
 */
final class RestockMarkKey implements Scenario {
    private static final Vec3i CHEST = new Vec3i(0, 0, 2);
    private static final int KEY = GLFW.GLFW_KEY_K;
    private static final int LOOK_TICKS = 20;

    private RestockScene scene;

    @Override
    public String name() {
        return "restock-mark-key";
    }

    @Override
    public Kind kind() {
        return Kind.CHECK;
    }

    @Override
    public int seconds() {
        return 10;
    }

    @Override
    public void arrange(Bench bench) {
        scene = new RestockScene(BenchSchematic.ROW, List.of(), List.of(new RestockScene.Chest(CHEST, List.of())), List.of());
        scene.arrange(bench);
    }

    @Override
    public Metrics act(Bench bench) {
        Restock restock = bench.meteor(Restock.class);
        Bench.check(!bench.fromClient(client -> restock.isActive()), "restock was on; the mark key must work with it off");
        bench.setting(restock, "General", "mark-key", Keybind.fromKey(KEY));
        bench.atDespawn(() -> {
            bench.setting(restock, "General", "mark-key", Keybind.none());
            bench.onClient(client -> MarkStore.clear());
        });
        bench.onClient(client -> MarkStore.clear());
        BlockPos origin = scene.origin();
        BlockPos chest = origin.add(CHEST);

        // Facing the chest, 30 degrees down: the crosshair is on its north face.
        bench.command("execute at " + Bench.PLAYER + " run tp " + Bench.PLAYER + " ~ ~ ~ 0 30");
        awaitLook(bench, chest, "the chest");
        bench.pressKey(KEY);
        bench.ticks(1);
        List<MarkBook.Mark> marks = marks(bench);
        Bench.check(marks.size() == 1, "marks after one press: " + marks.size() + ", one expected");
        Bench.check(marks.get(0).container().equals(pos(chest)) && marks.get(0).stand().equals(pos(origin)),
            "the mark is not the chest looked at, from the block the player stands on");

        bench.pressKey(KEY);
        bench.ticks(1);
        Bench.check(marks(bench).isEmpty(), "a second press did not unmark the chest");

        // Straight down: the floor, which is not a container.
        bench.command("execute at " + Bench.PLAYER + " run tp " + Bench.PLAYER + " ~ ~ ~ 0 90");
        awaitLook(bench, origin.down(), "the floor");
        bench.pressKey(KEY);
        bench.ticks(1);
        Bench.check(marks(bench).isEmpty(), "the floor was marked as a container");
        return Metrics.none();
    }

    private static List<MarkBook.Mark> marks(Bench bench) {
        return bench.fromClient(client -> {
            MarkBook book = MarkStore.book();
            if (book == null) throw new BenchException("the bench world's marks could not be read");
            return book.all();
        });
    }

    private static void awaitLook(Bench bench, BlockPos at, String what) {
        for (int i = 0; i < LOOK_TICKS; i++) {
            if (bench.fromClient(client -> client.crosshairTarget instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(at))) {
                return;
            }
            bench.ticks(1);
        }
        throw new BenchException("the crosshair did not reach " + what + " within " + LOOK_TICKS + " ticks");
    }

    private static Pos pos(BlockPos b) {
        return new Pos(b.getX(), b.getY(), b.getZ());
    }
}
