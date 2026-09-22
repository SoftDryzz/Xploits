package com.xploits.sweep.core;

import com.xploits.travel.core.Waypoint;

import java.util.ArrayList;
import java.util.List;

/**
 * El viaje entero que supone volar un plan de barrido: los vértices en el orden en que se vuelan y
 * <b>todas</b> las distancias que hay que presupuestar, desde donde está el jugador hasta que vuelve.
 *
 * <p><b>Esta clase existe por un fallo que ya se coló dos veces en este módulo</b>, y por eso la
 * aritmética vive aquí y no en el adaptador. {@link SweepPlanner.SweepPlan#totalBlocks()} mide el
 * barrido —del arranque de la primera pasada al final de la última, enlaces incluidos— y <b>no</b> el
 * viaje: le falta la <b>aproximación</b>, el trayecto desde donde esté el jugador hasta el arranque de
 * la primera pasada, que en un barrido de los que justifican este módulo es la pata más larga de
 * todas. Estimar cohetes con el «total» del plan da cohetes de menos, y entonces el módulo dice «te
 * llegan» a un jugador al que no le llegan: la comprobación previa, que es justo la que existe para
 * evitar el viaje, la pasa en falso.
 *
 * <p>Un {@code SweepPlan} no puede arreglarlo porque es geometría del área y no sabe dónde está el
 * jugador. {@code SweepRoute} sí lo sabe —se construye con su posición— y por eso es el sitio donde
 * el viaje completo se puede calcular una vez, con tests, en vez de rehacerse a mano en el adaptador
 * cada vez que hace falta.
 *
 * <p>De aquí sale el número que alimenta la proyección de cohetes de {@link FuelBudget#willRunOut},
 * que es la que decide si se corta el vuelo: {@link #remainingFrom(int, Waypoint)} es exactamente la
 * {@code blocksRemaining} que esa clase pide, con la vuelta dentro si el presupuesto la contempla.
 *
 * <p>Esta clase no toca Minecraft ni Meteor: trabaja sobre {@link Lane} y {@link Waypoint} como
 * números.
 */
public final class SweepRoute {
    private final List<Waypoint> waypoints;

    /**
     * Cuántos bloques quedan desde el vértice {@code i} hasta el final del viaje, regreso incluido si
     * se cuenta. Se precalcula de atrás hacia delante una sola vez, para que preguntarlo en vuelo
     * cueste una lectura y no un recorrido de toda la ruta.
     */
    private final double[] restanteDesde;

    private final double aproximacion;
    private final double regreso;
    private final double pasadaMasCorta;
    private final double enlaceMasCorto;

    private SweepRoute(List<Waypoint> waypoints, double[] restanteDesde, double aproximacion,
                       double regreso, double pasadaMasCorta, double enlaceMasCorto) {
        this.waypoints = waypoints;
        this.restanteDesde = restanteDesde;
        this.aproximacion = aproximacion;
        this.regreso = regreso;
        this.pasadaMasCorta = pasadaMasCorta;
        this.enlaceMasCorto = enlaceMasCorto;
    }

