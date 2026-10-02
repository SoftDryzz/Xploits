package com.xploits.restock.core;

import com.xploits.printer.core.Pos;
import com.xploits.travel.core.StallWatch;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * One restock trip (restock spec §3 "The trip"), from the first walking command to the moment the player stands again
 * where the trip started. One step per client tick: the adapter measures the {@link Facts}, this core answers one
 * {@link Action} and the adapter executes it. The printer is switched off before the trip is built (the session's
 * PAUSE_PRINTER), so a paused trip first waits {@code printerSettleTicks} for its last queued packets. Then it walks to
 * the stand spot (a stash-keeper entry is approached first and its spot chosen once arrived), looks at the container
 * and clicks it only while still, waits for its screen, takes while still (after the slots arrive, or after the content
 * wait for an empty-looking screen), closes while still, and walks back. A stale or unusable source asks the adapter
 * for the next one ({@link #retarget}, or {@link #giveUp} to go back empty-handed). The player's movement keys stop the
 * trip in any phase. A guard pause blocks every click and close and does not count towards a stall.
 */
public final class RestockTrip {
    public enum Phase { PAUSING, APPROACH, TRAVEL, OPEN, WAIT_SCREEN, TAKE, CHOOSING, STOPPING, RETURN, DONE, STOPPED }

    /** OPEN: the aim at the container from here — none possible, requested but not yet held, held with the ray on it. */
    public enum Aiming { NONE, WANTED, HELD }

    /** The tick after a {@link ClickContainer}: whether its packet left. */
    public enum Click { NONE, SENT, REFUSED }

    /** Where to fetch {@code material} from and where to come back to; {@code stand} empty: approach first. */
    public record Plan(String material, Pos container, Optional<Pos> stand, Pos resume, boolean printerPaused) {
        public Plan {
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(container, "container");
            Objects.requireNonNull(stand, "stand");
            Objects.requireNonNull(resume, "resume");
            if (material.isBlank()) throw new IllegalArgumentException("a trip fetches a material");
        }
    }

    /**
     * One tick as the adapter measured it.
     *
     * @param movementKeys the player holds forward, back, left, right, jump or sneak
     * @param paused       a guard pause this tick (lag, eating, another module rotating)
     * @param still        still as the server knows it, on ground, not sneaking, sprinting or using an item, no goal
     * @param screenFree   no screen open and nothing on the cursor
     * @param distance     to the current goal, in blocks (walking phases)
     * @param arrived      at the current goal (APPROACH: within the approach radius with the container's chunk loaded)
     * @param spot         APPROACH, once arrived: where to stand to open the container, if anywhere
     * @param aiming       OPEN: the aim at the container from here
     * @param click        the tick after a click: whether its packet left
     * @param ourScreen    the container screen restock opened is the current one
     * @param contentSeen  that screen shows at least one item
     * @param take         TAKE: what {@link TakePlan#next} says now
     * @param carried      how many of the trip's material the player carries now
     * @param cursorEmpty  the cursor holds nothing; an item on it would be dropped by a close
     */
    public record Facts(boolean movementKeys, boolean paused, boolean still, boolean screenFree, double distance,
                        boolean arrived, Optional<Pos> spot, Aiming aiming, Click click, boolean ourScreen,
                        boolean contentSeen, TakePlan.Step take, int carried, boolean cursorEmpty) {
        public Facts {
            Objects.requireNonNull(spot, "spot");
            Objects.requireNonNull(aiming, "aiming");
            Objects.requireNonNull(click, "click");
            Objects.requireNonNull(take, "take");
        }
    }

    public sealed interface Action permits Wait, GoTo, GoToward, StopWalking, Aim, ClickContainer, Take, Close,
        NeedSource, Finish, Stopped {
    }

    /** Nothing to send this tick. */
    public record Wait() implements Action {
    }

    /** Walk to stand exactly on {@code feet}. */
    public record GoTo(Pos feet) implements Action {
    }

    /** Walk towards a column, for a container not loaded yet. */
    public record GoToward(int x, int z) implements Action {
    }

    /** Cancel the walking goal. */
    public record StopWalking() implements Action {
    }

    /** Keep requesting the aim at the container (the adapter computed it into the facts). */
    public record Aim(Pos container) implements Action {
    }

    /** Click the container with the held aim. */
    public record ClickContainer(Pos container) implements Action {
    }

    /** One QUICK_MOVE of this container slot. */
    public record Take(int slot) implements Action {
    }

    /** Close the screen restock opened. */
    public record Close() implements Action {
    }

    /**
     * The source failed — {@code stale}: it does not hold the material; otherwise it could not be reached or opened. The
     * adapter stops walking, notes it, and answers with {@link #retarget} or {@link #giveUp} before the next step.
     */
    public record NeedSource(String material, Pos failed, boolean stale) implements Action {
    }

    /** Back where the trip started: stop walking; give the printer back if it was paused; {@code took}: any carried. */
    public record Finish(boolean resumePrinter, boolean took) implements Action {
    }

    /** The trip ends with a reason; {@code closeScreen}: close the screen restock opened (only while still). */
    public record Stopped(RestockReason reason, boolean closeScreen) implements Action {
    }

    private enum After { RETURN, STALE, UNUSABLE, NOTHING_FITS }

    private static final Wait WAIT = new Wait();

    private final RestockLimits limits;
    private final String material;
    private final Pos resume;
    private final boolean printerPaused;
    private final StallWatch stall;
    private Pos container;
    private Optional<Pos> stand;
    private Phase phase;
    private boolean entered;
    private int phaseTicks;
    private int liveTicks;
    private int goal;
    private int clicks;
    private After after;

    public RestockTrip(Plan plan, RestockLimits limits) {
        this.limits = limits;
        this.material = plan.material();
        this.resume = plan.resume();
        this.printerPaused = plan.printerPaused();
        this.container = plan.container();
        this.stand = plan.stand();
        this.stall = new StallWatch(limits.walkStallTicks(), limits.walkProgress());
        enter(printerPaused ? Phase.PAUSING : walkPhase());
    }

    public Phase phase() {
        return phase;
    }

    public String material() {
        return material;
    }

    public Pos container() {
        return container;
    }

    public Optional<Pos> stand() {
        return stand;
    }

    public Pos resume() {
        return resume;
    }

    public boolean printerPaused() {
        return printerPaused;
    }

    /** The phases in which restock aims, clicks or takes: the only ones another module's actions can disturb. */
    public boolean clicking() {
        return phase == Phase.OPEN || phase == Phase.WAIT_SCREEN || phase == Phase.TAKE;
    }

    /** The feet position walked to now: the stand spot in TRAVEL, the start in RETURN; empty otherwise. */
    public Optional<Pos> goal() {
        if (phase == Phase.TRAVEL) return stand;
        if (phase == Phase.RETURN) return Optional.of(resume);
        return Optional.empty();
    }

    /**
     * Whether a due trip may leave now (spec §3 "Before leaving: no screen open, cursor empty"; ruling R32): also no
     * movement key held, not sneaking and on the ground. Otherwise it waits, with no stop: the player who walks along
     * the build, sneaks at an edge or jumps would stop the trip at its first step ({@code PLAYER_MOVED}) with the
     * printer already switched off, and a trip started in the air would take an air block as the way back.
     *
     * @param screenFree   no screen open and nothing on the cursor
     * @param movementKeys the player holds forward, back, left, right, jump or sneak
     */
    public static boolean mayLeave(boolean screenFree, boolean movementKeys, boolean sneaking, boolean onGround) {
        return screenFree && !movementKeys && !sneaking && onGround;
    }

    public Action step(Facts f) {
        if (phase == Phase.DONE || phase == Phase.STOPPED) throw new IllegalStateException("the trip is over");
        phaseTicks++;
        if (f.movementKeys()) return stop(RestockReason.PLAYER_MOVED, f);
        return switch (phase) {
            case PAUSING -> pausing(f);
            case APPROACH -> approach(f);
            case TRAVEL -> travel(f);
            case OPEN -> open(f);
            case WAIT_SCREEN -> waitScreen(f);
            case TAKE -> take(f);
            case CHOOSING -> new NeedSource(material, container, after == After.STALE);
            case STOPPING -> stop(RestockReason.NOTHING_FITS, f);
            case RETURN -> back(f);
            case DONE, STOPPED -> throw new IllegalStateException("the trip is over");
        };
    }

    /** The next source after a {@link NeedSource}; {@code stand} empty: approach it first. */
    public void retarget(Pos container, Optional<Pos> stand) {
        if (phase != Phase.CHOOSING) throw new IllegalStateException("no source is being chosen");
        this.container = Objects.requireNonNull(container, "container");
        this.stand = Objects.requireNonNull(stand, "stand");
        clicks = 0;
        enter(walkPhase());
    }

    /** No other source: back to where the trip started without the material. */
    public void giveUp() {
        if (phase != Phase.CHOOSING) throw new IllegalStateException("no source is being chosen");
        enter(Phase.RETURN);
    }

    private Phase walkPhase() {
        return stand.isPresent() ? Phase.TRAVEL : Phase.APPROACH;
    }

    private Action pausing(Facts f) {
        if (phaseTicks <= limits.printerSettleTicks()) return WAIT;
        enter(walkPhase());
        return phase == Phase.TRAVEL ? travel(f) : approach(f);
    }

    private Action approach(Facts f) {
        return walk(f, RestockReason.NO_PATH, () -> new GoToward(container.x(), container.z()), () -> {
            if (f.spot().isEmpty()) return needSource(After.UNUSABLE);
            stand = f.spot();
            enter(Phase.TRAVEL);
            return new StopWalking();
        });
    }

    private Action travel(Facts f) {
        return walk(f, RestockReason.NO_PATH, () -> new GoTo(stand.orElseThrow()), () -> {
            enter(Phase.OPEN);
            return new StopWalking();
        });
    }

    private Action back(Facts f) {
        return walk(f, RestockReason.NO_PATH_BACK, () -> new GoTo(resume), () -> {
            enter(Phase.DONE);
            return new Finish(printerPaused, f.carried() > 0);
        });
    }

    /** A walking phase: the command on its first tick, then arrival, then the stall rule (a paused tick never counts). */
    private Action walk(Facts f, RestockReason stalled, Supplier<Action> command, Supplier<Action> arrival) {
        if (!entered) {
            entered = true;
            goal++;
            stall.reset();
            return command.get();
        }
        if (f.arrived()) return arrival.get();
        if (!f.paused() && stall.tick(goal, f.distance())) return stop(stalled, f);
        return WAIT;
    }

    private Action open(Facts f) {
        if (f.paused()) return WAIT;
        if (!f.still() || !f.screenFree()) {
            return ++liveTicks > limits.openTimeoutTicks() ? needSource(After.UNUSABLE) : WAIT;
        }
        return switch (f.aiming()) {
            case NONE -> needSource(After.UNUSABLE);
            case WANTED -> new Aim(container);
            case HELD -> {
                enter(Phase.WAIT_SCREEN);
                yield new ClickContainer(container);
            }
        };
    }

    private Action waitScreen(Facts f) {
        if (phaseTicks == 1 && f.click() == Click.REFUSED) return stop(RestockReason.CONTAINER_REFUSED, f);
        if (f.ourScreen()) {
            enter(Phase.TAKE);
            return WAIT;
        }
        if (!f.paused() && ++liveTicks > limits.openTimeoutTicks()) return needSource(After.UNUSABLE);
        return WAIT;
    }

    private Action take(Facts f) {
        if (!f.ourScreen()) return stop(RestockReason.CONTAINER_CLOSED, f);
        if (f.paused() || !f.still() || !f.cursorEmpty()) return WAIT;
        if (!f.contentSeen() && ++liveTicks <= limits.contentWaitTicks()) return WAIT;
        TakePlan.Step s = clicks >= limits.maxTakeClicks() ? new TakePlan.NothingFits() : f.take();
        if (s instanceof TakePlan.Click c) {
            clicks++;
            return new Take(c.slot());
        }
        boolean took = f.carried() > 0;
        if (s instanceof TakePlan.NothingFits) after = took ? After.RETURN : After.NOTHING_FITS;
        else after = took || ((TakePlan.Done) s).materialThere() ? After.RETURN : After.STALE;
        enter(switch (after) {
            case RETURN -> Phase.RETURN;
            case STALE, UNUSABLE -> Phase.CHOOSING;
            case NOTHING_FITS -> Phase.STOPPING;
        });
        return new Close();
    }

    private Action needSource(After a) {
        after = a;
        enter(Phase.CHOOSING);
        return new NeedSource(material, container, a == After.STALE);
    }

    private Action stop(RestockReason reason, Facts f) {
        enter(Phase.STOPPED);
        return new Stopped(reason, f.ourScreen() && f.still() && f.cursorEmpty() && !f.paused());
    }

    private void enter(Phase p) {
        phase = p;
        entered = false;
        phaseTicks = 0;
        liveTicks = 0;
    }
}
