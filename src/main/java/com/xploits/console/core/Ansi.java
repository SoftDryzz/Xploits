package com.xploits.console.core;

import java.util.List;

/**
 * The window's escape sequences, in one place (console spec §6). All verified in Windows Terminal:
 * the resize, the one-row scroll region for the input and the save/restore cursor around each frame.
 */
public final class Ansi {
    public static final String ESC = "\u001b";
    public static final String CSI = ESC + "[";
    public static final String RESET = CSI + "0m";
    public static final String CLEAR_TO_END = CSI + "K";
    public static final String CLEAR_SCREEN = CSI + "2J";
    public static final String SAVE_CURSOR = ESC + "7";
    public static final String RESTORE_CURSOR = ESC + "8";
    public static final String SYNC_BEGIN = CSI + "?2026h";
    public static final String SYNC_END = CSI + "?2026l";
    public static final String FULL_REGION = CSI + "r";

    public static final int CYAN = 45;
    public static final int MAGENTA = 199;
    public static final int WHITE = 231;
    public static final int YELLOW = 220;
    public static final int RED = 196;

    private Ansi() {
    }

    public static String moveTo(int row, int col) {
        return CSI + row + ";" + col + "H";
    }

    public static String resize(int rows, int cols) {
        return CSI + "8;" + rows + ";" + cols + "t";
    }

    public static String region(int from, int to) {
        return CSI + from + ";" + to + "r";
    }

    public static String color(int n) {
        return CSI + "38;5;" + n + "m";
    }

    public static String title(String title) {
        return ESC + "]0;" + TerminalText.sanitize(title).replace('\n', ' ') + "\u0007";
    }

    /** Strips the color sequences from a line, to measure it. */
    public static String stripColor(String s) {
        return s.replaceAll("\u001b\\[[0-9;]*m", "");
    }

    /**
     * A whole frame, synchronized and in a single write: the rows from top to bottom, without the input
     * row. With {@code restorePrompt} {@code "> "} is written again on the input row (after an Enter);
     * otherwise the cursor goes back to where the keyboard echo left it.
     *
     * <p>It never clears the whole screen: that flickers and would also wipe what was typed.
     */
    public static String frame(List<String> rows, int inputRow, boolean restorePrompt) {
        StringBuilder sb = new StringBuilder(SYNC_BEGIN).append(SAVE_CURSOR);
        for (int i = 0; i < rows.size(); i++) {
            sb.append(moveTo(i + 1, 1)).append(rows.get(i)).append(RESET).append(CLEAR_TO_END);
        }
        if (restorePrompt) sb.append(moveTo(inputRow, 1)).append("> ").append(CLEAR_TO_END);
        else sb.append(RESTORE_CURSOR);
        return sb.append(SYNC_END).toString();
    }
}
