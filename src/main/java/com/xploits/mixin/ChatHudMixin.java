package com.xploits.mixin;

import com.xploits.shared.ChatLogFilter;
import net.minecraft.client.gui.hud.ChatHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Masks coordinates in the copy of each chat line that {@code ChatHud.logChatMessage} writes to
 * latest.log. The line shown on screen is a different object and stays as it is.
 */
@Mixin(ChatHud.class)
public abstract class ChatHudMixin {
    @ModifyVariable(method = "logChatMessage", at = @At("STORE"), ordinal = 0)
    private String xploits$maskLoggedLine(String line) {
        return ChatLogFilter.filter(line);
    }
}
