package com.xploits.console.window;

import com.xploits.console.core.WindowSize;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Measures the window with {@code mode con}, without JNI: it is what was verified in the probe,
 * redirecting to a file inside cmd itself and with the inherited console. The output comes in the
 * console's code page; only the numbers matter, so it is read as ISO-8859-1.
 */
final class SizeProbe {
    private final Path file;

    SizeProbe(Path folder) {
        file = folder.resolve("size.txt");
    }

    Optional<WindowSize> measure() {
        try {
            Process p = new ProcessBuilder("cmd.exe", "/c", "mode con > \"" + file + "\"").inheritIO().start();
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroy();
                return Optional.empty();
            }
            return WindowSize.fromModeCon(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1));
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