    /**
     * Construye el viaje a partir de las pasadas del plan y de dónde está el jugador.
     *
     * <p>Cada pasada aporta dos vértices, su principio y su final, en ese orden. Las pasadas ya salen
     * de {@link SweepPlanner} alternando el sentido, así que cada una arranca donde terminó la
     * anterior y el enlace entre dos es el salto entre bandas; aquí no se reordena nada.
     *
     * @param lanes       las pasadas a volar, en el orden en que salen del plan
     * @param origin      dónde está el jugador al lanzar, que es de donde sale la aproximación y a
     *                    donde vuelve el regreso
     * @param countReturn si el presupuesto contempla volver al punto de partida
     * @throws IllegalArgumentException si no hay ninguna pasada: un plan sin pasadas no es una ruta
     *                                  corta, es que no hay nada que volar —el área ya está vista
     *                                  entera—, y construir una ruta vacía dejaría que alguien
     *                                  despegara hacia ninguna parte
     * @throws NullPointerException     si {@code lanes} u {@code origin} son nulos
     */
    public static SweepRoute of(List<Lane> lanes, Waypoint origin, boolean countReturn) {
        if (lanes == null) throw new NullPointerException("la ruta necesita las pasadas del plan");
        if (origin == null) throw new NullPointerException("la ruta necesita saber desde dónde se despega");
        if (lanes.isEmpty()) {
            throw new IllegalArgumentException(
                "un plan sin pasadas no produce ninguna ruta: significa que el área ya está vista"
                    + " entera y que no hay nada que volar, no que el viaje sea corto");
        }

        List<Waypoint> vertices = new ArrayList<>(lanes.size() * 2);
        for (Lane pasada : lanes) {
            vertices.add(new Waypoint(pasada.fromX(), pasada.fromZ()));
            vertices.add(new Waypoint(pasada.toX(), pasada.toZ()));
        }

        double regreso = countReturn ? vertices.get(vertices.size() - 1).distanceTo(origin) : 0;

        double[] restante = new double[vertices.size()];
        restante[vertices.size() - 1] = regreso;
        for (int i = vertices.size() - 2; i >= 0; i--) {
            restante[i] = vertices.get(i).distanceTo(vertices.get(i + 1)) + restante[i + 1];
        }

        // Los vértices salen en parejas -principio y final de cada pasada-, así que el hueco entre
        // el 2i y el 2i+1 es una pasada y el del 2i+1 al 2i+2 es el enlace hasta la siguiente. Los
        // dos mínimos se separan porque significan cosas distintas: ver sus métodos.
        double pasadaMasCorta = Double.MAX_VALUE;
        double enlaceMasCorto = Double.MAX_VALUE;
        for (int i = 0; i + 1 < vertices.size(); i++) {
            double hueco = vertices.get(i).distanceTo(vertices.get(i + 1));
            if (i % 2 == 0) pasadaMasCorta = Math.min(pasadaMasCorta, hueco);
            else enlaceMasCorto = Math.min(enlaceMasCorto, hueco);
        }

        double aproximacion = origin.distanceTo(vertices.get(0));
        return new SweepRoute(List.copyOf(vertices), restante, aproximacion, regreso, pasadaMasCorta,
            enlaceMasCorto);
    }

    /** Los vértices en el orden en que se vuelan: principio y final de cada pasada. */
    public List<Waypoint> waypoints() {
        return waypoints;
    }

    /** Cuántos vértices tiene la ruta, que es el doble del número de pasadas. */
    public int size() {
        return waypoints.size();
    }

    /**
     * La aproximación: de donde estaba el jugador al lanzar hasta el arranque de la primera pasada.
     * Es la pata que le falta a {@link SweepPlanner.SweepPlan#totalBlocks()} y la más larga de todas
     * en un barrido lejos de casa.
     */
    public double approachBlocks() {
        return aproximacion;
    }

    /**
     * El barrido en sí: las pasadas más los enlaces que las unen. <b>Tiene que valer exactamente lo
     * mismo</b> que {@link SweepPlanner.SweepPlan#totalBlocks()} del plan del que salió esta ruta, y
     * hay un test que lo comprueba contra el planificador de verdad: son dos caminos distintos al
     * mismo número —aquel suma longitudes de pasada y saltos, éste suma distancias entre vértices
     * consecutivos— y si alguna vez dejaran de coincidir sería que uno de los dos se ha roto.
     */
    public double sweepBlocks() {
        return restanteDesde[0] - regreso;
    }

    /** La vuelta desde el final de la última pasada al punto de partida, o cero si no se cuenta. */
    public double returnBlocks() {
        return regreso;
    }

    /**
     * El viaje entero: aproximación, barrido y regreso. <b>Este</b> es el número con el que se estima
     * el combustible antes de despegar, y no el del plan.
     */
    public double totalBlocks() {
        return aproximacion + sweepBlocks() + regreso;
    }

