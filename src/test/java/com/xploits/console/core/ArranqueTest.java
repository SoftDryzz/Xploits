package com.xploits.console.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArranqueTest {
    private static final Path JAVA = Path.of("C:\\j\\bin\\java.exe");
    private static final Path CARPETA = Path.of("C:\\mc\\meteor-client\\xploits\\consola");

    @Test
    void laOrdenExactaConUnaRutaConEspacios() {
        Arranque.Resultado r = Arranque.preparar(JAVA, true, List.of(Path.of("C:\\mods con espacio\\xploits-0.1.0.jar")),
            CARPETA, 1234, "abc");
        String interna = "chcp 65001 >nul & \"C:\\j\\bin\\java.exe\" -cp "
            + "\"C:\\mods con espacio\\xploits-0.1.0.jar\" com.xploits.console.ventana.ConsoleMain "
            + "\"C:\\mc\\meteor-client\\xploits\\consola\" 1234 abc";
        assertEquals(new Arranque.Orden(List.of("cmd.exe", "/c", "start", "Xploits consola", "cmd.exe", "/c", interna), interna), r);
    }

    @Test
    void elTituloLlevaUnEspacioPorqueSinElStartLoTomaPorElPrograma() {
        assertTrue(Arranque.TITULO.contains(" "));
    }

    @Test
    void unaRutaConAmpersandSeRechazaNombrandola() {
        Arranque.Resultado r = Arranque.preparar(JAVA, true, List.of(Path.of("C:\\m\\x.jar")),
            Path.of("C:\\Juegos & cosas\\x"), 1, "a");
        Arranque.Rechazo rechazo = assertInstanceOf(Arranque.Rechazo.class, r);
        assertTrue(rechazo.motivo().contains("C:\\Juegos & cosas\\x"), rechazo.motivo());
        assertTrue(rechazo.motivo().contains("«&»"), rechazo.motivo());
    }

    @Test
    void cadaCaracterQueCmdInterpretaSeRechaza() {
        for (String c : List.of("&", "^", "%", "!")) {
            Arranque.Resultado r = Arranque.preparar(JAVA, true, List.of(Path.of("C:\\m" + c + "n\\x.jar")), CARPETA, 1, "a");
            assertInstanceOf(Arranque.Rechazo.class, r, c);
        }
    }

    @Test
    void sinJavaOSinJarNoSeLanza() {
        assertTrue(assertInstanceOf(Arranque.Rechazo.class,
            Arranque.preparar(JAVA, false, List.of(Path.of("C:\\m\\x.jar")), CARPETA, 1, "a")).motivo().contains("java.exe"));
        assertTrue(assertInstanceOf(Arranque.Rechazo.class,
            Arranque.preparar(JAVA, true, List.of(), CARPETA, 1, "a")).motivo().contains("jar"));
    }

    @Test
    void enDesarrolloLasCarpetasSeUnenConPuntoYComa() {
        Arranque.Orden o = assertInstanceOf(Arranque.Orden.class, Arranque.preparar(JAVA, true,
            List.of(Path.of("C:\\a\\classes"), Path.of("C:\\a\\resources")), CARPETA, 1, "a"));
        assertTrue(o.descripcion().contains("-cp \"C:\\a\\classes;C:\\a\\resources\""), o.descripcion());
    }

    @Test
    void unIdDeLanzamientoRaroEsUnFalloDelAdaptador() {
        assertThrows(IllegalArgumentException.class,
            () -> Arranque.preparar(JAVA, true, List.of(Path.of("C:\\m\\x.jar")), CARPETA, 1, "a b"));
    }
}
