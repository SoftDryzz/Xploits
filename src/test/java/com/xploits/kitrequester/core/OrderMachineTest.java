package com.xploits.kitrequester.core;

import com.xploits.shared.chat.ChatEvent;
import com.xploits.shared.core.i18n.Msg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.xploits.kitrequester.core.OrderMachine.State.*;
import static org.junit.jupiter.api.Assertions.*;

class OrderMachineTest {
    private static final Set<String> COURIERS = Set.of("StormAegis44", "ValorKnight27", "IronSentri08");
    private static final OrderMachine.Context OK = new OrderMachine.Context(true, true, 36, false, false);
    private static final String ORDER_1_5 = "/w SnifferBuddy !kit 1, 2, 3, 4, 5";
    private static final long T0 = 1_000_000L;

    private Progress progress;
    private OrderMachine.Config config;
    private OrderMachine machine;

    @BeforeEach
    void setUp() {
        progress = new Progress();
        config = new OrderMachine.Config(300_000, COURIERS, false, false);
        machine = new OrderMachine(KitQueue.parse("#1 a\n#2 b\n#3 c\n#4 d\n#5 e\n#6 f\n#7 g\n"), progress, () -> config, () -> 0L);
    }

    private static boolean sent(List<Action> actions, String command) {
        return actions.contains(new Action.SendCommand(command));
    }

    private static boolean anySent(List<Action> actions) {
        return actions.stream().anyMatch(a -> a instanceof Action.SendCommand);
    }

    private static boolean alerts(List<Action> actions, Msg expected) {
        return actions.stream().anyMatch(a -> a instanceof Action.Notify n && n.alert() && n.message().equals(expected));
    }

    private static boolean notifies(List<Action> actions, Msg expected) {
        return actions.stream().anyMatch(a -> a instanceof Action.Notify n && !n.alert() && n.message().equals(expected));
    }

    /** Joins, waits the grace period, requests batch 1-5 and receives PLACED. Returns the PLACED instant. */
    private long placeOrder() {
        machine.onJoin(T0);
        long t = T0 + OrderMachine.JOIN_GRACE_MS;
        assertTrue(sent(machine.tick(t, OK), ORDER_1_5));
        machine.onChat(new ChatEvent.Placed(), t + 1_000);
        assertEquals(AWAIT_COURIER, machine.state());
        return t + 1_000;
    }

    @Test
    void waitsForJoinGraceThenOrders() {
        machine.onJoin(T0);
        assertFalse(anySent(machine.tick(T0 + 5_000, OK)));
        assertTrue(sent(machine.tick(T0 + OrderMachine.JOIN_GRACE_MS, OK), ORDER_1_5));
        assertEquals(AWAIT_CONFIRM, machine.state());
    }

    @Test
    void doesNothingOutsideWorld() {
        machine.onJoin(T0);
        assertTrue(machine.tick(T0 + OrderMachine.JOIN_GRACE_MS, new OrderMachine.Context(false, false, 0, false, false)).isEmpty());
    }

    @Test
    void waitsWhileKitbotOffline() {
        machine.onJoin(T0);
        assertFalse(anySent(machine.tick(T0 + OrderMachine.JOIN_GRACE_MS, new OrderMachine.Context(true, false, 36, false, false))));
        assertEquals(IDLE, machine.state());
    }

    @Test
    void happyPathFromLogs() {
        long placedAt = placeOrder();
        assertEquals(new Progress.ActiveOrder(List.of(1, 2, 3, 4, 5), placedAt, null), progress.activeOrder);

        // 13:52:03 — the TPA arrives 1 s before the READY whisper
        assertTrue(sent(machine.onChat(new ChatEvent.Tpa("ValorKnight27"), placedAt + 76_000), "/tpy ValorKnight27"));
        assertEquals(AWAIT_DELIVERY, machine.state());
        assertFalse(anySent(machine.onChat(new ChatEvent.Ready("ValorKnight27"), placedAt + 77_000)));

        List<Action> done = machine.onChat(new ChatEvent.Done("ValorKnight27"), placedAt + 120_000);
        assertTrue(done.contains(new Action.Save()));
        assertEquals(Set.of(1, 2, 3, 4, 5), progress.delivered);
        assertNull(progress.activeOrder);
        assertEquals(placedAt + 300_000, progress.nextOrderAt);
        assertEquals(IDLE, machine.state());
    }

