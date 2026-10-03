package com.xploits.restock;

import com.xploits.printer.core.Aim;
import com.xploits.printer.core.Point;
import com.xploits.printer.core.Pos;
import com.xploits.printer.core.PrinterLimits;
import com.xploits.restock.core.ContainerAim;
import com.xploits.restock.core.ContainerSpot;
import com.xploits.restock.core.LateScreen;
import com.xploits.restock.core.RestockLimits;
import com.xploits.restock.core.RestockMessages;
import com.xploits.restock.core.RestockReason;
import com.xploits.restock.core.RestockTrip;
import com.xploits.restock.core.Source;
import com.xploits.restock.core.TakePlan;
import com.xploits.restock.core.UnpackLimits;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One trip in the game (restock spec §3 "The trip"): it measures {@link RestockTrip.Facts} once a tick and executes the
 * core's action. The aim is requested from {@code SendMovementPacketsEvent.Pre} at priority −101 only while Meteor's
 * rotation queue is empty (spike S6), so it rides the tick's own movement packet; the click comes the next tick, only if
 * the server holds that exact rotation and the raycast with it still sees the container. The container screen is adopted
 * by its {@code syncId} after restock's click and is the only one clicked or closed; a container screen that answers
 * restock's click only after the trip stopped waiting for it is closed, by its own {@code syncId}, before anything else
 * ({@link LateScreen}: rulings R12, R28, deferred L79). A take click that moves a shulker box goes in the session's
 * ledger only once its packet left, and the session settles each visit's box moves as its screen closes, after the
 * trip waited for the server's answer (ruling R70). Client thread.
 */
final class TripDriver {
    sealed interface Result permits Running, Finished, Ended {
    }

    record Running() implements Result {
    }

    record Finished() implements Result {
    }

    record Ended(RestockReason reason, String detail) implements Result {
    }

    private static final Running RUNNING = new Running();

    private final RestockSession session;
    private final MinecraftClient mc;
    private final Mover mover;
    private final RestockTrip trip;
    private final RestockLimits limits;
    private final Map<String, Integer> carriedAtStart;
    /** The take from a shulker box set down at the build (phase B). */
    private final boolean inner;
    private Aim.Rotation wanted;
    private BlockHitResult hit;
    private RestockTrip.Click click = RestockTrip.Click.NONE;
    private int syncId = -1;
    /**
     * The extra distance, beyond the player's block interaction range, up to which the server keeps a container's screen
     * open ({@code Inventory.canPlayerUse}: {@code canInteractWithBlockAt(pos, 4.0)}, VERIFIED with javap).
     */
    private static final double SCREEN_KEPT_RANGE = 4.0;

    /** The answer to restock's click that comes after the open gave up waiting for it (rulings R12, R28; L79). */
    private final LateScreen late;
    /** The container block restock's last click that left went to. */
    private Pos clicked;
    /** OPEN: live ticks the aim was only wanted, never held (bounded in {@link #aim}). */
    private int wantedTicks;
    /** A container click, take or close went out in this session tick: its one container action is spent. */
    private boolean actedThisTick;
    /** The last tick the trip ran was a guard pause, which blocks every click and close. */
    private boolean pausedLastTick;
    /** M16, inner mode: live ticks the take waited in TAKE for stillness or an empty cursor. */
    private int innerWaitTicks;
    /** Whether a box could be carried, as the last tick in TAKE read it (ruling R54; next() uses it, Minor 7). */
    private boolean takeCarryRoom;
    private volatile boolean actingAtRequest;

    TripDriver(RestockSession session, MinecraftClient mc, Mover mover, RestockTrip trip, RestockLimits limits,
               Map<String, Integer> carriedAtStart) {
        this(session, mc, mover, trip, limits, carriedAtStart, false);
    }

    /**
     * {@code inner}: the take from a shulker box set down at the build (phase B) — it never carries a box or gives one
     * back, keeps one slot free for the box, takes against the loose need only, a failed source goes straight back, and
     * its end is only reported (nothing borrowed, nothing noted as a source, no trip counted). {@code carriedAtStart}:
     * {@link StateFacts#withShulkers} at the start; the trip's {@code carried} is what was gained since.
     */
    TripDriver(RestockSession session, MinecraftClient mc, Mover mover, RestockTrip trip, RestockLimits limits,
               Map<String, Integer> carriedAtStart, boolean inner) {
        this.session = session;
        this.mc = mc;
        this.mover = mover;
        this.trip = trip;
        this.limits = limits;
        this.carriedAtStart = Map.copyOf(carriedAtStart);
        this.inner = inner;
        this.late = new LateScreen(limits.openTimeoutTicks());
    }

