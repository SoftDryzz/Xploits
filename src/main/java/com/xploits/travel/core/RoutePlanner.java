package com.xploits.travel.core;

import com.xploits.shared.core.i18n.Msg;

import java.util.ArrayList;
import java.util.List;

/**
 * Calcula la ruta de un viaje: la secuencia de waypoints que llevan de {@code origin} al destino
 * siguiendo el patrón de despiste elegido (spec AutoTravel).
 *
 * <p>Toda la geometría vive aquí, en bloques de Minecraft sobre el plano XZ, sin saber nada de
 * Baritone ni del mundo real: eso es cosa de {@link BaritoneScript} y del adaptador.
 *
 * <p>Ningún patrón saca al jugador del corredor de una autopista (spec §4.2): en ZIGZAG y QUIEBRO
 * se acota la amplitud, en ESPIRAL se acota el radio, y SENUELO -que perdería su razón de ser si
 * se acotara, un señuelo que no puede apuntar fuera del eje no es nada- se rechaza en vez de
 * degradarse. Se acota lo que sigue funcionando acotado; se rechaza lo que no.
 *
 * <p>La otra cara de esa misma doctrina: una acotación que deja el patrón sin patrón ya no es una
 * acotación. Si el paso no cabe ni una sola vez en el viaje, si es cero o negativo, o si la amplitud
 * -o el radio, o las vueltas, o el ángulo del señuelo- deja los waypoints sobre el eje, la ruta sale
 * recta pese a haberse pedido evasión. Eso se rechaza con motivo en vez de entregarse en silencio:
 * el jugador que cree ondular y vuela recto dibuja exactamente la línea que delata su base. Y el
 * motivo dice siempre los números concretos y por dónde se sale del atasco, porque un rechazo que no
 * dice cómo salir es casi tan malo como el silencio.
 *
 * <p>El tope de waypoints se rige por lo mismo. Acota la CUENTA de puntos, no el alcance del patrón:
 * recortar la cuenta ondularía el principio del viaje y dejaría el final -el tramo que llega a casa-
 * en línea recta, que es la peor mitad donde dejarla. Así que un patrón que no cabe entero bajo el
 * tope se rechaza, y no se estira su paso para que quepa: un zigzag de periodo 200 cuando se pidió
 * 100 cubre el trayecto, sí, pero no es el patrón que se pidió, y callarlo es la misma degradación
 * silenciosa con otro disfraz.
 *
 * <p><b>Y la geometría tiene que ser volable, no solo dibujable.</b> Baritone da la ruta por
 * terminada y empieza a buscar sitio donde posarse en cuanto el jugador entra en los 48 bloques de
 * su objetivo ({@link #BARITONE_LANDING_DISTANCE}), así que el adaptador le cambia el objetivo
 * bastante antes de llegar. Eso obliga a que dos waypoints seguidos estén separados lo suficiente
 * para que ese adelanto quepa entre ellos: si no, se consumen en bloque sin volarse y el patrón se
 * degrada solo. La separación mínima vive aquí ({@link #minimumSpacing(double)}) y se aplica a los
 * cuatro patrones.
 *
 * <p>Los dos remedios posibles no valen lo mismo, y por eso no se aplica el mismo a todos:
 *
 * <ul>
 *   <li><b>ZIGZAG y QUIEBRO son periódicos.</b> Quitarles puntos intermedios es estirarles el paso
 *       a sus espaldas -un zigzag de periodo 6000 cuando se pidió 2000-, exactamente la sustitución
 *       que el párrafo anterior rechaza. Así que si su paso y su amplitud dejan los waypoints
 *       demasiado juntos <b>se rechaza</b>, con el número al que subirlos.</li>
 *   <li><b>ESPIRAL es el muestreo de una curva continua</b> que se cierra sobre el destino, y su
 *       último tramo es <b>invariablemente</b> involable: por definición termina en radio cero, y
 *       ningún ajuste lo arregla. Ahí sí se recorta -se dejan de emitir los pasos del núcleo, que
 *       es la misma acotación que ya le aplica el corredor de la autopista al radio-, y la curva
 *       que queda es la misma curva, no otra. Solo si de ese recorte no sobrevive ni un waypoint
 *       fuera del eje se rechaza: entonces la ruta sería recta.</li>
 * </ul>
 */
public final class RoutePlanner {
    /**
     * Tope de waypoints que puede generar un patrón ZIGZAG o QUIEBRO. Guarda contra parámetros
     * degenerados (un paso de 1 bloque en un viaje de 100 000, por ejemplo) que generarían cientos
     * de miles de puntos: el adaptador tendría que emitirlos uno a uno al chat de Baritone, así que
     * un número tan grande es en la práctica un cuelgue del cliente, no una ruta utilizable. 500
     * waypoints ya son muchísimos más de los que cualquier configuración razonable produce (con los
     * valores de fábrica, un viaje de 100 000 bloques genera 50).
     *
     * <p>Este tope NO recorta la ruta: pedir más de 500 cambios de lado se rechaza en
     * {@link #lateralRejection}. Truncar la cuenta dejaría el patrón a medias y el resto del viaje
     * recto, que es justo la degradación silenciosa que esta clase no entrega.
     */
    public static final int MAX_PATTERN_WAYPOINTS = 500;

    /**
     * Pasos de espiral por cada vuelta completa, fijado para que el ajuste de fábrica
     * ({@code spiralTurns = 1.5}) siga dando exactamente 36 pasos. Los pasos escalan con las vueltas
     * configuradas -en vez de quedarse fijos en 36- porque, si no, más vueltas significan más grados
     * por paso: con 3 vueltas y 36 pasos fijos el paso angular llega a 30°, y la "espiral" se ve
     * como un polígono estrellado en vez de una curva.
     */
    private static final double SPIRAL_STEPS_PER_TURN = 24.0;
    /** Un mínimo de pasos para que unas pocas vueltas no degeneren en un triángulo. */
    private static final int MIN_SPIRAL_STEPS = 8;

    /** Por debajo de esta distancia, origen y destino se consideran el mismo punto. */
    private static final double SAME_POINT_TOLERANCE = 1e-9;

    /** Por debajo de esta separación del eje, un waypoint se considera puesto sobre la recta. */
    private static final double AXIS_TOLERANCE = 1e-6;

