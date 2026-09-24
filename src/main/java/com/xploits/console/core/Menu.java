package com.xploits.console.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Msg;

/**
 * The window's menu, like CustomCLI's but with numbers: normal line input is enough and the console
 * does not need to be put in key-by-key mode (console spec §6).
 */
public final class Menu {
    private Menu() {
    }

    /** The menu line: the digits and their order are fixed, the words come from the catalog. */
    public static String line(Catalog texts) {
        return texts.render(WindowText.MENU_LINE);
    }

    public sealed interface Command {
    }

    public record ChangeFilter(LogFilter filter) implements Command {
    }

    public record TogglePause() implements Command {
    }

    public record Quit() implements Command {
    }

    public record Unknown(Msg reason) implements Command {
    }

    public static Command parse(String line) {
        String clean = line.strip();
        return switch (clean) {
            case "1" -> new ChangeFilter(LogFilter.ALL);
            case "2" -> new ChangeFilter(LogFilter.PVP);
            case "3" -> new ChangeFilter(LogFilter.TRAVEL);
            case "4" -> new ChangeFilter(LogFilter.SWEEP);
            case "5" -> new ChangeFilter(LogFilter.WARNINGS);
            case "6" -> new TogglePause();
            case "0" -> new Quit();
            case "" -> new Unknown(Msg.of(WindowText.MENU_EMPTY));
            default -> new Unknown(Msg.of(WindowText.MENU_UNKNOWN, "input", TerminalText.truncate(TerminalText.sanitize(clean), 20)));
        };
    }
}
