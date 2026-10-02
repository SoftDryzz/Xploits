package com.xploits.restock.core;

import com.xploits.printer.core.Pos;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static com.xploits.restock.core.RestockTrip.Aiming.HELD;
import static com.xploits.restock.core.RestockTrip.Aiming.WANTED;
import static com.xploits.restock.core.RestockTrip.Click.REFUSED;
import static com.xploits.restock.core.RestockTrip.Click.SENT;
import static com.xploits.restock.core.RestockTrip.Click.WITHHELD;
import static com.xploits.restock.core.RestockTrip.Failure.FILLED_ONLY;
import static com.xploits.restock.core.RestockTrip.Failure.STALE;
import static com.xploits.restock.core.RestockTrip.Failure.UNUSABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Restock spec §3 "The trip": one step a tick, from the first walking command back to where the trip started. */
class RestockTripTest {
    private static final String STONE = "minecraft:stone";
    private static final Pos CHEST = new Pos(0, 64, 6);
    private static final Pos STAND = new Pos(0, 64, 5);
    private static final Pos HOME = new Pos(0, 64, 0);
    private static final Pos SPOT = new Pos(1, 64, 5);
    private static final Pos OTHER = new Pos(5, 64, 6);
    private static final Pos OTHER_STAND = new Pos(5, 64, 5);

    private static RestockTrip trip(boolean printerPaused, Optional<Pos> stand) {
        return new RestockTrip(new RestockTrip.Plan(STONE, CHEST, stand, HOME, printerPaused), RestockLimits.DEFAULTS);
    }

    /** Walking to the marked chest's spot (the GoTo already given). */
    private static RestockTrip walking() {
        RestockTrip t = trip(false, Optional.of(STAND));
        assertEquals(new RestockTrip.GoTo(STAND), t.step(f().build()));
        return t;
    }

    /** Standing on the spot, in OPEN. */
    private static RestockTrip atChest() {
        RestockTrip t = walking();
        assertEquals(new RestockTrip.StopWalking(), t.step(f().arrived().build()));
        assertEquals(RestockTrip.Phase.OPEN, t.phase());
        return t;
    }

    /** The chest's screen just opened, in TAKE with no tick of it spent. */
    private static RestockTrip taking() {
        RestockTrip t = atChest();
        assertEquals(new RestockTrip.ClickContainer(CHEST), t.step(f().aiming(HELD).build()));
        assertEquals(new RestockTrip.Wait(), t.step(f().click(SENT).ourScreen().build()));
        assertEquals(RestockTrip.Phase.TAKE, t.phase());
        return t;
    }

    @Test
    void aWholeTripWithThePrinterPaused() {
        RestockTrip t = trip(true, Optional.of(STAND));
        for (int i = 1; i <= 10; i++) assertEquals(new RestockTrip.Wait(), t.step(f().build()), "settling " + i);
        assertEquals(new RestockTrip.GoTo(STAND), t.step(f().build()));
        assertEquals(new RestockTrip.Wait(), t.step(f().at(4).build()));
        assertEquals(new RestockTrip.StopWalking(), t.step(f().arrived().build()));
        assertEquals(new RestockTrip.Aim(CHEST), t.step(f().aiming(WANTED).build()));
        assertEquals(new RestockTrip.ClickContainer(CHEST), t.step(f().aiming(HELD).build()));
        assertEquals(new RestockTrip.Wait(), t.step(f().click(SENT).build()));
        assertEquals(new RestockTrip.Wait(), t.step(f().ourScreen().build()));
        assertEquals(new RestockTrip.Take(3), t.step(f().ourScreen().seen().take(new TakePlan.Click(3)).build()));
        assertEquals(new RestockTrip.Close(), t.step(f().ourScreen().seen().take(new TakePlan.Done(true)).carried(64).build()));
        assertEquals(new RestockTrip.GoTo(HOME), t.step(f().carried(64).build()));
        assertEquals(new RestockTrip.Wait(), t.step(f().at(3).carried(64).build()));
        assertEquals(new RestockTrip.Finish(true, true), t.step(f().arrived().carried(64).build()));
        assertEquals(RestockTrip.Phase.DONE, t.phase());
        assertThrows(IllegalStateException.class, () -> t.step(f().build()));
    }

    @Test
    void withoutThePrinterItWalksAtOnce() {
        assertEquals(new RestockTrip.GoTo(STAND), trip(false, Optional.of(STAND)).step(f().build()));
    }

