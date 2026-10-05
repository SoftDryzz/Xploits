package com.xploits.bench;

import com.xploits.restock.core.UnpackPlan;
import net.minecraft.item.Items;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CHECK {@code restock-shulker-hotbar} (owner ruling R43): as {@code restock-shulker-carried}, but the box of stone is
 * in the main inventory (slot 20) with free hotbar slots, and a second box of the same kind, holding dirt, sits in a
 * lower main slot (9). Restock moves the box of stone into the hotbar with one QUICK_MOVE in the player's own inventory
 * — the server's judge sees exactly one such click, scoped (syncId 0, button 0, a main-inventory slot holding a shulker
 * box with items in it, a free hotbar slot, nothing on the cursor, no screen, no dig, the player still) — then unpacks
 * it as usual. The judge accepts any filled box, so the scene proves the rest: the box in slot 9 is still there with
 * its dirt (M13). Nothing else in the player's own inventory is ever clicked.
 */
final class RestockShulkerHotbar implements Scenario {
    private static final int RUN_TICKS = 400;

    private RestockScene scene;

    @Override
    public String name() {
        return "restock-shulker-hotbar";
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
            RestockScene.shulker(9, "", List.of(RestockScene.stack(0, Items.DIRT, 64))),
            RestockScene.stack(21, Items.DIRT, 64),
            RestockScene.shulker(20, "", List.of(RestockScene.stack(0, Items.STONE, 64)))), List.of(), List.of());
        scene.arrange(bench);
        scene.selectSlot(bench, 0);
    }

    @Override
    public Metrics act(Bench bench) {
        scene.start(bench, true, false);
        scene.run(bench, RUN_TICKS, () -> bench.fromClient(client -> scene.printer().history()).size() >= 2);
        Optional<UnpackPlan.Phase> unpacking = scene.unpackPhase(bench);
        int standing = scene.standingShulkers(bench);
        Map<String, Long> slot9 = scene.slotHolds(bench, 9);
        Map<String, Long> loose = scene.loose(bench);
        int borrowed = scene.borrowed(bench);
        RestockScene.Outcome o = scene.finish(bench);
        // The judge first: this CHECK exists for the one click it lets through (owner ruling R43).
        RestockScene.checkClean(o, 1);
        Bench.check(o.on() && o.reason().isEmpty(), "restock ended with " + RestockScene.words(o.reason()));
        Bench.check(o.trips() == 0, "trips: " + o.trips() + ", none expected (the box was carried)");
        Bench.check(unpacking.isEmpty(), "the unpacking did not finish: " + unpacking.map(Enum::name).orElse(""));
        Bench.check(o.printer().equals(List.of(false, true)),
            "the print mode was switched " + o.printer() + ", off for the unpacking and on after it expected");
        Bench.check(o.interacts() == 2,
            "block interactions sent: " + o.interacts() + ", two expected (the placement, the box)");
        Bench.check(o.judge().startsWith("2 interaction(s) and 1 dig start(s) judged"),
            "the server judged " + o.judge());
        Bench.check(o.clicks() == 2 && o.closes() == 1, "slot clicks " + o.clicks() + " and closes " + o.closes()
            + ", two clicks (the move into the hotbar, the take) and one close expected");
        Bench.check(o.container().contains("already closed by the server 0,"),
            "the server closed a container screen by itself: " + o.container());
        Bench.check(o.player().getOrDefault("minecraft:shulker_box", 0L) == 2
                && o.player().getOrDefault("minecraft:stone", 0L) == 64
                && o.player().getOrDefault("minecraft:dirt", 0L) == 128,
            "the player carries " + o.player() + ": the two boxes, the 64 stone and 128 dirt (64 loose, 64 in the other"
                + " box) expected");
        Bench.check(loose.getOrDefault("minecraft:stone", 0L) == 64, "the player carries "
            + loose.getOrDefault("minecraft:stone", 0L) + " stone loose, the box's 64 expected (taken out of it)");
        Bench.check(slot9.equals(Map.of("minecraft:shulker_box", 1L, "minecraft:dirt", 64L)),
            "slot 9 holds " + slot9 + ": the other box, untouched, with its 64 dirt expected (owner ruling R43: only"
                + " the box being unpacked moves)");
        Bench.check(standing == 0, standing + " shulker box(es) left standing");
        Bench.check(borrowed == 0, "borrowed boxes: " + borrowed + ", none expected (owner ruling R44: the player's own"
            + " boxes are never noted)");
        Bench.check(o.home(), "the player is not back where the unpacking began");
        return Metrics.none();
    }
}