    @Test
    void configClampsIntervalToMinimum() {
        OrderMachine.Config cfg = new OrderMachine.Config(60_000, COURIERS, false, false);
        assertEquals(OrderMachine.MIN_INTERVAL_MS, cfg.intervalMs());
    }

    @Test
    void machineWaits300sWithClampedInterval() {
        config = new OrderMachine.Config(60_000, COURIERS, false, false);
        long placedAt = placeOrder();
        machine.onChat(new ChatEvent.Tpa("StormAegis44"), placedAt + 40_000);
        machine.onChat(new ChatEvent.Done("StormAegis44"), placedAt + 60_000);
        assertFalse(anySent(machine.tick(placedAt + 299_999, OK)));
        assertTrue(sent(machine.tick(placedAt + 300_000, OK), "/w SnifferBuddy !kit 6, 7"));
    }

    @Test
    void nextBatchWaitsForInterval() {
        long placedAt = placeOrder();
        machine.onChat(new ChatEvent.Tpa("StormAegis44"), placedAt + 40_000);
        machine.onChat(new ChatEvent.Done("StormAegis44"), placedAt + 60_000);
        assertFalse(anySent(machine.tick(placedAt + 299_999, OK)));
        assertTrue(sent(machine.tick(placedAt + 300_000, OK), "/w SnifferBuddy !kit 6, 7"));
    }

    @Test
    void ignoresTpaOutsideCourierWindow() {
        machine.onJoin(T0);
        assertFalse(anySent(machine.onChat(new ChatEvent.Tpa("StormAegis44"), T0 + 1_000)));
        assertEquals(IDLE, machine.state());
    }

    @Test
    void ignoresRandomPlayerTpaDuringWindow() {
        long placedAt = placeOrder();
        assertFalse(anySent(machine.onChat(new ChatEvent.Tpa("Coordlogger3000"), placedAt + 10_000)));
        List<Action> later = machine.tick(placedAt + 10_000 + OrderMachine.PENDING_TPA_MS, OK);
        assertFalse(anySent(later));
        assertTrue(notifies(later, Msg.of(KitText.TPA_IGNORED, "requester", "Coordlogger3000")));
        assertEquals(AWAIT_COURIER, machine.state());
    }

    @Test
    void lookalikeCourierIsNotKnown() {
        long placedAt = placeOrder();
        assertFalse(anySent(machine.onChat(new ChatEvent.Tpa("stormaegis44"), placedAt + 10_000)));
    }

    @Test
    void unknownCourierRejectedWhenNotTrusted() {
        long placedAt = placeOrder();
        machine.onChat(new ChatEvent.Tpa("NewCourier1"), placedAt + 10_000);
        List<Action> out = machine.onChat(new ChatEvent.Ready("NewCourier1"), placedAt + 11_000);
        assertFalse(anySent(out));
        assertTrue(alerts(out, Msg.of(KitText.UNKNOWN_COURIER_REJECTED, "requester", "NewCourier1")));
    }

    @Test
    void unknownCourierAcceptedAndLearnedWhenTrusted() {
        config = new OrderMachine.Config(300_000, COURIERS, true, false);
        long placedAt = placeOrder();
        assertFalse(anySent(machine.onChat(new ChatEvent.Tpa("NewCourier1"), placedAt + 10_000)));
        List<Action> out = machine.onChat(new ChatEvent.Ready("NewCourier1"), placedAt + 11_000);
        assertTrue(sent(out, "/tpy NewCourier1"));
        assertTrue(out.contains(new Action.LearnCourier("NewCourier1")));
        assertTrue(alerts(out, Msg.of(KitText.NEW_COURIER_LEARNED, "requester", "NewCourier1")));
        assertEquals(AWAIT_DELIVERY, machine.state());
    }

    @Test
    void overwritingPendingTpaNotifiesPreviousRequester() {
        long placedAt = placeOrder();
        assertFalse(anySent(machine.onChat(new ChatEvent.Tpa("First1"), placedAt + 1_000)));
        List<Action> out = machine.onChat(new ChatEvent.Tpa("Second2"), placedAt + 1_500);
        assertFalse(anySent(out));
        assertTrue(notifies(out, Msg.of(KitText.TPA_IGNORED, "requester", "First1")));
    }

