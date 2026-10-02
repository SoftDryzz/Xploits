package com.xploits.bench.mixin;

import com.xploits.bench.PlaceJudge;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bench-only (gametest source set, never on the addon's classpath): hands every block interaction and every player action
 * to {@link PlaceJudge} on the server thread, after the packet has been moved to that thread and before the server acts
 * on it, so the judge sees the world exactly as the server is about to use it.
 */
@Mixin(ServerPlayNetworkHandler.class)
abstract class PlaceJudgeMixin {
    @Shadow
    public ServerPlayerEntity player;

    @Inject(method = "onPlayerInteractBlock", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/server/world/ServerWorld;)V",
        shift = At.Shift.AFTER))
    private void xploits$judgePlace(PlayerInteractBlockC2SPacket packet, CallbackInfo ci) {
        PlaceJudge.place(player, packet);
    }

    @Inject(method = "onPlayerAction", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/server/world/ServerWorld;)V",
        shift = At.Shift.AFTER))
    private void xploits$judgeAction(PlayerActionC2SPacket packet, CallbackInfo ci) {
        PlaceJudge.action(player, packet);
    }
}
