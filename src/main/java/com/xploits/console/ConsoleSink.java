package com.xploits.console;

import com.xploits.XploitsAddon;
import com.xploits.console.core.ConsoleText;
import com.xploits.console.core.DroppedMessages;
import com.xploits.console.core.GameSnapshot;
import com.xploits.console.core.History;
import com.xploits.console.core.Level;
import com.xploits.console.core.LogEntry;
import com.xploits.console.core.LogRotation;
import com.xploits.shared.Texts;
import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Msg;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongFunction;
import java.util.stream.Stream;

/**
 * Writes {@code live.log} and the history from a thread of its own (console spec §10), the first one
 * in Xploits' code.
 *
 * <p>The rules: the game thread never waits here (except when the console turns off, when
 * {@link #close()} waits half a second at most for the writer to finish its batch; the loop exits
 * within 200 ms of seeing {@code stopping}) nor gets an exception from here; the queue is bounded and
 * what does not fit is counted and reported; {@code seq} is assigned when writing, under the same lock
 * as the write, so it always grows in the file; and an error on this thread never comes back in
 * through the sink, or it would feed itself.
 */
final class ConsoleSink {
    private static final int CAPACITY = 4_096;
    private static final long HEARTBEAT_MS = 1_000;

    private final Path liveLog;
    private final Path previousLog;
    private final Path historyDir;
    private final ZoneId zone = ZoneId.systemDefault();
    private final BlockingQueue<LongFunction<LogEntry>> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final AtomicReference<OfferedSnapshot> offered = new AtomicReference<>();
    private final DroppedMessages dropped = new DroppedMessages();
    private Thread thread;
    private volatile boolean stopping;
    private GameSnapshot lastSnapshot;
    private long lastSnapshotMs;
    private LocalDate prunedDay;
    private boolean failureReported;
    private boolean failedRoundReported;

    ConsoleSink(Path folder) {
        liveLog = folder.resolve("live.log");
        previousLog = folder.resolve("live.1.log");
        historyDir = folder.resolve("history");
    }

    void start() throws IOException {
        Files.createDirectories(historyDir);
        if (!Files.exists(liveLog) || !hasValidHeader()) startFile(false);
        prune();
        thread = new Thread(this::loop, "xploits-console-writer");
        thread.setDaemon(true);
        thread.start();
    }

    void message(Level level, String source, String text) {
        long ms = System.currentTimeMillis();
        offer(seq -> new LogEntry.Message(seq, ms, ConsoleOutput.SESSION, level, source, text));
    }

    void gameEvent(String reason) {
        long ms = System.currentTimeMillis();
        offer(seq -> new LogEntry.Game(seq, ms, ConsoleOutput.SESSION, reason));
    }

    void sendClose(String launchId, String reason) {
        long ms = System.currentTimeMillis();
        offer(seq -> new LogEntry.Close(seq, ms, ConsoleOutput.SESSION, launchId, reason));
    }

    /**
     * From the game thread: keeps the snapshot with the time at which the game offers it. That time is
     * the one the {@code S} carries, so the heartbeat measures the game and not the writer.
     */
    void snapshot(GameSnapshot snapshot) {
        offered.set(new OfferedSnapshot(snapshot, System.currentTimeMillis()));
    }

    /** A snapshot offered by the game and the time at which it offered it. */
    private record OfferedSnapshot(GameSnapshot snapshot, long ms) {
    }

    private void offer(LongFunction<LogEntry> entry) {
        if (!queue.offer(entry) && dropped.recordDrop()) {
            ConsoleOutput.alert(Msg.of(ConsoleText.QUEUE_FULL));
        }
    }

    /** Stops the thread and writes what is left. Called when the console turns off. */
    void close() {
        stopping = true;
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        drain();
    }

