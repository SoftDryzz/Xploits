package com.xploits.sweep.core;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Los chunks que el jugador ya ha visto, para que el barrido planifique solo sobre lo que falta
 * (spec Nether Sweep §5.1). No lleva registro propio: {@code NewerNewChunks} escribe mientras se
 * vuela, así que esto solo tiene que leer lo que ya existe, no acumularlo por su cuenta.
 *
 * <p>Esta clase no toca disco. Recibe las líneas ya leídas -de uno o varios ficheros, según decida
 * el adaptador- y solo entiende el formato: una línea por chunk, con sus coordenadas de chunk
 * separadas por coma. Quién lee esos cinco ficheros de {@code NewerNewChunks} y en qué orden es
 * cosa del adaptador; aquí no hay ninguna ruta ni ningún nombre de fichero.
 *
 * <p><b>Una línea basura no puede tirar la lectura del resto, ni mucho menos hacer que un chunk ya
 * visto parezca sin ver.</b> {@code NewerNewChunks} escribe estos ficheros mientras el jugador
 * vuela, y un cierre brusco del cliente los deja a medio escribir: la última línea puede quedar
 * vacía, cortada a la mitad, o con un campo que no es un número. {@link #ofLines} ignora esa línea
 * y sigue con las siguientes -nunca aborta la lectura entera por una sola línea rota-, porque las
 * dos formas de fallar aquí son peores que perder un chunk suelto: tirar la lectura entera manda al
 * jugador a repetir terreno ya visto, y leer una línea a medias como si fuera un chunk válido puede
 * dar por peinada una zona que nunca se miró.
 *
 * <p><b>Y la segunda de esas dos no la puede cerrar {@link #ofLines}</b>, porque una línea cortada
 * puede parsear perfectamente: {@code "-412,1087"} truncado en {@code "-412,1"} es un {@code x,z}
 * válido de un chunk que nunca se vio. Lo único que distingue un fichero terminado de uno cortado
 * está fuera de las líneas -el salto de línea final-, así que el arreglo vive en
 * {@link #ofFileContent(String)}, que recibe el contenido entero. <b>Es la entrada buena para leer
 * un fichero;</b> {@code ofLines} se queda para quien ya tenga las líneas por otro camino y sepa que
 * están completas.
 */
public final class Coverage {
    private final Set<ChunkPos> seen;

    private Coverage(Set<ChunkPos> seen) {
        this.seen = seen;
    }

    /** Sin ningún chunk visto: el punto de partida cuando no hay ningún fichero que leer. */
    public static Coverage empty() {
        return new Coverage(Set.of());
    }

    /**
     * Lee las líneas de uno de los ficheros de {@code NewerNewChunks}, una por chunk. Cada línea
     * válida tiene exactamente dos campos separados por coma, {@code x,z} en coordenadas de chunk;
     * cualquier otra cosa -línea vacía, con espacios, con un solo campo, con un campo que no
     * parsea como entero, o una línea nula- se salta en vez de romper la lectura.
     *
     * <p>La línea nula entra en esa lista y no es un detalle de nada: el javadoc promete saltarse
     * «cualquier otra cosa», y una promesa así en el núcleo se cumple entera o no vale nada. Sin
     * ella, una lista con un nulo dentro reventaba con un {@code NullPointerException} desde dentro
     * de la lectura, justo en el camino cuyo propósito declarado es no abortar nunca por una línea
     * mala y cuyo fallo cuesta replanificar terreno ya visto.
     *
     * @throws NullPointerException si {@code lines} es nulo: eso no es una línea mala, es no haber
     *                              traído nada que leer
     */
    public static Coverage ofLines(Iterable<String> lines) {
        if (lines == null) {
            throw new NullPointerException("hacen falta las líneas que leer: una lista nula no es un"
                + " fichero vacío, es no haber leído nada"); // i18n: allowed (exception message, continuation line)
        }

        Set<ChunkPos> chunks = new HashSet<>();
        for (String line : lines) {
            ChunkPos pos = parseLine(line);
            if (pos != null) {
                chunks.add(pos);
            }
        }
        return new Coverage(chunks);
    }

    /**
     * Lee el contenido entero de uno de los ficheros de {@code NewerNewChunks}, <b>descartando la
     * última línea si el fichero no termina en salto de línea</b>.
     *
     * <p>Es el arreglo del fallo que el javadoc de esta clase nombra y que {@link #ofLines} no puede
     * cerrar. {@code NewerNewChunks} escribe mientras el jugador vuela; si el cliente se cierra de
     * golpe a mitad de escritura, la última línea se queda cortada. Una línea cortada que no parsea
     * ya se salta -{@code "-412,"} o {@code "-41"}-, pero <b>una línea cortada puede parsear
     * perfectamente</b>: lo que iba a ser {@code "-412,1087"} se queda en {@code "-412,1"}, que es un
     * {@code x,z} válido de un chunk que nunca se vio. Marcar visto un chunk que no se vio es peor
     * que perder uno visto: si era el que le faltaba a su banda, {@link SweepPlanner} se salta la
     * banda entera y una pasada de punta a punta no se vuela y se da por peinada igual (spec §9).
     *
     * <p>No hay forma de distinguir esa línea mirándola, así que se mira <b>el fichero</b>: el único
     * indicio de que la escritura terminó es el salto de línea final. Si está, todas las líneas son
     * de fiar; si no está, la última se tira sin más análisis.
     *
     * <p><b>Lo que cuesta cuando no ha pasado nada:</b> normalmente nada. Comprobado en los ficheros
     * de esta instancia, {@code NewerNewChunks} los deja terminados en salto de línea, así que el
     * caso normal no pierde ni un chunk; solo se pierde uno en el fichero que de verdad se quedó a
     * medias, y ahí perderlo es justo lo que se quiere. Aun si algún día escribiera sin el salto
     * final, el coste sería un chunk replanificado -volar de más-, que es el lado barato.
     *
     * <p>Recibe el contenido y no una ruta: esta clase sigue sin tocar disco. Leer el fichero es del
     * adaptador; saber qué significa que no acabe en salto de línea, de aquí.
     *
     * @throws NullPointerException si {@code content} es nulo
     */
    public static Coverage ofFileContent(String content) {
        if (content == null) {
            throw new NullPointerException("el contenido del fichero de cobertura no puede ser nulo");
        }
        if (content.isEmpty()) {
            return empty();
        }

        String[] lineas = content.split(TERMINADORES, -1);
        int hasta = terminaEnSaltoDeLinea(content) ? lineas.length : lineas.length - 1;

        Set<ChunkPos> chunks = new HashSet<>();
        for (int i = 0; i < hasta; i++) {
            ChunkPos pos = parseLine(lineas[i]);
            if (pos != null) {
                chunks.add(pos);
            }
        }
        return new Coverage(chunks);
    }

    /**
     * Cómo se parte el contenido en líneas: {@code \r\n} primero para que un fin de línea de Windows
     * no cuente como dos.
     *
     * <p>A propósito <b>no</b> es {@code \R}, que además casa con la tabulación vertical, el avance
     * de página y tres separadores Unicode. No porque den miedo, sino porque {@link
     * #terminaEnSaltoDeLinea} tiene que reconocer exactamente el mismo conjunto: si uno partiera por
     * un carácter que el otro no considera fin de línea, un fichero terminado en él se leería como
     * truncado y perdería su última línea buena. Dos listas que tienen que coincidir son una fuente
     * de fallos; una lista corta que cubre lo que este fichero puede traer -dígitos, comas y saltos
     * de línea- no lo es.
     */
    private static final String TERMINADORES = "\r\n|\r|\n";

    /**
     * Si el contenido termina en un salto de línea, que es el único indicio de que la escritura de la
     * última línea llegó a terminar.
     *
     * <p>Basta mirar el último carácter: de los tres terminadores de {@link #TERMINADORES}, dos son
     * un solo carácter y el tercero, {@code \r\n}, acaba en uno de ellos.
     */
    private static boolean terminaEnSaltoDeLinea(String content) {
        char ultimo = content.charAt(content.length() - 1);
        return ultimo == '\n' || ultimo == '\r';
    }

    private static ChunkPos parseLine(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String[] fields = trimmed.split(",", -1);
        if (fields.length != 2) {
            return null;
        }
        try {
            int x = Integer.parseInt(fields[0].trim());
            int z = Integer.parseInt(fields[1].trim());
            return new ChunkPos(x, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * La unión de varios conjuntos de cobertura, sin duplicados. {@code NewerNewChunks} reparte lo
     * visto entre cinco ficheros distintos; esto es lo que junta sus cinco lecturas en una sola
     * respuesta a "¿se ha visto ya este chunk?".
     *
     * <p>Una colección vacía -que no exista ningún fichero que leer- da {@link #empty()}, no un
     * error: no tener ningún registro previo significa empezar de cero, no que algo haya fallado.
     *
     * <p>Y por el mismo motivo <b>una lectura nula dentro de la colección se salta</b> en vez de
     * tirar la unión entera: es el mismo criterio que {@link #ofLines} aplica a una línea mala, y el
     * coste de romper aquí es el mismo -la cobertura se lee vacía y el barrido replanifica horas de
     * terreno ya visto-. Que no haya salido nada de uno de los cinco ficheros no puede llevarse por
     * delante lo que sí salió de los otros cuatro.
     *
     * @throws NullPointerException si {@code coverages} es nulo: eso no es un fichero que faltara,
     *                              es no haber mirado
     */
    public static Coverage merge(Collection<Coverage> coverages) {
        if (coverages == null) {
            throw new NullPointerException("hacen falta las lecturas que unir: una colección nula no"
                + " es «ningún fichero», es no haber mirado"); // i18n: allowed (exception message, continuation line)
        }

        Set<ChunkPos> union = new HashSet<>();
        for (Coverage coverage : coverages) {
            if (coverage == null) {
                continue;
            }
            union.addAll(coverage.seen);
        }
        return new Coverage(union);
    }

    /** Si este chunk ya se ha visto. */
    public boolean seen(ChunkPos pos) {
        return seen.contains(pos);
    }

    /**
     * Cuántos chunks distintos hay en esta cobertura, <b>en toda la dimensión</b>.
     *
     * <p>Casi nunca es el número que se le quiere enseñar al jugador. Lo que le dice cuánto le ahorra
     * su cobertura previa -y por tanto si el barrido vale las horas que cuesta- es cuántos chunks
     * <b>del área que ha pedido</b> ya estaban vistos, y eso es {@link #seenIn(SweepArea)}. Este
     * número puede ser mayor que el área entera: con los 17.369 chunks acumulados de spec §1 y un
     * rectángulo nuevo de 60x60, diría «de los 3.600 chunks del área, 17.369 ya estaban vistos».
     */
    public int size() {
        return seen.size();
    }

    /**
     * Cuántos chunks <b>de este área</b> ya se han visto. Es la respuesta a «¿cuánto me ahorra lo que
     * ya tengo?», que es la mitad de lo que el jugador necesita para decidir si el barrido merece la
     * pena; la otra mitad es cuántos chunks tiene el área.
     *
     * <p>Se recorre el área y no la cobertura porque el área es lo acotado: la cobertura de una
     * dimensión entera puede ser mucho mayor que el rectángulo, y al revés nunca importa -un chunk
     * visto fuera del área no ahorra ni un bloque de vuelo-.
     */
    public int seenIn(SweepArea area) {
        if (area == null) throw new NullPointerException("hace falta un área para contar dentro de ella");

        int vistos = 0;
        for (int x = area.minChunkX(); x <= area.maxChunkX(); x++) {
            for (int z = area.minChunkZ(); z <= area.maxChunkZ(); z++) {
                if (seen.contains(new ChunkPos(x, z))) {
                    vistos++;
                }
            }
        }
        return vistos;
    }
}
