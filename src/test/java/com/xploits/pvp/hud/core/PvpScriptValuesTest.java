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
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link PvpScriptValues} feeds the {@code xploits.pvp.*} Starscript values (spec §3 + Precise rules). */
class PvpScriptValuesTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });

    // --- no world -------------------------------------------------------------------------------

    @Test
    void noWorldIsOffWithEverythingElseEmptyButStillReportsTheActiveProfile() {
        PvpScriptValues en = PvpScriptValues.of(null, "balanced", EN);
        assertEquals("off", en.state());
        assertEquals("", en.posture());
        assertEquals("balanced", en.profile());
        assertEquals("", en.target());
        assertEquals("", en.distance());

        PvpScriptValues es = PvpScriptValues.of(null, "balanced", ES);
        assertEquals("apagado", es.state());
        assertEquals("balanced", es.profile());
    }

    @Test
    void aNullActiveProfileIsRejectedEvenWithAWorld() {
        PanelInput in = PanelInputs.base().build();
        assertThrows(NullPointerException.class, () -> PvpScriptValues.of(null, null, EN));
        assertThrows(NullPointerException.class, () -> PvpScriptValues.of(in, null, EN));
    }

    // --- auto-pvp off -----------------------------------------------------------------------------

    @Test
    void autoPvpOffIsOffWithEmptyPostureTargetAndDistanceButKnowsTheActiveProfile() {
        // A deliberately different activeProfile proves in.profileName() wins whenever in != null.
        PanelInput in = PanelInputs.base().autoPvpOn(false).profileName("aggressive").build();

        PvpScriptValues en = PvpScriptValues.of(in, "stale-name", EN);
        assertEquals("off", en.state());
        assertEquals("", en.posture());
        assertEquals("aggressive", en.profile());
        assertEquals("", en.target());
        assertEquals("", en.distance());

        PvpScriptValues es = PvpScriptValues.of(in, "stale-name", ES);
        assertEquals("apagado", es.state());
        assertEquals("aggressive", es.profile());
    }

    // --- auto-pvp on ------------------------------------------------------------------------------

    @Test
    void onReportsTranslatedStateAndPostureTheRawProfileAndTheTargetWithItsDistance() {
        PanelInput in = PanelInputs.base().profileName("balanced").state(CombatState.SURROUNDED)
            .posture(CombatPosture.THREATENED).target("Foo").targetDistance(12.34).build();

        PvpScriptValues en = PvpScriptValues.of(in, in.profileName(), EN);
        assertEquals("SURROUNDED", en.state());
        assertEquals("THREATENED", en.posture());
        assertEquals("balanced", en.profile());
        assertEquals("Foo", en.target());
        assertEquals("12.3", en.distance());

        PvpScriptValues es = PvpScriptValues.of(in, in.profileName(), ES);
        assertEquals("RODEADO", es.state());
        assertEquals("AMENAZADO", es.posture());
        assertEquals("12,3", es.distance());
    }

    @Test
    void onWithNoTargetLeavesTargetAndDistanceEmpty() {
        PanelInput in = PanelInputs.base().target(null).build();
        PvpScriptValues values = PvpScriptValues.of(in, in.profileName(), EN);
        assertEquals("", values.target());
        assertEquals("", values.distance());
    }

    @Test
    void distanceAlwaysKeepsOneDecimalEvenForAWholeNumber() {
        PanelInput in = PanelInputs.base().target("Foo").targetDistance(10.0).build();
        assertEquals("10.0", PvpScriptValues.of(in, in.profileName(), EN).distance());
        assertEquals("10,0", PvpScriptValues.of(in, in.profileName(), ES).distance());
    }

    @Test
    void theProfileFieldIsNeverTranslatedOrAltered() {
        PanelInput in = PanelInputs.base().profileName("my-own-profile").build();
        assertEquals("my-own-profile", PvpScriptValues.of(in, in.profileName(), EN).profile());
        assertEquals("my-own-profile", PvpScriptValues.of(in, in.profileName(), ES).profile());
    }

    // --- never a position -----------------------------------------------------------------------------

    @Test
    void noValueEverLooksLikeAPosition() {
        List<PanelInput> inputs = List.of(
            PanelInputs.base().autoPvpOn(false).build(),
            PanelInputs.base().target(null).build(),
            PanelInputs.base().target("Foo").targetDistance(1234.5).build(),
            PanelInputs.base().state(CombatState.CHASE).posture(CombatPosture.THREATENED).build());
        for (PanelInput in : inputs) {
            for (Catalog catalog : List.of(ES, EN)) {
                PvpScriptValues values = PvpScriptValues.of(in, in.profileName(), catalog);
                for (String value : List.of(values.state(), values.posture(), values.profile(), values.target(), values.distance())) {
                    assertFalse(CoordinateSentinel.isSuspect(value), value);
                }
            }
            assertFalse(CoordinateSentinel.isSuspect(PvpScriptValues.of(null, "balanced", EN).toString()));
        }
    }
}
