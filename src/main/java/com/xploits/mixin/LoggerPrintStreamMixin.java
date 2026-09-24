package com.xploits.mixin;

import com.xploits.shared.ChatLogFilter;
import net.minecraft.util.logging.LoggerPrintStream;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Minecraft routes {@code System.out} to latest.log through this stream. Baritone prints its region
 * files there ({@code Saving region x,z to disk}), which gives the position to within 512 blocks.
 */
@Mixin(LoggerPrintStream.class)
public abstract class LoggerPrintStreamMixin {
    @ModifyVariable(method = "println(Ljava/lang/String;)V", at = @At("HEAD"), argsOnly = true)
    private String xploits$maskPrintedLine(String line) {
        return ChatLogFilter.filterStdout(line);
    }
}
