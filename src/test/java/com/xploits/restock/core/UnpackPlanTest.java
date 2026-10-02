package com.xploits.restock.core;

import com.xploits.printer.core.Aim;
import com.xploits.printer.core.BreakPlan;
import com.xploits.printer.core.Face;
import com.xploits.printer.core.HotbarPlan;
import com.xploits.printer.core.Point;
import com.xploits.printer.core.Pos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Restock spec §3 "Shulkers at the build", owner rulings R42 and R43: one box unpacked beside the build, one step a tick. */
class UnpackPlanTest {
    private static final String STONE = "minecraft:stone";
    private static final String BOX = "minecraft:shulker_box";
    private static final Pos HOME = new Pos(0, 64, 0);
    private static final Pos DROP = new Pos(-2, 64, 0);
    private static final ShulkerSpot.Choice S1 = spot(new Pos(-1, 64, 0));
    private static final ShulkerSpot.Choice S2 = spot(new Pos(0, 64, -1));
    private static final ShulkerSpot.Choice S3 = spot(new Pos(1, 64, 0));
    /** A pickaxe in slot 1: delta 0.25, so progress 0.25, 0.5, 0.75, 1.0 — STOP on the fourth tick after START. */
    private static final BreakPlan.Choice PICK = new BreakPlan.Choice(1, 0.25f, 4);
    private static final UnpackPlan.DigAim TOP = new UnpackPlan.DigAim(Face.UP, new Aim.Rotation(90f, 60f));
    private static final UnpackPlan.Action WAIT = new UnpackPlan.Wait();
    /** The defaults with no dig restart. */
    private static final UnpackLimits ONCE = new UnpackLimits(40, 3, 4, 40, 0, 40, 20, 100, 100, 200);

    private static ShulkerSpot.Choice spot(Pos cell) {
        return new ShulkerSpot.Choice(cell, new Pos(cell.x(), cell.y() - 1, cell.z()),
            new Point(cell.x() + 0.5, cell.y(), cell.z() + 0.5), new Aim.Rotation(90f, 69.7f));
    }

    /** One tick's facts: still, no screen, the slot change allowed, the box ready in the hand (slot 0, selected when it began). */
    private static final class F {
        boolean keys;
        boolean paused;
        boolean still = true;
        boolean screenFree = true;
        boolean slotOk = true;
        HotbarPlan.Step hotbar = new HotbarPlan.Ready(0);
        int selected;
        Optional<ShulkerSpot.Choice> spot = Optional.empty();
        boolean aimHeld;
        UnpackPlan.Click click = UnpackPlan.Click.NONE;
        Optional<Pos> standing = Optional.empty();
        UnpackPlan.Contents contents = UnpackPlan.Contents.RUNNING;
        Optional<RestockReason> contentsStop = Optional.empty();
        Optional<BreakPlan.Choice> tool = Optional.empty();
        Optional<UnpackPlan.DigAim> digAim = Optional.empty();
        float vanilla;
        int carried = 1;
        Optional<Pos> drop = Optional.empty();
        boolean arrived;
        double distance = 5;

        UnpackPlan.Facts get() {
            return new UnpackPlan.Facts(keys, paused, still, screenFree, slotOk, hotbar, selected, spot, aimHeld, click,
                standing, contents, contentsStop, tool, digAim, vanilla, carried, drop, arrived, distance);
        }
    }

    private static UnpackPlan fresh() {
        return fresh(UnpackLimits.DEFAULTS);
    }

    private static UnpackPlan fresh(UnpackLimits limits) {
        return new UnpackPlan(new UnpackPlan.Plan(STONE, BOX, 1, HOME, 0, false), limits, RestockLimits.DEFAULTS);
    }

    private static UnpackPlan.Stopped stopped(RestockReason reason) {
        return new UnpackPlan.Stopped(reason, Optional.empty(), OptionalInt.empty());
    }

    private static UnpackPlan.Stopped stopped(RestockReason reason, OptionalInt select) {
        return new UnpackPlan.Stopped(reason, Optional.empty(), select);
    }

    /** SELECT → PLACE → PLACED → CONTENTS on S1: the box was in the hand from the start. */
    private static UnpackPlan opened(UnpackLimits limits, F f) {
        UnpackPlan u = fresh(limits);
        assertEquals(WAIT, u.step(f.get()), "ready in the hand: into PLACE");
        f.spot = Optional.of(S1);
        f.aimHeld = true;
        assertEquals(new UnpackPlan.Place(S1), u.step(f.get()));
        f.spot = Optional.empty();
        f.aimHeld = false;
        f.click = UnpackPlan.Click.SENT;
        f.carried = 0;
        f.standing = Optional.of(S1.cell());
        assertEquals(new UnpackPlan.OpenContents(S1.cell()), u.step(f.get()));
        f.click = UnpackPlan.Click.NONE;
        return u;
    }

