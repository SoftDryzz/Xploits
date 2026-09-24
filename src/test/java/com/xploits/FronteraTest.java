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
import java.util.TreeSet;
import java.util.regex.Matcher;
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
    private static final List<String> MIGRATED = List.of("com/xploits/");
    // Note: adapted from the brief's version, which also matched the quoted argument *names* of
    // Msg.of-style calls (e.g. module.info(KEY, "choice", value)) as if they were literal message
    // text. This keeps the brief's "literal anywhere in the arguments" reach — so it still catches
    // registrar(Nivel.INFO, "texto"), responder(Nivel.INFO, fuente, "texto"), info("%s", "texto")
    // and warning(prefix + "texto") — but a scan through the call is blocked wherever it crosses a
    // MessageKey reference (an enum constant of some *Text type, e.g. LanguageText.X or "...Text.")
    // or a Msg.of(...) call: those are the values a named argument carries, not the message itself.
    // A logger call (LOG.info/warn/error) is developer text, not player text, and is not matched.
    private static final Pattern LITERAL_TO_PLAYER = Pattern.compile(
        "(?<!LOG\\.)\\b(info|warning|error|infoPrivado|warningPrivado|errorPrivado|registrar|responder|avisar)"
            + "\\s*\\((?:(?!Text\\.|Msg\\.of\\()[^;])*?\"[^\"]*\\p{L}{2}");
    private static final Pattern LITERAL_TO_UI = Pattern.compile(
        "(\\.description\\(\\s*\"|\\.text\\(\\s*\"|super\\(XploitsAddon\\.CATEGORY,\\s*\"[^\"]*\",\\s*\")");
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

    /**
     * Source path prefixes whose identifiers and main string literals are fully English (code-in-English
     * design §8). Each migration task appends its prefixes; the last replaces this with "com/xploits/".
     */
    private static final List<String> ENGLISH = List.of(
        "com/xploits/autotpy/core/",
        "com/xploits/elytra/",
        "com/xploits/kitrequester/inventory/",
        "com/xploits/mixin/",
        "com/xploits/shared/core/",
        "com/xploits/shared/core/migration/",
        "com/xploits/commands/CommandText.java",
        "com/xploits/commands/CommandTextTest.java",
        "com/xploits/console/core/ConsoleText.java",
        "com/xploits/console/core/CoordinatePolicyTest.java",
        "com/xploits/console/core/Escape.java",
        "com/xploits/console/core/WindowText.java",
        "com/xploits/kitrequester/core/Action.java",
        "com/xploits/kitrequester/core/CandidateTracker.java",
        "com/xploits/kitrequester/core/CandidateTrackerTest.java",
        "com/xploits/kitrequester/core/CourierPolicy.java",
        "com/xploits/kitrequester/core/CourierPolicyTest.java",
        "com/xploits/kitrequester/core/KitQueue.java",
        "com/xploits/kitrequester/core/KitQueueTest.java",
        "com/xploits/kitrequester/core/KitText.java",
        "com/xploits/kitrequester/core/KitTextTest.java",
        "com/xploits/kitrequester/core/OrderMachine.java",
        "com/xploits/kitrequester/core/OrderMachineTest.java",
        "com/xploits/kitrequester/core/Progress.java",
        "com/xploits/kitrequester/core/ProgressStoreTest.java",
        "com/xploits/pvp/core/FriendLedger.java",
        "com/xploits/pvp/core/ManagedModule.java",
        "com/xploits/pvp/core/Plan.java",
        "com/xploits/pvp/core/Resource.java",
        "com/xploits/pvp/core/RetreatWatchTest.java",
        "com/xploits/pvp/core/Skipped.java",
        "com/xploits/pvp/core/Snapshots.java",
        "com/xploits/shared/ChatLogFilter.java",
        "com/xploits/shared/LanguageStore.java",
        "com/xploits/shared/Languages.java",
        "com/xploits/shared/SettingsMigration.java",
        "com/xploits/shared/Texts.java",
        "com/xploits/shared/XploitsSettings.java",
        "com/xploits/shared/chat/ChatEvent.java",
        "com/xploits/shared/chat/ChatPatternsTest.java",
        "com/xploits/stash/core/ContainerSnapshot.java",
        "com/xploits/stash/core/ContainerSnapshotTest.java",
        "com/xploits/stash/core/ContainerType.java",
        "com/xploits/stash/core/NestedShulker.java",
        "com/xploits/stash/core/StashIndex.java",
        "com/xploits/stash/core/StashIndexTest.java",
        "com/xploits/stash/core/StashStoreTest.java",
        "com/xploits/stash/core/StashText.java",
        "com/xploits/sweep/core/ChunkPos.java",
        "com/xploits/sweep/core/Lane.java",
        "com/xploits/sweep/core/SweepText.java",
        "com/xploits/travel/core/Axis.java",
        "com/xploits/travel/core/BaritoneScript.java",
        "com/xploits/travel/core/BaritoneScriptTest.java",
        "com/xploits/travel/core/DestinationTest.java",
        "com/xploits/travel/core/FireworkWatch.java",
        "com/xploits/travel/core/PatternParams.java",
        "com/xploits/travel/core/Route.java",
        "com/xploits/travel/core/TravelTextTest.java",
        "com/xploits/travel/core/Waypoint.java"
    );

    /** Glossary §8: distinctive Spanish words, matched as whole camel/snake-case words. */
    private static final Set<String> SPANISH_WORDS = Set.of(
        "accion",
        "acercamiento",
        "ahora",
        "ajeno",
        "amenazado",
        "amigo",
        "anchura",
        "anillo",
        "apagado",
        "apagar",
        "apilado",
        "arranque",
        "autopista",
        "avisar",
        "aviso",
        "avisos",
        "barrido",
        "bloques",
        "borra",
        "borrar",
        "cabecera",
        "cambio",
        "cancelada",
        "carpeta",
        "caso",
        "centinela",
        "cero",
        "cerrado",
        "cerrojo",
        "ciclo",
        "cierre",
        "cobertura",
        "codificacion",
        "cohetes",
        "colector",
        "comando",
        "combate",
        "consola",
        "coordenadas",
        "corta",
        "corto",
        "cuenta",
        "desconocida",
        "desconocido",
        "desde",
        "distancia",
        "encender",
        "enterrado",
        "entrada",
        "entregada",
        "envolver",
        "escribir",
        "esperando",
        "espiral",
        "estado",
        "fallo",
        "fase",
        "fecha",
        "fichero",
        "filas",
        "filtro",
        "formato",
        "foto",
        "frontera",
        "gasto",
        "generacion",
        "glifos",
        "historial",
        "hueco",
        "idioma",
        "inicio",
        "instantanea",
        "juego",
        "jugador",
        "lanza",
        "lanzamiento",
        "lanzando",
        "lanzar",
        "latido",
        "lector",
        "leer",
        "lento",
        "limite",
        "linea",
        "lineas",
        "marca",
        "marco",
        "maximo",
        "medidor",
        "medir",
        "mensaje",
        "minimo",
        "modulos",
        "muestras",
        "nivel",
        "nombre",
        "nuevo",
        "observacion",
        "ofrecida",
        "orden",
        "pasada",
        "pasadas",
        "pausa",
        "pendiente",
        "perdida",
        "perdidas",
        "persecucion",
        "posicion",
        "privado",
        "progreso",
        "que",
        "quiebro",
        "rechazo",
        "recorrido",
        "recortar",
        "recto",
        "recursos",
        "registro",
        "relativo",
        "repartidor",
        "responde",
        "resultado",
        "rodeado",
        "rotacion",
        "rotado",
        "salida",
        "salir",
        "salto",
        "secuencia",
        "seguidor",
        "segundos",
        "senuelo",
        "sigue",
        "sumidero",
        "superficie",
        "tamano",
        "techo",
        "teclado",
        "texto",
        "titulo",
        "tranquilo",
        "ultimo",
        "umbral",
        "una",
        "usuario",
        "vecindario",
        "ventana",
        "viva",
        "vivo",
        "vuelo"
    );

    /** Glossary §2 old simple type names and §3 old enum constants that the word list cannot see. */
    private static final Set<String> OLD_NAMES = Set.of(
        "Colector", "Consola", "Aviso", "Repartidor", "Lanzamiento", "Salida", "Sumidero", "Ofrecida",
        "Anillo", "Arranque", "Resultado", "Orden", "Rechazo", "Cabecera", "Centinela", "Ciclo",
        "Observacion", "Accion", "Lanzar", "EscribirFin", "VigilarCierre", "Avisar", "ApagarModulo",
        "Fase", "Filtro", "Formato", "Glifos", "Historial", "Instantanea", "Progreso", "EstadoModulo",
        "Latido", "EstadoJuego", "SinDatos", "Vivo", "Lento", "NoResponde", "Cerrado",
        "CerradoSinDespedirse", "Lector", "Cambio", "Marco", "Entrada", "CambiarFiltro", "AlternarPausa",
        "Salir", "Desconocida", "Nivel", "Perdidas", "Registro", "Mensaje", "Foto", "Juego", "Fin",
        "Perdida", "Rotacion", "Fichero", "Secuencia", "Tamano", "Texto", "Medidor", "Seguidor", "Cola",
        "Teclado", "Vecindario", "ComandoBase", "LecturaDeCobertura", "FronteraTest", "AnilloTest",
        "ArranqueTest", "CabeceraTest", "CentinelaTest", "CicloTest", "FiltroTest", "FormatoTest",
        "GlifosTest", "HistorialTest", "InstantaneaTest", "LatidoTest", "LectorTest", "MarcoTest",
        "PerdidasTest", "RegistroTest", "RotacionTest", "SecuenciaTest", "TamanoTest", "TextoTest", "Caso",
        "APAGADO", "PENDIENTE", "LANZANDO", "ESPERANDO_PID", "VIVA", "CERRANDO", "TODO", "AVISOS", "SIGUE",
        "ROTADO", "AVISO", "AJENO", "AMIGO", "USUARIO_TPY", "TRANQUILO", "AMENAZADO", "SIN_COMBATE",
        "ACERCAMIENTO", "SUPERFICIE", "RODEADO", "ENTERRADO", "PERSECUCION", "SIN_RECURSOS",
        "STATE_SIN_COMBATE", "STATE_ACERCAMIENTO", "STATE_SUPERFICIE", "STATE_RODEADO", "STATE_ENTERRADO",
        "STATE_PERSECUCION", "STATE_SIN_RECURSOS", "POSTURE_TRANQUILO", "POSTURE_AMENAZADO", "COORDENADAS",
        "RELATIVO", "AUTOPISTA", "ENCENDER", "APAGAR", "NADA", "RECTO", "QUIEBRO", "ESPIRAL", "SENUELO",
        "COMANDO", "ENTREGADA", "CANCELADA", "SIN_JUGADOR", "SETTING_QUIEBRO_LEG", "SETTING_QUIEBRO_OFFSET"
    );

    private static final Path TEST_SOURCES = Path.of("src", "test", "java");
    private static final Pattern STRIP = Pattern.compile(
        "\"\"\"(?:.|\\n)*?\"\"\"|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'|//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_\\u00C0-\\u017F][A-Za-z0-9_\\u00C0-\\u017F]*");
    private static final Pattern WORD = Pattern.compile("[A-Z\\u00C0-\\u00DE]?[a-z\\u00DF-\\u00FF]+|[A-Z\\u00C0-\\u00DE]+(?![a-z])");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\\\n])*\"");
    private static final Pattern SPANISH_MARKS = Pattern.compile("[áéíóúñÁÉÍÓÚÑ¿¡«»]");

    private static Map<String, String> sourcesUnder(Path root) throws IOException {
        Map<String, String> all = new TreeMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
                all.put(root.relativize(p).toString().replace('\\', '/'), Files.readString(p, StandardCharsets.UTF_8));
            }
        }
        return all;
    }

    private static boolean english(String path) {
        return ENGLISH.stream().anyMatch(path::startsWith);
    }

    @Test
    void identifiersInEnglishPackagesHaveNoSpanishWords() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path root : List.of(FUENTES, TEST_SOURCES)) {
            sourcesUnder(root).forEach((path, text) -> {
                if (!english(path)) return;
                String code = STRIP.matcher(text).replaceAll(" ");
                Set<String> seen = new TreeSet<>();
                for (String line : code.split("\n")) {
                    String t = line.strip();
                    if (t.startsWith("import ") || t.startsWith("package ")) continue;
                    Matcher m = IDENT.matcher(line);
                    while (m.find()) {
                        String id = m.group();
                        if (OLD_NAMES.contains(id)) seen.add(id);
                        Matcher w = WORD.matcher(id);
                        while (w.find()) {
                            if (SPANISH_WORDS.contains(w.group().toLowerCase(java.util.Locale.ROOT))) seen.add(id);
                        }
                    }
                }
                for (String id : seen) found.add(path + ": " + id);
            });
        }
        assertEquals(List.of(), found, "Spanish identifiers left (see the code-in-English glossary)");
    }

    @Test
    void mainStringLiteralsInEnglishPackagesHaveNoSpanishMarks() throws IOException {
        List<String> found = new ArrayList<>();
        sourcesUnder(FUENTES).forEach((path, text) -> {
            if (!english(path)) return;
            String[] lines = text.split("\n");
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains("// i18n: allowed")) continue;
                Matcher m = STRING_LITERAL.matcher(lines[i]);
                while (m.find()) {
                    if (SPANISH_MARKS.matcher(m.group()).find()) found.add(path + ":" + (i + 1) + "  " + m.group());
                }
            }
        });
        assertEquals(List.of(), found, "Spanish text in code: player text belongs in the catalogs, the rest in English");
    }
}
