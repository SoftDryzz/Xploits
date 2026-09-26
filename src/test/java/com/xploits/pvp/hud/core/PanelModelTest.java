package com.xploits.pvp.hud.core;

import com.xploits.console.core.CoordinateSentinel;
import com.xploits.pvp.core.CombatPosture;
import com.xploits.pvp.core.CombatState;
import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link PanelModel} builds the {@code xploits-pvp} panel lines (spec §3). */
class PanelModelTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });

    private static List<String> render(List<PanelLine> lines, Catalog catalog) {
        return lines.stream().map(l -> catalog.render(l.text())).toList();
    }

    // --- auto-pvp off -------------------------------------------------------------------------------

    @Test
    void autoPvpOffShowsOnlyTheOffLineWithTheProfileName() {
        PanelInput in = PanelInputs.base().autoPvpOn(false).profileName("aggressive").build();
        List<PanelLine> lines = PanelModel.lines(in);

        assertEquals(1, lines.size());
        assertEquals(Tone.NORMAL, lines.getFirst().tone());
        assertEquals("auto-pvp off · profile aggressive", EN.render(lines.getFirst().text()));
        assertEquals("auto-pvp apagado · perfil aggressive", ES.render(lines.getFirst().text()));
    }

    @Test
    void theOffLineShowsTheModifiedMarkTheSameWayTheHeaderDoes() {
        PanelInput unmodified = PanelInputs.base().autoPvpOn(false).profileName("aggressive").profileModified(false).build();
        PanelInput modified = PanelInputs.base().autoPvpOn(false).profileName("aggressive").profileModified(true).build();

        assertEquals("auto-pvp off · profile aggressive", EN.render(PanelModel.lines(unmodified).getFirst().text()));
        assertEquals("auto-pvp off · profile aggressive*", EN.render(PanelModel.lines(modified).getFirst().text()));
    }

    // --- line 2: profile / state / posture -----------------------------------------------------------

    @Test
    void theHeaderNamesProfileStateAndPostureAndMarksAModifiedProfile() {
        PanelInput in = PanelInputs.base().profileName("balanced").profileModified(true)
            .state(CombatState.SURFACE).posture(CombatPosture.CALM).build();
        PanelLine header = PanelModel.lines(in).get(0); // no danger line with these defaults
        assertEquals("balanced* · SURFACE · CALM", EN.render(header.text()));
    }

    @Test
    void postureColoursTheHeaderCalmNormalThreatenedWarn() {
        PanelLine calm = PanelModel.lines(PanelInputs.base().posture(CombatPosture.CALM).build()).getFirst();
        PanelLine threatened = PanelModel.lines(PanelInputs.base().posture(CombatPosture.THREATENED).build()).getFirst();
        assertEquals(Tone.NORMAL, calm.tone());
        assertEquals(Tone.WARN, threatened.tone());
    }

    // --- line 3: target --------------------------------------------------------------------------------

    @Test
    void aTargetShowsItsNameAndDistanceToOneDecimal() {
        PanelInput in = PanelInputs.base().target("Foo").targetDistance(12.34).build();
        PanelLine line = PanelModel.lines(in).get(1);
        assertEquals("target Foo · 12.3 blocks", EN.render(line.text()));
        assertEquals("objetivo Foo · 12,3 bloques", ES.render(line.text()));
    }

    @Test
    void noTargetShowsTheNoTargetLine() {
        PanelInput in = PanelInputs.base().target(null).build();
        PanelLine line = PanelModel.lines(in).get(1);
        assertEquals("no target", EN.render(line.text()));
    }

    // --- line 4: modules ---------------------------------------------------------------------------------

    @Test
    void onlyNonEmptyModuleGroupsAppearWithTheirOwnTone() {
        PanelInput in = PanelInputs.base().enabled("crystal-aura", "surround").released("auto-trap")
            .profileOff("auto-city").build();
        List<PanelLine> lines = PanelModel.lines(in);
        List<String> rendered = render(lines, EN);

        assertTrue(rendered.contains("on: crystal-aura and surround"), rendered.toString());
        assertTrue(rendered.contains("released: auto-trap"), rendered.toString());
        assertTrue(rendered.contains("off by profile: auto-city"), rendered.toString());

        assertEquals(Tone.GOOD, lines.get(rendered.indexOf("on: crystal-aura and surround")).tone());
        assertEquals(Tone.WARN, lines.get(rendered.indexOf("released: auto-trap")).tone());
        assertEquals(Tone.MUTED, lines.get(rendered.indexOf("off by profile: auto-city")).tone());
    }

    @Test
    void emptyModuleGroupsAreOmitted() {
        PanelInput in = PanelInputs.base().enabled().released().profileOff().build();
        List<String> rendered = render(PanelModel.lines(in), EN);
        assertTrue(rendered.stream().noneMatch(l -> l.startsWith("on:") || l.startsWith("released:") || l.startsWith("off by profile:")),
            rendered.toString());
    }

    // --- line 5: resources and needs ------------------------------------------------------------------

    @Test
    void crystalsWarnWhenBelowOneAndCrystalAuraIsEnabled() {
        PanelInput enabledNoCrystals = PanelInputs.base().crystalAuraEnabled(true).crystals(0).build();
        PanelInput enabledWithCrystals = PanelInputs.base().crystalAuraEnabled(true).crystals(1).build();
        PanelInput disabledNoCrystals = PanelInputs.base().crystalAuraEnabled(false).crystals(0).build();

        assertEquals(Tone.WARN, crystalsLine(enabledNoCrystals).tone());
        assertEquals(Tone.NORMAL, crystalsLine(enabledWithCrystals).tone());
        // crystal-aura not enabled: nothing needed, 0 crystals is not a warning
        assertEquals(Tone.NORMAL, crystalsLine(disabledNoCrystals).tone());
    }

    @Test
    void totemsWarnOnlyAtExactlyZero() {
        assertEquals(Tone.WARN, totemsLine(PanelInputs.base().totems(0).build()).tone());
        assertEquals(Tone.NORMAL, totemsLine(PanelInputs.base().totems(1).build()).tone());
    }

    @Test
    void obsidianNeedIsTheLargestMinimumAmongTheEnabledConsumers() {
        // auto-trap needs 8: 7 warns, 8 does not.
        assertEquals(Tone.WARN, obsidianLine(PanelInputs.base().enabled("auto-trap").obsidian(7).build()).tone());
        assertEquals(Tone.NORMAL, obsidianLine(PanelInputs.base().enabled("auto-trap").obsidian(8).build()).tone());
        // surround alone needs only 4: 5 is enough even though auto-trap (unused here) would need 8.
        assertEquals(Tone.NORMAL, obsidianLine(PanelInputs.base().enabled("surround").obsidian(5).build()).tone());
        // hole-filler needs 1, the smallest of the three.
        assertEquals(Tone.NORMAL, obsidianLine(PanelInputs.base().enabled("hole-filler").obsidian(1).build()).tone());
        // several enabled at once: the need is the MAX of the minimums, not the sum (8, not 8+4+1=13).
        PanelInput all = PanelInputs.base().enabled("auto-trap", "surround", "hole-filler").obsidian(8).build();
        assertEquals(Tone.NORMAL, obsidianLine(all).tone());
        PanelInput allShort = PanelInputs.base().enabled("auto-trap", "surround", "hole-filler").obsidian(7).build();
        assertEquals(Tone.WARN, obsidianLine(allShort).tone());
        // anti-anvil places obsidian too: alone and with none in the hotbar, the line warns.
        assertEquals(Tone.WARN, obsidianLine(PanelInputs.base().enabled("anti-anvil").obsidian(0).build()).tone());
        assertEquals(Tone.NORMAL, obsidianLine(PanelInputs.base().enabled("anti-anvil").obsidian(1).build()).tone());
        // none of the obsidian consumers enabled: nothing needed.
        assertEquals(Tone.NORMAL, obsidianLine(PanelInputs.base().enabled("crystal-aura").obsidian(0).build()).tone());
    }

    private static PanelLine crystalsLine(PanelInput in) {
        return findResourceLine(in, "crystals");
    }

    private static PanelLine totemsLine(PanelInput in) {
        return findResourceLine(in, "totems");
    }

    private static PanelLine obsidianLine(PanelInput in) {
        return findResourceLine(in, "obsidian");
    }

    private static PanelLine findResourceLine(PanelInput in, String prefix) {
        List<PanelLine> lines = PanelModel.lines(in);
        for (PanelLine line : lines) {
            String rendered = EN.render(line.text());
            if (rendered.startsWith(prefix + " ")) return line;
        }
        throw new AssertionError("no " + prefix + " line in " + render(lines, EN));
    }

    // --- line 6: fight -----------------------------------------------------------------------------------

    @Test
    void theFightLineShowsSecondsPopsAndDamageToOneDecimal() {
        PanelInput in = PanelInputs.base().fight(new PanelInput.LiveFight(12, 1, 2, 18.5)).showFight(true).build();
        List<String> rendered = render(PanelModel.lines(in), EN);
        assertTrue(rendered.contains("12 s · your pops 1 · theirs 2 · damage 18.5"), rendered.toString());
    }

    @Test
    void theFightLineIsAbsentWithNoFightOrWithShowFightOff() {
        PanelInput noFight = PanelInputs.base().fight(null).showFight(true).build();
        PanelInput fightHiddenBySetting = PanelInputs.base().fight(new PanelInput.LiveFight(1, 0, 0, 0)).showFight(false).build();

        assertTrue(render(PanelModel.lines(noFight), EN).stream().noneMatch(l -> l.contains(" s · your pops")));
        assertTrue(render(PanelModel.lines(fightHiddenBySetting), EN).stream().noneMatch(l -> l.contains(" s · your pops")));
    }

    // --- danger priority (a > b > c > d), only one shown ------------------------------------------------

    @Test
    void aNoTotemsInAFightOutranksEverythingElse() {
        PanelInput in = PanelInputs.base().state(CombatState.SURFACE).totems(0).outOfResources(true)
            .idle(new PanelInput.Idle("obsidian", List.of("auto-trap")))
            .crystalAuraEnabled(true).crystals(0)
            .build();
        PanelLine danger = PanelModel.lines(in).getFirst();
        assertEquals(Tone.DANGER, danger.tone());
        assertEquals("no totems in a fight", EN.render(danger.text()));
    }

    @Test
    void noTotemsOnlyCountsAsDangerWhileInAFight() {
        PanelInput in = PanelInputs.base().state(CombatState.NO_COMBAT).totems(0).build();
        List<String> rendered = render(PanelModel.lines(in), EN);
        assertFalse(rendered.contains("no totems in a fight"), rendered.toString());
    }

    @Test
    void bOutOfResourcesOutranksIdleAndAuraWithoutCrystals() {
        PanelInput in = PanelInputs.base().totems(4).outOfResources(true)
            .idle(new PanelInput.Idle("obsidian", List.of("auto-trap")))
            .crystalAuraEnabled(true).crystals(0)
            .build();
        PanelLine danger = PanelModel.lines(in).getFirst();
        assertEquals(Tone.DANGER, danger.tone());
        assertEquals("Out of resources: there is no module in this phase you can sustain.", EN.render(danger.text()));
    }

    @Test
    void cIdleOutranksAuraWithoutCrystals() {
        PanelInput in = PanelInputs.base().totems(4).outOfResources(false)
            .idle(new PanelInput.Idle("obsidian", List.of("auto-trap", "surround")))
            .crystalAuraEnabled(true).crystals(0)
            .build();
        PanelLine danger = PanelModel.lines(in).getFirst();
        assertEquals(Tone.DANGER, danger.tone());
        assertEquals("obsidian idle: auto-trap and surround", EN.render(danger.text()));
    }

    @Test
    void dAuraWithoutCrystalsShowsAloneWhenNothingElseApplies() {
        PanelInput in = PanelInputs.base().totems(4).outOfResources(false).idle()
            .crystalAuraEnabled(true).crystals(0)
            .build();
        PanelLine danger = PanelModel.lines(in).getFirst();
        assertEquals(Tone.DANGER, danger.tone());
        assertEquals("crystal-aura without crystals: it will only break the ones placed against you.", EN.render(danger.text()));
    }

    @Test
    void noDangerLineWhenNothingIsWrong() {
        PanelInput in = PanelInputs.base().totems(4).outOfResources(false).idle()
            .crystalAuraEnabled(false).crystals(0)
            .build();
        List<PanelLine> lines = PanelModel.lines(in);
        assertFalse(lines.stream().anyMatch(l -> l.tone() == Tone.DANGER), render(lines, EN).toString());
    }

    // --- sample() -------------------------------------------------------------------------------------

    @Test
    void sampleIsFixedAndNonEmpty() {
        List<PanelLine> a = PanelModel.sample();
        List<PanelLine> b = PanelModel.sample();
        assertFalse(a.isEmpty());
        assertEquals(render(a, EN), render(b, EN));
    }

    @Test
    void sampleShowsOneOfEveryLineKind() {
        List<String> rendered = render(PanelModel.sample(), EN);
        assertTrue(rendered.stream().anyMatch(l -> l.contains("idle:")), rendered.toString());
        assertTrue(rendered.stream().anyMatch(l -> l.contains(" · ") && l.contains("SURFACE")), rendered.toString());
        assertTrue(rendered.stream().anyMatch(l -> l.startsWith("target ")), rendered.toString());
        assertTrue(rendered.stream().anyMatch(l -> l.startsWith("on:")), rendered.toString());
        assertTrue(rendered.stream().anyMatch(l -> l.startsWith("released:")), rendered.toString());
        assertTrue(rendered.stream().anyMatch(l -> l.startsWith("off by profile:")), rendered.toString());
        assertTrue(rendered.stream().anyMatch(l -> l.startsWith("crystals ")), rendered.toString());
        assertTrue(rendered.stream().anyMatch(l -> l.startsWith("totems ")), rendered.toString());
        assertTrue(rendered.stream().anyMatch(l -> l.startsWith("obsidian ")), rendered.toString());
        assertTrue(rendered.stream().anyMatch(l -> l.contains("your pops")), rendered.toString());
    }

    // --- ES/EN rendering safety --------------------------------------------------------------------------

    @Test
    void everyLineRendersInBothLanguagesWithNoMissingKeysAndNoCoordinates() {
        List<PanelInput> inputs = List.of(
            PanelInputs.base().autoPvpOn(false).build(),
            PanelInputs.base().target(null).build(),
            PanelInputs.base().enabled("crystal-aura").released("surround").profileOff("auto-city").build(),
            PanelInputs.base().totems(0).build(),
            PanelInputs.base().outOfResources(true).build(),
            PanelInputs.base().idle(new PanelInput.Idle("obsidian", List.of("auto-trap", "surround", "hole-filler"))).build(),
            PanelInputs.base().crystalAuraEnabled(true).crystals(0).build(),
            PanelInputs.base().fight(new PanelInput.LiveFight(30, 2, 3, 42.5)).build()
        );
        for (PanelInput in : inputs) {
            for (PanelLine line : PanelModel.lines(in)) {
                for (Catalog catalog : List.of(ES, EN)) {
                    String rendered = catalog.render(line.text());
                    assertFalse(rendered.contains("‹"), rendered);
                    assertFalse(CoordinateSentinel.isSuspect(rendered), rendered);
                }
            }
        }
        for (PanelLine line : PanelModel.sample()) {
            for (Catalog catalog : List.of(ES, EN)) {
                String rendered = catalog.render(line.text());
                assertFalse(rendered.contains("‹"), rendered);
                assertFalse(CoordinateSentinel.isSuspect(rendered), rendered);
            }
        }
    }
}
