package com.xploits.kitrequester.inventory;

import com.xploits.kitrequester.core.CandidateTracker;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;

/** Empties shulkers into an ender chest in reach (spec §6). Moves one per tick and never walks. */
public final class EnderDepositor {
    public static final double REACH = 4.5;

    private static final int SCAN_RADIUS = 5;
    private static final long OPEN_TIMEOUT_MS = 5_000;
    private static final long MOVE_TIMEOUT_MS = 10_000;
    /**
     * Window in which a candidate -block or entity, our own or unrelated- is considered current
     * (spec §6.1, third fix). Equal to {@link #OPEN_TIMEOUT_MS} on purpose: they used to be
     * independent (1 s versus 5 s), and a 6b6t lag spike could expire the candidate while
     * {@code start()} was still within its own waiting window, letting through exactly the race this
     * field exists to block. With the two windows equal that can no longer happen: if {@code start()}
     * is still waiting for its screen, the candidate has not expired either.
     *
     * <p>Only {@link #start} uses it: it expires the block candidate right before checking
     * {@link CandidateTracker#blocksStart}. {@code tick()} does not expire anything -the candidate
     * there only matters for the one-off check in {@link CandidateTracker#matches} during
     * {@code OPENING}, which does not depend on this window-.
     */
    private static final long CANDIDATE_TIMEOUT_MS = OPEN_TIMEOUT_MS;

    private enum Phase { IDLE, OPENING, MOVING }

    private Phase phase = Phase.IDLE;
    private long openedAt;
    private long movingSince;

    /** The ender chest that {@link #start} asked to open. Fixes which container the operation is tied to. */
    private BlockPos targetPos;
    /**
     * Candidate and expiry (spec §6.1): which interaction -our own or unrelated, block or entity- was
     * seen most recently. The logic is pure and lives in {@code kitrequester/core}; here it only
     * translates {@link BlockPos} to {@code long} and, with Minecraft, decides which block can really
     * back the screen being expected ({@link #opensContainerScreen}).
     */
    private final CandidateTracker tracker = new CandidateTracker(CANDIDATE_TIMEOUT_MS);
    /** syncId of the handler already accepted as this operation's ender chest. Null until OPENING→MOVING. */
    private Integer syncId;