    @Test
    void aStashSourceIsApproachedThenItsSpotChosen() {
        RestockTrip t = trip(false, Optional.empty());
        assertEquals(new RestockTrip.GoToward(0, 6), t.step(f().build()));
        assertEquals(new RestockTrip.Wait(), t.step(f().at(20).build()));
        assertEquals(new RestockTrip.StopWalking(), t.step(f().arrived().spot(SPOT).build()));
        assertEquals(Optional.of(SPOT), t.stand());
        assertEquals(new RestockTrip.GoTo(SPOT), t.step(f().build()));
    }

    @Test
    void noSpotNearAStashSourceAsksForAnother() {
        RestockTrip t = trip(false, Optional.empty());
        t.step(f().build());
        assertEquals(new RestockTrip.NeedSource(STONE, CHEST, UNUSABLE), t.step(f().arrived().build()));
        assertEquals(RestockTrip.Phase.CHOOSING, t.phase());
        t.retarget(OTHER, Optional.of(OTHER_STAND));
        assertEquals(new RestockTrip.GoTo(OTHER_STAND), t.step(f().build()));
        assertEquals(OTHER, t.container());
    }

    @Test
    void aStaleChestIsClosedThenAnotherAskedFor() {
        RestockTrip t = taking();
        assertEquals(new RestockTrip.Close(), t.step(f().ourScreen().seen().take(new TakePlan.Done(false)).build()));
        assertEquals(new RestockTrip.NeedSource(STONE, CHEST, STALE), t.step(f().build()));
        t.giveUp();
        assertEquals(new RestockTrip.GoTo(HOME), t.step(f().build()));
        assertEquals(new RestockTrip.Finish(false, false), t.step(f().arrived().build()));
    }

    @Test
    void aContainerWithTheMaterialOnlyFilledAsksForAnotherSayingSo() {
        // Deferred L86: restock never takes a filled shulker box as a block (ruling R34); the player is told that, not
        // that the container does not have the material any more.
        RestockTrip t = taking();
        assertEquals(new RestockTrip.Close(), t.step(f().ourScreen().seen().take(new TakePlan.Done(false, true)).build()));
        assertEquals(new RestockTrip.NeedSource(STONE, CHEST, FILLED_ONLY), t.step(f().build()));
        t.retarget(OTHER, Optional.of(OTHER_STAND));
        assertEquals(new RestockTrip.GoTo(OTHER_STAND), t.step(f().build()));
    }

    @Test
    void anEmptyLookingScreenIsNotStaleBeforeTheContentWait() {
        RestockTrip t = taking();
        for (int i = 1; i <= 20; i++) {
            assertEquals(new RestockTrip.Wait(), t.step(f().ourScreen().take(new TakePlan.Done(false)).build()), "tick " + i);
        }
        assertEquals(new RestockTrip.Close(), t.step(f().ourScreen().take(new TakePlan.Done(false)).build()));
        RestockTrip u = taking();
        for (int i = 1; i <= 14; i++) u.step(f().ourScreen().build());
        assertEquals(new RestockTrip.Take(0), u.step(f().ourScreen().seen().take(new TakePlan.Click(0)).build()),
            "the slots arrived after 15 ticks");
    }

    @Test
    void nothingFitsStopsOnlyWhenNothingWasTaken() {
        RestockTrip t = taking();
        assertEquals(new RestockTrip.Close(), t.step(f().ourScreen().seen().take(new TakePlan.NothingFits()).build()));
        assertEquals(new RestockTrip.Stopped(RestockReason.NOTHING_FITS, false), t.step(f().build()));
    }

    @Test
    void nothingFitsAfterTakingSomeReturns() {
        RestockTrip t = taking();
        assertEquals(new RestockTrip.Take(0), t.step(f().ourScreen().seen().take(new TakePlan.Click(0)).build()));
        assertEquals(new RestockTrip.Close(),
            t.step(f().ourScreen().seen().take(new TakePlan.NothingFits()).carried(5).build()));
        assertEquals(new RestockTrip.GoTo(HOME), t.step(f().carried(5).build()));
    }

