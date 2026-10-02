package com.xploits.restock;

import com.xploits.XploitsAddon;
import com.xploits.restock.core.MarkBook;
import com.xploits.restock.core.RestockSetting;
import com.xploits.restock.core.RestockSettings;
import com.xploits.restock.core.RestockText;
import com.xploits.shared.XploitsModule;
import com.xploits.printer.core.Pos;
import com.xploits.stash.core.ContainerKey;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
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
 * unmarks a chest, trapped or copper chest, barrel or shulker box (a double chest by its lesser half, as stash-keeper keys it) with
 * the block the player stands on as its stand spot. The outline shows this dimension's marks for a few seconds after a
 * change and all the time while restock is on, a double chest as both its halves. Subscribed once at start-up. Client
 * thread.
 */
public final class Marks {
    private static final Marks INSTANCE = new Marks();
    private static final long FLASH_MS = 3_000;
    private static final Color SIDE = new Color(255, 170, 0, 40);
    private static final Color LINE = new Color(255, 170, 0, 255);
    private static boolean started;
    /** The restock module, found once: a lookup by name scans every module and the outline asks every frame. */
    private static Module restockModule;
    private static Setting<?> maxDistance;

    private long flashUntil;

    private Marks() {
    }

    public static synchronized void start() {
        if (started) return;
        started = true;
        MeteorClient.EVENT_BUS.subscribe(INSTANCE);
    }

    /** Every join reads the marks file again. */
    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        MarkStore.forget();
    }

    @EventHandler
    private void onKey(KeyEvent event) {
        if (event.action != KeyAction.Press) return;
        guarded(() -> {
            if (pressed(k -> k.matches(event.input))) mark();
        });
    }

    @EventHandler
    private void onMouse(MouseClickEvent event) {
        if (event.action != KeyAction.Press) return;
        guarded(() -> {
            if (pressed(k -> k.matches(event.input))) mark();
        });
    }

    /**
     * Deferred L83 (as ruling R33 does for the module): a fault behind the mark key — an exception, or a
     * {@code LinkageError} from a Meteor build that changed — never reaches the game, whose crash report would list every
     * loaded player's name and position. Only its class is logged: its message could carry a position.
     */
    private static void guarded(Runnable press) {
        try {
            press.run();
        } catch (RuntimeException | LinkageError e) {
            XploitsAddon.LOG.error("restock: the mark key failed ({})", e.getClass().getName());
        }
    }

    private static Module restock() {
        if (restockModule == null) {
            restockModule = Modules.get().get("restock");
            maxDistance = restockModule == null ? null : restockModule.settings.get(RestockSetting.MAX_DISTANCE.id());
        }
        return restockModule;
    }

    /** Only marks this close are outlined: restock's own {@code max-distance}, the farthest it would go for one. */
    private static double outlineDistance() {
        return maxDistance != null && maxDistance.get() instanceof Integer d ? d : RestockSettings.MAX_DISTANCE;
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
        String dimension = mc.world.getRegistryKey().getValue().toString();
        BlockPos feet = mc.player.getBlockPos();
        Pos stand = new Pos(feet.getX(), feet.getY(), feet.getZ());
        Pos own = new Pos(pos.getX(), pos.getY(), pos.getZ());
        Pos key = container(state.getBlock()) ? key(dimension, pos, state) : own;
        MarkStore.Result r;
        MarkBook marked = MarkStore.book();
        Pos already = marked == null ? null : markedAmong(marked.in(dimension), own, key);
        if (already != null) {
            // Unmarking needs no container (it may be gone) and no ground.
            r = MarkStore.toggle(new MarkBook.Mark(dimension, already, stand));
        } else {
            if (!container(state.getBlock())) {
                if (say != null) say.warning(RestockText.NOT_A_CONTAINER);
                return;
            }
            if (!mc.player.isOnGround()) {
                if (say != null) say.warning(RestockText.MARK_NOT_ON_GROUND);
                return;
            }
            r = MarkStore.toggle(new MarkBook.Mark(dimension, key, stand));
        }
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

    /** The first of {@code candidates} that one of {@code marks} is on, or null. */
    static Pos markedAmong(java.util.List<MarkBook.Mark> marks, Pos... candidates) {
        for (Pos c : candidates) {
            for (MarkBook.Mark m : marks) if (m.container().equals(c)) return c;
        }
        return null;
    }

    /**
     * Chest, trapped chest, any copper chest (all are {@link ChestBlock}s, with a chest's block entity and screen),
     * barrel or shulker box. The ender chest is no {@code ChestBlock} and stays out: it has no position of its own.
     * Stash-keeper keeps its own, shorter list.
     */
    static boolean container(Block block) {
        return block instanceof ChestBlock || block == Blocks.BARREL
            || (block.asItem() != null && Utils.isShulker(block.asItem()));
    }

    /** A double chest by its lesser half ({@link ContainerKey#doubleChest}); anything else by its own position. */
    static Pos key(String dimension, BlockPos pos, BlockState state) {
        BlockPos other = otherHalf(pos, state);
        if (other != null) {
            ContainerKey k = ContainerKey.doubleChest(dimension, pos.getX(), pos.getY(), pos.getZ(), other.getX(),
                other.getY(), other.getZ());
            return new Pos(k.x(), k.y(), k.z());
        }
        return new Pos(pos.getX(), pos.getY(), pos.getZ());
    }

    /** The other half of the double chest whose one half is {@code state} at {@code pos}; null for anything else. */
    static BlockPos otherHalf(BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof ChestBlock) || state.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE) return null;
        return pos.offset(ChestBlock.getFacing(state));
    }

    /** Set by a fault in the outline render; outlining stays off until the next join. */
    private boolean renderFailed;

    @EventHandler
    private void onJoin(GameJoinedEvent event) {
        renderFailed = false;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (renderFailed) return;
        try {
            outline(event);
        } catch (RuntimeException | LinkageError e) {
            renderFailed = true;
            XploitsAddon.LOG.error("restock: the mark outline failed ({}); outlining is off until the next join",
                e.getClass().getName());
        }
    }

    private void outline(Render3DEvent event) {
        MinecraftClient mc = MeteorClient.mc;
        if (mc.world == null || mc.player == null) return;
        Module module = restock();
        boolean on = module != null && module.isActive();
        if (!on && System.currentTimeMillis() > flashUntil) return;
        MarkBook book = MarkStore.book();
        if (book == null) return;
        String dimension = mc.world.getRegistryKey().getValue().toString();
        double reach = outlineDistance();
        for (MarkBook.Mark m : book.in(dimension)) {
            BlockPos p = new BlockPos(m.container().x(), m.container().y(), m.container().z());
            if (p.getSquaredDistance(mc.player.getEntityPos()) > reach * reach) continue;
            // Deferred L25: a double chest is outlined whole, whichever half the mark keeps.
            BlockPos other = otherHalf(p, mc.world.getBlockState(p));
            if (other == null) {
                event.renderer.box(p, SIDE, LINE, ShapeMode.Lines, 0);
            } else {
                event.renderer.box(Math.min(p.getX(), other.getX()), Math.min(p.getY(), other.getY()),
                    Math.min(p.getZ(), other.getZ()), Math.max(p.getX(), other.getX()) + 1,
                    Math.max(p.getY(), other.getY()) + 1, Math.max(p.getZ(), other.getZ()) + 1, SIDE, LINE,
                    ShapeMode.Lines, 0);
            }
        }
    }
}