    /** ...then the take done: in DIG, the pickaxe (slot 1) already selected, the top in sight, vanilla's delta restock's own. */
    private static UnpackPlan digging(UnpackLimits limits, F f) {
        UnpackPlan u = opened(limits, f);
        f.contents = UnpackPlan.Contents.DONE;
        assertEquals(WAIT, u.step(f.get()), "the take done: into DIG");
        f.tool = Optional.of(PICK);
        f.selected = 1;
        f.digAim = Optional.of(TOP);
        f.aimHeld = true;
        f.vanilla = 0.25f;
        return u;
    }

    @Test
    void selectsMovesAndPlacesOnlyWhenAllowedAimingFirst() {
        UnpackPlan u = fresh();
        F f = new F();
        f.hotbar = new HotbarPlan.QuickMove(20);
        f.still = false;
        assertEquals(WAIT, u.step(f.get()), "never a click while moving");
        f.still = true;
        f.screenFree = false;
        assertEquals(WAIT, u.step(f.get()), "nor with a screen open");
        f.screenFree = true;
        assertEquals(new UnpackPlan.MoveToHotbar(20), u.step(f.get()), "ruling R43: the box into the hotbar");
        f.hotbar = new HotbarPlan.Select(4);
        f.slotOk = false;
        assertEquals(WAIT, u.step(f.get()), "no slot change after a right-click in the same Grim tick");
        f.slotOk = true;
        assertEquals(new UnpackPlan.Select(4), u.step(f.get()));
        f.hotbar = new HotbarPlan.Ready(4);
        f.selected = 4;
        assertEquals(WAIT, u.step(f.get()), "into PLACE");
        assertEquals(UnpackPlan.Phase.PLACE, u.phase());
        f.spot = Optional.of(S1);
        assertEquals(new UnpackPlan.AimAt(S1.rotation()), u.step(f.get()), "aim one tick");
        f.aimHeld = true;
        assertEquals(new UnpackPlan.Place(S1), u.step(f.get()), "click the next");
        assertEquals(UnpackPlan.Phase.PLACED, u.phase());
        assertEquals(Set.of(S1.cell()), u.tried());
    }

    @Test
    void oneMoveIntoTheHotbarAtMost() {
        UnpackPlan u = fresh();
        F f = new F();
        f.hotbar = new HotbarPlan.QuickMove(20);
        assertEquals(new UnpackPlan.MoveToHotbar(20), u.step(f.get()));
        assertEquals(stopped(RestockReason.NO_HOTBAR_ROOM), u.step(f.get()),
            "a box still outside the hotbar after the one move: never a second click");
    }

    @Test
    void noHotbarRoomNotCarriedAndNoSpotStop() {
        F f = new F();
        f.hotbar = new HotbarPlan.NoRoom();
        assertEquals(stopped(RestockReason.NO_HOTBAR_ROOM), fresh().step(f.get()));
        f.hotbar = new HotbarPlan.NotCarried();
        assertEquals(stopped(RestockReason.SHULKER_NOT_CARRIED), fresh().step(f.get()));
        UnpackPlan u = fresh();
        F g = new F();
        assertEquals(WAIT, u.step(g.get()));
        assertEquals(stopped(RestockReason.SHULKER_NO_SPOT), u.step(g.get()));
    }

    @Test
    void thePrinterSettlesBeforeTheFirstStep() {
        UnpackPlan u = new UnpackPlan(new UnpackPlan.Plan(STONE, BOX, 1, HOME, 0, true), UnpackLimits.DEFAULTS,
            RestockLimits.DEFAULTS);
        assertEquals(UnpackPlan.Phase.PAUSING, u.phase());
        F f = new F();
        f.hotbar = new HotbarPlan.Select(4);
        for (int i = 1; i <= 10; i++) assertEquals(WAIT, u.step(f.get()), "settling, tick " + i);
        assertEquals(new UnpackPlan.Select(4), u.step(f.get()));
    }