    @Test
    void movementKeysStopTheTripInAnyPhase() {
        assertEquals(new RestockTrip.Stopped(RestockReason.PLAYER_MOVED, false), walking().step(f().keys().build()));
        assertEquals(new RestockTrip.Stopped(RestockReason.PLAYER_MOVED, true),
            taking().step(f().keys().ourScreen().seen().build()));
        assertEquals(new RestockTrip.Stopped(RestockReason.PLAYER_MOVED, false),
            taking().step(f().keys().moving().ourScreen().build()), "never a close while moving");
        assertEquals(new RestockTrip.Stopped(RestockReason.PLAYER_MOVED, false),
            trip(true, Optional.of(STAND)).step(f().keys().build()), "while the printer settles too");
    }

    @Test
    void aTripLeavesOnlyWithTheScreenFreeAndThePlayerStandingOnTheGround() {
        // Ruling R32: a due trip waits (no stop) while the player walks along the build, sneaks at an edge or jumps;
        // starting then would switch the printer off only for the first step to stop on the player's own keys.
        assertTrue(RestockTrip.mayLeave(true, false, false, true));
        assertFalse(RestockTrip.mayLeave(false, false, false, true), "a screen open or an item on the cursor");
        assertFalse(RestockTrip.mayLeave(true, true, false, true), "a movement key held");
        assertFalse(RestockTrip.mayLeave(true, false, true, true), "sneaking");
        assertFalse(RestockTrip.mayLeave(true, false, false, false), "in the air: the way back would be an air block");
    }

    @Test
    void noProgressForTheStallLimitStops() {
        RestockTrip t = walking();
        // The first distance is the reference; 199 more without progress are not yet the 200 of the limit.
        for (int i = 1; i <= 200; i++) assertEquals(new RestockTrip.Wait(), t.step(f().at(5).build()), "tick " + i);
        assertEquals(new RestockTrip.Stopped(RestockReason.NO_PATH, false), t.step(f().at(5).build()));
    }

    @Test
    void pausedTicksDoNotCountTowardsAStall() {
        RestockTrip t = walking();
        for (int i = 1; i <= 150; i++) t.step(f().at(5).build());
        for (int i = 1; i <= 100; i++) assertEquals(new RestockTrip.Wait(), t.step(f().at(5).paused().build()));
        for (int i = 1; i <= 50; i++) assertEquals(new RestockTrip.Wait(), t.step(f().at(5).build()), "tick " + i);
        assertEquals(new RestockTrip.Stopped(RestockReason.NO_PATH, false), t.step(f().at(5).build()));
    }

    @Test
    void aRefusedClickStops() {
        RestockTrip t = atChest();
        t.step(f().aiming(HELD).build());
        assertEquals(new RestockTrip.Stopped(RestockReason.CONTAINER_REFUSED, false), t.step(f().click(REFUSED).build()));
    }

    @Test
    void aClickWithheldBecauseTheBlockIsNoLongerAContainerMakesItUnusable() {
        // Deferred L80: the adapter's last look before the packet found no container there (ruling R27), so nothing was
        // sent: the source is unusable, as when the aim finds none — not CONTAINER_REFUSED, whose text names Easy Place.
        RestockTrip t = atChest();
        assertEquals(new RestockTrip.ClickContainer(CHEST), t.step(f().aiming(HELD).build()));
        assertEquals(new RestockTrip.NeedSource(STONE, CHEST, UNUSABLE), t.step(f().click(WITHHELD).build()));
        assertEquals(RestockTrip.Phase.CHOOSING, t.phase());
    }

    @Test
    void aContainerThatNeverOpensAsksForAnother() {
        RestockTrip t = atChest();
        t.step(f().aiming(HELD).build());
        assertEquals(new RestockTrip.Wait(), t.step(f().click(SENT).build()));
        for (int i = 2; i <= 100; i++) assertEquals(new RestockTrip.Wait(), t.step(f().build()), "tick " + i);
        assertEquals(new RestockTrip.NeedSource(STONE, CHEST, UNUSABLE), t.step(f().build()));
    }

    @Test
    void noContainerActionWhileNotStillPausedOrWithAScreenOpen() {
        RestockTrip t = atChest();
        assertEquals(new RestockTrip.Wait(), t.step(f().aiming(HELD).moving().build()));
        assertEquals(new RestockTrip.Wait(), t.step(f().aiming(HELD).paused().build()));
        assertEquals(new RestockTrip.Wait(), t.step(f().aiming(HELD).screenOpen().build()));
        assertEquals(new RestockTrip.ClickContainer(CHEST), t.step(f().aiming(HELD).build()));
        RestockTrip u = taking();
        assertEquals(new RestockTrip.Wait(), u.step(f().ourScreen().seen().moving().take(new TakePlan.Click(0)).build()));
        assertEquals(new RestockTrip.Wait(), u.step(f().ourScreen().seen().paused().take(new TakePlan.Click(0)).build()));
        assertEquals(new RestockTrip.Take(0), u.step(f().ourScreen().seen().take(new TakePlan.Click(0)).build()));
        assertEquals(new RestockTrip.Wait(),
            u.step(f().ourScreen().seen().moving().take(new TakePlan.Done(true)).carried(64).build()),
            "the close waits for stillness too");
    }

