package com.xploits.printer.core;

import java.util.List;
import java.util.Optional;

/**
 * The printer's guards (spec §5.8) and the combat yield (§5.3, N-I2) as one pure decision. Every pause and stop names its
 * reason; the adapter turns it into a message that says what to do and which setting controls it. A pause ends by itself
 * when its cause is gone; a stop never restarts by itself.
 *
 * <p>Order of the running checks, the first that holds is the answer: died, dimension changed, auto-pvp engaged, attacked
 * by a player, low health, a player near (with the setting on), setback, the source's own stop, a conflicting module, a
 * placement not sent, break speed mismatch; then the yield: any acting tick yields (no click), {@code N_YIELD} acting
 * ticks pause, {@code N_CLEAR} clear ticks end the pause, the third pause stops. The reasons name a combat module
 * ({@code COMBAT}, {@code COMBAT_REPEATED}) when one is active, otherwise {@code OTHER_ROTATION} and
 * {@code OTHER_ROTATION_REPEATED} with no module; last come the lag pause and the eating pause. The enable-time refusals
 * run in the order {@link #refuse} documents. Once a {@link Stop} is returned every later tick returns it again: a stop
 * never restarts by itself.
 */
public final class Guards {
    public enum Effect { REFUSE, STOP, PAUSE, END }

    public enum Reason {
        NO_WORLD(Effect.REFUSE), DEAD(Effect.REFUSE), CAMERA_NOT_PLAYER(Effect.REFUSE), RIDING(Effect.REFUSE),
        METEOR_API(Effect.REFUSE), NO_LITEMATICA(Effect.REFUSE), LITEMATICA_API(Effect.REFUSE),
        NO_PLACEMENT(Effect.REFUSE), PLACEMENT_DISABLED(Effect.REFUSE), NO_ENABLED_REGION(Effect.REFUSE),
        PLACEMENT_TOO_LARGE(Effect.REFUSE), PLACEMENT_OVERLAP(Effect.REFUSE), TRAVEL_RUNNING(Effect.REFUSE),
        SWEEP_RUNNING(Effect.REFUSE), PREFIX_INVALID(Effect.REFUSE), BARITONE_SETTINGS_UNREADABLE(Effect.REFUSE),
        BARITONE_SAVE_FAILED(Effect.REFUSE), LITEMATICA_PRINTER_UNREADABLE(Effect.REFUSE),
        EASY_PLACE_RESTRICTION(Effect.STOP), LITEMATICA_PRINTER_ON(Effect.STOP), CONFLICTING_MODULE(Effect.STOP),
        PLAYER_NEAR(Effect.STOP), LOW_HEALTH(Effect.STOP), AUTO_PVP_ENGAGED(Effect.STOP), LEFT(Effect.STOP),
        DIED(Effect.STOP), DIMENSION(Effect.STOP), ATTACKED(Effect.STOP), SETBACK(Effect.STOP),
        PLACEMENT_CHANGED(Effect.STOP), LAYER_RANGE_CHANGED(Effect.STOP), OTHER_PLACEMENT_OVERLAPS(Effect.STOP),
        PLACEMENT_NOT_SENT(Effect.STOP), COMBAT_REPEATED(Effect.STOP), OTHER_ROTATION_REPEATED(Effect.STOP),
        BREAK_SPEED_MISMATCH(Effect.STOP),
        LOOP(Effect.STOP), NO_HOTBAR_ROOM(Effect.STOP), CURSOR_NOT_EMPTY(Effect.STOP),
        BARITONE_NOT_LISTENING(Effect.STOP), MODULE_OFF(Effect.STOP),
        FINISHED(Effect.END), LEFTOVERS(Effect.END), MATERIAL_MISSING(Effect.END), NOTHING_REACHABLE(Effect.END),
        NOTHING_KNOWN(Effect.END),
        LAG(Effect.PAUSE), EATING(Effect.PAUSE), COMBAT(Effect.PAUSE), OTHER_ROTATION(Effect.PAUSE);