    RestockTrip.Phase phase() {
        return trip.phase();
    }

    String material() {
        return trip.material();
    }

    boolean clicking() {
        return trip.clicking();
    }

    /** Meteor's rotation queue held someone else's request when restock wanted to aim (the combat yield's "acting"). */
    boolean actingAtRequest() {
        return actingAtRequest;
    }

    /** At the start of every session tick, before the guards: no container action yet in this one. */
    void newTick() {
        actedThisTick = false;
    }

    /** A container click, take or close went out in this session tick. */
    boolean actedThisTick() {
        return actedThisTick;
    }

    /** Owner ruling R42: no more is taken; the screen closes as soon as a close may go, and the trip goes back. */
    void hurry() {
        trip.hurry();
    }

    /**
     * This session tick's guard pause or yield, for the guards of a close at a stop (Minor 2, ruling R64): the unpack
     * notes it every tick, a dig under way included — when {@link #lateTick} does not run — and at its halt, so a stop
     * never closes this trip's screen in a lag pause or a rotation yield.
     */
    void notePaused(boolean paused) {
        pausedLastTick = paused;
    }

    /**
     * After this trip ended (the take from a box at the build), until the unpack ends: its own screen, if its take was
     * cut short with the screen open (M16), else a container screen that answers its click late (ruling R12,
     * {@link LateScreen}), is closed as soon as a close may go. True when one was closed now. The unpack driver never
     * calls it while a dig is under way (M2).
     */
    boolean lateTick(boolean paused) {
        notePaused(paused);
        ClientPlayerEntity p = mc.player;
        if (p == null) return false;
        if (ours(p)) {
            closeOwnScreen();
            return actedThisTick;
        }
        return closeLateScreen(p, paused);
    }

    /**
     * M3, at a stop once this trip has ended (the take from a box at the build): its own screen if it is still open,
     * else the late answer to its click if that is the screen shown — under {@link #closeOwnScreen}'s guards (standing
     * still, an empty cursor, no pause last tick, no container action yet this tick). Never while leaving (the caller's
     * rule).
     */
    void closeOwnOrLateScreen() {
        ClientPlayerEntity p = mc.player;
        if (p == null || actedThisTick || pausedLastTick) return;
        if (ours(p)) {
            closeOwnScreen();
            return;
        }
        if (!(mc.currentScreen instanceof HandledScreen<?> shown)) return;
        if (shown.getScreenHandler() != p.currentScreenHandler) return;
        closeLateScreen(p, false);
    }

    Result tick(boolean paused) {
        pausedLastTick = paused;
        ClientPlayerEntity p = mc.player;
        // At most one container action a tick: closing a late answer is this tick's action.
        if (closeLateScreen(p, paused)) return RUNNING;
        RestockTrip.Facts f = facts(p, paused);
        // M16: the take from a box set down at the build waits in TAKE for stillness (and an empty cursor) at most
        // stillWaitTicks live ticks, like every wait of an unpack; then it ends softly with UNPACK_BLOCKED: the box is
        // still broken and picked up, and its screen, still adopted, is closed by lateTick once a close may go.
        if (inner && trip.phase() == RestockTrip.Phase.TAKE && !paused && (!f.still() || !f.cursorEmpty())
            && ++innerWaitTicks > UnpackLimits.DEFAULTS.stillWaitTicks()) {
            return new Ended(RestockReason.UNPACK_BLOCKED, "");
        }
        click = RestockTrip.Click.NONE;
        return execute(trip.step(f), p);
    }

    /** From {@code SendMovementPacketsEvent.Pre} at {@code HIGH}: request the aim only when nobody else is rotating. */
    void requestRotation() {
        List<?> queue = RotationQueue.read();
        actingAtRequest = queue != null && !queue.isEmpty();
        Aim.Rotation r = wanted;
        if (r == null || queue == null || actingAtRequest || trip.phase() != RestockTrip.Phase.OPEN) return;
        Rotations.rotate(r.yaw(), r.pitch(), PrinterLimits.DEFAULTS.rotationPriority(), null);
    }

