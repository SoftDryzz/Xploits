package com.xploits.sweep.core;

import java.util.OptionalDouble;

/**
 * Mide el gasto real de cohetes en vuelo y proyecta si van a alcanzar, en vez de suponerlo (spec
 * Nether Sweep §6). El barrido se decide en dos momentos distintos y esta clase solo sirve al
 * segundo:
 *
 * <ul>
 *   <li><b>Antes de despegar</b>, la estimación sale del gasto por bloque medido en barridos
 *       anteriores y guardado entre sesiones -eso lo guarda y lo carga el adaptador, no esta
 *       clase-.</li>
 *   <li><b>Durante el vuelo</b>, que es lo que {@code FuelBudget} hace: mide el gasto real de
 *       <i>este</i> vuelo a partir de los cohetes que de verdad se han quemado, y en cuanto hay
 *       datos reales mandan ellos sobre la suposición inicial (spec §6, última línea).</li>
 * </ul>
 *
 * <p><b>Qué distancia hay que pasarle.</b> {@code blocksRemaining} en {@link #willRunOut} es lo que
 * le queda por volar al jugador desde donde está <b>hasta llegar a casa</b> otra vez si el
 * presupuesto contempla la vuelta, no un trozo cualquiera del barrido. En particular <b>no es</b>
 * {@code SweepPlan.totalBlocks()} tal cual: ese número mide solo el barrido -del arranque de la
 * primera pasada al final de la última, enlaces incluidos- y dos veces ya se ha colado en este
 * mismo módulo un «total» que no incluía la aproximación desde donde estaba el jugador y que acabó
 * alimentando esta misma comprobación de si despegar. La aproximación -y la vuelta, si toca- son
 * responsabilidad de quien construye {@code blocksRemaining}, no de esta clase: {@code FuelBudget}
 * no sabe dónde está el jugador ni dónde está el plan, solo mide un gasto y proyecta el número que
 * se le da.
 *
 * <p><b>Subestimar el gasto o la distancia restante es el lado peligroso, no el prudente.</b> Un
 * gasto por bloque de menos, o una distancia restante de menos, hacen que {@link #willRunOut} diga
 * que los cohetes llegan cuando no llegan, y el jugador despega a un viaje de horas mal
 * aprovisionado. Ante dos redondeos posibles, esta clase elige siempre el que sobreestima el
 * riesgo. Por el mismo motivo {@link #willRunOut} rechaza una reserva negativa: una reserva por
 * debajo de cero no es «menos margen», es un umbral que baja de lo que hace falta y produce
 * justo la mentira que esta clase existe para no decir -ver el javadoc del método-.
 *
 * <p><b>El gasto medido caduca si pasa demasiado vuelo sin confirmarlo.</b> Cuando el jugador
 * repone cohetes a menudo -más a menudo de lo que se muestrea-, ningún tramo vuelve a tener
 * cohetes gastados netos y los acumulados se quedan congelados en la última tasa que sí se pudo
 * medir, por muy vieja que sea. Sin nada que lo corrija, {@link #willRunOut} seguiría proyectando
 * sobre ese dato viejo como si fuera actual. {@link #blocksPerRocket()} lo hace visible: pasado un
 * tramo sin confirmar del tamaño del que sí se confirmó, vuelve a estar vacío -ver su javadoc-, en
 * vez de mentir con un número que ya no se sabe si sigue siendo cierto.
 *
 * <p>Esta clase no toca Minecraft ni Meteor: recibe bloques recorridos y cohetes restantes ya
 * medidos, no consulta al jugador ni al inventario.
 */
public final class FuelBudget {
    private boolean tieneReferencia = false;
    private double bloquesReferencia;
    private int cohetesReferencia;

    private double bloquesGastadosAcumulados = 0.0;
    private int cohetesGastadosAcumulados = 0;
    private double bloquesSinConfirmarDesdeLaUltimaActualizacion = 0.0;

    /**
     * Registra un punto del vuelo: bloques recorridos en total desde que se armó el módulo, y
     * cohetes que quedan ahora mismo.
     *
     * <p>La primera muestra solo fija la referencia de la que se miden las siguientes; no produce
     * gasto por sí sola, porque un solo punto no es una diferencia.
     *
     * <p><b>Si los cohetes suben respecto a la muestra anterior</b> -el jugador ha repuesto a mitad
     * de vuelo, por ejemplo tirando más cohetes al inventario desde un shulker- <b>el tramo se
     * ignora</b>: ni sus bloques ni su variación de cohetes cuentan para el gasto acumulado, porque
     * no sabemos cuántos cohetes se quemaron de verdad en ese tramo mezclados con la reposición. La
     * muestra sí se guarda como nueva referencia, así que el tramo siguiente vuelve a medir gasto
     * real sin arrastrar el hueco.
     *
     * <p>Ese mismo tramo ignorado también cuenta como vuelo sin confirmar para
     * {@link #blocksPerRocket()}: si se acumulan demasiados bloques así seguidos, sin que ningún
     * tramo vuelva a bajar los cohetes de verdad, el gasto medido caduca -ver el javadoc de la
     * clase-.
     *
     * @param blocksFlown  bloques recorridos en total desde el arranque de la medición
     * @param rocketsLeft  cohetes que quedan en el inventario en este instante
     */
    public void sample(double blocksFlown, int rocketsLeft) {
        if (!tieneReferencia) {
            bloquesReferencia = blocksFlown;
            cohetesReferencia = rocketsLeft;
            tieneReferencia = true;
            return;
        }

        double deltaBloques = blocksFlown - bloquesReferencia;
        int deltaCohetes = cohetesReferencia - rocketsLeft;
        if (deltaCohetes > 0) {
            bloquesGastadosAcumulados += deltaBloques;
            cohetesGastadosAcumulados += deltaCohetes;
            bloquesSinConfirmarDesdeLaUltimaActualizacion = 0.0;
        } else {
            bloquesSinConfirmarDesdeLaUltimaActualizacion += deltaBloques;
        }

        bloquesReferencia = blocksFlown;
        cohetesReferencia = rocketsLeft;
    }

