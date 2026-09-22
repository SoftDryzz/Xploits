package com.xploits.sweep.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Convierte un área y la cobertura que el jugador ya tiene acumulada en la lista de pasadas que hay
 * que volar (spec Nether Sweep §4 y §5.1). Es el corazón del módulo: todo lo demás lo alimenta -el
 * área, la cobertura leída de {@code NewerNewChunks}, la anchura medida del flujo de chunks- o
 * ejecuta lo que sale de aquí.
 *
 * <p>Geometría de cortacésped, deliberadamente simple: pasadas rectas y paralelas, separadas por la
 * anchura de pasada. Nada que ver con {@code travel/core/RoutePlanner}, cuyo trabajo es justo el
 * contrario -hacer la ruta impredecible-; aquí lo que se quiere es cobertura regular y sin
 * sorpresas, y por eso el barrido lleva su propio planificador.
 *
 * <p><b>El criterio que decide todas las dudas de esta clase:</b> el peor fallo posible del módulo
 * no es dejar de volar, es dar por peinada una zona que nunca se miró (spec §9). Eso no se descubre
 * nunca: el jugador simplemente no vuelve, y la base que buscaba seguía ahí. De ahí salen las dos
 * decisiones que parecen desperdicio y no lo son:
 *
 * <ul>
 *   <li><b>Un hueco más estrecho que una pasada no se ignora.</b> Es la optimización tentadora
 *       -parece ruido y ahorra una pasada entera-, pero un hueco de un solo chunk es terreno sin
 *       ver, y el barrido lo marcaría como peinado igual.</li>
 *   <li><b>Las pasadas van de punta a punta del área</b>, aunque el hueco de esa banda sea un trozo
 *       corto. Recortarlas al hueco ahorraría vuelo a cambio de hacer depender la cobertura de los
 *       bordes de un dato que puede estar incompleto; volar de más es barato, ver de menos no.</li>
 * </ul>
 *
 * <p>Esta clase no toca Minecraft ni Meteor: trabaja sobre chunks y bloques como números.
 */
public final class SweepPlanner {
    private static final int BLOQUES_POR_CHUNK = 16;

    private SweepPlanner() {
    }

    /**
     * El resultado de planificar: o bien la lista de pasadas a volar, o bien un rechazo con su
     * motivo, igual que hace {@code travel/core/Route}.
     *
     * <p>Con una diferencia que importa y que es el motivo de que no se reutilice aquel record: una
     * ruta aceptada sin waypoints no significa nada, pero <b>un plan aceptado sin pasadas sí</b>. Es
     * el área ya cubierta entera: no hay nada que volar y eso no es un error, es el barrido
     * terminado. Lo que no puede existir es lo contrario -un plan rechazado que traiga pasadas-,
     * porque quien leyera solo la lista volaría un barrido que se había rechazado.
     */
    public record SweepPlan(List<Lane> lanes, String rejection) {
        public SweepPlan {
            lanes = List.copyOf(lanes);
            if (rejection != null && !lanes.isEmpty()) {
                throw new IllegalArgumentException(
                    "un plan rechazado no puede traer pasadas: quien leyera solo la lista volaría"
                        + " un barrido que se había rechazado");
            }
        }

        /** Un plan aceptado, con sus pasadas -que pueden ser ninguna si no queda nada por ver-. */
        public static SweepPlan of(List<Lane> lanes) {
            return new SweepPlan(lanes, null);
        }

        /** Un plan rechazado: sin pasadas, con el motivo. */
        public static SweepPlan rejected(String reason) {
            return new SweepPlan(List.of(), reason);
        }

        public boolean isRejected() {
            return rejection != null;
        }

