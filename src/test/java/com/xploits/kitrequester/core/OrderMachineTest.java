package com.xploits.kitrequester.core;

import com.xploits.shared.chat.ChatEvent;
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

    private static boolean alerts(List<Action> actions, String fragment) {
        return actions.stream().anyMatch(a -> a instanceof Action.Notify n && n.alert() && n.message().contains(fragment));
    }

    /** Entra, espera el margen, pide el lote 1-5 y recibe PLACED. Devuelve el instante de PLACED. */
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

        // 13:52:03 — la TPA llega 1 s antes que el susurro READY
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
        assertTrue(later.stream().anyMatch(a -> a instanceof Action.Notify n && n.message().contains("Coordlogger3000")));
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
        assertTrue(alerts(out, "NewCourier1"));
    }

    @Test
    void unknownCourierAcceptedAndLearnedWhenTrusted() {
        config = new OrderMachine.Config(300_000, COURIERS, true, false);
        long placedAt = placeOrder();
        assertFalse(anySent(machine.onChat(new ChatEvent.Tpa("NewCourier1"), placedAt + 10_000)));
        List<Action> out = machine.onChat(new ChatEvent.Ready("NewCourier1"), placedAt + 11_000);
        assertTrue(sent(out, "/tpy NewCourier1"));
        assertTrue(out.contains(new Action.LearnCourier("NewCourier1")));
        assertTrue(alerts(out, "NewCourier1"));
        assertEquals(AWAIT_DELIVERY, machine.state());
    }

    @Test
    void overwritingPendingTpaNotifiesPreviousRequester() {
        long placedAt = placeOrder();
        assertFalse(anySent(machine.onChat(new ChatEvent.Tpa("First1"), placedAt + 1_000)));
        List<Action> out = machine.onChat(new ChatEvent.Tpa("Second2"), placedAt + 1_500);
        assertFalse(anySent(out));
        assertTrue(out.stream().anyMatch(a -> a instanceof Action.Notify n
            && !n.alert() && n.message().equals("TPA ignorada de First1.")));
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
        assertTrue(alerts(out, "huecos"));
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
        // Crítico: pedir el depósito con una pantalla abierta a mano es lo que vacía shulkers en
        // el contenedor equivocado (spec §6.1). OrderMachine debe quedarse quieto, sin pausar, y
        // reintentarlo en un tick posterior; pero sí debe avisar una vez de por qué no pide kits.
        config = new OrderMachine.Config(300_000, COURIERS, false, true);
        machine.onJoin(T0);
        long t = T0 + OrderMachine.JOIN_GRACE_MS;
        List<Action> out = machine.tick(t, new OrderMachine.Context(true, true, 2, true, true));
        assertFalse(anySent(out));
        assertFalse(out.contains(new Action.Deposit()));
        assertEquals(1, out.stream().filter(a -> a instanceof Action.Notify).count(),
            "avisa una sola vez de que espera a que se cierre la pantalla");
        assertEquals(IDLE, machine.state());

        // Un segundo tick con la pantalla todavía abierta no repite el aviso.
        List<Action> again = machine.tick(t + 500, new OrderMachine.Context(true, true, 2, true, true));
        assertTrue(again.isEmpty(), "el aviso no se repite mientras la situación no cambie");

        // En cuanto se cierra la pantalla, el mismo tick vuelve a intentarlo con normalidad.
        assertTrue(machine.tick(t + 1_000, new OrderMachine.Context(true, true, 2, true, false))
            .contains(new Action.Deposit()));
        assertEquals(DEPOSIT, machine.state());
    }

    @Test
    void screenOpenNoticeRepeatsIfTheBlockReturnsAfterClearing() {
        // El aviso "una sola vez" es por episodio de bloqueo, no para siempre: si la pantalla se
        // cierra (deja de bloquear) y vuelve a abrirse más tarde, tiene que avisar de nuevo.
        config = new OrderMachine.Config(300_000, COURIERS, false, true);
        machine.onJoin(T0);
        long t = T0 + OrderMachine.JOIN_GRACE_MS;
        List<Action> first = machine.tick(t, new OrderMachine.Context(true, true, 2, true, true));
        assertEquals(1, first.stream().filter(a -> a instanceof Action.Notify).count());

        // Se cierra sin llegar a depositar (huecos siguen insuficientes, pantalla ya no abierta):
        // sigue bloqueado por huecos, pero ya no por pantalla, así que se reintenta sin repetir aviso.
        List<Action> closed = machine.tick(t + 100, new OrderMachine.Context(true, true, 2, true, false));
        assertTrue(closed.contains(new Action.Deposit()));

        machine.onDepositResult(false, 2); // aborta, vuelve a IDLE (reintenta, spec §6.1)
        assertEquals(IDLE, machine.state());

        // Se vuelve a abrir una pantalla: es una situación nueva, así que avisa otra vez.
        List<Action> again = machine.tick(t + 200, new OrderMachine.Context(true, true, 2, true, true));
        assertEquals(1, again.stream().filter(a -> a instanceof Action.Notify).count(),
            "un nuevo episodio de bloqueo vuelve a avisar");
    }

    @Test
    void depositThatLeavesNoRoomPauses() {
        config = new OrderMachine.Config(300_000, COURIERS, false, true);
        machine.onJoin(T0);
        machine.tick(T0 + OrderMachine.JOIN_GRACE_MS, new OrderMachine.Context(true, true, 2, true, false));
        assertTrue(alerts(machine.onDepositResult(true, 3), "huecos"));
        assertEquals(PAUSED, machine.state());
    }

    @Test
    void depositAbortRetriesInsteadOfPausing() {
        // CRÍTICO, corregido: antes un aborto (interacción ajena, timeout...) pausaba el módulo, y
        // de PAUSED solo se sale con huecos suficientes -justo lo que el depósito iba a conseguir.
        // Un aborto debe reintentar; PAUSED se reserva para cuando de verdad no hay forma de
        // depositar (spec §6.1).
        config = new OrderMachine.Config(300_000, COURIERS, false, true);
        machine.onJoin(T0);
        machine.tick(T0 + OrderMachine.JOIN_GRACE_MS, new OrderMachine.Context(true, true, 2, true, false));
        assertEquals(DEPOSIT, machine.state());

        List<Action> out = machine.onDepositResult(false, 2);
        assertEquals(IDLE, machine.state(), "un aborto reintenta, no pausa");
        assertFalse(out.stream().anyMatch(a -> a instanceof Action.Notify),
            "un reintento silencioso no debe generar aviso por cada intento fallido");

        // El siguiente tick, con el ender todavía al alcance, vuelve a intentar el depósito solo.
        assertTrue(machine.tick(T0 + OrderMachine.JOIN_GRACE_MS + 50, new OrderMachine.Context(true, true, 2, true, false))
            .contains(new Action.Deposit()));
        assertEquals(DEPOSIT, machine.state());
    }

    @Test
    void depositAbortPausesWhenTheEnderIsNoLongerReachable() {
        // Si de verdad ya no hay forma de depositar -el ender salió de alcance, o alguien lo rompió-
        // idle() es quien pausa, con el mismo aviso de siempre de huecos insuficientes.
        config = new OrderMachine.Config(300_000, COURIERS, false, true);
        machine.onJoin(T0);
        machine.tick(T0 + OrderMachine.JOIN_GRACE_MS, new OrderMachine.Context(true, true, 2, true, false));
        assertEquals(DEPOSIT, machine.state());

        machine.onDepositResult(false, 2);
        assertEquals(IDLE, machine.state());

        List<Action> out = machine.tick(T0 + OrderMachine.JOIN_GRACE_MS + 50, new OrderMachine.Context(true, true, 2, false, false));
        assertTrue(alerts(out, "huecos"));
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