    @Test
    void aPlacementThatNeverAppearsTriesAnotherCellThenStops() {
        UnpackPlan u = fresh();
        F f = new F();
        for (ShulkerSpot.Choice s : List.of(S1, S2, S3)) {
            f.spot = Optional.empty();
            f.aimHeld = false;
            f.click = UnpackPlan.Click.NONE;
            assertEquals(WAIT, u.step(f.get()), "ready: into PLACE");
            f.spot = Optional.of(s);
            f.aimHeld = true;
            assertEquals(new UnpackPlan.Place(s), u.step(f.get()));
            f.spot = Optional.empty();
            f.aimHeld = false;
            f.click = UnpackPlan.Click.SENT;
            for (int i = 1; i <= 40; i++) {
                assertEquals(WAIT, u.step(f.get()), "waiting for it, tick " + i);
                f.click = UnpackPlan.Click.NONE;
            }
            UnpackPlan.Action last = u.step(f.get());
            if (s != S3) {
                assertEquals(WAIT, last);
                assertEquals(UnpackPlan.Phase.SELECT, u.phase(), "still in the inventory: another cell");
            } else {
                assertEquals(stopped(RestockReason.SHULKER_NOT_PLACED), last);
            }
        }
        assertEquals(Set.of(S1.cell(), S2.cell(), S3.cell()), u.tried());
    }

    @Test
    void pausedTicksDoNotCountTowardsThePlacement() {
        UnpackPlan u = fresh();
        F f = new F();
        u.step(f.get());
        f.spot = Optional.of(S1);
        f.aimHeld = true;
        u.step(f.get());
        f.spot = Optional.empty();
        f.aimHeld = false;
        for (int i = 1; i <= 40; i++) {
            f.paused = true;
            assertEquals(WAIT, u.step(f.get()));
            f.paused = false;
            assertEquals(WAIT, u.step(f.get()), "live tick " + i);
        }
        assertEquals(UnpackPlan.Phase.PLACED, u.phase());
        assertEquals(WAIT, u.step(f.get()));
        assertEquals(UnpackPlan.Phase.SELECT, u.phase(), "the 41st live tick");
    }

    @Test
    void aBoxThatAppearsOnAnEarlierCellIsAdopted() {
        UnpackPlan u = fresh();
        F f = new F();
        u.step(f.get());
        f.spot = Optional.of(S1);
        f.aimHeld = true;
        assertEquals(new UnpackPlan.Place(S1), u.step(f.get()));
        f.spot = Optional.empty();
        f.aimHeld = false;
        for (int i = 1; i <= 41; i++) u.step(f.get());
        assertEquals(UnpackPlan.Phase.SELECT, u.phase());
        u.step(f.get());
        f.spot = Optional.of(S2);
        f.aimHeld = true;
        assertEquals(new UnpackPlan.Place(S2), u.step(f.get()));
        f.spot = Optional.empty();
        f.aimHeld = false;
        f.standing = Optional.of(S1.cell());
        f.carried = 0;
        assertEquals(new UnpackPlan.OpenContents(S1.cell()), u.step(f.get()),
            "the late one on the first cell is the one taken from (pre-flight 18-4)");
        assertEquals(Optional.of(S1.cell()), u.cell());
    }

    @Test
    void aRefusedClickStopsAWithheldOneTriesAnotherCell() {
        UnpackPlan u = fresh();
        F f = new F();
        u.step(f.get());
        f.spot = Optional.of(S1);
        f.aimHeld = true;
        u.step(f.get());
        f.spot = Optional.empty();
        f.aimHeld = false;
        f.click = UnpackPlan.Click.REFUSED;
        assertEquals(stopped(RestockReason.CLICK_NOT_SENT), u.step(f.get()));
        UnpackPlan v = fresh();
        F g = new F();
        v.step(g.get());
        g.spot = Optional.of(S1);
        g.aimHeld = true;
        v.step(g.get());
        g.spot = Optional.empty();
        g.aimHeld = false;
        g.click = UnpackPlan.Click.WITHHELD;
        assertEquals(WAIT, v.step(g.get()), "nothing was sent: no stop");
        assertEquals(UnpackPlan.Phase.SELECT, v.phase());
        assertEquals(Set.of(S1.cell()), v.tried(), "that cell is not tried again");
    }

    @Test
    void aBoxThatLeftTheHandButNeverStandsStops() {
        UnpackPlan u = fresh();
        F f = new F();
        u.step(f.get());
        f.spot = Optional.of(S1);
        f.aimHeld = true;
        u.step(f.get());
        f.spot = Optional.empty();
        f.aimHeld = false;
        f.carried = 0;
        for (int i = 1; i <= 40; i++) assertEquals(WAIT, u.step(f.get()));
        assertEquals(stopped(RestockReason.SHULKER_NOT_PLACED), u.step(f.get()));
    }

