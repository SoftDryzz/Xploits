package com.xploits.travel.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Las secuencias de comandos de chat que hablan con Baritone (spec AutoTravel §8). A Baritone se
 * le habla por comandos de chat con prefijo -su propia consola, no la de Meteor-, así que este
 * módulo no depende de ninguna API de Baritone: solo construye cadenas.
 */
public final class BaritoneScript {
    private BaritoneScript() {
    }

    /**
     * Los ajustes de vuelo del jugador, tal y como los pide o los tenía antes de empezar.
     *
     * @param netherSeed la semilla del Nether para predecir el terreno; vacía si no se conoce -en
     *                   ese caso no se escribe ningún comando para ella (spec AutoTravel)
     */
    public record FlightSettings(boolean autoJump, boolean allowEmergencyLand, boolean conserveFireworks,
                                  double fireworkSpeed, String netherSeed) {
    }

    /**
     * Los valores <b>de fábrica de Baritone</b> para los cuatro ajustes que este módulo toca. Es el
     * reposo por defecto de {@link #restoration(String, FlightSettings)}: quien instala el addon y no
     * toca nada tiene que aterrizar con Baritone exactamente como estaba antes de instalarlo.
     *
     * <p>Leídos del bytecode del jar instalado -{@code baritone-standalone-fabric-1.17.0.jar}, clase
     * {@code Settings} ofuscada como {@code baritone/e.class}, con {@code javap -p -c}-, en el
     * constructor donde cada ajuste se construye con su valor inicial:
     *
     * <ul>
     *   <li>{@code elytraAutoJump}: {@code Boolean.FALSE}</li>
     *   <li>{@code elytraAllowEmergencyLand}: {@code Boolean.TRUE}</li>
     *   <li>{@code elytraConserveFireworks}: {@code Boolean.FALSE}</li>
     *   <li>{@code elytraFireworkSpeed}: {@code double 1.2d}</li>
     * </ul>
     *
     * <p><b>Por qué esto importa tanto como para tener su propio sitio.</b> Baritone <b>persiste sus
     * ajustes a disco</b>. Un valor de reposo inventado no se queda en el viaje: reconfigura para
     * siempre todos los {@code #elytra} que el jugador haga a mano después, sin que tenga forma de
     * relacionarlo con el addon. Eso contradice el "lo deja todo como estaba" de spec §1.
     *
     * <p>La semilla va vacía porque no es un ajuste que se restaure (ver {@link
     * #restoration(String, FlightSettings)}): nunca fue un cambio nuestro.
     */
    public static FlightSettings baritoneDefaults() {
        return new FlightSettings(false, true, false, 1.2, "");
    }

    /**
     * Los comandos que preparan el vuelo: los cuatro ajustes del jugador, más los tres que exige
     * nuestro propio manejo del vuelo (spec §8.1) -{@code elytraAutoSwap false} porque el cambio de
     * élitro lo hace {@code elytra-replace}, no Baritone; {@code elytraTermsAccepted true} para
     * silenciar su aviso; y {@code elytraPredictTerrain false}-. La semilla solo se escribe si no
     * está vacía.
     */
    public static List<String> preparation(String prefix, FlightSettings settings) {
        requirePrefix(prefix);
        List<String> commands = new ArrayList<>();
        commands.add(set(prefix, "elytraAutoSwap", "false"));
        commands.add(set(prefix, "elytraTermsAccepted", "true"));
        commands.add(set(prefix, "elytraPredictTerrain", "false"));
        commands.add(set(prefix, "elytraAutoJump", bool(settings.autoJump())));
        commands.add(set(prefix, "elytraAllowEmergencyLand", bool(settings.allowEmergencyLand())));
        commands.add(set(prefix, "elytraConserveFireworks", bool(settings.conserveFireworks())));
        commands.add(set(prefix, "elytraFireworkSpeed", number(settings.fireworkSpeed())));
        if (!settings.netherSeed().isEmpty()) {
            commands.add(set(prefix, "elytraNetherSeed", settings.netherSeed()));
        }
        return List.copyOf(commands);
    }

    /**
     * Los comandos que devuelven el vuelo al reposo: {@code elytraAutoSwap true} -deshace el cambio
     * propio de Baritone- y los cuatro ajustes del jugador a sus valores de reposo. La semilla no se
     * restaura: nunca fue un cambio nuestro, solo un dato que le pasamos a Baritone si lo teníamos.
     *
     * <p>Dos cosas que parecen olvidos y no lo son:
     *
     * <ul>
     *   <li>{@code elytraAutoSwap} se restaura siempre a {@code true}, fijo, sin leerlo de {@code
     *       resting}: el cambio a {@code false} fue nuestro, no del jugador, así que no hay un
     *       "valor de reposo suyo" que consultar -siempre fue {@code true} antes de que {@link
     *       #preparation} lo tocara.</li>
     *   <li>{@code elytraPredictTerrain} no se restaura aquí porque {@link #preparation} tampoco lo
     *       trata como un ajuste del jugador: lo apaga por nuestra cuenta (spec §8.1) y no forma
     *       parte de {@link FlightSettings}, así que este método no tiene ningún valor que
     *       devolverle.</li>
     * </ul>
     */
    public static List<String> restoration(String prefix, FlightSettings resting) {
        requirePrefix(prefix);
        List<String> commands = new ArrayList<>();
        commands.add(set(prefix, "elytraAutoSwap", "true"));
        commands.add(set(prefix, "elytraAutoJump", bool(resting.autoJump())));
        commands.add(set(prefix, "elytraAllowEmergencyLand", bool(resting.allowEmergencyLand())));
        commands.add(set(prefix, "elytraConserveFireworks", bool(resting.conserveFireworks())));
        commands.add(set(prefix, "elytraFireworkSpeed", number(resting.fireworkSpeed())));
        return List.copyOf(commands);
    }

    /** El comando que fija el siguiente objetivo, con las coordenadas redondeadas al bloque. */
    public static String goTo(String prefix, Waypoint point) {
        requirePrefix(prefix);
        return prefix + "goal " + Math.round(point.x()) + " " + Math.round(point.z());
    }

    /** El comando que lanza el vuelo hacia el objetivo ya fijado. */
    public static String launch(String prefix) {
        requirePrefix(prefix);
        return prefix + "elytra";
    }

    /** El comando que corta cualquier cosa que Baritone estuviera haciendo. */
    public static String cancel(String prefix) {
        requirePrefix(prefix);
        return prefix + "cancel";
    }

    /**
     * El núcleo es el único embudo por el que pasan todos los comandos antes de llegar al chat, así
     * que la validación del prefijo va aquí: un prefijo vacío convertiría cada comando en chat
     * plano -{@code "set elytraAutoJump true"} en vez de {@code "#set elytraAutoJump true"}-, que se
     * publicaría en el servidor tal cual, y la red de seguridad que cancela los paquetes con el
     * prefijo de Baritone no lo reconocería como suyo y dejaría pasar el chat entero.
     */
    private static void requirePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            throw new IllegalArgumentException(
                "el prefijo de Baritone no puede estar vacío: los comandos saldrían como chat plano al servidor"); // i18n: allowed (exception message, continuation line)
        }
    }

    private static String set(String prefix, String name, String value) {
        return prefix + "set " + name + " " + value;
    }

    private static String bool(boolean value) {
        return Boolean.toString(value);
    }

    /** Sin decimales cuando el valor es entero, para que {@code elytraFireworkSpeed 1} no salga {@code 1.0}. */
    private static String number(double value) {
        if (!Double.isInfinite(value) && !Double.isNaN(value) && value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }
}
