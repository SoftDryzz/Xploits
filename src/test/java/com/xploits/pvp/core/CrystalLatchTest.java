package com.xploits.pvp.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code crystal-module} latch (crystal-aura++ spec P5, Q4). The world here is auto-pvp's own loop,
 * reduced to what the order depends on: the real {@link ModuleLedger}, two aura modules that are on or
 * off, and the adapter's {@code apply} and {@code releaseAll} resolving the catalog's
 * {@code crystal-aura} through the latch, as {@code AutoPvp} does.
 */
class CrystalLatchTest {
    private static final String AURA = CrystalModule.LOGICAL;
    private static final String METEORS = "crystal-aura";
    private static final String OURS = "crystal-aura++";

    /** Auto-pvp's loop around the ledger, with the auras as plain switches. */
    private static final class World {
        final CrystalLatch latch = new CrystalLatch();
        final ModuleLedger ledger = new ModuleLedger();
        final Map<String, Boolean> on = new HashMap<>(Map.of(METEORS, false, OURS, false));
        final List<String> released = new ArrayList<>();
        int releases;

        /** One tick in a fight that wants the aura: AutoPvp.apply. */
        void tick() {
            Set<String> wanted = Set.of(AURA);
            Set<String> active = new LinkedHashSet<>();
            if (on.get(latch.resolve(AURA))) active.add(AURA);
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
            for (String name : ledger.owned()) on.put(latch.resolve(name), false);
            ledger.reset();
        }

        /** The setting's onChanged. */
        boolean changed(boolean running, CrystalModule value) {
            return latch.change(running, value, this::releaseAll);
        }
    }

    private static World fightingWith(CrystalModule module) {
        World world = new World();
        world.latch.latch(module);
        world.ticks(5);
        assertTrue(world.on.get(module.moduleName()), "auto-pvp took " + module.moduleName());
        assertFalse(world.on.get(module.other().moduleName()));
        assertEquals(Set.of(AURA), world.ledger.owned());
        return world;
    }

    @Test
    void aChangeReleasesTheOldAuraFirstAndThenDrivesTheNewOne() {
        World world = fightingWith(CrystalModule.METEOR);

        assertTrue(world.changed(true, CrystalModule.XPLOITS));
        assertSame(CrystalModule.XPLOITS, world.latch.latched());
        assertFalse(world.on.get(METEORS), "Meteor's aura is turned off at once, while it was still the latched one");

        for (int tick = 0; tick < 40; tick++) {
            world.tick();
            assertFalse(world.on.get(METEORS), "Meteor's aura stays off, tick " + tick);
            assertTrue(world.on.get(OURS), "crystal-aura++ is driven from the next tick on, tick " + tick);
        }
        assertEquals(List.of(), world.released, "the change is not taken for the player letting it go");
        assertEquals(Set.of(), world.ledger.released());
    }

    @Test
    void andBackAgain() {
        World world = fightingWith(CrystalModule.XPLOITS);

        assertTrue(world.changed(true, CrystalModule.METEOR));
        assertFalse(world.on.get(OURS), "crystal-aura++ is turned off at once");
        world.ticks(40);
        assertFalse(world.on.get(OURS));
        assertTrue(world.on.get(METEORS));
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
        world.latch.latch(CrystalModule.METEOR);

        assertFalse(world.changed(false, CrystalModule.XPLOITS));
        assertSame(CrystalModule.METEOR, world.latch.latched(), "the next activation latches the setting");
        assertEquals(0, world.releases);
    }

    @Test
    void theSameValueIsNoChange() {
        World world = fightingWith(CrystalModule.XPLOITS);

        assertFalse(world.changed(true, CrystalModule.XPLOITS));
        assertEquals(0, world.releases);
        assertTrue(world.on.get(OURS), "nothing was released");
        assertEquals(Set.of(AURA), world.ledger.owned());
    }

    /**
     * Meteor loading a saved value, call for call (meteor-client 1.21.11 sources): {@code Settings.fromTag}
     * resets every setting first ({@code Setting.reset}: default, {@code onChanged}), then
     * {@code Setting.fromTag} runs {@code EnumSetting.load} ({@code parse}: the saved value,
     * {@code onChanged}) and fires {@code onChanged} once more.
     */
    private static void load(World world, boolean running, CrystalModule saved) {
        world.changed(running, CrystalModule.METEOR);
        world.changed(running, saved);
        world.changed(running, saved);
    }

    @Test
    void aLoadWithTheLatchedValueIsNoChange() {
        World world = fightingWith(CrystalModule.METEOR);

        load(world, true, CrystalModule.METEOR);
        assertEquals(0, world.releases, "three onChanged calls with the latched value");
        assertTrue(world.on.get(METEORS));
        assertEquals(Set.of(AURA), world.ledger.owned());
    }

    @Test
    void aLoadAtStartupIsNoChange() {
        // Module.fromTag loads the settings before it turns the module on: auto-pvp is still off.
        World world = new World();
        load(world, false, CrystalModule.XPLOITS);
        assertEquals(0, world.releases);
        assertSame(CrystalModule.METEOR, world.latch.latched());
    }

    @Test
    void theTwoAurasWarningIsSaidOncePerEpisode() {
        CrystalLatch latch = new CrystalLatch();
        latch.latch(CrystalModule.METEOR);

        assertFalse(latch.otherAuraStarted(false));
        assertTrue(latch.otherAuraStarted(true), "said when it starts");
        assertFalse(latch.otherAuraStarted(true), "not while it lasts");
        assertFalse(latch.otherAuraStarted(true));
        assertFalse(latch.otherAuraStarted(false), "its end re-arms it");
        assertTrue(latch.otherAuraStarted(true), "a new episode is said again");

        latch.latch(CrystalModule.METEOR);
        assertTrue(latch.otherAuraStarted(true), "an activation re-arms it too");

        // A change makes the other aura the other one: a player's Meteor aura left on beside a newly
        // driven crystal-aura++ is a new case to say, even right after crystal-aura++ was the one said.
        assertTrue(latch.change(true, CrystalModule.XPLOITS, () -> { }));
        assertTrue(latch.otherAuraStarted(true), "a change re-arms it");
        assertFalse(latch.change(true, CrystalModule.XPLOITS, () -> { }));
        assertFalse(latch.otherAuraStarted(true), "no change, no re-arm");
    }
}
