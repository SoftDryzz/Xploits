package com.xploits.console.window;

import com.xploits.console.core.Ansi;
import com.xploits.console.core.Banner;
import com.xploits.console.core.Glyphs;
import com.xploits.console.core.GameSnapshot;
import com.xploits.console.core.Heartbeat;
import com.xploits.console.core.LogEntry;
import com.xploits.console.core.LogFilter;
import com.xploits.console.core.Menu;
import com.xploits.console.core.RingBuffer;
import com.xploits.console.core.ScreenFrame;
import com.xploits.console.core.SequenceTracker;
import com.xploits.console.core.TerminalText;
import com.xploits.console.core.WindowLifecycle;
import com.xploits.console.core.WindowSize;
import com.xploits.console.core.WindowStart;
import com.xploits.console.core.WindowText;
import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.Msg;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * The console window (console spec §4 and §6). It runs outside the game with
 * {@code java -cp <mod jar> com.xploits.console.window.ConsoleMain <folder> <game pid> <launch id> <session> <language>},
 * so it only touches the JDK and {@code console/core}.
 *
 * <p>{@code session} is the one of the game that opened it: only that session's {@code J} says whether its
 * game said goodbye, and once its game dies the window stops reading {@code live.log}, so the next game
 * session does not show up in this window nor turn it red.
 *
 * <p>{@code language} is the language the window starts in; every snapshot says the current one, and
 * the window switches as soon as a snapshot brings another. A catalog problem goes to
 * {@code console-errors.log}, never to the screen.
 *
 * <p>If it crashes it does not vanish: it writes why in {@code console.exit} (so the game says it in
 * chat) and in {@code console-errors.log}, shows it in red and waits for the player to read it.
 */
public final class ConsoleMain {
    private static final long POLL_MS = 100;
    private static final long MIN_FRAME_MS = 100;
    private static final long FORCED_REPAINT_MS = 1_000;
    private static final long MEASURE_MS = 2_000;
    private static final int CAPACITY = 2_000;
    private static final int READ_FAILURES_BEFORE_WARNING = 10;

    private final Path folder;
    private final long gamePid;
    private final String launchId;
    private final String session;
    private final PrintStream out;
    private final String encoding;
    private final KeyboardReader keyboard;
    private final LogTailer tailer;
    private final SizeProbe sizeProbe;
    private final RingBuffer<LogEntry.Message> ring = new RingBuffer<>(CAPACITY);
    private final SequenceTracker sequence = new SequenceTracker();

    private GameSnapshot snapshot;
    private Long heartbeatMs;
    private boolean sawGameEnd;
    private LogFilter filter = LogFilter.ALL;
    private boolean paused;
    private List<LogEntry.Message> frozen = List.of();
    private int newMessages;
    private Msg notice;
    private Catalog texts;
    private Language failedLanguage;
    private WindowSize size = WindowSize.REQUESTED;
    private Heartbeat.GameState game;
    private boolean dirty = true;
    private boolean restorePrompt;
    private int consecutiveReadFailures;

    private ConsoleMain(Path folder, long gamePid, String launchId, String session, PrintStream out, String encoding,
                        KeyboardReader keyboard, Catalog texts) {
        this.texts = texts;
        this.folder = folder;
        this.gamePid = gamePid;
        this.launchId = launchId;
        this.session = session;
        this.out = out;
        this.encoding = encoding;
        this.keyboard = keyboard;
        this.tailer = new LogTailer(folder);
        this.sizeProbe = new SizeProbe(folder);
    }

