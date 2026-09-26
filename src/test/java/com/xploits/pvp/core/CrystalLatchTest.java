package com.xploits.pvp.core;

import com.xploits.shared.core.i18n.Msg;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code crystal-module} latch (crystal-aura++ spec P5, Q4). The world here is auto-pvp's own loop,
 * reduced to what the order depends on: the real {@link ModuleLedger}, two aura modules that are on or
 * off, and the adapter's {@code onChanged}, tick start, {@code apply}, {@code releaseAll} and
 * after-move steps resolving the catalog's {@code crystal-aura} through the latch, as {@code AutoPvp}
 * does.
 */
class CrystalLatchTest {
    private static final String AURA = CrystalModule.LOGICAL;
    private static final String METEORS = "crystal-aura";
    private static final String OURS = "crystal-aura++";

    /** Auto-pvp's loop around the ledger, with the auras as plain switches. */
    private static final class World {
        boolean running = true;
        final CrystalLatch latch = new CrystalLatch(() -> running, this::releaseAll, this::moved);
        final ModuleLedger ledger = new ModuleLedger();
        final Map<String, Boolean> on = new HashMap<>(Map.of(METEORS, false, OURS, false));
        /** Every managed module, and crystal-aura++. */
        final Set<String> registered = registeredAll();
        final List<String> released = new ArrayList<>();
        final List<String> turnedOffByRelease = new ArrayList<>();
        final List<Msg> warnings = new ArrayList<>();
        MissingModules missing = MissingModules.measure(name -> true);
        int releases;

        /** AutoPvp.onActivate, first statement. */
        void activate(CrystalModule setting) {
            latch.latch(setting);
            missing = MissingModules.measure(name -> registered.contains(latch.resolve(name)));
        }

        /** Meteor setting the value: the setting's onChanged. */
        void set(CrystalModule value) {
            latch.requested(value);
        }

        /** One tick in a fight that wants the aura: the latch settles first, then AutoPvp.apply. */
        void tick() {
            latch.settle();
            Set<String> wanted = Set.of(AURA);
            Set<String> active = new LinkedHashSet<>();
            if (on.getOrDefault(latch.resolve(AURA), false)) active.add(AURA);
            ModuleLedger.Result result = ledger.apply(CombatState.SURFACE, CombatPosture.CALM, wanted, active);
            for (String name : result.toDisable()) on.put(latch.resolve(name), false);
            for (String name : result.toEnable()) on.put(latch.resolve(name), true);
            released.addAll(result.newlyReleased());
        }

        void ticks(int n) {
            for (int i = 0; i < n; i++) tick();
        }

        /** AutoPvp.releaseAll: turns off what the ledger owns, through the latch, and forgets it. */
        void releaseAll() {
            releases++;
            for (String name : ledger.owned()) {
                String real = latch.resolve(name);
                if (on.getOrDefault(real, false)) turnedOffByRelease.add(real);
                on.put(real, false);
            }
            ledger.reset();
        }

        /** AutoPvp after the latch moved: measure the missing modules again, and the already-on warning. */
        void moved(CrystalModule now) {
            missing = missing.remeasure(name -> registered.contains(latch.resolve(name)));
            latch.alreadyOn(on.getOrDefault(latch.resolve(AURA), false)).ifPresent(warnings::add);
        }

        /**
         * Meteor loading a saved value, call for call (meteor-client 1.21.11 sources):
         * {@code Settings.fromTag} resets every setting first ({@code Setting.reset}: the default,
         * {@code onChanged}), then {@code Setting.fromTag} runs {@code EnumSetting.load} ({@code parse}:
         * the saved value, {@code onChanged}) and fires {@code onChanged} once more.
         */
        void load(CrystalModule saved) {
            set(CrystalModule.METEOR);
            set(saved);
            set(saved);
        }
    }

    private static Set<String> registeredAll() {
        Set<String> names = new HashSet<>(Set.of(OURS));
        for (ManagedModule module : ManagedModules.ALL) names.add(module.name());
        return names;
    }

    private static World fightingWith(CrystalModule module) {
        World world = new World();
        world.activate(module);
        world.ticks(5);
        assertTrue(world.on.get(module.moduleName()), "auto-pvp took " + module.moduleName());
        assertFalse(world.on.get(module.other().moduleName()));
        assertEquals(Set.of(AURA), world.ledger.owned());
        return world;
    }

