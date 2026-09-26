package com.xploits.mixin;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.network.SequencedPacketCreator;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Opens {@code ClientPlayerInteractionManager.sendSequencedPacket}, which is private: crystal-aura++ places
 * its crystals through it exactly as Meteor's CrystalAura does (line 1049), so the placement packet carries
 * the world's next sequence number and nothing else happens (crystal-aura++ spec P8). Required: if Minecraft
 * ever renames the method, the game stops at load instead of crystal-aura++ silently never placing.
 */
@Mixin(ClientPlayerInteractionManager.class)
public interface XploitsInteractionInvoker {
    @Invoker("sendSequencedPacket")
    void xploits$sendSequencedPacket(ClientWorld world, SequencedPacketCreator packetCreator);
}