        /**
         * Los bloques del barrido en sí: <b>la longitud de todas las pasadas más la de los enlaces
         * que las unen</b>, es decir el trayecto <b>desde el arranque de la primera pasada hasta el
         * final de la última</b>. Un plan sin pasadas mide cero, y uno de una sola pasada mide esa
         * pasada, porque no hay ningún enlace.
         *
         * <p><b>No es todo lo que el jugador va a volar</b>, y quien estime cohetes con esto tiene
         * que saberlo: falta la aproximación, el trayecto desde donde esté el jugador hasta el
         * arranque de la primera pasada. En un barrido de los que justifican este módulo -a una hora
         * de casa- esa pata es la más larga de todas, y sobre el ejemplo de spec §6, unos 116.000
         * bloques de barrido, son decenas de miles más. <b>Quien calcule el presupuesto de cohetes
         * debe sumarla aparte</b>, y si además piensa volver, también la vuelta.
         *
         * <p>Aquí no se puede incluir: un {@link SweepPlan} es geometría del área y no sabe dónde
         * está el jugador. Por eso este método promete el barrido y no «el viaje», y por eso lo dice
         * en la primera línea en vez de dejarlo a que alguien lo deduzca del nombre.
         *
         * <p>Los enlaces sí cuentan porque este número alimenta una decisión de seguridad: de él sale
         * la estimación de cohetes que se enseña <b>antes</b> de despegar (spec §6), y quedarse sin
         * cohetes lejos de casa cuesta la sesión entera. Una distancia por debajo de la real da
         * cohetes estimados de menos, y entonces el módulo dice «te llegan» a un jugador al que no
         * le llegan: la comprobación previa, que es justo la que existe para evitar el viaje, la
         * pasaría en falso. Que la medición en vuelo lo cace a la media hora no lo salva, porque
         * para entonces ya está lejos.
         *
         * <p>Y no son un redondeo. Con las pasadas de punta a punta, el enlace entre dos pasadas
         * consecutivas es un salto perpendicular que vale una anchura de pasada si las bandas van
         * seguidas, pero tantas como bandas ya vistas se hayan saltado por en medio si no —y
         * saltarse bandas es el caso normal aquí, porque se planifica sobre huecos—.
         */
        public double totalBlocks() {
            double total = 0.0;
            for (int i = 0; i < lanes.size(); i++) {
                Lane pasada = lanes.get(i);
                total += pasada.lengthInBlocks();
                if (i + 1 < lanes.size()) {
                    Lane siguiente = lanes.get(i + 1);
                    total += Math.hypot(siguiente.fromX() - pasada.toX(),
                        siguiente.fromZ() - pasada.toZ());
                }
            }
            return total;
        }
    }

    /**
     * Planifica el barrido del área, saltándose lo que ya se ha visto.
     *
     * <p>Las pasadas van <b>paralelas al eje largo</b> del rectángulo y se apilan a lo ancho del eje
     * corto, separadas por {@code laneWidthInChunks}. Por ahí salen menos pasadas y menos giros que
     * apilándolas al revés, y cada giro es un waypoint en el que Baritone frena y aterriza.
     *
     * <p>Cada banda de {@code laneWidthInChunks} chunks se mira entera: si le queda algún chunk sin
     * ver, se vuela; si está vista del todo, se salta. La pasada se coloca en el centro de la banda,
     * con lo que ningún chunk de esa banda queda a más de media anchura de pasada de ella.
     *
     * @param area              el rectángulo a barrer, en chunks del Nether
     * @param seen              los chunks que el jugador ya ha visto
     * @param laneWidthInChunks separación entre pasadas, en chunks; normalmente medida del flujo de
     *                          chunks que manda el servidor, no tecleada
     * @return el plan, o un rechazo si la anchura de pasada no sirve para barrer
     */
    public static SweepPlan plan(SweepArea area, Coverage seen, int laneWidthInChunks) {
        if (laneWidthInChunks <= 0) {
            return SweepPlan.rejected(anchuraInservible(laneWidthInChunks));
        }

        boolean pasadasEnX = area.widthInChunks() >= area.heightInChunks();
        int minRecorrido = pasadasEnX ? area.minChunkX() : area.minChunkZ();
        int maxRecorrido = pasadasEnX ? area.maxChunkX() : area.maxChunkZ();
        int minApilado = pasadasEnX ? area.minChunkZ() : area.minChunkX();
        int maxApilado = pasadasEnX ? area.maxChunkZ() : area.maxChunkX();

        // En long para que un área enorme con una anchura de pasada de 1 no desborde la cuenta de
        // bandas antes de poder recorrerlas.
        long chunksApilados = (long) maxApilado - minApilado + 1;
        long bandas = (chunksApilados + laneWidthInChunks - 1) / laneWidthInChunks;

        List<Lane> pasadas = new ArrayList<>();
        for (long banda = 0; banda < bandas; banda++) {
            int desde = (int) (minApilado + banda * laneWidthInChunks);
            int hasta = (int) Math.min((long) desde + laneWidthInChunks - 1, maxApilado);
            if (bandaVistaEntera(seen, pasadasEnX, desde, hasta, minRecorrido, maxRecorrido)) {
                continue;
            }
            pasadas.add(pasadaDeLaBanda(pasadasEnX, desde, hasta, minRecorrido, maxRecorrido,
                pasadas.size()));
        }
        return SweepPlan.of(pasadas);
    }