    /** From the game's shutdown hook: stops the writer, then what is pending and the goodbye. */
    void writeGameEnd() {
        stopping = true;
        if (thread != null) {
            try {
                thread.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        long ms = System.currentTimeMillis();
        drain();
        write(List.of(seq -> new LogEntry.Game(seq, ms, ConsoleOutput.SESSION, "end")));
    }

    private void loop() {
        while (!stopping) {
            try {
                List<LongFunction<LogEntry>> batch = new ArrayList<>();
                LongFunction<LogEntry> first = queue.poll(200, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch);
                }
                addSnapshotAndLosses(batch);
                write(batch);
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                // The writer cannot die silently: it is reported once and the next round goes on.
                XploitsAddon.LOG.error("The console writer has failed", e);
                if (!failedRoundReported) {
                    failedRoundReported = true;
                    ConsoleOutput.alert(Msg.of(ConsoleText.WRITER_FAILED, "error", e.getClass().getSimpleName() + ": "
                        + e.getMessage()));
                }
            }
        }
    }

    private void drain() {
        List<LongFunction<LogEntry>> batch = new ArrayList<>();
        queue.drainTo(batch);
        addSnapshotAndLosses(batch);
        write(batch);
    }

    /**
     * The snapshot is, besides the header, the game's heartbeat. The last one the game offered is taken
     * and it goes only if it changed or if a second has passed since the previous one, with the time at
     * which the game offered it. If the game stops offering, no {@code S} goes out and the window sees
     * the heartbeat age. An offer that does not qualify is dropped: the next one arrives in about 250 ms.
     */
    private synchronized void addSnapshotAndLosses(List<LongFunction<LogEntry>> batch) {
        long now = System.currentTimeMillis();
        OfferedSnapshot offering = offered.getAndSet(null);
        if (offering != null && (!offering.snapshot().equals(lastSnapshot) || offering.ms() - lastSnapshotMs >= HEARTBEAT_MS)) {
            GameSnapshot s = offering.snapshot();
            long ms = offering.ms();
            lastSnapshot = s;
            lastSnapshotMs = ms;
            batch.add(seq -> new LogEntry.Snapshot(seq, ms, ConsoleOutput.SESSION, s));
        }
        dropped.drain().ifPresent(n -> batch.add(seq -> new LogEntry.Lost(seq, now, ConsoleOutput.SESSION, n)));
    }

    private synchronized void write(List<LongFunction<LogEntry>> batch) {
        if (batch.isEmpty()) return;
        try {
            StringBuilder raw = new StringBuilder();
            StringBuilder readable = new StringBuilder();
            // The history is read by the player: it is written in the language chosen now.
            Catalog texts = Texts.catalog(Texts.current());
            for (LongFunction<LogEntry> pending : batch) {
                LogEntry e = pending.apply(ConsoleOutput.nextSeq());
                raw.append(e.encode()).append('\n');
                String line = History.line(e, zone, texts);
                if (line != null) readable.append(line).append('\n');
            }
            byte[] bytes = raw.toString().getBytes(StandardCharsets.UTF_8);
            if (!Files.exists(liveLog)) startFile(false);
            else if (LogRotation.shouldRotateLiveLog(Files.size(liveLog), bytes.length)) startFile(true);
            Files.write(liveLog, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            LocalDate today = LocalDate.now(zone);
            if (!readable.isEmpty()) {
                Files.writeString(historyDir.resolve(LogRotation.fileNameFor(today)), readable, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            if (!today.equals(prunedDay)) prune();
        } catch (IOException | RuntimeException e) {
            if (!failureReported) {
                failureReported = true;
                XploitsAddon.LOG.error("The console cannot write its log", e);
                ConsoleOutput.alert(Msg.of(ConsoleText.CANNOT_WRITE_LOG, "error", String.valueOf(e.getMessage())));
            }
        }
    }

    private void startFile(boolean rotate) throws IOException {
        if (rotate && Files.exists(liveLog)) Files.move(liveLog, previousLog, StandardCopyOption.REPLACE_EXISTING);
        String generation = Long.toString(ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE, 36);
        Files.writeString(liveLog, LogEntry.header(generation) + "\n", StandardCharsets.UTF_8);
    }

    private boolean hasValidHeader() {
        try (BufferedReader in = Files.newBufferedReader(liveLog, StandardCharsets.UTF_8)) {
            String firstLine = in.readLine();
            if (firstLine == null) return false;
            LogEntry.generationOf(firstLine);
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    private void prune() {
        LocalDate today = LocalDate.now(zone);
        prunedDay = today;
        try (Stream<Path> files = Files.list(historyDir)) {
            List<LogRotation.LogFile> list = new ArrayList<>();
            for (Path p : files.toList()) {
                String name = p.getFileName().toString();
                Optional<LocalDate> date = LogRotation.dateOf(name);
                if (date.isPresent()) list.add(new LogRotation.LogFile(name, Files.size(p), date.get()));
            }
            for (String name : LogRotation.toDelete(list, today)) {
                Files.deleteIfExists(historyDir.resolve(name));
                message(Level.INFO, "console", Texts.render(ConsoleText.HISTORY_PRUNED, "file", name, "days", LogRotation.DAYS));
            }
        } catch (IOException e) {
            XploitsAddon.LOG.warn("Could not prune the console history", e);
        }
    }
}