    /**
     * Lo que queda por volar desde el vértice {@code index} hasta terminar el viaje, regreso incluido
     * si se cuenta. No incluye dónde esté el jugador ahora mismo: para eso está
     * {@link #remainingFrom(int, Waypoint)}.
     *
     * @throws IndexOutOfBoundsException si {@code index} no es un vértice de esta ruta
     */
    public double remainingFrom(int index) {
        if (index < 0 || index >= restanteDesde.length) {
            throw new IndexOutOfBoundsException(
                "el vértice " + index + " no existe en una ruta de " + restanteDesde.length
                    + " vértices");
        }
        return restanteDesde[index];
    }

    /**
     * Lo que le queda por volar al jugador: de donde está hasta el vértice al que va, más el resto de
     * la ruta, más el regreso si se cuenta.
     *
     * <p>Es exactamente la {@code blocksRemaining} que pide {@link FuelBudget#willRunOut}, y el
     * motivo por el que esa cuenta no se hace en el adaptador: de ella sale la decisión de cortar el
     * vuelo, y una distancia de menos hace que la proyección diga que los cohetes llegan cuando no
     * llegan.
     *
     * @throws IndexOutOfBoundsException si {@code index} no es un vértice de esta ruta
     * @throws NullPointerException      si {@code player} es nulo
     */
    public double remainingFrom(int index, Waypoint player) {
        if (player == null) throw new NullPointerException("hace falta saber dónde está el jugador");
        return player.distanceTo(waypoints.get(index)) + remainingFrom(index);
    }

    /**
     * La separación mínima que se le puede exigir a dos vértices seguidos de un <b>barrido</b> con
     * el margen de waypoint configurado: el margen a secas.
     *
     * <p><b>No es {@code RoutePlanner.minimumSpacing}, y esa diferencia es el arreglo.</b> Aquel
     * número es el doble del margen y además nunca baja de 300 bloques, y los dos sumandos vienen de
     * un problema que aquí no se da:
     *
     * <ul>
     *   <li><b>El doble del margen sale de dos vértices alineados.</b> En una ruta de evasión el
     *       waypoint siguiente puede estar justo detrás del actual y en la misma dirección: al
     *       soltar el primero ya se está a un margen de él, y el segundo cae dentro del otro margen.
     *       En un barrido no puede pasar, porque <b>los vértices forman ángulo recto</b>: al que
     *       remata una pasada se llega <i>a lo largo</i> de la pasada, y el que arranca la siguiente
     *       está <i>perpendicular</i>, a una banda de distancia. Si el primero se suelta estando a
     *       {@code d ≤ margen} de él, la distancia al segundo es {@code hipotenusa(d, hueco)}, que
     *       nunca baja del hueco. Basta, pues, con que el hueco pase del margen.</li>
     *   <li><b>Los 300 bloques salen de la física de la elytra</b>: por debajo de unos cuantos
     *       radios de giro, Baritone se pasa de largo y vuelve a por el vértice. Eso hace el vuelo
     *       más feo, pero <b>no pierde ninguna pasada</b> —el vértice sigue siendo el objetivo y se
     *       acaba alcanzando—, mientras que la mentira de spec §9 solo la produce un vértice
     *       consumido sin haberlo volado. Un suelo que no protege de eso no puede ser el que decide
     *       si el barrido se rechaza; quien lo quiera decir, que lo avise.</li>
     * </ul>
     *
     * <p><b>Lo que costaba importarlo:</b> el hueco más corto de un barrido es el enlace entre
     * pasadas, {@code anchura × 16} bloques. Con el margen de fábrica, un suelo de 300 exigía
     * anchura ≥ 19 chunks, o sea un radio observado ≥ 12; un servidor que declarase 8, 10 u 11
     * —normal en anarchy— veía <b>rechazado todo barrido medido, siempre, para cualquier
     * rectángulo</b>, y ninguna de las salidas que el rechazo ofrecía servía ahí: por debajo de 150
     * el margen no movía el suelo, agrandar el área no separa las bandas y subir la anchura a mano
     * no aplica a quien la tiene medida. Con esta regla, ese mismo radio de 8 da anchura 12 y hueco
     * 192, que pasa de sobra, y el margen vuelve a ser una salida de verdad: en su mínimo admite
     * hasta un radio observado de 5.
     *
     * @param waypointMargin cuántos bloques antes de cada vértice intermedio se le cambia el
     *                       objetivo a Baritone
     */
    public static double minimumGap(double waypointMargin) {
        return waypointMargin;
    }