    @Test
    void aScreenClosedDuringTheTakeIsAStop() {
        assertEquals(new RestockTrip.Stopped(RestockReason.CONTAINER_CLOSED, false), taking().step(f().build()));
    }

    @Test
    void theTakeClickCapEndsTheTake() {
        RestockTrip t = taking();
        for (int i = 1; i <= 64; i++) {
            assertEquals(new RestockTrip.Take(i % 27), t.step(f().ourScreen().seen().take(new TakePlan.Click(i % 27)).build()));
        }
        assertEquals(new RestockTrip.Close(), t.step(f().ourScreen().seen().take(new TakePlan.Click(0)).carried(64).build()));
        assertEquals(new RestockTrip.GoTo(HOME), t.step(f().carried(64).build()));
        RestockTrip u = taking();
        for (int i = 1; i <= 64; i++) u.step(f().ourScreen().seen().take(new TakePlan.Click(0)).build());
        assertEquals(new RestockTrip.Close(), u.step(f().ourScreen().seen().take(new TakePlan.Click(0)).build()));
        assertEquals(new RestockTrip.Stopped(RestockReason.NOTHING_FITS, false), u.step(f().build()),
            "64 clicks moved nothing: the inventory cannot take it");
    }

    @Test
    void noAimFromTheSpotAsksForAnother() {
        assertEquals(new RestockTrip.NeedSource(STONE, CHEST, UNUSABLE), atChest().step(f().build()));
    }

    @Test
    void noProgressBackStopsWithItsOwnReason() {
        RestockTrip t = taking();
        t.step(f().ourScreen().seen().take(new TakePlan.Done(true)).carried(64).build());
        assertEquals(new RestockTrip.GoTo(HOME), t.step(f().carried(64).build()));
        for (int i = 1; i <= 200; i++) t.step(f().at(5).carried(64).build());
        assertEquals(new RestockTrip.Stopped(RestockReason.NO_PATH_BACK, false), t.step(f().at(5).carried(64).build()));
    }

    @Test
    void retargetAndGiveUpOnlyWhileChoosing() {
        assertThrows(IllegalStateException.class, () -> walking().retarget(OTHER, Optional.of(OTHER_STAND)));
        assertThrows(IllegalStateException.class, () -> walking().giveUp());
    }

    @Test
    void whatTheAdapterReadsOfThePhases() {
        RestockTrip t = walking();
        assertEquals(Optional.of(STAND), t.goal());
        assertFalse(t.clicking());
        RestockTrip open = atChest();
        assertTrue(open.clicking());
        assertEquals(Optional.empty(), open.goal());
        RestockTrip back = taking();
        assertTrue(back.clicking());
        back.step(f().ourScreen().seen().take(new TakePlan.Done(true)).carried(64).build());
        assertEquals(Optional.of(HOME), back.goal());
        assertFalse(back.clicking());
    }

    @Test
    void anItemOnTheCursorBlocksEveryTakeAndClose() {
        RestockTrip t = taking();
        assertEquals(new RestockTrip.Wait(), t.step(f().ourScreen().seen().cursor().take(new TakePlan.Click(0)).build()));
        assertEquals(new RestockTrip.Wait(),
            t.step(f().ourScreen().seen().cursor().take(new TakePlan.Done(true)).carried(64).build()));
        assertEquals(new RestockTrip.Close(),
            t.step(f().ourScreen().seen().take(new TakePlan.Done(true)).carried(64).build()));
    }

    @Test
    void aStopNeverClosesOverAHeldItemOrDuringAPause() {
        assertEquals(new RestockTrip.Stopped(RestockReason.PLAYER_MOVED, false),
            taking().step(f().keys().ourScreen().seen().cursor().build()));
        assertEquals(new RestockTrip.Stopped(RestockReason.PLAYER_MOVED, false),
            taking().step(f().keys().ourScreen().seen().paused().build()));
    }