    /**
     * A cuántos bloques de su objetivo da Baritone la ruta por terminada y <b>empieza a aterrizar</b>.
     *
     * <p>Leído del bytecode del jar instalado ({@code baritone-standalone-fabric-1.17.0.jar}, clase
     * {@code baritone/ju.class}, con {@code javap -p -c}): justo antes del {@code ldc} de la cadena
     * {@code "Path complete, searching for safe landing spot..."} compara la distancia <b>al
     * cuadrado</b> del jugador al destino de la ruta contra la constante {@code 2304.0d}, y
     * {@code sqrt(2304) = 48}.
     *
     * <p><b>No hay ajuste de Baritone que lo desactive.</b> Revisados sus 29 ajustes {@code elytra*}:
     * {@code elytraAllowEmergencyLand} y {@code elytraMinFireworksBeforeLanding} son para quedarse
     * sin fuegos, no para esto. {@code #elytra} significa "vuela hasta el objetivo <b>y pósate</b>",
     * y eso no se negocia; lo único que se puede hacer es quitarle el objetivo antes de que llegue.
     */
    public static final double BARITONE_LANDING_DISTANCE = 48;

    /**
     * Cuántos bloques antes de un waypoint intermedio el adaptador le cambia el objetivo a Baritone.
     *
     * <p>De dónde sale el número, sumando lo que tiene que caber dentro:
     *
     * <ul>
     *   <li><b>48 bloques</b> de {@link #BARITONE_LANDING_DISTANCE}: por debajo de eso Baritone ya
     *       está aterrizando y el daño está hecho.</li>
     *   <li><b>~2 bloques</b> de granularidad: el adaptador mira la distancia una vez por tick (20
     *       Hz) y una elytra con cohetes va a unos 33 bloques por segundo, así que entre cruzar el
     *       margen y enterarnos puede pasar algo menos de un tick de vuelo.</li>
     *   <li><b>~70 bloques</b> de reacción: entre que el {@code goal} y el {@code elytra} salen por
     *       el chat y Baritone deja de perseguir el objetivo viejo hay un recálculo de ruta que no
     *       es instantáneo y que desde aquí no se puede medir. Se presupuestan dos segundos de
     *       vuelo, el doble de lo que se le ve tardar en el log, porque quedarse corto reintroduce
     *       el fallo entero y pasarse solo redondea un poco más las esquinas.</li>
     * </ul>
     *
     * <p>48 + 2 + 70 = 120, redondeado a <b>150</b> para dejar un segundo largo de holgura. Por
     * debajo de {@link #MIN_WAYPOINT_MARGIN} el presupuesto de reacción se queda en nada y el
     * aterrizaje vuelve, así que ahí está el suelo del ajuste.
     *
     * <p><b>Lo que cuesta:</b> cambiar de objetivo 150 bloques antes redondea las esquinas del
     * patrón, porque el jugador nunca llega a tocar el vértice. Cuánto redondea depende de lo
     * tumbado que venga el tramo: con el ZIGZAG de fábrica (periodo 2000, amplitud 200) el tramo es
     * casi paralelo al eje y el vértice se queda a unos 29 bloques de su amplitud -el 15 %-; con el
     * QUIEBRO de fábrica (tramo 5000, desvío 800), a unos 46 de 800 -el 6 %-. Es el precio de no
     * aterrizar 73 veces, y es barato.
     */
    public static final double DEFAULT_WAYPOINT_MARGIN = 150;

    /**
     * El margen más bajo que se admite. Con 48 bloques de aterrizaje y un tick de granularidad, 100
     * deja unos 50 bloques -metro y medio de segundo- para que Baritone suelte el objetivo viejo.
     * Menos que eso ya no es un margen, es una apuesta.
     *
     * <p>El margen de fábrica de antes de este arreglo eran <b>30 bloques</b>, por debajo de los 48
     * de Baritone: el objetivo se cambiaba cuando ya llevaba un rato aterrizando. Este suelo también
     * arregla a quien tenga ese 30 guardado en su configuración: {@code Setting.set} de Meteor
     * devuelve {@code false} sin escribir nada cuando el valor no pasa {@code isValueValid}, y
     * {@code DoubleSetting.load} carga por ahí, así que un 30 persistido se descarta al cargar y el
     * ajuste se queda en su valor de fábrica.
     */
    public static final double MIN_WAYPOINT_MARGIN = 100;

    /**
     * El suelo físico de la separación entre dos waypoints seguidos, en bloques, independientemente
     * del margen configurado.
     *
     * <p>De dónde sale: una elytra empujada por cohetes va a unos 33 bloques por segundo y no gira
     * en seco -para dar media vuelta necesita un par de segundos y del orden de 70 u 80 bloques de
     * radio-. Dos waypoints separados por menos que unos cuantos radios de giro no son dos tramos:
     * son un bamboleo, y Baritone se los pasa de largo y vuelve a por ellos. 300 bloques son unos 9
     * segundos de crucero y unos cuatro radios de giro: el tramo más corto que todavía se vuela como
     * tramo.
     *
     * <p>Coincide con el doble del margen de fábrica, y no es casualidad: son los dos suelos del
     * mismo problema por caminos distintos -uno la física de la elytra, el otro la contabilidad de
     * Baritone- y se han cuadrado a propósito para que el ajuste de fábrica no dependa de cuál de
     * los dos mande.
     */
    public static final double MIN_WAYPOINT_SPACING = 300;

    /**
     * A cuántos bloques del <b>último</b> waypoint se da el viaje por llegado.
     *
     * <p>No es un ajuste, y es a propósito: el último waypoint es el destino real y ahí aterrizar es
     * justo lo que se quiere, así que aquí no hay nada que adelantar ni ningún número que afinar. Los
     * 30 bloques son los que el módulo ha usado siempre. El número que sí depende del jugador -cuánto
     * antes soltar los waypoints intermedios- es {@code waypoint-margin}, y dos deslizadores parecidos
     * con significados tan distintos solo invitan a tocar el que no es.
     */
    public static final double ARRIVAL_MARGIN = 30;

    private RoutePlanner() {
    }