    /** The session ends with this trip under way: the walk stops. Only Baritone's cancel is sent (this may run between ticks). */
    void abort() {
        mover.cancel();
        wanted = null;
    }

    /**
     * Deferred L59: the session ends from outside the trip (a guard's stop, the module turned off, a fault) while restock's
     * own container screen is open and shown. It is closed only as any close would be: the player stands still
     * ({@link #still}), the cursor is empty, the last tick was no guard pause, and no container action went out in this
     * tick. Otherwise it stays open for the player, as before.
     */
    void closeOwnScreen() {
        ClientPlayerEntity p = mc.player;
        if (p == null || actedThisTick || pausedLastTick || !ours(p)) return;
        if (!(mc.currentScreen instanceof HandledScreen<?> shown)) return;
        if (shown.getScreenHandler() != p.currentScreenHandler) return;
        if (!p.currentScreenHandler.getCursorStack().isEmpty() || !still(p)) return;
        ContainerScreen.close(p, syncId);
        syncId = -1;
        actedThisTick = true;
    }

    /**
     * Ruling R12: once restock's click is sent, a container screen that opens after the trip stopped waiting for it (its
     * open timed out and the trip moved on) is the late answer to that click; it is closed as soon as the player stands
     * still ({@link #still}), the cursor is empty and no guard pauses, and nothing else happens in that tick. Ruling R28:
     * only a screen that opens within {@code openTimeoutTicks} live ticks since the open gave up, while the player is in
     * the range at which the server keeps that container's screen open, is taken for it. Deferred L79: such a screen is
     * remembered by its {@code syncId} and closed once a close is allowed even after that window ran out (the player was
     * walking to the next source); a screen with another {@code syncId} is never closed ({@link LateScreen}). True when
     * it was closed now.
     */
    private boolean closeLateScreen(ClientPlayerEntity p, boolean paused) {
        if (trip.phase() == RestockTrip.Phase.WAIT_SCREEN) return false;
        ScreenHandler h = p.currentScreenHandler;
        boolean container = h != p.playerScreenHandler && ContainerScreen.containerSlots(h) > 0 && h.syncId != syncId;
        boolean inRange = clicked != null && p.canInteractWithBlockAt(WorldRay.block(clicked), SCREEN_KEPT_RANGE);
        boolean mayClose = !paused && h.getCursorStack().isEmpty() && still(p);
        int close = late.tick(container ? h.syncId : LateScreen.NONE, inRange, paused, mayClose);
        if (close == LateScreen.NONE) return false;
        ContainerScreen.close(p, close);
        actedThisTick = true;
        return true;
    }

    /**
     * Standing still as every click, close, place, dig START and slot change needs it (Global Constraints, spike S5):
     * the last input the server got had no movement, jump or sneak and sprint is off, no one else's action in the last
     * tick or the open Grim tick, on ground, not sprinting, sneaking or using an item, and no walking goal.
     */
    static boolean still(ClientPlayerEntity p, Mover mover) {
        PacketWatch watch = PacketWatch.get();
        return watch.stillAsServerKnows() && !watch.foreignActionLastTick() && !watch.foreignActionThisGrimTick()
            && p.isOnGround() && !p.isSprinting() && !p.isSneaking() && !p.isUsingItem() && mover.idle();
    }

    private boolean still(ClientPlayerEntity p) {
        return still(p, mover);
    }

    /** No screen open and nothing on the cursor. */
    static boolean screenFree(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        return mc.currentScreen == null && p.currentScreenHandler == p.playerScreenHandler
            && p.currentScreenHandler.getCursorStack().isEmpty();
    }

    /**
     * Ruling R27: the block at {@code block} is still one restock marks ({@link Marks#container}: chest, trapped chest,
     * copper chest, barrel, shulker box). A mark or a stash-keeper entry can outlive its container, and a right-click
     * on whatever replaced it would use the held item (place a block, flip a lever) or set off a bed or a respawn
     * anchor.
     */
    private boolean containerAt(Pos block) {
        ClientWorld w = mc.world;
        return w != null && Marks.container(w.getBlockState(WorldRay.block(block)).getBlock());
    }

