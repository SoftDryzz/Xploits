package com.xploits.pvp.recorder.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.Msg;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DamageKindTest {
    /** Every vanilla id the spec maps, with the kind it must give when the direct source is not a crystal. */
    private static final Map<String, DamageKind> MAPPED = new LinkedHashMap<>();

    static {
        MAPPED.put("player_explosion", DamageKind.EXPLOSION);
        MAPPED.put("explosion", DamageKind.EXPLOSION);
        MAPPED.put("bad_respawn_point", DamageKind.RESPAWN_POINT);
        MAPPED.put("falling_anvil", DamageKind.ANVIL);
        for (String id : new String[] {"player_attack", "mace_smash", "spear"}) MAPPED.put(id, DamageKind.MELEE);
        for (String id : new String[] {"arrow", "trident", "fireworks", "thrown", "wind_charge", "mob_projectile",
            "fireball", "unattributed_fireball", "wither_skull", "spit"}) {
            MAPPED.put(id, DamageKind.PROJECTILE);
        }
        for (String id : new String[] {"fall", "fly_into_wall", "ender_pearl", "stalagmite"}) MAPPED.put(id, DamageKind.FALL);
        for (String id : new String[] {"in_fire", "on_fire", "lava", "hot_floor", "campfire", "lightning_bolt"}) {
            MAPPED.put(id, DamageKind.FIRE);
        }
        for (String id : new String[] {"magic", "indirect_magic", "wither", "dragon_breath"}) MAPPED.put(id, DamageKind.MAGIC);
        for (String id : new String[] {"mob_attack", "mob_attack_no_aggro", "sting", "sonic_boom"}) MAPPED.put(id, DamageKind.MOB);
    }

    @Test
    void everyMappedIdGivesItsKind() {
        MAPPED.forEach((id, kind) -> assertEquals(kind, DamageKind.classify("minecraft:" + id, false), id));
    }

    @Test
    void theCrystalFlagTurnsOnlyTheTwoExplosionsIntoCrystal() {
        assertEquals(DamageKind.CRYSTAL, DamageKind.classify("minecraft:player_explosion", true));
        assertEquals(DamageKind.CRYSTAL, DamageKind.classify("minecraft:explosion", true));
        MAPPED.forEach((id, kind) -> {
            if (!id.endsWith("explosion")) assertEquals(kind, DamageKind.classify("minecraft:" + id, true), id);
        });
        assertEquals(DamageKind.OTHER, DamageKind.classify("minecraft:generic", true));
    }

    @Test
    void unknownIdsAndOtherNamespacesAreOther() {
        assertEquals(DamageKind.OTHER, DamageKind.classify("minecraft:generic", false));
        assertEquals(DamageKind.OTHER, DamageKind.classify("minecraft:out_of_world", false));
        assertEquals(DamageKind.OTHER, DamageKind.classify("minecraft:starve", false));
        assertEquals(DamageKind.OTHER, DamageKind.classify("somemod:fall", false));
        assertEquals(DamageKind.OTHER, DamageKind.classify("fall", false));
        assertEquals(DamageKind.OTHER, DamageKind.classify("", false));
        assertEquals(DamageKind.OTHER, DamageKind.classify(null, true));
    }

    @Test
    void classifyNeverAnswersUnseen() {
        for (String id : MAPPED.keySet()) {
            assertFalse(DamageKind.classify("minecraft:" + id, true) == DamageKind.UNSEEN, id);
            assertFalse(DamageKind.classify("minecraft:" + id, false) == DamageKind.UNSEEN, id);
        }
        assertEquals(DamageKind.OTHER, DamageKind.classify("minecraft:unseen", false));
    }

    @Test
    void onlyCrystalToProjectileIsCombat() {
        Set<DamageKind> combat = EnumSet.of(DamageKind.CRYSTAL, DamageKind.EXPLOSION, DamageKind.RESPAWN_POINT,
            DamageKind.ANVIL, DamageKind.MELEE, DamageKind.PROJECTILE);
        for (DamageKind kind : DamageKind.values()) assertEquals(combat.contains(kind), kind.isCombat(), kind.name());
        assertTrue(DamageKind.CRYSTAL.isCombat());
        assertFalse(DamageKind.FALL.isCombat());
    }

    @Test
    void everyKindIsNamedInBothCatalogs() {
        Catalog es = Catalog.load(Language.ES, p -> {
            throw new AssertionError(p);
        });
        Catalog en = Catalog.load(Language.EN, p -> {
            throw new AssertionError(p);
        });
        for (DamageKind kind : DamageKind.values()) {
            assertFalse(es.render(Msg.of(kind.label())).isBlank(), kind.name());
            assertFalse(en.render(Msg.of(kind.label())).isBlank(), kind.name());
        }
        assertEquals("crystal", en.render(Msg.of(DamageKind.CRYSTAL.label())));
        assertEquals("caída", es.render(Msg.of(DamageKind.FALL.label())));
    }
}