    /**
     * Lo que le queda por volar al jugador: de donde está hasta el waypoint al que va, más el resto
     * de la ruta. Es la cuenta de {@code SweepRoute.remainingFrom}, para la cabecera de la consola.
     *
     * @throws IndexOutOfBoundsException si {@code index} no es un waypoint de la ruta
     * @throws NullPointerException      si {@code aqui} es nulo
     */
    public static double bloquesRestantes(List<Waypoint> ruta, int index, Waypoint aqui) {
        if (aqui == null) throw new NullPointerException("hace falta saber dónde está el jugador");
        if (index < 0 || index >= ruta.size()) {
            throw new IndexOutOfBoundsException("el waypoint " + index + " no existe en una ruta de " + ruta.size());
        }
        double total = aqui.distanceTo(ruta.get(index));
        for (int i = index; i < ruta.size() - 1; i++) total += ruta.get(i).distanceTo(ruta.get(i + 1));
        return total;
    }

    /**
     * A qué distancia del waypoint {@code index} de una ruta de {@code waypointCount} puntos se da
     * por alcanzado. <b>No es el mismo número para todos</b>, y ahí está el arreglo.
     *
     * <p>{@code #elytra} significa "vuela hasta el objetivo <b>y pósate</b>": Baritone da la ruta por
     * terminada y se pone a buscar sitio donde aterrizar en cuanto entra en los
     * {@link #BARITONE_LANDING_DISTANCE} bloques de su objetivo, y no tiene ningún ajuste que lo
     * desactive. Con el margen de 30 bloques de antes de este arreglo, el cambio de objetivo llegaba
     * <b>después</b> de que hubiera empezado a bajar: un patrón de 74 waypoints eran 73 aterrizajes y
     * 73 despegues repartidos por el trayecto -y con {@code elytraAutoJump}, 73 saltos desde el suelo-
     * en un módulo cuya razón de ser es no dejar rastro. Así que a los waypoints intermedios se les
     * cambia el objetivo {@code waypointMargin} bloques antes de llegar.
     *
     * <p>El <b>último</b> no: ese es el destino real, el único sitio donde aterrizar es lo que se
     * pidió. Adelantarse 150 bloques ahí sería mandarle {@code cancel} a Baritone en mitad del
     * descenso y soltar al jugador en el aire, así que se usa {@link #ARRIVAL_MARGIN}.
     */
    public static double reachedMargin(int index, int waypointCount, double waypointMargin) {
        return index == waypointCount - 1 ? ARRIVAL_MARGIN : waypointMargin;
    }

    /**
     * La separación mínima que se le exige a dos waypoints seguidos con el margen {@code
     * waypointMargin} configurado.
     *
     * <p>El <b>doble</b> del margen, y no el margen a secas, porque el margen se gasta dos veces:
     * cuando el adaptador suelta el waypoint N ya está a {@code margen} bloques de él, y si el
     * waypoint N+1 no está a más de otro {@code margen} por delante, el mismo tick que suelta N
     * suelta también N+1. Los puntos se consumirían en ráfaga sin haberse volado -una ráfaga de
     * {@code goal} al chat y un patrón que se recorta solo-, que es la degradación silenciosa de
     * siempre con otro disfraz.
     *
     * <p>Y nunca por debajo de {@link #MIN_WAYPOINT_SPACING}, que es lo que la elytra puede volar
     * como tramo aunque el margen baje.
     */
    public static double minimumSpacing(double waypointMargin) {
        return Math.max(MIN_WAYPOINT_SPACING, 2 * waypointMargin);
    }

    /**
     * Planea el viaje de {@code origin} a {@code destination} con el patrón {@code pattern}.
     *
     * @param highwayMaxAmplitude el ancho máximo del corredor permitido cuando el destino es de
     *                            autopista; fuera de autopista no se usa
     * @param waypointMargin      cuántos bloques antes de cada waypoint intermedio le cambia el
     *                            adaptador el objetivo a Baritone; de él sale la separación mínima
     *                            que se le exige a la geometría ({@link #minimumSpacing(double)})
     * @return la ruta, o un rechazo si el patrón no es compatible con el destino pedido
     */
    public static Route plan(Waypoint origin, Destination destination, FlightPattern pattern,
                              PatternParams params, double highwayMaxAmplitude, double waypointMargin) {
        if (pattern == FlightPattern.SENUELO && destination.highway()) {
            return Route.rejected(Msg.of(TravelText.DECOY_ON_HIGHWAY));
        }

        Waypoint destinationPoint = destination.resolve(origin);
        double distance = origin.distanceTo(destinationPoint);
        if (distance < SAME_POINT_TOLERANCE) {
            return Route.of(List.of(destinationPoint));
        }

        double ux = (destinationPoint.x() - origin.x()) / distance;
        double uz = (destinationPoint.z() - origin.z()) / distance;
        double nx = -uz;
        double nz = ux;
        double spacing = minimumSpacing(waypointMargin);

        return switch (pattern) {
            case RECTO -> Route.of(List.of(destinationPoint));
            case ZIGZAG -> {
                double amplitude = effectiveAmplitude(params.amplitude(), destination, highwayMaxAmplitude);
                Msg rejection = lateralRejection(pattern, distance, params.period(),
                    params.amplitude(), amplitude, destination.highway(), spacing, waypointMargin);
                yield rejection != null ? Route.rejected(rejection)
                    : Route.of(zigzag(origin, destinationPoint, ux, uz, nx, nz, distance, params.period(),
                        amplitude, spacing));
            }
            case QUIEBRO -> {
                double amplitude = effectiveAmplitude(params.lateralOffset(), destination, highwayMaxAmplitude);
                Msg rejection = lateralRejection(pattern, distance, params.legLength(),
                    params.lateralOffset(), amplitude, destination.highway(), spacing, waypointMargin);
                yield rejection != null ? Route.rejected(rejection)
                    : Route.of(zigzag(origin, destinationPoint, ux, uz, nx, nz, distance, params.legLength(),
                        amplitude, spacing));
            }
            case ESPIRAL -> {
                double radiusCap = destination.highway() ? highwayMaxAmplitude : Double.POSITIVE_INFINITY;
                double radius = Math.min(Math.min(params.spiralRadius(), distance / 2.0), radiusCap);
                Msg rejection = spiralRejection(params.spiralRadius(), radius, params.spiralTurns(),
                    destination.highway());
                if (rejection != null) yield Route.rejected(rejection);

                List<Waypoint> points = spaceOut(origin,
                    spiral(destinationPoint, ux, uz, nx, nz, radius, params), spacing);
                yield leavesTheAxis(points, origin, nx, nz)
                    ? Route.of(points)
                    : Route.rejected(spiralSpacingRejection(origin, destinationPoint, ux, uz, nx, nz, distance,
                        params, radius, destination.highway(), highwayMaxAmplitude, spacing, waypointMargin));
            }
            case SENUELO -> {
                Msg rejection = decoyRejection(params.decoyAngleDegrees(), params.decoyFraction());
                if (rejection != null) yield Route.rejected(rejection);

                List<Waypoint> points = decoy(origin, destinationPoint, ux, uz, distance, params);
                double shortest = Math.min(origin.distanceTo(points.get(0)),
                    points.get(0).distanceTo(destinationPoint));
                yield shortest >= spacing
                    ? Route.of(points)
                    : Route.rejected(decoySpacingRejection(distance, params, shortest, spacing, waypointMargin));
            }
        };
    }

