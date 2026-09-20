package com.xploits.travel.core;

/**
 * La vigilancia del atasco (spec AutoTravel §8): decide cuándo un viaje ha dejado de avanzar y hay
 * que cortarlo. Baritone no informa de cómo le va, así que lo único observable desde fuera es si la
 * distancia al waypoint baja; esta clase es todo lo que se puede razonar sobre eso sin tocar
 * Minecraft, y por eso vive en el núcleo y se prueba entera sin arrancar el juego.
 *
 * <p><b>Cuenta ticks, no milisegundos de reloj</b>, como el resto del núcleo: un test no puede
 * esperar treinta segundos reales, y el vuelo se observa una vez por tick de todas formas.
 *
 * <p><b>El contador va atado al waypoint que vigila.</b> Avanzar al siguiente waypoint hace que la
 * distancia salte hacia arriba de golpe -del margen de llegada a los miles de bloques del
 * siguiente-, y comparar ese salto con la distancia mínima del waypoint anterior sería leerlo como
 * "no me estoy acercando" treinta segundos seguidos y cortar un viaje que va perfectamente. Por eso
 * {@link #tick(int, double)} recibe el índice del waypoint y se reinicia solo cuando cambia: el
 * adaptador no puede olvidarse de reiniciarla, porque no es él quien lo hace.
 */
public final class StallWatch {
    /** Los ticks por segundo del cliente, para traducir el límite a segundos en los mensajes. */
    public static final int TICKS_PER_SECOND = 20;

    private final int limitTicks;
    private final double epsilon;

    /** El waypoint que se está vigilando, o -1 si todavía no se ha observado ninguno. */
    private int watchedWaypoint = -1;

    /** La distancia más corta observada a ese waypoint. Infinito mientras no haya ninguna. */
    private double closestDistance = Double.POSITIVE_INFINITY;

    private int ticksWithoutProgress;

    /**
     * @param limitTicks cuántos ticks seguidos sin acercarse hacen que se corte. Al menos uno
     * @param epsilon    cuánto tiene que bajar la distancia para contar como avance, en bloques.
     *                   Sin este margen, el vaivén de un bloque que da el propio vuelo rearmaría el
     *                   contador eternamente y la vigilancia no cortaría nunca; acercarse medio
     *                   bloque en treinta segundos no es avanzar
     */
    public StallWatch(int limitTicks, double epsilon) {
        if (limitTicks < 1) {
            throw new IllegalArgumentException("el límite del atasco tiene que ser de al menos un tick: " + limitTicks);
        }
        if (!(epsilon >= 0) || Double.isInfinite(epsilon)) {
            throw new IllegalArgumentException("el epsilon de avance tiene que ser un número finito no negativo: " + epsilon);
        }
        this.limitTicks = limitTicks;
        this.epsilon = epsilon;
    }

    /** La misma vigilancia expresada en segundos, que es como la escribe la spec. */
    public static StallWatch ofSeconds(double seconds, double epsilon) {
        return new StallWatch((int) Math.round(seconds * TICKS_PER_SECOND), epsilon);
    }

    /**
     * Observa un tick de vuelo y dice si hay que cortar.
     *
     * @param waypointIndex el waypoint al que se está yendo ahora. Si es otro que el del tick
     *                      anterior, la vigilancia empieza de cero: la distancia al waypoint nuevo
     *                      no se compara jamás con la del viejo
     * @param distance      la distancia que queda hasta ese waypoint, en bloques
     * @return {@code true} si se han cumplido los ticks del límite sin acercarse
     */
    public boolean tick(int waypointIndex, double distance) {
        if (waypointIndex != watchedWaypoint) {
            watchedWaypoint = waypointIndex;
            forget();
        }

        // El primer tick de cada waypoint cae siempre aquí -cualquier distancia es menor que
        // infinito-, así que fija la referencia y nunca cuenta como atasco.
        if (distance < closestDistance - epsilon) {
            closestDistance = distance;
            ticksWithoutProgress = 0;
            return false;
        }

        ticksWithoutProgress++;
        return ticksWithoutProgress >= limitTicks;
    }

    /** Vuelve al estado de recién empezada. Para el arranque y el final de un viaje. */
    public void reset() {
        watchedWaypoint = -1;
        forget();
    }

    /** Cuántos ticks seguidos se lleva sin acercarse al waypoint vigilado. */
    public int ticksWithoutProgress() {
        return ticksWithoutProgress;
    }

    /** El límite, en segundos, para poder nombrarlo en el aviso. */
    public long limitSeconds() {
        return Math.round((double) limitTicks / TICKS_PER_SECOND);
    }

    private void forget() {
        closestDistance = Double.POSITIVE_INFINITY;
        ticksWithoutProgress = 0;
    }
}
