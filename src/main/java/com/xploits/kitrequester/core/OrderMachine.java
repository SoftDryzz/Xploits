package com.xploits.kitrequester.core;

import com.xploits.shared.chat.ChatEvent;
import com.xploits.shared.chat.ChatPatterns;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Máquina de estados del spec §4. Pura: recibe la hora, el contexto del juego y los eventos de chat,
 * modifica {@link Progress} y devuelve las {@link Action}s que KitRequester debe ejecutar.
 */
public final class OrderMachine {
    public enum State { IDLE, AWAIT_CONFIRM, AWAIT_COURIER, AWAIT_DELIVERY, DEPOSIT, PAUSED, FINISHED, ERROR }

    public record Context(boolean inWorld, boolean kitbotOnline, int freeSlots, boolean enderInReach, boolean screenOpen) {}

    public record Config(long intervalMs, Set<String> knownCouriers, boolean trustUnknownCouriers, boolean autoEnder) {
        public Config {
            intervalMs = Math.max(intervalMs, MIN_INTERVAL_MS);
        }
    }

    public static final long MIN_INTERVAL_MS = 300_000;
    public static final long CONFIRM_TIMEOUT_MS = 20_000;
    public static final long COURIER_TIMEOUT_MS = 180_000;
    public static final long DELIVERY_TIMEOUT_MS = 90_000;
    public static final long PENDING_TPA_MS = 5_000;
    public static final long JOIN_GRACE_MS = 10_000;
    public static final long COOLDOWN_MARGIN_MS = 20_000;
    public static final long CONFIRM_RETRY_MS = 60_000;
    public static final int MAX_FAILURES = 3;

    private final KitQueue queue;
    private final Progress progress;
    private final Supplier<Config> config;
    private final LongSupplier jitterMs;

    private State state = State.IDLE;
    private long deadline;
    private long resumeAt;
    private List<Integer> batch = List.of();
    private String courier;
    private boolean partialSeen;
    private final Map<String, Long> readySeen = new HashMap<>();
    private String pendingTpa;
    private long pendingTpaUntil;
    /**
     * Si ya se avisó de que hay una pantalla abierta bloqueando el depósito (spec §6.1). Sin esto,
     * {@link #idle} calla cada tick mientras dure la pantalla abierta: {@code status()} sigue
     * diciendo {@code IDLE} y no hay ninguna línea que explique por qué no se piden kits. Se avisa
     * una sola vez y se olvida en cuanto la situación deja de bloquear.
     */
    private boolean screenOpenBlockNotified;

    public OrderMachine(KitQueue queue, Progress progress, Supplier<Config> config, LongSupplier jitterMs) {
        this.queue = queue;
        this.progress = progress;
        this.config = config;
        this.jitterMs = jitterMs;
    }

    public State state() {
        return state;
    }

    /** Al activar el módulo o al entrar al servidor (spec §9). */
    public List<Action> onJoin(long now) {
        resumeAt = now + JOIN_GRACE_MS;
        clearWindow();
        screenOpenBlockNotified = false;
        Progress.ActiveOrder active = progress.activeOrder;
        if (active != null && now - active.placedAt() < COURIER_TIMEOUT_MS) {
            batch = active.ids();
            state = State.AWAIT_COURIER;
            deadline = active.placedAt() + COURIER_TIMEOUT_MS;
        } else {
            if (active != null) {
                progress.nextOrderAt = Math.max(progress.nextOrderAt,
                    active.placedAt() + config.get().intervalMs() + jitterMs.getAsLong());
            }
            progress.activeOrder = null;
            batch = List.of();
            state = State.IDLE;
        }
        return List.of(new Action.Save());
    }