    @Test
    void aSoftFailureOfTheTakeStillBreaksAndPicksUpTheBoxThenStopsWithIt() {
        F f = new F();
        UnpackPlan u = opened(UnpackLimits.DEFAULTS, f);
        f.contents = UnpackPlan.Contents.FAILED;
        f.contentsStop = Optional.of(RestockReason.NOTHING_FITS);
        assertEquals(WAIT, u.step(f.get()), "into DIG all the same (pre-flight 18-10)");
        assertEquals(UnpackPlan.Phase.DIG, u.phase());
        f.tool = Optional.of(new BreakPlan.Choice(0, 1.0f, 0));
        f.digAim = Optional.of(TOP);
        f.aimHeld = true;
        f.vanilla = 1.0f;
        assertEquals(new UnpackPlan.DigStart(S1.cell(), Face.UP, true), u.step(f.get()), "an instant break: START only");
        f.standing = Optional.empty();
        assertEquals(WAIT, u.step(f.get()), "gone: into PICK_UP");
        f.carried = 1;
        assertEquals(WAIT, u.step(f.get()), "back by itself: into RETURN");
        f.arrived = true;
        assertEquals(WAIT, u.step(f.get()), "already where it began: into RESTORE");
        assertEquals(stopped(RestockReason.NOTHING_FITS), u.step(f.get()), "the slot never changed: the stop says why");
    }

    @Test
    void aHardFailureOfTheTakeStopsAtOnce() {
        F f = new F();
        UnpackPlan u = opened(UnpackLimits.DEFAULTS, f);
        f.contents = UnpackPlan.Contents.FAILED;
        f.contentsStop = Optional.of(RestockReason.INTERNAL);
        assertEquals(stopped(RestockReason.INTERNAL), u.step(f.get()));
    }

    @Test
    void theToolIsSelectedAndSettlesUntilTheDeltasMatch() {
        // P18: the client gets the new tool's Efficiency only with the server's equipment sync, a round trip later.
        F f = new F();
        UnpackPlan u = digging(UnpackLimits.DEFAULTS, f);
        f.selected = 0;
        assertEquals(new UnpackPlan.Select(1), u.step(f.get()));
        f.selected = 1;
        f.vanilla = 0.2f;
        for (int i = 1; i <= 6; i++) assertEquals(WAIT, u.step(f.get()), "settling, tick " + i);
        f.vanilla = 0.25f;
        assertEquals(new UnpackPlan.DigStart(S1.cell(), Face.UP, false), u.step(f.get()));
        F g = new F();
        UnpackPlan v = digging(UnpackLimits.DEFAULTS, g);
        g.selected = 0;
        assertEquals(new UnpackPlan.Select(1), v.step(g.get()));
        g.selected = 1;
        for (int i = 1; i <= 4; i++) assertEquals(WAIT, v.step(g.get()), "the least settle, tick " + i);
        assertEquals(new UnpackPlan.DigStart(S1.cell(), Face.UP, false), v.step(g.get()));
    }

    @Test
    void aDeltaThatNeverMatchesRestartsThenStops() {
        F f = new F();
        UnpackPlan u = digging(UnpackLimits.DEFAULTS, f);
        f.vanilla = 0.2f;
        for (int round = 1; round <= 4; round++) {
            for (int i = 1; i <= 40; i++) assertEquals(WAIT, u.step(f.get()), "round " + round + ", tick " + i);
            UnpackPlan.Action a = u.step(f.get());
            if (round < 4) assertEquals(WAIT, a, "restart " + round);
            else assertEquals(stopped(RestockReason.BREAK_SPEED_MISMATCH, OptionalInt.of(0)), a);
        }
    }

    @Test
    void theDigCountsFromTheTickAfterStartAndSwingsEveryTick() {
        F f = new F();
        UnpackPlan u = digging(UnpackLimits.DEFAULTS, f);
        assertEquals(new UnpackPlan.DigStart(S1.cell(), Face.UP, false), u.step(f.get()));
        assertTrue(u.digging());
        f.click = UnpackPlan.Click.SENT;
        assertEquals(new UnpackPlan.Swing(), u.step(f.get()), "0.25");
        f.click = UnpackPlan.Click.NONE;
        assertEquals(new UnpackPlan.Swing(), u.step(f.get()), "0.5");
        assertEquals(new UnpackPlan.Swing(), u.step(f.get()), "0.75");
        assertEquals(new UnpackPlan.DigStop(S1.cell(), Face.UP), u.step(f.get()), "1.0 on the fourth tick after START");
        assertEquals(UnpackPlan.Phase.DUG, u.phase());
        assertFalse(u.digging());
    }

    @Test
    void theDigAimsBeforeItStarts() {
        F f = new F();
        UnpackPlan u = digging(UnpackLimits.DEFAULTS, f);
        f.aimHeld = false;
        assertEquals(new UnpackPlan.AimAt(TOP.rotation()), u.step(f.get()));
    }

