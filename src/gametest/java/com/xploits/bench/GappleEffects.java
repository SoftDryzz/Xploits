package com.xploits.bench;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;

/**
 * Task A1 requirement 4 (gapple model): the enchanted golden apple's effects, applied directly to a
 * player, server-side, instead of simulating the eat itself (the scheduling — 32 ticks after a pop, a
 * later pop re-arms it, a death cancels it — is {@link com.xploits.bench.core.GappleSchedule}, pure).
 *
 * <p>Values verified against the yarn 1.21.11 jar (javap on the merged {@code build.6} jar's
 * {@code ConsumableComponents}, class file, since no decompiled source was available in the Gradle cache):
 * its {@code ENCHANTED_GOLDEN_APPLE} constant applies Regeneration II ({@code amplifier} 1) for 400 ticks,
 * Resistance I ({@code amplifier} 0) for 6000 ticks, Fire Resistance I for 6000 ticks, and Absorption IV
 * ({@code amplifier} 3) for 2400 ticks, in that order. This bench applies only the three that matter for a
 * crystal fight — Absorption, Regeneration, Resistance — and deliberately leaves out Fire Resistance: a
 * documented simplification (there is no fire in these fights, so it would never change a metric).
 *
 * <p>Documented simplification (task A1 requirement 4): this does not tie up the eater's hands for the
 * 1.6 s the real eat takes (no item-use state, no walk-speed penalty); it is instant status effects only,
 * identical for both players and both auras.
 */
final class GappleEffects {
    /** Verified: {@code ConsumableComponents.ENCHANTED_GOLDEN_APPLE}'s Absorption, amplifier 3 (IV). */
    static final int ABSORPTION_TICKS = 2400;
    static final int ABSORPTION_AMPLIFIER = 3;
    /** Verified: its Regeneration, amplifier 1 (II). */
    static final int REGENERATION_TICKS = 400;
    static final int REGENERATION_AMPLIFIER = 1;
    /** Verified: its Resistance, amplifier 0 (I). */
    static final int RESISTANCE_TICKS = 6000;
    static final int RESISTANCE_AMPLIFIER = 0;

    private GappleEffects() {
    }

    /** Server thread. Applies (or refreshes) the three effects on {@code entity}. */
    static void apply(LivingEntity entity) {
        entity.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, ABSORPTION_TICKS, ABSORPTION_AMPLIFIER));
        entity.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, REGENERATION_TICKS, REGENERATION_AMPLIFIER));
        entity.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, RESISTANCE_TICKS, RESISTANCE_AMPLIFIER));
    }
}
