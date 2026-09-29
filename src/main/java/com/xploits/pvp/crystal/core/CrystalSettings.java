package com.xploits.pvp.crystal.core;

import java.util.Objects;

/**
 * The settings {@link CrystalBrain} decides with (spec §2), with Meteor's names and defaults. Ranges
 * (place, break and their walls ranges) are not here: the adapter measures them into
 * {@link Candidate#inRange} and {@link CrystalSeen#inBreakRange}. Neither are {@code pause-modules}
 * ({@link CrystalTick#pauseModuleActive}) or {@code swing-mode} (how to swing is the adapter's; which hand
 * is {@link Action#hand}).
 *
 * <p>Meteor's settings that ++ fixes at their defaults are not settings here either: break, place and
 * switch delays 0 (so Meteor's break and place timers never run), {@code only-own} off,
 * {@code ticks-existed} 0, {@code support} off, {@code 1.12-placement} off, {@code yaw-steps} 180 (every
 * rotation is done in one step), {@code smart-delay} off, {@code predict-movement} off,
 * {@code face-place-missing-armor} off, {@code force-face-place} none, {@code ignore-nakeds} off.
 */
public record CrystalSettings(double targetRange, double minDamage, double maxDamage, boolean antiSuicide,
                              boolean rotate, AutoSwitch autoSwitch, boolean noGapSwitch, boolean noBowSwitch,
                              boolean antiWeakness, boolean place, boolean facePlace, double facePlaceHealth,
                              double facePlaceDurability, boolean breakCrystals, int breakAttempts,
                              int attackFrequency, boolean fastBreak, PauseMode pauseOnUse, PauseMode pauseOnMine,
                              boolean pauseOnLag, double pauseHealth, RiskLevel risk, boolean selfBudget,
                              boolean finishingBlow, double reserve, double safeSelfDamage) {
    /**
     * Meteor's {@code auto-switch}, without Silent (Q6). The display names are Meteor's, which it saves and a
     * player types: never change them.
     */
    public enum AutoSwitch {
        NONE("None"),
        NORMAL("Normal");

        private final String display;

        AutoSwitch(String display) {
            this.display = display;
        }

        @Override
        public String toString() {
            return display;
        }
    }

    /**
     * Meteor's {@code PauseMode}: which process a pause applies to (lines 1371-1380). The display names are
     * Meteor's, which it saves and a player types: never change them.
     */
    public enum PauseMode {
        BOTH("Both"),
        PLACE("Place"),
        BREAK("Break"),
        NONE("None");

        private final String display;

        PauseMode(String display) {
            this.display = display;
        }

        /** Meteor's {@code PauseMode.equals(process)}: this very process, or both. */
        public boolean pauses(PauseMode process) {
            return this == process || this == BOTH;
        }

        @Override
        public String toString() {
            return display;
        }
    }

    public CrystalSettings {
        Damage.check(targetRange, "target range");
        Damage.check(minDamage, "min damage");
        Damage.check(maxDamage, "max damage");
        Objects.requireNonNull(autoSwitch, "auto switch");
        Damage.check(facePlaceHealth, "face place health");
        Damage.check(facePlaceDurability, "face place durability");
        if (breakAttempts < 0) throw new IllegalArgumentException("break attempts " + breakAttempts);
        if (attackFrequency < 1) throw new IllegalArgumentException("attack frequency " + attackFrequency);
        Objects.requireNonNull(pauseOnUse, "pause on use");
        Objects.requireNonNull(pauseOnMine, "pause on mine");
        Damage.check(pauseHealth, "pause health");
        Objects.requireNonNull(risk, "risk");
        Damage.check(reserve, "reserve");
        if (reserve < SelfBudget.FLOOR) throw new IllegalArgumentException("reserve " + reserve + " below the floor");
        Damage.check(safeSelfDamage, "safe self damage");
    }

    /**
     * The reserve R the budget keeps: {@link #risk}'s, or the {@code reserve} setting when the level is
     * {@link RiskLevel#CUSTOM}. {@link #reserve} itself is only that setting.
     */
    public double budgetReserve() {
        return risk.reserve(reserve);
    }

    /** Meteor's defaults (lines 90-432) and the Safety group's (§2). */
    public static CrystalSettings defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A builder starting from these values. */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.targetRange = targetRange;
        b.minDamage = minDamage;
        b.maxDamage = maxDamage;
        b.antiSuicide = antiSuicide;
        b.rotate = rotate;
        b.autoSwitch = autoSwitch;
        b.noGapSwitch = noGapSwitch;
        b.noBowSwitch = noBowSwitch;
        b.antiWeakness = antiWeakness;
        b.place = place;
        b.facePlace = facePlace;
        b.facePlaceHealth = facePlaceHealth;
        b.facePlaceDurability = facePlaceDurability;
        b.breakCrystals = breakCrystals;
        b.breakAttempts = breakAttempts;
        b.attackFrequency = attackFrequency;
        b.fastBreak = fastBreak;
        b.pauseOnUse = pauseOnUse;
        b.pauseOnMine = pauseOnMine;
        b.pauseOnLag = pauseOnLag;
        b.pauseHealth = pauseHealth;
        b.risk = risk;
        b.selfBudget = selfBudget;
        b.finishingBlow = finishingBlow;
        b.reserve = reserve;
        b.safeSelfDamage = safeSelfDamage;
        return b;
    }

    /** Starts at the defaults. */
    public static final class Builder {
        private double targetRange = 10;
        private double minDamage = 6;
        private double maxDamage = 6;
        private boolean antiSuicide = true;
        private boolean rotate = true;
        private AutoSwitch autoSwitch = AutoSwitch.NORMAL;
        private boolean noGapSwitch = true;
        private boolean noBowSwitch = true;
        private boolean antiWeakness = true;
        private boolean place = true;
        private boolean facePlace = true;
        private double facePlaceHealth = 8;
        private double facePlaceDurability = 2;
        private boolean breakCrystals = true;
        private int breakAttempts = 2;
        private int attackFrequency = 25;
        private boolean fastBreak = true;
        private PauseMode pauseOnUse = PauseMode.PLACE;
        private PauseMode pauseOnMine = PauseMode.NONE;
        private boolean pauseOnLag = true;
        private double pauseHealth = 5;
        private RiskLevel risk = RiskLevel.BALANCED;
        private boolean selfBudget = true;
        private boolean finishingBlow = true;
        private double reserve = SelfBudget.DEFAULT_RESERVE;
        private double safeSelfDamage = SelfBudget.DEFAULT_SAFE_SELF_DAMAGE;

        private Builder() {}

        public Builder targetRange(double v) { targetRange = v; return this; }
        public Builder minDamage(double v) { minDamage = v; return this; }
        public Builder maxDamage(double v) { maxDamage = v; return this; }
        public Builder antiSuicide(boolean v) { antiSuicide = v; return this; }
        public Builder rotate(boolean v) { rotate = v; return this; }
        public Builder autoSwitch(AutoSwitch v) { autoSwitch = v; return this; }
        public Builder noGapSwitch(boolean v) { noGapSwitch = v; return this; }
        public Builder noBowSwitch(boolean v) { noBowSwitch = v; return this; }
        public Builder antiWeakness(boolean v) { antiWeakness = v; return this; }
        public Builder place(boolean v) { place = v; return this; }
        public Builder facePlace(boolean v) { facePlace = v; return this; }
        public Builder facePlaceHealth(double v) { facePlaceHealth = v; return this; }
        public Builder facePlaceDurability(double v) { facePlaceDurability = v; return this; }
        public Builder breakCrystals(boolean v) { breakCrystals = v; return this; }
        public Builder breakAttempts(int v) { breakAttempts = v; return this; }
        public Builder attackFrequency(int v) { attackFrequency = v; return this; }
        public Builder fastBreak(boolean v) { fastBreak = v; return this; }
        public Builder pauseOnUse(PauseMode v) { pauseOnUse = v; return this; }
        public Builder pauseOnMine(PauseMode v) { pauseOnMine = v; return this; }
        public Builder pauseOnLag(boolean v) { pauseOnLag = v; return this; }
        public Builder pauseHealth(double v) { pauseHealth = v; return this; }
        public Builder risk(RiskLevel v) { risk = v; return this; }
        public Builder selfBudget(boolean v) { selfBudget = v; return this; }
        public Builder finishingBlow(boolean v) { finishingBlow = v; return this; }
        public Builder reserve(double v) { reserve = v; return this; }
        public Builder safeSelfDamage(double v) { safeSelfDamage = v; return this; }

        public CrystalSettings build() {
            return new CrystalSettings(targetRange, minDamage, maxDamage, antiSuicide, rotate, autoSwitch,
                noGapSwitch, noBowSwitch, antiWeakness, place, facePlace, facePlaceHealth, facePlaceDurability,
                breakCrystals, breakAttempts, attackFrequency, fastBreak, pauseOnUse, pauseOnMine, pauseOnLag,
                pauseHealth, risk, selfBudget, finishingBlow, reserve, safeSelfDamage);
        }
    }
}