        private final Effect effect;

        Reason(Effect effect) {
            this.effect = effect;
        }

        public Effect effect() {
            return effect;
        }
    }

    /** Why the printer will not start; {@code detail} names modules when there are some, otherwise it is empty. */
    public record Refusal(Reason reason, String detail) {
    }

    public sealed interface Verdict permits Run, Pause, Stop {
    }

    /** Carry on; {@code yielding}: a foreign rotation or action happens this tick (a combat module or any other), so no click (§5.3). */
    public record Run(boolean yielding) implements Verdict {
    }

    public record Pause(Reason reason, String detail) implements Verdict {
    }

    public record Stop(Reason reason, String detail) implements Verdict {
    }

    /**
     * One tick's guard facts.
     *
     * @param acting       Meteor's rotation queue was not empty at the printer's request point, or someone else's action
     *                     packet left in the last tick (§5.3 "acting")
     * @param activeCombat names of the {@link #COMBAT_MODULES} that are on, to name in the pause
     * @param health       health plus absorption
     * @param sourceStop   the target source's own stop reason this tick, or null
     */
    public record Inputs(double secondsSinceServerTick, boolean eatingOrUsing, boolean acting, List<String> activeCombat,
                         boolean autoPvpEngaged, boolean playerNear, boolean stopNearPlayers, boolean attackedByPlayer,
                         double health, double minHealth, boolean setback, Reason sourceStop, List<String> conflicting,
                         boolean placementNotSent, boolean died, boolean dimensionChanged, boolean breakSpeedMismatch) {
    }

    /** What is checked before the printer starts; {@code sourceRefusal} null when the source is ready. */
    public record EnableInputs(boolean world, boolean alive, boolean cameraIsPlayer, boolean riding,
                               boolean meteorQueueReadable, Refusal sourceRefusal, boolean travelRunning,
                               boolean sweepRunning, List<String> conflicting, boolean autoPvpEngaged, boolean playerNear,
                               boolean stopNearPlayers, double health, double minHealth, boolean baritoneInstalled,
                               boolean prefixValid) {
    }

    /** Modules whose rotation or action packets count as "acting" (spike S6's table; names from Meteor's sources). */
    public static final List<String> COMBAT_MODULES = List.of("crystal-aura++", "surround++", "crystal-aura",
        "surround", "auto-trap", "anchor-aura", "anti-bed", "anti-anvil", "anti-anchor", "hole-filler", "auto-web",
        "auto-anvil", "auto-city", "kill-aura", "bow-aimbot", "bed-aura", "self-trap", "burrow");

    /** Modules the printer refuses to run beside (§5.4, §5.8 M14/N-M12, and those that place or break by themselves). */
    public static final List<String> CONFLICTING_MODULES = List.of("instant-rebreak", "speed-mine", "packet-mine",
        "auto-tool", "anti-afk", "auto-walk", "auto-replenish", "inventory-tweaks", "scaffold", "air-place",
        "no-ghost-blocks", "nuker", "vein-miner", "highway-builder", "liquid-filler", "excavator", "infinity-miner",
        "echest-farmer", "spawn-proofer", "timer");

    private final PrinterLimits limits;
    private int actingStreak;
    private int clearStreak;
    private int pauses;
    private boolean combatPaused;
    private Reason pauseReason;
    private String pauseDetail = "";
    private Stop latched;

    public Guards(PrinterLimits limits) {
        this.limits = limits;
    }

    public Verdict tick(Inputs in) {
        if (latched != null) return latched;
        Verdict verdict = decide(in);
        if (verdict instanceof Stop stop) latched = stop;
        return verdict;
    }

