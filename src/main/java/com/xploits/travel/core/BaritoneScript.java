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
     * Los comandos que preparan el vuelo: los cuatro ajustes del jugador, más los tres que exige
     * nuestro propio manejo del vuelo (spec §8.1) -{@code elytraAutoSwap false} porque el cambio de
     * élitro lo hace {@code elytra-replace}, no Baritone; {@code elytraTermsAccepted true} para
     * silenciar su aviso; y {@code elytraPredictTerrain false}-. La semilla solo se escribe si no
     * está vacía.
     */
    public static List<String> preparation(String prefix, FlightSettings settings) {
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
     */
    public static List<String> restoration(String prefix, FlightSettings resting) {
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
        return prefix + "goal " + Math.round(point.x()) + " " + Math.round(point.z());
    }

    /** El comando que lanza el vuelo hacia el objetivo ya fijado. */
    public static String launch(String prefix) {
        return prefix + "elytra";
    }

    /** El comando que corta cualquier cosa que Baritone estuviera haciendo. */
    public static String cancel(String prefix) {
        return prefix + "cancel";
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
