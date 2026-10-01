package com.xploits.printer.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.xploits.printer.core.PaceRules.Kind.CLICK_SLOT;
import static com.xploits.printer.core.PaceRules.Kind.DIG_ABORT;
import static com.xploits.printer.core.PaceRules.Kind.DIG_START;
import static com.xploits.printer.core.PaceRules.Kind.DIG_STOP;
import static com.xploits.printer.core.PaceRules.Kind.INTERACT_ENTITY;
import static com.xploits.printer.core.PaceRules.Kind.PLACE;
import static com.xploits.printer.core.PaceRules.Kind.SWING;
import static com.xploits.printer.core.PaceRules.Kind.TICK_END;
import static com.xploits.printer.core.PaceRules.Rule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Printer spec §5.3 (a) and the §10 rules check; spikes S4–S6. */
class PaceRulesTest {
    private static PaceRules.Packet ours(PaceRules.Kind k) {
        return PaceRules.Packet.of(k, true);
    }

    private static PaceRules.Packet theirs(PaceRules.Kind k) {
        return PaceRules.Packet.of(k, false);
    }

    private static final PaceRules.Packet MOVE = PaceRules.Packet.move(false, 0f, 0f);
    private static final PaceRules.Packet END = PaceRules.Packet.of(TICK_END, false);

    private static PaceRules run(PaceRules.Packet... packets) {
        PaceRules r = new PaceRules(PrinterLimits.DEFAULTS);
        for (PaceRules.Packet p : packets) r.accept(p);
        return r;
    }

    private static List<Rule> rules(PaceRules r) {
        return r.violations().stream().map(PaceRules.Violation::rule).toList();
    }

    @Test
    void aPlaceWithItsSwingBeforeTheMoveIsClean() {
        assertEquals(List.of(), rules(run(ours(PLACE), ours(SWING), MOVE, END)));
    }

    @Test
    void twoMovementPacketsInOneTickFlag() {
        PaceRules r = run(MOVE, PaceRules.Packet.move(true, 10f, 20f), END);
        assertEquals(List.of(new PaceRules.Violation(Rule.MOVES_IN_A_TICK, 0)), r.violations());
        assertEquals(List.of(), rules(run(MOVE, END, MOVE, END)));
    }

    @Test
    void aPlaceAndADigInOneGrimTickFlagTwice() {
        assertEquals(List.of(Rule.ACTIONS_IN_A_GRIM_TICK, Rule.PLACE_AND_DIG),
            rules(run(ours(PLACE), ours(SWING), ours(DIG_START), MOVE, END)));
    }

    @Test
    void anAbortAndAPlaceInOneGrimTickFlag() {
        assertEquals(List.of(Rule.PLACE_AND_DIG), rules(run(ours(DIG_ABORT), ours(PLACE), ours(SWING), MOVE, END)));
    }

    @Test
    void anActionAfterTheMoveSharesTheNextTicksGrimTick() {
        // Tick 0: a combat module's callback places after the movement packet (Meteor's Rotations post handler).
        // Tick 1: the printer places at TickEvent.Pre: both land in the same Grim tick.
        PaceRules r = run(MOVE, theirs(PLACE), theirs(SWING), END, ours(PLACE), ours(SWING), MOVE, END);
        assertEquals(List.of(new PaceRules.Violation(Rule.ACTION_AFTER_MOVE, 0),
            new PaceRules.Violation(Rule.ACTIONS_IN_A_GRIM_TICK, 1)), r.violations());
    }

    @Test
    void aTickWithoutMovementClosesItsGrimTickAtTheTickEnd() {
        assertEquals(List.of(), rules(run(ours(PLACE), ours(SWING), END, ours(PLACE), ours(SWING), MOVE, END)));
    }

    @Test
    void aPlaceNeedsItsSwing() {
        assertEquals(List.of(Rule.NO_SWING), rules(run(ours(PLACE), MOVE, END)));
    }

    @Test
    void theSlotGoesBeforeTheClickNeverAfter() {
        assertEquals(List.of(), rules(run(PaceRules.Packet.slot(3, true), ours(PLACE), ours(SWING), MOVE, END)));
        assertEquals(List.of(Rule.SLOT_AFTER_ACTION),
            rules(run(ours(PLACE), ours(SWING), PaceRules.Packet.slot(3, true), MOVE, END)));
        assertEquals(List.of(Rule.SLOT_AFTER_ACTION),
            rules(run(PaceRules.Packet.sprint(true), PaceRules.Packet.slot(3, true), MOVE, END)));
    }

    @Test
    void theSameSlotTwiceFlags() {
        assertEquals(List.of(Rule.SAME_SLOT_TWICE), rules(run(PaceRules.Packet.slot(3, true), MOVE, END,
            PaceRules.Packet.slot(3, true), MOVE, END)));
    }

    @Test
    void aDigKeepsItsSlotAndSwingsEveryTick() {
        assertEquals(List.of(), rules(run(ours(DIG_START), ours(SWING), MOVE, END, ours(SWING), MOVE, END,
            ours(DIG_STOP), ours(SWING), MOVE, END)));
        assertEquals(List.of(Rule.SLOT_DURING_DIG), rules(run(ours(DIG_START), ours(SWING), MOVE, END,
            PaceRules.Packet.slot(2, true), ours(SWING), MOVE, END)));
        PaceRules missing = run(ours(DIG_START), ours(SWING), MOVE, END, MOVE, END);
        assertEquals(List.of(new PaceRules.Violation(Rule.DIG_TICK_WITHOUT_SWING, 1)), missing.violations());
        assertEquals(List.of(), rules(run(ours(DIG_START), ours(SWING), MOVE, END, ours(DIG_ABORT), MOVE, END)),
            "an abort tick needs no swing, as vanilla's cancelBlockBreaking sends none");
    }

