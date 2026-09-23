package com.xploits;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Las tres fronteras que la consola necesita y que ningún test de un núcleo ve (spec consola §13).
 * Lee el código fuente: es la única forma de impedir que un módulo nuevo se salte el registro.
 */
class FronteraTest {
    private static final Path FUENTES = Path.of("src", "main", "java");
    private static final Set<String> BASES = Set.of("com/xploits/shared/XploitsModule.java", "com/xploits/shared/ComandoBase.java");
    private static final Pattern EXTIENDE_METEOR = Pattern.compile("\\bextends\\s+(Module|Command)\\b");
    private static final Pattern CHAT_DIRECTO = Pattern.compile("ChatUtils\\.(info|warning|error)\\(");
    private static final String MARCA = "// consola: registrado aparte";
    private static final List<String> DEL_JUEGO =
        List.of("net.minecraft.", "meteordevelopment.", "net.fabricmc.", "baritone.", "com.mojang.");

    private static Map<String, List<String>> fuentes() throws IOException {
        Map<String, List<String>> todas = new TreeMap<>();
        try (Stream<Path> s = Files.walk(FUENTES)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
                todas.put(FUENTES.relativize(p).toString().replace('\\', '/'), Files.readAllLines(p, StandardCharsets.UTF_8));
            }
        }
        return todas;
    }

    @Test
    void soloLasBasesExtiendenModuleOCommandDeMeteor() throws IOException {
        List<String> fuera = new ArrayList<>();
        fuentes().forEach((ruta, lineas) -> {
            if (BASES.contains(ruta)) return;
            for (String l : lineas) {
                if (EXTIENDE_METEOR.matcher(l).find()) fuera.add(ruta);
            }
        });
        assertEquals(List.of(), fuera, "estas clases se saltarían el registro de la consola");
    }

    @Test
    void ningunChatDirectoSinMarca() throws IOException {
        List<String> sinMarca = new ArrayList<>();
        fuentes().forEach((ruta, lineas) -> {
            for (int i = 0; i < lineas.size(); i++) {
                if (!CHAT_DIRECTO.matcher(lineas.get(i)).find()) continue;
                boolean marcada = lineas.get(i).contains(MARCA) || (i > 0 && lineas.get(i - 1).contains(MARCA));
                if (!marcada) sinMarca.add(ruta + ":" + (i + 1));
            }
        });
        assertEquals(List.of(), sinMarca, "ChatUtils directo no llega a la consola: registra aparte y marca la línea");
    }

    @Test
    void losNucleosYLaVentanaNoTocanElJuego() throws IOException {
        List<String> malas = new ArrayList<>();
        fuentes().forEach((ruta, lineas) -> {
            boolean nucleo = ruta.contains("/core/");
            boolean ventana = ruta.startsWith("com/xploits/console/ventana/");
            if (!nucleo && !ventana) return;
            boolean deLaConsola = ruta.startsWith("com/xploits/console/");
            for (String l : lineas) {
                if (!l.startsWith("import ")) continue;
                String importado = l.replace("import static ", "").replace("import ", "").trim();
                for (String prefijo : DEL_JUEGO) {
                    if (importado.startsWith(prefijo)) malas.add(ruta + " importa " + importado);
                }
                if (deLaConsola && importado.startsWith("com.xploits.")
                    && !importado.startsWith("com.xploits.console.core.")
                    && !importado.startsWith("com.xploits.console.ventana.")
                    && !importado.startsWith("com.xploits.shared.core.")) {
                    malas.add(ruta + " importa " + importado);
                }
            }
        });
        assertEquals(List.of(), malas, "esto no se puede ejecutar fuera del juego");
    }

    /**
     * Packages whose player text is fully in the catalogs (language spec §6). Each migration task
     * adds its prefixes; the last one replaces the list with "com/xploits/".
     */
    private static final List<String> MIGRATED = List.of(
        "com/xploits/shared/Texts.java",
        "com/xploits/shared/Languages.java",
        "com/xploits/shared/LanguageStore.java",
        "com/xploits/shared/XploitsSettings.java",
        "com/xploits/shared/core/i18n/");
    // Note: adapted from the brief's version, which also matched the quoted argument *names* of
    // Msg.of-style calls (e.g. module.info(KEY, "choice", value)) as if they were literal message
    // text. This keeps the brief's "literal anywhere in the arguments" reach — so it still catches
    // registrar(Nivel.INFO, "texto"), responder(Nivel.INFO, fuente, "texto"), info("%s", "texto")
    // and warning(prefix + "texto") — but a scan through the call is blocked wherever it crosses a
    // MessageKey reference (an enum constant of some *Text type, e.g. LanguageText.X or "...Text.")
    // or a Msg.of(...) call: those are the values a named argument carries, not the message itself.
    private static final Pattern LITERAL_TO_PLAYER = Pattern.compile(
        "\\b(info|warning|error|infoPrivado|warningPrivado|errorPrivado|registrar|responder|avisar)"
            + "\\s*\\((?:(?!Text\\.|Msg\\.of\\()[^;])*?\"[^\"]*\\p{L}{2}");
    private static final Pattern LITERAL_TO_UI = Pattern.compile(
        "(\\.description\\(\\s*\"|\\.text\\(\\s*\"|super\\(XploitsAddon\\.CATEGORY,\\s*\"[^\"]*\",\\s*\"|TextoConPosicion\\.igual\\(\\s*\"|new TextoConPosicion\\(\\s*\")");
    private static final Pattern SPANISH_LITERAL = Pattern.compile("\"[^\"]*[áéíóúñÁÉÍÓÚÑ¿¡«»][^\"]*\"");
    private static final String ALLOWED = "// i18n: allowed";

    private static String code(String line) {
        String t = line.strip();
        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) return "";
        int comment = t.indexOf(" // ");
        return comment >= 0 ? t.substring(0, comment) : t;
    }

    @Test
    void migratedCodeSendsNoLiteralTextToThePlayer() throws IOException {
        List<String> found = new ArrayList<>();
        fuentes().forEach((ruta, lineas) -> {
            if (MIGRATED.stream().noneMatch(ruta::startsWith)) return;
            for (int i = 0; i < lineas.size(); i++) {
                String raw = lineas.get(i);
                if (raw.contains(ALLOWED)) continue;
                String c = code(raw);
                boolean exception = c.contains("Exception(") || c.contains("LOG.");
                if (LITERAL_TO_PLAYER.matcher(c).find() || LITERAL_TO_UI.matcher(c).find()
                    || (!exception && SPANISH_LITERAL.matcher(c).find())) {
                    found.add(ruta + ":" + (i + 1) + "  " + c);
                }
            }
        });
        assertEquals(List.of(), found, "player text must come from the catalogs (xploits/lang/*.lang)");
    }
}
