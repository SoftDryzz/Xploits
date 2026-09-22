package com.xploits.sweep.core;

/**
 * Una pasada del barrido: un tramo recto en coordenadas de bloque del Nether, de un extremo al
 * otro. El coste en cohetes se calcula sumando {@link #lengthInBlocks()} de todas las pasadas antes
 * de despegar, así que esta longitud es la que decide si el jugador vuela armado de sobra o se
 * queda corto a mitad de barrido.
 */
public record Lane(double fromX, double fromZ, double toX, double toZ) {
    /** Distancia euclídea entre los dos extremos de la pasada. */
    public double lengthInBlocks() {
        return Math.hypot(toX - fromX, toZ - fromZ);
    }
}
