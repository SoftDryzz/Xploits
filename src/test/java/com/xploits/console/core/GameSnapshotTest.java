package com.xploits.console.core;

import com.xploits.shared.core.i18n.Language;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameSnapshotTest {
    private static final GameSnapshot FULL = new GameSnapshot("minecraft:the_nether", 3, 1, 64, 87,
        new GameSnapshot.Progress(3, 12, 850), null, 18.5, 20, 128, 7, 0, null,
        List.of(new GameSnapshot.ModuleStatus("auto-pvp", true, "SUPERFICIE"),
            new GameSnapshot.ModuleStatus("auto-travel", false, "")), Language.ES);

    @Test
    void exactEncodingWorkedOutByHand() {
        assertEquals("dim=minecraft:the_nether;ply=3;frn=1;fwk=64;ely=87;trv=3/12/850;swp=-;hp=18.5;arm=20;"
            + "obs=128;cry=7;web=0;anv=-;mod=auto-pvp,1,SUPERFICIE|auto-travel,0,;lng=es", FULL.encode());
    }

    @Test
    void roundTripWithSeparatorsInTheTexts() {
        GameSnapshot odd = new GameSnapshot("a;b=c", 0, 0, 0, -1, null, new GameSnapshot.Progress(2, 7, 12400),
            0.0, 0, 0, 0, 0, 0,
            List.of(new GameSnapshot.ModuleStatus("m,|;=\\", true, "SUPERFICIE · Foo, el de; la=base|x")), Language.EN);
        assertEquals(odd, GameSnapshot.decode(odd.encode()));
        assertEquals(FULL, GameSnapshot.decode(FULL.encode()));
    }

    @Test
    void unknownIsNotZero() {
        GameSnapshot empty = GameSnapshot.decode(GameSnapshot.withoutPlayer(List.of(), Language.EN).encode());
        assertNull(empty.players());
        assertNull(empty.health());
        assertTrue(empty.modules().isEmpty());
    }

    @Test
    void withModulesChangesOnlyTheModules() {
        GameSnapshot other = FULL.withModules(List.of());
        assertTrue(other.modules().isEmpty());
        assertEquals(FULL.fireworks(), other.fireworks());
        assertEquals(FULL.travel(), other.travel());
    }

    @Test
    void zeroStaysZero() {
        assertEquals(0, GameSnapshot.decode(FULL.encode()).webs());
    }

    @Test
    void anUnknownKeyIsRejectedByName() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> GameSnapshot.decode(FULL.encode() + ";zzz=1"));
        assertTrue(e.getMessage().contains("zzz"));
    }

    @Test
    void aMissingKeyIsRejectedByName() {
        String withoutArmor = FULL.encode().replace("arm=20;", "");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> GameSnapshot.decode(withoutArmor));
        assertTrue(e.getMessage().contains("arm"));
    }

    @Test
    void languageTravelsInTheSnapshot() {
        GameSnapshot s = GameSnapshot.withoutPlayer(List.of(), Language.ES);
        assertEquals(Language.ES, GameSnapshot.decode(s.encode()).language());
    }

    @Test
    void unknownLanguageFallsBackToEnglish() {
        String s = GameSnapshot.withoutPlayer(List.of(), Language.ES).encode().replace("lng=es", "lng=fr");
        assertEquals(Language.EN, GameSnapshot.decode(s).language());
    }

    @Test
    void languageIsRequired() {
        String s = GameSnapshot.withoutPlayer(List.of(), Language.ES).encode().replace(";lng=es", "");
        assertThrows(IllegalArgumentException.class, () -> GameSnapshot.decode(s));
    }
}
