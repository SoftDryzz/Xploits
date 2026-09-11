package com.kitbot.inventory;

import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;

/** Vacía shulkers en un ender chest al alcance (spec §6). Mueve uno por tick y nunca camina. */
public final class EnderDepositor {
    public static final double REACH = 4.5;

    private static final int SCAN_RADIUS = 5;
    private static final long OPEN_TIMEOUT_MS = 5_000;

    private enum Phase { IDLE, OPENING, MOVING }

    private Phase phase = Phase.IDLE;
    private long openedAt;

    public static Optional<BlockPos> findInReach(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return Optional.empty();
        BlockPos center = mc.player.getBlockPos();
        Vec3d eyes = mc.player.getEyePos();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(center.add(-SCAN_RADIUS, -SCAN_RADIUS, -SCAN_RADIUS),
                                             center.add(SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS))) {
            if (!mc.world.getBlockState(pos).isOf(Blocks.ENDER_CHEST)) continue;
            double distance = eyes.distanceTo(Vec3d.ofCenter(pos));
            if (distance <= REACH && distance < bestDistance) {
                best = pos.toImmutable();
                bestDistance = distance;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Abre el ender chest al alcance más cercano. Devuelve false si no hay ninguno. */
    public boolean start(MinecraftClient mc, long now) {
        Optional<BlockPos> target = findInReach(mc);
        if (target.isEmpty()) return false;
        BlockPos pos = target.get();
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
            new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false));
        phase = Phase.OPENING;
        openedAt = now;
        return true;
    }

    /** Llamar en cada tick mientras OrderMachine está en DEPOSIT. */
    public Optional<Boolean> tick(MinecraftClient mc, long now) {
        switch (phase) {
            case IDLE -> {
                return Optional.of(false);
            }
            case OPENING -> {
                if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler) phase = Phase.MOVING;
                else if (now - openedAt > OPEN_TIMEOUT_MS) return finish(mc, false);
                return Optional.empty();
            }
            case MOVING -> {
                if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler)) return finish(mc, false);
                int containerSlots = handler.getRows() * 9; // 27 o 54: en 6b6t el ender chest puede ser doble
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

    public void reset() {
        phase = Phase.IDLE;
    }

    private Optional<Boolean> finish(MinecraftClient mc, boolean ok) {
        if (mc.player != null && mc.player.currentScreenHandler instanceof GenericContainerScreenHandler) {
            mc.player.closeHandledScreen();
        }
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
