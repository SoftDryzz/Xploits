package com.xploits.pvp.core;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The director with the computed shell (surround++ spec §8). */
class ShellModuleDirectorTest {
    private static final Set<ManagedModule> ALL = Set.copyOf(ManagedModules.ALL);

    private static Plan tick(CombatSnapshot s, ShellModule shell) {
        return new CombatDirector().tick(s, 6, DefensivePolicy.THREAT_MARGIN, ALL, Set.of(), shell);
    }

    private static boolean asked(Plan plan, ManagedModule m) {
        return plan.enable().contains(m) || plan.skipped().stream().anyMatch(s -> s.module().equals(m));
    }

    @Test
    void theComputedShellIsOnInEveryPhaseEvenWhenCalm() {
        assertTrue(tick(CombatSnapshot.none(), ShellModule.XPLOITS).enable().contains(ManagedModules.SURROUND));
        assertFalse(asked(tick(CombatSnapshot.none(), ShellModule.METEOR), ManagedModules.SURROUND),
            "Meteor's surround still waits for a threat while you stand in a hole");
    }

    @Test
    void theComputedShellNeedsNoObsidianToGoUp() {
        // none() carries no resources at all: Meteor's surround would be left out for want of 4 obsidian.
        CombatSnapshot threatenedInAHole = CombatSnapshot.none().withDefense(20, 10, true, true);
        assertTrue(tick(threatenedInAHole, ShellModule.XPLOITS).enable().contains(ManagedModules.SURROUND));
        assertFalse(tick(threatenedInAHole, ShellModule.METEOR).enable().contains(ManagedModules.SURROUND));
    }

    @Test
    void withTheComputedShellHoleFillerIsNeverAskedFor() {
        CombatSnapshot threatened = CombatSnapshot.none().withDefense(20, 10, false, true);
        assertTrue(asked(tick(threatened, ShellModule.METEOR), ManagedModules.HOLE_FILLER));
        assertFalse(asked(tick(threatened, ShellModule.XPLOITS), ManagedModules.HOLE_FILLER));
    }

    @Test
    void aProfileWithoutSurroundKeepsTheComputedShellOff() {
        Set<ManagedModule> allowed = new HashSet<>(ManagedModules.ALL);
        allowed.remove(ManagedModules.SURROUND);
        Plan plan = new CombatDirector().tick(CombatSnapshot.none(), 6, DefensivePolicy.THREAT_MARGIN, allowed, Set.of(),
            ShellModule.XPLOITS);
        assertFalse(plan.enable().contains(ManagedModules.SURROUND));
        assertTrue(plan.skipped().stream().anyMatch(s -> s.module().equals(ManagedModules.SURROUND)));
    }

    @Test
    void theShorterTickIsMeteorsShell() {
        CombatSnapshot threatened = CombatSnapshot.none().withDefense(20, 10, true, true);
        assertEquals(tick(threatened, ShellModule.METEOR).enable(),
            new CombatDirector().tick(threatened, 6, DefensivePolicy.THREAT_MARGIN, ALL, Set.of()).enable());
    }
}