    @Test
    void aPauseMidDigAbortsAndStartsAgainAfterTheGap() {
        F f = new F();
        UnpackPlan u = digging(UnpackLimits.DEFAULTS, f);
        assertEquals(new UnpackPlan.DigStart(S1.cell(), Face.UP, false), u.step(f.get()));
        f.paused = true;
        assertEquals(new UnpackPlan.DigAbort(S1.cell(), Face.UP), u.step(f.get()));
        f.paused = false;
        // The gap: no START earlier than 6 ticks after the ABORT (breakGapTicks).
        for (int i = 1; i <= 5; i++) assertEquals(WAIT, u.step(f.get()), "the gap, tick " + i);
        assertEquals(new UnpackPlan.DigStart(S1.cell(), Face.UP, false), u.step(f.get()));
    }

    @Test
    void aMoveOrAScreenMidDigLetsGoLikeVanilla() {
        // M1, M2: vanilla lets go of a dig when the player moves or a screen opens (handleBlockBreaking(false)).
        F f = new F();
        UnpackPlan u = digging(UnpackLimits.DEFAULTS, f);
        assertEquals(new UnpackPlan.DigStart(S1.cell(), Face.UP, false), u.step(f.get()));
        f.still = false;
        assertEquals(new UnpackPlan.DigAbort(S1.cell(), Face.UP), u.step(f.get()), "not still: let go");
        assertFalse(u.digging());
        F g = new F();
        UnpackPlan v = digging(UnpackLimits.DEFAULTS, g);
        assertEquals(new UnpackPlan.DigStart(S1.cell(), Face.UP, false), v.step(g.get()));
        g.screenFree = false;
        assertEquals(new UnpackPlan.DigAbort(S1.cell(), Face.UP), v.step(g.get()), "a screen opened: let go");
        assertEquals(UnpackPlan.Phase.DIG, v.phase());
    }

    @Test
    void aLetGoThatKeepsComingBackStopsTheUnpack() {
        // I3: every let-go counts towards digRestarts, so an interruption that keeps coming back ends the unpack
        // instead of sending a START, swings and an ABORT again and again.
        F f = new F();
        UnpackPlan u = digging(UnpackLimits.DEFAULTS, f);
        for (int round = 1; round <= 3; round++) {
            f.paused = false;
            assertEquals(new UnpackPlan.DigStart(S1.cell(), Face.UP, false), u.step(f.get()), "START, round " + round);
            f.paused = true;
            assertEquals(new UnpackPlan.DigAbort(S1.cell(), Face.UP), u.step(f.get()), "let go, round " + round);
            f.paused = false;
            for (int i = 1; i <= 5; i++) assertEquals(WAIT, u.step(f.get()), "the gap, round " + round + ", tick " + i);
        }
        assertEquals(new UnpackPlan.DigStart(S1.cell(), Face.UP, false), u.step(f.get()), "START, round 4");
        f.paused = true;
        assertEquals(new UnpackPlan.Stopped(RestockReason.UNPACK_BLOCKED,
            Optional.of(new UnpackPlan.DigAbort(S1.cell(), Face.UP)), OptionalInt.empty()), u.step(f.get()),
            "the fourth let-go: the stop, with the ABORT and nothing else");
    }

    @Test
    void aDeltaThatChangesMidDigAbortsAndStartsAgain() {
        F f = new F();
        UnpackPlan u = digging(UnpackLimits.DEFAULTS, f);
        u.step(f.get());
        f.vanilla = 0.2f;
        assertEquals(new UnpackPlan.DigAbort(S1.cell(), Face.UP), u.step(f.get()), "Haste ran out: let go, start again");
        assertEquals(UnpackPlan.Phase.DIG, u.phase());
        assertFalse(u.digging());
        F g = new F();
        UnpackPlan v = digging(ONCE, g);
        v.step(g.get());
        g.vanilla = 0.2f;
        assertEquals(new UnpackPlan.Stopped(RestockReason.BREAK_SPEED_MISMATCH,
            Optional.of(new UnpackPlan.DigAbort(S1.cell(), Face.UP)), OptionalInt.empty()), v.step(g.get()),
            "no restart left: the stop aborts the dig, and sends nothing else that tick");
    }

    @Test
    void aSlotChangedMidDigAbortsLikeVanilla() {
        F f = new F();
        UnpackPlan u = digging(UnpackLimits.DEFAULTS, f);
        u.step(f.get());
        f.selected = 2;
        assertEquals(new UnpackPlan.DigAbort(S1.cell(), Face.UP), u.step(f.get()));
    }

    @Test
    void anInstantBreakIsStartOnly() {
        F f = new F();
        UnpackPlan u = digging(UnpackLimits.DEFAULTS, f);
        f.tool = Optional.of(new BreakPlan.Choice(1, 1.0f, 0));
        f.vanilla = 1.0f;
        assertEquals(new UnpackPlan.DigStart(S1.cell(), Face.UP, true), u.step(f.get()));
        assertEquals(UnpackPlan.Phase.DUG, u.phase());
        assertFalse(u.digging());
    }

