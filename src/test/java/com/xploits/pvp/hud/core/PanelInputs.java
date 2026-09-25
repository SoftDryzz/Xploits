package com.xploits.pvp.hud.core;

import com.xploits.pvp.core.CombatPosture;
import com.xploits.pvp.core.CombatState;

import java.util.List;

/**
 * A small fluent builder over {@link PanelInput} so each test only names the fields it actually varies;
 * everything else keeps a plain, "nothing wrong" default. Test helper only, not pure-core code.
 */
final class PanelInputs {
    private boolean autoPvpOn = true;
    private String profileName = "balanced";
    private boolean profileModified = false;
    private CombatState state = CombatState.SURFACE;
    private CombatPosture posture = CombatPosture.CALM;
    private String target = "Foo";
    private double targetDistance = 8.0;
    private List<String> enabled = List.of();
    private List<String> released = List.of();
    private List<String> profileOff = List.of();
    private List<PanelInput.Idle> idle = List.of();
    private int crystals = 5;
    private int totems = 4;
    private int obsidian = 10;
    private boolean crystalAuraEnabled = false;
    private boolean outOfResources = false;
    private PanelInput.LiveFight fight = null;
    private boolean showFight = true;

    static PanelInputs base() {
        return new PanelInputs();
    }

    PanelInputs autoPvpOn(boolean v) {
        this.autoPvpOn = v;
        return this;
    }

    PanelInputs profileName(String v) {
        this.profileName = v;
        return this;
    }

    PanelInputs profileModified(boolean v) {
        this.profileModified = v;
        return this;
    }

    PanelInputs state(CombatState v) {
        this.state = v;
        return this;
    }

    PanelInputs posture(CombatPosture v) {
        this.posture = v;
        return this;
    }

    PanelInputs target(String v) {
        this.target = v;
        return this;
    }

    PanelInputs targetDistance(double v) {
        this.targetDistance = v;
        return this;
    }

    PanelInputs enabled(String... v) {
        this.enabled = List.of(v);
        return this;
    }

    PanelInputs released(String... v) {
        this.released = List.of(v);
        return this;
    }

    PanelInputs profileOff(String... v) {
        this.profileOff = List.of(v);
        return this;
    }

    PanelInputs idle(PanelInput.Idle... v) {
        this.idle = List.of(v);
        return this;
    }

    PanelInputs crystals(int v) {
        this.crystals = v;
        return this;
    }

    PanelInputs totems(int v) {
        this.totems = v;
        return this;
    }

    PanelInputs obsidian(int v) {
        this.obsidian = v;
        return this;
    }

    PanelInputs crystalAuraEnabled(boolean v) {
        this.crystalAuraEnabled = v;
        return this;
    }

    PanelInputs outOfResources(boolean v) {
        this.outOfResources = v;
        return this;
    }

    PanelInputs fight(PanelInput.LiveFight v) {
        this.fight = v;
        return this;
    }

    PanelInputs showFight(boolean v) {
        this.showFight = v;
        return this;
    }

    PanelInput build() {
        return new PanelInput(autoPvpOn, profileName, profileModified, state, posture, target, targetDistance,
            enabled, released, profileOff, idle, crystals, totems, obsidian, crystalAuraEnabled,
            outOfResources, fight, showFight);
    }
}
