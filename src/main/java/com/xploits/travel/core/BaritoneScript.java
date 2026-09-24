package com.xploits.travel.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The chat command sequences that talk to Baritone (AutoTravel spec §8). Baritone is spoken to
 * through prefixed chat commands -its own console, not Meteor's-, so this module depends on no
 * Baritone API: it only builds strings.
 */
public final class BaritoneScript {
    private BaritoneScript() {
    }

    /**
     * The player's flight settings, as they ask for them or as they had them before starting.
     *
     * @param netherSeed the Nether seed used to predict the terrain; empty if it is not known -in
     *                   that case no command is written for it (AutoTravel spec)
     */
    public record FlightSettings(boolean autoJump, boolean allowEmergencyLand, boolean conserveFireworks,
                                  double fireworkSpeed, String netherSeed) {
    }

    /**
     * <b>Baritone's out-of-the-box</b> values for the four settings this module touches. It is the
     * default resting state of {@link #restoration(String, FlightSettings)}: whoever installs the
     * addon and touches nothing must land with Baritone exactly as it was before installing it.
     *
     * <p>Read from the bytecode of the installed jar -{@code baritone-standalone-fabric-1.17.0.jar},
     * the {@code Settings} class obfuscated as {@code baritone/e.class}, with {@code javap -p -c}-, in
     * the constructor where each setting is built with its initial value:
     *
     * <ul>
     *   <li>{@code elytraAutoJump}: {@code Boolean.FALSE}</li>
     *   <li>{@code elytraAllowEmergencyLand}: {@code Boolean.TRUE}</li>
     *   <li>{@code elytraConserveFireworks}: {@code Boolean.FALSE}</li>
     *   <li>{@code elytraFireworkSpeed}: {@code double 1.2d}</li>
     * </ul>
     *
     * <p><b>Why this matters enough to have its own place.</b> Baritone <b>persists its settings to
     * disk</b>. A made-up resting value does not stay in the trip: it reconfigures forever every
     * {@code #elytra} the player runs by hand afterwards, with no way for them to link it to the
     * addon. That contradicts spec §1's "leaves everything as it was".
     *
     * <p>The seed is empty because it is not a setting that gets restored (see {@link
     * #restoration(String, FlightSettings)}): it was never a change of ours.
     */
    public static FlightSettings baritoneDefaults() {
        return new FlightSettings(false, true, false, 1.2, "");
    }

    /**
     * The commands that prepare the flight: the player's four settings, plus the three that our own
     * handling of the flight requires (spec §8.1) -{@code elytraAutoSwap false} because the elytra
     * swap is done by {@code elytra-replace}, not Baritone; {@code elytraTermsAccepted true} to
     * silence its notice; and {@code elytraPredictTerrain false}-. The seed is only written if it is
     * not empty.
     *
     * <p>Before all of them, {@code censorCoordinates true} and {@code censorRanCommands true}: Baritone
     * echoes goals and commands to chat, and Minecraft copies chat to latest.log. They are never
     * turned back off (see {@link #restoration}).
     */
    public static List<String> preparation(String prefix, FlightSettings settings) {
        requirePrefix(prefix);
        List<String> commands = new ArrayList<>();
        // First, before any goal: Baritone echoes goals and commands to chat, and Minecraft copies
        // chat to latest.log. Censored, they never carry the destination.
        commands.add(set(prefix, "censorCoordinates", "true"));
        commands.add(set(prefix, "censorRanCommands", "true"));
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
     * The commands that put the flight back to rest: {@code elytraAutoSwap true} -undoes Baritone's
     * own swap- and the player's four settings at their resting values. The seed is not restored: it
     * was never a change of ours, only a piece of data we handed to Baritone if we had it.
     *
     * <p>Two things that look like omissions and are not:
     *
     * <ul>
     *   <li>{@code elytraAutoSwap} is always restored to {@code true}, fixed, without reading it
     *       from {@code resting}: the change to {@code false} was ours, not the player's, so there is
     *       no "resting value of theirs" to look up -it was always {@code true} before {@link
     *       #preparation} touched it.</li>
     *   <li>{@code elytraPredictTerrain} is not restored here because {@link #preparation} does not
     *       treat it as a player setting either: it turns it off on our own account (spec §8.1) and
     *       it is not part of {@link FlightSettings}, so this method has no value to give back to
     *       it.</li>
     *   <li>{@code censorCoordinates} and {@code censorRanCommands} stay on: Baritone saves {@code #set}
     *       to disk, so turning them off would undo a censor the player already had, and leaving them
     *       on only hides coordinates.</li>
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

    /** The command that sets the next goal, with the coordinates rounded to the block. */
    public static String goTo(String prefix, Waypoint point) {
        requirePrefix(prefix);
        return prefix + "goal " + Math.round(point.x()) + " " + Math.round(point.z());
    }

    /** The command that launches the flight towards the goal already set. */
    public static String launch(String prefix) {
        requirePrefix(prefix);
        return prefix + "elytra";
    }

    /** The command that cuts whatever Baritone was doing. */
    public static String cancel(String prefix) {
        requirePrefix(prefix);
        return prefix + "cancel";
    }

    /**
     * The core is the only funnel all commands go through before reaching the chat, so the prefix
     * check goes here: an empty prefix would turn every command into plain chat -{@code "set
     * elytraAutoJump true"} instead of {@code "#set elytraAutoJump true"}-, which would be published
     * on the server as it is, and the safety net that cancels packets with Baritone's prefix would not
     * recognise it as its own and would let the whole chat through.
     */
    private static void requirePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            throw new IllegalArgumentException(
                "the Baritone prefix cannot be empty: the commands would go out to the server as plain chat");
        }
    }

    private static String set(String prefix, String name, String value) {
        return prefix + "set " + name + " " + value;
    }

    private static String bool(boolean value) {
        return Boolean.toString(value);
    }

    /** No decimals when the value is whole, so that {@code elytraFireworkSpeed 1} does not come out as {@code 1.0}. */
    private static String number(double value) {
        if (!Double.isInfinite(value) && !Double.isNaN(value) && value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }
}
