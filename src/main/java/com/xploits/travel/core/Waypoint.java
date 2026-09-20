package com.xploits.travel.core;

/**
 * Un punto en el plano XZ, sin altura: Baritone resuelve la Y por su cuenta al perseguir un
 * {@code goal} de dos coordenadas.
 */
public record Waypoint(double x, double z) {
    /** Distancia euclídea en el plano XZ hasta {@code other}. */
    public double distanceTo(Waypoint other) {
        double dx = other.x() - x;
        double dz = other.z() - z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