    /** El modo autopista acota la amplitud: fuera de autopista se usa la configurada sin tocar. */
    private static double effectiveAmplitude(double amplitude, Destination destination, double highwayMaxAmplitude) {
        return destination.highway() ? Math.min(amplitude, highwayMaxAmplitude) : amplitude;
    }

    /**
     * Las cuatro maneras en que un patrón lateral -ZIGZAG y QUIEBRO son la misma familia, spec §5-
     * se queda sin patrón, las cuatro sin hacer ruido (y una quinta, la de abajo, en que el patrón
     * sale entero pero no se puede volar):
     *
     * <ul>
     *   <li><b>El paso es cero o negativo.</b> No hay ningún avance entre cambios de lado, así que
     *       no hay ondulación que dibujar. Antes esto se degradaba a RECTO en silencio para no
     *       explotar a miles de millones de iteraciones -{@code (int) floor(distancia/0.0)} es
     *       {@code Integer.MAX_VALUE}-, pero un rechazo evita el cuelgue igual de bien y además se
     *       oye: se devuelve antes de entrar en ningún bucle. Un paso de cero no "funciona
     *       acotado", no funciona.</li>
     *   <li><b>No cabe ni un tramo.</b> {@code floor(distancia/paso)} vale 0 en cuanto la distancia
     *       es menor que el paso, así que no se genera ni un solo punto de patrón. Con el tramo de
     *       fábrica del QUIEBRO (5000) eso es todo viaje de menos de 5000 bloques.</li>
     *   <li><b>La amplitud efectiva es cero.</b> Los puntos se generan, pero todos colineales con
     *       el eje: una recta con waypoints decorativos. No hace falta ningún parámetro absurdo
     *       para llegar aquí, basta un destino de autopista con el ancho del corredor a 0.</li>
     *   <li><b>El patrón no cabe entero bajo el tope de waypoints.</b> {@code floor(distancia/paso)}
     *       pasa de {@link #MAX_PATTERN_WAYPOINTS}. Este es el único de los cuatro en que la ruta no
     *       sale recta del todo: sale recta EL FINAL, que es peor. El periodo mínimo del deslizador
     *       (100) con un destino a 100 000 bloques pide 1000 cambios de lado; truncar la cuenta a
     *       500 ondula los primeros 50 000 bloques y deja los otros 50 000 en una línea perfecta
     *       apuntando a la base, justo el tramo que llega a casa. La spec §5 promete lo contrario
     *       ("el patrón se aplica en todo el trayecto"), así que el jugador cree que ondula entero.</li>
     * </ul>
     *
     * <p>En los cuatro casos la ruta sale recta -entera o en su tramo final- pese a haberse pedido
     * evasión, así que se rechaza con motivo, igual que el señuelo en autopista. Una amplitud
     * acotada a cero ya no "funciona acotada".
     *
     * <p>Y una quinta, que no es que el patrón no se dibuje sino que no se pueda volar: <b>los
     * waypoints quedan demasiado juntos</b>. Dos puntos seguidos de un patrón lateral están a
     * {@code hypot(paso, 2*amplitud)} uno de otro, y el primero está a {@code hypot(paso, amplitud)}
     * del origen -el más corto de los dos, así que es el que manda-. Si ese hueco no llega a
     * {@link #minimumSpacing(double)}, el adaptador suelta un waypoint y el siguiente en el mismo
     * tick y el patrón se consume en ráfaga. Aquí <b>no</b> se quitan puntos para arreglarlo: en un
     * patrón periódico quitar uno de cada dos es estirar el paso a espaldas del jugador, la misma
     * sustitución silenciosa del párrafo de abajo. Se rechaza, y el motivo lleva los dos números a
     * los que subir -paso o amplitud- para que elija él cuál mueve.
     *
     * <p>Va la última de las cinco a propósito: las cuatro de arriba dicen "no habría patrón" y esta
     * dice "habría patrón pero la elytra no puede volarlo". Un periodo de 100 bloques en un viaje de
     * 100 000 incumple las dos, y el motivo útil es el del tope, que nombra el tramo que se quedaría
     * recto.
     *
     * <p>El cuarto tiene una salida tentadora que NO se toma: estirar el paso hasta
     * {@code distancia/500} cubriría el viaje entero respetando el tope, sin rechazar nada y dejando
     * volar al jugador. Pero eso es entregarle un patrón que no pidió -un zigzag de periodo 200
     * cuando puso 100- y callárselo, que es la misma degradación silenciosa con otro disfraz: se
     * cambia la forma en vez de la longitud, y el jugador sigue creyendo que vuela lo que configuró.
     * Acotar el paso no es acotar: es sustituirlo. Entre engañarlo y pararlo con un motivo que dice
     * exactamente a cuánto subir el paso, se le para; el número que necesita va en el motivo y lo
     * sube él, sabiendo lo que vuela.
     *
     * @return el motivo del rechazo, o {@code null} si el patrón se puede dibujar de verdad
     */
    private static Msg lateralRejection(FlightPattern pattern, double distance, double step,
                                            double configuredAmplitude, double effectiveAmplitude,
                                            boolean highway, double minSpacing, double waypointMargin) {
        if (step <= 0) {
            return Msg.of(TravelText.LATERAL_NO_STEP, "pattern", pattern.name(), "step", stepName(pattern),
                "value", step);
        }
        if (distance < step) {
            return Msg.of(TravelText.LATERAL_STEP_OVER_TRIP, "pattern", pattern.name(), "step", stepName(pattern),
                "value", step, "distance", distance);
        }
        if (effectiveAmplitude <= 0) {
            return Msg.of(TravelText.LATERAL_NO_SIDE, "pattern", pattern.name(), "side", effectiveSideName(pattern),
                "amplitude", effectiveAmplitude, "fix", howToWiden(sideName(pattern), configuredAmplitude, highway));
        }
        double neededSteps = Math.floor(distance / step);
        if (neededSteps > MAX_PATTERN_WAYPOINTS) {
            // El paso mínimo que cubre el viaje entero, redondeado hacia arriba para que sea un número
            // de bloques redondo y para que floor(distancia/paso) quede en el tope o por debajo, nunca
            // justo encima por un decimal.
            double minimumStep = Math.ceil(distance / MAX_PATTERN_WAYPOINTS);
            double covered = MAX_PATTERN_WAYPOINTS * step;
            return Msg.of(TravelText.LATERAL_TOO_MANY_SIDES, "pattern", pattern.name(), "step", stepName(pattern),
                "value", step, "needed", neededSteps, "distance", distance, "max", MAX_PATTERN_WAYPOINTS,
                "covered", covered, "rest", distance - covered, "minimum", minimumStep);
        }
        double firstGap = Math.hypot(step, effectiveAmplitude);
        if (firstGap < minSpacing) {
            // Qué subir para llegar a la separación, manteniendo el otro valor. Los dos radicandos
            // son positivos justo por haber entrado aquí: hypot(paso, amplitud) < separación implica
            // que el paso y la amplitud son los dos menores que la separación.
            double neededStep = Math.ceil(Math.sqrt(minSpacing * minSpacing - effectiveAmplitude * effectiveAmplitude));
            double neededAmplitude = Math.ceil(Math.sqrt(minSpacing * minSpacing - step * step));
            return Msg.of(TravelText.LATERAL_TOO_CLOSE, "pattern", pattern.name(), "step", stepName(pattern),
                "value", step, "side", effectiveSideName(pattern), "amplitude", effectiveAmplitude,
                "gap", Math.floor(firstGap), "spacing", minSpacing, "why", whyThatSpacing(minSpacing, waypointMargin),
                "neededStep", neededStep, "sideName", sideName(pattern), "neededSide", neededAmplitude,
                "capped", cappedBySide(configuredAmplitude, effectiveAmplitude, highway));
        }
        if (omitsLastLateralPoint(distance, step, effectiveAmplitude, minSpacing)
            && Math.floor(distance / step) <= 1) {
            // El único punto de patrón cae tan pegado al destino que Baritone aterrizaría en él antes
            // de llegar a casa; y omitirlo dejaría la ruta completamente recta. No hay nada que
            // acotar: se rechaza.
            double lastGap = Math.hypot(distance - Math.floor(distance / step) * step, effectiveAmplitude);
            return Msg.of(TravelText.LATERAL_SINGLE_TOO_CLOSE, "pattern", pattern.name(), "step", stepName(pattern),
                "value", step, "distance", distance, "gap", Math.floor(lastGap), "spacing", minSpacing,
                "why", whyThatSpacing(minSpacing, waypointMargin), "sideName", sideName(pattern),
                "needed", Math.ceil(minSpacing));
        }
        return null;
    }

