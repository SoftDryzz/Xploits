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

class ArranqueTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });
    private static final Path JAVA = Path.of("C:\\j\\bin\\java.exe");
    private static final Path CARPETA = Path.of("C:\\mc\\meteor-client\\xploits\\consola");

    @Test
    void laOrdenExactaConUnaRutaConEspacios() {
        Arranque.Resultado r = Arranque.preparar(JAVA, true, List.of(Path.of("C:\\mods con espacio\\xploits-0.1.0.jar")),
            CARPETA, 1234, "abc", "s1", Language.ES);
        String interna = "chcp 65001 >nul & \"C:\\j\\bin\\java.exe\" -cp "
            + "\"C:\\mods con espacio\\xploits-0.1.0.jar\" com.xploits.console.ventana.ConsoleMain "
            + "\"C:\\mc\\meteor-client\\xploits\\consola\" 1234 abc s1 es";
        assertEquals(new Arranque.Orden(List.of("cmd.exe", "/c", "start", "Xploits consola", "cmd.exe", "/c", interna), interna), r);
    }

    @Test
    void elTituloLlevaUnEspacioPorqueSinElStartLoTomaPorElPrograma() {
        assertTrue(Arranque.TITULO.contains(" "));
    }

    @Test
    void unaRutaConAmpersandSeRechazaNombrandola() {
        Arranque.Resultado r = Arranque.preparar(JAVA, true, List.of(Path.of("C:\\m\\x.jar")),
            Path.of("C:\\Juegos & cosas\\x"), 1, "a", "s", Language.ES);
        Arranque.Rechazo rechazo = assertInstanceOf(Arranque.Rechazo.class, r);
        String motivo = ES.render(rechazo.motivo());
        assertTrue(motivo.contains("C:\\Juegos & cosas\\x"), motivo);
        assertTrue(motivo.contains("«&»"), motivo);
    }

    @Test
    void cadaCaracterQueCmdInterpretaSeRechaza() {
        for (String c : List.of("&", "^", "%", "!")) {
            Arranque.Resultado r = Arranque.preparar(JAVA, true, List.of(Path.of("C:\\m" + c + "n\\x.jar")), CARPETA, 1, "a", "s", Language.ES);
            assertInstanceOf(Arranque.Rechazo.class, r, c);
        }
    }

    @Test
    void sinJavaOSinJarNoSeLanza() {
        assertTrue(ES.render(assertInstanceOf(Arranque.Rechazo.class,
            Arranque.preparar(JAVA, false, List.of(Path.of("C:\\m\\x.jar")), CARPETA, 1, "a", "s", Language.ES)).motivo()).contains("java.exe"));
        assertTrue(ES.render(assertInstanceOf(Arranque.Rechazo.class,
            Arranque.preparar(JAVA, true, List.of(), CARPETA, 1, "a", "s", Language.ES)).motivo()).contains("jar"));
    }

    @Test
    void enDesarrolloLasCarpetasSeUnenConPuntoYComa() {
        Arranque.Orden o = assertInstanceOf(Arranque.Orden.class, Arranque.preparar(JAVA, true,
            List.of(Path.of("C:\\a\\classes"), Path.of("C:\\a\\resources")), CARPETA, 1, "a", "s", Language.ES));
        assertTrue(o.descripcion().contains("-cp \"C:\\a\\classes;C:\\a\\resources\""), o.descripcion());
    }

    @Test
    void unIdDeLanzamientoRaroEsUnFalloDelAdaptador() {
        assertThrows(IllegalArgumentException.class,
            () -> Arranque.preparar(JAVA, true, List.of(Path.of("C:\\m\\x.jar")), CARPETA, 1, "a b", "s", Language.ES));
    }

    @Test
    void unaSesionRaraEsUnFalloDelAdaptador() {
        assertThrows(IllegalArgumentException.class,
            () -> Arranque.preparar(JAVA, true, List.of(Path.of("C:\\m\\x.jar")), CARPETA, 1, "a", "s 1", Language.ES));
        assertThrows(IllegalArgumentException.class,
            () -> Arranque.preparar(JAVA, true, List.of(Path.of("C:\\m\\x.jar")), CARPETA, 1, "a", "S1", Language.ES));
    }

    @Test
    void theLanguageIsTheLastArgument() {
        Arranque.Orden o = assertInstanceOf(Arranque.Orden.class,
            Arranque.preparar(JAVA, true, List.of(Path.of("C:\\m\\x.jar")), CARPETA, 1, "a", "s", Language.EN));
        String[] tokens = o.descripcion().split(" ");
        assertEquals("en", tokens[tokens.length - 1]);
    }

    @Test
    void aRejectionInEnglish() {
        Arranque.Rechazo r = assertInstanceOf(Arranque.Rechazo.class,
            Arranque.preparar(JAVA, true, List.of(), CARPETA, 1, "a", "s", Language.EN));
        assertEquals("the addon's jar is not on disk, and the window runs from it", EN.render(r.motivo()));
    }
}
