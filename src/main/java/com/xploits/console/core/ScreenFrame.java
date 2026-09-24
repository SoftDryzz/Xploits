package com.xploits.console.core;

import com.xploits.shared.core.i18n.Catalog;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.StringJoiner;

/**
 * The whole screen, row by row (console spec §6): logo, separator, data, separator, log, separator,
 * menu and status. The input row is the window's last one and the window draws it.
 *
 * <p>If everything does not fit, parts are removed in a fixed order and <b>the status line says so</b>:
 * showing less data without saying it would present an incomplete header as if it were the whole one.
 */
public final class ScreenFrame {
    public static final int MIN_LOG_ROWS = 5;
    public static final int ROWS_PER_MESSAGE = 3;
    private static final int SOURCE_WIDTH = 12;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    /** Indices into {@link Header#NAMES} in the order they are removed: modules, environment, flight, combat. */
    private static final int[] REMOVAL_ORDER = {3, 0, 1, 2};

    private ScreenFrame() {
    }

    public record Input(WindowSize size, List<String> art, GameSnapshot snapshot, Heartbeat.GameState game,
                        List<LogEntry.Message> messages, LogFilter filter, boolean paused, int newMessages,
                        String notice, Glyphs glyphs, ZoneId zone, Catalog texts) {
    }

    /** Exactly {@code size.rows() - 1} rows. */
    public static List<String> compose(Input in) {
        int cols = in.size().cols();
        int height = in.size().rows() - 1;
        List<String> rows = new ArrayList<>();
        if (height <= 0) return rows;
        if (!in.size().fitsMinimum()) {
            rows.add(TerminalText.truncate(in.texts().render(WindowText.TOO_SMALL, "cols", cols, "rows", in.size().rows(),
                "minCols", WindowSize.MINIMUM.cols(), "minRows", WindowSize.MINIMUM.rows()), cols));
            while (rows.size() < height) rows.add("");
            return rows;
        }

        List<String> hidden = new ArrayList<>();
        List<String> logo = Banner.choose(in.art(), cols, in.size().rows());
        if (!Banner.fits(cols, in.size().rows())) hidden.add(in.texts().render(WindowText.SECTION_LOGO));

        List<String> data = in.snapshot() == null
            ? List.of(in.texts().render(WindowText.NO_GAME_DATA), "", "", "")
            : Header.rows(in.snapshot(), in.glyphs(), in.texts());
        boolean[] visible = {true, true, true, true};
        int remaining = 4;
        int body = logRows(height, logo.size(), remaining);
        for (int i = 0; i < REMOVAL_ORDER.length && body < MIN_LOG_ROWS; i++) {
            visible[REMOVAL_ORDER[i]] = false;
            hidden.add(in.texts().render(Header.NAMES.get(REMOVAL_ORDER[i])));
            remaining--;
            body = logRows(height, logo.size(), remaining);
        }

        String separator = Ansi.color(Ansi.CYAN) + in.glyphs().line(cols) + Ansi.RESET;
        rows.addAll(logo);
        rows.add(separator);
        if (remaining > 0) {
            for (int i = 0; i < data.size(); i++) {
                if (visible[i]) rows.add(TerminalText.truncate(TerminalText.sanitize(data.get(i)), cols));
            }
            rows.add(separator);
        }
        rows.addAll(logPane(in, cols, body));
        rows.add(separator);
        rows.add(TerminalText.truncate(Menu.line(in.texts()), cols));
        rows.add(statusLine(in, cols, hidden));
        return rows;
    }

    private static int logRows(int height, int logoRows, int dataRows) {
        int separators = dataRows > 0 ? 3 : 2;
        return height - logoRows - dataRows - separators - 2;
    }

    /** The most recent messages at the bottom; one that does not fit whole is not shown by halves. */
    private static List<String> logPane(Input in, int cols, int rows) {
        LinkedList<String> result = new LinkedList<>();
        List<LogEntry.Message> all = in.messages();
        for (int i = all.size() - 1; i >= 0 && result.size() < rows; i--) {
            LogEntry.Message m = all.get(i);
            if (!in.filter().accepts(m)) continue;
            String prefix = TIME.format(Instant.ofEpochMilli(m.epochMs()).atZone(in.zone())) + "  " + source(m.source()) + "  ";
            List<String> block = TerminalText.wrap(prefix + TerminalText.sanitize(m.text()), cols, ROWS_PER_MESSAGE);
            if (result.size() + block.size() > rows) break;
            String color = switch (m.level()) {
                case INFO -> "";
                case WARNING -> Ansi.color(Ansi.YELLOW);
                case ERROR -> Ansi.color(Ansi.RED);
            };
            for (int j = block.size() - 1; j >= 0; j--) {
                result.addFirst(color.isEmpty() ? block.get(j) : color + block.get(j) + Ansi.RESET);
            }
        }
        while (result.size() < rows) result.addFirst("");
        return result;
    }

    private static String source(String source) {
        String clean = TerminalText.truncate(TerminalText.sanitize(source).replace('\n', ' '), SOURCE_WIDTH);
        return clean + " ".repeat(Math.max(0, SOURCE_WIDTH - TerminalText.width(clean)));
    }

    private static String statusLine(Input in, int cols, List<String> hidden) {
        StringJoiner sj = new StringJoiner(" · ");
        Catalog t = in.texts();
        sj.add(t.render(WindowText.STATUS_FILTER, "filter", in.filter().label(t)));
        if (in.paused()) sj.add(t.render(WindowText.STATUS_PAUSED, "count", in.newMessages()));
        sj.add(Heartbeat.text(in.game(), t));
        if (!hidden.isEmpty()) sj.add(t.render(WindowText.STATUS_HIDDEN, "sections", String.join(", ", hidden)));
        if (in.notice() != null) sj.add(TerminalText.sanitize(in.notice()).replace('\n', ' '));
        String text = TerminalText.truncate(sj.toString(), cols);
        int color = Heartbeat.color(in.game());
        return color == 0 ? text : Ansi.color(color) + text + Ansi.RESET;
    }
}
