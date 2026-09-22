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

    /** Número total de chunks del rectángulo. */
    public int chunkCount() {
        return widthInChunks() * heightInChunks();
    }

    /** Tamaño del rectángulo en bloques del Overworld, para que el jugador vea qué área real cubre. */
    public String overworldEquivalent() {
        long anchoBloques = (long) widthInChunks() * BLOQUES_POR_CHUNK * RATIO_NETHER_OVERWORLD;
        long altoBloques = (long) heightInChunks() * BLOQUES_POR_CHUNK * RATIO_NETHER_OVERWORLD;
        return anchoBloques + "x" + altoBloques + " bloques del Overworld";
    }
}
