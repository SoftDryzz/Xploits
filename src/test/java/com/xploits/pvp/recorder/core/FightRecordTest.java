package com.xploits.pvp.recorder.core;

import com.xploits.pvp.recorder.core.CombatEvent.AttackerKind;
import com.xploits.pvp.recorder.core.CombatEvent.SelfDamaged;
import com.xploits.pvp.recorder.core.FightRecord.DamageEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The records refuse the shapes the rest of the recorder relies on never seeing. */
class FightRecordTest {
    @Test
    void anAttackerNameGoesWithPlayerAndOnlyWithIt() {
        new DamageEvent(1, DamageKind.CRYSTAL, AttackerKind.PLAYER, "Foo", 20, 9.5, false);
        new DamageEvent(1, DamageKind.CRYSTAL, AttackerKind.SELF, null, 20, 9.5, false);
        assertThrows(IllegalArgumentException.class,
            () -> new DamageEvent(1, DamageKind.CRYSTAL, AttackerKind.PLAYER, null, 20, 9.5, false));
        assertThrows(IllegalArgumentException.class,
            () -> new DamageEvent(1, DamageKind.CRYSTAL, AttackerKind.NONE, "Foo", 20, 9.5, false));
        assertThrows(IllegalArgumentException.class,
            () -> new SelfDamaged(DamageKind.MELEE, AttackerKind.PLAYER, null, false));
        assertThrows(IllegalArgumentException.class,
            () -> new SelfDamaged(DamageKind.MELEE, AttackerKind.SELF, "self", false));
    }

    @Test
    void aUsernameCalledSelfIsJustAPlayer() {
        SelfDamaged hit = new SelfDamaged(DamageKind.MELEE, AttackerKind.PLAYER, "self", false);
        assertEquals(AttackerKind.PLAYER, hit.by());
    }

    @Test
    void unseenHitsComeOnlyFromTheLedgerAndHaveNoAttacker() {
        assertThrows(IllegalArgumentException.class,
            () -> new SelfDamaged(DamageKind.UNSEEN, AttackerKind.NONE, null, false));
        assertThrows(IllegalArgumentException.class,
            () -> new DamageEvent(1, DamageKind.UNSEEN, AttackerKind.SELF, null, 20, 10, false));
        new DamageEvent(1, DamageKind.UNSEEN, AttackerKind.NONE, null, 20, 10, false);
    }

    @Test
    void aHitNeverRaisesHealthNorLeavesItNegative() {
        assertThrows(IllegalArgumentException.class,
            () -> new DamageEvent(1, DamageKind.FALL, AttackerKind.NONE, null, 10, 12, false));
        assertThrows(IllegalArgumentException.class,
            () -> new DamageEvent(1, DamageKind.FALL, AttackerKind.NONE, null, 10, -1, true));
        new DamageEvent(1, DamageKind.FALL, AttackerKind.NONE, null, 10, 0, true);
    }

    @Test
    void theListsAreCopiedAndRefuseNull() {
        List<String> modules = new ArrayList<>(List.of("auto-totem"));
        FightRecord f = fight(modules);
        modules.add("surround");
        assertEquals(List.of("auto-totem"), f.modulesAtStart());
        assertThrows(NullPointerException.class, () -> fight(null));
        assertThrows(IllegalArgumentException.class, () -> new FightRecord(FightRecord.SCHEMA, "0.5.0", 10, 5, 0,
            FightOutcome.ENDED, false, FightMode.MANUAL, 0, List.of(), 0, totals(), List.of(), 0, List.of(), List.of(),
            List.of(), List.of()));
    }

    private static FightRecord.SelfTotals totals() {
        return new FightRecord.SelfTotals(0, 0, 0, false, 0, 0, 0, 0, 0);
    }

    private static FightRecord fight(List<String> modules) {
        return new FightRecord(FightRecord.SCHEMA, "0.5.0", 0, 1000, 1, FightOutcome.ENDED, false, FightMode.MANUAL, 0,
            List.of(), 0, totals(), List.of(), 0, List.of(), modules, List.of(), List.of());
    }
}
