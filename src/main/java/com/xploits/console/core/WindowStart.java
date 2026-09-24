package com.xploits.console.core;

import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.Msg;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The command that opens the window (console spec §3.1 and §8), verified by launching it from
 * {@code javaw}: {@code cmd /c start "<title>" cmd /c "chcp 65001 & java …"}.
 *
 * <p>Two {@code cmd} traps, both checked: {@code start} takes a title without a space for the program,
 * and the characters {@code & | < > ^ % ! "} in a path are interpreted by the console. The second
 * cannot be escaped reliably, so such a path is rejected by name.
 */
public final class WindowStart {
    public static final String TITLE = "Xploits console";
    public static final String MAIN_CLASS = "com.xploits.console.window.ConsoleMain";
    private static final String FORBIDDEN = "&|<>^%!\"";

    private WindowStart() {
    }

    public sealed interface Result {
    }

    /** {@code description} is the inner command as is, to tell the player if the window never shows up. */
    public record Command(List<String> argv, String description) implements Result {
    }

    public record Rejection(Msg reason) implements Result {
    }

    public static Result prepare(Path java, boolean javaExists, List<Path> classpath, Path folder,
                                 long gamePid, String launchId, String session, Language language) {
        if (!launchId.matches("[0-9a-z]+")) throw new IllegalArgumentException("invalid launch id: " + launchId);
        if (!session.matches("[0-9a-z]+")) throw new IllegalArgumentException("invalid session id: " + session);
        if (!javaExists) return new Rejection(Msg.of(ConsoleText.NO_JAVA, "path", java.toString()));
        if (classpath.isEmpty()) return new Rejection(Msg.of(ConsoleText.NO_JAR));
        List<Path> paths = new ArrayList<>();
        paths.add(java);
        paths.addAll(classpath);
        paths.add(folder);
        for (Path path : paths) {
            String text = path.toString();
            for (char c : FORBIDDEN.toCharArray()) {
                if (text.indexOf(c) >= 0) {
                    return new Rejection(Msg.of(ConsoleText.BAD_PATH_CHARACTER, "path", text, "character", String.valueOf(c)));
                }
            }
        }
        String cp = classpath.stream().map(Path::toString).collect(Collectors.joining(";"));
        String inner = "chcp 65001 >nul & \"" + java + "\" -cp \"" + cp + "\" " + MAIN_CLASS
            + " \"" + folder + "\" " + gamePid + " " + launchId + " " + session + " " + language.code();
        return new Command(List.of("cmd.exe", "/c", "start", TITLE, "cmd.exe", "/c", inner), inner);
    }
}
