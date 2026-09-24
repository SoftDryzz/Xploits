package com.xploits.console;

import com.xploits.console.core.WindowLifecycle;
import com.xploits.console.core.WindowStart;
import com.xploits.shared.Texts;
import meteordevelopment.meteorclient.MeteorClient;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/** What the module needs from the system to open, watch and close the window (console spec §8). */
final class WindowLauncher {
    private WindowLauncher() {
    }

    static Path folder() {
        return MeteorClient.FOLDER.toPath().resolve("xploits").resolve("console");
    }

    static String newId() {
        return Long.toString(ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE, 36);
    }

    static WindowStart.Result prepare(Path folder, String launchId) {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (!Files.exists(java)) {
            java = ProcessHandle.current().info().command().map(c -> Path.of(c).resolveSibling("java.exe")).orElse(java); // i18n: allowed: a file name, not player text
        }
        return WindowStart.prepare(java, Files.exists(java), classpath(), folder, ProcessHandle.current().pid(), launchId,
            ConsoleOutput.SESSION, Texts.current());
    }

    /** The mod's jar, or its folders in development. {@code getRootPaths()} is no use: it points inside the zip. */
    private static List<Path> classpath() {
        return FabricLoader.getInstance().getModContainer("xploits")
            .map(ModContainer::getOrigin)
            .filter(origin -> origin.getKind() == ModOrigin.Kind.PATH)
            .map(ModOrigin::getPaths)
            .orElse(List.of());
    }

    /**
     * Without redirecting anything: it is exactly the form verified in the probe. {@code start} opens the
     * window and the intermediate {@code cmd} ends right away.
     */
    static void launch(List<String> argv) throws IOException {
        new ProcessBuilder(argv).start();
    }

    static Optional<WindowLifecycle.Pid> readPid(Path folder) {
        return read(folder.resolve("console.pid")).flatMap(WindowLifecycle.Pid::read);
    }

    static Optional<WindowLifecycle.Exit> readExit(Path folder) {
        return read(folder.resolve("console.exit")).flatMap(WindowLifecycle.Exit::read);
    }

    private static Optional<String> read(Path file) {
        try {
            return Files.exists(file) ? Optional.of(Files.readString(file, StandardCharsets.UTF_8)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Whether the pid is still alive and the same process: the start instant is compared too, against pid reuse. */
    static boolean isAlive(WindowLifecycle.Pid pid) {
        return ProcessHandle.of(pid.pid())
            .filter(ProcessHandle::isAlive)
            .filter(h -> h.info().startInstant().map(i -> Math.abs(i.toEpochMilli() - pid.startMs()) < 2_000).orElse(true))
            .isPresent();
    }

    /** Before launching: the pid and exit of earlier launches must not confuse the lifecycle. */
    static void deleteLeftovers(Path folder) {
        try {
            Files.deleteIfExists(folder.resolve("console.pid"));
            Files.deleteIfExists(folder.resolve("console.exit"));
        } catch (IOException ignored) {
            // The lifecycle already ignores whatever is not from its launch: this is only cleanup.
        }
    }

    /** Waits for the window to close by itself on reading its F; if it does not in time, kills it. */
    static void watchClose(Path folder, WindowLifecycle.WatchClose w) {
        Thread thread = new Thread(() -> {
            long limit = System.currentTimeMillis() + w.waitMs();
            while (System.currentTimeMillis() < limit) {
                // If its pid is already known and it is dead, it closed by itself: nothing to do.
                Optional<Long> pid = pidOf(folder, w);
                if (pid.isPresent() && !ProcessHandle.of(pid.get()).map(ProcessHandle::isAlive).orElse(false)) return;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
            }
            pidOf(folder, w).flatMap(ProcessHandle::of).ifPresent(ProcessHandle::destroy);
        }, "xploits-console-closer");
        thread.setDaemon(true);
        thread.start();
    }

    private static Optional<Long> pidOf(Path folder, WindowLifecycle.WatchClose w) {
        if (w.pid() != null) return Optional.of(w.pid());
        return readPid(folder).filter(p -> p.launchId().equals(w.launchId())).map(WindowLifecycle.Pid::pid);
    }
}