    @Test
    void trustedUnknownCourierWithReadyFirstIsAcceptedOnTpa() {
        config = new OrderMachine.Config(300_000, COURIERS, true, false);
        long placedAt = placeOrder();
        machine.onChat(new ChatEvent.Ready("NewCourier1"), placedAt + 10_000);
        assertTrue(sent(machine.onChat(new ChatEvent.Tpa("NewCourier1"), placedAt + 10_500), "/tpy NewCourier1"));
    }

    @Test
    void cooldownRetriesSameBatchWithoutFailure() {
        machine.onJoin(T0);
        long t = T0 + OrderMachine.JOIN_GRACE_MS;
        machine.tick(t, OK);
        machine.onChat(new ChatEvent.Cooldown(240_000), t + 1_000);
        assertEquals(IDLE, machine.state());
        assertEquals(t + 1_000 + 240_000 + OrderMachine.COOLDOWN_MARGIN_MS, progress.nextOrderAt);
        assertTrue(progress.failures.isEmpty());
        assertTrue(sent(machine.tick(progress.nextOrderAt, OK), ORDER_1_5));
    }

    @Test
    void activeReplyWaitsForCourierWithoutReordering() {
        machine.onJoin(T0);
        long t = T0 + OrderMachine.JOIN_GRACE_MS;
        machine.tick(t, OK);
        assertFalse(anySent(machine.onChat(new ChatEvent.Active(), t + 1_000)));
        assertEquals(AWAIT_COURIER, machine.state());
        assertEquals(List.of(1, 2, 3, 4, 5), progress.activeOrder.ids());
    }

    @Test
    void confirmTimeoutRetriesAfterOneMinute() {
        machine.onJoin(T0);
        long t = T0 + OrderMachine.JOIN_GRACE_MS;
        machine.tick(t, OK);
        machine.tick(t + OrderMachine.CONFIRM_TIMEOUT_MS, OK);
        assertEquals(IDLE, machine.state());
        assertEquals(t + OrderMachine.CONFIRM_TIMEOUT_MS + OrderMachine.CONFIRM_RETRY_MS, progress.nextOrderAt);
        assertTrue(progress.failures.isEmpty());
    }

    @Test
    void unregisteredDisablesModule() {
        machine.onJoin(T0);
        long t = T0 + OrderMachine.JOIN_GRACE_MS;
        machine.tick(t, OK);
        List<Action> out = machine.onChat(new ChatEvent.Unregistered(), t + 1_000);
        assertTrue(out.stream().anyMatch(a -> a instanceof Action.Disable));
        assertEquals(ERROR, machine.state());
    }

    @Test
    void usageOutsideConfirmIsIgnored() {
        machine.onJoin(T0);
        assertTrue(machine.onChat(new ChatEvent.Usage(), T0).isEmpty());
        assertEquals(IDLE, machine.state());
    }

    @Test
    void timedOutWithoutTpaCountsFailure() {
        long placedAt = placeOrder();
        machine.onChat(new ChatEvent.TimedOut("IronSentri08"), placedAt + 90_000);
        assertEquals(IDLE, machine.state());
        assertEquals(Map.of(1, 1, 2, 1, 3, 1, 4, 1, 5, 1), progress.failures);
        assertTrue(progress.delivered.isEmpty());
        assertNull(progress.activeOrder);
    }

    @Test
    void threeFailuresSkipIds() {
        long now = T0;
        machine.onJoin(now);
        now += OrderMachine.JOIN_GRACE_MS;
        for (int i = 0; i < OrderMachine.MAX_FAILURES; i++) {
            now = Math.max(now, progress.nextOrderAt);
            assertTrue(sent(machine.tick(now, OK), ORDER_1_5));
            machine.onChat(new ChatEvent.Placed(), now);
            machine.onChat(new ChatEvent.TimedOut("StormAegis44"), now + 60_000);
            now += 60_000;
        }
        assertEquals(Set.of(1, 2, 3, 4, 5), progress.skipped);
        now = Math.max(now, progress.nextOrderAt);
        assertTrue(sent(machine.tick(now, OK), "/w SnifferBuddy !kit 6, 7"));
    }