    public List<Action> tick(long now, Context ctx) {
        List<Action> out = new ArrayList<>();
        if (!ctx.inWorld() || now < resumeAt) return out;

        switch (state) {
            case IDLE -> idle(now, ctx, out);
            case PAUSED -> {
                if (ctx.freeSlots() >= nextBatch().size()) state = State.IDLE;
            }
            case AWAIT_CONFIRM -> {
                if (now >= deadline) {
                    batch = List.of();
                    state = State.IDLE;
                    progress.nextOrderAt = now + CONFIRM_RETRY_MS;
                    out.add(new Action.Notify("SnifferBuddy no respondió; reintento en 60 s.", false));
                    out.add(new Action.Save());
                }
            }
            case AWAIT_COURIER -> {
                if (pendingTpa != null && now >= pendingTpaUntil) decidePending(now, true, out);
                if (state == State.AWAIT_COURIER && now >= deadline) fail(now, "El courier no llegó a tiempo.", out);
            }
            case AWAIT_DELIVERY -> {
                if (now >= deadline) {
                    progress.unconfirmed.add(batch);
                    endOrder(now, "Entrega sin confirmar (a revisar): " + batch, out);
                }
            }
            default -> {
                // DEPOSIT espera a onDepositResult; FINISHED y ERROR son finales.
            }
        }
        return out;
    }

    public List<Action> onChat(ChatEvent event, long now) {
        List<Action> out = new ArrayList<>();
        switch (event) {
            case ChatEvent.Placed placed -> {
                if (state == State.AWAIT_CONFIRM) {
                    progress.activeOrder = new Progress.ActiveOrder(batch, now, null);
                    enterCourierWindow(now, out);
                }
            }
            case ChatEvent.Active active -> {
                if (state == State.AWAIT_CONFIRM) {
                    if (progress.activeOrder != null) batch = progress.activeOrder.ids();
                    else progress.activeOrder = new Progress.ActiveOrder(batch, now, null);
                    enterCourierWindow(now, out);
                }
            }
            case ChatEvent.Cooldown cooldown -> {
                if (state == State.AWAIT_CONFIRM) {
                    batch = List.of();
                    state = State.IDLE;
                    progress.nextOrderAt = now + cooldown.millis() + COOLDOWN_MARGIN_MS;
                    out.add(new Action.Notify("SnifferBuddy en cooldown; siguiente intento en "
                        + (cooldown.millis() + COOLDOWN_MARGIN_MS) / 1000 + " s.", false));
                    out.add(new Action.Save());
                }
            }
            case ChatEvent.Unregistered unregistered -> {
                if (state == State.AWAIT_CONFIRM) error("SnifferBuddy dice que la cuenta no está registrada.", out);
            }
            case ChatEvent.Usage usage -> {
                if (state == State.AWAIT_CONFIRM) error("SnifferBuddy no entendió el pedido: " + ChatPatterns.orderCommand(batch), out);
            }
            case ChatEvent.UnknownKitbot unknown -> out.add(new Action.Notify("SnifferBuddy: " + unknown.text(), false));
            case ChatEvent.Ready ready -> {
                if (state == State.AWAIT_COURIER) {
                    readySeen.put(ready.courier(), now);
                    if (ready.courier().equals(pendingTpa)) decidePending(now, false, out);
                }
            }
            case ChatEvent.Tpa tpa -> onTpa(tpa.requester(), now, out);
            case ChatEvent.Partial partial -> {
                if (isFromCourier(partial.courier())) partialSeen = true;
            }
            case ChatEvent.Done done -> {
                if (isFromCourier(done.courier())) complete(now, out);
            }
            case ChatEvent.NotFound notFound -> {
                if (isFromCourier(notFound.courier())) {
                    progress.notFound.addAll(batch);
                    endOrder(now, "Kits no encontrados: " + batch, out);
                }
            }
            case ChatEvent.TimedOut timedOut -> {
                if (isFromCourier(timedOut.courier())) fail(now, "El courier canceló: la TPA caducó.", out);
            }
        }
        return out;
    }

