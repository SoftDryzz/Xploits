package com.xploits.stash.core;

import com.xploits.shared.core.i18n.Msg;

/**
 * Identidad de un contenedor (spec §4.2). El ender chest tiene clave propia porque es uno solo
 * y viaja contigo; los cofres dobles usan siempre la menor de sus dos posiciones, para que las
 * dos mitades den la misma clave.
 */
public record ContainerKey(String dimension, int x, int y, int z) {
    /** Clave única del ender chest: no está en ninguna coordenada. */
    public static final ContainerKey ENDER = new ContainerKey("", Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);

    public static ContainerKey block(String dimension, int x, int y, int z) {
        return new ContainerKey(dimension, x, y, z);
    }

    /** Devuelve la clave canónica de un cofre doble: la menor posición por x, luego y, luego z. */
    public static ContainerKey doubleChest(String dimension, int x1, int y1, int z1, int x2, int y2, int z2) {
        if (x1 != x2) return x1 < x2 ? block(dimension, x1, y1, z1) : block(dimension, x2, y2, z2);
        if (y1 != y2) return y1 < y2 ? block(dimension, x1, y1, z1) : block(dimension, x2, y2, z2);
        return z1 <= z2 ? block(dimension, x1, y1, z1) : block(dimension, x2, y2, z2);
    }

    public boolean isEnder() {
        return equals(ENDER);
    }

    /** Forma estable para usar como clave en JSON y para leerla de un vistazo en los avisos. */
    public String id() {
        return isEnder() ? "ender" : dimension + "@" + x + "," + y + "," + z;
    }

    /**
     * Dónde está, sin coordenadas (spec consola §7): la dimensión y, si el jugador está en la misma,
     * a cuántos bloques en el plano. Para la consola y su fichero, que no pueden llevar posiciones.
     */
    public Msg sinPosicion(String dimensionActual, Double jugadorX, Double jugadorZ) {
        if (isEnder()) return Msg.of(StashText.WHERE_ENDER);
        String corta = dimension.startsWith("minecraft:") ? dimension.substring("minecraft:".length()) : dimension;
        if (jugadorX == null || jugadorZ == null || !dimension.equals(dimensionActual)) return Msg.of(StashText.WHERE_DIMENSION, "dimension", corta);
        double dx = x - jugadorX;
        double dz = z - jugadorZ;
        return Msg.of(StashText.WHERE_DISTANCE, "dimension", corta, "blocks", Math.round(Math.sqrt(dx * dx + dz * dz)));
    }
}