    @Test
    void pausedTicksDoNotCountTowardsTheContentWait() {
        RestockTrip t = taking();
        for (int i = 1; i <= 25; i++) assertEquals(new RestockTrip.Wait(), t.step(f().ourScreen().paused().build()));
        assertEquals(new RestockTrip.Wait(), t.step(f().ourScreen().take(new TakePlan.Done(false)).build()));
    }

    @Test
    void pausedTicksDoNotCountTowardsTheOpenTimeout() {
        RestockTrip t = atChest();
        t.step(f().aiming(HELD).build());
        for (int i = 1; i <= 150; i++) assertEquals(new RestockTrip.Wait(), t.step(f().paused().build()));
        assertEquals(new RestockTrip.Wait(), t.step(f().build()));
    }

    @Test
    void anOpenThatCannotClickForTheLimitAsksForAnother() {
        RestockTrip t = atChest();
        for (int i = 1; i <= 100; i++) assertEquals(new RestockTrip.Wait(), t.step(f().aiming(HELD).screenOpen().build()), "tick " + i);
        assertEquals(new RestockTrip.NeedSource(STONE, CHEST, UNUSABLE), t.step(f().aiming(HELD).screenOpen().build()));
        RestockTrip u = atChest();
        for (int i = 1; i <= 150; i++) assertEquals(new RestockTrip.Wait(), u.step(f().aiming(HELD).screenOpen().paused().build()));
        assertEquals(new RestockTrip.Wait(), u.step(f().aiming(HELD).screenOpen().build()));
    }

    @Test
    void pausedStallsNeverStopApproachOrReturn() {
        RestockTrip a = trip(false, Optional.empty());
        a.step(f().build());
        for (int i = 1; i <= 250; i++) assertEquals(new RestockTrip.Wait(), a.step(f().at(5).paused().build()));
        for (int i = 1; i <= 200; i++) a.step(f().at(5).build());
        assertEquals(new RestockTrip.Stopped(RestockReason.NO_PATH, false), a.step(f().at(5).build()));
        RestockTrip b = taking();
        b.step(f().ourScreen().seen().take(new TakePlan.Done(true)).carried(64).build());
        b.step(f().carried(64).build());
        for (int i = 1; i <= 250; i++) assertEquals(new RestockTrip.Wait(), b.step(f().at(5).carried(64).paused().build()));
        for (int i = 1; i <= 200; i++) b.step(f().at(5).carried(64).build());
        assertEquals(new RestockTrip.Stopped(RestockReason.NO_PATH_BACK, false), b.step(f().at(5).carried(64).build()));
    }

    private static F f() {
        return new F();
    }

    /** The facts of one tick; by default: still, no screen, not arrived, 5 blocks to go, nothing seen or carried. */
    private static final class F {
        private boolean keys;
        private boolean paused;
        private boolean still = true;
        private boolean screenFree = true;
        private double distance = 5;
        private boolean arrived;
        private Optional<Pos> spot = Optional.empty();
        private RestockTrip.Aiming aiming = RestockTrip.Aiming.NONE;
        private RestockTrip.Click click = RestockTrip.Click.NONE;
        private boolean ourScreen;
        private boolean contentSeen;
        private TakePlan.Step take = new TakePlan.Done(false);
        private int carried;
        private boolean cursorEmpty = true;

        F cursor() {
            cursorEmpty = false;
            return this;
        }

        F keys() {
            keys = true;
            return this;
        }

        F paused() {
            paused = true;
            return this;
        }

        F moving() {
            still = false;
            return this;
        }

        F screenOpen() {
            screenFree = false;
            return this;
        }

        F at(double d) {
            distance = d;
            return this;
        }

        F arrived() {
            arrived = true;
            return this;
        }

        F spot(Pos p) {
            spot = Optional.of(p);
            return this;
        }

        F aiming(RestockTrip.Aiming a) {
            aiming = a;
            return this;
        }

        F click(RestockTrip.Click c) {
            click = c;
            return this;
        }

        F ourScreen() {
            ourScreen = true;
            return this;
        }

        F seen() {
            contentSeen = true;
            return this;
        }

        F take(TakePlan.Step s) {
            take = s;
            return this;
        }

        F carried(int n) {
            carried = n;
            return this;
        }

        RestockTrip.Facts build() {
            return new RestockTrip.Facts(keys, paused, still, screenFree, distance, arrived, spot, aiming, click,
                ourScreen, contentSeen, take, carried, cursorEmpty);
        }
    }
}
