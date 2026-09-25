package com.xploits.pvp.hud.core;

import com.xploits.pvp.core.CombatPosture;
import com.xploits.pvp.core.CombatState;

import java.util.List;
import java.util.Objects;

/**
 * Everything {@link PanelModel} needs to draw one frame of the {@code xploits-pvp} HUD panel, in plain
 * values only (spec §3): no {@code ActionWatch.Idle}, no {@code Plan}, no adapter type of any kind, so
 * this stays a pure core that {@code BoundaryTest} can check and that a test can build by hand without
 * touching the game. The adapter (Task 6/7) is the one that turns {@code AutoPvp.panelState()}, the
 * {@code ProfileBook} and {@code FightRecorder.live()} into one of these every frame.
 *
 * @param autoPvpOn          whether the module is on; when {@code false} {@link PanelModel#lines} draws
 *                           only the single "off" line and every other field below is ignored
 * @param profileName        the active profile's id, always known (it persists whether or not
 *                           auto-pvp is on)
 * @param profileModified    whether the live settings no longer match the active profile
 *                           ({@code ProfileBook.modified}); shown as a trailing {@code *}
 * @param state               the phase auto-pvp reports this tick
 * @param posture             the defensive posture this tick, independent of the phase
 * @param target              the target's name, or {@code null} when there is none
 * @param targetDistance     blocks to the target; meaningless when {@code target} is {@code null}
 * @param enabled            names of the managed modules currently on, in the order they should read
 * @param released           names of managed modules you turned off by hand and that stay yours this
 *                           phase ({@code ModuleLedger.released()})
 * @param profileOff         names of managed modules the active profile does not allow
 *                           ({@code Skipped} entries whose reason is {@code PROFILE_OFF})
 * @param idle                the watched-resource verdicts still standing ({@code ActionWatch.idle()}),
 *                           turned into plain names by the adapter; empty when nothing is idle
 * @param crystals            crystals carried
 * @param totems              totems carried
 * @param obsidian            obsidian carried
 * @param crystalAuraEnabled whether {@code crystal-aura} is one of the enabled modules (its own flag,
 *                           not derived from {@link #enabled}, so the panel does not have to compare
 *                           strings to know it)
 * @param outOfResources     whether this tick's state is the loud {@code OUT_OF_RESOURCES}
 * @param fight               the recorder's live totals for the fight under way, or {@code null} when
 *                           the recorder is off or no fight is open
 * @param showFight           the panel's own {@code show-fight} setting; the fight line is never drawn
 *                           when this is {@code false}, even with a fight open
 */
public record PanelInput(
    boolean autoPvpOn,
    String profileName,
    boolean profileModified,
    CombatState state,
    CombatPosture posture,
    String target,
    double targetDistance,
    List<String> enabled,
    List<String> released,
    List<String> profileOff,
    List<Idle> idle,
    int crystals,
    int totems,
    int obsidian,
    boolean crystalAuraEnabled,
    boolean outOfResources,
    LiveFight fight,
    boolean showFight
) {
    public PanelInput {
        Objects.requireNonNull(profileName, "profileName");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(posture, "posture");
        enabled = List.copyOf(enabled);
        released = List.copyOf(released);
        profileOff = List.copyOf(profileOff);
        idle = List.copyOf(idle);
    }

    /**
     * One watched-resource verdict, in plain names (spec §3): the resource, lowercase
     * ({@code Resource.name().toLowerCase()}, e.g. {@code "obsidian"}), and the names of the modules
     * that were eligible for it, in catalog order. The mirror of {@code ActionWatch.Idle} without the
     * pvp-core type.
     */
    public record Idle(String resource, List<String> modules) {
        public Idle {
            Objects.requireNonNull(resource, "resource");
            modules = List.copyOf(modules);
        }
    }

    /**
     * The recorder's live totals for the fight under way (mirrors {@code FightTracker.LiveFight}
     * without importing the recorder core, so this package only ever depends on {@code pvp.core}).
     */
    public record LiveFight(int seconds, int yourPops, int theirPops, double damageTaken) {
    }
}