    @Test
    void aChangeIsAppliedOnTheNextTickReleasingTheOldAuraFirst() {
        World world = fightingWith(CrystalModule.METEOR);

        world.set(CrystalModule.XPLOITS);
        assertEquals(0, world.releases, "onChanged only notes it");
        assertSame(CrystalModule.METEOR, world.latch.latched());
        assertTrue(world.on.get(METEORS));

        world.tick();
        assertEquals(1, world.releases, "exactly one release");
        assertEquals(List.of(METEORS), world.turnedOffByRelease, "made with the old aura latched");
        assertSame(CrystalModule.XPLOITS, world.latch.latched());
        assertFalse(world.on.get(METEORS));
        assertTrue(world.on.get(OURS), "and the new one is driven in that same tick");

        for (int tick = 0; tick < 40; tick++) {
            world.tick();
            assertFalse(world.on.get(METEORS), "Meteor's aura stays off, tick " + tick);
            assertTrue(world.on.get(OURS), "crystal-aura++ stays driven, tick " + tick);
        }
        assertEquals(1, world.releases);
        assertEquals(List.of(), world.released, "the change is not taken for the player letting it go");
        assertEquals(List.of(), world.warnings, "crystal-aura++ was off: nothing to say");
    }

    @Test
    void andBackAgain() {
        World world = fightingWith(CrystalModule.XPLOITS);

        world.set(CrystalModule.METEOR);
        world.tick();
        assertEquals(List.of(OURS), world.turnedOffByRelease);
        world.ticks(40);
        assertFalse(world.on.get(OURS));
        assertTrue(world.on.get(METEORS));
        assertEquals(1, world.releases);
        assertEquals(List.of(), world.released);
    }

    @Test
    void theWrongOrderLeavesTheOldAuraOnAndOwnedByNobody() {
        // What a latch moved before the release does, spelled out by hand: releaseAll asks the new aura
        // -still off- to turn off, the old one stays on, and the reset ledger no longer owns it, so
        // auto-pvp never turns it off again; crystal-aura++ is taken next to it and refuses for good.
        World world = fightingWith(CrystalModule.METEOR);

        world.latch.latch(CrystalModule.XPLOITS);
        world.releaseAll();
        world.ticks(40);

        assertTrue(world.on.get(METEORS), "Meteor's aura was never turned off");
        assertTrue(world.on.get(OURS));
        assertEquals(Set.of(AURA), world.ledger.owned(), "the ledger owns only the new one");
    }

    @Test
    void aLatchMovedWithoutAnyReleaseMakesTheLedgerRecordARelease() {
        // P5's reason for releasing at all: with the latch moved under the ledger, the logical aura reads
        // off for the debounce ticks, the ledger records it as released by hand and never takes it again.
        World world = fightingWith(CrystalModule.METEOR);

        world.latch.latch(CrystalModule.XPLOITS);
        world.ticks(ModuleLedger.RELEASE_DEBOUNCE_TICKS + 40);

        assertEquals(List.of(AURA), world.released, "recorded as released by the player");
        assertFalse(world.on.get(OURS), "crystal-aura++ never comes on");
        assertTrue(world.on.get(METEORS), "and Meteor's is left on");
    }

    @Test
    void withAutoPvpOffAChangeIsNoChange() {
        World world = new World();
        world.activate(CrystalModule.METEOR);
        world.running = false;

        world.set(CrystalModule.XPLOITS);
        assertFalse(world.latch.settle());
        assertSame(CrystalModule.METEOR, world.latch.latched(), "the next activation latches the setting");
        assertEquals(0, world.releases);
    }

    @Test
    void theSameValueIsNoChange() {
        World world = fightingWith(CrystalModule.XPLOITS);

        world.set(CrystalModule.XPLOITS);
        world.ticks(3);
        assertEquals(0, world.releases);
        assertTrue(world.on.get(OURS), "nothing was released");
        assertEquals(Set.of(AURA), world.ledger.owned());
    }

