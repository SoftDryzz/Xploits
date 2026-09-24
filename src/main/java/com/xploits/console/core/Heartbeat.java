package com.xploits.console.core;

import com.xploits.shared.core.i18n.Catalog;

/**
 * The game's state as seen from the window (console spec §8). Two signals: the snapshots, which the
 * game writes at least once a second, and whether its process is still alive. The PID wins: a fresh
 * heartbeat from a dead process is an old file, not a live game.
 */
public final class Heartbeat {
    public static final long SLOW_MS = 3_000;
    public static final long NOT_RESPONDING_MS = 15_000;

    private Heartbeat() {
    }

    public sealed interface GameState {
        record NoData() implements GameState {
        }

        record Alive() implements GameState {
        }

        /** Loading a world freezes the game thread for a few seconds: yellow, not red. */
        record Slow(long seconds) implements GameState {
        }

        record NotResponding(long seconds) implements GameState {
        }

        record Closed() implements GameState {
        }

        record ClosedWithoutGoodbye() implements GameState {
        }
    }

    public static GameState evaluate(Long lastHeartbeatMs, long nowMs, boolean pidAlive, boolean sawGameEnd) {
        if (!pidAlive) return sawGameEnd ? new GameState.Closed() : new GameState.ClosedWithoutGoodbye();
        if (lastHeartbeatMs == null) return new GameState.NoData();
        long age = Math.max(0, nowMs - lastHeartbeatMs);
        if (age < SLOW_MS) return new GameState.Alive();
        if (age <= NOT_RESPONDING_MS) return new GameState.Slow(age / 1000);
        return new GameState.NotResponding(age / 1000);
    }

    public static String text(GameState state, Catalog t) {
        return switch (state) {
            case GameState.NoData n -> t.render(WindowText.HEARTBEAT_WAITING);
            case GameState.Alive a -> t.render(WindowText.HEARTBEAT_ALIVE);
            case GameState.Slow s -> t.render(WindowText.HEARTBEAT_SLOW, "seconds", s.seconds());
            case GameState.NotResponding n -> t.render(WindowText.HEARTBEAT_NOT_RESPONDING, "seconds", n.seconds());
            case GameState.Closed c -> t.render(WindowText.GAME_CLOSED);
            case GameState.ClosedWithoutGoodbye c -> t.render(WindowText.GAME_ENDED_WITHOUT_GOODBYE);
        };
    }

    /** The status line's color; 0 means no color. */
    public static int color(GameState state) {
        return switch (state) {
            case GameState.Slow s -> Ansi.YELLOW;
            case GameState.NotResponding n -> Ansi.RED;
            case GameState.ClosedWithoutGoodbye c -> Ansi.RED;
            default -> 0;
        };
    }
}