    /**
     * De dónde sale la separación exigida. Nombra siempre {@code waypoint-margin} con su valor, aunque
     * el que esté mandando sea el suelo físico: es el ajuste que el jugador puede mover para pedir
     * menos sitio, y un motivo que no lo nombra le deja sin esa salida.
     */
    private static Msg whyThatSpacing(double minSpacing, double waypointMargin) {
        if (minSpacing > MIN_WAYPOINT_SPACING) {
            return Msg.of(TravelText.WHY_SPACING, "margin", waypointMargin, "landing", BARITONE_LANDING_DISTANCE);
        }
        return Msg.of(TravelText.WHY_SPACING_FLOOR, "margin", waypointMargin, "landing", BARITONE_LANDING_DISTANCE,
            "floor", MIN_WAYPOINT_SPACING);
    }

    /**
     * Si el último punto de un patrón lateral cae tan cerca del destino que no se emite.
     *
     * <p>Es un desvío en balde: te aparta la amplitud entera sin ganar avance, justo al final del
     * viaje -donde menos fuegos quedan- y, peor desde este arreglo, deja a Baritone con dos objetivos
     * pegados y un aterrizaje de más entre ellos. El umbral es la separación mínima, el mismo criterio
     * que gobierna a todos los demás huecos de la ruta: antes era la amplitud, que no tenía nada que
     * ver con lo que Baritone puede volar.
     *
     * <p>Se usa {@code hypot} y no el resto a secas porque lo que importa es la distancia real entre
     * el último waypoint y el destino, que incluye el desvío lateral. De paso hace irrelevante el
     * signo del resto: con {@code ceil} en vez de {@code floor} el resto sale negativo, y un hueco
     * negativo no es un hueco corto.
     */
    private static boolean omitsLastLateralPoint(double distance, double step, double amplitude,
                                                  double minSpacing) {
        double remaining = distance - Math.floor(distance / step) * step;
        return Math.hypot(remaining, amplitude) < minSpacing;
    }

    /**
     * La espiral tiene el mismo agujero que ZIGZAG y QUIEBRO por sus dos ajustes, y por coherencia
     * recibe el mismo trato:
     *
     * <ul>
     *   <li><b>Radio efectivo cero.</b> Con el radio acotado a cero -un destino de autopista con el
     *       ancho del corredor a 0- todos sus pasos caen exactamente sobre el destino, porque
     *       {@code stepRadius = radius * (1 - fraction)} es cero para cualquier {@code fraction}. No
     *       es una espiral pequeña: son 37 copias del destino, ni una vuelta, ni un bloque de
     *       separación del eje.</li>
     *   <li><b>Vueltas a cero.</b> El radio sí decrece, pero el ángulo es {@code 2π * 0 * fraction},
     *       o sea cero en todos los pasos: los puntos se reparten sobre el propio eje, entre el
     *       destino y el punto a una radio antes. Es exactamente la aproximación recta que ya haría
     *       RECTO, con 9 waypoints decorativos encima.</li>
     * </ul>
     *
     * <p>Unas vueltas negativas NO entran aquí: {@link #spiralSteps} toma el valor absoluto para los
     * pasos y el ángulo sale negativo, así que la espiral gira al otro lado. Gira, que es lo único
     * que se le pide; eso sigue funcionando y no se rechaza.
     *
     * @return el motivo del rechazo, o {@code null} si la espiral se puede dibujar de verdad
     */
    private static Msg spiralRejection(double configuredRadius, double effectiveRadius, double turns,
                                           boolean highway) {
        if (effectiveRadius <= 0) {
            return Msg.of(TravelText.SPIRAL_NO_RADIUS, "pattern", FlightPattern.ESPIRAL.name(),
                "radius", effectiveRadius, "fix", howToWiden(TravelText.NAME_RADIUS, configuredRadius, highway));
        }
        if (turns == 0) {
            return Msg.of(TravelText.SPIRAL_NO_TURNS, "pattern", FlightPattern.ESPIRAL.name(), "turns", turns);
        }
        return null;
    }

