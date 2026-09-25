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
    void noWorldIsOffWithEverythingElseEmptyIncludingProfile() {
        PvpScriptValues en = PvpScriptValues.of(null, EN);
        assertEquals("off", en.state());
        assertEquals("", en.posture());
        assertEquals("", en.profile());
        assertEquals("", en.target());
        assertEquals("", en.distance());

        PvpScriptValues es = PvpScriptValues.of(null, ES);
        assertEquals("apagado", es.state());
        assertEquals("", es.profile());
    }

    // --- auto-pvp off -----------------------------------------------------------------------------

    @Test
    void autoPvpOffIsOffWithEmptyPostureTargetAndDistanceButKnowsTheActiveProfile() {
        PanelInput in = PanelInputs.base().autoPvpOn(false).profileName("aggressive").build();

        PvpScriptValues en = PvpScriptValues.of(in, EN);
        assertEquals("off", en.state());
        assertEquals("", en.posture());
        assertEquals("aggressive", en.profile());
        assertEquals("", en.target());
        assertEquals("", en.distance());

        PvpScriptValues es = PvpScriptValues.of(in, ES);
        assertEquals("apagado", es.state());
        assertEquals("aggressive", es.profile());
    }

    // --- auto-pvp on ------------------------------------------------------------------------------

    @Test
    void onReportsTranslatedStateAndPostureTheRawProfileAndTheTargetWithItsDistance() {
        PanelInput in = PanelInputs.base().profileName("balanced").state(CombatState.SURROUNDED)
            .posture(CombatPosture.THREATENED).target("Foo").targetDistance(12.34).build();

        PvpScriptValues en = PvpScriptValues.of(in, EN);
        assertEquals("SURROUNDED", en.state());
        assertEquals("THREATENED", en.posture());
        assertEquals("balanced", en.profile());
        assertEquals("Foo", en.target());
        assertEquals("12.3", en.distance());

        PvpScriptValues es = PvpScriptValues.of(in, ES);
        assertEquals("RODEADO", es.state());
        assertEquals("AMENAZADO", es.posture());
        assertEquals("12,3", es.distance());
    }

    @Test
    void onWithNoTargetLeavesTargetAndDistanceEmpty() {
        PanelInput in = PanelInputs.base().target(null).build();
        PvpScriptValues values = PvpScriptValues.of(in, EN);
        assertEquals("", values.target());
        assertEquals("", values.distance());
    }

    @Test
    void distanceAlwaysKeepsOneDecimalEvenForAWholeNumber() {
        PanelInput in = PanelInputs.base().target("Foo").targetDistance(10.0).build();
        assertEquals("10.0", PvpScriptValues.of(in, EN).distance());
        assertEquals("10,0", PvpScriptValues.of(in, ES).distance());
    }

    @Test
    void theProfileFieldIsNeverTranslatedOrAltered() {
        PanelInput in = PanelInputs.base().profileName("my-own-profile").build();
        assertEquals("my-own-profile", PvpScriptValues.of(in, EN).profile());
        assertEquals("my-own-profile", PvpScriptValues.of(in, ES).profile());
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
                PvpScriptValues values = PvpScriptValues.of(in, catalog);
                for (String value : List.of(values.state(), values.posture(), values.profile(), values.target(), values.distance())) {
                    assertFalse(CoordinateSentinel.isSuspect(value), value);
                }
            }
            assertFalse(CoordinateSentinel.isSuspect(PvpScriptValues.of(null, EN).toString()));
        }
    }
}