    @Test
    void courierTimeoutCountsFailure() {
        long placedAt = placeOrder();
        machine.tick(placedAt + OrderMachine.COURIER_TIMEOUT_MS, OK);
        assertEquals(IDLE, machine.state());
        assertEquals(1, progress.failures.get(1));
    }

    @Test
    void notFoundMarksIds() {
        long placedAt = placeOrder();
        machine.onChat(new ChatEvent.NotFound("StormAegis44"), placedAt + 30_000);
        assertEquals(Set.of(1, 2, 3, 4, 5), progress.notFound);
        assertEquals(IDLE, machine.state());
    }

    @Test
    void partialThenDoneGoesToPartial() {
        long placedAt = placeOrder();
        machine.onChat(new ChatEvent.Tpa("StormAegis44"), placedAt + 40_000);
        machine.onChat(new ChatEvent.Partial("StormAegis44"), placedAt + 41_000);
        machine.onChat(new ChatEvent.Done("StormAegis44"), placedAt + 60_000);
        assertEquals(List.of(List.of(1, 2, 3, 4, 5)), progress.partial);
        assertTrue(progress.delivered.isEmpty());
    }

    @Test
    void unknownSenderEventsDuringAwaitCourierChangeNothing() {
        long placedAt = placeOrder();

        assertTrue(machine.onChat(new ChatEvent.NotFound("Mallory"), placedAt + 10_000).isEmpty());
        assertEquals(AWAIT_COURIER, machine.state());
        assertTrue(progress.notFound.isEmpty());

        assertTrue(machine.onChat(new ChatEvent.TimedOut("Mallory"), placedAt + 11_000).isEmpty());
        assertEquals(AWAIT_COURIER, machine.state());
        assertTrue(progress.failures.isEmpty());

        assertTrue(machine.onChat(new ChatEvent.Done("Mallory"), placedAt + 12_000).isEmpty());
        assertEquals(AWAIT_COURIER, machine.state());
        assertTrue(progress.delivered.isEmpty());
    }

    @Test
    void knownCourierEventsInIdleChangeNothing() {
        machine.onJoin(T0);
        assertEquals(IDLE, machine.state());

        assertTrue(machine.onChat(new ChatEvent.Done("StormAegis44"), T0).isEmpty());
        assertEquals(IDLE, machine.state());
        assertTrue(progress.delivered.isEmpty());

        assertTrue(machine.onChat(new ChatEvent.TimedOut("StormAegis44"), T0).isEmpty());
        assertEquals(IDLE, machine.state());
        assertTrue(progress.failures.isEmpty());

        assertTrue(machine.onChat(new ChatEvent.NotFound("StormAegis44"), T0).isEmpty());
        assertEquals(IDLE, machine.state());
        assertTrue(progress.notFound.isEmpty());
    }

    @Test
    void tpaFromKnownCourierOutsideAwaitCourierSendsNothing() {
        machine.onJoin(T0);
        long t = T0 + OrderMachine.JOIN_GRACE_MS;
        machine.tick(t, OK);
        assertEquals(AWAIT_CONFIRM, machine.state());
        assertFalse(anySent(machine.onChat(new ChatEvent.Tpa("StormAegis44"), t + 1_000)));

        long placedAt = t + 1_000;
        machine.onChat(new ChatEvent.Placed(), placedAt);
        assertEquals(AWAIT_COURIER, machine.state());
        assertTrue(sent(machine.onChat(new ChatEvent.Tpa("StormAegis44"), placedAt + 5_000), "/tpy StormAegis44"));
        assertEquals(AWAIT_DELIVERY, machine.state());

        assertFalse(anySent(machine.onChat(new ChatEvent.Tpa("ValorKnight27"), placedAt + 6_000)));
        assertEquals(AWAIT_DELIVERY, machine.state());
    }

    @Test
    void eventsFromOtherCourierIgnoredOnceFixed() {
        long placedAt = placeOrder();
        machine.onChat(new ChatEvent.Tpa("StormAegis44"), placedAt + 40_000);
        machine.onChat(new ChatEvent.Done("ValorKnight27"), placedAt + 50_000);
        assertEquals(AWAIT_DELIVERY, machine.state());
        assertTrue(progress.delivered.isEmpty());
    }

