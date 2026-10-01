package com.xploits.printer.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The per-tick packet rules (printer spec §5.3, §10), shared by the printer's own watch and the bench's rules check. Two
 * windows (spike S5): client ticks, split at {@code ClientTickEndC2SPacket}, and Grim ticks, split at the movement packet
 * or, when a client tick sent none, at its tick end. Packets arrive in send order; a packet is "ours" when the printer
 * sent it.
 */
public final class PaceRules {
    public enum Kind {
        MOVE, TICK_END, PLACE, USE_ITEM, INTERACT_ENTITY, DIG_START, DIG_STOP, DIG_ABORT, RELEASE_USE, ACTION_OTHER, SLOT,
        CLICK_SLOT, CLOSE_SCREEN, SWING, INPUT, SPRINT, OTHER
    }

    /**
     * One outgoing packet, classified.
     *
     * @param rotation a movement packet that carries a rotation
     * @param flag     INPUT: moving, jumping or sneaking; SPRINT: start (true) or stop (false); DIG_START: the block
     *                 breaks at once (no STOP or ABORT follows, spec §5.4)
     * @param slot     SLOT: the slot selected; -1 otherwise
     */
    public record Packet(Kind kind, boolean ours, boolean rotation, float yaw, float pitch, boolean flag, int slot) {
        public static Packet of(Kind kind, boolean ours) {
            return new Packet(kind, ours, false, 0f, 0f, false, -1);
        }

        public static Packet move(boolean rotation, float yaw, float pitch) {
            return new Packet(Kind.MOVE, false, rotation, yaw, pitch, false, -1);
        }

        public static Packet input(boolean movingOrSneaking) {
            return new Packet(Kind.INPUT, false, false, 0f, 0f, movingOrSneaking, -1);
        }

        public static Packet sprint(boolean start) {
            return new Packet(Kind.SPRINT, false, false, 0f, 0f, start, -1);
        }

        public static Packet digStart(boolean ours, boolean instant) {
            return new Packet(Kind.DIG_START, ours, false, 0f, 0f, instant, -1);
        }

        public static Packet slot(int slot, boolean ours) {
            return new Packet(Kind.SLOT, ours, false, 0f, 0f, false, slot);
        }
    }

    public enum Rule {
        MOVES_IN_A_TICK, ACTIONS_IN_A_GRIM_TICK, PLACE_AND_DIG, NO_SWING, ACTION_AFTER_MOVE, SLOT_AFTER_ACTION,
        SAME_SLOT_TWICE, SLOT_DURING_DIG, DIG_TICK_WITHOUT_SWING, DIG_GAP, CLICK_WHILE_MOVING, PLACE_AFTER_USE
    }

    /** A rule broken in client tick {@code tick} (0 = the first tick seen). */
    public record Violation(Rule rule, long tick) {
    }

    private final int digGap;
    private final boolean keep;
    private final List<Violation> violations = new ArrayList<>();

    // The client tick in progress.
    private long tick;
    private int moves;
    private boolean movedThisTick;
    private boolean afterMoveFlagged;
    private boolean swungThisTick;
    private boolean foreignThisTick;
    private boolean digTouched;
    private boolean abortThisTick;
    // The last completed client tick.
    private int movesLastTick;
    private boolean foreignLastTick;
    // The Grim tick in progress.
    private int places;
    private int digStartsAndStops;
    private int digPackets;
    private boolean grimSwing;
    private boolean slotBlocked;
    private boolean usedInGrim;
    private boolean foreignInGrim;
    // Session state.
    private Aim.Rotation lastRotation;
    private int lastSlot = -1;
    private boolean digging;
    private long lastDigEnd = -1;
    private boolean inputMoving;
    private boolean sprinting;
    private long oursSent;

    public PaceRules(PrinterLimits limits) {
        this(limits, true);
    }

    /** {@code keepViolations} false: judge the aim and the stillness only, record nothing (the always-on watch). */
    public PaceRules(PrinterLimits limits, boolean keepViolations) {
        this.digGap = limits.breakGapTicks();
        this.keep = keepViolations;
    }

