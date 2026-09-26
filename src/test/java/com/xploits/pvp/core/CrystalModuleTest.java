package com.xploits.pvp.core;

import com.xploits.pvp.profile.core.ProfileText;
import com.xploits.shared.core.PositionedMsg;
import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.Msg;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Which crystal aura auto-pvp drives (spec §3.1, P5): names, the saved form and the texts that name it. */
class CrystalModuleTest {
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });

    @Test
    void meteorDrivesMeteorsAuraAndIsTheDefaultFirstValue() {
        assertEquals("crystal-aura", CrystalModule.METEOR.moduleName());
        assertEquals("crystal-aura++", CrystalModule.XPLOITS.moduleName());
        assertSame(CrystalModule.METEOR, CrystalModule.values()[0], "the first value is Meteor's aura");
        assertEquals("crystal-aura", CrystalModule.LOGICAL);
        assertEquals(ManagedModules.CRYSTAL_AURA.name(), CrystalModule.LOGICAL,
            "everything below auto-pvp keys on the catalog's name");
    }

    @Test
    void theSavedFormIsStable() {
        // Meteor saves an enum setting by toString and reads it back by comparing toString: renaming a
        // constant must not change what is written to disk or typed in a command.
        assertEquals("meteor", CrystalModule.METEOR.toString());
        assertEquals("xploits++", CrystalModule.XPLOITS.toString());
        assertEquals(2, CrystalModule.values().length);
    }

    @Test
    void theOtherAuraIsTheOneNotSelected() {
        assertSame(CrystalModule.XPLOITS, CrystalModule.METEOR.other());
        assertSame(CrystalModule.METEOR, CrystalModule.XPLOITS.other());
    }

    @Test
    void onlyTheLogicalAuraResolvesToAnotherModule() {
        assertEquals("crystal-aura", CrystalModule.METEOR.resolve("crystal-aura"));
        assertEquals("crystal-aura++", CrystalModule.XPLOITS.resolve("crystal-aura"));
        for (ManagedModule module : ManagedModules.ALL) {
            if (module.equals(ManagedModules.CRYSTAL_AURA)) continue;
            assertEquals(module.name(), CrystalModule.XPLOITS.resolve(module.name()), module.name());
            assertEquals(module.name(), CrystalModule.METEOR.resolve(module.name()), module.name());
        }
        assertEquals("auto-totem", CrystalModule.XPLOITS.resolve("auto-totem"));
    }

    @Test
    void withMeteorTheTextsReadExactlyAsBefore() {
        // 0.6.2's texts, word for word: with the default nothing a player reads changes.
        CrystalModule m = CrystalModule.METEOR;
        assertEquals("you carry no totems and crystal-aura's anti-suicide is off",
            EN.render(m.name(Msg.of(PvpText.TOTEM_FLOOR))));
        assertEquals("crystal-aura without crystals: it will only break the ones placed against you.",
            EN.render(m.name(Msg.of(PvpText.AURA_NO_CRYSTALS))));
        assertEquals("Profile solo does not allow crystal-aura: it loses the autobreak.",
            EN.render(m.name(Msg.of(ProfileText.PROFILE_NO_AUTOBREAK, "name", "solo"))));
        assertEquals("no llevas tótems y el anti-suicide de crystal-aura está apagado",
            ES.render(m.name(Msg.of(PvpText.TOTEM_FLOOR))));
        assertEquals("crystal-aura was already on: it is yours, I will not turn it off even against a buried player.",
            EN.render(Msg.of(PvpText.CRYSTAL_AURA_ALREADY_ON, "module", m.moduleName())));
        assertEquals("use-crystal-aura is off: auto-pvp will not turn crystal-aura on, and you lose its autobreak.",
            EN.render(Msg.of(ProfileText.PROFILE_NO_AUTOBREAK_UNTICKED, "module", m.moduleName())));
    }

    @Test
    void withXploitsTheTextsNameCrystalAuraPlusPlus() {
        CrystalModule m = CrystalModule.XPLOITS;
        assertEquals("you carry no totems and crystal-aura++'s anti-suicide is off",
            EN.render(m.name(Msg.of(PvpText.TOTEM_FLOOR))));
        assertEquals("crystal-aura++ sin cristales: solo romperá los que te pongan.",
            ES.render(m.name(Msg.of(PvpText.AURA_NO_CRYSTALS))));
    }

    @Test
    void theNameReachesNestedMessagesAndTheWholePlan() {
        CrystalModule m = CrystalModule.XPLOITS;
        Msg line = Msg.of(PvpText.STATUS_SKIPPED, "module", "auto-trap", "reason", Msg.of(PvpText.TOTEM_FLOOR));
        assertEquals("\n  not turned on:  auto-trap — you carry no totems and crystal-aura++'s anti-suicide is off",
            EN.render(m.name(line)));

        Plan plan = new Plan(CombatState.SURFACE, CombatPosture.CALM, List.of(ManagedModules.CRYSTAL_AURA),
            List.of(new Skipped(ManagedModules.AUTO_TRAP, Msg.of(PvpText.TOTEM_FLOOR))),
            List.of(Msg.of(PvpText.AURA_NO_CRYSTALS)));
        Plan named = m.name(plan);
        assertEquals(plan.state(), named.state());
        assertEquals(plan.posture(), named.posture());
        assertEquals(plan.enable(), named.enable(), "the plan keeps the catalog's modules");
        assertEquals(List.of(Msg.of(PvpText.AURA_NO_CRYSTALS, "module", "crystal-aura++")), named.warnings());
        assertEquals(new Skipped(ManagedModules.AUTO_TRAP, Msg.of(PvpText.TOTEM_FLOOR, "module", "crystal-aura++")),
            named.skipped().getFirst());

        PositionedMsg profile = PositionedMsg.same(Msg.of(ProfileText.PROFILE_NO_AUTOBREAK, "name", "solo"));
        assertEquals(PositionedMsg.same(Msg.of(ProfileText.PROFILE_NO_AUTOBREAK, "name", "solo", "module", "crystal-aura++")),
            m.name(profile));
    }

    @Test
    void messagesThatDoNotNameTheAuraAreLeftAlone() {
        Msg released = Msg.of(PvpText.RELEASED, "module", "auto-trap");
        assertEquals(released, CrystalModule.XPLOITS.name(released));
        Msg already = Msg.of(PvpText.TOTEM_FLOOR, "module", "crystal-aura");
        assertEquals(already, CrystalModule.XPLOITS.name(already), "a name given on purpose is kept");
    }

    @Test
    void theSuspectsDependOnWhichAuraIsIdle() {
        assertEquals(PvpText.SUSPECTS_CRYSTAL_AURA, CrystalModule.METEOR.suspects());
        assertEquals(PvpText.SUSPECTS_CRYSTAL_AURA_PP, CrystalModule.XPLOITS.suspects());
    }
}