    @Test
    void deliveryTimeoutGoesToUnconfirmed() {
        long placedAt = placeOrder();
        machine.onChat(new ChatEvent.Tpa("StormAegis44"), placedAt + 40_000);
        machine.tick(placedAt + 40_000 + OrderMachine.DELIVERY_TIMEOUT_MS, OK);
        assertEquals(List.of(List.of(1, 2, 3, 4, 5)), progress.unconfirmed);
        assertEquals(IDLE, machine.state());
    }

    @Test
    void pausesWhenInventoryFullAndResumes() {
        machine.onJoin(T0);
        long t = T0 + OrderMachine.JOIN_GRACE_MS;
        List<Action> out = machine.tick(t, new OrderMachine.Context(true, true, 4, false, false));
        assertFalse(anySent(out));
        assertTrue(alerts(out, Msg.of(KitText.INVENTORY_FULL_PAUSED, "slots", 5)));
        assertEquals(PAUSED, machine.state());
        machine.tick(t + 1_000, OK);
        assertEquals(IDLE, machine.state());
        assertTrue(sent(machine.tick(t + 2_000, OK), ORDER_1_5));
    }

    @Test
    void depositsWhenAutoEnderAndInReach() {
        config = new OrderMachine.Config(300_000, COURIERS, false, true);
        machine.onJoin(T0);
        long t = T0 + OrderMachine.JOIN_GRACE_MS;
        assertTrue(machine.tick(t, new OrderMachine.Context(true, true, 2, true, false)).contains(new Action.Deposit()));
        assertEquals(DEPOSIT, machine.state());
        machine.onDepositResult(true, 36);
        assertEquals(IDLE, machine.state());
    }

    @Test
    void doesNotDepositWhenAScreenIsAlreadyOpen() {
        // Critical: requesting the deposit with a screen open by hand is what empties shulkers into
        // the wrong container (spec §6.1). OrderMachine must sit still, without pausing, and retry on
        // a later tick; but it must warn once about why it is not requesting kits.
        config = new OrderMachine.Config(300_000, COURIERS, false, true);
        machine.onJoin(T0);
        long t = T0 + OrderMachine.JOIN_GRACE_MS;
        List<Action> out = machine.tick(t, new OrderMachine.Context(true, true, 2, true, true));
        assertFalse(anySent(out));
        assertFalse(out.contains(new Action.Deposit()));
        assertEquals(1, out.stream().filter(a -> a instanceof Action.Notify).count(),
            "warns exactly once that it is waiting for the screen to close");
        assertEquals(IDLE, machine.state());

        // A second tick with the screen still open does not repeat the warning.
        List<Action> again = machine.tick(t + 500, new OrderMachine.Context(true, true, 2, true, true));
        assertTrue(again.isEmpty(), "the warning does not repeat while the situation has not changed");

        // As soon as the screen closes, the same tick retries normally.
        assertTrue(machine.tick(t + 1_000, new OrderMachine.Context(true, true, 2, true, false))
            .contains(new Action.Deposit()));
        assertEquals(DEPOSIT, machine.state());
    }

    @Test
    void screenOpenNoticeRepeatsIfTheBlockReturnsAfterClearing() {
        // The "only once" warning is per blocking episode, not forever: if the screen closes (stops
        // blocking) and opens again later, it has to warn again.
        config = new OrderMachine.Config(300_000, COURIERS, false, true);
        machine.onJoin(T0);
        long t = T0 + OrderMachine.JOIN_GRACE_MS;
        List<Action> first = machine.tick(t, new OrderMachine.Context(true, true, 2, true, true));
        assertEquals(1, first.stream().filter(a -> a instanceof Action.Notify).count());

        // It closes without actually depositing (slots still insufficient, screen no longer open):
        // still blocked by slots, but no longer by the screen, so it retries without repeating the warning.
        List<Action> closed = machine.tick(t + 100, new OrderMachine.Context(true, true, 2, true, false));
        assertTrue(closed.contains(new Action.Deposit()));

        machine.onDepositResult(false, 2); // aborts, goes back to IDLE (retries, spec §6.1)
        assertEquals(IDLE, machine.state());

        // A screen opens again: it is a new situation, so it warns again.
        List<Action> again = machine.tick(t + 200, new OrderMachine.Context(true, true, 2, true, true));
        assertEquals(1, again.stream().filter(a -> a instanceof Action.Notify).count(),
            "a new blocking episode warns again");
    }