    public void accept(Packet p) {
        switch (p.kind()) {
            case MOVE -> {
                moves++;
                movedThisTick = true;
                if (p.rotation()) lastRotation = new Aim.Rotation(p.yaw() + 0.0f, p.pitch() + 0.0f);
                closeGrimTick();
            }
            case TICK_END -> endClientTick();
            case PLACE -> {
                action(p);
                if (usedInGrim) violate(Rule.PLACE_AFTER_USE);
                places++;
                slotBlocked = true;
                if (p.ours()) oursSent++;
            }
            case USE_ITEM, INTERACT_ENTITY -> {
                action(p);
                slotBlocked = true;
                if (p.kind() == Kind.USE_ITEM) usedInGrim = true;
            }
            case RELEASE_USE -> {
                action(p);
                slotBlocked = true;
                usedInGrim = true;
            }
            case DIG_START -> {
                action(p);
                digStartsAndStops++;
                digPackets++;
                digTouched = true;
                if (lastDigEnd >= 0 && tick - lastDigEnd < digGap) violate(Rule.DIG_GAP);
                if (p.flag()) {
                    // An instant break: START only, the dig is over at once and the cooldown begins.
                    digging = false;
                    lastDigEnd = tick;
                } else {
                    digging = true;
                }
                if (p.ours()) oursSent++;
            }
            case DIG_STOP -> {
                action(p);
                digStartsAndStops++;
                digPackets++;
                digTouched = true;
                digging = false;
                lastDigEnd = tick;
                if (p.ours()) oursSent++;
            }
            case DIG_ABORT -> {
                action(p);
                digPackets++;
                abortThisTick = true;
                digging = false;
                lastDigEnd = tick;
            }
            case ACTION_OTHER -> {
                action(p);
                slotBlocked = true;
            }
            case SLOT -> {
                action(p);
                if (slotBlocked) violate(Rule.SLOT_AFTER_ACTION);
                if (p.slot() == lastSlot) violate(Rule.SAME_SLOT_TWICE);
                if (digging) violate(Rule.SLOT_DURING_DIG);
                lastSlot = p.slot();
            }
            case CLICK_SLOT -> {
                action(p);
                if (inputMoving || sprinting) violate(Rule.CLICK_WHILE_MOVING);
            }
            case CLOSE_SCREEN -> {
                afterMove();
                if (inputMoving || sprinting) violate(Rule.CLICK_WHILE_MOVING);
            }
            case SWING -> {
                afterMove();
                swungThisTick = true;
                grimSwing = true;
            }
            case INPUT -> inputMoving = p.flag();
            case SPRINT -> {
                sprinting = p.flag();
                slotBlocked = true;
            }
            case OTHER -> {
            }
        }
    }

    /** A new connection: everything forgotten. */
    public void reset() {
        violations.clear();
        tick = 0;
        moves = 0;
        movedThisTick = false;
        afterMoveFlagged = false;
        swungThisTick = false;
        foreignThisTick = false;
        digTouched = false;
        abortThisTick = false;
        movesLastTick = 0;
        foreignLastTick = false;
        clearGrimTick();
        lastRotation = null;
        lastSlot = -1;
        digging = false;
        lastDigEnd = -1;
        inputMoving = false;
        sprinting = false;
        oursSent = 0;
    }

    public List<Violation> violations() {
        return List.copyOf(violations);
    }

    /** Client ticks completed. */
    public long tick() {
        return tick;
    }

    public int movesLastTick() {
        return movesLastTick;
    }

    public Optional<Aim.Rotation> lastRotation() {
        return Optional.ofNullable(lastRotation);
    }

    /** §5.3 (a): at most one movement packet in the last tick, and the rotation the server last received is this one. */
    public boolean aimHeld(Aim.Rotation wanted) {
        return moves == 0 && movesLastTick <= 1 && wanted.equals(lastRotation);
    }

    /**
     * Someone else's place, use, attack, release, other player command, hotbar slot change or inventory click in the last
     * completed client tick (§5.3 "acting"). A player scrolling the hotbar counts as acting: conservative on purpose.
     */
    public boolean foreignActionLastTick() {
        return foreignLastTick;
    }

    /**
     * Someone else's action in the Grim tick still open: an action of ours now would share it. Foreign slot changes and
     * inventory clicks count too (conservative on purpose).
     */
    public boolean foreignActionThisGrimTick() {
        return foreignInGrim;
    }

    /** No right-click, release-use or sprint command so far in this Grim tick (Grim {@code PacketOrderE}). */
    public boolean slotChangeAllowed() {
        return !slotBlocked;
    }

    /** The last input packet had no movement, jump or sneak, and sprint is off (spike S5). */
    public boolean stillAsServerKnows() {
        return !inputMoving && !sprinting;
    }

    /** Our place, START and STOP packets that left the client. */
    public long oursSent() {
        return oursSent;
    }

    private void action(Packet p) {
        afterMove();
        foreign(p);
    }

    /** Grim {@code Post} / {@code PacketOrderO}: one flag per client tick however many packets follow the move. */
    private void afterMove() {
        if (movedThisTick && !afterMoveFlagged) {
            afterMoveFlagged = true;
            violate(Rule.ACTION_AFTER_MOVE);
        }
    }

    private void foreign(Packet p) {
        if (p.ours()) return;
        foreignThisTick = true;
        foreignInGrim = true;
    }

    private void endClientTick() {
        if (moves > 1) violate(Rule.MOVES_IN_A_TICK);
        if (digTouched && !abortThisTick && !swungThisTick) violate(Rule.DIG_TICK_WITHOUT_SWING);
        if (moves == 0) closeGrimTick();
        movesLastTick = moves;
        foreignLastTick = foreignThisTick;
        moves = 0;
        movedThisTick = false;
        afterMoveFlagged = false;
        swungThisTick = false;
        foreignThisTick = false;
        abortThisTick = false;
        tick++;
        digTouched = digging;
    }

    private void closeGrimTick() {
        if (places + digStartsAndStops > 1) violate(Rule.ACTIONS_IN_A_GRIM_TICK);
        if (places > 0 && digPackets > 0) violate(Rule.PLACE_AND_DIG);
        if ((places > 0 || digStartsAndStops > 0) && !grimSwing) violate(Rule.NO_SWING);
        clearGrimTick();
    }

    private void clearGrimTick() {
        places = 0;
        digStartsAndStops = 0;
        digPackets = 0;
        grimSwing = false;
        slotBlocked = false;
        usedInGrim = false;
        foreignInGrim = false;
    }

    private void violate(Rule rule) {
        if (keep) violations.add(new Violation(rule, tick));
    }
}
