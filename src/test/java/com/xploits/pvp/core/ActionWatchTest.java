package com.xploits.pvp.core;

import com.xploits.shared.core.i18n.Catalog;

import com.xploits.shared.core.i18n.Language;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The numbers in these tests are worked out by hand against {@link ActionWatch#IDLE_TICKS}, not
 * against the code's formula: the default margin is 60 ticks, so tick 59 stays quiet, tick 60
 * speaks, and spending on tick 30 moves the warning to tick 90.
 */
class ActionWatchTest {
    /** The warning as a Spanish-speaking player reads it: what these tests already checked. */
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });

    private static String reason(ActionWatch.Idle idle) {
        return ES.render(ActionWatch.reason(idle));
    }

    private static final String AURA = ManagedModules.CRYSTAL_AURA.name();
    private static final String TRAP = ManagedModules.AUTO_TRAP.name();
    private static final String SURROUND = ManagedModules.SURROUND.name();
    private static final String FILLER = ManagedModules.HOLE_FILLER.name();

    /** With a target in front and whatever stack it is given in the hotbar. */
    private static CombatSnapshot with(Map<Resource, Integer> resources) {
        return Snapshots.of(true, 3.0, 0, 0, false, false, false, 2, resources);
    }

    private static CombatSnapshot withCrystals(int crystals) {
        return with(Map.of(Resource.CRYSTALS, crystals));
    }

    private static CombatSnapshot obsidian(int blocks) {
        return with(Map.of(Resource.OBSIDIAN, blocks));
    }

    private static CombatSnapshot noTarget(int crystals) {
        return Snapshots.of(false, 0, 0, 0, false, false, false, 2, Map.of(Resource.CRYSTALS, crystals));
    }

    /** Feeds {@code ticks} identical ticks and returns whether any of them had a verdict for that stack. */
    private static boolean feed(ActionWatch watch, int ticks, CombatSnapshot snapshot,
                                Set<String> wanted, Set<String> active, Resource resource) {
        boolean warned = false;
        for (int i = 0; i < ticks; i++) {
            for (ActionWatch.Idle idle : watch.update(snapshot, wanted, active)) {
                if (idle.resource() == resource) warned = true;
            }
        }
        return warned;
    }

    @Test
    void thePickaxeUserAndTheAntiModulesAreLeftOut() {
        // auto-city uses a pickaxe, which is not consumed; the three anti- modules are reactive: they
        // spend only when their threat shows up, so a still stack is them working. Watching any of the
        // four would mean warning of a failure every time they work well.
        assertFalse(ActionWatch.watches(ManagedModules.AUTO_CITY), "auto-city uses a pickaxe, which does not go down");
        assertFalse(ActionWatch.watches(ManagedModules.ANTI_ANVIL));
        assertFalse(ActionWatch.watches(ManagedModules.ANTI_BED));
        assertFalse(ActionWatch.watches(ManagedModules.ANTI_ANCHOR));

        assertEquals(List.of(ManagedModules.CRYSTAL_AURA, ManagedModules.AUTO_TRAP,
                ManagedModules.AUTO_WEB, ManagedModules.SURROUND,
                ManagedModules.AUTO_ANVIL, ManagedModules.HOLE_FILLER),
            ActionWatch.WATCHED,
            "six of the ten: the four offensive ones that spend and the two obsidian ones");
        assertEquals(List.of(Resource.CRYSTALS, Resource.OBSIDIAN, Resource.WEBS, Resource.ANVILS),
            ActionWatch.WATCHED_RESOURCES, "four stacks, and obsidian only once for the three");
    }

    @Test
    void antiBedOnWithItsStringStillIsNotIdle() {
        // anti-bed places string only when a bed can be put on you: with nobody trying, its string not
        // moving for three seconds is the module waiting, not failing.
        ActionWatch watch = new ActionWatch();
        String antiBed = ManagedModules.ANTI_BED.name();
        CombatSnapshot snapshot = with(Map.of(Resource.STRING, 5));

        for (int tick = 0; tick < ActionWatch.IDLE_TICKS + 5; tick++) {
            assertEquals(List.of(), watch.update(snapshot, Set.of(antiBed), Set.of(antiBed)), "tick " + tick);
        }
        assertEquals(List.of(), watch.idle());
    }

    @Test
    void theMarginIsThreeSecondsAndThatMustStayTrue() {
        // The number, pinned by hand: if someone touches it, this test is the one that argues. From below
        // it has to leave plenty of room for the slowest legitimate cadence of the watched modules -the
        // `delay` of AutoAnvil, 10 ticks by default, that is one anvil every eleven- and from above there
        // is no hurry, because the cause it looks for is permanent.
        assertEquals(60, ActionWatch.IDLE_TICKS, "three seconds");
        assertTrue(ActionWatch.IDLE_TICKS >= 5 * 11,
            "five auto-anvil cadences (11 ticks each) before claiming anything");
    }

    @Test
    void quietUntilTheMarginAndWarnsExactlyAtIt() {
        ActionWatch watch = new ActionWatch();
        CombatSnapshot snapshot = withCrystals(10);

        // 59 ticks counted by hand, not ActionWatch.IDLE_TICKS - 1: one less than the margin does not
        // claim anything yet.
        assertFalse(feed(watch, 59, snapshot, Set.of(AURA), Set.of(AURA), Resource.CRYSTALS),
            "59 ticks are not 60");
        assertEquals(59, watch.idleTicksOf(Resource.CRYSTALS));
        assertEquals(List.of(), watch.idle());

        List<ActionWatch.Idle> sixty = watch.update(snapshot, Set.of(AURA), Set.of(AURA));
        assertEquals(1, sixty.size(), "tick 60 does");
        assertEquals(List.of(ManagedModules.CRYSTAL_AURA), sixty.getFirst().modules());
        assertEquals(60, sixty.getFirst().ticks());
        assertFalse(sixty.getFirst().joint(), "the aura is the only one drawing from the crystals");
        assertEquals(1, watch.idle().size());
    }

    @Test
    void spendingResets() {
        // Spending on tick 30: the count goes back to zero and the warning moves to tick 90. On tick 89 -59
        // ticks after the spending- not yet.
        ActionWatch watch = new ActionWatch();
        feed(watch, 30, withCrystals(10), Set.of(AURA), Set.of(AURA), Resource.CRYSTALS);

        assertEquals(List.of(), watch.update(withCrystals(9), Set.of(AURA), Set.of(AURA)),
            "the crystal count went down: it is acting");
        assertEquals(0, watch.idleTicksOf(Resource.CRYSTALS));

        assertFalse(feed(watch, 59, withCrystals(9), Set.of(AURA), Set.of(AURA), Resource.CRYSTALS),
            "tick 89: 59 ticks since the spending");
        assertEquals(1, watch.update(withCrystals(9), Set.of(AURA), Set.of(AURA)).size(), "tick 90");
    }

    @Test
    void pickingUpMaterialAlsoResets() {
        // A rise is not spending, but it breaks the run just the same: it can hide some (you spend one,
        // pick up two) and the same thing is no longer being compared.
        ActionWatch watch = new ActionWatch();
        feed(watch, 59, withCrystals(10), Set.of(AURA), Set.of(AURA), Resource.CRYSTALS);

        assertEquals(List.of(), watch.update(withCrystals(14), Set.of(AURA), Set.of(AURA)));
        assertEquals(0, watch.idleTicksOf(Resource.CRYSTALS));
    }

    @Test
    void saidOnceAndNotInALoop() {
        ActionWatch watch = new ActionWatch();
        CombatSnapshot snapshot = withCrystals(10);
        assertTrue(feed(watch, ActionWatch.IDLE_TICKS, snapshot, Set.of(AURA), Set.of(AURA),
            Resource.CRYSTALS));

        assertFalse(feed(watch, 200, snapshot, Set.of(AURA), Set.of(AURA), Resource.CRYSTALS),
            "ten more seconds of the same and not one more line");
        // But the situation can still be seen: the warning goes quiet, the counter does not.
        assertEquals(1, watch.idle().size());
        assertEquals(260, watch.idleTicksOf(Resource.CRYSTALS));
        assertEquals(260, watch.idle().getFirst().ticks());
    }

    @Test
    void ifTheSituationChangesItRearms() {
        ActionWatch watch = new ActionWatch();
        assertTrue(feed(watch, ActionWatch.IDLE_TICKS, withCrystals(10), Set.of(AURA), Set.of(AURA),
            Resource.CRYSTALS));

        // The fight ends: a single tick without a target rearms.
        watch.update(noTarget(10), Set.of(), Set.of());
        assertEquals(List.of(), watch.idle());

        assertTrue(feed(watch, ActionWatch.IDLE_TICKS, withCrystals(10), Set.of(AURA), Set.of(AURA),
            Resource.CRYSTALS), "in the next fight it warns again");
    }

    @Test
    void losingTheTargetResetsTheCount() {
        ActionWatch watch = new ActionWatch();
        feed(watch, 59, withCrystals(10), Set.of(AURA), Set.of(AURA), Resource.CRYSTALS);

        watch.update(noTarget(10), Set.of(AURA), Set.of(AURA));
        assertEquals(0, watch.idleTicksOf(Resource.CRYSTALS));

        assertFalse(feed(watch, 59, withCrystals(10), Set.of(AURA), Set.of(AURA), Resource.CRYSTALS),
            "the count started from zero, it did not carry on");
    }

    @Test
    void withoutMaterialItDoesNotCount() {
        // Without crystals the aura cannot place: not spending is normal and nothing is claimed.
        // The plan already warns about that on its own (redesign §7).
        ActionWatch watch = new ActionWatch();
        assertFalse(feed(watch, 200, withCrystals(0), Set.of(AURA), Set.of(AURA), Resource.CRYSTALS));
        assertEquals(0, watch.idleTicksOf(Resource.CRYSTALS));
    }

    @Test
    void belowTheModulesMinimumNeither() {
        // auto-trap needs eight obsidian for the whole trap: with seven it cannot act, and it is
        // the only one of the three asked for here, so the whole stack is left with nobody.
        ActionWatch watch = new ActionWatch();
        assertFalse(feed(watch, 200, obsidian(7), Set.of(TRAP), Set.of(TRAP), Resource.OBSIDIAN));

        // With exactly the minimum it is measured. It uses a new watch on purpose: going from seven to
        // eight is the stack moving, and that resets on its own -what is checked here is the
        // minimum, not the reset-.
        assertTrue(feed(new ActionWatch(), ActionWatch.IDLE_TICKS, obsidian(8), Set.of(TRAP),
            Set.of(TRAP), Resource.OBSIDIAN), "with eight it could, and it did not spend");
    }

    @Test
    void offOrUnwantedDoNotCount() {
        ActionWatch watch = new ActionWatch();
        CombatSnapshot snapshot = withCrystals(10);

        assertFalse(feed(watch, 200, snapshot, Set.of(AURA), Set.of(), Resource.CRYSTALS),
            "the plan wants it but it is not on: there is nothing to measure");
        assertFalse(feed(watch, 200, snapshot, Set.of(), Set.of(AURA), Resource.CRYSTALS),
            "it is on but it is the player's, the plan does not ask for it");
    }

    @Test
    void obsidianIsSharedByThreeAndSpendingCountsForAllThree() {
        // There is no way to know who placed: the inventory only says there is one less. It is credited
        // to all three, which is the cheap side -a late warning, not a false one-.
        ActionWatch watch = new ActionWatch();
        Set<String> three = Set.of(TRAP, SURROUND, FILLER);
        for (int i = 0; i < 30; i++) watch.update(obsidian(20), three, three);
        assertEquals(30, watch.idleTicksOf(Resource.OBSIDIAN));

        watch.update(obsidian(19), three, three);
        assertEquals(0, watch.idleTicksOf(Resource.OBSIDIAN));

        // And from there, the verdict is reached again all at once: tick 59 quiet, tick 90 a single one
        // with the three names inside.
        for (int i = 0; i < 59; i++) {
            assertEquals(List.of(), watch.update(obsidian(19), three, three));
        }
        List<ActionWatch.Idle> ninety = watch.update(obsidian(19), three, three);
        assertEquals(1, ninety.size(), "one verdict, not three");
        assertEquals(List.of(ManagedModules.AUTO_TRAP, ManagedModules.SURROUND, ManagedModules.HOLE_FILLER),
            ninety.getFirst().modules());
        assertTrue(ninety.getFirst().joint());
    }

    @Test
    void theSharedStackVerdictIsNotSplitPerModule() {
        // It is what the measurement supports and nothing else: the stack does not go down, and the inventory
        // does not say who places. Reporting the three separately would claim a per-module certainty
        // that does not exist.
        ActionWatch watch = new ActionWatch();
        Set<String> three = Set.of(TRAP, SURROUND, FILLER);
        List<ActionWatch.Idle> verdicts = null;
        for (int i = 0; i < ActionWatch.IDLE_TICKS; i++) {
            List<ActionWatch.Idle> tick = watch.update(obsidian(20), three, three);
            if (!tick.isEmpty()) verdicts = tick;
        }

        assertEquals(1, verdicts.size());
        ActionWatch.Idle idle = verdicts.getFirst();
        assertTrue(idle.joint());
        assertEquals(3, idle.modules().size());

        String warning = reason(idle);
        assertTrue(warning.startsWith("auto-trap, surround y hole-filler llevan 3 s encendidos"), warning);
        assertTrue(warning.contains("No puedo decirte cuál de los 3 falla"), warning);
        assertTrue(warning.contains("no quién la colocó"), warning);
        assertTrue(warning.contains("no ha colocado ninguno"), warning);
        // And it still names each one's suspects, which is what it is for.
        assertTrue(warning.contains("auto-trap: whitelist"), warning);
        assertTrue(warning.contains("surround: blocks"), warning);
        assertTrue(warning.contains("hole-filler: only-moving"), warning);
    }

    @Test
    void aSingleUserOfTheSharedStackIsReportedAsItsOwn() {
        // If the plan only wants hole-filler, nobody else is in a position to spend that obsidian
        // and the verdict is indeed its own: there is no certainty to invent.
        ActionWatch watch = new ActionWatch();
        List<ActionWatch.Idle> verdicts = null;
        for (int i = 0; i < ActionWatch.IDLE_TICKS; i++) {
            List<ActionWatch.Idle> tick = watch.update(obsidian(20), Set.of(FILLER), Set.of(FILLER));
            if (!tick.isEmpty()) verdicts = tick;
        }

        ActionWatch.Idle idle = verdicts.getFirst();
        assertFalse(idle.joint());
        assertEquals(List.of(ManagedModules.HOLE_FILLER), idle.modules());
        assertTrue(reason(idle).startsWith("hole-filler lleva 3 s encendido"),
            reason(idle));
    }

    @Test
    void aChangeOfGroupResets() {
        // auto-trap joins on tick 50: the verdict about the two cannot rest on the
        // fifty ticks in which only hole-filler was there. With 20 obsidian both reach their
        // minimum, so the change is of the group and not of the stack.
        ActionWatch watch = new ActionWatch();
        for (int i = 0; i < 50; i++) watch.update(obsidian(20), Set.of(FILLER), Set.of(FILLER));
        assertEquals(50, watch.idleTicksOf(Resource.OBSIDIAN));

        Set<String> two = Set.of(FILLER, TRAP);
        watch.update(obsidian(20), two, two);
        assertEquals(1, watch.idleTicksOf(Resource.OBSIDIAN), "it starts from zero with the new group");

        assertFalse(feed(watch, 58, obsidian(20), two, two, Resource.OBSIDIAN), "59 so far");
        assertTrue(feed(watch, 1, obsidian(20), two, two, Resource.OBSIDIAN), "the new group's tick 60");
    }

    @Test
    void aStackThatIsNotItsOwnDoesNotResetIt() {
        // The aura lives off the crystals: the obsidian going down says nothing about it.
        ActionWatch watch = new ActionWatch();
        Set<String> two = Set.of(AURA, FILLER);
        for (int i = 0; i < 59; i++) {
            watch.update(with(Map.of(Resource.CRYSTALS, 10, Resource.OBSIDIAN, 20)), two, two);
        }
        List<ActionWatch.Idle> sixty =
            watch.update(with(Map.of(Resource.CRYSTALS, 10, Resource.OBSIDIAN, 19)), two, two);
        assertEquals(1, sixty.size(), "the hole-filler spent and is off the hook; the aura is not");
        assertEquals(Resource.CRYSTALS, sixty.getFirst().resource());
    }

    @Test
    void theWarningNamesTheModuleAndTheSuspect() {
        for (ManagedModule module : ActionWatch.WATCHED) {
            ActionWatch.Idle idle =
                new ActionWatch.Idle(module.needs(), List.of(module), ActionWatch.IDLE_TICKS);
            String reason = reason(idle);
            assertTrue(reason.startsWith(module.name() + " lleva 3 s encendido"), reason);
            assertTrue(reason.contains("No lo apago"), reason);
            assertFalse(reason.contains("apágalo"), reason);
        }

        String aura = reason(
            new ActionWatch.Idle(Resource.CRYSTALS, List.of(ManagedModules.CRYSTAL_AURA), 60));
        assertTrue(aura.contains("cristales"), aura);
        assertTrue(aura.contains("min-damage"), aura);
        assertTrue(aura.contains("support"), aura);

        String filler = reason(
            new ActionWatch.Idle(Resource.OBSIDIAN, List.of(ManagedModules.HOLE_FILLER), 60));
        assertTrue(filler.contains("obsidiana"), filler);
        assertTrue(filler.contains("only-moving"), filler);
        assertTrue(filler.contains("no haya ningún hueco"), filler);
    }

    @Test
    void autoTrapsWhitelistHasNoNetheriteBlock() {
        String trap = reason(
            new ActionWatch.Idle(Resource.OBSIDIAN, List.of(ManagedModules.AUTO_TRAP), 60));
        assertTrue(trap.contains("obsidiana llorosa"), trap);
        assertTrue(trap.contains("el bloque de netherita NO está en ella"), trap);
    }

    @Test
    void autoWebNamesItsInnocentCause() {
        // A web is not replaceable, so as soon as the intended cell has a web
        // auto-web stops placing there, legitimately. It is its equivalent of the complete surround.
        String web = reason(
            new ActionWatch.Idle(Resource.WEBS, List.of(ManagedModules.AUTO_WEB), 60));
        assertTrue(web.contains("ya tenga telaraña"), web);
        assertTrue(web.contains("no se sustituye"), web);
        assertTrue(web.indexOf("telaraña no se sustituye") < web.indexOf("los sospechosos son"),
            "the innocent cause goes before the suspects: " + web);
    }

    @Test
    void resetForgetsTheCountAndWhatWasWarned() {
        ActionWatch watch = new ActionWatch();
        assertTrue(feed(watch, ActionWatch.IDLE_TICKS, withCrystals(10), Set.of(AURA), Set.of(AURA),
            Resource.CRYSTALS));

        watch.reset();
        assertEquals(0, watch.idleTicksOf(Resource.CRYSTALS));
        assertEquals(List.of(), watch.idle());
        assertTrue(feed(watch, ActionWatch.IDLE_TICKS, withCrystals(10), Set.of(AURA), Set.of(AURA),
            Resource.CRYSTALS), "after the reset it counts from zero again");
    }
}
