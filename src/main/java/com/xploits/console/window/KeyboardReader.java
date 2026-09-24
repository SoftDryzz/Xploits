package com.xploits.console.window;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Reads the keyboard on its own thread, line by line. Windows' normal line input is enough: the menu
 * is numbers and Enter (console spec §3.1, verified with 40 characters at 10 frames/s).
 */
final class KeyboardReader {
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

    private KeyboardReader() {
    }

    static KeyboardReader start() {
        KeyboardReader keyboard = new KeyboardReader();
        Thread thread = new Thread(keyboard::readLoop, "console-keyboard");
        thread.setDaemon(true);
        thread.start();
        return keyboard;
    }

    private void readLoop() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) lines.add(line);
        } catch (IOException ignored) {
            // Without a keyboard the window keeps showing; it only stops handling the menu.
        }
    }

    /** The next typed line, or null if there is none. */
    String next() {
        return lines.poll();
    }

    /** Waits for an Enter, ten minutes at most. Only for the error screen. */
    void awaitLine() {
        try {
            lines.poll(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