    /**
     * El motivo de la espiral que se queda sin espiral al espaciarla: sus pasos están tan juntos que,
     * quitando los que no se pueden volar, no sobrevive ninguno fuera del eje y la ruta sale recta.
     *
     * <p>El número que hace falta es <b>el radio más pequeño con el que esta espiral vuelve a
     * dibujarse</b>, y se busca probando radios de bloque en bloque hasta el máximo geométrico
     * ({@code distancia/2}, que es donde la espiral empezaría por detrás del origen). Se busca en vez
     * de despejarlo porque el espaciado no tiene forma cerrada -depende de qué pasos sobreviven, que
     * depende de los que sobrevivieron antes-, y probar 37 puntos unos miles de veces es gratis en un
     * camino que solo se recorre para redactar un rechazo. La búsqueda vale porque la alternativa es
     * un motivo que dice "sube el radio" sin decir a cuánto, y ese es justo el rechazo que esta clase
     * no entrega.
     *
     * <p>Quién tiene la culpa del radio pequeño cambia la salida: si el destino es de autopista y el
     * ancho del corredor es lo que lo está acotando, el ajuste que hay que mover es ese ancho y no el
     * radio. Con los valores de fábrica -radio 1500, 1.5 vueltas, corredor 300- esta es exactamente
     * la combinación que se rechaza: una espiral que solo puede apartarse 300 bloques del eje deja
     * pasos de 8 bloques, y hacen falta 338 de radio para que vuelva a haber espiral.
     */
    private static Msg spiralSpacingRejection(Waypoint origin, Waypoint destination, double ux, double uz,
                                                  double nx, double nz, double distance, PatternParams params,
                                                  double effectiveRadius, boolean highway,
                                                  double highwayMaxAmplitude, double minSpacing,
                                                  double waypointMargin) {
        double neededRadius = 0;
        for (double radius = Math.ceil(effectiveRadius) + 1; radius <= distance / 2.0; radius++) {
            List<Waypoint> candidate = spaceOut(origin,
                spiral(destination, ux, uz, nx, nz, radius, params), minSpacing);
            if (leavesTheAxis(candidate, origin, nx, nz)) {
                neededRadius = radius;
                break;
            }
        }

        List<Waypoint> raw = spiral(destination, ux, uz, nx, nz, effectiveRadius, params);
        double shortestStep = Double.MAX_VALUE;
        for (int i = 0; i < raw.size() - 1; i++) {
            shortestStep = Math.min(shortestStep, raw.get(i).distanceTo(raw.get(i + 1)));
        }

        Msg fix;
        if (neededRadius == 0) {
            fix = Msg.of(TravelText.SPIRAL_FIX_SHORT_TRIP, "distance", distance);
        } else if (highway && params.spiralRadius() > highwayMaxAmplitude) {
            fix = Msg.of(TravelText.SPIRAL_FIX_CORRIDOR, "radius", params.spiralRadius(), "max", highwayMaxAmplitude,
                "needed", neededRadius);
        } else {
            fix = Msg.of(TravelText.SPIRAL_FIX_RADIUS, "needed", neededRadius);
        }
        return Msg.of(TravelText.SPIRAL_TOO_CLOSE, "pattern", FlightPattern.ESPIRAL.name(), "radius", effectiveRadius,
            "turns", params.spiralTurns(), "step", Math.floor(shortestStep), "spacing", minSpacing,
            "why", whyThatSpacing(minSpacing, waypointMargin), "fix", fix);
    }

    /**
     * El señuelo se queda sin señuelo cuando su punto de corrección -su único waypoint intermedio-
     * cae sobre la recta origen-destino, y entonces la ruta es la recta con una parada de más:
     *
     * <ul>
     *   <li><b>Fracción cero.</b> El punto de corrección es {@code origen + dirección*distancia*0},
     *       o sea el propio origen. Se "corrige" sin haberse apartado.</li>
     *   <li><b>Ángulo múltiplo de 180 grados.</b> La dirección del señuelo es la del rumbo real (0)
     *       o la contraria (180), así que el punto de corrección queda sobre el mismo eje: no hay
     *       nada que despistar. Se mira el múltiplo y no {@code sin(ángulo) == 0} porque
     *       {@code Math.sin(Math.toRadians(180))} vale 1,2e-16, no cero.</li>
     * </ul>
     *
     * <p>Una fracción negativa NO entra aquí: el punto de corrección queda fuera del eje, detrás del
     * origen. Es un rodeo caro, pero aparta del rumbo real de verdad, que es para lo que existe el
     * señuelo; se acota lo que sigue funcionando. Un ángulo negativo tampoco: despista hacia el otro
     * lado y ya está.
     *
     * @return el motivo del rechazo, o {@code null} si el señuelo despista de verdad
     */
    private static Msg decoyRejection(double angleDegrees, double fraction) {
        if (fraction == 0) {
            return Msg.of(TravelText.DECOY_NO_FRACTION, "pattern", FlightPattern.SENUELO.name(), "fraction", fraction);
        }
        if (angleDegrees % 180 == 0) {
            return Msg.of(TravelText.DECOY_NO_ANGLE, "pattern", FlightPattern.SENUELO.name(), "angle", angleDegrees);
        }
        return null;
    }

