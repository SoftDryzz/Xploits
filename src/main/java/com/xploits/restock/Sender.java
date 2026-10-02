package com.xploits.restock;

import com.xploits.mixin.XploitsInteractionInvoker;
import meteordevelopment.meteorclient.mixininterface.IClientPlayerInteractionManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Every packet of an unpack, marked as restock's own for {@link PacketWatch} (spike S7): places and digs through the
 * sequenced invoker with a lambda that only builds the packet, so nothing is predicted — the box appears and goes when
 * the server says so — and the swing a vanilla click sends follows in the same tick. The slot goes through vanilla's
 * own sync, never a hand-built packet (S5). The one click in the player's own inventory is owner ruling R43's. Client
 * thread, at {@code TickEvent.Pre} only; callers re-check what must hold right before each call.
 */
final class Sender {
    private final MinecraftClient mc;

    Sender(MinecraftClient mc) {
        this.mc = mc;
    }

    void place(BlockHitResult hit) {
        PacketWatch.get().asOurs(() -> {
            ((XploitsInteractionInvoker) mc.interactionManager).xploits$sendSequencedPacket(mc.world,
                s -> new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hit, s));
            mc.player.swingHand(Hand.MAIN_HAND);
        });
    }

    void digStart(BlockPos pos, Direction side, boolean instant) {
        Runnable send = () -> {
            ((XploitsInteractionInvoker) mc.interactionManager).xploits$sendSequencedPacket(mc.world,
                s -> new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, side, s));
            mc.player.swingHand(Hand.MAIN_HAND);
        };
        if (instant) PacketWatch.get().asOursInstantDig(send);
        else PacketWatch.get().asOurs(send);
    }

    void digStop(BlockPos pos, Direction side) {
        PacketWatch.get().asOurs(() -> {
            ((XploitsInteractionInvoker) mc.interactionManager).xploits$sendSequencedPacket(mc.world,
                s -> new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos, side, s));
            mc.player.swingHand(Hand.MAIN_HAND);
        });
    }

    /** A tick of held digging between START and STOP: the swing vanilla sends. */
    void swing() {
        PacketWatch.get().asOurs(() -> mc.player.swingHand(Hand.MAIN_HAND));
    }

    /** ABORT with sequence 0 (the 3-argument constructor), as vanilla's {@code cancelBlockBreaking}. Tick path only. */
    void digAbort(BlockPos pos, Direction side) {
        PacketWatch.get().asOurs(() -> mc.getNetworkHandler().sendPacket(
            new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, pos, side)));
    }

    /** Selects a hotbar slot through vanilla's sync (Meteor's {@code InvUtils.swap} path); never the one selected. */
    void select(int slot) {
        PacketWatch.get().asOurs(() -> {
            mc.player.getInventory().setSelectedSlot(slot);
            ((IClientPlayerInteractionManager) mc.interactionManager).meteor$syncSelected();
        });
    }

    /**
     * Owner ruling R43: one QUICK_MOVE, button 0, in the player's own screen (syncId 0), of a main-inventory slot
     * (9–35, the same number there); vanilla moves a shulker box from there into the first empty hotbar slot.
     */
    void moveToHotbar(int screenSlot) {
        ClientPlayerEntity p = mc.player;
        PacketWatch.get().asOurs(() -> mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, screenSlot, 0,
            SlotActionType.QUICK_MOVE, p));
    }
}