    public static Optional<BlockPos> findInReach(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return Optional.empty();
        BlockPos center = mc.player.getBlockPos();
        Vec3d eyes = mc.player.getEyePos();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(center.add(-SCAN_RADIUS, -SCAN_RADIUS, -SCAN_RADIUS),
                                             center.add(SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS))) {
            if (!mc.world.getBlockState(pos).isOf(Blocks.ENDER_CHEST)) continue;
            // A solid block above it stops it from opening: verified in the bytecode of
            // EnderChestBlock.onUse -if world.getBlockState(pos.up()).isSolidBlock(...) is true, it
            // returns ActionResult.SUCCESS without opening any screen-. Requesting it anyway produces
            // no visible error, just a whole OPEN_TIMEOUT_MS wasted for no reason (spec §6, fixed).
            BlockPos above = pos.up();
            if (mc.world.getBlockState(above).isSolidBlock(mc.world, above)) continue;
            double distance = eyes.distanceTo(Vec3d.ofCenter(pos));
            if (distance <= REACH && distance < bestDistance) {
                best = pos.toImmutable();
                bestDistance = distance;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Opens the nearest ender chest in reach. Returns false without sending anything if there is
     * none, if the player already has a screen open, or if there is a recent, unresolved unrelated
     * interaction -a click (block or entity) the player just sent whose screen has not arrived yet
     * ({@link #CANDIDATE_TIMEOUT_MS})-: sending the interact in any of those cases is exactly the
     * first step of the failure that empties shulkers into any container the player had open or was
     * about to open (spec §6.1).
     */
    public boolean start(MinecraftClient mc, long now) {
        if (mc.player == null) return false;
        if (!(mc.player.currentScreenHandler instanceof PlayerScreenHandler)) return false;
        tracker.expire(now);
        // While the phase is IDLE, a current candidate can only come from onInteractBlock or
        // onInteractEntity -this very call never leaves it set while IDLE-, so if it is still current
        // it is a player interaction whose screen has not arrived from the server yet.
        // Starting now is exactly the failure's race: the unrelated screen arrives afterwards, with
        // the candidate already rewritten to the ender chest, and OPENING accepts it as its own.
        if (tracker.blocksStart(now)) return false;
        Optional<BlockPos> target = findInReach(mc);
        if (target.isEmpty()) return false;
        BlockPos pos = target.get();
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
            new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false));
        targetPos = pos;
        tracker.noteBlockInteraction(pos.asLong(), true, now);
        syncId = null;
        phase = Phase.OPENING;
        openedAt = now;
        return true;
    }

    /**
     * Fed by {@code InteractBlockEvent}, which fires both for this very interaction and for any
     * player click on another container while the server's response is pending (spec: window of
     * {@link #OPEN_TIMEOUT_MS}). It is one of the two signals for knowing, when a screen appears,
     * whether the block behind it is the ender chest that was requested or something the player
     * opened on their own -the other is {@link #onInteractEntity}, for minecarts and boats-.
     *
     * <p>Ignored if the block is not of a type that can back a container screen -placing a block,
     * opening a door, or the {@code BlockUtils.place} calls of {@code surround}/{@code auto-trap}
     * must not be able to abort a deposit in progress or block the next one (spec §6.1, point 2)-.
     */
    public void onInteractBlock(MinecraftClient mc, BlockPos pos, long now) {
        if (mc.world == null) return;
        tracker.noteBlockInteraction(pos.asLong(), opensContainerScreen(mc.world.getBlockState(pos).getBlock()), now);
    }

    /**
     * Fed by {@code InteractEntityEvent} (a mixin on {@code interactEntity}, fires for any player
     * click on an entity). Minecart chests ({@code ChestMinecartEntity}) and boat chests
     * ({@code AbstractChestBoatEntity}) open a 9x3 {@code GenericContainerScreenHandler}, same as any
     * block chest -verified in the bytecode-, but {@code InteractBlockEvent} never sees them because
     * they are not blocks: without this hook, a click on either one went unnoticed and {@link #start}
     * could step on it just like, before the first fix, it stepped on a block chest (spec §6.1, third
     * fix).
     *
     * <p>There is no {@code BlockPos} to note as a candidate -an entity does not have one-, so this
     * only feeds {@link #start}: "something unrelated may be in flight". The positive title
     * confirmation in {@code OPENING} ({@link #isEnderChestScreen}) is what really protects that phase.
     */
    public void onInteractEntity(long now) {
        tracker.noteEntityInteraction(now);
    }

    /**
     * Blocks that open a container screen, same as {@code StashKeeper.typeOf}: chest, trapped chest,
     * barrel and ender chest back a {@code GenericContainerScreenHandler}; the shulker box backs its
     * own {@code ShulkerBoxScreenHandler} -not the generic one, verified in the bytecode of
     * {@code ShulkerBoxBlockEntity.createScreenHandler}-, but it is a container the player can open
     * by hand while waiting on the ender chest all the same, so it counts as a candidate too.
     */
    private static boolean opensContainerScreen(Block block) {
        return block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.BARREL
            || block == Blocks.ENDER_CHEST
            || (block.asItem() != null && Utils.isShulker(block.asItem()));
    }

    /**
     * Positive confirmation that the open screen really is an ender chest, not a deduction by
     * candidate (spec §6.1, third fix). Minecart chests and boat chests are entities and open the
     * same 9x3 {@code GenericContainerScreenHandler} as an ender chest, so the candidate alone is no
     * longer enough proof.
     *
     * <p>Verified in the bytecode of {@code EnderChestBlock.onUse}: it opens with
     * {@code new SimpleNamedScreenHandlerFactory(..., CONTAINER_NAME)}, where
     * {@code CONTAINER_NAME = Text.translatable("container.enderchest")}, and that same {@code Text}
     * is what {@code ClientPlayNetworkHandler.onOpenScreen} passes to {@code HandledScreens.open(...)}
     * -in the same synchronous call that sets {@code mc.player.currentScreenHandler}, with no tick in
     * between- and ends up in {@link HandledScreen#getTitle()}.
     *
     * <p>The already-translated text is compared ({@code getString()}), not the translation keys:
     * the screen title uses {@code container.enderchest} and {@code Blocks.ENDER_CHEST.getName()}
     * uses {@code block.minecraft.ender_chest} -different keys-, but they produce the same text in
     * every language checked against Mojang's files (en_us, es_es, fr_fr, de_de, ru_ru, zh_cn, ja_jp,
     * pt_br, it_it). A vanilla server cannot change that title: it is fixed by {@code EnderChestBlock},
     * without going through any data the server controls.
     */
    private static boolean isEnderChestScreen(MinecraftClient mc) {
        return mc.currentScreen instanceof HandledScreen<?> screen
            && screen.getTitle().getString().equals(Blocks.ENDER_CHEST.getName().getString());
    }

    /** Call on every tick while OrderMachine is in DEPOSIT. */
    public Optional<Boolean> tick(MinecraftClient mc, long now) {
        if (phase != Phase.IDLE && mc.player == null) {
            reset();
            return Optional.of(false);
        }
        switch (phase) {
            case IDLE -> {
                return Optional.of(false);
            }
            case OPENING -> {
                if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler) {
                    // It is no longer enough for the candidate to match the requested BlockPos -that
                    // is a deduction, and minecart/boat chests do not generate InteractBlockEvent-:
                    // the screen title is now also required to really be an ender chest's (spec §6.1,
                    // third fix). If either check fails, nothing is touched, when in doubt.
                    if (tracker.matches(targetPos.asLong()) && isEnderChestScreen(mc)) {
                        syncId = handler.syncId;
                        phase = Phase.MOVING;
                        movingSince = now;
                    } else {
                        return finish(mc, false);
                    }
                } else if (now - openedAt > OPEN_TIMEOUT_MS) return finish(mc, false);
                return Optional.empty();
            }
            case MOVING -> {
                if (now - movingSince > MOVE_TIMEOUT_MS) return finish(mc, false);
                if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler)) return finish(mc, false);
                // The container could have closed and a different one opened between two ticks
                // without ever passing through PlayerScreenHandler (e.g. the server chains two
                // windows). syncId is the only way to notice it is no longer the window accepted in
                // OPENING.
                if (syncId == null || handler.syncId != syncId) return finish(mc, false);
                int containerSlots = handler.getRows() * 9; // 27 or 54: on 6b6t the ender chest can be double
                if (!hasEmptySlot(handler, containerSlots)) return finish(mc, true);
                for (int i = containerSlots; i < handler.slots.size(); i++) {
                    Slot slot = handler.slots.get(i);
                    if (Utils.isShulker(slot.getStack().getItem())) {
                        mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, mc.player);
                        return Optional.empty();
                    }
                }
                return finish(mc, true);
            }
        }
        return Optional.empty();
    }

    /**
     * Closes the ender chest screen if the depositor opened it, and goes back to IDLE. Safe to call
     * at any time. Only closes a screen that has been confirmed as its own ({@code syncId} tied in
     * OPENING→MOVING): an unrelated screen rejected in OPENING never gets a {@code syncId} and is
     * left untouched, so as not to close something the player opened themselves.
     */
    public void reset() {
        if (syncId != null) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null && mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler
                && handler.syncId == syncId) {
                mc.player.closeHandledScreen();
            }
        }
        phase = Phase.IDLE;
        targetPos = null;
        tracker.clear();
        syncId = null;
    }

    private Optional<Boolean> finish(MinecraftClient mc, boolean ok) {
        reset();
        return Optional.of(ok);
    }

    private static boolean hasEmptySlot(GenericContainerScreenHandler handler, int containerSlots) {
        for (int i = 0; i < containerSlots; i++) {
            if (handler.slots.get(i).getStack().isEmpty()) return true;
        }
        return false;
    }
}