    /**
     * El motivo del señuelo cuyos dos tramos -origen a punto de corrección, y punto de corrección a
     * destino- son demasiado cortos para volarse como tramos.
     *
     * <p>Aquí no hay nada que recortar: el señuelo tiene un solo waypoint intermedio, así que o cabe
     * o no cabe. Y el número exacto que hace falta sale sin buscar nada, porque <b>los dos tramos son
     * proporcionales a la distancia del viaje</b>: el primero mide {@code fracción*distancia} y el
     * segundo {@code distancia*|fracción*dirección_señuelo - u|}, así que basta escalar. Por eso el
     * motivo habla de alejar el destino y no de tocar el ángulo: con el ángulo y la fracción de
     * fábrica el señuelo necesita un viaje de unos 530 bloques, y quien se lo encuentra es quien pide
     * un señuelo para ir a la vuelta de la esquina.
     */
    private static Msg decoySpacingRejection(double distance, PatternParams params, double shortestGap,
                                                 double minSpacing, double waypointMargin) {
        double neededDistance = Math.ceil(distance * minSpacing / shortestGap);
        return Msg.of(TravelText.DECOY_TOO_CLOSE, "pattern", FlightPattern.SENUELO.name(),
            "angle", params.decoyAngleDegrees(), "fraction", params.decoyFraction(), "gap", Math.floor(shortestGap),
            "distance", distance, "spacing", minSpacing, "why", whyThatSpacing(minSpacing, waypointMargin),
            "needed", neededDistance);
    }

    /**
     * La coletilla que avisa de que subir el desvío no va a servir de nada por sí solo porque el
     * corredor de la autopista lo está acotando. Sin ella, el motivo manda al jugador a mover un
     * deslizador que no cambia la ruta, que es peor que no decirle nada.
     */
    private static Msg cappedBySide(double configuredAmplitude, double effectiveAmplitude, boolean highway) {
        if (!highway || configuredAmplitude <= effectiveAmplitude) return Msg.of(TravelText.NOTHING);
        return Msg.of(TravelText.CAPPED_BY_CORRIDOR, "amplitude", effectiveAmplitude);
    }

    /** La salida del atasco, que cambia según de dónde venga el cero: del corredor o del ajuste. */
    private static Msg howToWiden(TravelText settingName, double configured, boolean highway) {
        if (highway && configured > 0) {
            return Msg.of(TravelText.WIDEN_CORRIDOR, "configured", configured);
        }
        return Msg.of(TravelText.WIDEN_SETTING, "setting", settingName);
    }

    /** Cómo se llama el paso de cada patrón lateral en los ajustes, para que el motivo sea accionable. */
    private static TravelText stepName(FlightPattern pattern) {
        return pattern == FlightPattern.QUIEBRO ? TravelText.NAME_LEG : TravelText.NAME_PERIOD;
    }

    /** Cómo se llama el desvío de cada patrón lateral en los ajustes. */
    private static TravelText sideName(FlightPattern pattern) {
        return pattern == FlightPattern.QUIEBRO ? TravelText.NAME_OFFSET : TravelText.NAME_AMPLITUDE;
    }

    /** El mismo nombre con el adjetivo concordado, que en español no sale de concatenar. */
    private static TravelText effectiveSideName(FlightPattern pattern) {
        return pattern == FlightPattern.QUIEBRO ? TravelText.NAME_OFFSET_EFFECTIVE : TravelText.NAME_AMPLITUDE_EFFECTIVE;
    }

    /**
     * ZIGZAG y QUIEBRO comparten esta función; solo cambian el paso y la amplitud que reciben. Para
     * {@code i} de 1 a {@code floor(distance/period)}, el waypoint es {@code origen + u*(i*period) +
     * n*(amplitude * (i impar ? +1 : -1))}. Al final, siempre el destino exacto.
     *
     * <p>Los cinco casos en que el patrón no se volaría como se pidió -que el paso sea cero o
     * negativo, que no quepa ni un tramo, que la amplitud efectiva sea cero, que el patrón no quepa
     * entero bajo el tope de waypoints, o que sus waypoints queden más juntos que la separación
     * mínima- se rechazan antes de llegar aquí, en {@link #lateralRejection}, así que esta función
     * siempre genera al menos un punto de patrón con desvío real, separado lo suficiente del
     * anterior, y la ondulación llega siempre hasta el destino: de aquí no sale ningún viaje
     * ondulado a medias.
     *
     * <p>El {@code Math.min} con {@link #MAX_PATTERN_WAYPOINTS} ya no decide nada -el rechazo
     * garantiza que el conteo cabe bajo el tope-, y se queda como cinturón contra el cuelgue por si
     * alguien se saltara ese rechazo: {@code floor(distancia/0.0)} es infinito y el {@code (int)} de
     * infinito es {@code Integer.MAX_VALUE}, dos mil millones de waypoints que el adaptador iría
     * emitiendo uno a uno al chat; con el min son 500, y con paso negativo el conteo sale negativo y
     * el bucle no se ejecuta. Por eso ningún test lo pone en rojo a solas: solo se nota si antes se
     * rompe el rechazo, y de eso se ocupan los tests del rechazo. Lo que quedaría en ambos casos es
     * una ruta absurda y callada, que es justo lo que el rechazo impide.
     */
    private static List<Waypoint> zigzag(Waypoint origin, Waypoint destination, double ux, double uz,
                                          double nx, double nz, double distance, double period, double amplitude,
                                          double minSpacing) {
        List<Waypoint> points = new ArrayList<>();
        int steps = (int) Math.min(Math.floor(distance / period), MAX_PATTERN_WAYPOINTS);
        for (int i = 1; i <= steps; i++) {
            double along = i * period;
            double sign = (i % 2 == 1) ? 1 : -1;
            double x = origin.x() + ux * along + nx * amplitude * sign;
            double z = origin.z() + uz * along + nz * amplitude * sign;
            points.add(new Waypoint(x, z));
        }

        // El último punto lateral se omite cuando queda pegado al destino: ver
        // omitsLastLateralPoint, que es donde vive el criterio y su porqué.
        //
        // La guarda "points.size() > 1" ya no decide nada: si el patrón solo tiene un punto y cae
        // dentro de la ventana de omisión, lateralRejection lo ha rechazado antes de llegar aquí
        // -conservarlo haría aterrizar a Baritone dos veces y quitarlo dejaría la ruta recta, y
        // ninguna de las dos es aceptable-. Se queda como cinturón: sin ella, romper ese rechazo
        // devolvería una ruta recta y callada en vez de una ruta con un rodeo de más, que es el
        // fallo peor de los dos. Por eso ningún test la pone en rojo a solas.
        //
        // Quitar la omisión entera SÍ se nota, y de eso se ocupan
        // aZigzagDoesNotWasteAFinalOutAndBackRightAtTheDestination y
        // theWaypointCoordinatesMatchFloorOfDistanceOverPeriodForANonExactDivision.
        if (points.size() > 1 && omitsLastLateralPoint(distance, period, amplitude, minSpacing)) {
            points.remove(points.size() - 1);
        }

        points.add(destination);
        return points;
    }

