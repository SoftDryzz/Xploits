package com.xploits.sweep.core;

/**
 * Mide la anchura real a la que el servidor manda chunks, observando el propio flujo en vez de
 * suponerla (spec Nether Sweep §5). Cada chunk recibido es una muestra: la distancia entre la
 * posición del jugador en ese instante y el chunk que acaba de llegar dice hasta dónde llega el
 * servidor ahora mismo, en este servidor y a esta velocidad.
 *
 * <p><b>La distancia es de Chebyshev</b>, {@code max(|dx|, |dz|)} en chunks, porque el servidor
 * manda en un cuadrado, no en un círculo. Medirla en euclídea daría un radio mayor que el real en
 * las diagonales -un chunk a (3,3) mediría 4,24 en vez de 3- y {@link SweepPlanner} separaría las
 * pasadas más de lo que el servidor de verdad cubre, abriendo huecos justo entre ellas: el fallo
 * que este módulo no puede cometer (spec §9), terreno sin ver marcado como peinado.
 *
 * <p><b>El resultado es el máximo observado, no la media.</b> Una ráfaga lenta -el servidor se
 * atasca un instante y manda de golpe un puñado de chunks cercanos- no puede estrechar la anchura
 * de pasada: lo que importa es hasta dónde ha llegado a mandar el servidor, no el promedio de lo
 * que ha llegado hasta ahora.
 *
 * <p>Esta clase no toca Minecraft ni Meteor: recibe posiciones ya convertidas a {@link ChunkPos},
 * no consulta ningún evento ni ningún chunk. Quien la alimenta es el adaptador, suscrito al evento
 * de recepción de chunks del cliente.
 */
public final class WidthProbe {
    /**
     * Muestras mínimas para que {@link #hasEnoughSamples()} sea cierto.
     *
     * <p>No lo fija la spec; es una decisión de implementación. Con menos muestras que esto el
     * máximo observado puede ser solo el primer chunk que ha llegado -normalmente uno de los más
     * cercanos-, y planificar la anchura de pasada sobre eso repetiría el fallo que la spec señala
     * para los chunks cargados al lanzar (§5): un número que no ha tenido tiempo de estabilizarse
     * disfrazado de medida. Si en el uso real hace falta otro valor, se ajusta aquí, en un solo
     * sitio.
     */
    public static final int MUESTRAS_MINIMAS = 8;

    private int muestras = 0;
    private int radioMaximoObservado = 0;

    /**
     * Registra un chunk recibido del servidor.
     *
     * @param player   la posición del jugador, en chunks, en el instante en que se recibió
     * @param received el chunk que acaba de llegar
     */
    public void sample(ChunkPos player, ChunkPos received) {
        int distancia = Math.max(Math.abs(received.x() - player.x()), Math.abs(received.z() - player.z()));
        radioMaximoObservado = Math.max(radioMaximoObservado, distancia);
        muestras++;
    }

    /**
     * El radio observado, en chunks: el máximo de todas las muestras registradas.
     *
     * <p>Lanza si aún no hay muestras suficientes en vez de devolver un cero o el máximo parcial
     * como si fuera la medida final -sería exactamente el número inventado con aspecto de medida
     * que la spec descarta en §6 para la estimación de cohetes, y aquí aplica igual: quien llame a
     * esto debe comprobar primero {@link #hasEnoughSamples()}.
     *
     * @throws IllegalStateException si {@link #hasEnoughSamples()} es falso
     */
    public int observedRadiusInChunks() {
        if (!hasEnoughSamples()) {
            throw new IllegalStateException(
                "todavía no hay muestras suficientes (" + muestras + " de " + MUESTRAS_MINIMAS
                    + ") para dar un radio observado: comprueba hasEnoughSamples() antes de llamar"
                    + " a esto");
        }
        return radioMaximoObservado;
    }

    /** Si ya hay muestras suficientes para que {@link #observedRadiusInChunks()} signifique algo. */
    public boolean hasEnoughSamples() {
        return muestras >= MUESTRAS_MINIMAS;
    }

    /**
     * La anchura de pasada segura para {@link SweepPlanner#plan}, a partir del radio observado y
     * con el margen de seguridad de la spec aplicado (§5: «volar rápido puede dejar huecos aunque
     * la distancia nominal sea correcta, porque los chunks tardan en llegar»).
     *
     * <p><b>Por qué hace falta un margen aunque el radio ya sea una medida real, no una
     * suposición.</b> {@link #observedRadiusInChunks()} es hasta dónde llegó el servidor mientras
     * se tomaban las muestras, <b>a la velocidad a la que se voló entonces</b>. Si el barrido se
     * vuela más rápido que eso, el jugador se adelanta a la entrega de chunks: para cuando el
     * chunk que antes llegaba a radio R llega ahora, el jugador ya está más lejos, así que la
     * cobertura real cae por debajo de R. El margen no es un colchón inventado sobre una medida ya
     * de por sí incierta -es la diferencia entre «lo que llegó a esta velocidad» y «lo que habría
     * llegado si se hubiera ido más rápido», y solo quien va a volar el barrido sabe cuánto más
     * rápido puede ir respecto a cuando se tomaron las muestras. Por eso {@code safetyMargin} lo
     * aporta quien llama, igual que {@code reserveFraction} en {@link FuelBudget#willRunOut}: esta
     * clase no puede adivinarlo, solo aplicarlo correctamente.
     *
     * <p><b>De radio a anchura.</b> {@link SweepPlanner} coloca cada pasada en el centro de su
     * banda, así que ningún chunk de la banda queda a más de media anchura de ella (ver su
     * javadoc); para que esa media anchura quepa dentro del radio seguro, la anchura de pasada es
     * <b>el doble</b> del radio ya reducido por el margen.
     *
     * <p><b>El redondeo va hacia abajo</b>, nunca al más cercano ni hacia arriba. Un radio
     * observado sobre el que redondear <i>menos</i> anchura de pasada cuesta pasadas de más -vuelo
     * caro, pero completo-; redondear <i>más</i> anchura de la que el margen permite es el fallo
     * que este módulo existe para no cometer: terreno sin ver marcado como peinado. Ante los dos
     * redondeos posibles, este método elige siempre el que sobreestima el riesgo.
     *
     * @param safetyMargin fracción del radio observado que se descuenta como margen, en
     *                      {@code [0, 1)}; 0 no descuenta nada, 0.2 se queda con el 80 % del radio
     * @return la anchura de pasada, en chunks, lista para {@link SweepPlanner#plan}
     * @throws IllegalStateException   si {@link #hasEnoughSamples()} es falso -mismo criterio que
     *                                 {@link #observedRadiusInChunks()}-
     * @throws IllegalArgumentException si {@code safetyMargin} no está en {@code [0, 1)}
     */
    public int laneWidthInChunks(double safetyMargin) {
        if (!(safetyMargin >= 0) || !(safetyMargin < 1)) {
            throw new IllegalArgumentException(
                "safetyMargin tiene que estar en [0, 1) (recibido " + safetyMargin + "): fuera de"
                    + " ese rango la anchura segura sale cero o negativa, que no es una anchura de"
                    + " pasada volable");
        }
        int radio = observedRadiusInChunks();
        double radioSeguro = radio * (1 - safetyMargin);
        return (int) Math.floor(2 * radioSeguro);
    }
}