    /**
     * La pasada más corta del plan, en bloques: el hueco entre el vértice que la arranca y el que la
     * remata.
     *
     * <p><b>Este es el número que decide si un barrido se puede volar</b>, porque es el único hueco
     * cuya pérdida es la mentira de spec §9. Si una pasada cabe dentro del margen de waypoint, sus
     * dos vértices se consumen casi seguidos y <b>la pasada no se vuela nunca</b>: el adaptador pasa
     * de aimarla a darla por hecha, el barrido la cuenta como suya y esa franja del rectángulo queda
     * marcada como peinada sin que nadie la haya mirado.
     *
     * <p>Compárese con {@link #minimumGap(double)}. Solo puede quedarse corta en un área diminuta por
     * su eje largo: las pasadas van de punta a punta, así que la más corta mide el lado largo del
     * rectángulo entero.
     */
    public double shortestLane() {
        return pasadaMasCorta;
    }

    /**
     * El enlace más corto entre dos pasadas seguidas, en bloques: el salto perpendicular de una banda
     * a la siguiente.
     *
     * <p><b>Que este se quede corto no pierde ninguna pasada</b>, y por eso no es motivo de rechazo
     * sino de aviso. Si el enlace cabe dentro del margen, el adaptador suelta el vértice que remata
     * una pasada y en el tick siguiente suelta también el que arranca la otra, así que Baritone nunca
     * recibe la esquina: su objetivo pasa a ser <b>el final de la pasada siguiente</b>, y vuela hasta
     * él en diagonal. Esa diagonal recorre el eje largo entero derivando una banda a lo ancho, o sea
     * que cruza la banda de la pasada perdida igual —lo que se pierde es la esquina limpia, no el
     * terreno—. Lo que el barrido nunca puede hacer es saltarse una pasada entera, y eso lo vigila
     * {@link #shortestLane()}.
     *
     * <p>Y no se puede encadenar: consumido el enlace, el vértice siguiente es el final de la pasada,
     * que está a una pasada entera de distancia. Como mucho se pierde una esquina por curva.
     *
     * <p>Suele ser el hueco más corto de la ruta, y suele medir la anchura de pasada por 16. El
     * mínimo aparece en la última banda cuando el área no es múltiplo exacto de la anchura: esa banda
     * sale más estrecha, su pasada se centra más cerca de la anterior, y el enlace llega a valer
     * poco más de media anchura.
     */
    public double shortestLink() {
        return enlaceMasCorto;
    }

    /**
     * La separación más corta entre dos vértices consecutivos de la ruta, sean del tipo que sean.
     *
     * <p><b>No es el número con el que se decide si un barrido se vuela</b>, y confundirlo con eso
     * costó una ronda entera: los dos tipos de hueco pesan cosas distintas. Si el que se queda corto
     * es una pasada, esa pasada no se vuela y el barrido la da por peinada igual —la mentira de spec
     * §9, y eso se rechaza—; si es un enlace entre pasadas, lo que se pierde es la esquina y no el
     * terreno, y eso se avisa. Para decidir, {@link #shortestLane()} y {@link #shortestLink()}; esto
     * es la consulta general, útil para describir la ruta o para compararla con el suelo físico de la
     * elytra.
     *
     * <p>Una ruta de una sola pasada tiene un único hueco, el de la propia pasada.
     */
    public double tightestGap() {
        double minimo = Double.MAX_VALUE;
        for (int i = 0; i + 1 < waypoints.size(); i++) {
            minimo = Math.min(minimo, waypoints.get(i).distanceTo(waypoints.get(i + 1)));
        }
        return minimo;
    }
}