    /**
     * Quita de {@code raw} los waypoints que caen a menos de {@code minSpacing} del anterior que sí
     * se conserva, empezando a medir desde el propio {@code origin} -el primer waypoint también
     * tiene que estar lo bastante lejos del punto de partida, o se soltaría nada más despegar-. El
     * último punto de {@code raw}, que es el destino, se conserva siempre; los que queden pegados a
     * él por detrás se van, uno detrás de otro, hasta que el tramo final mida lo que tiene que medir.
     *
     * <p><b>Esto no se le aplica a ZIGZAG ni a QUIEBRO</b>, y no por olvido: en un patrón periódico
     * quitar uno de cada dos puntos es multiplicar el paso por dos sin decírselo al jugador. Ahí se
     * rechaza. Aquí se usa solo para la espiral, cuyos pasos son el muestreo de una curva continua:
     * quedarse con menos muestras del mismo trazo no cambia el trazo, y el núcleo que se deja fuera
     * -los últimos grados, con el radio ya casi a cero- no es volable con una elytra por mucho que
     * se configure, porque por definición termina en radio cero.
     */
    private static List<Waypoint> spaceOut(Waypoint origin, List<Waypoint> raw, double minSpacing) {
        Waypoint destination = raw.get(raw.size() - 1);
        List<Waypoint> kept = new ArrayList<>();
        Waypoint anchor = origin;
        for (int i = 0; i < raw.size() - 1; i++) {
            if (anchor.distanceTo(raw.get(i)) < minSpacing) continue;
            kept.add(raw.get(i));
            anchor = raw.get(i);
        }
        while (!kept.isEmpty() && kept.get(kept.size() - 1).distanceTo(destination) < minSpacing) {
            kept.remove(kept.size() - 1);
        }
        kept.add(destination);
        return kept;
    }

    /**
     * Si de {@code points} sobrevive algún waypoint intermedio fuera de la recta origen-destino.
     *
     * <p>Es la condición que separa "la espiral se ha recortado" de "la espiral ha desaparecido".
     * No basta con mirar cuántos puntos quedan: con el radio acotado al ancho de un corredor
     * estrecho, el único paso que sobrevive al espaciado es el primero, que está exactamente sobre
     * el eje -a una radio del destino, en línea recta-. La ruta sería la recta con un waypoint
     * decorativo, y eso se rechaza, no se entrega.
     */
    private static boolean leavesTheAxis(List<Waypoint> points, Waypoint origin, double nx, double nz) {
        for (int i = 0; i < points.size() - 1; i++) {
            double offset = (points.get(i).x() - origin.x()) * nx + (points.get(i).z() - origin.z()) * nz;
            if (Math.abs(offset) > AXIS_TOLERANCE) return true;
        }
        return false;
    }

    /**
     * El radio llega ya acotado desde {@link #plan}: a {@code distance/2} si el viaje es más corto
     * -así la espiral nunca retrocede detrás del origen- y, en autopista, también al ancho del
     * corredor ({@code highwayMaxAmplitude}), porque el punto más alejado del eje de cualquier paso
     * está a lo sumo a {@code stepRadius} del destino, y {@code stepRadius <= radius} siempre, así
     * que acotar el radio de partida basta para que ningún paso se salga del corredor. Se acota
     * allí y no aquí para que el rechazo por radio cero y la geometría hablen del mismo número.
     *
     * <p>Se va recto hasta {@code destino - u*radius} -que es exactamente el primer punto de la
     * espiral, con {@code j=0}- y desde ahí se dan {@code spiralTurns} vueltas cerrándose sobre el
     * destino, con el ángulo medido desde la dirección {@code -u} hacia {@code n}: en {@code j=0}
     * eso da {@code destino - u*radius} en componentes, no solo en distancia, y es lo que garantiza
     * que el primer punto queda ANTES del destino y no después -medir desde {@code +u} pondría ese
     * mismo punto una radio PASADO el destino.
     */
    private static List<Waypoint> spiral(Waypoint destination, double ux, double uz, double nx, double nz,
                                          double radius, PatternParams params) {
        int steps = spiralSteps(params.spiralTurns());

        List<Waypoint> points = new ArrayList<>();
        for (int j = 0; j <= steps; j++) {
            double fraction = (double) j / steps;
            double angle = 2 * Math.PI * params.spiralTurns() * fraction;
            double stepRadius = radius * (1 - fraction);
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            // Base (-u, n): ángulo 0 apunta hacia -u, y crece girando hacia n.
            double x = destination.x() + stepRadius * (cos * -ux + sin * nx);
            double z = destination.z() + stepRadius * (cos * -uz + sin * nz);
            points.add(new Waypoint(x, z));
        }
        return points;
    }

    /** Pasos de espiral escalados con las vueltas, con un mínimo para no degenerar en un polígono. */
    private static int spiralSteps(double turns) {
        return (int) Math.max(MIN_SPIRAL_STEPS, Math.ceil(SPIRAL_STEPS_PER_TURN * Math.abs(turns)));
    }

    /**
     * El señuelo es {@code origen + rotar(u, grados)*distancia}. El punto de corrección -el único
     * waypoint intermedio- es {@code origen + (señuelo - origen)*fracción}. Waypoints: el punto de
     * corrección, y el destino.
     */
    private static List<Waypoint> decoy(Waypoint origin, Waypoint destination, double ux, double uz,
                                         double distance, PatternParams params) {
        double radians = Math.toRadians(params.decoyAngleDegrees());
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double decoyDirectionX = ux * cos - uz * sin;
        double decoyDirectionZ = ux * sin + uz * cos;

        double correctionX = origin.x() + decoyDirectionX * distance * params.decoyFraction();
        double correctionZ = origin.z() + decoyDirectionZ * distance * params.decoyFraction();

        return List.of(new Waypoint(correctionX, correctionZ), destination);
    }
}
