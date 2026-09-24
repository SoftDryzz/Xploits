package com.xploits.pvp.recorder.core;

import com.xploits.console.core.CoordinateSentinel;
import com.xploits.pvp.core.CombatState;
import com.xploits.pvp.recorder.core.CombatEvent.AttackerKind;
import com.xploits.pvp.recorder.core.FightAnalysis.Cause;
import com.xploits.pvp.recorder.core.FightAnalysis.CauseKind;
import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.Msg;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.xploits.pvp.recorder.core.FightAnalysis.CauseKind.ANVIL;
import static com.xploits.pvp.recorder.core.FightAnalysis.CauseKind.ARMOR_BROKE;
import static com.xploits.pvp.recorder.core.FightAnalysis.CauseKind.BURST;
import static com.xploits.pvp.recorder.core.FightAnalysis.CauseKind.CHASING;
import static com.xploits.pvp.recorder.core.FightAnalysis.CauseKind.CRYSTAL_AURA_OFF;
import static com.xploits.pvp.recorder.core.FightAnalysis.CauseKind.CRYSTAL_OUTPACED;
import static com.xploits.pvp.recorder.core.FightAnalysis.CauseKind.DOUBLE_POP;
import static com.xploits.pvp.recorder.core.FightAnalysis.CauseKind.FALL;
import static com.xploits.pvp.recorder.core.FightAnalysis.CauseKind.GLIDING;
import static com.xploits.pvp.recorder.core.FightAnalysis.CauseKind.LOW_HEALTH_STAYED;
import static com.xploits.pvp.recorder.core.FightAnalysis.CauseKind.MELEE;
import static com.xploits.pvp.recorder.core.FightAnalysis.CauseKind.NO_TOTEMS;
import static com.xploits.pvp.recorder.core.FightAnalysis.CauseKind.OUTNUMBERED;
import static com.xploits.pvp.recorder.core.FightAnalysis.CauseKind.OUT_OF_CRYSTALS;
import static com.xploits.pvp.recorder.core.FightAnalysis.CauseKind.OUT_OF_OBSIDIAN;
import static com.xploits.pvp.recorder.core.FightAnalysis.CauseKind.OUT_OF_TOTEMS;
import static com.xploits.pvp.recorder.core.FightAnalysis.CauseKind.SELF_CRYSTAL;
import static com.xploits.pvp.recorder.core.FightAnalysis.CauseKind.TOTEMS_LEFT;
import static com.xploits.pvp.recorder.core.FightAnalysis.CauseKind.UNDEFENDED;
import static com.xploits.pvp.recorder.core.Fights.FOE;
import static com.xploits.pvp.recorder.core.Fights.OTHER_FOE;
import static com.xploits.pvp.recorder.core.Fights.lost;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The probable causes of a lost fight, each at its threshold and one step short of it (spec §FightAnalysis). */
class FightAnalysisTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });

    // --- thresholds -------------------------------------------------------------------------------------

    @Test
    void theThresholdsAreTheSpecs() {
        assertEquals(20, FightAnalysis.DOUBLE_POP_TICKS);
        assertEquals(0.25, FightAnalysis.SELF_CRYSTAL_SHARE);
        assertEquals(0.30, FightAnalysis.FALL_SHARE);
        assertEquals(35, FightAnalysis.FALL_SHARE_WEIGHT);
        assertEquals(12.0, FightAnalysis.BURST_HEALTH);
        assertEquals(2, FightAnalysis.OUTNUMBERED_PLAYERS);
        assertEquals(0.60, FightAnalysis.CRYSTAL_SHARE);
        assertEquals(0.50, FightAnalysis.CRYSTAL_OFFENSE_SECONDS);
        assertEquals(0.5, FightAnalysis.OUTPACED_RATIO);
        assertEquals(12.0, FightAnalysis.UNDEFENDED_MARGIN, "auto-pvp's threat margin");
        assertEquals(3, FightAnalysis.UNDEFENDED_SECONDS);
        assertEquals(5, FightAnalysis.OUT_OF_CRYSTALS_SECONDS);
        assertEquals(4, FightAnalysis.OBSIDIAN_MINIMUM, "surround's minimum");
        assertEquals(5, FightAnalysis.OUT_OF_OBSIDIAN_SECONDS);
        assertEquals(0.30, FightAnalysis.ANVIL_SHARE);
        assertEquals(0.50, FightAnalysis.MELEE_SHARE);
        assertEquals(8.0, FightAnalysis.LOW_HEALTH);
        assertEquals(8.0, FightAnalysis.LOW_HEALTH_RANGE);
        assertEquals(5, FightAnalysis.LOW_HEALTH_SECONDS);
    }

    @Test
    void theWeightsAreTheSpecs() {
        int[] weights = {100, 97, 95, 90, 85, 90, 80, 70, 65, 60, 55, 50, 45, 40, 40, 35, 30, 30, 25};
        CauseKind[] kinds = CauseKind.values();
        assertEquals(weights.length, kinds.length);
        for (int i = 0; i < kinds.length; i++) assertEquals(weights[i], kinds[i].weight(), kinds[i].name());
    }

    @Test
    void theBaseFightHasNoCause() {
        assertEquals(List.of(), FightAnalysis.causes(lost().build()));
    }

    // --- totems -----------------------------------------------------------------------------------------

    @Test
    void noTotemsFiresWithNoneAtTheStartAndNoPop() {
        assertEquals(Msg.of(RecorderText.CAUSE_NO_TOTEMS), cause(lost().totems(0, 0, false).build(), NO_TOTEMS).evidence());
        assertFalse(fires(lost().totems(1, 0, false).build(), NO_TOTEMS));
        // a totem picked up during the fight and popped
        assertFalse(fires(lost().totems(0, 0, false).pops(1).build(), NO_TOTEMS));
    }

    @Test
    void doublePopFiresWhenThePopWasTwentyTicksBeforeDeathAtMost() {
        FightRecord f = lost().totems(8, 5, false).pop(20).build();
        assertEquals(Msg.of(RecorderText.CAUSE_DOUBLE_POP, "seconds", 1.0, "totems", 5), cause(f, DOUBLE_POP).evidence());
        assertFalse(fires(f, TOTEMS_LEFT), "the pop just before explains the empty offhand");

        FightRecord later = lost().totems(8, 5, false).pop(21).build();
        assertFalse(fires(later, DOUBLE_POP));
        assertTrue(fires(later, TOTEMS_LEFT));
    }

    @Test
    void doublePopNeedsTotemsLeft() {
        FightRecord f = lost().totems(8, 0, false).pop(20).build();
        assertFalse(fires(f, DOUBLE_POP));
        assertTrue(fires(f, OUT_OF_TOTEMS));
        assertTrue(fires(lost().totems(8, 1, false).pop(20).build(), DOUBLE_POP));
    }

    @Test
    void aKillingBlowSpreadOverTwoTicksIsNotAPopWhenYouNeverPopped() {
        FightRecord f = lost().totems(8, 5, false)
            .killedBy(DamageKind.CRYSTAL, FOE, 7)
            .killingHit(1, DamageKind.CRYSTAL, AttackerKind.PLAYER, FOE, 7)
            .build();
        assertFalse(fires(f, DOUBLE_POP));
        assertTrue(fires(f, TOTEMS_LEFT));
        // documented bias: only the last tick of the blow counts, 7 of the 14
        assertFalse(fires(f, BURST));
    }

    @Test
    void totemsLeftFiresWithOneLeftAndNoneInTheOffhand() {
        assertEquals(Msg.of(RecorderText.CAUSE_TOTEMS_LEFT, "totems", 1),
            cause(lost().totems(8, 1, false).build(), TOTEMS_LEFT).evidence());
        assertFalse(fires(lost().totems(8, 0, false).build(), TOTEMS_LEFT));
        assertFalse(fires(lost().totems(8, 1, true).build(), TOTEMS_LEFT));
    }

    @Test
    void outOfTotemsFiresWithNoneLeftAfterAPop() {
        assertEquals(Msg.of(RecorderText.CAUSE_OUT_OF_TOTEMS, "pops", 1),
            cause(lost().totems(8, 0, false).pops(1).build(), OUT_OF_TOTEMS).evidence());
        assertFalse(fires(lost().totems(8, 0, false).build(), OUT_OF_TOTEMS));
        assertFalse(fires(lost().totems(8, 1, true).pops(1).build(), OUT_OF_TOTEMS));
    }

    // --- what hurt you ----------------------------------------------------------------------------------

    @Test
    void selfCrystalFiresAtAQuarterOfTheDamage() {
        FightRecord f = mix(lost().ownHit(DamageKind.CRYSTAL, 10).ownHit(DamageKind.CRYSTAL, 10).ownHit(DamageKind.CRYSTAL, 5),
            DamageKind.PROJECTILE, 75).build();
        assertEquals(Msg.of(RecorderText.CAUSE_SELF_CRYSTAL, "percent", 25, "blow", RecorderText.NOTHING),
            cause(f, SELF_CRYSTAL).evidence());

        FightRecord below = mix(lost().ownHit(DamageKind.CRYSTAL, 10).ownHit(DamageKind.CRYSTAL, 10).ownHit(DamageKind.CRYSTAL, 4),
            DamageKind.PROJECTILE, 76).build();
        assertFalse(fires(below, SELF_CRYSTAL));
    }

    @Test
    void selfCrystalFiresWhenYourOwnCrystalKilledYou() {
        FightRecord f = mix(lost().killedByOwn(DamageKind.CRYSTAL, 4), DamageKind.PROJECTILE, 96).build();
        assertEquals(Msg.of(RecorderText.CAUSE_SELF_CRYSTAL, "percent", 4, "blow", RecorderText.CAUSE_KILLING_BLOW),
            cause(f, SELF_CRYSTAL).evidence());
        assertFalse(fires(mix(lost().killedBy(DamageKind.CRYSTAL, FOE, 4), DamageKind.PROJECTILE, 96).build(), SELF_CRYSTAL));
    }

    @Test
    void aFallThatKilledYouWeighsNinety() {
        Cause fall = cause(lost().killedUnattributed(DamageKind.FALL, 4).build(), FALL);
        assertEquals(90, fall.weight());
        assertEquals(Msg.of(RecorderText.CAUSE_FALL, "percent", 100, "blow", RecorderText.CAUSE_KILLING_BLOW), fall.evidence());
    }

    @Test
    void fallsWeighThirtyFiveFromThirtyPercentOfTheDamage() {
        Cause fall = cause(mix(unattributed(lost(), DamageKind.FALL, 30), DamageKind.PROJECTILE, 70).build(), FALL);
        assertEquals(35, fall.weight());
        assertEquals(Msg.of(RecorderText.CAUSE_FALL, "percent", 30, "blow", RecorderText.NOTHING), fall.evidence());
        assertFalse(fires(mix(unattributed(lost(), DamageKind.FALL, 29), DamageKind.PROJECTILE, 71).build(), FALL));
    }

    @Test
    void burstFiresWhenTheKillingBlowTookTwelveHealth() {
        assertEquals(Msg.of(RecorderText.CAUSE_BURST, "health", 12.0),
            cause(lost().killedBy(DamageKind.CRYSTAL, FOE, 12).build(), BURST).evidence());
        assertFalse(fires(lost().killedBy(DamageKind.CRYSTAL, FOE, 11.9).build(), BURST));
    }

    @Test
    void burstAddsUpTheHitsThatLandedTogether() {
        FightRecord f = lost().killedBy(DamageKind.CRYSTAL, FOE, 6).killedBy(DamageKind.CRYSTAL, OTHER_FOE, 6).build();
        assertEquals(Msg.of(RecorderText.CAUSE_BURST, "health", 12.0), cause(f, BURST).evidence());
    }

    @Test
    void outnumberedFiresWithTwoPlayersHittingYou() {
        FightRecord f = lost().hit(DamageKind.PROJECTILE, FOE, 5).hit(DamageKind.PROJECTILE, OTHER_FOE, 5).build();
        assertEquals(Msg.of(RecorderText.CAUSE_OUTNUMBERED, "attackers", 2, "near", 1), cause(f, OUTNUMBERED).evidence());
        // an opponent who never hit you (you hit them, or auto-pvp targeted them) is not an attacker
        assertFalse(fires(lost().hit(DamageKind.PROJECTILE, FOE, 5).opponent(OTHER_FOE).build(), OUTNUMBERED));
    }

    @Test
    void outnumberedFiresWithTwoHostilesCloseAtOnce() {
        assertEquals(Msg.of(RecorderText.CAUSE_OUTNUMBERED, "attackers", 0, "near", 2),
            cause(lost().hostilesNear(2).build(), OUTNUMBERED).evidence());
        assertFalse(fires(lost().hostilesNear(1).build(), OUTNUMBERED));
    }

    @Test
    void crystalAuraOffFiresBelowHalfTheFight() {
        // crystal-aura only from second 11: on 9 of 20 seconds
        FightRecord f = crystals(60).modules("auto-totem", "surround").moduleChange(11, "crystal-aura", true).build();
        assertEquals(Msg.of(RecorderText.CAUSE_CRYSTAL_AURA_OFF, "percent", 60, "active", 9, "seconds", 20),
            cause(f, CRYSTAL_AURA_OFF).evidence());

        FightRecord half = crystals(60).modules("auto-totem", "surround").moduleChange(10, "crystal-aura", true).build();
        assertFalse(fires(half, CRYSTAL_AURA_OFF));
    }

    @Test
    void anAnchorOrBedAuraCountsAsCrystalOffense() {
        assertFalse(fires(crystals(60).modules("auto-totem", "surround", "anchor-aura").build(), CRYSTAL_AURA_OFF));
        assertTrue(fires(crystals(60).modules("auto-totem", "surround", "kill-aura").build(), CRYSTAL_AURA_OFF));
    }

    @Test
    void crystalAuraOffNeedsSixtyPercentOfTheDamageFromCrystals() {
        assertFalse(fires(crystals(59).modules("auto-totem", "surround").build(), CRYSTAL_AURA_OFF));
    }

    @Test
    void crystalOutpacedFiresWhenYouPlacedUnderHalfTheirCrystals() {
        FightRecord f = crystals(60).crystals(9, 20).build();
        assertEquals(Msg.of(RecorderText.CAUSE_CRYSTAL_OUTPACED, "percent", 60, "placed", 9, "enemy", 20),
            cause(f, CRYSTAL_OUTPACED).evidence());
        assertFalse(fires(crystals(60).crystals(10, 20).build(), CRYSTAL_OUTPACED));
        assertFalse(fires(crystals(59).crystals(9, 20).build(), CRYSTAL_OUTPACED));
    }

    @Test
    void anvilFiresFromThirtyPercentOfTheDamage() {
        assertEquals(Msg.of(RecorderText.CAUSE_ANVIL, "percent", 30),
            cause(mix(unattributed(lost(), DamageKind.ANVIL, 30), DamageKind.PROJECTILE, 70).build(), ANVIL).evidence());
        assertFalse(fires(mix(unattributed(lost(), DamageKind.ANVIL, 29), DamageKind.PROJECTILE, 71).build(), ANVIL));
    }

    @Test
    void meleeFiresFromHalfTheDamageAndNamesWhoDealtMost() {
        FightRecord f = mix(lost().hits(3, DamageKind.MELEE, FOE, 10).hits(2, DamageKind.MELEE, OTHER_FOE, 10),
            DamageKind.PROJECTILE, 50).build();
        assertEquals(Msg.of(RecorderText.CAUSE_MELEE, "percent", 50, "attacker", FOE), cause(f, MELEE).evidence());

        FightRecord below = mix(lost().hits(4, DamageKind.MELEE, FOE, 10).hit(DamageKind.MELEE, FOE, 9),
            DamageKind.PROJECTILE, 51).build();
        assertFalse(fires(below, MELEE));
    }

    // --- what you did -----------------------------------------------------------------------------------

    @Test
    void undefendedFiresWithThreeSecondsUnderTheMarginOutOfAHoleWithNoDefense() {
        FightRecord f = lost().modules("auto-totem", "crystal-aura").samples(0, 3, FightAnalysisTest::exposed).build();
        assertEquals(Msg.of(RecorderText.CAUSE_UNDEFENDED, "seconds", 3, "margin", 12.0), cause(f, UNDEFENDED).evidence());
        assertFalse(fires(lost().modules("auto-totem", "crystal-aura").samples(0, 2, FightAnalysisTest::exposed).build(), UNDEFENDED));
    }

    @Test
    void undefendedCountsOnlyUnderTheMargin() {
        FightRecord atMargin = lost().modules("auto-totem", "crystal-aura").samples(0, 3, row -> {
            exposed(row);
            row.incoming = 8;
        }).build();
        assertFalse(fires(atMargin, UNDEFENDED));
    }

    @Test
    void undefendedDoesNotCountSecondsInAHoleOrWithADefenseOn() {
        FightRecord inHole = lost().modules("auto-totem", "crystal-aura").samples(0, 3, row -> {
            exposed(row);
            row.inHole = true;
        }).build();
        assertFalse(fires(inHole, UNDEFENDED));
        // surround is on in the base fight
        assertFalse(fires(lost().samples(0, 3, FightAnalysisTest::exposed).build(), UNDEFENDED));
        // surround off from second 5: seconds 5-7 count, second 4 does not
        assertTrue(fires(lost().moduleChange(5, "surround", false).samples(5, 8, FightAnalysisTest::exposed).build(), UNDEFENDED));
        assertFalse(fires(lost().moduleChange(5, "surround", false).samples(4, 7, FightAnalysisTest::exposed).build(), UNDEFENDED));
    }

    @Test
    void outOfCrystalsFiresWithFiveSecondsInARowWithNoneAndAnEnemyInRange() {
        assertEquals(Msg.of(RecorderText.CAUSE_OUT_OF_CRYSTALS, "seconds", 5),
            cause(lost().samples(0, 5, row -> row.crystals = 0).build(), OUT_OF_CRYSTALS).evidence());
        assertFalse(fires(lost().samples(0, 4, row -> row.crystals = 0).build(), OUT_OF_CRYSTALS));
        assertFalse(fires(lost().samples(0, 4, row -> row.crystals = 0).samples(5, 6, row -> row.crystals = 0).build(),
            OUT_OF_CRYSTALS), "four and one are not five in a row");
        assertFalse(fires(lost().samples(0, 5, row -> {
            row.crystals = 0;
            row.nearestHostile = null;
        }).build(), OUT_OF_CRYSTALS));
    }

    @Test
    void outOfObsidianFiresWithFiveSecondsUnderSurroundsMinimum() {
        assertEquals(Msg.of(RecorderText.CAUSE_OUT_OF_OBSIDIAN, "seconds", 5, "minimum", 4),
            cause(lost().samples(0, 5, row -> row.obsidian = 3).build(), OUT_OF_OBSIDIAN).evidence());
        assertFalse(fires(lost().samples(0, 4, row -> row.obsidian = 3).build(), OUT_OF_OBSIDIAN));
        assertFalse(fires(lost().samples(0, 5, row -> row.obsidian = 4).build(), OUT_OF_OBSIDIAN));
        assertTrue(fires(lost().samples(0, 3, row -> row.obsidian = 3).samples(10, 12, row -> row.obsidian = 0).build(),
            OUT_OF_OBSIDIAN), "not in a row");
    }

    @Test
    void armorBrokeFiresWhenYouEndedWithLessThanYouStarted() {
        assertEquals(Msg.of(RecorderText.CAUSE_ARMOR_BROKE, "start", 4, "end", 3),
            cause(lost().lastSample(row -> row.armor = 3).build(), ARMOR_BROKE).evidence());
        assertFalse(fires(lost().samples(5, 10, row -> row.armor = 2).build(), ARMOR_BROKE), "put back on");
    }

    @Test
    void glidingFiresOnlyForTheLastSecond() {
        assertEquals(Msg.of(RecorderText.CAUSE_GLIDING), cause(lost().lastSample(row -> row.gliding = true).build(), GLIDING).evidence());
        assertFalse(fires(lost().samples(18, 19, row -> row.gliding = true).build(), GLIDING));
    }

    @Test
    void chasingFiresWhenAutoPvpsLastPhaseWasChase() {
        FightRecord f = lost().phase(0, CombatState.SURFACE, FOE).phase(10, CombatState.CHASE, FOE).build();
        assertEquals(Msg.of(RecorderText.CAUSE_CHASING, "target", FOE), cause(f, CHASING).evidence());
        assertFalse(fires(lost().phase(10, CombatState.CHASE, FOE).phase(15, CombatState.SURFACE, FOE).build(), CHASING));
        assertEquals(Msg.of(RecorderText.CAUSE_CHASING, "target", RecorderText.NOBODY),
            cause(lost().phase(10, CombatState.CHASE, null).build(), CHASING).evidence());
    }

    @Test
    void lowHealthStayedFiresWithFiveSecondsInARowAtEightOrLessWithAnEnemyWithinEight() {
        assertEquals(Msg.of(RecorderText.CAUSE_LOW_HEALTH_STAYED, "seconds", 5, "health", 8.0, "range", 8.0),
            cause(lost().samples(0, 5, FightAnalysisTest::lowAndClose).build(), LOW_HEALTH_STAYED).evidence());
        assertFalse(fires(lost().samples(0, 4, FightAnalysisTest::lowAndClose).build(), LOW_HEALTH_STAYED));
        assertFalse(fires(lost().samples(0, 5, row -> {
            lowAndClose(row);
            row.health = 8.1;
        }).build(), LOW_HEALTH_STAYED));
        assertFalse(fires(lost().samples(0, 5, row -> {
            lowAndClose(row);
            row.nearestHostile = 8.1;
        }).build(), LOW_HEALTH_STAYED));
        assertFalse(fires(lost().samples(0, 5, row -> {
            lowAndClose(row);
            row.nearestHostile = null;
        }).build(), LOW_HEALTH_STAYED));
        assertFalse(fires(lost().samples(0, 5, row -> {
            lowAndClose(row);
            row.inHole = true;
        }).build(), LOW_HEALTH_STAYED));
    }

    // --- ranking and the main cause ---------------------------------------------------------------------

    @Test
    void causesAreRankedByWeight() {
        List<Cause> causes = FightAnalysis.causes(Fights.crystalDeath());
        assertEquals(List.of(OUT_OF_TOTEMS, CRYSTAL_OUTPACED, UNDEFENDED, ARMOR_BROKE), kinds(causes));
        assertEquals(List.of(90, 60, 55, 40), causes.stream().map(Cause::weight).toList());
    }

    @Test
    void equalWeightsFollowTheEnumOrder() {
        FightRecord f = lost().totems(8, 0, false).pops(1).killedUnattributed(DamageKind.FALL, 4)
            .lastSample(row -> row.gliding = true).phase(0, CombatState.CHASE, FOE).build();
        assertEquals(List.of(OUT_OF_TOTEMS, FALL, GLIDING, CHASING), kinds(FightAnalysis.causes(f)));
    }

    @Test
    void aFallThatOnlyReachedItsShareRanksByItsLowerWeight() {
        FightRecord f = mix(unattributed(unattributed(lost(), DamageKind.FALL, 30), DamageKind.ANVIL, 30), DamageKind.PROJECTILE, 40)
            .lastSample(row -> {
                row.gliding = true;
                row.armor = 3;
            }).build();
        assertEquals(List.of(ANVIL, ARMOR_BROKE, FALL, GLIDING), kinds(FightAnalysis.causes(f)));
    }

    @Test
    void onlyALostFightHasCauses() {
        for (FightOutcome outcome : List.of(FightOutcome.WON, FightOutcome.ENDED, FightOutcome.ABORTED)) {
            FightRecord f = Fights.ending(outcome).totems(0, 0, false).hostilesNear(3)
                .lastSample(row -> row.gliding = true).build();
            assertEquals(List.of(), FightAnalysis.causes(f), outcome.name());
            assertEquals(RecorderText.CAUSE_UNCLEAR, FightAnalysis.mainCause(f).key(), outcome.name());
        }
    }

    @Test
    void theMainCauseIsTheFirstOne() {
        assertEquals(Msg.of(RecorderText.CAUSE_OUT_OF_TOTEMS, "pops", 3), FightAnalysis.mainCause(Fights.crystalDeath()));
        assertEquals("Out of totems after 3 pops", EN.render(FightAnalysis.mainCause(Fights.crystalDeath())));
    }

    @Test
    void withNoCauseTheMainCauseNamesTheTopKnownSource() {
        FightRecord f = lost().hits(4, DamageKind.CRYSTAL, FOE, 10).hits(3, DamageKind.MELEE, FOE, 10)
            .hits(3, DamageKind.PROJECTILE, FOE, 10).unattributedHit(DamageKind.UNSEEN, 45).build();
        assertEquals(List.of(), FightAnalysis.causes(f));
        Msg main = FightAnalysis.mainCause(f);
        assertEquals(Msg.of(RecorderText.CAUSE_UNCLEAR, "source",
            Msg.of(RecorderText.CAUSE_TOP_SOURCE, "kind", RecorderText.KIND_CRYSTAL, "percent", 40)), main);
        assertEquals("No clear cause · most damage: crystal (40%)", EN.render(main));
        assertEquals("Sin causa clara · la mayor parte del daño: cristal (40%)", ES.render(main));
    }

    @Test
    void withNoKnownDamageTheMainCauseIsJustUnclear() {
        Msg main = FightAnalysis.mainCause(lost().build());
        assertEquals(Msg.of(RecorderText.CAUSE_UNCLEAR, "source", RecorderText.NOTHING), main);
        assertEquals("No clear cause", EN.render(main));
    }

    // --- damage split and modules -----------------------------------------------------------------------

    @Test
    void theShareLeavesUnseenDamageOut() {
        FightRecord f = lost().hit(DamageKind.CRYSTAL, FOE, 10).hit(DamageKind.MELEE, FOE, 30)
            .unattributedHit(DamageKind.UNSEEN, 60).build();
        assertEquals(0.25, FightAnalysis.share(f, DamageKind.CRYSTAL));
        assertEquals(0.75, FightAnalysis.share(f, DamageKind.MELEE));
        assertEquals(0.0, FightAnalysis.share(f, DamageKind.UNSEEN));
        assertEquals(0.0, FightAnalysis.share(f, DamageKind.FALL));
        // the unseen killing blow of the base fight adds its 4
        assertEquals(Map.of(DamageKind.CRYSTAL, 10.0, DamageKind.MELEE, 30.0, DamageKind.UNSEEN, 64.0), FightAnalysis.damageByKind(f));
        assertEquals(0.0, FightAnalysis.share(lost().build(), DamageKind.CRYSTAL), "nothing known: no share, no division by zero");
    }

    @Test
    void aModuleIsActiveAsItsLastChangeLeftIt() {
        FightRecord f = lost().moduleChange(5, "crystal-aura", false).moduleChange(12, "crystal-aura", true)
            .moduleChange(3, "hole-filler", true).build();
        assertTrue(FightAnalysis.activeAt(f, "crystal-aura", 4));
        assertFalse(FightAnalysis.activeAt(f, "crystal-aura", 5));
        assertFalse(FightAnalysis.activeAt(f, "crystal-aura", 11));
        assertTrue(FightAnalysis.activeAt(f, "crystal-aura", 12));
        assertFalse(FightAnalysis.activeAt(f, "hole-filler", 2));
        assertTrue(FightAnalysis.activeAt(f, "hole-filler", 3));
        assertFalse(FightAnalysis.activeAt(f, "kill-aura", 0));

        assertEquals(13, FightAnalysis.secondsActive(f, ModuleRole.CRYSTAL_OFFENSE));
        assertEquals(20, FightAnalysis.secondsActive(f, ModuleRole.DEFENSE));
        assertEquals(0, FightAnalysis.secondsActive(f, ModuleRole.MELEE_OFFENSE));
    }

    // --- texts ------------------------------------------------------------------------------------------

    @Test
    void everyCauseRendersInBothLanguagesWithoutLookingLikeCoordinates() {
        List<FightRecord> fights = List.of(
            lost().totems(0, 0, false).build(),
            lost().totems(8, 3, false).pop(10).build(),
            lost().totems(8, 3, false).build(),
            Fights.crystalDeath(),
            mix(lost().killedByOwn(DamageKind.CRYSTAL, 13), DamageKind.MELEE, 60).hostilesNear(3).build(),
            lost().killedUnattributed(DamageKind.FALL, 4).build(),
            crystals(70).modules("auto-totem").build(),
            mix(unattributed(lost(), DamageKind.ANVIL, 40), DamageKind.PROJECTILE, 60)
                .samples(0, 6, row -> {
                    row.crystals = 0;
                    row.obsidian = 1;
                })
                .samples(10, 16, FightAnalysisTest::lowAndClose)
                .lastSample(row -> row.gliding = true)
                .phase(2, CombatState.CHASE, FOE).build());

        Set<CauseKind> seen = EnumSet.noneOf(CauseKind.class);
        for (FightRecord f : fights) {
            for (Cause c : FightAnalysis.causes(f)) {
                seen.add(c.kind());
                assertEquals(c.kind().key(), c.evidence().key());
                for (Catalog catalog : List.of(ES, EN)) {
                    String line = catalog.render(c.evidence());
                    assertFalse(line.contains("‹"), line);
                    assertFalse(CoordinateSentinel.isSuspect(line), line);
                }
            }
        }
        assertEquals(EnumSet.allOf(CauseKind.class), seen);
    }

    // --- helpers ----------------------------------------------------------------------------------------

    private static boolean fires(FightRecord f, CauseKind kind) {
        return find(f, kind).isPresent();
    }

    private static Cause cause(FightRecord f, CauseKind kind) {
        return find(f, kind).orElseThrow(() -> new AssertionError(kind + " did not fire: " + kinds(FightAnalysis.causes(f))));
    }

    private static Optional<Cause> find(FightRecord f, CauseKind kind) {
        return FightAnalysis.causes(f).stream().filter(c -> c.kind() == kind).findFirst();
    }

    private static List<CauseKind> kinds(List<Cause> causes) {
        return causes.stream().map(Cause::kind).toList();
    }

    /** A lost fight where crystals by {@link Fights#FOE} dealt {@code percent} of 100 known damage, projectiles the rest. */
    private static Fights.Builder crystals(int percent) {
        Fights.Builder b = lost();
        int left = percent;
        while (left > 0) {
            int chunk = Math.min(10, left);
            b.hit(DamageKind.CRYSTAL, FOE, chunk);
            left -= chunk;
        }
        return mix(b, DamageKind.PROJECTILE, 100 - percent);
    }

    /** Adds {@code amount} of {@code kind} by {@link Fights#FOE}, in hits of 10 or less. */
    private static Fights.Builder mix(Fights.Builder b, DamageKind kind, int amount) {
        int left = amount;
        while (left > 0) {
            int chunk = Math.min(10, left);
            b.hit(kind, FOE, chunk);
            left -= chunk;
        }
        return b;
    }

    /** Adds {@code amount} of {@code kind} with no attacker, in hits of 10 or less. */
    private static Fights.Builder unattributed(Fights.Builder b, DamageKind kind, int amount) {
        int left = amount;
        while (left > 0) {
            int chunk = Math.min(10, left);
            b.unattributedHit(kind, chunk);
            left -= chunk;
        }
        return b;
    }

    /** Out of a hole at 20 health with 8.5 aimed at you: 11.5 left, under the margin. */
    private static void exposed(Fights.Row row) {
        row.inHole = false;
        row.incoming = 8.5;
    }

    private static void lowAndClose(Fights.Row row) {
        row.health = 8;
        row.inHole = false;
        row.nearestHostile = 8.0;
    }
}
