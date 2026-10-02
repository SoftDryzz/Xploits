package com.xploits.bench.mixin;

import com.xploits.bench.PlaceJudge;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bench-only (gametest source set, never on the addon's classpath): tells {@link PlaceJudge} which container screen the
 * server drops on its own. {@code ServerPlayerEntity.closeHandledScreen} is the server's own close (out of range or
 * broken in its tick, another screen opened): it sends the client a close and goes back to the player's own screen. A
 * close the client sends takes another way ({@code onCloseHandledScreen} calls {@code onHandledScreenClosed} directly),
 * so only the server's own closes pass here. Runs before the screen is dropped, while it is still the current one.
 */
@Mixin(ServerPlayerEntity.class)
abstract class ServerCloseMixin {
    @Inject(method = "closeHandledScreen", at = @At("HEAD"))
    private void xploits$serverClosed(CallbackInfo ci) {
        PlaceJudge.serverClosed((ServerPlayerEntity) (Object) this);
    }
}
