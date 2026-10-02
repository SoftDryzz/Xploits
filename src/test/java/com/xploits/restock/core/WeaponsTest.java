package com.xploits.restock.core;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P17 corrected (pre-flight V11): a weapon is never a digging tool — by item tag, the mace and the trident by item. */
class WeaponsTest {
    @Test
    void swordsAxesAndSpearsByTagTheMaceAndTheTridentByItem() {
        assertTrue(Weapons.of("minecraft:diamond_sword", Set.of("minecraft:swords")));
        assertTrue(Weapons.of("minecraft:netherite_axe", Set.of("minecraft:axes")));
        assertTrue(Weapons.of("minecraft:iron_spear", Set.of("minecraft:spears")));
        assertTrue(Weapons.of("minecraft:mace", Set.of()));
        assertTrue(Weapons.of("minecraft:trident", Set.of()));
    }

    @Test
    void everythingElseMayDig() {
        assertFalse(Weapons.of("minecraft:diamond_pickaxe", Set.of("minecraft:pickaxes")),
            "\"pickaxes\" ends with \"axes\": only the exact tag id counts");
        assertFalse(Weapons.of("minecraft:shears", Set.of()));
        assertFalse(Weapons.of("minecraft:air", Set.of()), "the hand");
    }
}
