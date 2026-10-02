package com.xploits.bench;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts the chat packets that really leave the client: a listener at {@code LOWEST} sees only what nobody cancelled
 * (Orbit stops a cancelled event; spike S8). Subscribed once, never unsubscribed; a run reads it before and after.
 */
final class OutgoingChat {
    private static final OutgoingChat INSTANCE = new OutgoingChat();
    private static volatile boolean subscribed;

    private final AtomicLong left = new AtomicLong();

    private OutgoingChat() {
    }

    /** The counter, subscribed on first use. Client thread. */
    static OutgoingChat get() {
        if (!subscribed) {
            MeteorClient.EVENT_BUS.subscribe(INSTANCE);
            subscribed = true;
        }
        return INSTANCE;
    }

    long left() {
        return left.get();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onSend(PacketEvent.Send event) {
        if (event.packet instanceof ChatMessageC2SPacket) left.incrementAndGet();
    }
}
