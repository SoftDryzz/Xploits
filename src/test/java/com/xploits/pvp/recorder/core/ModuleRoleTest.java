package com.xploits.pvp.recorder.core;

import com.xploits.pvp.core.ManagedModule;
import com.xploits.pvp.core.ManagedModules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModuleRoleTest {
    @Test
    void theCrystalAurasAreCrystalOffense() {
        for (String name : new String[] {"crystal-aura", "anchor-aura", "bed-aura"}) {
            assertEquals(ModuleRole.CRYSTAL_OFFENSE, ModuleRole.of(name), name);
        }
    }

    @Test
    void killAuraIsMeleeOffense() {
        assertEquals(ModuleRole.MELEE_OFFENSE, ModuleRole.of("kill-aura"));
    }

    @Test
    void theTrappersAreTrapOffense() {
        for (String name : new String[] {"auto-trap", "auto-web", "auto-anvil", "auto-city"}) {
            assertEquals(ModuleRole.TRAP_OFFENSE, ModuleRole.of(name), name);
        }
    }

    @Test
    void autoPvpsDefensiveModulesAndTheSelfLockersAreDefense() {
        for (ManagedModule module : ManagedModules.DEFENSIVE) {
            assertEquals(ModuleRole.DEFENSE, ModuleRole.of(module.name()), module.name());
        }
        for (String name : new String[] {"surround", "hole-filler", "anti-anvil", "anti-bed", "anti-anchor",
            "self-trap", "self-web", "self-anvil", "burrow"}) {
            assertEquals(ModuleRole.DEFENSE, ModuleRole.of(name), name);
        }
    }

    @Test
    void autoTotemAndOffhandAreTotem() {
        assertEquals(ModuleRole.TOTEM, ModuleRole.of("auto-totem"));
        assertEquals(ModuleRole.TOTEM, ModuleRole.of("offhand"));
    }

    @Test
    void anythingElseIsOther() {
        assertEquals(ModuleRole.OTHER, ModuleRole.of("auto-armor"));
        assertEquals(ModuleRole.OTHER, ModuleRole.of("auto-pvp"));
        assertEquals(ModuleRole.OTHER, ModuleRole.of("Crystal-Aura"));
        assertEquals(ModuleRole.OTHER, ModuleRole.of(""));
        assertEquals(ModuleRole.OTHER, ModuleRole.of(null));
    }
}
