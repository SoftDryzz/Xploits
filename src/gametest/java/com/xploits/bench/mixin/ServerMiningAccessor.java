package com.xploits.bench.mixin;

import net.minecraft.server.network.ServerPlayerInteractionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Bench-only (gametest source set, never on the addon's classpath): whether the server holds a dig of the player under
 * way. {@code ServerPlayerInteractionManager.processBlockBreakingAction} sets {@code mining} at a START that does not
 * break the block at once and clears it at the STOP and at the ABORT (verified with {@code javap -c}), so the server's
 * judge of owner ruling R43's click ({@code PlaceJudge.ownInventoryMove}) reads the dig as the server itself sees it.
 */
@Mixin(ServerPlayerInteractionManager.class)
public interface ServerMiningAccessor {
    @Accessor("mining")
    boolean xploits$mining();
}
