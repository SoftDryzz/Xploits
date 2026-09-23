package com.xploits.travel.core;

import com.xploits.shared.core.i18n.Msg;

/**
 * Las reglas puras de la red de seguridad (spec AutoTravel §7): qué prefijos se pueden vigilar, qué
 * texto saliente es un comando de Baritone de los que dirigimos, y qué se le dice al jugador cuando
 * la restauración no ha llegado a ninguna parte.
 *
 * <p>Aquí no hay Minecraft. El adaptador solo saca del paquete dos cosas -por qué canal sale y qué
 * carga lleva- y pregunta; la decisión es de esta clase, que se prueba sin arrancar el juego. Eso
 * importa porque de este {@code startsWith} depende la razón de ser del módulo: un {@code #elytra}
 * que se escape es anunciarle a un servidor anarchy que vas volando y hacia dónde.
 *
 * <p><b>La sutileza del canal.</b> El texto de un comando de servidor no viaja con su barra: el
 * cliente la quita antes de construir el paquete. Compararlo tal cual contra el prefijo sería
 * comparar {@code "tpy Pepe"} en vez de {@code "/tpy Pepe"}, así que la barra se le devuelve antes
 * de decidir: la red compara siempre contra <b>el texto que el jugador habría escrito</b>.
 */
public final class SafetyNet {
    private SafetyNet() {
    }

    /** Los dos caminos por los que un texto escrito en el chat sale del cliente hacia el servidor. */
    public enum Channel {
        /** Chat plano. Es por donde viajan los comandos de Baritone, y el único que Baritone engancha. */
        CHAT,
        /** Comando de servidor. El texto viaja <b>sin</b> la barra inicial. */
        COMANDO
    }

    /**
     * El texto tal y como el jugador lo habría escrito en el chat, que es la forma en la que se
     * puede comparar con un prefijo.
     */
    public static String typedText(Channel channel, String payload) {
        if (channel == null) throw new IllegalArgumentException("un texto saliente tiene que venir por algún canal");
        if (payload == null) throw new IllegalArgumentException("un texto saliente no puede ser nulo");
        return channel == Channel.COMANDO ? "/" + payload : payload;
    }

    /**
     * El comando de un texto de Baritone sin sus argumentos: {@code "#goal 1200 -800"} da
     * {@code "#goal"}. Es lo que puede ir a la consola al avisar de que la red cortó algo: los
     * argumentos pueden ser coordenadas (spec consola §7).
     */
    public static String verbo(String texto) {
        if (texto == null) throw new IllegalArgumentException("un texto saliente no puede ser nulo");
        String limpio = texto.strip();
        if (limpio.isEmpty()) return "(vacío)"; // i18n: allowed (SafetyNet.verbo output is not player text per the task-5 brief)
        return limpio.split("\\s+", 2)[0];
    }

    /**
     * Si este texto saliente es un comando de Baritone del prefijo con el que se lanzó el viaje, y
     * por tanto la red tiene que matarlo antes de que llegue al servidor.
     *
     * <p>Con un prefijo que {@link #prefixRejection} rechazaría se contesta que no, y no es un
     * descuido: un prefijo vacío haría que <i>todo</i> texto empezara por él, y la red pasaría de
     * cancelar comandos de Baritone a amordazar el chat entero del jugador mientras le diagnostica
     * una fuga que no existe. Un prefijo inservible se rechaza al lanzar -ahí sí, con su motivo y sin
     * mandar un solo comando-, que es donde se puede hacer algo al respecto; aquí ya no.
     */
    public static boolean directs(String prefix, Channel channel, String payload) {
        if (prefixRejection(prefix) != null) return false;
        return typedText(channel, payload).startsWith(prefix);
    }

    /**
     * El motivo por el que un prefijo no sirve para dirigir a Baritone, o {@code null} si sirve.
     *
     * <p>El caso interesante es el tercero. Un prefijo que empieza por {@code /} parece una
     * configuración exótica y es en realidad la trampa peor del módulo, por dos motivos a la vez:
     *
     * <ul>
     *   <li>El cliente manda por el camino de comando todo lo que empiece por barra, y el mixin de
     *       Baritone engancha el de chat plano (spec §2), así que el comando no llegaría nunca a
     *       Baritone: iría al servidor, que no lo conoce.</li>
     *   <li>Y la red, que cancela todo lo que empiece por el prefijo, se comería de paso <b>todos</b>
     *       los demás comandos con barra mientras el viaje dure -el {@code /tpy} de {@code auto-tpy}
     *       y los susurros de {@code kit-requester} incluidos-, diagnosticando además lo contrario de
     *       lo que pasa.</li>
     * </ul>
     *
     * <p>No se acota porque no hay nada que acotar: la doctrina del repo es <i>se acota lo que sigue
     * funcionando acotado; se rechaza lo que no</i>, y un prefijo con barra no funciona de ninguna
     * manera. Se rechaza al lanzar, antes de armar la red y antes de mandar un solo comando.
     */
    public static Msg prefixRejection(String prefix) {
        if (prefix == null || prefix.isEmpty()) return Msg.of(TravelText.PREFIX_EMPTY);
        if (prefix.isBlank()) return Msg.of(TravelText.PREFIX_BLANK);
        if (prefix.startsWith("/")) return Msg.of(TravelText.PREFIX_SLASH, "prefix", prefix);
        return null;
    }

    /**
     * Qué pasó de verdad con los comandos de la restauración (spec §6.3). El módulo no puede cantar
     * <i>"entorno restaurado"</i> por haberlos emitido: la red cancela todo lo que empieza por el
     * prefijo <b>incluidos los suyos propios</b> -y eso es deliberado y correcto-, así que "emitido"
     * y "llegado a Baritone" son dos cosas distintas, y la diferencia entre ellas es que Baritone
     * siga volando solo o no.
     */
    public enum Restoration {
        /** Los comandos salieron del cliente: o los interceptó Baritone, o no hizo falta cancelarlos. */
        ENTREGADA,
        /** La red tuvo que cancelar comandos nuestros: no llegaron a ninguna parte. */
        CANCELADA,
        /** No había jugador al que mandárselos: no se emitió ninguno. */
        SIN_JUGADOR;

        /** Si se puede afirmar que el entorno quedó restaurado. */
        public boolean arrived() {
            return this == ENTREGADA;
        }

        /**
         * El aviso que hay que sacarle al jugador, o {@code null} si no hay nada que avisar. Dice las
         * tres cosas que necesita: que Baritone puede seguir volando, que los ajustes se han quedado
         * escritos con los valores de vuelo, y qué escribir a mano para arreglarlo.
         *
         * @param prefix el prefijo con el que se intentó hablarle a Baritone
         */
        public Msg warning(String prefix) {
            return switch (this) {
                case ENTREGADA -> null;
                case CANCELADA -> Msg.of(TravelText.RESTORATION_CANCELLED, "prefix", prefix);
                case SIN_JUGADOR -> Msg.of(TravelText.RESTORATION_NO_PLAYER, "prefix", prefix);
            };
        }
    }
}