    @Test
    void depositThatLeavesNoRoomPauses() {
        config = new OrderMachine.Config(300_000, COURIERS, false, true);
        machine.onJoin(T0);
        machine.tick(T0 + OrderMachine.JOIN_GRACE_MS, new OrderMachine.Context(true, true, 2, true, false));
        assertTrue(alerts(machine.onDepositResult(true, 3), Msg.of(KitText.DEPOSIT_NO_ROOM)));
        assertEquals(PAUSED, machine.state());
    }

    @Test
    void depositAbortRetriesInsteadOfPausing() {
        // CRITICAL, fixed: an abort (unrelated interaction, timeout...) used to pause the module, and
        // PAUSED can only be left with enough slots -exactly what the deposit was going to achieve.
        // An abort must retry; PAUSED is reserved for when there really is no way to deposit (spec §6.1).
        config = new OrderMachine.Config(300_000, COURIERS, false, true);
        machine.onJoin(T0);
        machine.tick(T0 + OrderMachine.JOIN_GRACE_MS, new OrderMachine.Context(true, true, 2, true, false));
        assertEquals(DEPOSIT, machine.state());

        List<Action> out = machine.onDepositResult(false, 2);
        assertEquals(IDLE, machine.state(), "an abort retries, it does not pause");
        assertFalse(out.stream().anyMatch(a -> a instanceof Action.Notify),
            "a silent retry must not produce a warning for every failed attempt");

        // The next tick, with the ender still in reach, tries the deposit again on its own.
        assertTrue(machine.tick(T0 + OrderMachine.JOIN_GRACE_MS + 50, new OrderMachine.Context(true, true, 2, true, false))
            .contains(new Action.Deposit()));
        assertEquals(DEPOSIT, machine.state());
    }

    @Test
    void depositAbortPausesWithStrongNoticeAfterThreeConsecutiveAborts() {
        // CRITICAL, fixed: an uncapped retry is silent in the face of a persistent cause (a covered
        // ender chest never opens a screen). On the third abort in a row, PAUSED with a loud warning
        // -the same one that used to fire before the abort started retrying- instead of a silent loop.
        config = new OrderMachine.Config(300_000, COURIERS, false, true);
        machine.onJoin(T0);
        OrderMachine.Context reach = new OrderMachine.Context(true, true, 2, true, false);

        Msg abortsExceeded = Msg.of(KitText.DEPOSIT_ABORTS_EXCEEDED, "max", OrderMachine.MAX_DEPOSIT_ABORTS);

        machine.tick(T0 + OrderMachine.JOIN_GRACE_MS, reach);
        assertEquals(DEPOSIT, machine.state());
        assertFalse(alerts(machine.onDepositResult(false, 2), abortsExceeded), "first abort: no warning");
        assertEquals(IDLE, machine.state());

        machine.tick(T0 + OrderMachine.JOIN_GRACE_MS + 10, reach);
        assertEquals(DEPOSIT, machine.state());
        assertFalse(alerts(machine.onDepositResult(false, 2), abortsExceeded), "second abort: no warning");
        assertEquals(IDLE, machine.state());

        machine.tick(T0 + OrderMachine.JOIN_GRACE_MS + 20, reach);
        assertEquals(DEPOSIT, machine.state());
        List<Action> third = machine.onDepositResult(false, 2);
        assertTrue(alerts(third, abortsExceeded), "third abort in a row: loud warning");
        assertEquals(PAUSED, machine.state());
    }

