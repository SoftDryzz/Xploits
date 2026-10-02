package com.xploits.restock.core;

import com.xploits.printer.core.Aim;
import com.xploits.printer.core.BreakPlan;
import com.xploits.printer.core.Face;
import com.xploits.printer.core.HotbarPlan;
import com.xploits.printer.core.Pos;
import com.xploits.printer.core.PrinterLimits;
import com.xploits.travel.core.StallWatch;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Unpacking one shulker box at the build (restock spec §3 "Shulkers at the build"; owner rulings R42, R43), one step per
 * client tick, the printer paused throughout. After the printer's settle when this unpack switched it off: the box into
 * the hotbar (one QUICK_MOVE at most, R43) and selected; set down on a free cell outside the build (aim one tick, click
 * the next, only while still with no screen; another cell while it is still in the inventory, at most
 * {@code spotAttempts}); the take from it (the adapter runs a {@link RestockTrip} on it and reports); the dig on vanilla's
 * held-mining clock — the tool selected, START once the server holds it (P18), no progress in START's own tick, a swing
 * every tick, STOP when it reaches 1; a pause, a move, a screen, a changed slot or delta lets go (ABORT) and starts again
 * after the gap, every let-go counted but that of a box gone mid-dig, at most {@code digRestarts} times; the cell empty (P23); the box back in the
 * inventory, by itself or walked onto;
 * back to where it began; the slot selected then, selected again. Every wait counts only unpaused ticks and is bounded.
 * A failure of the take that is not the player's hands or a fault still breaks and picks the box up, then stops with it.
 * Owner ruling R42: {@link #halt} stops at once with the one action allowed; {@link #drain} finishes the break and the
 * pick-up first, within {@code drainLimitTicks}. A stop carries at most one action: the ABORT of a dig under way, else the
 * slot given back when the player stands still, nothing is open and a slot change is allowed.
 */
public final class UnpackPlan {
    public enum Phase { PAUSING, SELECT, PLACE, PLACED, CONTENTS, DIG, DUG, PICK_UP, RETURN, RESTORE, DONE, STOPPED }

    /** The tick after a {@link Place} or a {@link DigStart}: whether its packet left; WITHHELD: the re-check before it failed. */
    public enum Click { NONE, SENT, REFUSED, WITHHELD }

    /** The take from the box set down, as the adapter's inner trip reports it. */
    public enum Contents { RUNNING, DONE, FAILED }

    /**
     * @param material      the material that ran out, which the box holds
     * @param shulker       the box's item id
     * @param carried       how many boxes of this box's kind (item and custom name) the player carries now, this one among
     *                      them (M4)
     * @param resume        the block the player stands on now, to come back to
     * @param originalSlot  the hotbar slot selected now, selected again at the end
     * @param printerPaused this unpack switched litematica-printer off: its settle first
     */
    public record Plan(String material, String shulker, int carried, Pos resume, int originalSlot,
                       boolean printerPaused) {
        public Plan {
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(shulker, "shulker");
            Objects.requireNonNull(resume, "resume");
            if (material.isBlank() || shulker.isBlank()) throw new IllegalArgumentException("an unpack names its box and material");
            if (carried < 1) throw new IllegalArgumentException("unpacking needs a carried box");
            if (originalSlot < 0 || originalSlot > 8) throw new IllegalArgumentException("hotbar slot " + originalSlot);
        }

        /** Never a position ({@link HiddenPositions}). */
        @Override
        public String toString() {
            return "Plan[material=" + material + ", shulker=" + shulker + ", carried=" + carried + ", resume="
                + HiddenPositions.HIDDEN + ", originalSlot=" + originalSlot + ", printerPaused=" + printerPaused + "]";
        }
    }

    /** The face of the box to dig, and the rotation that looks at it. */
    public record DigAim(Face side, Aim.Rotation rotation) {
        public DigAim {
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(rotation, "rotation");
        }
    }

    /**
     * One tick as the adapter measured it.
     *
     * @param movementKeys      the player holds forward, back, left, right, jump or sneak
     * @param paused            a guard pause, a yield, or (finishing) lag, eating or someone else acting
     * @param still             still as the server knows it, on ground, not sneaking or using an item, no walking goal
     * @param screenFree        no screen open and nothing on the cursor (false too when a close went out this tick)
     * @param slotChangeAllowed no right-click, release-use or sprint command so far in this Grim tick, no action this tick
     * @param hotbar            {@code HotbarPlan.forMaterial} for this box (its kind, holding the material)
     * @param selected          the selected hotbar slot
     * @param spot              PLACE: {@code ShulkerSpot.choose} now, avoiding {@link #tried()}
     * @param aimHeld           PLACE and DIG: the server holds the rotation last asked for, and the ray still sees the face
     * @param click             the tick after a {@link Place} or a {@link DigStart}: whether its packet left
     * @param standing          the cell the box was set on while a box stands there, else another tried cell one stands on
     * @param contents          CONTENTS: the inner trip's state; {@code contentsStop}: its reason when FAILED
     * @param tool              DIG: {@code BreakPlan.choose} for the box with the hotbar now, weapons left out
     * @param digAim            DIG: a face of the box to dig and its rotation, if one is in reach
     * @param vanillaDelta      DIG: vanilla's breaking delta for the box with the selected slot now
     * @param carried           how many boxes of the plan's kind (item and custom name) the player carries now (M4)
     * @param drop              PICK_UP: the block of the nearest dropped box of that kind, if one is seen
     * @param arrived           RETURN: at the goal, on ground
     * @param distance          RETURN: to the goal, in blocks
     */
    public record Facts(boolean movementKeys, boolean paused, boolean still, boolean screenFree,
                        boolean slotChangeAllowed, HotbarPlan.Step hotbar, int selected,
                        Optional<ShulkerSpot.Choice> spot, boolean aimHeld, Click click, Optional<Pos> standing,
                        Contents contents, Optional<RestockReason> contentsStop, Optional<BreakPlan.Choice> tool,
                        Optional<DigAim> digAim, float vanillaDelta, int carried, Optional<Pos> drop, boolean arrived,
                        double distance) {
        public Facts {
            Objects.requireNonNull(hotbar, "hotbar");
            Objects.requireNonNull(spot, "spot");
            Objects.requireNonNull(click, "click");
            Objects.requireNonNull(standing, "standing");
            Objects.requireNonNull(contents, "contents");
            Objects.requireNonNull(contentsStop, "contentsStop");
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(digAim, "digAim");
            Objects.requireNonNull(drop, "drop");
        }

        /** Never a position ({@link HiddenPositions}): the spot, the standing cell and the drop are. */
        @Override
        public String toString() {
            return "Facts[movementKeys=" + movementKeys + ", paused=" + paused + ", still=" + still + ", screenFree="
                + screenFree + ", slotChangeAllowed=" + slotChangeAllowed + ", hotbar=" + hotbar + ", selected="
                + selected + ", spot=" + HiddenPositions.of(spot) + ", aimHeld=" + aimHeld + ", click=" + click
                + ", standing=" + HiddenPositions.of(standing) + ", contents=" + contents + ", contentsStop="
                + contentsStop + ", tool=" + tool + ", digAim=" + digAim + ", vanillaDelta=" + vanillaDelta
                + ", carried=" + carried + ", drop=" + HiddenPositions.of(drop) + ", arrived=" + arrived
                + ", distance=" + distance + "]";
        }
    }

    public sealed interface Action permits Wait, Select, MoveToHotbar, AimAt, Place, OpenContents, DigStart, Swing,
        DigStop, DigAbort, GoTo, StopWalking, Finish, Stopped {
    }

    /** Nothing to send this tick. */
    public record Wait() implements Action {
    }

    /** Select this hotbar slot (never the one selected). */
    public record Select(int slot) implements Action {
    }

    /** Owner ruling R43: one QUICK_MOVE, syncId 0, button 0, of this main-inventory slot (9–35, the same number in the player's own screen). */
    public record MoveToHotbar(int screenSlot) implements Action {
    }

    /** Request this rotation for the tick's movement packet. */
    public record AimAt(Aim.Rotation rotation) implements Action {
    }

    /** Click the support's top with the held aim: the box goes on the cell. */
    public record Place(ShulkerSpot.Choice spot) implements Action {
    }

    /** Start the inner trip that opens the box on {@code cell} and takes from it. */
    public record OpenContents(Pos cell) implements Action {
        /** Never a position ({@link HiddenPositions}). */
        @Override
        public String toString() {
            return "OpenContents[cell=" + HiddenPositions.HIDDEN + "]";
        }
    }

    /** START digging; {@code instant}: the box breaks on START, no STOP follows. */
    public record DigStart(Pos cell, Face side, boolean instant) implements Action {
        /** Never a position ({@link HiddenPositions}). */
        @Override
        public String toString() {
            return "DigStart[cell=" + HiddenPositions.HIDDEN + ", side=" + side + ", instant=" + instant + "]";
        }
    }

    /** A tick of held digging: the swing vanilla sends every tick it digs. */
    public record Swing() implements Action {
    }

    public record DigStop(Pos cell, Face side) implements Action {
        /** Never a position ({@link HiddenPositions}). */
        @Override
        public String toString() {
            return "DigStop[cell=" + HiddenPositions.HIDDEN + ", side=" + side + "]";
        }
    }

    public record DigAbort(Pos cell, Face side) implements Action {
        /** Never a position ({@link HiddenPositions}). */
        @Override
        public String toString() {
            return "DigAbort[cell=" + HiddenPositions.HIDDEN + ", side=" + side + "]";
        }
    }

    /** Walk to stand exactly on {@code feet}. */
    public record GoTo(Pos feet) implements Action {
        /** Never a position ({@link HiddenPositions}). */
        @Override
        public String toString() {
            return "GoTo[feet=" + HiddenPositions.HIDDEN + "]";
        }
    }

    public record StopWalking() implements Action {
    }

    /** The box is back, the slot too, the player where the unpacking began. */
    public record Finish() implements Action {
    }

    /**
     * The unpacking ends with {@code reason}; at most one action goes with it: {@code abort}, the ABORT of a dig under
     * way; else {@code select}, the slot selected when it began, selected again.
     */
    public record Stopped(RestockReason reason, Optional<DigAbort> abort, OptionalInt select) implements Action {
        public Stopped {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(abort, "abort");
            Objects.requireNonNull(select, "select");
            if (abort.isPresent() && select.isPresent()) throw new IllegalArgumentException("one action with a stop");
        }
    }

    private static final Wait WAIT = new Wait();
    private static final long NEVER = Long.MIN_VALUE / 2;
    /** Goals onto a drop that keeps moving, at most. */
    private static final int MAX_WALKS = 3;

    private final Plan plan;
    private final UnpackLimits limits;
    private final RestockLimits restock;
    private final StallWatch stall;
    private final Set<Pos> tried = new LinkedHashSet<>();
    private Phase phase;
    private int phaseTicks;
    private int liveTicks;
    private int waitTicks;
    private int heldTicks;
    private boolean entered;
    private long tick;
    private int attempts;
    private boolean moved;
    private Pos cell;
    private Face digSide;
    private BreakPlan.Choice choice;
    private BreakPlan.Clock clock;
    private boolean digging;
    private long lastDigEnd = -1;
    private long selectedAt = NEVER;
    private int restarts;
    private boolean broken;
    private boolean back;
    private boolean walking;
    private int walks;
    private Pos walkedTo;
    private RestockReason soft;
    private RestockReason draining;
    private int drainTicks;

    public UnpackPlan(Plan plan, UnpackLimits limits, RestockLimits restock) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.restock = Objects.requireNonNull(restock, "restock");
        this.stall = new StallWatch(restock.walkStallTicks(), restock.walkProgress());
        enter(plan.printerPaused() ? Phase.PAUSING : Phase.SELECT);
    }

    public Phase phase() {
        return phase;
    }

    public Plan plan() {
        return plan;
    }

    public String material() {
        return plan.material();
    }

    /** The cells a box was set down on (or withheld from) in this unpacking. */
    public Set<Pos> tried() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(tried));
    }

    /** The cell the box was last set down on, or adopted. */
    public Optional<Pos> cell() {
        return Optional.ofNullable(cell);
    }

    public boolean digging() {
        return digging;
    }

    public boolean over() {
        return phase == Phase.DONE || phase == Phase.STOPPED;
    }

    /** A box is out of the inventory, or may be: from the place click until it is back. */
    public boolean outside() {
        return phase == Phase.PLACED || phase == Phase.CONTENTS || phase == Phase.DIG || phase == Phase.DUG
            || phase == Phase.PICK_UP;
    }

    /** The box was broken and has not come back to the inventory (yet, or ever). */
    public boolean dropped() {
        return broken && !back;
    }

    public Optional<RestockReason> draining() {
        return Optional.ofNullable(draining);
    }

    /** The phases in which it aims, clicks or changes the slot itself (the take clicks through its own trip). */
    public boolean clicking() {
        return phase == Phase.SELECT || phase == Phase.PLACE || phase == Phase.DIG || phase == Phase.RESTORE;
    }

    public Action step(Facts f) {
        if (over()) throw new IllegalStateException("the unpacking is over");
        tick++;
        phaseTicks++;
        if (draining != null && ++drainTicks > limits.drainLimitTicks()) return stop(draining, f);
        if (f.movementKeys()) return stop(RestockReason.PLAYER_MOVED, f);
        return switch (phase) {
            case PAUSING -> pausing(f);
            case SELECT -> select(f);
            case PLACE -> place(f);
            case PLACED -> placed(f);
            case CONTENTS -> contents(f);
            case DIG -> digging ? held(f) : dig(f);
            case DUG -> dug(f);
            case PICK_UP -> pickUp(f);
            case RETURN -> back(f);
            case RESTORE -> restore(f);
            case DONE, STOPPED -> throw new IllegalStateException("the unpacking is over");
        };
    }

    /** Owner ruling R42, at once: the stop now, with the one action it allows ({@link Stopped}). */
    public Action halt(Facts f, RestockReason reason) {
        if (over()) throw new IllegalStateException("the unpacking is over");
        return stop(Objects.requireNonNull(reason, "reason"), f);
    }

    /**
     * Owner ruling R42, finishing first: nothing out yet, the next step stops; a box clicked down is not opened; a take
     * under way is cut short by the adapter; the break and the pick-up finish, the slot goes back, no walk back; then the
     * stop with {@code reason}. The first reason counts.
     */
    public void drain(RestockReason reason) {
        Objects.requireNonNull(reason, "reason");
        if (draining == null) {
            draining = reason;
            drainTicks = 0;
        }
    }

    private Action pausing(Facts f) {
        if (draining != null) return stop(draining, f);
        if (phaseTicks <= restock.printerSettleTicks()) return WAIT;
        enter(Phase.SELECT);
        return select(f);
    }

    private Action select(Facts f) {
        if (draining != null) return stop(draining, f);
        return switch (f.hotbar()) {
            case HotbarPlan.Ready r -> {
                enter(Phase.PLACE);
                yield WAIT;
            }
            case HotbarPlan.Select s -> canSelect(f) ? selectSlot(s.slot()) : waitGate(f);
            case HotbarPlan.QuickMove q -> {
                if (moved) yield stop(RestockReason.NO_HOTBAR_ROOM, f);
                if (!canSelect(f)) yield waitGate(f);
                moved = true;
                yield new MoveToHotbar(q.screenSlot());
            }
            case HotbarPlan.NoRoom n -> stop(RestockReason.NO_HOTBAR_ROOM, f);
            case HotbarPlan.NotCarried n -> stop(RestockReason.SHULKER_NOT_CARRIED, f);
        };
    }

    private Action place(Facts f) {
        if (draining != null) return stop(draining, f);
        if (!(f.hotbar() instanceof HotbarPlan.Ready)) {
            enter(Phase.SELECT);
            return WAIT;
        }
        if (!canAct(f)) return waitGate(f);
        if (f.spot().isEmpty()) return stop(RestockReason.SHULKER_NO_SPOT, f);
        ShulkerSpot.Choice s = f.spot().get();
        if (!f.aimHeld()) return aim(f, s.rotation());
        tried.add(s.cell());
        attempts++;
        cell = s.cell();
        enter(Phase.PLACED);
        return new Place(s);
    }

    private Action placed(Facts f) {
        if (phaseTicks == 1 && f.click() == Click.REFUSED) return stop(RestockReason.CLICK_NOT_SENT, f);
        if (phaseTicks == 1 && f.click() == Click.WITHHELD) return another(f);
        if (f.standing().isPresent()) {
            cell = f.standing().get();
            if (draining != null) {
                enter(Phase.DIG);
                return WAIT;
            }
            enter(Phase.CONTENTS);
            return new OpenContents(cell);
        }
        if (f.paused()) return WAIT;
        if (++liveTicks <= limits.placedTimeoutTicks()) return WAIT;
        if (f.carried() < plan.carried()) return stop(RestockReason.SHULKER_NOT_PLACED, f);
        if (draining != null) return stop(draining, f);
        return another(f);
    }

    /** The box is still in the inventory (or nothing was sent): another cell, while attempts are left. */
    private Action another(Facts f) {
        if (attempts >= limits.spotAttempts()) return stop(RestockReason.SHULKER_NOT_PLACED, f);
        enter(Phase.SELECT);
        return WAIT;
    }

    private Action contents(Facts f) {
        return switch (f.contents()) {
            case RUNNING -> WAIT;
            case DONE -> {
                enter(Phase.DIG);
                yield WAIT;
            }
            case FAILED -> {
                RestockReason why = f.contentsStop().orElse(RestockReason.INTERNAL);
                if (why == RestockReason.PLAYER_MOVED || why == RestockReason.INTERNAL) yield stop(why, f);
                if (soft == null) soft = why;
                enter(Phase.DIG);
                yield WAIT;
            }
        };
    }

    /** Before START: the tool, its settle (P18), the aim, the gap. */
    private Action dig(Facts f) {
        if (!holds(f)) {
            enter(Phase.DUG);
            return WAIT;
        }
        if (!canAct(f)) return waitGate(f);
        if (f.tool().isEmpty()) return stop(RestockReason.SHULKER_NOT_BROKEN, f);
        BreakPlan.Choice t = f.tool().get();
        if (f.selected() != t.slot()) return f.slotChangeAllowed() ? selectSlot(t.slot()) : waitGate(f);
        boolean settled = tick - selectedAt > limits.toolSettleTicks()
            && Float.compare(f.vanillaDelta(), t.delta()) == 0;
        if (!settled) {
            if (++liveTicks > limits.settleLimitTicks()) return restart(RestockReason.BREAK_SPEED_MISMATCH, f);
            return WAIT;
        }
        if (f.digAim().isEmpty()) return stop(RestockReason.SHULKER_NOT_BROKEN, f);
        DigAim a = f.digAim().get();
        if (!f.aimHeld()) return aim(f, a.rotation());
        if (!BreakPlan.mayStart(tick, lastDigEnd, PrinterLimits.DEFAULTS)) return WAIT;
        choice = t;
        digSide = a.side();
        if (BreakPlan.Clock.instant(t.delta())) {
            lastDigEnd = tick;
            enter(Phase.DUG);
            return new DigStart(cell, digSide, true);
        }
        clock = new BreakPlan.Clock();
        digging = true;
        heldTicks = 0;
        return new DigStart(cell, digSide, false);
    }

    /** A tick of held digging; the START's own tick never counted (P18). */
    private Action held(Facts f) {
        heldTicks++;
        if (heldTicks == 1 && f.click() == Click.REFUSED) {
            digging = false;
            return stop(RestockReason.CLICK_NOT_SENT, f);
        }
        if (heldTicks == 1 && f.click() == Click.WITHHELD) {
            digging = false;
            enter(Phase.DUG);
            return WAIT;
        }
        // The box gone mid-dig: let go; DIG then finds the cell empty and goes to the pick-up (not a restart).
        if (!holds(f)) return abort();
        // A pause, a move or a screen lets go as vanilla does (M2: handleBlockBreaking(false) when a screen opens),
        // and counts like any restart (I3): an interruption that keeps coming back ends the unpack, the ABORT with the
        // stop.
        if (f.paused() || !f.still() || !f.screenFree()) {
            if (++restarts > limits.digRestarts()) return stop(RestockReason.UNPACK_BLOCKED, f);
            return abort();
        }
        if (f.selected() != choice.slot() || Float.compare(f.vanillaDelta(), choice.delta()) != 0) {
            if (++restarts > limits.digRestarts()) return stop(RestockReason.BREAK_SPEED_MISMATCH, f);
            return abort();
        }
        if (clock.tick(choice.delta()) == BreakPlan.Step.STOP) {
            digging = false;
            lastDigEnd = tick;
            enter(Phase.DUG);
            return new DigStop(cell, digSide);
        }
        return new Swing();
    }

    /** Vanilla lets go: ABORT, and the next START waits the gap. */
    private Action abort() {
        digging = false;
        lastDigEnd = tick;
        Action a = new DigAbort(cell, digSide);
        enter(Phase.DIG);
        return a;
    }

    private Action dug(Facts f) {
        if (phaseTicks == 1 && f.click() == Click.REFUSED) return stop(RestockReason.CLICK_NOT_SENT, f);
        if (!holds(f)) {
            broken = true;
            enter(Phase.PICK_UP);
            return WAIT;
        }
        if (f.paused()) return WAIT;
        if (++liveTicks <= limits.goneTimeoutTicks()) return WAIT;
        return restart(RestockReason.SHULKER_NOT_BROKEN, f);
    }

    private Action restart(RestockReason reason, Facts f) {
        if (++restarts > limits.digRestarts()) return stop(reason, f);
        enter(Phase.DIG);
        return WAIT;
    }

    private Action pickUp(Facts f) {
        if (f.carried() >= plan.carried()) {
            back = true;
            boolean wasWalking = walking;
            walking = false;
            enter(draining != null ? Phase.RESTORE : Phase.RETURN);
            return wasWalking ? new StopWalking() : WAIT;
        }
        if (f.paused()) return WAIT;
        liveTicks++;
        if (liveTicks > limits.pickUpTimeoutTicks()) return stop(RestockReason.SHULKER_NOT_PICKED_UP, f);
        if (liveTicks > limits.pickUpWaitTicks() && walks < MAX_WALKS && f.drop().isPresent()
            && !f.drop().get().equals(walkedTo)) {
            walking = true;
            walks++;
            walkedTo = f.drop().get();
            return new GoTo(walkedTo);
        }
        return WAIT;
    }

    private Action back(Facts f) {
        if (draining != null) {
            boolean wasWalking = walking;
            walking = false;
            enter(Phase.RESTORE);
            return wasWalking ? new StopWalking() : WAIT;
        }
        if (!entered) {
            entered = true;
            if (f.arrived()) {
                enter(Phase.RESTORE);
                return WAIT;
            }
            stall.reset();
            walking = true;
            return new GoTo(plan.resume());
        }
        if (f.arrived()) {
            walking = false;
            enter(Phase.RESTORE);
            return new StopWalking();
        }
        if (!f.paused() && stall.tick(1, f.distance())) return stop(RestockReason.NO_PATH_BACK, f);
        return WAIT;
    }

    /** The slot selected when the unpacking began, selected again; not worth a stop if it cannot be. */
    private Action restore(Facts f) {
        if (f.selected() == plan.originalSlot()) return end(f);
        if (!f.paused() && ++waitTicks > limits.stillWaitTicks()) return end(f);
        if (!canSelect(f)) return WAIT;
        return selectSlot(plan.originalSlot());
    }

    private Action end(Facts f) {
        if (draining != null) return stop(draining, f);
        if (soft != null) return stop(soft, f);
        enter(Phase.DONE);
        return new Finish();
    }

    private Action aim(Facts f, Aim.Rotation r) {
        if (++waitTicks > limits.stillWaitTicks()) return stop(RestockReason.UNPACK_BLOCKED, f);
        return new AimAt(r);
    }

    private Action waitGate(Facts f) {
        if (!f.paused() && ++waitTicks > limits.stillWaitTicks()) return stop(RestockReason.UNPACK_BLOCKED, f);
        return WAIT;
    }

    private Action selectSlot(int slot) {
        selectedAt = tick;
        return new Select(slot);
    }

    private boolean canAct(Facts f) {
        return !f.paused() && f.still() && f.screenFree();
    }

    private boolean canSelect(Facts f) {
        return canAct(f) && f.slotChangeAllowed() && !digging;
    }

    /** The box stands on the cell restock set it on. */
    private boolean holds(Facts f) {
        return cell != null && f.standing().equals(Optional.of(cell));
    }

    private Action stop(RestockReason reason, Facts f) {
        boolean startNeverLeft = heldTicks == 0 && (f.click() == Click.REFUSED || f.click() == Click.WITHHELD);
        Optional<DigAbort> abort = digging && !startNeverLeft ? Optional.of(new DigAbort(cell, digSide)) : Optional.empty();
        OptionalInt select = abort.isEmpty() && f.selected() != plan.originalSlot() && !f.paused() && f.still()
            && f.screenFree() && f.slotChangeAllowed() ? OptionalInt.of(plan.originalSlot()) : OptionalInt.empty();
        digging = false;
        enter(Phase.STOPPED);
        return new Stopped(reason, abort, select);
    }

    private void enter(Phase next) {
        phase = next;
        phaseTicks = 0;
        liveTicks = 0;
        waitTicks = 0;
        heldTicks = 0;
        entered = false;
    }
}
