package com.xploits.restock;

import com.xploits.restock.core.MarkBook;
import com.xploits.restock.core.RestockSetting;
import com.xploits.restock.core.RestockText;
import com.xploits.shared.XploitsModule;
import com.xploits.printer.core.Pos;
import com.xploits.stash.core.ContainerKey;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.function.Predicate;

/**
 * The mark key and the outline (restock spec §4), always on, whether restock is on or off: Meteor's own keybind action
 * would fire only while restock is active. A press with no screen open, on the block the crosshair is on, marks or
 * unmarks a chest, trapped chest, barrel or shulker box (a double chest by its lesser half, as stash-keeper keys it) with
 * the block the player stands on as its stand spot. The outline shows this dimension's marks for a few seconds after a
 * change and all the time while restock is on. Subscribed once at start-up. Client thread.
 */
public final class Marks {
    private static final Marks INSTANCE = new Marks();
    private static final long FLASH_MS = 3_000;
    /** Only marks this close are outlined. */
    private static final double OUTLINE_DISTANCE = 64;
    private static final Color SIDE = new Color(255, 170, 0, 40);
    private static final Color LINE = new Color(255, 170, 0, 255);
    private static boolean started;

    private long flashUntil;

    private Marks() {
    }

    public static synchronized void start() {
        if (started) return;
        started = true;
        MeteorClient.EVENT_BUS.subscribe(INSTANCE);
    }

    @EventHandler
    private void onKey(KeyEvent event) {
        if (event.action == KeyAction.Press && pressed(k -> k.matches(event.input))) mark();
    }

    @EventHandler
    private void onMouse(MouseClickEvent event) {
        if (event.action == KeyAction.Press && pressed(k -> k.matches(event.input))) mark();
    }

    private static Module restock() {
        return Modules.get().get("restock");
    }

    private static boolean pressed(Predicate<Keybind> matches) {
        MinecraftClient mc = MeteorClient.mc;
        if (mc.currentScreen != null || mc.player == null || mc.world == null) return false;
        Module module = restock();
        if (module == null) return false;
        Setting<?> setting = module.settings.get(RestockSetting.MARK_KEY.id());
        return setting != null && setting.get() instanceof Keybind key && key.isSet() && matches.test(key);
    }

    private void mark() {
        MinecraftClient mc = MeteorClient.mc;
        XploitsModule say = restock() instanceof XploitsModule m ? m : null;
        if (!(mc.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            if (say != null) say.warning(RestockText.NOT_A_CONTAINER);
            return;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.world.getBlockState(pos);
        if (!container(state.getBlock())) {
            if (say != null) say.warning(RestockText.NOT_A_CONTAINER);
            return;
        }
        String dimension = mc.world.getRegistryKey().getValue().toString();
        Pos key = key(dimension, pos, state);
        BlockPos feet = mc.player.getBlockPos();
        MarkStore.Result r = MarkStore.toggle(new MarkBook.Mark(dimension, key, new Pos(feet.getX(), feet.getY(), feet.getZ())));
        flashUntil = System.currentTimeMillis() + FLASH_MS;
        if (say == null) return;
        MarkBook book = MarkStore.book();
        int count = book == null ? 0 : book.size();
        switch (r) {
            case MARKED -> say.info(RestockText.MARKED, "count", count);
            case UNMARKED -> say.info(RestockText.UNMARKED, "count", count);
            case UNREADABLE -> say.warning(RestockText.MARKS_UNREADABLE);
            case SAVE_FAILED -> say.warning(RestockText.MARK_SAVE_FAILED);
        }
    }

    /** Chest, trapped chest, barrel or shulker box (stash-keeper's list without the ender chest, which has no position). */
    static boolean container(Block block) {
        return block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.BARREL
            || (block.asItem() != null && Utils.isShulker(block.asItem()));
    }

    /** A double chest by its lesser half ({@link ContainerKey#doubleChest}); anything else by its own position. */
    static Pos key(String dimension, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof ChestBlock && state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
            BlockPos other = pos.offset(ChestBlock.getFacing(state));
            ContainerKey k = ContainerKey.doubleChest(dimension, pos.getX(), pos.getY(), pos.getZ(), other.getX(),
                other.getY(), other.getZ());
            return new Pos(k.x(), k.y(), k.z());
        }
        return new Pos(pos.getX(), pos.getY(), pos.getZ());
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        MinecraftClient mc = MeteorClient.mc;
        if (mc.world == null || mc.player == null) return;
        Module module = restock();
        boolean on = module != null && module.isActive();
        if (!on && System.currentTimeMillis() > flashUntil) return;
        MarkBook book = MarkStore.book();
        if (book == null) return;
        String dimension = mc.world.getRegistryKey().getValue().toString();
        for (MarkBook.Mark m : book.in(dimension)) {
            BlockPos p = new BlockPos(m.container().x(), m.container().y(), m.container().z());
            if (p.getSquaredDistance(mc.player.getEntityPos()) > OUTLINE_DISTANCE * OUTLINE_DISTANCE) continue;
            event.renderer.box(p, SIDE, LINE, ShapeMode.Lines, 0);
        }
    }
}