    /**
     * The blocks a click may go to, in order (deferred L25): the trip's container, and for a double chest its other half
     * too — a mark keeps the lesser half, and the player may have marked it from beside the greater one. None when the
     * trip's container is no longer a container (ruling R27).
     */
    private List<Pos> clickable() {
        Pos c = trip.container();
        if (!containerAt(c)) return List.of();
        BlockPos b = WorldRay.block(c);
        BlockPos other = Marks.otherHalf(b, mc.world.getBlockState(b));
        return other == null ? List.of(c) : List.of(c, WorldRay.pos(other));
    }

    private RestockTrip.Facts facts(ClientPlayerEntity p, boolean paused) {
        boolean still = still(p);
        boolean cursorEmpty = p.currentScreenHandler.getCursorStack().isEmpty();
        boolean screenFree = mc.currentScreen == null && p.currentScreenHandler == p.playerScreenHandler && cursorEmpty;
        double distance = Double.NaN;
        boolean arrived = false;
        Optional<Pos> spot = Optional.empty();
        RestockTrip.Aiming aiming = RestockTrip.Aiming.NONE;
        if (trip.phase() != RestockTrip.Phase.OPEN) wantedTicks = 0;
        switch (trip.phase()) {
            case TRAVEL, RETURN -> {
                BlockPos goal = WorldRay.block(trip.goal().orElseThrow());
                distance = Vec3d.ofBottomCenter(goal).distanceTo(p.getEntityPos());
                arrived = p.getBlockPos().equals(goal) && p.isOnGround();
            }
            case APPROACH -> {
                Pos c = trip.container();
                distance = Math.hypot(c.x() + 0.5 - p.getX(), c.z() + 0.5 - p.getZ());
                arrived = distance <= limits.approachRadius() && mc.world.isChunkLoaded(c.x() >> 4, c.z() >> 4);
                if (arrived) {
                    spot = ContainerSpot.choose(c, new Point(p.getX(), p.getY(), p.getZ()), limits.reach(),
                        limits.hitMargin(), spotWorld());
                }
            }
            case OPEN -> aiming = aim(p, !paused && still && screenFree);
            case WAIT_SCREEN -> adopt(p);
            default -> {
            }
        }
        boolean ours = ours(p);
        boolean seen = ours && ContainerScreen.contentSeen(p.currentScreenHandler);
        // Ruling R71: restock's screen brought the inventory from the server; the last visit's kept carries are
        // checked against it before anything is taken or given back here.
        if (ours && !inner) session.recheckLastVisit(p);
        // Ruling R54: a container trip carries a box only while one can be carried (a free hotbar slot and one more).
        // Otherwise its slots are phase A's — a block only inside boxes is not "there" — so the trip tries the next
        // source (Task B7's session) instead of stopping the session with NOTHING_FITS. The inner take never carries.
        boolean carryRoom = !inner && session.carryOnTrips() && ContainerScreen.carryRoom(p.getInventory()) > 0;
        if (trip.phase() == RestockTrip.Phase.TAKE) takeCarryRoom = carryRoom;
        // Ruling R70: once a box this visit carried came back (the server refused its click), carry nothing more.
        boolean carry = carryRoom && !session.carriedBoxCameBack(p);
        TakePlan.Step take = ours
            ? TakePlan.next(ContainerScreen.slots(p, inner ? 1 : session.takeReserve(), carry, session.buildItems()),
                inner ? List.of() : session.returning(p, trip.container()), trip.material(),
                inner ? session.innerNeed(trip.material()) : session.orderedNeed(), carry)
            : new TakePlan.Done(false);
        String m = trip.material();
        int carried = Math.max(0, StateFacts.withShulkers(p.getInventory()).getOrDefault(m, 0)
            - carriedAtStart.getOrDefault(m, 0));
        return new RestockTrip.Facts(movementKeys(mc.options), paused, still, screenFree, distance, arrived, spot, aiming,
            click, ours, seen, take, carried, cursorEmpty);
    }

