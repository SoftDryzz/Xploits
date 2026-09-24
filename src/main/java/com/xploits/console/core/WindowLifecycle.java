package com.xploits.console.core;

import com.xploits.shared.core.i18n.Msg;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The life of a console window, from turning the module on until the window closes
 * (console spec §8). It decides; the adapter carries out the actions it returns.
 *
 * <p>Every launch carries an id: a {@code console.pid}, an exit or a close order from another launch
 * does not count. And closing with the X leaves nothing written (verified), so from here it
 * <b>cannot be told apart</b> from a kill, and the notice does not pretend otherwise.
 */
public final class WindowLifecycle {
    public static final long PID_WAIT_MS = 10_000;
    public static final long CLOSE_WAIT_MS = 3_000;

    public record Pid(long pid, long startMs, String launchId) {
        public String write() {
            return pid + "\t" + startMs + "\t" + launchId;
        }

        public static Optional<Pid> read(String content) {
            String[] c = content.lines().findFirst().orElse("").split("\t", -1);
            if (c.length != 3 || c[2].isEmpty()) return Optional.empty();
            try {
                return Optional.of(new Pid(Long.parseLong(c[0]), Long.parseLong(c[1]), c[2]));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
    }

    /** What the window leaves written when it goes: {@code user} if it quit from the menu, {@code error} if it crashed. */
    public record Exit(String type, String launchId, String detail) {
        public static final String USER = "user";
        public static final String ERROR = "error";

        public static String user(String launchId) {
            return USER + "\t" + launchId;
        }

        public static String error(String launchId, String detail) {
            return ERROR + "\t" + launchId + "\t" + Escape.escape(detail);
        }

        public static Optional<Exit> read(String content) {
            String[] c = content.lines().findFirst().orElse("").split("\t", -1);
            if (c.length == 2 && c[0].equals(USER)) return Optional.of(new Exit(USER, c[1], ""));
            if (c.length == 3 && c[0].equals(ERROR)) {
                try {
                    return Optional.of(new Exit(ERROR, c[1], Escape.unescape(c[2])));
                } catch (IllegalArgumentException e) {
                    return Optional.of(new Exit(ERROR, c[1], c[2]));
                }
            }
            return Optional.empty();
        }
    }

    /** What the adapter saw on this tick. {@code alive} says whether a pid is still alive and the same process. */
    public record Observation(long nowMs, Pid readPid, Predicate<Pid> alive, Exit exit) {
    }

    public sealed interface Action {
    }

    public record Launch(String launchId) implements Action {
    }

    public record WriteClose(String launchId) implements Action {
    }

    /** If the window is still alive after {@code waitMs}, it is killed. {@code pid} is null if it was not known yet. */
    public record WatchClose(String launchId, Long pid, long waitMs) implements Action {
    }

    public record Notify(Level level, Msg text) implements Action {
    }

    public record DisableModule() implements Action {
    }

    private enum Phase { OFF, PENDING, LAUNCHING, AWAITING_PID, ALIVE, CLOSING }

    private final Supplier<String> ids;
    private Phase phase = Phase.OFF;
    private String launchId;
    private String command;
    private long deadline;
    private Pid alive;
    private Pid closing;
    private boolean relaunch;

    public WindowLifecycle(Supplier<String> ids) {
        this.ids = Objects.requireNonNull(ids);
    }

    public boolean active() {
        return phase != Phase.OFF;
    }

    public List<Action> turnOn() {
        switch (phase) {
            case OFF -> phase = Phase.PENDING;
            case CLOSING -> relaunch = true;
            default -> {
            }
        }
        return List.of();
    }

    public List<Action> turnOff(long nowMs) {
        switch (phase) {
            case PENDING -> {
                forget();
                return List.of();
            }
            case LAUNCHING, AWAITING_PID, ALIVE -> {
                String launch = launchId;
                Long pid = alive == null ? null : alive.pid();
                closing = alive;
                launchId = null;
                command = null;
                alive = null;
                phase = Phase.CLOSING;
                deadline = nowMs + CLOSE_WAIT_MS;
                relaunch = false;
                return List.of(new WriteClose(launch), new WatchClose(launch, pid, CLOSE_WAIT_MS));
            }
            case CLOSING -> {
                relaunch = false;
                return List.of();
            }
            default -> {
                return List.of();
            }
        }
    }

    public List<Action> tick(Observation o) {
        return switch (phase) {
            case OFF, LAUNCHING -> List.of();
            case PENDING -> {
                launchId = ids.get();
                phase = Phase.LAUNCHING;
                yield List.of(new Launch(launchId));
            }
            case AWAITING_PID -> awaitingPid(o);
            case ALIVE -> o.alive().test(alive) ? List.of() : windowDied(o.exit());
            case CLOSING -> {
                boolean closed = o.nowMs() >= deadline || (closing != null && !o.alive().test(closing));
                if (closed) {
                    boolean again = relaunch;
                    forget();
                    if (again) phase = Phase.PENDING;
                }
                yield List.of();
            }
        };
    }

    public List<Action> launched(String command, long nowMs) {
        requireLaunching();
        this.command = command;
        deadline = nowMs + PID_WAIT_MS;
        phase = Phase.AWAITING_PID;
        return List.of();
    }

    public List<Action> rejected(Msg reason) {
        requireLaunching();
        forget();
        return List.of(new Notify(Level.ERROR, Msg.of(ConsoleText.CANNOT_OPEN, "reason", reason)), new DisableModule());
    }

    private List<Action> awaitingPid(Observation o) {
        Pid readPid = o.readPid();
        if (readPid != null && readPid.launchId().equals(launchId)) {
            if (o.alive().test(readPid)) {
                alive = readPid;
                phase = Phase.ALIVE;
                return List.of();
            }
            return windowDied(o.exit());
        }
        if (o.nowMs() >= deadline) {
            // The F goes anyway: a window that starts late finds it and closes, instead of being left orphaned.
            Msg text = Msg.of(ConsoleText.NOT_STARTED, "seconds", PID_WAIT_MS / 1000, "command", command);
            String launch = launchId;
            forget();
            return List.of(new Notify(Level.ERROR, text), new WriteClose(launch), new DisableModule());
        }
        return List.of();
    }

    private List<Action> windowDied(Exit e) {
        String launch = launchId;
        forget();
        boolean ours = e != null && e.launchId().equals(launch);
        Notify notice;
        if (ours && e.type().equals(Exit.USER)) {
            notice = new Notify(Level.INFO, Msg.of(ConsoleText.CLOSED_FROM_MENU));
        } else if (ours && e.type().equals(Exit.ERROR)) {
            notice = new Notify(Level.ERROR, Msg.of(ConsoleText.CLOSED_BY_ERROR, "detail", e.detail()));
        } else {
            notice = new Notify(Level.INFO, Msg.of(ConsoleText.WINDOW_CLOSED));
        }
        return List.of(notice, new DisableModule());
    }

    private void requireLaunching() {
        if (phase != Phase.LAUNCHING) throw new IllegalStateException("no launch is in progress");
    }

    private void forget() {
        phase = Phase.OFF;
        launchId = null;
        command = null;
        deadline = 0;
        alive = null;
        closing = null;
        relaunch = false;
    }
}
