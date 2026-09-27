package com.xploits.bench.core;

/**
 * The simulated ping a crystal-aura MEASURE plays over (R3-12, research-offense-gap): the integrated server
 * ticks in the same millisecond as the client, so a few microseconds of client work decide a tick race that a
 * real network never lets happen (0.07-0.21 of crystal-aura++'s cycles land in 2 ticks in "below" against
 * Meteor's 0.56-0.68; a pure busy-wait reproduces the gap with no change to any decision). Every {@code ca-*}
 * and {@code capp-*} run, including the inner runs of the CHECK {@code capp-budget-off-parity}, plays over a
 * round trip of {@link #BENCH_PING_MS}, the same for both auras, so the race is never available to either one;
 * every other scenario keeps today's lock-step (0 ms) unless it says otherwise. Pure: no game class, no Netty
 * class.
 */
public final class PingDelay {
    /** The round trip a crystal-aura MEASURE plays over, unless {@code -Pbench.ping} overrides it. */
    public static final int BENCH_PING_MS = 100;
    /**
     * The system property the bench sets, fresh before every run's world is created, to the delay this run's
     * connection adds in each direction ({@link #eachWayMs}); the bench-only pipeline handler reads it when it
     * is installed. Unset or {@code 0} leaves the pipeline exactly as vanilla builds it.
     */
    public static final String ACTIVE_PROPERTY = "xploits.bench.ping.active";

    private PingDelay() {
    }

    /**
     * The round trip {@code -Pbench.ping} asks for, or {@value #BENCH_PING_MS} when {@code override} is null
     * (the property was not passed).
     *
     * @throws IllegalArgumentException {@code override} is negative
     */
    public static int effective(Integer override) {
        return override == null ? BENCH_PING_MS : nonNegative(override, "-Pbench.ping");
    }

    /**
     * Half of {@code roundTripMs}, rounded down: what each direction (client to server, server to client)
     * delays, so the two together add up to {@code roundTripMs} or one millisecond less.
     *
     * @throws IllegalArgumentException {@code roundTripMs} is negative
     */
    public static int eachWayMs(int roundTripMs) {
        return nonNegative(roundTripMs, "the round trip") / 2;
    }

    private static int nonNegative(int value, String of) {
        if (value < 0) throw new IllegalArgumentException(of + " is negative: " + value);
        return value;
    }
}