    private Verdict decide(Inputs in) {
        if (in.died()) return new Stop(Reason.DIED, "");
        if (in.dimensionChanged()) return new Stop(Reason.DIMENSION, "");
        if (in.autoPvpEngaged()) return new Stop(Reason.AUTO_PVP_ENGAGED, "");
        if (in.attackedByPlayer()) return new Stop(Reason.ATTACKED, "");
        if (!(in.health() >= in.minHealth())) return new Stop(Reason.LOW_HEALTH, "");
        if (in.stopNearPlayers() && in.playerNear()) return new Stop(Reason.PLAYER_NEAR, "");
        if (in.setback()) return new Stop(Reason.SETBACK, "");
        if (in.sourceStop() != null) return new Stop(in.sourceStop(), "");
        if (!in.conflicting().isEmpty()) return new Stop(Reason.CONFLICTING_MODULE, String.join(", ", in.conflicting()));
        if (in.placementNotSent()) return new Stop(Reason.PLACEMENT_NOT_SENT, "");
        if (in.breakSpeedMismatch()) return new Stop(Reason.BREAK_SPEED_MISMATCH, "");
        String names = String.join(", ", in.activeCombat());
        boolean named = !in.activeCombat().isEmpty();
        if (combatPaused) {
            clearStreak = in.acting() ? 0 : clearStreak + 1;
            if (clearStreak < limits.clearTicks()) return new Pause(pauseReason, pauseDetail);
            combatPaused = false;
            clearStreak = 0;
            actingStreak = 0;
        }
        if (in.acting()) {
            actingStreak++;
            if (actingStreak >= limits.yieldTicks()) {
                actingStreak = 0;
                pauses++;
                if (pauses >= limits.pausesBeforeStop()) {
                    return new Stop(named ? Reason.COMBAT_REPEATED : Reason.OTHER_ROTATION_REPEATED, names);
                }
                combatPaused = true;
                clearStreak = 0;
                pauseReason = named ? Reason.COMBAT : Reason.OTHER_ROTATION;
                pauseDetail = names;
                return new Pause(pauseReason, pauseDetail);
            }
        } else {
            actingStreak = 0;
        }
        if (in.secondsSinceServerTick() >= limits.lagSeconds()) return new Pause(Reason.LAG, "");
        if (in.eatingOrUsing()) return new Pause(Reason.EATING, "");
        return new Run(in.acting());
    }

    /** Combat and other-rotation pauses so far in this session. */
    public int combatPauses() {
        return pauses;
    }

    /**
     * The first reason the printer will not start, or empty. Order: no world, dead, camera not the player, riding, Meteor's
     * queue unreadable, the source's refusal, auto-travel running, nether-sweep running, a conflicting module, auto-pvp
     * engaged (whatever {@code stop-near-players} says), a near player (with the setting on), low health (NaN counts as
     * low), an unusable Baritone prefix (only with Baritone installed).
     */
    public static Optional<Refusal> refuse(EnableInputs in) {
        if (!in.world()) return refusal(Reason.NO_WORLD);
        if (!in.alive()) return refusal(Reason.DEAD);
        if (!in.cameraIsPlayer()) return refusal(Reason.CAMERA_NOT_PLAYER);
        if (in.riding()) return refusal(Reason.RIDING);
        if (!in.meteorQueueReadable()) return refusal(Reason.METEOR_API);
        if (in.sourceRefusal() != null) return Optional.of(in.sourceRefusal());
        if (in.travelRunning()) return refusal(Reason.TRAVEL_RUNNING);
        if (in.sweepRunning()) return refusal(Reason.SWEEP_RUNNING);
        if (!in.conflicting().isEmpty()) {
            return Optional.of(new Refusal(Reason.CONFLICTING_MODULE, String.join(", ", in.conflicting())));
        }
        if (in.autoPvpEngaged()) return refusal(Reason.AUTO_PVP_ENGAGED);
        if (in.stopNearPlayers() && in.playerNear()) return refusal(Reason.PLAYER_NEAR);
        if (!(in.health() >= in.minHealth())) return refusal(Reason.LOW_HEALTH);
        if (in.baritoneInstalled() && !in.prefixValid()) return refusal(Reason.PREFIX_INVALID);
        return Optional.empty();
    }

    private static Optional<Refusal> refusal(Reason reason) {
        return Optional.of(new Refusal(reason, ""));
    }
}