    @Test
    void aStartWithheldOrRefusedSendsNoAbort() {
        F f = new F();
        UnpackPlan u = digging(UnpackLimits.DEFAULTS, f);
        u.step(f.get());
        f.click = UnpackPlan.Click.WITHHELD;
        assertEquals(WAIT, u.step(f.get()), "the box was gone when START was due: nothing was sent");
        assertEquals(UnpackPlan.Phase.DUG, u.phase());
        assertFalse(u.digging());
        F g = new F();
        UnpackPlan v = digging(UnpackLimits.DEFAULTS, g);
        v.step(g.get());
        g.click = UnpackPlan.Click.REFUSED;
        assertEquals(stopped(RestockReason.CLICK_NOT_SENT, OptionalInt.of(0)), v.step(g.get()),
            "something cancelled START: a stop, the slot given back, no ABORT for a dig that never began");
    }

    @Test
    void aBoxThatDoesNotGoIsDugAgainThenStops() {
        F f = new F();
        UnpackPlan u = digging(ONCE, f);
        f.tool = Optional.of(new BreakPlan.Choice(1, 1.0f, 0));
        f.vanilla = 1.0f;
        u.step(f.get());
        for (int i = 1; i <= 40; i++) assertEquals(WAIT, u.step(f.get()), "still standing, tick " + i);
        assertEquals(stopped(RestockReason.SHULKER_NOT_BROKEN, OptionalInt.of(0)), u.step(f.get()));
        F g = new F();
        UnpackPlan v = digging(UnpackLimits.DEFAULTS, g);
        g.tool = Optional.of(new BreakPlan.Choice(1, 1.0f, 0));
        g.vanilla = 1.0f;
        v.step(g.get());
        for (int i = 1; i <= 41; i++) v.step(g.get());
        assertEquals(UnpackPlan.Phase.DIG, v.phase(), "dug again");
    }

    @Test
    void thePickUpWaitsThenWalksOntoTheDropAndComesBack() {
        F f = new F();
        UnpackPlan u = digging(UnpackLimits.DEFAULTS, f);
        u.step(f.get());
        for (int i = 0; i < 3; i++) u.step(f.get());
        assertEquals(new UnpackPlan.DigStop(S1.cell(), Face.UP), u.step(f.get()));
        f.standing = Optional.empty();
        assertEquals(WAIT, u.step(f.get()), "the cell empty: into PICK_UP");
        assertTrue(u.dropped());
        f.drop = Optional.of(DROP);
        for (int i = 1; i <= 20; i++) assertEquals(WAIT, u.step(f.get()), "the drop may come by itself, tick " + i);
        assertEquals(new UnpackPlan.GoTo(DROP), u.step(f.get()), "then onto it");
        assertEquals(WAIT, u.step(f.get()));
        f.carried = 1;
        f.drop = Optional.empty();
        assertEquals(new UnpackPlan.StopWalking(), u.step(f.get()), "back in the inventory");
        assertFalse(u.dropped());
        assertEquals(new UnpackPlan.GoTo(HOME), u.step(f.get()), "back to where it began");
        f.arrived = true;
        assertEquals(new UnpackPlan.StopWalking(), u.step(f.get()));
        assertEquals(new UnpackPlan.Select(0), u.step(f.get()), "the slot selected when it began");
        f.selected = 0;
        assertEquals(new UnpackPlan.Finish(), u.step(f.get()));
        assertEquals(UnpackPlan.Phase.DONE, u.phase());
        assertTrue(u.over());
    }

    /** Reaches PICK_UP with the box broken and gone from its cell, nothing carried yet. */
    private static UnpackPlan pickingUp(F f) {
        UnpackPlan u = digging(UnpackLimits.DEFAULTS, f);
        u.step(f.get());
        for (int i = 0; i < 3; i++) u.step(f.get());
        u.step(f.get());
        f.standing = Optional.empty();
        assertEquals(WAIT, u.step(f.get()), "the cell empty: into PICK_UP");
        assertEquals(UnpackPlan.Phase.PICK_UP, u.phase());
        return u;
    }

    @Test
    void thePickUpEndsOnTheCountNeverOnArrival() {
        F f = new F();
        UnpackPlan u = pickingUp(f);
        f.arrived = true;
        f.carried = 0;
        assertEquals(WAIT, u.step(f.get()));
        assertEquals(UnpackPlan.Phase.PICK_UP, u.phase(), "arriving somewhere is not the box being back");
        f.carried = 1;
        u.step(f.get());
        assertEquals(UnpackPlan.Phase.RETURN, u.phase());
    }

