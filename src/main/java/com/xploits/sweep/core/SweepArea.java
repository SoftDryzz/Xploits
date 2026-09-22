package com.xploits.sweep.core;

/**
 * El rectángulo a barrer, en coordenadas de chunk del Nether, con las esquinas ya normalizadas
 * (min ≤ max en cada eje). El jugador lo piensa en chunks del Nether porque ahí es donde vuela;
 * {@link #overworldEquivalent()} lo traduce a bloques del Overworld (×8 sobre bloques del Nether,
 * y cada chunk son 16 bloques) para que sepa qué extensión real está cubriendo.
 */
public record SweepArea(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
    private static final int BLOQUES_POR_CHUNK = 16;
    private static final int RATIO_NETHER_OVERWORLD = 8;

    /**
     * Rechaza, no reordena. Los nombres de los componentes prometen cuál es el borde menor y cuál
     * el mayor de cada eje; si se aceptara un min &gt; max en silencio, {@code chunkCount()} y
     * {@code overworldEquivalent()} podían contradecirse en la misma llamada (una anunciaba área
     * negativa, la otra un recuento positivo). Sigue el precedente de {@code Destination.java}: el
     * constructor canónico valida y rechaza, no repara; quien no conozca el orden de las esquinas
     * usa {@link #ofChunks} para que se normalicen antes de llegar aquí.
     */
    public SweepArea {
        if (minChunkX > maxChunkX || minChunkZ > maxChunkZ) {
            throw new IllegalArgumentException(
                "minChunkX/minChunkZ deben ser <= que maxChunkX/maxChunkZ respectivamente; "
                    + "usa ofChunks si no conoces de antemano el orden de las esquinas");
        }
    }

    /**
     * Construye el área a partir de dos esquinas cualesquiera, en cualquier orden: normaliza cada
     * eje por separado con min/max, porque dar la esquina «mayor» primero es el error de dedo más
     * fácil de cometer al teclear coordenadas.
     */
    public static SweepArea ofChunks(int chunkX1, int chunkZ1, int chunkX2, int chunkZ2) {
        return new SweepArea(
            Math.min(chunkX1, chunkX2), Math.min(chunkZ1, chunkZ2),
            Math.max(chunkX1, chunkX2), Math.max(chunkZ1, chunkZ2));
    }

    /** Anchura en chunks, contando ambos bordes: un área de un solo chunk mide 1, no 0. */
    public int widthInChunks() {
        return maxChunkX - minChunkX + 1;
    }

    /** Altura en chunks, contando ambos bordes. */
    public int heightInChunks() {
        return maxChunkZ - minChunkZ + 1;
    }

    /**
     * Número total de chunks del rectángulo. Se multiplica en {@code long} para no desbordar antes
     * de comprobar el rango: con anchura y altura como {@code int}, hacer la cuenta directamente en
     * {@code int} podía envolverse a un número negativo con pinta de válido para áreas grandes pero
     * tecleables (muy por debajo del límite real del mundo). Si ni así cabe en un {@code int} se
     * falla alto en vez de devolver ese número envuelto.
     */
    public int chunkCount() {
        long total = (long) widthInChunks() * heightInChunks();
        if (total > Integer.MAX_VALUE) {
            throw new ArithmeticException(
                "el área tiene " + total + " chunks, demasiados para contarlos en un entero");
        }
        return (int) total;
    }

    /** Tamaño del rectángulo en bloques del Overworld, para que el jugador vea qué área real cubre. */
    public String overworldEquivalent() {
        long anchoBloques = (long) widthInChunks() * BLOQUES_POR_CHUNK * RATIO_NETHER_OVERWORLD;
        long altoBloques = (long) heightInChunks() * BLOQUES_POR_CHUNK * RATIO_NETHER_OVERWORLD;
        return anchoBloques + "x" + altoBloques + " bloques del Overworld";
    }
}
