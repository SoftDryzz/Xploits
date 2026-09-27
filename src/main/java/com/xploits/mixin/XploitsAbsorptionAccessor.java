package com.xploits.mixin;

import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Opens {@code PlayerEntity.ABSORPTION_AMOUNT}, private: crystal-aura++'s R3-15 fix round 3 needs to recognise
 * the absorption entry inside an {@code EntityTrackerUpdateS2CPacket} about us, to know that specific sync (not
 * only the health packet's) has been applied before excluding a crystal whose hit landed while we carried
 * absorption (rereview2-r3-15.md). Required: if Minecraft ever renames the field, the game stops at load instead
 * of the check silently never matching, which would silently keep every absorbed hit unconfirmed instead.
 */
@Mixin(PlayerEntity.class)
public interface XploitsAbsorptionAccessor {
    @Accessor("ABSORPTION_AMOUNT")
    static TrackedData<Float> xploits$absorptionAmount() {
        throw new AssertionError();
    }
}