    /**
     * The aim at the container from here. {@code live}: a tick on which the core reads the aim (unpaused, still, screen
     * free). The core waits on WANTED without a limit, so a rotation that is never held (Meteor's queue always busy, a
     * request that never goes out, a server that never echoes it) would hold the trip, the printer paused, for ever: after
     * {@code openTimeoutTicks} live ticks it is NONE, and the core makes the container unusable and tries the next. A
     * block that is no longer a container restock marks is NONE at once (ruling R27).
     */
    private RestockTrip.Aiming aim(ClientPlayerEntity p, boolean live) {
        Vec3d e = p.getEyePos();
        Point eye = new Point(e.x, e.y, e.z);
        Optional<ContainerAim.Pick> a = ContainerAim.firstOf(clickable(), eye, p.getYaw(), limits.reach(),
            limits.hitMargin(), (block, side, r) -> WorldRay.ray(mc, block, side, r, limits.reach()) != null);
        if (a.isEmpty()) {
            wanted = null;
            hit = null;
            return RestockTrip.Aiming.NONE;
        }
        Aim.Rotation r = a.get().aiming().rotation();
        boolean held = r.equals(wanted) && PacketWatch.get().aimHeld(r);
        wanted = r;
        // The block the rotation looks at: the container, or the other half of a double chest (deferred L25).
        hit = held ? WorldRay.ray(mc, a.get().block(), a.get().aiming().side(), r, limits.reach()) : null;
        if (hit != null) return RestockTrip.Aiming.HELD;
        if (live && ++wantedTicks > limits.openTimeoutTicks()) {
            wanted = null;
            return RestockTrip.Aiming.NONE;
        }
        return RestockTrip.Aiming.WANTED;
    }

    /** After restock's click, the first container screen that opens is restock's. */
    private void adopt(ClientPlayerEntity p) {
        ScreenHandler h = p.currentScreenHandler;
        if (syncId < 0 && h != p.playerScreenHandler && ContainerScreen.containerSlots(h) > 0) {
            syncId = h.syncId;
            late.adopted(syncId);
        }
    }

    private boolean ours(ClientPlayerEntity p) {
        ScreenHandler h = p.currentScreenHandler;
        return syncId >= 0 && h.syncId == syncId && ContainerScreen.containerSlots(h) > 0;
    }

    /**
     * The player's own movement keys (Baritone 1.17.0 standalone drives {@code player.input}, not the key bindings): they
     * stop a trip under way, and a due trip waits while one is held (ruling R32).
     */
    static boolean movementKeys(GameOptions o) {
        return o.forwardKey.isPressed() || o.backKey.isPressed() || o.leftKey.isPressed() || o.rightKey.isPressed()
            || o.jumpKey.isPressed() || o.sneakKey.isPressed();
    }

    private ContainerSpot.World spotWorld() {
        ClientWorld w = mc.world;
        return new ContainerSpot.World() {
            @Override
            public boolean standable(Pos feet) {
                BlockPos f = WorldRay.block(feet);
                BlockPos head = f.up();
                BlockPos below = f.down();
                if (!w.isChunkLoaded(f.getX() >> 4, f.getZ() >> 4)) return false;
                return w.getBlockState(f).getCollisionShape(w, f).isEmpty() && w.getFluidState(f).isEmpty()
                    && w.getBlockState(head).getCollisionShape(w, head).isEmpty() && w.getFluidState(head).isEmpty()
                    && w.getBlockState(below).isSideSolidFullSquare(w, below, Direction.UP);
            }

            @Override
            public boolean open(Pos block) {
                BlockPos b = WorldRay.block(block);
                return w.getBlockState(b).getCollisionShape(w, b).isEmpty();
            }
        };
    }

