package com.xploits.pvp.core;

import com.xploits.shared.core.i18n.Msg;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
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

    /** In a hole, threatened, the enemy on top of you (3.0: auto-trap reaches him), carrying this much obsidian. */
    private static CombatSnapshot inAHoleWithObsidian(int obsidian) {
        Map<Resource, Integer> resources = Map.of(Resource.CRYSTALS, 12, Resource.OBSIDIAN, obsidian,
            Resource.WEBS, 5, Resource.ANVILS, 3, Resource.PICKAXE, 1);
        return Snapshots.of(true, 3.0, 0, 0, false, false, false, 2, resources).withTargetId("enemy")
            .withDefense(10.0, 0.0, true, true);
    }

    private static Msg reasonFor(Plan plan, ManagedModule module) {
        return plan.skipped().stream().filter(s -> s.module().equals(module)).map(Skipped::reason).findFirst().orElse(null);
    }

    @Test
    void theComputedShellSetsItsObsidianAsideBeforeAutoTrapAsMeteorsDoes() {
        // 10 obsidian. Meteor's shell: hole-filler 1 (9 left), surround 4 (5), anti-anvil 1 (4); auto-trap needs 8: out.
        Plan meteor = tick(inAHoleWithObsidian(10), ShellModule.METEOR);
        assertTrue(meteor.skipped().stream().anyMatch(s -> s.module().equals(ManagedModules.AUTO_TRAP)),
            "precondition: this phase asks for auto-trap and Meteor's shell leaves it out");
        assertFalse(meteor.enable().contains(ManagedModules.AUTO_TRAP));
        // surround++ sets aside Meteor's surround's 4 (6 left), then anti-anvil 1 (5); auto-trap needs 8: out as well.
        // Setting nothing aside, auto-trap would find 9 and come up, and both would swap to the same stack.
        Plan xploits = tick(inAHoleWithObsidian(10), ShellModule.XPLOITS);
        assertTrue(xploits.enable().contains(ManagedModules.SURROUND));
        assertTrue(xploits.enable().contains(ManagedModules.ANTI_ANVIL));
        assertFalse(xploits.enable().contains(ManagedModules.AUTO_TRAP), "5 are left and it needs 8");
        assertEquals(Msg.of(PvpText.SHORTAGE_SHARED, "have", 10,
                "others", Msg.of(PvpText.JOIN_AND, "first", "surround", "second", "anti-anvil"), "left", 5, "minimum", 8),
            reasonFor(xploits, ManagedModules.AUTO_TRAP), "the reason names who took the obsidian");
        // With 13: 4 for the shell (9 left), 1 for anti-anvil (8): exactly auto-trap's 8, so it comes up. The shell takes
        // its share, not the whole stack.
        assertTrue(tick(inAHoleWithObsidian(13), ShellModule.XPLOITS).enable().contains(ManagedModules.AUTO_TRAP));
    }

    @Test
    void theComputedShellStillGoesUpWithNoObsidianLeft() {
        // 0 obsidian: it sets aside what there is, nothing, and still comes up; it can break crystals with no blocks.
        Plan plan = tick(inAHoleWithObsidian(0), ShellModule.XPLOITS);
        assertTrue(plan.enable().contains(ManagedModules.SURROUND));
        assertFalse(plan.enable().contains(ManagedModules.AUTO_TRAP));
        assertFalse(plan.enable().contains(ManagedModules.ANTI_ANVIL), "nothing for anti-anvil either");
    }

    @Test
    void theShorterTickIsMeteorsShell() {
        CombatSnapshot threatened = CombatSnapshot.none().withDefense(20, 10, true, true);
        assertEquals(tick(threatened, ShellModule.METEOR).enable(),
            new CombatDirector().tick(threatened, 6, DefensivePolicy.THREAT_MARGIN, ALL, Set.of()).enable());
    }
}
