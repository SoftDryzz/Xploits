package com.xploits.pvp.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.Msg;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The profile parts of the status command (precise rules "Status command"). */
class PvpStatusTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });

    private static Skipped off(ManagedModule module) {
        return new Skipped(module, Msg.of(PvpText.PROFILE_OFF));
    }

    private static Skipped missing(ManagedModule module) {
        return new Skipped(module, Msg.of(PvpText.MODULE_MISSING_SKIP));
    }

    /** The totem floor as auto-pvp says it: its text names the aura it drives (crystal-aura++ spec §3.6). */
    private static final Msg FLOOR = Msg.of(PvpText.TOTEM_FLOOR, "module", "crystal-aura");
    private static final Skipped TOTEMS = new Skipped(ManagedModules.AUTO_TRAP, FLOOR);

    @Test
    void profileShowsTheNameAndAStarOnlyWhenModified() {
        assertEquals(" · profile aggressive*", EN.render(PvpStatus.profile("aggressive", true)));
        assertEquals(" · profile aggressive", EN.render(PvpStatus.profile("aggressive", false)));
        assertEquals(" · perfil balanced*", ES.render(PvpStatus.profile("balanced", true)));
    }

    @Test
    void profileOffSkipsAreGroupedIntoOneLineAfterTheRealOnes() {
        List<Skipped> skipped = List.of(off(ManagedModules.AUTO_CITY), TOTEMS, off(ManagedModules.AUTO_ANVIL));
        String en = EN.render(PvpStatus.skippedLines(skipped));
        assertEquals("\n  not turned on:  auto-trap — " + EN.render(FLOOR)
            + "\n  off by profile: auto-city, auto-anvil", en);
        String es = ES.render(PvpStatus.skippedLines(skipped));
        assertEquals("\n  no encendido: auto-trap — " + ES.render(FLOOR)
            + "\n  vetados:      auto-city, auto-anvil (por el perfil)", es);
    }

    @Test
    void noProfileOffLineWithoutProfileOffSkips() {
        assertEquals("\n  not turned on:  auto-trap — " + EN.render(FLOOR),
            EN.render(PvpStatus.skippedLines(List.of(TOTEMS))));
    }

    @Test
    void onlyProfileOffGivesOnlyTheGroupedLine() {
        assertEquals("\n  off by profile: auto-city",
            EN.render(PvpStatus.skippedLines(List.of(off(ManagedModules.AUTO_CITY)))));
    }

    @Test
    void nothingSkippedRendersEmpty() {
        assertEquals("", EN.render(PvpStatus.skippedLines(List.of())));
    }

    @Test
    void filtersSplitProfileOffFromTheRest() {
        List<Skipped> skipped = List.of(off(ManagedModules.AUTO_CITY), TOTEMS);
        assertEquals(List.of(TOTEMS), PvpStatus.realSkips(skipped));
        assertEquals(List.of("auto-city"), PvpStatus.profileOffNames(skipped));
    }

    @Test
    void missingModulesAreNotRealSkipsEither() {
        // Neither a profile choice nor a module this Meteor build does not have is something to say in
        // chat each fight, nor a reason in the loud OUT OF RESOURCES warning.
        List<Skipped> skipped = List.of(missing(ManagedModules.ANTI_ANCHOR), TOTEMS, off(ManagedModules.AUTO_CITY));
        assertEquals(List.of(TOTEMS), PvpStatus.realSkips(skipped));
        assertEquals(List.of("auto-city"), PvpStatus.profileOffNames(skipped));
    }

    @Test
    void theMissingLineComesFromTheMeasuredListNotFromThePlan() {
        // The plan only skips a missing module while something wants it; the status names it always,
        // once, after the profile line.
        List<Skipped> skipped = List.of(missing(ManagedModules.ANTI_ANCHOR), TOTEMS, off(ManagedModules.AUTO_CITY));
        assertEquals("\n  not turned on:  auto-trap — " + EN.render(FLOOR)
            + "\n  off by profile: auto-city"
            + "\n  missing in Meteor: anti-anchor, anti-bed",
            EN.render(PvpStatus.skippedLines(skipped, List.of("anti-anchor", "anti-bed"))));
        assertEquals("\n  faltan en Meteor: anti-anchor", ES.render(PvpStatus.skippedLines(List.of(),
            List.of("anti-anchor"))));
    }

    @Test
    void aMissingSkipInThePlanIsNotListedTwiceNorWithoutTheMeasuredList() {
        List<Skipped> skipped = List.of(missing(ManagedModules.ANTI_ANCHOR));
        assertEquals("", EN.render(PvpStatus.skippedLines(skipped, List.of())));
        assertEquals("\n  missing in Meteor: anti-anchor",
            EN.render(PvpStatus.skippedLines(skipped, List.of("anti-anchor"))));
    }

    @Test
    void theMissingWarningListsTheModulesInOneLine() {
        assertEquals("Not in this Meteor build, so auto-pvp will not use: anti-anchor.",
            EN.render(PvpStatus.missingWarning(List.of("anti-anchor"))));
        assertEquals("Módulos que esta versión de Meteor no trae y que auto-pvp no usará: anti-anchor.",
            ES.render(PvpStatus.missingWarning(List.of("anti-anchor"))));
        assertEquals("Not in this Meteor build, so auto-pvp will not use: anti-anchor and anti-bed.",
            EN.render(PvpStatus.missingWarning(List.of("anti-anchor", "anti-bed"))));
        assertEquals("Módulos que esta versión de Meteor no trae y que auto-pvp no usará: anti-anvil, anti-bed y anti-anchor.",
            ES.render(PvpStatus.missingWarning(List.of("anti-anvil", "anti-bed", "anti-anchor"))));
    }

    // --- crystal-aura++ (spec §3.3, Q6) -------------------------------------------------------------

    @Test
    void withCrystalAuraPlusPlusTheAuraIsNamedAsTheRealModule() {
        List<Skipped> skipped = List.of(new Skipped(ManagedModules.CRYSTAL_AURA, Msg.of(PvpText.TOTEM_FLOOR)),
            off(ManagedModules.AUTO_CITY));
        assertEquals("\n  not turned on:  crystal-aura++ — you carry no totems and crystal-aura++'s anti-suicide is off"
                + "\n  off by profile: auto-city",
            EN.render(CrystalModule.XPLOITS.name(PvpStatus.skippedLines(skipped, List.of(), CrystalModule.XPLOITS))));
        assertEquals("\n  off by profile: crystal-aura++, auto-city", EN.render(PvpStatus.skippedLines(
            List.of(off(ManagedModules.CRYSTAL_AURA), off(ManagedModules.AUTO_CITY)), List.of(), CrystalModule.XPLOITS)));
        assertEquals(EN.render(PvpStatus.skippedLines(List.of(TOTEMS, off(ManagedModules.CRYSTAL_AURA)))),
            EN.render(PvpStatus.skippedLines(List.of(TOTEMS, off(ManagedModules.CRYSTAL_AURA)), List.of(), CrystalModule.METEOR)),
            "with Meteor's aura the lines are the ones they always were");
    }

    @Test
    void theLateOwnCrystalsLineShowsOnlyWithCrystalAuraPlusPlus() {
        Msg status = Msg.of(PvpText.STATUS_OFF);
        assertSame(status, PvpStatus.withLateOwn(status, CrystalModule.METEOR, 3), "Meteor's aura: the status as it was");
        assertEquals("auto-pvp is off.", EN.render(PvpStatus.withLateOwn(status, CrystalModule.METEOR, 3)));

        assertEquals("auto-pvp is off.\n  late crystals:  3 of yours arrived after their wait ran out and were treated"
                + " as someone else's (Meteor's rules, no budget)",
            EN.render(PvpStatus.withLateOwn(status, CrystalModule.XPLOITS, 3)));
        assertTrue(ES.render(PvpStatus.withLateOwn(status, CrystalModule.XPLOITS, 0)).contains("\n  tardíos:      0 cristales"),
            "shown even at zero while crystal-aura++ is driven");
    }
}
