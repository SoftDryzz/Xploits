package com.xploits.pvp.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.Msg;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private static final Skipped TOTEMS = new Skipped(ManagedModules.AUTO_TRAP, Msg.of(PvpText.TOTEM_FLOOR));

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
        assertEquals("\n  not turned on:  auto-trap — " + EN.render(Msg.of(PvpText.TOTEM_FLOOR))
            + "\n  off by profile: auto-city, auto-anvil", en);
        String es = ES.render(PvpStatus.skippedLines(skipped));
        assertEquals("\n  no encendido: auto-trap — " + ES.render(Msg.of(PvpText.TOTEM_FLOOR))
            + "\n  vetados:      auto-city, auto-anvil (por el perfil)", es);
    }

    @Test
    void noProfileOffLineWithoutProfileOffSkips() {
        assertEquals("\n  not turned on:  auto-trap — " + EN.render(Msg.of(PvpText.TOTEM_FLOOR)),
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
        assertEquals(List.of("anti-anchor"), PvpStatus.missingNames(skipped));
        assertEquals(List.of("auto-city"), PvpStatus.profileOffNames(skipped));
    }

    @Test
    void missingModulesAreGroupedIntoOneLineAfterTheProfileOffOne() {
        List<Skipped> skipped = List.of(missing(ManagedModules.ANTI_ANCHOR), TOTEMS, off(ManagedModules.AUTO_CITY),
            missing(ManagedModules.ANTI_BED));
        assertEquals("\n  not turned on:  auto-trap — " + EN.render(Msg.of(PvpText.TOTEM_FLOOR))
            + "\n  off by profile: auto-city"
            + "\n  missing in Meteor: anti-anchor, anti-bed", EN.render(PvpStatus.skippedLines(skipped)));
        assertEquals("\n  faltan en Meteor: anti-anchor", ES.render(PvpStatus.skippedLines(
            List.of(missing(ManagedModules.ANTI_ANCHOR)))));
    }

    @Test
    void theMissingWarningNamesTheModule() {
        assertEquals("anti-anchor is not in this Meteor build; auto-pvp will not use it.",
            EN.render(Msg.of(PvpText.MODULE_MISSING, "module", "anti-anchor")));
        assertEquals("anti-anchor no está en esta versión de Meteor; auto-pvp no lo usará.",
            ES.render(Msg.of(PvpText.MODULE_MISSING, "module", "anti-anchor")));
    }
}
