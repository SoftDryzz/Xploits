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
     * parsea como entero- se salta en vez de romper la lectura.
     */
    public static Coverage ofLines(Iterable<String> lines) {
        Set<ChunkPos> chunks = new HashSet<>();
        for (String line : lines) {
            ChunkPos pos = parseLine(line);
            if (pos != null) {
                chunks.add(pos);
            }
        }
        return new Coverage(chunks);
    }

    private static ChunkPos parseLine(String line) {
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
     */
    public static Coverage merge(Collection<Coverage> coverages) {
        Set<ChunkPos> union = new HashSet<>();
        for (Coverage coverage : coverages) {
            union.addAll(coverage.seen);
        }
        return new Coverage(union);
    }

    /** Si este chunk ya se ha visto. */
    public boolean seen(ChunkPos pos) {
        return seen.contains(pos);
    }

    /** Cuántos chunks distintos hay en esta cobertura. */
    public int size() {
        return seen.size();
    }
}