    public List<Action> onDepositResult(boolean ok, int freeSlots) {
        List<Action> out = new ArrayList<>();
        if (state != State.DEPOSIT) return out;
        if (ok && freeSlots >= nextBatch().size()) {
            state = State.IDLE;
            out.add(new Action.Notify("Shulkers guardados en el ender chest.", false));
        } else if (ok) {
            // Se usó el ender chest de verdad y aun así no hay huecos: reintentarlo no cambiaría
            // nada, así que aquí sí es un PAUSED de verdad (spec §6.1).
            state = State.PAUSED;
            out.add(new Action.Notify("No quedan huecos suficientes tras usar el ender chest: pausado.", true));
        } else {
            // Un aborto (interacción ajena descartada por EnderDepositor, timeout, syncId que ya no
            // coincide...) no significa que sea imposible depositar, solo que este intento concreto
            // no pudo. Se vuelve a IDLE para reintentar, en vez de pausar como antes: con el aborto
            // pausando, un clic derecho ajeno cualquiera dejaba el módulo parado hasta vaciar el
            // inventario a mano, justo lo que el depósito iba a conseguir (spec §6.1). Si de verdad
            // ya no hay forma de depositar -el ender ya no está al alcance, autoEnder se apagó-,
            // idle() lo pausará él mismo con el aviso de huecos, esta vez de verdad sin salida.
            state = State.IDLE;
        }
        return out;
    }

    public String status(long now) {
        long waitSeconds = Math.max(0, progress.nextOrderAt - now) / 1000;
        return "Estado: " + state
            + " | pedido: " + (batch.isEmpty() ? "-" : batch)
            + (courier != null ? " | courier: " + courier : "")
            + " | pendientes: " + queue.pending(progress.resolved()).size()
            + " | entregados: " + progress.delivered.size()
            + " | siguiente pedido en: " + waitSeconds + " s";
    }

    private void idle(long now, Context ctx, List<Action> out) {
        List<Integer> next = nextBatch();
        if (next.isEmpty()) {
            state = State.FINISHED;
            out.add(new Action.Notify("Cola terminada: no quedan kits pendientes.", true));
            out.add(new Action.Save());
            out.add(new Action.Disable("cola terminada"));
            return;
        }
        if (now < progress.nextOrderAt || !ctx.kitbotOnline()) return;

        if (ctx.freeSlots() < next.size()) {
            boolean canAutoDeposit = config.get().autoEnder() && ctx.enderInReach();
            if (canAutoDeposit && ctx.screenOpen()) {
                // Hay una pantalla abierta a mano: pedir el depósito ahora es lo que vacía shulkers
                // en el contenedor equivocado (spec §6.1). No se pausa, solo se reintenta en un tick
                // posterior, cuando el jugador haya cerrado lo que tuviera abierto. Se avisa una sola
                // vez de que se está esperando, para que status() no calle en silencio (spec §6.1).
                if (!screenOpenBlockNotified) {
                    screenOpenBlockNotified = true;
                    out.add(new Action.Notify(
                        "Inventario lleno y hay una pantalla abierta: espero a que la cierres para depositar.", false));
                }
                return;
            }
            screenOpenBlockNotified = false;
            if (canAutoDeposit) {
                state = State.DEPOSIT;
                out.add(new Action.Deposit());
            } else {
                state = State.PAUSED;
                out.add(new Action.Notify("Inventario lleno: pausado hasta tener " + next.size() + " huecos libres.", true));
            }
            return;
        }

        screenOpenBlockNotified = false;
        batch = next;
        state = State.AWAIT_CONFIRM;
        deadline = now + CONFIRM_TIMEOUT_MS;
        out.add(new Action.SendCommand(ChatPatterns.orderCommand(next)));
        progress.nextOrderAt = now + config.get().intervalMs();
        out.add(new Action.Save());
    }

    private void onTpa(String requester, long now, List<Action> out) {
        if (state != State.AWAIT_COURIER) {
            out.add(new Action.Notify("TPA ignorada de " + requester + " (no hay pedido esperando courier).", false));
            return;
        }
        Config cfg = config.get();
        boolean ready = readySeen.containsKey(requester);
        if (CourierPolicy.shouldAccept(requester, ready, cfg.knownCouriers(), cfg.trustUnknownCouriers())) {
            accept(requester, now, out);
        } else if (!ready && !cfg.knownCouriers().contains(requester)) {
            if (pendingTpa != null && !pendingTpa.equals(requester)) {
                out.add(new Action.Notify("TPA ignorada de " + pendingTpa + ".", false));
            }
            // Su READY puede llegar justo después de la TPA: se decide al llegar o a los 5 s.
            pendingTpa = requester;
            pendingTpaUntil = now + PENDING_TPA_MS;
        } else {
            rejectNotice(requester, ready, out);
        }
    }

