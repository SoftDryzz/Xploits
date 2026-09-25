package com.xploits.pvp.recorder.core;

import com.xploits.console.core.CoordinateSentinel;
import com.xploits.pvp.core.CombatState;
import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.Msg;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.xploits.pvp.recorder.core.Fights.FOE;
import static com.xploits.pvp.recorder.core.Fights.OTHER_FOE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FightSummary renders reviews, list lines and death notices as chat-ready Msg (spec §FightSummary). */
class FightSummaryTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });

    /**
     * A realistic win: crystals then melee, a module turned off partway, a profile switch later in the
     * same fight, two named opponents.
     */
    private static FightRecord won() {
        return Fights.ending(FightOutcome.WON).seconds(25).totems(8, 6, true)
            .modules("auto-totem", "crystal-aura", "surround")
            .moduleChange(12, "surround", false)
            .profile("balanced")
            .profileChange(18, "aggressive")
            .phase(0, CombatState.SURFACE, FOE)
            .phase(15, CombatState.SURROUNDED, FOE)
            .opponent(OTHER_FOE)
            .hits(3, DamageKind.CRYSTAL, FOE, 5)
            .hit(DamageKind.MELEE, FOE, 4)
            .build();
    }

    /** A realistic quiet ending: light damage, nobody died. */
    private static FightRecord ended() {
        return Fights.ending(FightOutcome.ENDED).seconds(20).totems(8, 8, true)
            .modules("auto-totem", "surround")
            .phase(0, CombatState.APPROACH, FOE)
            .hit(DamageKind.PROJECTILE, FOE, 3)
            .build();
    }

    // --- rendering safety ---------------------------------------------------------------------------

    @Test
    void everyReviewListLineAndDeathNoticeRendersInBothLanguagesWithNoMissingKeysOrCoordinates() {
        for (FightRecord f : List.of(Fights.crystalDeath(), won(), ended())) {
            List<Msg> lines = FightSummary.review(f, 1);
            assertFalse(lines.isEmpty());
            for (Catalog catalog : List.of(ES, EN)) {
                for (Msg line : lines) {
                    String rendered = catalog.render(line);
                    assertFalse(rendered.contains("‹"), rendered);
                    assertFalse(CoordinateSentinel.isSuspect(rendered), rendered);
                }
                String listLine = catalog.render(FightSummary.listLine(1, f, "3 h ago"));
                assertFalse(listLine.contains("‹"), listLine);
                assertFalse(CoordinateSentinel.isSuspect(listLine), listLine);

                String notice = catalog.render(FightSummary.deathNotice(f));
                assertFalse(notice.contains("‹"), notice);
                assertFalse(CoordinateSentinel.isSuspect(notice), notice);
            }
        }
    }

    // --- content --------------------------------------------------------------------------------------

    @Test
    void aFightWithNoModulesAtStartSaysSoInsteadOfLeavingTheLineBlank() {
        FightRecord f = Fights.ending(FightOutcome.ENDED).modules().build();
        Msg modules = FightSummary.review(f, 1).stream()
            .filter(m -> m.key() == RecorderText.SUMMARY_MODULES).findFirst().orElseThrow();
        assertEquals("Modules at start: none", EN.render(modules));
        assertEquals("Módulos al empezar: ninguno", ES.render(modules));
    }

    @Test
    void theHeaderNamesTheNumberOutcomeSecondsOpponentsAndMode() {
        assertEquals("Fight #7 · lost · 43 s · Foo · auto-pvp", EN.render(FightSummary.review(Fights.crystalDeath(), 7).get(0)));
        assertEquals("Pelea #7 · perdida · 43 s · Foo · auto-pvp", ES.render(FightSummary.review(Fights.crystalDeath(), 7).get(0)));
    }

    @Test
    void droppingALineFromTheReviewChangesItsLength() {
        // header, self, damage header + crystal + melee + unseen, offense, modules, changes header + 1 change,
        // phases header + 1 phase, causes header + 4 causes (OUT_OF_TOTEMS, CRYSTAL_OUTPACED, UNDEFENDED, ARMOR_BROKE)
        assertEquals(17, FightSummary.review(Fights.crystalDeath(), 1).size());
        // won(): header, self, damage header + crystal + melee (no unseen), offense, modules, profile,
        // changes header + 2 changes, phases header + 2 phases; WON has no causes section at all
        assertEquals(14, FightSummary.review(won(), 1).size());
    }

    @Test
    void theCausesSectionListsEveryCauseRankedByWeight() {
        List<String> rendered = FightSummary.review(Fights.crystalDeath(), 1).stream().map(EN::render).toList();
        assertTrue(rendered.contains("Probable cause:"), rendered.toString());
        assertTrue(rendered.contains("  1. Out of totems after 3 pops"), rendered.toString());
        assertTrue(rendered.contains("  2. Enemy crystals: 93% of the damage · you placed 20 to their 55"), rendered.toString());
        assertTrue(rendered.contains("  4. Armor broke: from 4 pieces to 3"), rendered.toString());
    }

    @Test
    void aWonFightHasNoCausesSection() {
        List<String> rendered = FightSummary.review(won(), 1).stream().map(EN::render).toList();
        assertTrue(rendered.stream().noneMatch(l -> l.startsWith("Probable cause")), rendered.toString());
    }

    @Test
    void opponentsJoinsNamesWithCommas() {
        assertEquals("Foo, Bar", EN.render(FightSummary.opponents(won())));
        assertEquals("Foo", EN.render(FightSummary.opponents(Fights.crystalDeath())));
    }

    @Test
    void opponentsFallsBackToNobodyWithNone() {
        FightRecord f = new FightRecord(FightRecord.SCHEMA, "0.5.0", 0, 1000, 1, FightOutcome.ENDED, false,
            FightMode.MANUAL, 0, List.of(), 0, new FightRecord.SelfTotals(0, 0, 0, false, 0, 0, 0, 0, 0),
            List.of(), 0, List.of(), List.of(), List.of(), List.of());
        assertEquals("nobody", EN.render(FightSummary.opponents(f)));
        assertEquals("nadie", ES.render(FightSummary.opponents(f)));
        // the fallback itself must never look like coordinates
        assertFalse(CoordinateSentinel.isSuspect(EN.render(FightSummary.opponents(f))));
    }

    @Test
    void deathNoticeNamesSecondsOpponentsAndTheMainCause() {
        String line = EN.render(FightSummary.deathNotice(Fights.crystalDeath()));
        assertTrue(line.contains("43 s"), line);
        assertTrue(line.contains("Foo"), line);
        assertTrue(line.contains("Out of totems after 3 pops"), line);

        String es = ES.render(FightSummary.deathNotice(Fights.crystalDeath()));
        assertTrue(es.contains("43 s"), es);
        assertTrue(es.contains("Foo"), es);
    }

    @Test
    void twentyPhasesShowOnlyTheLastEightWithAnEarlierLineFirst() {
        Fights.Builder builder = Fights.ending(FightOutcome.ENDED).seconds(25).totems(8, 8, true).modules("auto-totem");
        for (int i = 0; i < 20; i++) builder.phase(i, CombatState.SURFACE, FOE);
        List<String> rendered = FightSummary.review(builder.build(), 1).stream().map(EN::render).toList();

        assertTrue(rendered.contains("  12 earlier changes not shown."), rendered.toString());
        // The 8 most recent (seconds 12..19) are shown; anything before second 12 is not.
        assertFalse(rendered.contains("  11 s · SURFACE · CALM · Foo"), rendered.toString());
        assertTrue(rendered.contains("  12 s · SURFACE · CALM · Foo"), rendered.toString());
        assertTrue(rendered.contains("  19 s · SURFACE · CALM · Foo"), rendered.toString());
        long phaseLines = rendered.stream().filter(l -> l.startsWith("  ") && l.contains(" s ·")).count();
        assertEquals(8, phaseLines);
    }

    @Test
    void exactlyEightPhasesShowNoEarlierLine() {
        Fights.Builder builder = Fights.ending(FightOutcome.ENDED).seconds(25).totems(8, 8, true).modules("auto-totem");
        for (int i = 0; i < 8; i++) builder.phase(i, CombatState.SURFACE, FOE);
        List<String> rendered = FightSummary.review(builder.build(), 1).stream().map(EN::render).toList();

        assertTrue(rendered.stream().noneMatch(l -> l.endsWith("earlier changes not shown.")), rendered.toString());
        assertTrue(rendered.contains("  0 s · SURFACE · CALM · Foo"), rendered.toString());
        assertTrue(rendered.contains("  7 s · SURFACE · CALM · Foo"), rendered.toString());
        long phaseLines = rendered.stream().filter(l -> l.startsWith("  ") && l.contains(" s ·")).count();
        assertEquals(8, phaseLines);
    }

    @Test
    void listLineShowsTheNumberOutcomeAndHowLongAgoForSeveralOutcomes() {
        assertEquals("#3 · lost · 43 s · Foo · auto-pvp · 2 h ago", EN.render(FightSummary.listLine(3, Fights.crystalDeath(), "2 h ago")));
        assertEquals("#1 · won · 25 s · Foo, Bar · manual · just now", EN.render(FightSummary.listLine(1, won(), "just now")));
        assertEquals("#5 · ended · 20 s · Foo · manual · 1 d ago", EN.render(FightSummary.listLine(5, ended(), "1 d ago")));
    }

    // --- profile and changes -------------------------------------------------------------------------

    @Test
    void theProfileLineNamesTheProfileActiveWhenTheFightOpened() {
        List<String> en = FightSummary.review(won(), 1).stream().map(EN::render).toList();
        List<String> es = FightSummary.review(won(), 1).stream().map(ES::render).toList();
        assertTrue(en.contains("Profile: balanced"), en.toString());
        assertTrue(es.contains("Perfil: balanced"), es.toString());
    }

    @Test
    void aFightWithNoProfileOrChangesShowsNeitherLine() {
        List<String> rendered = FightSummary.review(ended(), 1).stream().map(EN::render).toList();
        assertTrue(rendered.stream().noneMatch(l -> l.startsWith("Profile:")), rendered.toString());
        assertTrue(rendered.stream().noneMatch(l -> l.equals("Changes:")), rendered.toString());
    }

    @Test
    void changesMergeModuleAndProfileSwitchesInTimeOrder() {
        FightRecord f = Fights.ending(FightOutcome.ENDED).seconds(10).totems(8, 8, true).modules("auto-totem")
            .profile("balanced")
            .moduleChange(3, "surround", true)
            .profileChange(5, "aggressive")
            .moduleChange(5, "surround", false)
            .build();
        List<Msg> lines = FightSummary.review(f, 1);
        List<String> rendered = lines.stream().map(EN::render).toList();

        int header = rendered.indexOf("Changes:");
        assertTrue(header >= 0, rendered.toString());
        assertEquals(List.of("  3 s · surround · on", "  5 s · surround · off", "  5 s · profile · aggressive"),
            rendered.subList(header + 1, header + 4));

        assertEquals("Cambios:", ES.render(lines.get(header)));
        assertEquals("  5 s · perfil · aggressive", ES.render(lines.get(header + 3)));
    }

    @Test
    void moreThanEightChangesShowOnlyTheLastEightWithAnEarlierLineFirst() {
        Fights.Builder builder = Fights.ending(FightOutcome.ENDED).seconds(25).totems(8, 8, true).modules("auto-totem");
        for (int i = 0; i < 20; i++) builder.moduleChange(i, "surround", i % 2 == 0);
        List<String> rendered = FightSummary.review(builder.build(), 1).stream().map(EN::render).toList();

        int header = rendered.indexOf("Changes:");
        assertTrue(header >= 0, rendered.toString());
        assertEquals("  12 earlier changes not shown.", rendered.get(header + 1), rendered.toString());
        // The 8 most recent (seconds 12..19) are shown; anything before second 12 is not.
        assertFalse(rendered.contains("  11 s · surround · off"), rendered.toString());
        assertTrue(rendered.contains("  12 s · surround · on"), rendered.toString());
        assertTrue(rendered.contains("  19 s · surround · off"), rendered.toString());
        long changeLines = rendered.stream().filter(l -> l.startsWith("  ") && l.contains(" s ·")).count();
        assertEquals(8, changeLines);
    }

    @Test
    void exactlyEightChangesShowNoEarlierLine() {
        Fights.Builder builder = Fights.ending(FightOutcome.ENDED).seconds(25).totems(8, 8, true).modules("auto-totem");
        for (int i = 0; i < 8; i++) builder.moduleChange(i, "surround", i % 2 == 0);
        List<String> rendered = FightSummary.review(builder.build(), 1).stream().map(EN::render).toList();

        assertTrue(rendered.stream().noneMatch(l -> l.endsWith("earlier changes not shown.")), rendered.toString());
        assertTrue(rendered.contains("  0 s · surround · on"), rendered.toString());
        assertTrue(rendered.contains("  7 s · surround · off"), rendered.toString());
        long changeLines = rendered.stream().filter(l -> l.startsWith("  ") && l.contains(" s ·")).count();
        assertEquals(8, changeLines);
    }
}