    /**
     * Si toda la banda está ya vista. En cuanto aparece un chunk sin ver se corta y la banda se
     * vuela: <b>no hay ningún umbral por debajo del cual un hueco se desprecie</b>. Un hueco de un
     * chunk es igual de terreno sin ver que uno de cien, y la única diferencia es que el pequeño
     * resulta más fácil de justificar tirando a la basura.
     */
    private static boolean bandaVistaEntera(Coverage seen, boolean pasadasEnX, int desde, int hasta,
                                             int minRecorrido, int maxRecorrido) {
        for (int apilado = desde; apilado <= hasta; apilado++) {
            for (int recorrido = minRecorrido; recorrido <= maxRecorrido; recorrido++) {
                ChunkPos chunk = pasadasEnX
                    ? new ChunkPos(recorrido, apilado)
                    : new ChunkPos(apilado, recorrido);
                if (!seen.seen(chunk)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * La pasada de una banda, centrada en ella y de punta a punta del eje largo.
     *
     * <p><b>El sentido alterna, y alterna contando las pasadas que se vuelan, no las bandas.</b> La
     * lista sale en el orden en que el adaptador las va a volar, una detrás de otra, así que si dos
     * pasadas seguidas fueran en el mismo sentido el jugador tendría que recorrer el largo entero
     * del área en vacío para colocarse al principio de la segunda. Alternar por número de banda
     * parece lo mismo y no lo es: en cuanto una banda se salta por estar ya vista -que es el caso
     * normal aquí, porque se planifica sobre huecos- la paridad se rompe y aparecen justo esos
     * viajes en vacío. Contando las pasadas emitidas, cada una arranca donde terminó la anterior
     * pase lo que pase con las bandas de en medio.
     *
     * <p><b>Ninguna pasada sale con los dos extremos en el mismo punto.</b> Los extremos caen sobre
     * el centro del primer y del último chunk del eje largo, así que un área de un solo chunk los
     * dejaría encima: una «pasada» de longitud cero. No emitirla sería el fallo que este módulo no
     * puede cometer -ese chunk se quedaría sin ver y el barrido lo daría por peinado igual-, y
     * entregarla tal cual tampoco vale: una pasada de longitud cero no es una instrucción de vuelo,
     * es un vector nulo que el adaptador tendría que normalizar y un objetivo idéntico al origen
     * para Baritone. Así que se le da la longitud mínima que significa algo aquí: el chunk entero,
     * de borde a borde, que es exactamente el terreno que hay que hacer que el servidor mande. El
     * centro del chunk sigue cayendo sobre la pasada, así que la cobertura no cambia. Solo puede
     * pasar en un área de 1x1 chunk: en cualquier otra, el eje largo mide dos chunks o más.
     */
    private static Lane pasadaDeLaBanda(boolean pasadasEnX, int desde, int hasta, int minRecorrido,
                                         int maxRecorrido, int pasadasYaEmitidas) {
        boolean deIda = pasadasYaEmitidas % 2 == 0;
        double centro = centroDeLaBandaEnBloques(desde, hasta);
        double principio = centroDelChunkEnBloques(minRecorrido);
        double fin = centroDelChunkEnBloques(maxRecorrido);
        if (principio == fin) {
            principio -= BLOQUES_POR_CHUNK / 2.0;
            fin += BLOQUES_POR_CHUNK / 2.0;
        }
        double arranque = deIda ? principio : fin;
        double remate = deIda ? fin : principio;
        return pasadasEnX
            ? new Lane(arranque, centro, remate, centro)
            : new Lane(centro, arranque, centro, remate);
    }

    /**
     * El centro de la banda en bloques. Con anchura par cae entre dos chunks, que es lo correcto: lo
     * que hay que minimizar es la distancia del chunk más alejado de la banda, no cuadrar la pasada
     * con una rejilla.
     */
    private static double centroDeLaBandaEnBloques(int desde, int hasta) {
        double centroEnChunks = (desde + (double) hasta) / 2.0;
        return centroEnChunks * BLOQUES_POR_CHUNK + BLOQUES_POR_CHUNK / 2.0;
    }

    /** El centro de un chunk en bloques, para que los extremos de la pasada caigan sobre él. */
    private static double centroDelChunkEnBloques(int chunk) {
        return chunk * (double) BLOQUES_POR_CHUNK + BLOQUES_POR_CHUNK / 2.0;
    }

    /**
     * El motivo del rechazo por anchura de pasada inservible, con el estilo de
     * {@code travel/core/RoutePlanner}: dice qué ajuste tocar, cuánto vale ahora y a qué subirlo, no
     * solo que algo está mal. Un rechazo que no dice cómo salir del atasco es casi tan malo como el
     * silencio.
     *
     * <p><b>Los ajustes se nombran tal y como aparecen en la interfaz de Meteor</b> -{@code
     * lane-width} y {@code lane-width-margin} del módulo {@code nether-sweep}-, y no solo por su
     * nombre en prosa. Un motivo que dijera únicamente «sube la anchura de pasada» manda al jugador a
     * buscar en la ClickGUI algo que no existe con ese nombre, y entonces el rechazo vuelve a ser lo
     * que este texto existe para no ser: saber que algo está mal y no saber qué tocar.
     *
     * <p>Y se rechaza en vez de degradar a 1 por lo de siempre: un barrido volado con una separación
     * que el módulo se inventa deja franjas sin mirar y las marca como peinadas igual. Degradar aquí
     * sería contar en silencio la única mentira que este módulo no puede contar.
     */
    private static String anchuraInservible(int laneWidthInChunks) {
        String queHaria = laneWidthInChunks == 0
            ? "no avanza ni una banda: cada pasada saldría encima de la anterior y el recorrido del"
                + " área no terminaría nunca"
            : "avanza hacia atrás: las bandas se irían saliendo del área por el borde contrario y el"
                + " rectángulo no llegaría a recorrerse entero";
        return "El barrido con la anchura de pasada -el ajuste lane-width- en " + laneWidthInChunks
            + " chunks " + queHaria
            + ". Degradarla a 1 chunk en silencio sería peor que pararse: el barrido volaría con una"
            + " separación inventada, dejaría franjas sin mirar y las marcaría como peinadas igual."
            + " Sube lane-width a 1 chunk o más. Normalmente no se teclea -se deja en 0 y sale medida"
            + " del flujo de chunks que manda el servidor-, así que si ha llegado aquí en "
            + laneWidthInChunks + " es que la medida todavía no tiene muestras o que"
            + " lane-width-margin la ha dejado en eso.";
    }
}