    @Test
    void aLoadThatEndsOnTheLatchedValueIsNoChangeEvenThroughTheReset() {
        // The load resets to meteor first: acting on that call would switch to Meteor's aura and back.
        World world = fightingWith(CrystalModule.XPLOITS);

        world.load(CrystalModule.XPLOITS);
        world.ticks(10);
        assertEquals(0, world.releases, "reset to meteor, then crystal-aura++ saved: no release");
        assertSame(CrystalModule.XPLOITS, world.latch.latched());
        assertTrue(world.on.get(OURS));
        assertFalse(world.on.get(METEORS));
        assertEquals(Set.of(AURA), world.ledger.owned());

        World meteor = fightingWith(CrystalModule.METEOR);
        meteor.load(CrystalModule.METEOR);
        meteor.ticks(10);
        assertEquals(0, meteor.releases);
    }

    @Test
    void aLoadThatEndsOnAnotherValueIsOneChange() {
        World world = fightingWith(CrystalModule.XPLOITS);

        world.load(CrystalModule.METEOR);
        world.ticks(10);
        assertEquals(1, world.releases, "three onChanged calls, one change");
        assertEquals(List.of(OURS), world.turnedOffByRelease);
        assertSame(CrystalModule.METEOR, world.latch.latched());
        assertTrue(world.on.get(METEORS));
    }

    @Test
    void aLoadAtStartupIsNoChange() {
        // Module.fromTag loads the settings before it turns the module on: auto-pvp is still off, and the
        // activation that follows latches the setting and drops what was pending.
        World world = new World();
        world.running = false;
        world.load(CrystalModule.XPLOITS);
        world.running = true;
        world.activate(CrystalModule.XPLOITS);
        world.ticks(5);
        assertEquals(0, world.releases);
        assertSame(CrystalModule.XPLOITS, world.latch.latched());
        assertTrue(world.on.get(OURS));
    }

    @Test
    void movingToAnAuraAlreadyOnSaysItIsThePlayers() {
        World world = fightingWith(CrystalModule.METEOR);
        world.on.put(OURS, true); // the player turned crystal-aura++ on by hand

        world.set(CrystalModule.XPLOITS);
        world.tick();
        assertEquals(List.of(Msg.of(PvpText.CRYSTAL_AURA_ALREADY_ON, "module", "crystal-aura++")), world.warnings);
        assertEquals(Set.of(), world.ledger.owned(), "it is not taken: it is the player's, as at activation");
        assertFalse(world.on.get(METEORS));
        world.ticks(10);
        assertEquals(1, world.warnings.size(), "said once");
    }

    @Test
    void theAlreadyOnWarningNamesTheLatchedAura() {
        World world = new World();
        world.activate(CrystalModule.METEOR);
        assertEquals(Optional.of(Msg.of(PvpText.CRYSTAL_AURA_ALREADY_ON, "module", "crystal-aura")),
            world.latch.alreadyOn(true));
        assertEquals(Optional.empty(), world.latch.alreadyOn(false));
    }

    @Test
    void movingMeasuresTheMissingModulesAgain() {
        World world = fightingWith(CrystalModule.METEOR);
        world.registered.remove(OURS);
        assertEquals(List.of(), world.missing.names());

        world.set(CrystalModule.XPLOITS);
        world.tick();
        assertEquals(List.of(AURA), world.missing.names(), "the catalog's aura is now the one this build lacks");
    }

    @Test
    void theTwoAurasWarningIsSaidOncePerEpisode() {
        World world = new World();
        CrystalLatch latch = world.latch;
        world.activate(CrystalModule.METEOR);

        assertFalse(latch.otherAuraStarted(false));
        assertTrue(latch.otherAuraStarted(true), "said when it starts");
        assertFalse(latch.otherAuraStarted(true), "not while it lasts");
        assertFalse(latch.otherAuraStarted(true));
        assertFalse(latch.otherAuraStarted(false), "its end re-arms it");
        assertTrue(latch.otherAuraStarted(true), "a new episode is said again");

        world.activate(CrystalModule.METEOR);
        assertTrue(latch.otherAuraStarted(true), "an activation re-arms it too");

        // A move makes the other aura the other one: a player's Meteor aura left on beside a newly
        // driven crystal-aura++ is a new case to say, even right after crystal-aura++ was the one said.
        world.set(CrystalModule.XPLOITS);
        assertTrue(latch.settle());
        assertTrue(latch.otherAuraStarted(true), "a move re-arms it");
        world.set(CrystalModule.XPLOITS);
        assertFalse(latch.settle());
        assertFalse(latch.otherAuraStarted(true), "no move, no re-arm");
    }
}
