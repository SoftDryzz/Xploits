package com.xploits.pvp.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ownership of the managed modules, in pure logic (spec §7): from what the phase asks for, what is
 * really on right now and what it had taken, it decides what to enable, what to disable and which
 * modules the player has just released. It knows nothing about Meteor or Minecraft: the adapter only
 * passes it names and booleans, and carries out what this returns by calling the real
 * {@code enable()} and {@code disable()}.
 *
 * <p>This decision used to live in the adapter, without tests, and that is why a single-tick bug -the
 * first pass released what the player had just turned off by hand, and the second turned it back on
 * because it was still in what the phase asked for- made it all the way to the final review (spec
 * §12). Here it is pure logic and can be tested without starting the game.
 *
 * <p><b>An observed shutdown is not always a manual release (spec §7).</b> Three of the managed
 * modules can turn themselves off without the player touching them -not all three for the same reason,
 * nor all by default: {@code auto-trap} after placing the trap, by default; {@code auto-city} by default
 * if it finds no target, block or pickaxe, or after mining successfully; and {@code auto-anvil} only if
 * the player enables {@code toggle-on-break}, which is {@code false} by default-, so treating that
 * shutdown as a manual release blocked the whole phase and warned with a false "you turned it off". The
 * distinction is per module ({@link ManagedModule#turnsItselfOff()}), not by time: there is no tick
 * window that works for all three at once, because {@code auto-trap} turns off many ticks after
 * being taken and {@code auto-city} can turn off inside the same {@code onActivate()} that its
 * enabling fires.
 *
 * <p><b>{@code surround} left that list</b> (critical C2). With the flag set, the debounce did not
 * apply to it: the ledger released ownership and took it back on the second pass of the same tick,
 * so turning it off by hand was impossible and the only way out was to turn off the whole {@code auto-pvp}.
 * Its default self-shutdown is {@code toggle-on-y-change}, and that case is now excluded upstream:
 * the posture does not ask for {@code surround} while your Y is changing ({@link DefensivePolicy}), which
 * is exactly the condition under which the module turns itself off.
 *
 * <p><b>And a flicker is not a release either (redesign §8).</b> Giving up the module for the whole
 * phase on a single tick observed "off" turned a double press of a bind into losing it in the middle of
 * combat. For the modules that cannot turn themselves off it must also stay off for
 * {@link #RELEASE_DEBOUNCE_TICKS} consecutive ticks.
 */
public final class ModuleLedger {
    /**
     * Consecutive ticks a taken module must be off, while the phase still asks for it, to conclude
     * that the player wants it for themselves (redesign §8). Four ticks, 0.2 s.
     *
     * <p>The number comes from the two ways of getting it wrong. From below: a double press of a bind
     * -or a repeating bind, or opening and closing the ClickGUI on the same module- leaves the module
     * off only for the ticks you take to press again, around two or three at human speed
     * (100-150 ms); four ticks cover them, and since the player turns it back on themselves, the
     * ledger does not even have to take it back. From above: a real release takes those same 0.2 s
     * to be recognised, less than the shortest crystal cycle of §9, so it never comes across as the
     * director fighting the player over a bind.
     *
     * <p>During the wait the module is <b>neither taken back nor considered released</b>: taking it back
     * would be exactly the fight to avoid -the player turns it off, the director turns it on, and the
     * count of ticks off would never go up-.
     */
    public static final int RELEASE_DEBOUNCE_TICKS = 4;

    /** The modules this ledger has taken right now. */
    private final Set<String> owned = new LinkedHashSet<>();

    /**
     * Consecutive ticks each taken module has been observed "off" while the phase still asks for it
     * (redesign §8). It only has an entry during the debounce wait.
     */
    private final Map<String, Integer> offTicks = new HashMap<>();

    /**
     * Modules the player released by hand while the situation still asked for them. They are not
     * taken again until the situation changes or {@link #reset()} is called (spec §7): it is the memory
     * that was missing for turning something off by hand to really work.
     *
     * <p><b>"The situation" is not the same for both axes</b> (important I6). This memory was
     * indexed entirely by the <b>offensive phase</b>, even for the defensive modules, which do not
     * depend on it: you turned {@code hole-filler} off by hand because it was spending your obsidian, the
     * enemy moved one block away, the offensive phase changed -without anything of yours having changed- and
     * the ledger forgot you had released it and turned it back on. It is the same mistake as I4
     * through the other door: a structure written for one axis applied to both.
     *
     * <p>Now each half forgets with its own: the offensive one when the phase changes, the defensive one
     * when the posture changes, which is the "phase" of the defensive axis (§3).
     */
    private final Set<String> released = new LinkedHashSet<>();

    private CombatState lastPhase;
    private CombatPosture lastPosture;

    /**
     * Whether an observed shutdown of {@code name} counts as a "manual release" (spec §7). It looks in
     * {@link ManagedModules#ALL}, the catalog of managed modules, which lives in this same
     * package: no adapter is needed to consult it, it is still pure logic. A name
     * that is not in the catalog (for example one of the "usual" ones) never gets here as
     * "owned", so the default value (false) does not matter in practice.
     */
    private static boolean turnsItselfOff(String name) {
        for (ManagedModule module : ManagedModules.ALL) {
            if (module.name().equals(name)) return module.turnsItselfOff();
        }
        return false;
    }

    /**
     * What has to be done this tick: what to really enable, what to really disable, and which
     * modules it has just learnt the player released by hand (to warn once, not on every
     * tick they stay untaken).
     */
    public record Result(List<String> toEnable, List<String> toDisable, List<String> newlyReleased) {
        public Result {
            toEnable = List.copyOf(toEnable);
            toDisable = List.copyOf(toDisable);
            newlyReleased = List.copyOf(newlyReleased);
        }
    }

    /**
     * Decides this tick's ownership. It enables or disables nothing on its own: the adapter carries out
     * the {@link Result} it returns.
     *
     * @param phase   the current physical phase (not the one the plan reports); a phase change forgets
     *                what the player released by hand <b>on the offensive axis</b> (spec §7, important I6)
     * @param posture the current defensive posture; a posture change forgets what the player
     *                released by hand <b>on the defensive axis</b>, which is what did not depend on the phase
     * @param wanted  the module names the plan of this tick wants on
     * @param active  the module names that are really on right now, whoever
     *                took them
     */
    public Result apply(CombatState phase, CombatPosture posture, Set<String> wanted, Set<String> active) {
        if (phase != lastPhase) {
            forget(false);
            lastPhase = phase;
        }
        if (posture != lastPosture) {
            forget(true);
            lastPosture = posture;
        }

        List<String> toDisable = new ArrayList<>();
        List<String> newlyReleased = new ArrayList<>();

        // First pass: reconcile what we believed taken with what is really on.
        for (String name : new ArrayList<>(owned)) {
            if (!active.contains(name)) {
                // It is no longer on. Three of the offensive modules can turn themselves off without
                // the player touching them (spec §7) -auto-trap on placing the trap, by default;
                // auto-city by default if it finds no target/block/pickaxe or after mining successfully;
                // auto-anvil only if the player enables toggle-on-break; surround is no longer on the
                // list (C2)-, and that shutdown is not the player releasing it by hand: it stops
                // being ours and the director can take it again on the second pass of this
                // same tick. The same if the phase no longer asks for it: there is nothing to release.
                if (turnsItselfOff(name) || !wanted.contains(name)) {
                    owned.remove(name);
                    offTicks.remove(name);
                    continue;
                }

                // For those that CANNOT turn themselves off, an observed shutdown points at the player,
                // but a single tick is not enough (redesign §8): a double press of a bind left
                // you without the module in the middle of combat. While the debounce lasts it is still
                // ours and is not taken back -taking it back would be fighting the bind-.
                int ticksOff = offTicks.merge(name, 1, Integer::sum);
                if (ticksOff < RELEASE_DEBOUNCE_TICKS) continue;

                owned.remove(name);
                offTicks.remove(name);
                released.add(name);
                newlyReleased.add(name);
                continue;
            }
            // It is on again before the debounce ran out: it was a flicker.
            offTicks.remove(name);
            if (!wanted.contains(name)) {
                toDisable.add(name);
                owned.remove(name);
            }
        }

        // Second pass: take what is missing. Never what is already on -whether ours or the
        // player's-, and never what the player has just released in this same phase.
        List<String> toEnable = new ArrayList<>();
        for (String name : wanted) {
            if (active.contains(name)) continue;
            if (released.contains(name)) continue;
            // In the middle of the debounce it is not touched: if the player turned it off, turning it
            // back on would be fighting them and would also keep the count from ever reaching RELEASE_DEBOUNCE_TICKS.
            if (offTicks.containsKey(name)) continue;
            toEnable.add(name);
            owned.add(name);
        }

        return new Result(toEnable, toDisable, newlyReleased);
    }

    /** What the ledger has taken right now. */
    public Set<String> owned() {
        return Set.copyOf(owned);
    }

    /**
     * Forgets what was released by hand on <b>a single axis</b> and its half-done debounce (important I6).
     * Each axis changes situation on its own: the offensive one with the phase, the defensive one with
     * the posture, and the other one's is left alone.
     */
    private void forget(boolean defensive) {
        released.removeIf(name -> ManagedModules.isDefensive(name) == defensive);
        offTicks.keySet().removeIf(name -> ManagedModules.isDefensive(name) == defensive);
    }

    /** Forgets what was taken and what was released by hand. Called when the whole module is turned on or off. */
    public void reset() {
        owned.clear();
        released.clear();
        offTicks.clear();
        lastPhase = null;
        lastPosture = null;
    }
}
