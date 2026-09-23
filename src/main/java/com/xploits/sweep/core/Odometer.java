package com.xploits.sweep.core;

/**
 * El cuentakilómetros del vuelo: suma lo que el jugador <b>ha volado de verdad</b>, tick a tick, y
 * se salta los saltos que no se volaron.
 *
 * <p><b>Por qué no vale sumar la distancia entre dos ticks y ya.</b> Un teletransporte -un portal,
 * un {@code /tpa}, un reaparecer, un tirón del servidor- mete de golpe cientos o miles de bloques
 * entre un tick y el siguiente. Ese trayecto no se voló y no costó ni un cohete, pero se suma a los
 * bloques recorridos, y de ahí sale el número que alimenta {@link FuelBudget}: el gasto medido
 * queda <b>dividido entre más bloques de los que se volaron</b>, o sea más bloques por cohete de
 * los reales.
 *
 * <p>Y esa dirección del error es justo la peligrosa, no la prudente. Con una tasa inflada,
 * {@link FuelBudget#willRunOut} proyecta que los cohetes dan para más de lo que dan y contesta que
 * llegan cuando no llegan: es exactamente el redondeo que el javadoc de {@code FuelBudget} dice que
 * esta parte del módulo nunca hace -«ante dos redondeos posibles, se elige siempre el que
 * sobreestima el riesgo»- colándose por la puerta de la distancia en vez de por la del gasto. El
 * precio es quedarse tirado lejos de casa, que es lo que la protección de cohetes existe para
 * evitar.
 *
 * <p><b>Cómo se distingue un salto de un vuelo rápido:</b> por la velocidad, que en un tick es una
 * cota física. Ver {@link #BLOQUES_POR_TICK_MAXIMOS}. El tramo descartado no se suma y no se
 * reparte: no se voló, así que no existe para el presupuesto.
 *
 * <p>El último paso se guarda aparte porque sirve para otra cosa: es la velocidad con la que
 * {@link WidthProbe#sample} decide si una muestra de anchura está contaminada por la deriva. Ahí se
 * entrega <b>el paso en bruto</b>, sin filtrar, y a propósito: un tick con un teletransporte dentro
 * es el peor momento posible para medir el alcance del servidor, así que conviene que la sonda lo
 * vea grande y descarte la muestra.
 *
 * <p>Esta clase no toca Minecraft ni Meteor: recibe distancias ya medidas.
 */
public final class Odometer {
    /**
     * A partir de cuántos bloques en un solo tick se da por hecho que el jugador no voló ese tramo,
     * y de dónde sale el número.
     *
     * <p>Por abajo tiene que dejar pasar cualquier vuelo real: una elytra empujada por cohetes va a
     * unos 33 bloques por segundo -1,65 por tick- y un picado con cohete encadenado no pasa de unos
     * 60 -3 por tick-. Diez bloques por tick son 200 por segundo, tres veces el vuelo más rápido que
     * se puede sostener: ningún tick volado llega ahí.
     *
     * <p>Por arriba tiene que cazar lo que de verdad importa. Un teletransporte útil mueve cientos o
     * miles de bloques; el más corto que este módulo puede encontrarse es un portal del Nether, y
     * aun ese cambia de dimensión y de coordenadas de golpe. Un salto de menos de diez bloques que
     * se colara no cambia una tasa medida sobre tramos de mil.
     */
    public static final double BLOQUES_POR_TICK_MAXIMOS = 10;

    private double bloquesVolados;
    private double ultimoPaso;
    private int saltos;

    /**
     * Registra lo que el jugador se ha desplazado en este tick.
     *
     * @param blocks distancia recorrida desde el tick anterior, en bloques; nunca negativa
     * @return si el paso se contó como vuelo. {@code false} significa que se descartó por salto
     * @throws IllegalArgumentException si {@code blocks} es negativo o {@code NaN}: no es una
     *                                  distancia, y dejarlo entrar corrompería el total del que sale
     *                                  la proyección de cohetes
     */
    public boolean advance(double blocks) {
        // En negativo para que un NaN caiga aquí en vez de colarse: comparado con cualquier cosa da
        // falso, así que un "blocks >= 0" lo dejaría pasar y luego envenenaría el acumulado entero.
        if (!(blocks >= 0)) {
            throw new IllegalArgumentException(
                "el desplazamiento de un tick tiene que ser cero o positivo (recibido " + blocks
                    + "): no es una distancia, y sumarlo corrompería los bloques volados de los que" // i18n: allowed (exception message, continuation line)
                    + " sale la proyección de cohetes"); // i18n: allowed (exception message, continuation line)
        }

        ultimoPaso = blocks;
        if (blocks > BLOQUES_POR_TICK_MAXIMOS) {
            saltos++;
            return false;
        }
        bloquesVolados += blocks;
        return true;
    }

    /** Los bloques volados de verdad desde que se armó el cuentakilómetros. */
    public double blocksFlown() {
        return bloquesVolados;
    }

    /**
     * El último desplazamiento registrado, <b>en bruto</b>: sin filtrar los saltos. Es la velocidad
     * del jugador en el último tick, que es lo que {@link WidthProbe#sample} necesita para saber si
     * una muestra de anchura viene inflada por la deriva -ver el javadoc de la clase-.
     */
    public double lastStep() {
        return ultimoPaso;
    }

    /** Cuántos pasos se han descartado por ser saltos y no vuelo, para poder decirlo. */
    public int jumps() {
        return saltos;
    }
}
