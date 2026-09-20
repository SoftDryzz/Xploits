package com.xploits.kitrequester.core;

/**
 * La lógica pura del candidato de {@code EnderDepositor} (spec §6.1): qué interacción del jugador
 * -o del propio depositor- se vio más recientemente, y si todavía es de fiar. Puro: no importa nada
 * de {@code net.minecraft} ni de {@code meteordevelopment}. Las posiciones se representan como
 * {@code long} -en el adaptador, {@code BlockPos.asLong()}- porque a este nivel no importa qué tipo
 * de coordenada sea, solo si dos interacciones caen en el mismo sitio.
 *
 * <p>Sin esto, una pantalla que se abre no dice por sí sola qué la respalda: es la única señal
 * disponible para atar la operación a un bloque concreto y para saber que un clic ajeno -de bloque o
 * de entidad- puede tener una pantalla en camino todavía sin llegar.
 */
public final class CandidateTracker {
    private final long timeoutMs;

    private boolean hasCandidate;
    private long candidate;
    private long candidateAt;
    /**
     * Si ha habido alguna interacción con una entidad -vagoneta o barca con cofre, que abren una
     * pantalla de contenedor sin que haya ningún {@code BlockPos} que anotar, porque no son bloques-.
     * Campo aparte, no un valor centinela en {@link #entityInteractionAt}: antes de la primera
     * interacción no hay ninguna hora que restar de forma segura contra {@code now} sin arriesgar un
     * desbordamiento de {@code long}.
     */
    private boolean hasEntityInteraction;
    /** Última vez (ms) que se vio una interacción con una entidad. Solo válido si {@link #hasEntityInteraction}. */
    private long entityInteractionAt;

    public CandidateTracker(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    /**
     * Anota una interacción de bloque como candidato, si de verdad puede respaldar la pantalla que
     * se espera. {@code opensContainer} lo decide quien llama -el filtro por tipo de bloque es cosa
     * de Minecraft, no de este core-: colocar un bloque, abrir una puerta o los
     * {@code BlockUtils.place} de {@code surround}/{@code auto-trap} no deben poder abortar un
     * depósito en curso ni bloquear el siguiente (spec §6.1, punto 2).
     */
    public void noteBlockInteraction(long pos, boolean opensContainer, long now) {
        if (!opensContainer) return;
        hasCandidate = true;
        candidate = pos;
        candidateAt = now;
    }

    /**
     * Anota una interacción con una entidad. No hay {@code BlockPos} que comparar -a diferencia de
     * {@link #noteBlockInteraction}-, así que solo sirve para {@link #blocksStart}: "algo ajeno
     * puede estar en vuelo", sin más detalle.
     */
    public void noteEntityInteraction(long now) {
        hasEntityInteraction = true;
        entityInteractionAt = now;
    }

    /**
     * Caduca el candidato de bloque si lleva más de {@code timeoutMs} sin refrescarse. Perezoso:
     * solo se comprueba cuando se llama, nunca solo. Llamar antes de {@link #blocksStart}.
     */
    public void expire(long now) {
        if (hasCandidate && now - candidateAt > timeoutMs) hasCandidate = false;
    }

    /**
     * true si hay un candidato de bloque vigente o una interacción de entidad reciente sin resolver
     * -en ambos casos, una pantalla ajena que puede no haber llegado todavía-. Para el guardián de
     * {@code start()}: negarse a empezar mientras esto sea cierto es lo que evita pisar una
     * interacción cuya pantalla, cuando llegue, se confundiría con la propia.
     */
    public boolean blocksStart(long now) {
        return hasCandidate || (hasEntityInteraction && now - entityInteractionAt <= timeoutMs);
    }

    /** true si el candidato de bloque vigente es exactamente esta posición. */
    public boolean matches(long pos) {
        return hasCandidate && candidate == pos;
    }

    /** Olvida el candidato de bloque. La interacción de entidad no se olvida: caduca sola con el tiempo. */
    public void clear() {
        hasCandidate = false;
    }
}
