package com.xploits.restock;

import com.xploits.printer.core.Aim;
import com.xploits.printer.core.PaceRules;
import com.xploits.printer.core.PrinterLimits;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientTickEndC2SPacket;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.PlayerInput;

/**
 * Every packet that really leaves the client, judged by the printer's {@link PaceRules} (restock spec §3 "Take": stand
 * still before any container click and close; look at the container): Meteor posts {@code PacketEvent.Send} at the head
 * of {@code ClientConnection.send} and Orbit stops a cancelled event, so a listener at {@code LOWEST} sees exactly what
 * leaves (spike S8). Subscribed once at start-up and never unsubscribed, so the rotation the server last received and the
 * input it last heard are known before restock is ever turned on; it records no violation (the bench keeps its own
 * recorder). Packets can be sent from the Netty thread too, so every access is synchronized.
 */
public final class PacketWatch {
    private static final PacketWatch INSTANCE = new PacketWatch();
    private static boolean started;

    private final PaceRules rules = new PaceRules(PrinterLimits.DEFAULTS, false);
    /** How many of restock's own sends are in progress (client thread). */
    private int ours;

    private PacketWatch() {
    }

    public static synchronized void start() {
        if (started) return;
        started = true;
        MeteorClient.EVENT_BUS.subscribe(INSTANCE);
    }

    public static PacketWatch get() {
        return INSTANCE;
    }

    /** Runs {@code send} with every packet it sends marked as restock's own. */
    void asOurs(Runnable send) {
        synchronized (this) {
            ours++;
        }
        try {
            send.run();
        } finally {
            synchronized (this) {
                ours--;
            }
        }
    }

    synchronized boolean aimHeld(Aim.Rotation wanted) {
        return rules.aimHeld(wanted);
    }

    synchronized boolean foreignActionLastTick() {
        return rules.foreignActionLastTick();
    }

    synchronized boolean foreignActionThisGrimTick() {
        return rules.foreignActionThisGrimTick();
    }

    synchronized boolean slotChangeAllowed() {
        return rules.slotChangeAllowed();
    }

    synchronized boolean stillAsServerKnows() {
        return rules.stillAsServerKnows();
    }

    synchronized long oursSent() {
        return rules.oursSent();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onSend(PacketEvent.Send event) {
        Packet<?> packet = event.packet;
        synchronized (this) {
            rules.accept(classify(packet, ours > 0));
        }
    }

    @EventHandler
    private void onJoin(GameJoinedEvent event) {
        synchronized (this) {
            rules.reset();
        }
    }

    @EventHandler
    private void onLeft(GameLeftEvent event) {
        synchronized (this) {
            rules.reset();
        }
    }

    /** One outgoing packet as {@link PaceRules} reads it. */
    public static PaceRules.Packet classify(Packet<?> packet, boolean ours) {
        if (packet instanceof PlayerMoveC2SPacket move) {
            return PaceRules.Packet.move(move.changesLook(), move.getYaw(0f), move.getPitch(0f));
        }
        if (packet instanceof ClientTickEndC2SPacket) return PaceRules.Packet.of(PaceRules.Kind.TICK_END, ours);
        if (packet instanceof PlayerInteractBlockC2SPacket) return PaceRules.Packet.of(PaceRules.Kind.PLACE, ours);
        if (packet instanceof PlayerInteractItemC2SPacket) return PaceRules.Packet.of(PaceRules.Kind.USE_ITEM, ours);
        if (packet instanceof PlayerInteractEntityC2SPacket) return PaceRules.Packet.of(PaceRules.Kind.INTERACT_ENTITY, ours);
        if (packet instanceof PlayerActionC2SPacket action) {
            PaceRules.Kind kind = switch (action.getAction()) {
                case START_DESTROY_BLOCK -> PaceRules.Kind.DIG_START;
                case STOP_DESTROY_BLOCK -> PaceRules.Kind.DIG_STOP;
                case ABORT_DESTROY_BLOCK -> PaceRules.Kind.DIG_ABORT;
                case RELEASE_USE_ITEM -> PaceRules.Kind.RELEASE_USE;
                case STAB -> PaceRules.Kind.INTERACT_ENTITY;
                default -> PaceRules.Kind.ACTION_OTHER;
            };
            return PaceRules.Packet.of(kind, ours);
        }
        if (packet instanceof UpdateSelectedSlotC2SPacket slot) return PaceRules.Packet.slot(slot.getSelectedSlot(), ours);
        if (packet instanceof ClickSlotC2SPacket) return PaceRules.Packet.of(PaceRules.Kind.CLICK_SLOT, ours);
        if (packet instanceof CloseHandledScreenC2SPacket) return PaceRules.Packet.of(PaceRules.Kind.CLOSE_SCREEN, ours);
        if (packet instanceof HandSwingC2SPacket) return PaceRules.Packet.of(PaceRules.Kind.SWING, ours);
        if (packet instanceof PlayerInputC2SPacket input) {
            PlayerInput in = input.input();
            return PaceRules.Packet.input(in.forward() || in.backward() || in.left() || in.right() || in.jump() || in.sneak());
        }
        if (packet instanceof ClientCommandC2SPacket command) {
            if (command.getMode() == ClientCommandC2SPacket.Mode.START_SPRINTING) return PaceRules.Packet.sprint(true);
            if (command.getMode() == ClientCommandC2SPacket.Mode.STOP_SPRINTING) return PaceRules.Packet.sprint(false);
        }
        return PaceRules.Packet.of(PaceRules.Kind.OTHER, ours);
    }
}
