package com.xploits.bench;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts the crystal placement packets the client sends, as the fight recorder does (a block
 * interaction with an end crystal in that hand), but whether or not a fight is recorded. It is what the
 * rule "zero records is valid only if no placement packet was sent" (spec {@code
 * 2026-09-25-ingame-bench}, §Metrics) is checked against.
 *
 * <p>One counter for the whole session, subscribed once and never unsubscribed: it only counts, so a
 * run reads it at T0 and at the close and keeps the difference.
 */
final class PlacementCounter {
    private static final PlacementCounter INSTANCE = new PlacementCounter();
    private static volatile boolean subscribed;

    private final AtomicInteger sent = new AtomicInteger();

    private PlacementCounter() {
    }

    /** The counter, subscribed to Meteor's event bus on first use. Client thread. */
    static PlacementCounter get() {
        if (!subscribed) {
            MeteorClient.EVENT_BUS.subscribe(INSTANCE);
            subscribed = true;
        }
        return INSTANCE;
    }

    /** Placement packets sent since the session began. */
    int sent() {
        return sent.get();
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        if (event.packet instanceof PlayerInteractBlockC2SPacket place && mc.player.getStackInHand(place.getHand()).isOf(Items.END_CRYSTAL)) {
            sent.incrementAndGet();
        }
    }
}
