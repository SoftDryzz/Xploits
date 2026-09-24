package com.xploits.console.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowStartTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });
    private static final Path JAVA = Path.of("C:\\j\\bin\\java.exe");
    private static final Path FOLDER = Path.of("C:\\mc\\meteor-client\\xploits\\console");

    @Test
    void theExactCommandWithAPathWithSpaces() {
        WindowStart.Result r = WindowStart.prepare(JAVA, true, List.of(Path.of("C:\\mods with spaces\\xploits-0.1.0.jar")),
            FOLDER, 1234, "abc", "s1", Language.ES);
        String inner = "chcp 65001 >nul & \"C:\\j\\bin\\java.exe\" -cp "
            + "\"C:\\mods with spaces\\xploits-0.1.0.jar\" com.xploits.console.window.ConsoleMain "
            + "\"C:\\mc\\meteor-client\\xploits\\console\" 1234 abc s1 es";
        assertEquals(new WindowStart.Command(List.of("cmd.exe", "/c", "start", "Xploits console", "cmd.exe", "/c", inner), inner), r);
    }

    @Test
    void theTitleHasASpaceBecauseWithoutItStartTakesItForTheProgram() {
        assertTrue(WindowStart.TITLE.contains(" "));
    }

    @Test
    void aPathWithAnAmpersandIsRejectedByName() {
        WindowStart.Result r = WindowStart.prepare(JAVA, true, List.of(Path.of("C:\\m\\x.jar")),
            Path.of("C:\\Games & stuff\\x"), 1, "a", "s", Language.ES);
        WindowStart.Rejection rejection = assertInstanceOf(WindowStart.Rejection.class, r);
        String reason = ES.render(rejection.reason());
        assertTrue(reason.contains("C:\\Games & stuff\\x"), reason);
        assertTrue(reason.contains("«&»"), reason);
    }

    @Test
    void everyCharacterCmdInterpretsIsRejected() {
        for (String c : List.of("&", "^", "%", "!")) {
            WindowStart.Result r = WindowStart.prepare(JAVA, true, List.of(Path.of("C:\\m" + c + "n\\x.jar")), FOLDER, 1, "a", "s", Language.ES);
            assertInstanceOf(WindowStart.Rejection.class, r, c);
        }
    }

    @Test
    void withoutJavaOrJarNothingIsLaunched() {
        assertTrue(ES.render(assertInstanceOf(WindowStart.Rejection.class,
            WindowStart.prepare(JAVA, false, List.of(Path.of("C:\\m\\x.jar")), FOLDER, 1, "a", "s", Language.ES)).reason()).contains("java.exe"));
        assertTrue(ES.render(assertInstanceOf(WindowStart.Rejection.class,
            WindowStart.prepare(JAVA, true, List.of(), FOLDER, 1, "a", "s", Language.ES)).reason()).contains("jar"));
    }

    @Test
    void inDevelopmentTheFoldersAreJoinedWithSemicolons() {
        WindowStart.Command c = assertInstanceOf(WindowStart.Command.class, WindowStart.prepare(JAVA, true,
            List.of(Path.of("C:\\a\\classes"), Path.of("C:\\a\\resources")), FOLDER, 1, "a", "s", Language.ES));
        assertTrue(c.description().contains("-cp \"C:\\a\\classes;C:\\a\\resources\""), c.description());
    }

    @Test
    void aStrangeLaunchIdIsAnAdapterBug() {
        assertThrows(IllegalArgumentException.class,
            () -> WindowStart.prepare(JAVA, true, List.of(Path.of("C:\\m\\x.jar")), FOLDER, 1, "a b", "s", Language.ES));
    }

    @Test
    void aStrangeSessionIsAnAdapterBug() {
        assertThrows(IllegalArgumentException.class,
            () -> WindowStart.prepare(JAVA, true, List.of(Path.of("C:\\m\\x.jar")), FOLDER, 1, "a", "s 1", Language.ES));
        assertThrows(IllegalArgumentException.class,
            () -> WindowStart.prepare(JAVA, true, List.of(Path.of("C:\\m\\x.jar")), FOLDER, 1, "a", "S1", Language.ES));
    }

    @Test
    void theLanguageIsTheLastArgument() {
        WindowStart.Command c = assertInstanceOf(WindowStart.Command.class,
            WindowStart.prepare(JAVA, true, List.of(Path.of("C:\\m\\x.jar")), FOLDER, 1, "a", "s", Language.EN));
        String[] tokens = c.description().split(" ");
        assertEquals("en", tokens[tokens.length - 1]);
    }

    @Test
    void aRejectionInEnglish() {
        WindowStart.Rejection r = assertInstanceOf(WindowStart.Rejection.class,
            WindowStart.prepare(JAVA, true, List.of(), FOLDER, 1, "a", "s", Language.EN));
        assertEquals("the addon's jar is not on disk, and the window runs from it", EN.render(r.reason()));
    }
}
