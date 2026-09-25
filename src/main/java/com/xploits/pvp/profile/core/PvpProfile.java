package com.xploits.pvp.profile.core;

import com.xploits.pvp.core.ManagedModule;
import com.xploits.pvp.core.ManagedModules;

import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A style profile (design §2): the auto-pvp values it sets and which managed modules the director may
 * use. Never touches the inner settings of a Meteor module.
 *
 * @param name             an id, shown as-is in both languages (like a module name); {@code [a-z0-9-]{1,16}}
 * @param targetRange      {@code target-range}, {@value #TARGET_RANGE_MIN}-{@value #TARGET_RANGE_MAX}
 *                         (the setting's {@code range()}, not its slider)
 * @param approachDistance {@code approach-distance}, {@value #APPROACH_DISTANCE_MIN}-{@value #APPROACH_DISTANCE_MAX}
 * @param threatMargin     {@code threat-margin}, {@value #THREAT_MARGIN_MIN}-{@value #THREAT_MARGIN_MAX}
 *                         (the setting's range is wider than its slider, 0-20)
 * @param allowed          managed module names the director may use ({@link ManagedModules#ALL});
 *                         empty is a valid profile (auto-pvp only watches), {@link ProfileBook#save}
 *                         warns about it
 */
public record PvpProfile(String name, int targetRange, int approachDistance, double threatMargin, Set<String> allowed) {
    /** Same shape as a module name (glossary): lowercase letters, digits and hyphens, 1 to 16 characters. */
    public static final Pattern NAME = Pattern.compile("[a-z0-9-]{1,16}");

    public static final int TARGET_RANGE_MIN = 4;
    public static final int TARGET_RANGE_MAX = 64;
    public static final int APPROACH_DISTANCE_MIN = 2;
    public static final int APPROACH_DISTANCE_MAX = 6;
    public static final double THREAT_MARGIN_MIN = 0.0;
    public static final double THREAT_MARGIN_MAX = 40.0;

    /** Tolerance for comparing {@code threatMargin} in {@link #modified} (design §2). */
    private static final double MODIFIED_TOLERANCE = 1e-6;

    private static final Set<String> MANAGED_NAMES = ManagedModules.ALL.stream()
        .map(ManagedModule::name)
        .collect(Collectors.toUnmodifiableSet());

    public PvpProfile {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("bad profile name: " + name);
        }
        if (targetRange < TARGET_RANGE_MIN || targetRange > TARGET_RANGE_MAX) {
            throw new IllegalArgumentException("target-range out of range: " + targetRange);
        }
        if (approachDistance < APPROACH_DISTANCE_MIN || approachDistance > APPROACH_DISTANCE_MAX) {
            throw new IllegalArgumentException("approach-distance out of range: " + approachDistance);
        }
        if (threatMargin < THREAT_MARGIN_MIN || threatMargin > THREAT_MARGIN_MAX) {
            throw new IllegalArgumentException("threat-margin out of range: " + threatMargin);
        }
        allowed = Set.copyOf(allowed);
        for (String module : allowed) {
            if (!MANAGED_NAMES.contains(module)) {
                throw new IllegalArgumentException("not a managed module: " + module);
            }
        }
    }

    /**
     * Whether the given values differ from this profile's (design §2): an exact comparison for the two
     * int settings and the allowed set, {@value #MODIFIED_TOLERANCE} tolerance for {@code threatMargin}
     * (a slider step can round it without the player having touched anything).
     */
    public boolean modified(int otherTargetRange, int otherApproachDistance, double otherThreatMargin, Set<String> otherAllowed) {
        return targetRange != otherTargetRange
            || approachDistance != otherApproachDistance
            || Math.abs(threatMargin - otherThreatMargin) > MODIFIED_TOLERANCE
            || !allowed.equals(Set.copyOf(otherAllowed));
    }

    /** Whether this profile allows {@code crystal-aura}: without it, saving warns that the profile loses the autobreak. */
    public boolean hasCrystalAura() {
        return allowed.contains(ManagedModules.CRYSTAL_AURA.name());
    }
}
