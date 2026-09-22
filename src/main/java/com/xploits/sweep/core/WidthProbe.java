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
 * <p><b>Y por eso mismo hace falta un techo.</b> Que el máximo no baje nunca es lo correcto frente a
 * una ráfaga lenta y es exactamente lo que convierte <b>un solo chunk tardío</b> en un barrido con
 * agujeros: el servidor encola un lote cuando el jugador está en un sitio y se lo entrega cuando ya
 * está diez chunks más allá, así que esa muestra mide radio real + 10 y se queda de máximo el resto
 * de la sesión. Con un radio real de 8 chunks, un máximo de 18 da pasadas de 28 chunks -448
 * bloques- sobre un servidor que cubre 256: una franja de 12 chunks entre cada dos pasadas que nunca
 * pasa por delante del cliente y que el barrido anuncia como peinada. Es la mentira de spec §9, y
 * además persistente: mientras el máximo siga ahí, cada relanzamiento repite los mismos huecos en el
 * mismo sitio.
 *
 * <p>La spec ya da el techo en §3: <i>«la distancia de renderizado del cliente en esta instancia es
 * 16 chunks… el límite lo pone el menor de los dos»</i>. El servidor no puede mandar más allá de la
 * distancia que el cliente le declaró, así que <b>toda muestra por encima de ella es demostrablemente
 * un artefacto</b> y {@link #sample} la descarta al entrar, sin contarla siquiera como muestra: no es
 * una observación del alcance del servidor, es una observación de cuánto se ha movido el jugador
 * mientras el paquete estaba en cola.
 *
 * <p><b>Pero el techo solo caza las muestras infladas que se pasan de él, y ese es el caso raro.</b>
 * Toda muestra tomada con el jugador en movimiento mide <i>radio real + lo que el jugador se movió
 * mientras el paquete estaba en cola</i>; las que con esa suma se salen del techo se tiran, y
 * <b>todas las que quedan por debajo se aceptan como medida buena</b>. Como el máximo no baja nunca,
 * la medida se va acercando al techo cuanto más retraso lleve el servidor -más cola, más deriva-, y
 * el retraso es justo cuando su alcance efectivo <b>baja</b>: la medida subiría cuanto menos cubre el
 * servidor, invertida y hacia el lado que abre huecos. Los números del caso normal son techo 16 y un
 * servidor que bajo carga entrega 8: el máximo se acerca a 16, la anchura sale 25, media banda son
 * 12,5 chunks y el servidor cubre 8. Son 4,5 chunks sin ver a cada lado de cada pasada, el 36 % del
 * rectángulo, anunciado como peinado.
 *
 * <p><b>Por eso la muestra trae también a qué velocidad iba el jugador</b>, y se descarta entera si
 * iba a más de {@link #BLOQUES_POR_TICK_MAXIMOS}. No es una corrección aproximada de la deriva -no
 * hay forma de saber cuándo se encoló cada paquete-, es negarse a medir cuando la medida está
 * contaminada: quieto o andando, la deriva de un segundo entero de cola no llega a un chunk y la
 * observación es del alcance del servidor; volando con elytra son tres chunks por segundo de cola y
 * la observación es de la cola, no del servidor. Medir sigue siendo barato -basta andar unos
 * segundos con el módulo encendido, que es justo lo que pide el rechazo de «todavía no hay muestras
 * suficientes»-, y en cambio volar media hora hacia la zona ya no mete ni una sola muestra inflada.
 *
 * <p>Esta clase no toca Minecraft ni Meteor: recibe posiciones ya convertidas a {@link ChunkPos} y
 * el techo ya leído, no consulta ningún evento ni ningún chunk. Quien la alimenta es el adaptador,
 * suscrito al evento de recepción de chunks del cliente.
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

    /**
     * A qué velocidad como mucho puede ir el jugador para que su muestra cuente, en bloques por
     * tick, y de dónde sale el número.
     *
     * <p>Lo que contamina una muestra es la <b>deriva</b>: lo que el jugador se ha movido entre que
     * el servidor encoló el paquete y el cliente lo recibió. No se puede medir -el paquete no dice
     * cuándo se encoló-, pero sí se puede acotar: a {@code v} bloques por tick, un segundo entero de
     * cola -veinte ticks, que ya es un servidor yendo muy mal- desplaza al jugador {@code 20 v}
     * bloques, o sea {@code 20 v / 16} chunks.
     *
     * <p>Con 0,5 esa cota son 10 bloques, <b>0,63 chunks</b>: por debajo de uno, así que ni con un
     * segundo de cola puede una muestra inflarse un chunk entero y ensanchar la pasada. Y deja
     * cómodamente dentro todo lo que no es vuelo: andar son 4,3 bloques por segundo (0,215 por
     * tick), correr 5,6 (0,28) y correr saltando unos 7,1 (0,36). Deja fuera, también cómodamente,
     * lo único que de verdad infla: una elytra empujada por cohetes va a unos 33 bloques por segundo
     * -1,65 por tick, más del triple del tope-, y ahí un segundo de cola son más de dos chunks de
     * deriva.
     */
    public static final double BLOQUES_POR_TICK_MAXIMOS = 0.5;

    private int muestras = 0;
    private int muestrasDescartadas = 0;
    private int muestrasEnMovimiento = 0;
    private int radioMaximoObservado = 0;

    /**
     * Registra un chunk recibido del servidor, <b>salvo que sea un artefacto</b>.
     *
     * <p>Una muestra por encima de {@code maxRadiusInChunks} no dice hasta dónde manda el servidor
     * -no puede mandar más allá de lo que el cliente le declaró-, dice cuánto se ha movido el jugador
     * mientras ese paquete estaba encolado. Se descarta entera: ni fija el máximo ni cuenta para
     * {@link #hasEnoughSamples()}, porque contarla sería dar por medido algo que no se ha medido.
     * Ver el javadoc de la clase para el vuelo completo del fallo que esto cierra.
     *
     * <p><b>Y una muestra tomada en movimiento tampoco se cuenta</b>, aunque quepa bajo el techo: mide
     * el alcance del servidor más la deriva del jugador, y el máximo se queda con la más inflada de
     * todas. Ver el javadoc de la clase para el vuelo entero de ese fallo y
     * {@link #BLOQUES_POR_TICK_MAXIMOS} para de dónde sale el tope.
     *
     * <p>El techo va por muestra y no en el constructor a propósito: puede cambiar a mitad de sesión
     * -el jugador toca su distancia de renderizado, o el servidor declara otra-, y entonces cada
     * observación tiene que juzgarse contra el techo que había cuando llegó.
     *
     * @param player             la posición del jugador, en chunks, en el instante en que se recibió
     * @param received           el chunk que acaba de llegar
     * @param maxRadiusInChunks  el radio más grande que el servidor puede estar mandando ahora mismo,
     *                           en chunks; por encima de él la muestra es un artefacto
     * @param blocksPerTick      cuánto se movió el jugador en el último tick, en bloques; por encima
     *                           de {@link #BLOQUES_POR_TICK_MAXIMOS} la muestra mide la cola del
     *                           servidor y no su alcance, así que no se cuenta
     * @throws IllegalArgumentException si {@code maxRadiusInChunks} es menor que 1 -un techo así
     *                                  descartaría absolutamente todo y la sonda se quedaría muda
     *                                  para siempre sin que nadie supiera por qué- o si
     *                                  {@code blocksPerTick} es negativo o {@code NaN}, que no es
     *                                  una velocidad y dejaría pasar la muestra sin comprobarla
     */
    public void sample(ChunkPos player, ChunkPos received, int maxRadiusInChunks, double blocksPerTick) {
        if (maxRadiusInChunks < 1) {
            throw new IllegalArgumentException(
                "el techo de la sonda tiene que ser de 1 chunk o más (recibido " + maxRadiusInChunks
                    + "): con menos se descartaría toda muestra y la sonda no llegaría a medir nunca");
        }
        // Escrito en negativo para que un NaN -que compara falso contra todo- caiga aquí y no se
        // cuele como velocidad válida por la puerta de atrás.
        if (!(blocksPerTick >= 0)) {
            throw new IllegalArgumentException(
                "la velocidad del jugador tiene que ser cero o positiva (recibido " + blocksPerTick
                    + "): sin una velocidad de verdad no se puede saber si la muestra viene inflada"
                    + " por la deriva, y dejarla pasar sería aceptarla sin comprobarla");
        }

        if (blocksPerTick > BLOQUES_POR_TICK_MAXIMOS) {
            muestrasEnMovimiento++;
            return;
        }

        int distancia = Math.max(Math.abs(received.x() - player.x()), Math.abs(received.z() - player.z()));
        if (distancia > maxRadiusInChunks) {
            muestrasDescartadas++;
            return;
        }
        radioMaximoObservado = Math.max(radioMaximoObservado, distancia);
        muestras++;
    }

    /** Cuántas muestras se han descartado por pasarse del techo, para poder decirlo. */
    public int discardedSamples() {
        return muestrasDescartadas;
    }

    /**
     * Cuántas muestras se han descartado por haberse tomado con el jugador en movimiento. Se cuenta
     * aparte de {@link #discardedSamples()} porque significa otra cosa y se arregla de otra manera:
     * aquélla es un servidor con retraso, ésta es que el jugador está volando y hay que parar unos
     * segundos para que la anchura se mida.
     */
    public int movingSamples() {
        return muestrasEnMovimiento;
    }

    /** Cuántas muestras buenas lleva la sonda. */
    public int sampleCount() {
        return muestras;
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
