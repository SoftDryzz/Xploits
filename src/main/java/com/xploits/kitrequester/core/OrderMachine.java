package com.xploits.kitrequester.core;

import com.xploits.shared.chat.ChatEvent;
import com.xploits.shared.chat.ChatPatterns;
import com.xploits.shared.core.i18n.Msg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * State machine from spec §4. Pure: it receives the time, the game context and the chat events,
 * mutates {@link Progress} and returns the {@link Action}s that KitRequester must execute.
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
    /**
     * Consecutive aborts of {@link com.xploits.kitrequester.inventory.EnderDepositor} before moving
     * to {@code PAUSED} with a loud warning, instead of retrying silently forever (spec §6.1, fixed:
     * the uncapped retry turned a loud failure into a silent one). An ender chest covered by a solid
     * block above it never opens a screen -verified in the bytecode of {@code EnderChestBlock.onUse}-,
     * so with {@code findInReach} not filtering that out the cycle was: request a deposit, wait 5 s,
     * abort, retry, forever, with the state still saying {@code IDLE} and no toast, sound or chat
     * line. A stray unrelated click -the original reason for an abort to retry instead of pausing-
     * keeps retrying without this warning: the counter is forgotten on any successful deposit, so
     * only a persistent cause exhausts it.
     */
    public static final int MAX_DEPOSIT_ABORTS = 3;

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
     * Whether the open-screen-blocking-the-deposit warning has already been given (spec §6.1).
     * Without this, {@link #idle} stays silent every tick for as long as the screen stays open:
     * {@code status()} keeps saying {@code IDLE} and there is no line explaining why kits are not
     * being requested. It warns once and forgets as soon as the situation stops blocking.
     */
    private boolean screenOpenBlockNotified;
    /** Consecutive EnderDepositor aborts. Forgotten on any successful deposit (spec §6.1). */
    private int consecutiveDepositAborts;

    public OrderMachine(KitQueue queue, Progress progress, Supplier<Config> config, LongSupplier jitterMs) {
        this.queue = queue;
        this.progress = progress;
        this.config = config;
        this.jitterMs = jitterMs;
    }

    public State state() {
        return state;
    }

    /** On module activation or on joining the server (spec §9). */
    public List<Action> onJoin(long now) {
        resumeAt = now + JOIN_GRACE_MS;
        clearWindow();
        screenOpenBlockNotified = false;
        consecutiveDepositAborts = 0;
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
                    out.add(new Action.Notify(Msg.of(KitText.CONFIRM_TIMEOUT_NOTICE), false));
                    out.add(new Action.Save());
                }
            }
            case AWAIT_COURIER -> {
                if (pendingTpa != null && now >= pendingTpaUntil) decidePending(now, true, out);
                if (state == State.AWAIT_COURIER && now >= deadline) fail(now, KitText.FAIL_COURIER_LATE, out);
            }
            case AWAIT_DELIVERY -> {
                if (now >= deadline) {
                    progress.unconfirmed.add(batch);
                    endOrder(now, Msg.of(KitText.DELIVERY_UNCONFIRMED, "batch", batch), out);
                }
            }
            default -> {
                // DEPOSIT waits for onDepositResult; FINISHED and ERROR are final.
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
                    long waitMs = cooldown.millis() + COOLDOWN_MARGIN_MS;
                    progress.nextOrderAt = now + waitMs;
                    out.add(new Action.Notify(Msg.of(KitText.COOLDOWN_NOTICE, "seconds", waitMs / 1000), false));
                    out.add(new Action.Save());
                }
            }
            case ChatEvent.Unregistered unregistered -> {
                if (state == State.AWAIT_CONFIRM) error(Msg.of(KitText.UNREGISTERED), out);
            }
            case ChatEvent.Usage usage -> {
                if (state == State.AWAIT_CONFIRM) {
                    error(Msg.of(KitText.USAGE_ERROR, "command", ChatPatterns.orderCommand(batch)), out);
                }
            }
            case ChatEvent.UnknownKitbot unknown -> out.add(new Action.Notify(Msg.of(KitText.UNKNOWN_KITBOT, "text", unknown.text()), false));
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
                    endOrder(now, Msg.of(KitText.NOT_FOUND, "batch", batch), out);
                }
            }
            case ChatEvent.TimedOut timedOut -> {
                if (isFromCourier(timedOut.courier())) fail(now, KitText.FAIL_COURIER_CANCELLED, out);
            }
        }
        return out;
    }

    public List<Action> onDepositResult(boolean ok, int freeSlots) {
        List<Action> out = new ArrayList<>();
        if (state != State.DEPOSIT) return out;
        if (ok && freeSlots >= nextBatch().size()) {
            state = State.IDLE;
            consecutiveDepositAborts = 0;
            out.add(new Action.Notify(Msg.of(KitText.DEPOSIT_SAVED), false));
        } else if (ok) {
            // The ender chest really was used and there is still no room: retrying would not change
            // anything, so this is a genuine PAUSED (spec §6.1).
            state = State.PAUSED;
            consecutiveDepositAborts = 0;
            out.add(new Action.Notify(Msg.of(KitText.DEPOSIT_NO_ROOM), true));
        } else {
            // An abort (an unrelated interaction discarded by EnderDepositor, a timeout, a syncId
            // that no longer matches...) does not mean depositing is impossible, only that this
            // particular attempt could not. It goes back to IDLE to retry, instead of just pausing:
            // with every abort pausing, any unrelated right-click left the module stuck until the
            // inventory was emptied by hand, exactly what the deposit was meant to achieve (spec §6.1).
            //
            // But an uncapped retry is silent in the face of a persistent cause -an ender chest
            // covered by a solid block above it never opens a screen, so it would request, wait 5 s,
            // abort, and repeat forever with no warning at all (spec §6.1, fixed)-. After
            // MAX_DEPOSIT_ABORTS in a row it does pause, with the same loud warning that used to fire
            // before the abort started retrying; the counter is forgotten on any successful deposit,
            // so a stray unrelated click -the original reason to retry- never exhausts it.
            consecutiveDepositAborts++;
            if (consecutiveDepositAborts >= MAX_DEPOSIT_ABORTS) {
                consecutiveDepositAborts = 0;
                state = State.PAUSED;
                out.add(new Action.Notify(Msg.of(KitText.DEPOSIT_ABORTS_EXCEEDED, "max", MAX_DEPOSIT_ABORTS), true));
            } else {
                state = State.IDLE;
            }
        }
        return out;
    }

    public Msg status(long now) {
        long waitSeconds = Math.max(0, progress.nextOrderAt - now) / 1000;
        Object batchArg = batch.isEmpty() ? "-" : batch;
        Msg courierArg = courier != null ? Msg.of(KitText.STATUS_COURIER, "courier", courier) : Msg.of(KitText.NOTHING);
        return Msg.of(KitText.STATUS,
            "state", KitText.of(state),
            "batch", batchArg,
            "courier", courierArg,
            "pending", queue.pending(progress.resolved()).size(),
            "delivered", progress.delivered.size(),
            "wait", waitSeconds);
    }

    private void idle(long now, Context ctx, List<Action> out) {
        List<Integer> next = nextBatch();
        if (next.isEmpty()) {
            state = State.FINISHED;
            out.add(new Action.Notify(Msg.of(KitText.QUEUE_FINISHED), true));
            out.add(new Action.Save());
            out.add(new Action.Disable("queue-finished"));
            return;
        }
        if (now < progress.nextOrderAt || !ctx.kitbotOnline()) return;

        if (ctx.freeSlots() < next.size()) {
            boolean canAutoDeposit = config.get().autoEnder() && ctx.enderInReach();
            if (canAutoDeposit && ctx.screenOpen()) {
                // A screen is open by hand: requesting the deposit now is exactly what empties
                // shulkers into the wrong container (spec §6.1). It does not pause, it just retries
                // on a later tick, once the player has closed whatever they had open. It warns once
                // that it is waiting, so status() does not stay silent about it (spec §6.1).
                if (!screenOpenBlockNotified) {
                    screenOpenBlockNotified = true;
                    out.add(new Action.Notify(Msg.of(KitText.SCREEN_OPEN_BLOCKING), false));
                }
                return;
            }
            screenOpenBlockNotified = false;
            if (canAutoDeposit) {
                state = State.DEPOSIT;
                out.add(new Action.Deposit());
            } else {
                state = State.PAUSED;
                out.add(new Action.Notify(Msg.of(KitText.INVENTORY_FULL_PAUSED, "slots", next.size()), true));
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
            out.add(new Action.Notify(Msg.of(KitText.TPA_IGNORED_NO_ORDER, "requester", requester), false));
            return;
        }
        Config cfg = config.get();
        boolean ready = readySeen.containsKey(requester);
        if (CourierPolicy.shouldAccept(requester, ready, cfg.knownCouriers(), cfg.trustUnknownCouriers())) {
            accept(requester, now, out);
        } else if (!ready && !cfg.knownCouriers().contains(requester)) {
            if (pendingTpa != null && !pendingTpa.equals(requester)) {
                out.add(new Action.Notify(Msg.of(KitText.TPA_IGNORED, "requester", pendingTpa), false));
            }
            // Its READY may arrive right after the TPA: it is decided when it arrives, or at 5 s.
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
            out.add(new Action.Notify(Msg.of(KitText.UNKNOWN_COURIER_REJECTED, "requester", requester), true));
        } else {
            out.add(new Action.Notify(Msg.of(KitText.TPA_IGNORED, "requester", requester), false));
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
            out.add(new Action.Notify(Msg.of(KitText.NEW_COURIER_LEARNED, "requester", requester), true));
        }
        out.add(new Action.Save());
    }

    /** Origin rule (spec §4): the pinned courier, or a known one while none is pinned. */
    private boolean isFromCourier(String name) {
        return switch (state) {
            case AWAIT_DELIVERY -> name.equals(courier);
            case AWAIT_COURIER -> config.get().knownCouriers().contains(name);
            default -> false;
        };
    }

    private void complete(long now, List<Action> out) {
        if (partialSeen) {
            progress.partial.add(batch);
            endOrder(now, Msg.of(KitText.DELIVERY_PARTIAL, "batch", batch), out);
        } else {
            progress.delivered.addAll(batch);
            endOrder(now, Msg.of(KitText.DELIVERED, "batch", batch), out);
        }
    }

    /** TIMED_OUT or courier timeout: adds a failure per ID; at MAX_FAILURES it moves to skipped. */
    private void fail(long now, KitText reasonKey, List<Action> out) {
        for (int id : batch) {
            int failures = progress.failures.merge(id, 1, Integer::sum);
            if (failures >= MAX_FAILURES) progress.skipped.add(id);
        }
        endOrder(now, Msg.of(KitText.FAIL_NOTICE, "reason", Msg.of(reasonKey), "batch", batch), out);
    }

    private void endOrder(long now, Msg message, List<Action> out) {
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

    private void error(Msg message, List<Action> out) {
        batch = List.of();
        state = State.ERROR;
        out.add(new Action.Notify(message, true));
        out.add(new Action.Save());
        out.add(new Action.Disable(message.key().name()));
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
