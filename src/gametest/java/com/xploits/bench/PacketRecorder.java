package com.xploits.bench;

import com.xploits.printer.core.PaceRules;
import com.xploits.printer.core.PrinterLimits;
import com.xploits.restock.PacketWatch;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.TeleportConfirmC2SPacket;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The rules check on our outgoing packets (restock spec §6 "no container click while moving (rules recorder)"): every
 * packet that leaves the client during a run, judged by a {@link PaceRules} that keeps every violation. It also counts
 * the client ticks in which a container click, close or interact went out while the player's position changed: the
 * scripted walker moves the player with no movement input, which {@code PaceRules}' own input rule cannot see. It listens
 * at {@code LOWEST}: Orbit stops a cancellable post at the first listener that cancels (spike S8), so a cancelled packet
 * never arrives here. Packets are classified with restock's own table ({@link PacketWatch#classify}), with one
 * exception Grim makes too: the movement packet that answers a server teleport is not the tick's movement
 * packet ({@link #teleportAnswerNext}). Sends come from the client thread and Netty's, so every method is
 * synchronized. Never prints a position.
 */
final class PacketRecorder {
    /** Below this, a movement packet did not move the player. */
    private static final double MOVED = 1.0E-4;

    private final PaceRules rules = new PaceRules(PrinterLimits.DEFAULTS);
    private int interacts;
    private int containerActions;
    private int clicks;
    private int closes;
    private boolean moved;
    private int walkingClicks;
    private double lastX = Double.NaN;
    private double lastY = Double.NaN;
    private double lastZ = Double.NaN;
    /**
     * A {@code TeleportConfirmC2SPacket} just left, and no tick has ended since: a {@code PlayerMoveC2SPacket.Full}
     * that follows it answers the server's teleport. Vanilla 1.21.11 sends both together from
     * {@code ClientPlayNetworkHandler.onPlayerPositionLook}, on the client thread outside the tick (javap -c: the
     * confirm, then a {@code PlayerMoveC2SPacket.Full}). Grim (GrimAnticheat/Grim 2.0) does not count that answer as
     * the tick's movement: {@code GrimProcessor.isTickPacket} (GrimProcessor.java:74-82) takes a flying packet only
     * while the last packet was not a teleport, and the checks that read the tick's packets key on it
     * ({@code Post.java:77-81}, {@code PacketOrderO.java:36}, {@code TickTimer.java:31},
     * {@code CheckManagerListener.java:422-423}). The recorder reads it the same way: that one packet is no movement
     * packet ({@code OTHER}), its position still tracked for the walking clicks. Any tick-ending packet clears the
     * flag.
     */
    private boolean teleportAnswerNext;

    private PacketRecorder() {
    }

    /** A fresh recorder, subscribed now (before T0); the teardown unsubscribes it. */
    static PacketRecorder start(Bench bench) {
        PacketRecorder recorder = new PacketRecorder();
        bench.onClient(client -> MeteorClient.EVENT_BUS.subscribe(recorder));
        bench.atDespawn(() -> bench.onClient(client -> MeteorClient.EVENT_BUS.unsubscribe(recorder)));
        return recorder;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onSend(PacketEvent.Send event) {
        if (event.isCancelled()) return;
        PaceRules.Packet classified = PacketWatch.classify(event.packet, false, PacketWatch.get().sendingInstantDig());
        synchronized (this) {
            PaceRules.Packet p = classified;
            if (event.packet instanceof TeleportConfirmC2SPacket) {
                teleportAnswerNext = true;
            } else if (teleportAnswerNext && event.packet instanceof PlayerMoveC2SPacket.Full) {
                teleportAnswerNext = false;
                p = PaceRules.Packet.of(PaceRules.Kind.OTHER, false);
            }
            if (event.packet instanceof PlayerMoveC2SPacket move && move.changesPosition()) {
                double x = move.getX(0);
                double y = move.getY(0);
                double z = move.getZ(0);
                if (!Double.isNaN(lastX) && (Math.abs(x - lastX) > MOVED || Math.abs(y - lastY) > MOVED
                    || Math.abs(z - lastZ) > MOVED)) {
                    moved = true;
                }
                lastX = x;
                lastY = y;
                lastZ = z;
            }
            switch (p.kind()) {
                case PLACE -> {
                    interacts++;
                    containerActions++;
                }
                case CLICK_SLOT -> {
                    clicks++;
                    containerActions++;
                }
                case CLOSE_SCREEN -> {
                    closes++;
                    containerActions++;
                }
                case TICK_END -> {
                    teleportAnswerNext = false;
                    if (moved && containerActions > 0) walkingClicks++;
                    moved = false;
                    containerActions = 0;
                }
                default -> {
                }
            }
            rules.accept(p);
        }
    }

    /** Block interactions sent (the container clicks, and phase B's placements). */
    synchronized int interacts() {
        return interacts;
    }

    /** Slot clicks sent. */
    synchronized int clicks() {
        return clicks;
    }

    /** Screen closes sent. */
    synchronized int closes() {
        return closes;
    }

    /** Client ticks with a container click, close or interact while the position changed. */
    synchronized int walkingClicks() {
        return walkingClicks;
    }

    synchronized long tick() {
        return rules.tick();
    }

    synchronized List<PaceRules.Violation> violations() {
        return rules.violations();
    }

    /** "no rule broken", or each rule broken with its count: "NO_SWING ×2; DIG_GAP ×1". */
    synchronized String violationWords() {
        Map<PaceRules.Rule, Integer> byRule = new EnumMap<>(PaceRules.Rule.class);
        for (PaceRules.Violation v : rules.violations()) byRule.merge(v.rule(), 1, Integer::sum);
        if (byRule.isEmpty()) return "no rule broken";
        List<String> parts = new ArrayList<>();
        byRule.forEach((rule, n) -> parts.add(rule.name() + " ×" + n));
        return String.join("; ", parts);
    }
}
