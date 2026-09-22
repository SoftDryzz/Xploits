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

    private SweepRoute(List<Waypoint> waypoints, double[] restanteDesde, double aproximacion,
                       double regreso) {
        this.waypoints = waypoints;
        this.restanteDesde = restanteDesde;
        this.aproximacion = aproximacion;
        this.regreso = regreso;
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

        double aproximacion = origin.distanceTo(vertices.get(0));
        return new SweepRoute(List.copyOf(vertices), restante, aproximacion, regreso);
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
     * La separación más corta entre dos vértices consecutivos de la ruta.
     *
     * <p>Quien vuele esto tiene que compararla con la separación mínima que admite su margen de
     * waypoint: dos vértices más juntos que eso se consumen en el mismo tick —cuando se suelta el
     * primero ya se está a un margen de él, y el segundo cae dentro del otro margen—, y aquí lo que
     * se consume sin volar <b>son pasadas enteras</b>, que el barrido daría por peinadas igual. Es el
     * peor fallo que este módulo puede cometer (spec §9).
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