    @Test
    void aDropThatKeepsMovingIsWalkedToThreeTimesAtMost() {
        F f = new F();
        UnpackPlan u = pickingUp(f);
        for (int i = 1; i <= 20; i++) u.step(f.get());
        int goals = 0;
        for (int i = 0; i < 12; i++) {
            f.drop = Optional.of(new Pos(-2 - i, 64, 0));
            if (u.step(f.get()) instanceof UnpackPlan.GoTo) goals++;
        }
        assertEquals(3, goals, "at most three goals as it moves");
    }

    @Test
    void theSlotIsGivenBackOnlyWhenNothingIsOpenAndTheClockAllows() {
        F f = new F();
        UnpackPlan u = pickingUp(f);
        f.carried = 1;
        u.step(f.get());
        f.arrived = true;
        u.step(f.get());
        u.step(f.get());
        assertEquals(UnpackPlan.Phase.RESTORE, u.phase());
        f.selected = 3;
        f.screenFree = false;
        assertEquals(WAIT, u.step(f.get()), "a screen is open: no slot change");
        f.screenFree = true;
        f.slotOk = false;
        assertEquals(WAIT, u.step(f.get()), "no slot change in this Grim tick");
        f.slotOk = true;
        assertEquals(new UnpackPlan.Select(0), u.step(f.get()));
    }

    @Test
    void aBoxNeverPickedUpStops() {
        F f = new F();
        UnpackPlan u = digging(UnpackLimits.DEFAULTS, f);
        u.step(f.get());
        for (int i = 0; i < 3; i++) u.step(f.get());
        u.step(f.get());
        f.standing = Optional.empty();
        u.step(f.get());
        for (int i = 1; i <= 100; i++) assertEquals(WAIT, u.step(f.get()), "not back yet, tick " + i);
        assertEquals(stopped(RestockReason.SHULKER_NOT_PICKED_UP, OptionalInt.of(0)), u.step(f.get()));
        assertTrue(u.dropped(), "the stop says where it lies, or that it is gone");
    }

    @Test
    void theMovementKeysStopAnywhereAndAbortADig() {
        F f = new F();
        UnpackPlan u = digging(UnpackLimits.DEFAULTS, f);
        u.step(f.get());
        f.keys = true;
        assertEquals(new UnpackPlan.Stopped(RestockReason.PLAYER_MOVED,
            Optional.of(new UnpackPlan.DigAbort(S1.cell(), Face.UP)), OptionalInt.empty()), u.step(f.get()));
    }

    @Test
    void aStopAtOnceAbortsADigOrGivesTheSlotBack() {
        // Owner ruling R42: attacked or low on health, at once; the one action that goes with the stop.
        F f = new F();
        UnpackPlan u = digging(UnpackLimits.DEFAULTS, f);
        u.step(f.get());
        assertEquals(new UnpackPlan.Stopped(RestockReason.LOW_HEALTH,
                Optional.of(new UnpackPlan.DigAbort(S1.cell(), Face.UP)), OptionalInt.empty()),
            u.halt(f.get(), RestockReason.LOW_HEALTH), "the box stays standing, the dig aborted");
        assertThrows(IllegalStateException.class, () -> u.step(f.get()));
        F g = new F();
        UnpackPlan v = opened(UnpackLimits.DEFAULTS, g);
        g.screenFree = false;
        g.selected = 3;
        assertEquals(stopped(RestockReason.ATTACKED), v.halt(g.get(), RestockReason.ATTACKED),
            "the take's screen is open: the stop path closes it, and nothing else goes that tick");
        F h = new F();
        UnpackPlan w = fresh();
        h.hotbar = new HotbarPlan.Select(4);
        assertEquals(new UnpackPlan.Select(4), w.step(h.get()));
        h.hotbar = new HotbarPlan.Ready(4);
        h.selected = 4;
        assertEquals(stopped(RestockReason.ATTACKED, OptionalInt.of(0)), w.halt(h.get(), RestockReason.ATTACKED),
            "nothing out yet: the slot goes back");
    }