    private Result execute(RestockTrip.Action action, ClientPlayerEntity p) {
        switch (action) {
            case RestockTrip.Wait w -> {
            }
            case RestockTrip.GoTo g -> {
                if (!mover.goTo(WorldRay.block(g.feet()))) return new Ended(RestockReason.BARITONE_NOT_LISTENING, "");
            }
            case RestockTrip.GoToward g -> {
                if (!mover.goToward(g.x(), g.z())) return new Ended(RestockReason.BARITONE_NOT_LISTENING, "");
            }
            case RestockTrip.StopWalking s -> mover.cancel();
            case RestockTrip.Aim a -> {
                // `wanted` holds the rotation; requestRotation() asks Meteor for it before the movement packet.
            }
            case RestockTrip.ClickContainer c -> {
                actedThisTick = true;
                clickContainer(p);
            }
            case RestockTrip.Take t -> {
                actedThisTick = true;
                // Ruling R70: what the click moves is read before it (the client applies its own prediction at once),
                // and goes in the ledger only once the click's packet left.
                Optional<RestockSession.BoxMove> box = inner ? Optional.empty()
                    : session.boxMove(p, t.slot(), trip.container());
                long sent = PacketWatch.get().slotClicksSent();
                ContainerScreen.quickMove(mc, syncId, t.slot());
                if (box.isPresent()) {
                    if (PacketWatch.get().slotClicksSent() != sent + 1) {
                        // Cancelled on its way out: the client already shows the box moved, the server never saw it,
                        // so the screen no longer shows the truth. Nothing is noted, and the trip ends saying so.
                        mover.cancel();
                        wanted = null;
                        return new Ended(RestockReason.CLICK_NOT_SENT, "");
                    }
                    session.moved(box.get());
                }
            }
            case RestockTrip.Close c -> {
                actedThisTick = true;
                if (!inner) {
                    session.saw(trip.container(), ContainerScreen.loose(p.currentScreenHandler),
                        ContainerScreen.nested(p.currentScreenHandler));
                    // Ruling R70: the server answered every box click of this visit (the trip waited for it).
                    session.settleVisit();
                }
                ContainerScreen.close(p, syncId);
                syncId = -1;
            }
            case RestockTrip.NeedSource n -> {
                mover.cancel();
                syncId = -1;
                if (inner) {
                    trip.giveUp();
                } else {
                    // Review Minor 7: a stale container is judged on the carry room its take read; a failure before
                    // any take reads it now.
                    boolean carry = n.failure() == RestockTrip.Failure.UNUSABLE
                        ? session.carryOnTrips() && ContainerScreen.carryRoom(p.getInventory()) > 0 : takeCarryRoom;
                    Optional<Source> next = session.next(n.material(), n.failed(), n.failure(), trip.resume(), carry);
                    if (next.isPresent()) trip.retarget(next.get().container(), next.get().stand());
                    else trip.giveUp();
                }
            }
            case RestockTrip.Finish f -> {
                mover.cancel();
                if (!inner) session.finished(f.resumePrinter(), f.took(), trip.material(), carriedAtStart);
                return new Finished();
            }
            case RestockTrip.Stopped st -> {
                mover.cancel();
                if (st.closeScreen()) {
                    actedThisTick = true;
                    ContainerScreen.close(p, syncId);
                }
                syncId = -1;
                wanted = null;
                return new Ended(st.reason(),
                    st.reason() == RestockReason.NOTHING_FITS ? RestockMessages.itemName(trip.material()) : "");
            }
        }
        return RUNNING;
    }

    private void clickContainer(ClientPlayerEntity p) {
        BlockHitResult h = hit;
        wanted = null;
        hit = null;
        // Ruling R27, checked again right before the packet: never a right-click on anything but a container. Nothing
        // is sent, and the container is unusable as when the aim finds none (deferred L80), never CONTAINER_REFUSED.
        if (h == null || !containerAt(WorldRay.pos(h.getBlockPos()))) {
            click = RestockTrip.Click.WITHHELD;
            return;
        }
        long before = PacketWatch.get().oursSent();
        ActionResult[] result = new ActionResult[1];
        // interactBlock fires Meteor's InteractBlockEvent, so stash-keeper (when on) indexes what restock opens.
        PacketWatch.get().asOurs(() -> result[0] = mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, h));
        click = PacketWatch.get().oursSent() == before + 1 ? RestockTrip.Click.SENT : RestockTrip.Click.REFUSED;
        // Only a click that left can be answered with a screen (ruling R12).
        if (click == RestockTrip.Click.SENT) {
            late.clicked();
            clicked = WorldRay.pos(h.getBlockPos());
        }
        // As vanilla's right click (MinecraftClient.doItemUse): a success the client swings for swings the hand, in the
        // same tick as the click — a click without its swing is what PaceRules' NO_SWING rule (and an anticheat) flags.
        if (click == RestockTrip.Click.SENT && result[0] instanceof ActionResult.Success success
            && success.swingSource() == ActionResult.SwingSource.CLIENT) {
            p.swingHand(Hand.MAIN_HAND);
        }
    }
}
