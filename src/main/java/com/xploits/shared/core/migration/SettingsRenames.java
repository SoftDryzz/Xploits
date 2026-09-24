package com.xploits.shared.core.migration;

import java.util.Map;

/**
 * Saved names that 0.4.0 renamed (code-in-English design §7): Meteor stores modules, setting groups,
 * settings and enum values by name, so each old name here is moved to its new one once, at startup.
 * The old names are Spanish on purpose: they are what players' files contain.
 */
public final class SettingsRenames {
    private SettingsRenames() {
    }

    /**
     * @param groups   new module name → (old group → new group)
     * @param settings "new module/new group" → (old setting → new setting)
     * @param values   "new module/new group/new setting" → (old value → new value)
     */
    public record Table(Map<String, String> modules, Map<String, Map<String, String>> groups,
                        Map<String, Map<String, String>> settings, Map<String, Map<String, String>> values) {
    }

    public static final Table V0_4_0 = new Table(
        Map.of("consola", "console"),
        Map.of(
            "nether-sweep", Map.of("Pasada", "Lane", "Cohetes", "Fireworks", "Vuelo", "Flight", "Avisos", "Notify"),
            "auto-travel", Map.of("Patrón", "Pattern", "Vuelo", "Flight", "Avisos", "Notify")), // i18n: allowed (old saved name)
        Map.of("auto-travel/Pattern", Map.of("quiebro-leg", "swerve-leg", "quiebro-offset", "swerve-offset")),
        Map.of(
            "auto-travel/Pattern/pattern",
            Map.of("RECTO", "STRAIGHT", "QUIEBRO", "SWERVE", "ESPIRAL", "SPIRAL", "SENUELO", "DECOY"),
            "auto-travel/General/destination-mode",
            Map.of("COORDENADAS", "COORDINATES", "RELATIVO", "RELATIVE", "AUTOPISTA", "HIGHWAY")));
}