    /**
     * Bloques que cuesta un cohete, medidos sobre los tramos donde de verdad se ha gastado alguno.
     *
     * <p>Vacío si todavía no hay ningún tramo así -sin muestras, con una sola muestra, o con
     * muestras en las que los cohetes solo han subido o se han mantenido-, en vez de cero o
     * cualquier otro número inventado con aspecto de medida: es el mismo criterio que la spec fija
     * para la estimación previa sin barridos anteriores (§6), aplicado aquí a la medición en vuelo.
     *
     * <p><b>También vacío si el dato ha caducado.</b> Cuánto se ha volado sin que ningún tramo
     * confirmara gasto real se compara con cuánto costó confirmar la tasa actual por primera vez
     * ({@code bloquesGastadosAcumulados}): en cuanto lo primero supera a lo segundo, la tasa deja
     * de contarse como de fiar y este método vuelve a estar vacío, aunque los acumulados internos
     * sigan ahí. No es un número de bloques fijo porque no hay ninguno en la spec que lo
     * justifique, y uno fijo sería o bien demasiado corto para una tasa bien confirmada con mucho
     * vuelo detrás, o bien demasiado largo para una tasa que solo se vio confirmada una vez con
     * poco vuelo. Comparar contra la propia evidencia acumulada escala con ella: una tasa
     * confirmada sobre mucho vuelo aguanta mucho vuelo sin confirmar antes de dudar de sí misma;
     * una confirmada sobre poco, duda enseguida. En cuanto vuelve a haber un tramo con gasto real,
     * la confianza se restablece de inmediato -el contador de vuelo sin confirmar se pone a cero en
     * {@link #sample}-.
     */
    public OptionalDouble blocksPerRocket() {
        if (cohetesGastadosAcumulados == 0) {
            return OptionalDouble.empty();
        }
        if (bloquesSinConfirmarDesdeLaUltimaActualizacion > bloquesGastadosAcumulados) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(bloquesGastadosAcumulados / cohetesGastadosAcumulados);
    }

    /**
     * Si, al ritmo medido, los cohetes no van a llegar para cubrir {@code blocksRemaining}
     * respetando la reserva.
     *
     * <p>Corta <b>antes</b> de llegar a cero, no al llegar (spec §10): el umbral de cohetes que
     * hacen falta se infla por {@code (1 + reserveFraction)}, así que con una reserva del 20 % el
     * aviso salta con cohetes todavía en la mano, no en el instante en que se agotan.
     *
     * <p><b>{@code reserveFraction} tiene que ser cero o positivo.</b> Una reserva negativa no
     * reduce el margen, lo invierte: con tasa 100 bloques/cohete, 5000 bloques por delante y una
     * reserva de -1.0, el umbral sale en {@code 50 * (1 + (-1.0)) = 0}, así que con <b>cero
     * cohetes en mano</b> este método diría que no hace falta cortar. Es exactamente lo que el
     * javadoc de la clase dice que no puede pasar, así que se rechaza en vez de devolver esa
     * mentira -la misma condición descarta también un {@code NaN}, que compararía siempre a
     * falso y colaría el mismo fallo por otra puerta-.
     *
     * @param blocksRemaining lo que queda por volar hasta el destino final -ver el javadoc de la
     *                        clase sobre qué tiene que incluir esta distancia-
     * @param rocketsLeft     cohetes que quedan ahora mismo
     * @param reserveFraction fracción de margen sobre los cohetes necesarios, p. ej. 0.2 para un
     *                        20 % de reserva; tiene que ser {@code >= 0}
     * @return si hay que cortar el vuelo ya
     * @throws IllegalArgumentException         si {@code reserveFraction} es negativo o
     *                                           {@code NaN}
     * @throws java.util.NoSuchElementException si {@link #blocksPerRocket()} está vacío -no se
     *                                           puede proyectar sin ninguna medida real, o porque
     *                                           ha caducado; quien llame debe comprobarlo antes
     */
    public boolean willRunOut(double blocksRemaining, int rocketsLeft, double reserveFraction) {
        if (!(reserveFraction >= 0)) {
            throw new IllegalArgumentException(
                "reserveFraction tiene que ser >= 0 (recibido " + reserveFraction + "): una reserva"
                    + " negativa baja el umbral de cohetes necesarios en vez de ampliarlo e invierte"
                    + " la garantía de seguridad de esta clase -ver el javadoc del método-");
        }
        double tasa = blocksPerRocket().getAsDouble();
        double cohetesNecesarios = blocksRemaining / tasa;
        double umbral = cohetesNecesarios * (1 + reserveFraction);
        return rocketsLeft < umbral;
    }
}
