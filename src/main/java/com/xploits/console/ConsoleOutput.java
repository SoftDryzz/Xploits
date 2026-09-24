package com.xploits.console;

import com.xploits.console.core.ConsoleText;
import com.xploits.console.core.CoordinatePolicy;
import com.xploits.console.core.CoordinateSentinel;
import com.xploits.console.core.GameSnapshot;
import com.xploits.console.core.Level;
import com.xploits.shared.Texts;
import com.xploits.shared.core.PositionedMsg;
import com.xploits.shared.core.i18n.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The gate for everything that goes to the console (console spec §7 and §10). With the console off it
 * goes nowhere: the history is only written with the console on, by the user's choice.
 *
 * <p>Everything passes the sentinel before reaching the sink. The places with coordinates are marked at
 * the source; this is the net in case one slips through.
 */
public final class ConsoleOutput {
    /** This game's session: {@code seq} is monotonic within it. */
    public static final String SESSION = Long.toString(ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE, 36);

    private static final AtomicLong SEQ = new AtomicLong();
    private static final Queue<Msg> ALERTS = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean SENTINEL_WARNED = new AtomicBoolean();
    private static final AtomicBoolean HOOK_INSTALLED = new AtomicBoolean();
    private static volatile ConsoleSink activeSink;
    private static volatile boolean hideCoordinates = true;

    private ConsoleOutput() {
    }

    static void connect(ConsoleSink sink) {
        activeSink = sink;
    }

    static void disconnect() {
        activeSink = null;
    }

    /** The console module's {@code hide-coordinates} setting; takes effect from the next line. */
    static void hideCoordinates(boolean hide) {
        hideCoordinates = hide;
    }

    /** The half of a positioned message the console gets under the current setting. */
    public static Msg forConsole(PositionedMsg msg) {
        return CoordinatePolicy.pick(hideCoordinates, msg.chat(), msg.log());
    }

    static long nextSeq() {
        return SEQ.getAndIncrement();
    }

    public static void message(Level level, String source, String text) {
        ConsoleSink s = activeSink;
        if (s == null) return;
        String written = text;
        if (CoordinatePolicy.hold(hideCoordinates, text)) {
            reportSentinelHit(source);
            written = Texts.render(CoordinateSentinel.held(source));
        }
        s.message(level, source, written);
    }

    public static void snapshot(GameSnapshot snapshot) {
        ConsoleSink s = activeSink;
        if (s == null) return;
        List<GameSnapshot.ModuleStatus> modules = new ArrayList<>();
        for (GameSnapshot.ModuleStatus m : snapshot.modules()) {
            if (CoordinatePolicy.hold(hideCoordinates, m.activity())) {
                reportSentinelHit(m.name());
                modules.add(new GameSnapshot.ModuleStatus(m.name(), m.active(), Texts.render(ConsoleText.HELD_SHORT)));
            } else {
                modules.add(m);
            }
        }
        s.snapshot(snapshot.withModules(modules));
    }

    private static void reportSentinelHit(String source) {
        if (SENTINEL_WARNED.compareAndSet(false, true)) {
            alert(Msg.of(ConsoleText.UNMARKED_COORDINATES, "source", source));
        }
    }

    static void alert(Msg text) {
        ALERTS.add(text);
    }

    /** The next alert to hand out, or null. It is handed out from the game thread. */
    static Msg pendingAlert() {
        return ALERTS.poll();
    }

    /** Once per JVM: when the game closes, what is pending and the goodbye. */
    static void installShutdownHook() {
        if (!HOOK_INSTALLED.compareAndSet(false, true)) return;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ConsoleSink s = activeSink;
            if (s != null) s.writeGameEnd();
        }, "xploits-console-shutdown"));
    }
}
