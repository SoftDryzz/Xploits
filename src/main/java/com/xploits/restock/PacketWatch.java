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
import net.minecraft.network.packet.c2s.play.BundleItemSelectedC2SPacket;
import net.minecraft.network.packet.c2s.play.ButtonClickC2SPacket;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientTickEndC2SPacket;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.c2s.play.CraftRequestC2SPacket;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PickItemFromBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PickItemFromEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket;
import net.minecraft.network.packet.c2s.play.SlotChangedStateC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.PlayerInput;

import java.util.Set;

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
    /**
     * Deferred L36 (Task 9 review M5): what the player does to an inventory, a screen or a trade — pick-block (from a
     * block or an entity), the bundle scroll, the creative inventory, a screen button, a recipe-book craft, a villager
     * trade, a crafter slot toggle. PaceRules counts inventory actions as acting, conservative on purpose, so each is a
     * foreign action unless restock sent it. Class literals, so naming them initialises none of them.
     */
    static final Set<Class<?>> PLAYER_ACTIONS = Set.of(PickItemFromBlockC2SPacket.class,
        PickItemFromEntityC2SPacket.class, BundleItemSelectedC2SPacket.class, CreativeInventoryActionC2SPacket.class,
        ButtonClickC2SPacket.class, CraftRequestC2SPacket.class, SelectMerchantTradeC2SPacket.class,
        SlotChangedStateC2SPacket.class);
    private static boolean started;

    private final PaceRules rules = new PaceRules(PrinterLimits.DEFAULTS, false);
    /** How many of restock's own sends are in progress (client thread). */
    private int ours;
    /** Restock is sending a dig START that breaks its block at once: no STOP follows (the rules' instant flag). */
    private boolean instantDig;
    /** Restock's own slot clicks that left ({@link #ourSlotClick}); {@code oursSent} counts places and digs only. */
    private long slotClicksSent;

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

    /** As {@link #asOurs}, for a dig START that breaks the block at once (pre-flight 19-18). */
    void asOursInstantDig(Runnable send) {
        synchronized (this) {
            instantDig = true;
        }
        try {
            asOurs(send);
        } finally {
            synchronized (this) {
                instantDig = false;
            }
        }
    }

    /** True while restock sends an instant dig START; the bench's recorder reads it within the same send. */
    public synchronized boolean sendingInstantDig() {
        return instantDig;
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

    /** Restock's own block interactions, dig STARTs and STOPs that left ({@link PaceRules#oursSent}). */
    synchronized long oursSent() {
        return rules.oursSent();
    }

    /** Restock's own slot clicks that left (ruling R70: a take's box click is noted only once its packet left). */
    synchronized long slotClicksSent() {
        return slotClicksSent;
    }

    /** One of restock's own slot clicks: what {@link #slotClicksSent} counts. */
    static boolean ourSlotClick(Packet<?> packet, boolean ours) {
        return ours && packet instanceof ClickSlotC2SPacket;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onSend(PacketEvent.Send event) {
        Packet<?> packet = event.packet;
        synchronized (this) {
            rules.accept(classify(packet, ours > 0, instantDig));
            if (ourSlotClick(packet, ours > 0)) slotClicksSent++;
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

    /** One outgoing packet as {@link PaceRules} reads it (a START is never instant here). */
    public static PaceRules.Packet classify(Packet<?> packet, boolean ours) {
        return classify(packet, ours, false);
    }

    /** One outgoing packet as {@link PaceRules} reads it; {@code instantDig}: a START breaks its block at once. */
    public static PaceRules.Packet classify(Packet<?> packet, boolean ours, boolean instantDig) {
        if (packet instanceof PlayerActionC2SPacket a
            && a.getAction() == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK) {
            return PaceRules.Packet.digStart(ours, instantDig);
        }
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
        for (Class<?> action : PLAYER_ACTIONS) {
            if (action.isInstance(packet)) return PaceRules.Packet.of(PaceRules.Kind.ACTION_OTHER, ours);
        }
        return PaceRules.Packet.of(PaceRules.Kind.OTHER, ours);
    }
}