    public static void main(String[] args) {
        String encoding = System.getProperty("stdout.encoding", "UTF-8");
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, Charset.forName(encoding));
        if (args.length != 5) {
            // The language is not known yet: English is the fallback.
            out.println(Catalog.load(Language.EN, p -> { }).render(WindowText.USAGE));
            out.flush();
            System.exit(2);
            return;
        }
        Path folder = Path.of(args[0]);
        String launchId = args[2];
        Language initial = Language.fromCode(args[4]).orElse(Language.EN);
        KeyboardReader keyboard = KeyboardReader.start();
        ConsoleMain window = null;
        try {
            window = new ConsoleMain(folder, Long.parseLong(args[1]), launchId, args[3], out, encoding, keyboard,
                firstCatalog(folder, initial));
            window.run();
        } catch (Throwable t) {
            crash(folder, launchId, out, keyboard, t, window == null ? null : window.texts);
        }
    }

    private void run() throws IOException, InterruptedException {
        Files.createDirectories(folder);
        ProcessHandle self = ProcessHandle.current();
        long start = self.info().startInstant().map(Instant::toEpochMilli).orElse(System.currentTimeMillis());
        Files.writeString(folder.resolve("console.pid"), new WindowLifecycle.Pid(self.pid(), start, launchId).write(),
            StandardCharsets.UTF_8);

        // chcp 65001 puts the output in UTF-8 (verified). If it did not take effect, the frame is drawn in ASCII and the window says so.
        Glyphs glyphs = "UTF-8".equalsIgnoreCase(encoding) ? Glyphs.UNICODE : Glyphs.ASCII;
        if (glyphs == Glyphs.ASCII) notice = Msg.of(WindowText.NOT_UTF8);
        List<String> art = Banner.load();

        out.print(Ansi.title(WindowStart.TITLE) + Ansi.resize(WindowSize.REQUESTED.rows(), WindowSize.REQUESTED.cols()));
        out.flush();
        Thread.sleep(300);
        size = sizeProbe.measure().orElse(WindowSize.REQUESTED);
        prepareScreen();

        long lastPaint = 0;
        long lastMeasure = System.currentTimeMillis();
        boolean reading = true;
        while (true) {
            long now = System.currentTimeMillis();
            // Once its game is dead, the log is read one last time (what it wrote before dying, its J end
            // included) and then no more: what is shown freezes on its game session. Keyboard and repaint go on.
            boolean alive = gameAlive();
            if (reading) {
                if (!readLog()) return;
                reading = alive;
            }
            if (!handleKeyboard()) return;
            if (now - lastMeasure >= MEASURE_MS) {
                lastMeasure = now;
                sizeProbe.measure().filter(s -> !s.equals(size)).ifPresent(s -> {
                    size = s;
                    prepareScreen();
                });
            }
            Heartbeat.GameState state = Heartbeat.evaluate(heartbeatMs, now, alive, sawGameEnd);
            if (!state.equals(game)) {
                game = state;
                dirty = true;
            }
            boolean due = dirty || now - lastPaint >= FORCED_REPAINT_MS;
            if (due && now - lastPaint >= MIN_FRAME_MS) {
                paint(art, glyphs);
                lastPaint = now;
            }
            Thread.sleep(POLL_MS);
        }
    }

    /**
     * Reads what is new in live.log. Returns false if the order to close this launch has arrived.
     *
     * <p>An I/O failure while reading is normal during a rotation (for example, opening {@code live.log}
     * between the {@code move} and the new header): it is ignored and retried on the next round. Only
     * if it fails {@value #READ_FAILURES_BEFORE_WARNING} times in a row (one second) is it reported.
     */
    private boolean readLog() {
        List<String> lines;
        try {
            lines = tailer.read();
            consecutiveReadFailures = 0;
        } catch (IllegalArgumentException e) {
            showNotice(Msg.of(WindowText.CANNOT_READ_LOG, "error", String.valueOf(e.getMessage())));
            return true;
        } catch (IOException e) {
            if (++consecutiveReadFailures == READ_FAILURES_BEFORE_WARNING) {
                showNotice(Msg.of(WindowText.CANNOT_READ_LOG, "error", String.valueOf(e.getMessage())));
            }
            return true;
        }
        for (String line : lines) {
            LogEntry entry;
            try {
                entry = LogEntry.decode(line);
            } catch (IllegalArgumentException e) {
                showNotice(Msg.of(WindowText.UNREADABLE_LINE, "error", String.valueOf(e.getMessage())));
                continue;
            }
            try {
                long lost = sequence.gap(entry.session(), entry.seq());
                if (lost > 0) showNotice(Msg.of(WindowText.RECORDS_LOST, "count", lost));
            } catch (IllegalStateException e) {
                showNotice(Msg.of(WindowText.PROBLEM, "problem", String.valueOf(e.getMessage())));
            }
            switch (entry) {
                case LogEntry.Message m -> {
                    ring.add(m);
                    if (paused && filter.accepts(m)) newMessages++;
                }
                case LogEntry.Snapshot s -> {
                    snapshot = s.snapshot();
                    heartbeatMs = s.epochMs();
                    if (snapshot.language() != texts.language()) changeLanguage(snapshot.language());
                }
                case LogEntry.Game g -> {
                    if (g.session().equals(session)) sawGameEnd = g.reason().equals("end");
                    heartbeatMs = g.epochMs();
                }
                case LogEntry.Close c -> {
                    if (c.launchId().equals(launchId)) {
                        sayGoodbye();
                        return false;
                    }
                }
                case LogEntry.Lost l -> showNotice(Msg.of(WindowText.GAME_LOST_MESSAGES, "count", l.count()));
            }
            dirty = true;
        }
        return true;
    }

    /** Handles the menu. Returns false if the player asked to quit. */
    private boolean handleKeyboard() throws IOException {
        String line;
        while ((line = keyboard.next()) != null) {
            restorePrompt = true;
            dirty = true;
            switch (Menu.parse(line)) {
                case Menu.ChangeFilter c -> {
                    filter = c.filter();
                    notice = null;
                }
                case Menu.TogglePause p -> {
                    paused = !paused;
                    newMessages = 0;
                    frozen = paused ? ring.latest(CAPACITY, m -> true) : List.of();
                    notice = null;
                }
                case Menu.Quit q -> {
                    Files.writeString(folder.resolve("console.exit"), WindowLifecycle.Exit.user(launchId), StandardCharsets.UTF_8);
                    sayGoodbye();
                    return false;
                }
                case Menu.Unknown u -> notice = u.reason();
            }
        }
        return true;
    }

    private void paint(List<String> art, Glyphs glyphs) {
        List<LogEntry.Message> messages = paused ? frozen : ring.latest(CAPACITY, m -> true);
        Heartbeat.GameState state = game == null ? new Heartbeat.GameState.NoData() : game;
        List<String> rows = ScreenFrame.compose(new ScreenFrame.Input(size, art, snapshot, state, messages, filter, paused,
            newMessages, notice == null ? null : texts.render(notice), glyphs, ZoneId.systemDefault(), texts));
        out.print(Ansi.frame(rows, size.rows(), restorePrompt));
        out.flush();
        restorePrompt = false;
        dirty = false;
    }

    /** A clean screen, with the last row as its own scroll region for the input (verified in the probe). */
    private void prepareScreen() {
        out.print(Ansi.FULL_REGION + Ansi.CLEAR_SCREEN + Ansi.region(size.rows(), size.rows())
            + Ansi.moveTo(size.rows(), 1) + "> ");
        out.flush();
        dirty = true;
        restorePrompt = false;
    }

    private void sayGoodbye() {
        out.print(Ansi.FULL_REGION + Ansi.RESET + "\n");
        out.flush();
    }

    private boolean gameAlive() {
        return ProcessHandle.of(gamePid).map(ProcessHandle::isAlive).orElse(false);
    }

    private void showNotice(Msg text) {
        notice = text;
        dirty = true;
    }

    /** The game changed language: the next frame is drawn in the new one. A catalog that fails to load keeps the old one. */
    private void changeLanguage(Language language) {
        if (language == failedLanguage) return;
        try {
            texts = loadCatalog(folder, language);
            failedLanguage = null;
        } catch (RuntimeException e) {
            // Said once, not on every snapshot: the window keeps speaking the old language.
            failedLanguage = language;
            logError(folder, "cannot load the " + language.code() + " texts: " + e);
        }
        dirty = true;
    }

    /** The starting language's texts; if they fail to load, it is logged and English is used. If English fails too, it throws. */
    private static Catalog firstCatalog(Path folder, Language language) {
        try {
            return loadCatalog(folder, language);
        } catch (RuntimeException e) {
            logError(folder, "cannot load the " + language.code() + " texts, using English: " + e);
            return loadCatalog(folder, Language.EN);
        }
    }

    private static Catalog loadCatalog(Path folder, Language language) {
        return Catalog.load(language, problem -> logError(folder, problem));
    }

    /** Appends to console-errors.log; if even that fails there is nowhere left to say it. */
    private static void logError(Path folder, String text) {
        try {
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("console-errors.log"), Instant.now() + "  " + text + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Nothing more to do: the problem is with a text, not with the window.
        }
    }

    private static void crash(Path folder, String launchId, PrintStream out, KeyboardReader keyboard, Throwable t,
                              Catalog texts) {
        String detail = t.getClass().getSimpleName() + ": " + t.getMessage();
        try {
            Files.createDirectories(folder);
            StringWriter trace = new StringWriter();
            t.printStackTrace(new PrintWriter(trace));
            Files.writeString(folder.resolve("console-errors.log"), Instant.now() + "  " + trace + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Files.writeString(folder.resolve("console.exit"), WindowLifecycle.Exit.error(launchId, detail), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // There is nowhere left to write it: it is shown on screen, which is what still can be done.
        }
        out.print(Ansi.FULL_REGION + Ansi.RESET + Ansi.CLEAR_SCREEN + Ansi.moveTo(1, 1));
        Catalog t2 = texts;
        if (t2 == null) {
            try {
                t2 = loadCatalog(folder, Language.EN);
            } catch (RuntimeException noTexts) {
                t2 = null;
            }
        }
        if (t2 == null) {
            out.println(Ansi.color(Ansi.RED) + TerminalText.sanitize(detail) + Ansi.RESET);
        } else {
            out.println(Ansi.color(Ansi.RED) + t2.render(WindowText.CRASHED, "detail", TerminalText.sanitize(detail)) + Ansi.RESET);
            out.println(t2.render(WindowText.CRASH_DETAIL_AT, "file", folder.resolve("console-errors.log").toString()));
            out.println(t2.render(WindowText.PRESS_ENTER));
        }
        out.flush();
        keyboard.awaitLine();
        System.exit(1);
    }
}
