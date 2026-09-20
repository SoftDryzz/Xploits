package com.xploits.travel.core;

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

    private RoutePlanner() {
    }

    /**
     * Planea el viaje de {@code origin} a {@code destination} con el patrón {@code pattern}.
     *
     * @param highwayMaxAmplitude el ancho máximo del corredor permitido cuando el destino es de
     *                            autopista; fuera de autopista no se usa
     * @return la ruta, o un rechazo si el patrón no es compatible con el destino pedido
     */
    public static Route plan(Waypoint origin, Destination destination, FlightPattern pattern,
                              PatternParams params, double highwayMaxAmplitude) {
        if (pattern == FlightPattern.SENUELO && destination.highway()) {
            return Route.rejected("el señuelo se saldría del corredor de la autopista, se rechaza en vez de degradarse");
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

        return switch (pattern) {
            case RECTO -> Route.of(List.of(destinationPoint));
            case ZIGZAG -> {
                double amplitude = effectiveAmplitude(params.amplitude(), destination, highwayMaxAmplitude);
                String rejection = lateralRejection(pattern, distance, params.period(),
                    params.amplitude(), amplitude, destination.highway());
                yield rejection != null ? Route.rejected(rejection)
                    : Route.of(zigzag(origin, destinationPoint, ux, uz, nx, nz, distance, params.period(), amplitude));
            }
            case QUIEBRO -> {
                double amplitude = effectiveAmplitude(params.lateralOffset(), destination, highwayMaxAmplitude);
                String rejection = lateralRejection(pattern, distance, params.legLength(),
                    params.lateralOffset(), amplitude, destination.highway());
                yield rejection != null ? Route.rejected(rejection)
                    : Route.of(zigzag(origin, destinationPoint, ux, uz, nx, nz, distance, params.legLength(), amplitude));
            }
            case ESPIRAL -> {
                double radiusCap = destination.highway() ? highwayMaxAmplitude : Double.POSITIVE_INFINITY;
                double radius = Math.min(Math.min(params.spiralRadius(), distance / 2.0), radiusCap);
                String rejection = spiralRejection(params.spiralRadius(), radius, params.spiralTurns(),
                    destination.highway());
                yield rejection != null ? Route.rejected(rejection)
                    : Route.of(spiral(destinationPoint, ux, uz, nx, nz, radius, params));
            }
            case SENUELO -> {
                String rejection = decoyRejection(params.decoyAngleDegrees(), params.decoyFraction());
                yield rejection != null ? Route.rejected(rejection)
                    : Route.of(decoy(origin, destinationPoint, ux, uz, distance, params));
            }
        };
    }

    /** El modo autopista acota la amplitud: fuera de autopista se usa la configurada sin tocar. */
    private static double effectiveAmplitude(double amplitude, Destination destination, double highwayMaxAmplitude) {
        return destination.highway() ? Math.min(amplitude, highwayMaxAmplitude) : amplitude;
    }

    /**
     * Las cuatro maneras en que un patrón lateral -ZIGZAG y QUIEBRO son la misma familia, spec §5-
     * se queda sin patrón, las cuatro sin hacer ruido:
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
    private static String lateralRejection(FlightPattern pattern, double distance, double step,
                                            double configuredAmplitude, double effectiveAmplitude,
                                            boolean highway) {
        if (step <= 0) {
            return pattern + " con " + stepName(pattern) + " en " + number(step) + " bloques no deja ningún"
                + " avance entre cambios de lado: no se dibujaría ni una ondulación y la ruta saldría recta"
                + " hasta el destino sin avisar. Sube " + stepName(pattern) + " por encima de 0 bloques o"
                + " elige RECTO.";
        }
        if (distance < step) {
            return pattern + " con " + stepName(pattern) + " en " + number(step) + " bloques no cabe ni una vez"
                + " en un viaje de " + number(distance) + " bloques: no se dibujaría ni una ondulación y la ruta"
                + " saldría recta sin avisar. Baja " + stepName(pattern) + " por debajo de " + number(distance)
                + " bloques o elige RECTO.";
        }
        if (effectiveAmplitude <= 0) {
            return pattern + " con " + effectiveSideName(pattern) + " en " + number(effectiveAmplitude)
                + " bloques no se aparta del eje: los waypoints saldrían todos sobre la recta, un patrón"
                + " decorativo. " + howToWiden(sideName(pattern), configuredAmplitude, highway);
        }
        double neededSteps = Math.floor(distance / step);
        if (neededSteps > MAX_PATTERN_WAYPOINTS) {
            // El paso mínimo que cubre el viaje entero, redondeado hacia arriba para que sea un número
            // de bloques redondo y para que floor(distancia/paso) quede en el tope o por debajo, nunca
            // justo encima por un decimal.
            double minimumStep = Math.ceil(distance / MAX_PATTERN_WAYPOINTS);
            double covered = MAX_PATTERN_WAYPOINTS * step;
            return pattern + " con " + stepName(pattern) + " en " + number(step) + " bloques necesitaría "
                + number(neededSteps) + " cambios de lado para ondular un viaje de " + number(distance)
                + " bloques, y una ruta no admite más de " + MAX_PATTERN_WAYPOINTS + ": el patrón cubriría"
                + " solo los primeros " + number(covered) + " bloques y los últimos "
                + number(distance - covered) + " saldrían en línea recta hasta el destino sin avisar, justo"
                + " el tramo que llega a casa. Sube " + stepName(pattern) + " a " + number(minimumStep)
                + " bloques o más, que es donde el patrón vuelve a caber entero, o elige RECTO.";
        }
        return null;
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
    private static String spiralRejection(double configuredRadius, double effectiveRadius, double turns,
                                           boolean highway) {
        if (effectiveRadius <= 0) {
            return FlightPattern.ESPIRAL + " con el radio efectivo en " + number(effectiveRadius)
                + " bloques no da ninguna vuelta: todos sus pasos caerían sobre el destino, así que la"
                + " aproximación sería recta. " + howToWiden("el radio", configuredRadius, highway);
        }
        if (turns == 0) {
            return FlightPattern.ESPIRAL + " con las vueltas en " + number(turns) + " no gira: sus pasos"
                + " caerían todos sobre el eje, entre el destino y el punto a una radio antes, así que la"
                + " aproximación sería recta. Sube las vueltas por encima de 0 o elige RECTO.";
        }
        return null;
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
    private static String decoyRejection(double angleDegrees, double fraction) {
        if (fraction == 0) {
            return FlightPattern.SENUELO + " con la fracción en " + number(fraction) + " no recorre nada"
                + " hacia el señuelo: el punto de corrección caería sobre el propio origen y la ruta sería la"
                + " recta al destino. Sube la fracción por encima de 0 o elige RECTO.";
        }
        if (angleDegrees % 180 == 0) {
            return FlightPattern.SENUELO + " con el ángulo en " + number(angleDegrees) + " grados no apunta"
                + " fuera del eje: el punto de corrección caería sobre la propia recta origen-destino y la"
                + " ruta sería recta, sin despistar a nadie. Dale al ángulo un valor que no sea múltiplo de"
                + " 180 grados o elige RECTO.";
        }
        return null;
    }

    /** La salida del atasco, que cambia según de dónde venga el cero: del corredor o del ajuste. */
    private static String howToWiden(String settingName, double configured, boolean highway) {
        if (highway && configured > 0) {
            return "El ajuste vale " + number(configured) + " bloques, pero el ancho máximo del corredor de la"
                + " autopista lo acota a 0: sube ese ancho por encima de 0 o elige RECTO.";
        }
        return "Sube " + settingName + " por encima de 0 bloques o elige RECTO.";
    }

    /** Cómo se llama el paso de cada patrón lateral en los ajustes, para que el motivo sea accionable. */
    private static String stepName(FlightPattern pattern) {
        return pattern == FlightPattern.QUIEBRO ? "el tramo" : "el periodo";
    }

    /** Cómo se llama el desvío de cada patrón lateral en los ajustes. */
    private static String sideName(FlightPattern pattern) {
        return pattern == FlightPattern.QUIEBRO ? "el desvío lateral" : "la amplitud";
    }

    /** El mismo nombre con el adjetivo concordado, que en español no sale de concatenar. */
    private static String effectiveSideName(FlightPattern pattern) {
        return pattern == FlightPattern.QUIEBRO ? "el desvío lateral efectivo" : "la amplitud efectiva";
    }

    /** Sin decimales cuando el valor es entero, para que un motivo no diga "5000.0 bloques". */
    private static String number(double value) {
        if (!Double.isInfinite(value) && !Double.isNaN(value) && value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    /**
     * ZIGZAG y QUIEBRO comparten esta función; solo cambian el paso y la amplitud que reciben. Para
     * {@code i} de 1 a {@code floor(distance/period)}, el waypoint es {@code origen + u*(i*period) +
     * n*(amplitude * (i impar ? +1 : -1))}. Al final, siempre el destino exacto.
     *
     * <p>Los cuatro casos en que el patrón saldría recto sin avisar -que el paso sea cero o
     * negativo, que no quepa ni un tramo, que la amplitud efectiva sea cero, o que el patrón no
     * quepa entero bajo el tope de waypoints- se rechazan antes de llegar aquí, en
     * {@link #lateralRejection}, así que esta función siempre genera al menos un punto de patrón con
     * desvío real y la ondulación llega siempre hasta el destino: de aquí no sale ningún viaje
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
                                          double nx, double nz, double distance, double period, double amplitude) {
        List<Waypoint> points = new ArrayList<>();
        int steps = (int) Math.min(Math.floor(distance / period), MAX_PATTERN_WAYPOINTS);
        for (int i = 1; i <= steps; i++) {
            double along = i * period;
            double sign = (i % 2 == 1) ? 1 : -1;
            double x = origin.x() + ux * along + nx * amplitude * sign;
            double z = origin.z() + uz * along + nz * amplitude * sign;
            points.add(new Waypoint(x, z));
        }

        // El último punto lateral es un desvío en balde si ya cae a la altura del destino: te
        // aparta la amplitud entera sin ganar ningún avance, justo al final del viaje, que es donde
        // menos fuegos artificiales quedan. Se omite cuando lo que falta por recorrer en línea recta
        // es menor que la propia amplitud del desvío -nunca cuando ese resto es negativo: eso
        // significaría haberse pasado del destino, no estar cerca de él, y con floor() bien
        // calculado nunca ocurre; solo aparecería si algo más arriba estuviera roto.
        //
        // Ese "remaining >= 0" es una afirmación sobre el invariante, no una rama viva: con floor()
        // el resto cae siempre en [0, paso), y el tope de MAX_PATTERN_WAYPOINTS ya no lo estira
        // porque un conteo que no cabe bajo el tope se rechaza en vez de truncarse. Así
        // que quitarlo A SOLAS no cambia ni un waypoint y ningún test puede ponerse en rojo por
        // ello; lo que sí se nota es quitarlo JUNTO con cambiar floor por ceil, y de eso se ocupa
        // theWaypointCoordinatesMatchFloorOfDistanceOverPeriodForANonExactDivision. No se busque
        // un test que cubra la guarda por separado: no existe mientras floor() esté bien.
        //
        // Nunca se omite si eso deja la ruta sin ningún punto de patrón: con un solo tramo, omitirlo
        // dejaría una ruta completamente recta pese a haber pedido un patrón -la misma degradación
        // silenciosa que rechazamos con el señuelo en autopista. Más vale un rodeo entero que un
        // viaje recto que el jugador cree ondulado.
        if (points.size() > 1) {
            double remaining = distance - steps * period;
            if (remaining >= 0 && remaining < amplitude) {
                points.remove(points.size() - 1);
            }
        }

        points.add(destination);
        return points;
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