    @Test
    void aSuccessfulDepositResetsTheConsecutiveAbortCounter() {
        config = new OrderMachine.Config(300_000, COURIERS, false, true);
        machine.onJoin(T0);
        OrderMachine.Context reach = new OrderMachine.Context(true, true, 2, true, false);

        // Two aborts, then a successful deposit: the counter is forgotten.
        machine.tick(T0 + OrderMachine.JOIN_GRACE_MS, reach);
        machine.onDepositResult(false, 2);
        machine.tick(T0 + OrderMachine.JOIN_GRACE_MS + 10, reach);
        machine.onDepositResult(false, 2);

        machine.tick(T0 + OrderMachine.JOIN_GRACE_MS + 20, reach);
        machine.onDepositResult(true, 36);
        assertEquals(IDLE, machine.state());

        // Two more aborts after the success should not be enough to pause (the counter reset to 0).
        machine.tick(T0 + OrderMachine.JOIN_GRACE_MS + 30, reach);
        machine.onDepositResult(false, 2);
        machine.tick(T0 + OrderMachine.JOIN_GRACE_MS + 40, reach);
        List<Action> out = machine.onDepositResult(false, 2);
        assertFalse(alerts(out, Msg.of(KitText.DEPOSIT_ABORTS_EXCEEDED, "max", OrderMachine.MAX_DEPOSIT_ABORTS)));
        assertEquals(IDLE, machine.state(), "the counter reset after the success, so it keeps retrying");
    }

    @Test
    void depositAbortPausesWhenTheEnderIsNoLongerReachable() {
        // If there really is no way to deposit anymore -the ender went out of reach, or someone
        // broke it- idle() is the one that pauses, with the same insufficient-slots warning as always.
        config = new OrderMachine.Config(300_000, COURIERS, false, true);
        machine.onJoin(T0);
        machine.tick(T0 + OrderMachine.JOIN_GRACE_MS, new OrderMachine.Context(true, true, 2, true, false));
        assertEquals(DEPOSIT, machine.state());

        machine.onDepositResult(false, 2);
        assertEquals(IDLE, machine.state());

        List<Action> out = machine.tick(T0 + OrderMachine.JOIN_GRACE_MS + 50, new OrderMachine.Context(true, true, 2, false, false));
        assertTrue(alerts(out, Msg.of(KitText.INVENTORY_FULL_PAUSED, "slots", 5)));
        assertEquals(PAUSED, machine.state());
    }

    @Test
    void finishesWhenQueueEmpty() {
        progress.delivered.addAll(List.of(1, 2, 3, 4, 5, 6, 7));
        machine.onJoin(T0);
        List<Action> out = machine.tick(T0 + OrderMachine.JOIN_GRACE_MS, OK);
        assertTrue(out.stream().anyMatch(a -> a instanceof Action.Disable));
        assertEquals(FINISHED, machine.state());
    }

    @Test
    void resumesLiveOrderAfterReconnect() {
        progress.activeOrder = new Progress.ActiveOrder(List.of(1, 2, 3, 4, 5), T0, null);
        machine.onJoin(T0 + 60_000);
        assertEquals(AWAIT_COURIER, machine.state());
        assertTrue(sent(machine.onChat(new ChatEvent.Tpa("StormAegis44"), T0 + 75_000), "/tpy StormAegis44"));
    }

    @Test
    void requeuesExpiredOrderAfterReconnect() {
        progress.activeOrder = new Progress.ActiveOrder(List.of(1, 2, 3, 4, 5), T0, "StormAegis44");
        machine.onJoin(T0 + 200_000);
        assertEquals(IDLE, machine.state());
        assertNull(progress.activeOrder);
        assertTrue(progress.failures.isEmpty());
        assertFalse(anySent(machine.tick(T0 + 200_000 + OrderMachine.JOIN_GRACE_MS, OK)));
        assertTrue(sent(machine.tick(T0 + 300_000, OK), ORDER_1_5));
    }

    @Test
    void kickDuringConfirmDoesNotReorderBeforeInterval() {
        machine.onJoin(T0);
        long t = T0 + OrderMachine.JOIN_GRACE_MS;
        assertTrue(sent(machine.tick(t, OK), ORDER_1_5));

        machine.onJoin(t + 5_000);
        assertFalse(anySent(machine.tick(t + 5_000 + OrderMachine.JOIN_GRACE_MS, OK)));
        assertFalse(anySent(machine.tick(t + 299_999, OK)));
        assertTrue(sent(machine.tick(t + 300_000, OK), ORDER_1_5));
    }
}