    @Test
    void aStopThatFinishesFirstSkipsTheTakeBreaksAndPicksUpThenStops() {
        // Owner ruling R42: a stranger near — the break and the pick-up finish, then the stop.
        F f = new F();
        UnpackPlan u = opened(UnpackLimits.DEFAULTS, f);
        assertTrue(u.outside());
        u.drain(RestockReason.PLAYER_NEAR);
        u.drain(RestockReason.CONFLICTING_MODULE);
        assertEquals(Optional.of(RestockReason.PLAYER_NEAR), u.draining(), "the first one counts");
        assertEquals(WAIT, u.step(f.get()), "the take is being cut short");
        f.contents = UnpackPlan.Contents.DONE;
        assertEquals(WAIT, u.step(f.get()), "into DIG");
        f.tool = Optional.of(PICK);
        f.selected = 1;
        f.digAim = Optional.of(TOP);
        f.aimHeld = true;
        f.vanilla = 0.25f;
        assertEquals(new UnpackPlan.DigStart(S1.cell(), Face.UP, false), u.step(f.get()));
        for (int i = 0; i < 3; i++) assertEquals(new UnpackPlan.Swing(), u.step(f.get()));
        assertEquals(new UnpackPlan.DigStop(S1.cell(), Face.UP), u.step(f.get()));
        f.standing = Optional.empty();
        assertEquals(WAIT, u.step(f.get()), "into PICK_UP");
        f.carried = 1;
        assertEquals(WAIT, u.step(f.get()), "back: no walk back, into RESTORE");
        assertEquals(UnpackPlan.Phase.RESTORE, u.phase());
        assertEquals(new UnpackPlan.Select(0), u.step(f.get()));
        f.selected = 0;
        assertEquals(stopped(RestockReason.PLAYER_NEAR), u.step(f.get()), "then the stop");
        assertFalse(u.outside());
    }

    @Test
    void aStopThatFinishesFirstWithNothingOutStopsAtOnce() {
        UnpackPlan u = fresh();
        F f = new F();
        assertFalse(u.outside());
        u.drain(RestockReason.PLAYER_NEAR);
        assertEquals(stopped(RestockReason.PLAYER_NEAR), u.step(f.get()));
        UnpackPlan v = fresh();
        F g = new F();
        v.step(g.get());
        g.spot = Optional.of(S1);
        g.aimHeld = true;
        v.step(g.get());
        g.spot = Optional.empty();
        g.aimHeld = false;
        assertTrue(v.outside(), "clicked, not seen yet: it may appear");
        v.drain(RestockReason.CONFLICTING_MODULE);
        g.standing = Optional.of(S1.cell());
        g.carried = 0;
        assertEquals(WAIT, v.step(g.get()), "it appears: broken and picked up, never opened");
        assertEquals(UnpackPlan.Phase.DIG, v.phase());
    }

    @Test
    void finishingIsBoundedByTheDrainLimit() {
        F f = new F();
        UnpackPlan u = digging(new UnpackLimits(40, 3, 4, 40, 3, 40, 20, 100, 100, 5), f);
        u.drain(RestockReason.CONFLICTING_MODULE);
        f.paused = true;
        for (int i = 1; i <= 5; i++) assertEquals(WAIT, u.step(f.get()), "finishing, tick " + i);
        assertEquals(stopped(RestockReason.CONFLICTING_MODULE), u.step(f.get()),
            "another module never let it act: the stop, the box left where it is");
    }

    @Test
    void everyWaitForStillnessIsBounded() {
        UnpackPlan u = fresh();
        F f = new F();
        u.step(f.get());
        f.spot = Optional.of(S1);
        f.still = false;
        for (int i = 1; i <= 100; i++) {
            f.paused = true;
            assertEquals(WAIT, u.step(f.get()), "a paused tick never counts");
            f.paused = false;
            assertEquals(WAIT, u.step(f.get()), "live tick " + i);
        }
        assertEquals(stopped(RestockReason.UNPACK_BLOCKED), u.step(f.get()));
    }

    @Test
    void whatTheAdapterReadsOfThePhases() {
        UnpackPlan u = fresh();
        assertEquals(UnpackPlan.Phase.SELECT, u.phase());
        assertTrue(u.clicking());
        assertFalse(u.outside());
        assertEquals(Optional.empty(), u.cell());
        assertEquals(STONE, u.material());
        F f = new F();
        UnpackPlan v = opened(UnpackLimits.DEFAULTS, f);
        assertFalse(v.clicking(), "the take clicks through its own trip");
        assertTrue(v.outside());
        assertFalse(v.dropped());
        assertEquals(Optional.of(S1.cell()), v.cell());
        assertEquals(Optional.empty(), v.draining());
    }

    @Test
    void thePlanNamesItsBoxAndAHotbarSlot() {
        assertThrows(IllegalArgumentException.class, () -> new UnpackPlan.Plan(STONE, BOX, 0, HOME, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new UnpackPlan.Plan(STONE, BOX, 1, HOME, 9, false));
        assertThrows(IllegalArgumentException.class, () -> new UnpackPlan.Plan(" ", BOX, 1, HOME, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new UnpackPlan.Stopped(RestockReason.INTERNAL,
            Optional.of(new UnpackPlan.DigAbort(HOME, Face.UP)), OptionalInt.of(0)), "one action with a stop, never two");
    }
}