    @Test
    void theNextStartWaitsSixTicks() {
        // STOP in tick 0; START in tick 5 is too early, in tick 6 it is not.
        PaceRules early = run(ours(DIG_START), ours(SWING), ours(DIG_STOP), END, END, END, END, END, ours(DIG_START), ours(SWING), END);
        assertEquals(List.of(Rule.ACTIONS_IN_A_GRIM_TICK, Rule.DIG_GAP), rules(early));
        PaceRules onTime = run(ours(DIG_STOP), ours(SWING), END, END, END, END, END, END, ours(DIG_START), ours(SWING), END);
        assertEquals(List.of(), rules(onTime));
    }

    @Test
    void inventoryClicksOnlyWhileStill() {
        assertEquals(List.of(Rule.CLICK_WHILE_MOVING), rules(run(PaceRules.Packet.input(true), ours(CLICK_SLOT))));
        assertEquals(List.of(), rules(run(PaceRules.Packet.input(true), PaceRules.Packet.input(false), ours(CLICK_SLOT))));
        assertEquals(List.of(Rule.CLICK_WHILE_MOVING), rules(run(PaceRules.Packet.sprint(true), ours(CLICK_SLOT))));
        assertEquals(List.of(), rules(run(PaceRules.Packet.sprint(true), PaceRules.Packet.sprint(false), ours(CLICK_SLOT))));
    }

    @Test
    void theAimIsHeldByTheLastRotationReceived() {
        PaceRules r = run(PaceRules.Packet.move(true, 10f, 20f), END);
        assertTrue(r.aimHeld(new Aim.Rotation(10f, 20f)));
        assertFalse(r.aimHeld(new Aim.Rotation(10f, 20.5f)));
        r.accept(MOVE);
        r.accept(END);
        assertTrue(r.aimHeld(new Aim.Rotation(10f, 20f)), "no rotation sent: the server keeps the last one");
        r.accept(END);
        assertTrue(r.aimHeld(new Aim.Rotation(10f, 20f)), "no packet at all: same");
        r.accept(PaceRules.Packet.move(true, 10f, 20f));
        r.accept(PaceRules.Packet.move(true, 30f, 40f));
        r.accept(END);
        assertFalse(r.aimHeld(new Aim.Rotation(30f, 40f)), "two rotation packets in one tick never hold an aim");
        assertEquals(Optional.of(new Aim.Rotation(30f, 40f)), r.lastRotation());
        assertEquals(Optional.empty(), new PaceRules(PrinterLimits.DEFAULTS).lastRotation());
        assertFalse(new PaceRules(PrinterLimits.DEFAULTS).aimHeld(new Aim.Rotation(0f, 0f)), "unknown is not held");
    }

    @Test
    void someoneElsesActionIsSeenForOneTickAndWithinItsGrimTick() {
        PaceRules r = run(theirs(INTERACT_ENTITY), END);
        assertTrue(r.foreignActionLastTick());
        r.accept(END);
        assertFalse(r.foreignActionLastTick());
        assertFalse(run(ours(PLACE), ours(SWING), END).foreignActionLastTick(), "our own sends are not someone else's");
        PaceRules g = run(MOVE, theirs(PLACE));
        assertTrue(g.foreignActionThisGrimTick());
        assertFalse(g.slotChangeAllowed());
        g.accept(END);
        assertTrue(g.foreignActionThisGrimTick(), "still the same Grim tick until the next movement packet");
        g.accept(MOVE);
        assertFalse(g.foreignActionThisGrimTick());
        assertTrue(g.slotChangeAllowed());
    }

    @Test
    void stillnessIsWhatTheServerLastHeard() {
        PaceRules r = new PaceRules(PrinterLimits.DEFAULTS);
        assertTrue(r.stillAsServerKnows());
        r.accept(PaceRules.Packet.input(true));
        assertFalse(r.stillAsServerKnows());
        r.accept(PaceRules.Packet.input(false));
        assertTrue(r.stillAsServerKnows());
        r.accept(PaceRules.Packet.sprint(true));
        assertFalse(r.stillAsServerKnows());
    }

    @Test
    void ourPlacesAndDigsAreCountedAsTheyLeave() {
        PaceRules r = run(ours(PLACE), ours(SWING), theirs(PLACE), ours(DIG_START), ours(DIG_STOP), ours(DIG_ABORT));
        assertEquals(3, r.oursSent());
    }

    @Test
    void aWatchThatKeepsNoViolationsStillJudgesTheAim() {
        PaceRules r = new PaceRules(PrinterLimits.DEFAULTS, false);
        r.accept(MOVE);
        r.accept(PaceRules.Packet.move(true, 1f, 2f));
        r.accept(END);
        assertEquals(List.of(), r.violations());
        assertFalse(r.aimHeld(new Aim.Rotation(1f, 2f)), "two movement packets: not held, recorded or not");
        r.accept(END);
        assertTrue(r.aimHeld(new Aim.Rotation(1f, 2f)));
    }

    @Test
    void ticksCountTheTickEnds() {
        assertEquals(3, run(END, END, END).tick());
        PaceRules r = run(MOVE, MOVE, END);
        r.reset();
        assertEquals(0, r.tick());
        assertEquals(List.of(), r.violations());
        assertEquals(Optional.empty(), r.lastRotation());
    }
}