    private void decidePending(long now, boolean expired, List<Action> out) {
        String requester = pendingTpa;
        Config cfg = config.get();
        boolean ready = readySeen.containsKey(requester);
        if (CourierPolicy.shouldAccept(requester, ready, cfg.knownCouriers(), cfg.trustUnknownCouriers())) {
            pendingTpa = null;
            accept(requester, now, out);
        } else if (expired || ready) {
            pendingTpa = null;
            rejectNotice(requester, ready, out);
        }
    }

    private void rejectNotice(String requester, boolean ready, List<Action> out) {
        if (ready) {
            out.add(new Action.Notify("Courier desconocido " + requester
                + " no aceptado; añádelo a known-couriers si es legítimo.", true));
        } else {
            out.add(new Action.Notify("TPA ignorada de " + requester + ".", false));
        }
    }

    private void accept(String requester, long now, List<Action> out) {
        courier = requester;
        state = State.AWAIT_DELIVERY;
        deadline = now + DELIVERY_TIMEOUT_MS;
        if (progress.activeOrder != null) progress.activeOrder = progress.activeOrder.withCourier(requester);
        out.add(new Action.SendCommand(ChatPatterns.acceptCommand(requester)));
        if (!config.get().knownCouriers().contains(requester)) {
            out.add(new Action.LearnCourier(requester));
            out.add(new Action.Notify("Courier nuevo " + requester
                + " aceptado y añadido a known-couriers (trust-unknown-couriers activo).", true));
        }
        out.add(new Action.Save());
    }

    /** Regla de origen (spec §4): el courier fijado, o uno conocido mientras no hay ninguno fijado. */
    private boolean isFromCourier(String name) {
        return switch (state) {
            case AWAIT_DELIVERY -> name.equals(courier);
            case AWAIT_COURIER -> config.get().knownCouriers().contains(name);
            default -> false;
        };
    }

    private void complete(long now, List<Action> out) {
        if (partialSeen) progress.partial.add(batch);
        else progress.delivered.addAll(batch);
        endOrder(now, (partialSeen ? "Entrega parcial: " : "Entregado: ") + batch, out);
    }

    /** TIMED_OUT o timeout del courier: suma un fallo por ID; con MAX_FAILURES pasa a skipped. */
    private void fail(long now, String message, List<Action> out) {
        for (int id : batch) {
            int failures = progress.failures.merge(id, 1, Integer::sum);
            if (failures >= MAX_FAILURES) progress.skipped.add(id);
        }
        endOrder(now, message + " Lote " + batch + " de vuelta a la cola.", out);
    }

    private void endOrder(long now, String message, List<Action> out) {
        long placedAt = progress.activeOrder != null ? progress.activeOrder.placedAt() : now;
        progress.nextOrderAt = placedAt + config.get().intervalMs() + jitterMs.getAsLong();
        progress.activeOrder = null;
        batch = List.of();
        clearWindow();
        state = State.IDLE;
        out.add(new Action.Notify(message, false));
        out.add(new Action.Save());
    }

    private void enterCourierWindow(long now, List<Action> out) {
        clearWindow();
        state = State.AWAIT_COURIER;
        deadline = now + COURIER_TIMEOUT_MS;
        out.add(new Action.Save());
    }

    private void error(String message, List<Action> out) {
        batch = List.of();
        state = State.ERROR;
        out.add(new Action.Notify(message, true));
        out.add(new Action.Save());
        out.add(new Action.Disable(message));
    }

    private List<Integer> nextBatch() {
        return queue.nextBatch(progress.resolved());
    }

    private void clearWindow() {
        readySeen.clear();
        pendingTpa = null;
        courier = null;
        partialSeen = false;
    }
}
