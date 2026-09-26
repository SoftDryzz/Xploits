package com.xploits.pvp.crystal.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The raw damage of an end crystal, before difficulty, shield, armour and effects, as the server computes it
 * ({@code ExplosionBehavior.calculateDamage}): unrounded, where Meteor's {@code DamageUtils} truncates it to an
 * int. Every value here is exact in binary.
 */
class RawExplosionTest {
    @Test
    void theNumbersAreVanillas() {
        // A crystal explodes with power 6: vanilla's reach is power * 2 = 12, Meteor's "power" 12.
        assertEquals(12.0, RawExplosion.CRYSTAL_POWER, 0.0);
        assertEquals(-1.0, RawExplosion.UNKNOWN, 0.0);
    }

    @Test
    void theFormulaIsVanillasAndUnrounded() {
        // i = (1 - d) * exposure; ((i * i + i) / 2 * 7 * 12 + 1)
        assertEquals(85.0, RawExplosion.damage(0, 1), 0.0);
        assertEquals(32.5, RawExplosion.damage(0.5, 1), 0.0);
        assertEquals(32.5, RawExplosion.damage(0, 0.5), 0.0);
        // Meteor would say 14 here: (int) 14.125
        assertEquals(14.125, RawExplosion.damage(0.75, 1), 0.0);
        // At the edge of the reach, and fully behind a wall, a crystal still deals 1.
        assertEquals(1.0, RawExplosion.damage(1, 1), 0.0);
        assertEquals(1.0, RawExplosion.damage(0.25, 0), 0.0);
    }

    @Test
    void beyondTheReachThereIsNoHitAtAll() {
        // ExplosionImpl.damageEntities skips an entity whose distance / 12 is above 1.
        assertEquals(0.0, RawExplosion.damage(Math.nextUp(1.0), 1), 0.0);
        assertEquals(0.0, RawExplosion.crystal(12.5, 1), 0.0);
    }

    @Test
    void aCrystalDividesTheDistanceByTwelve() {
        assertEquals(14.125, RawExplosion.crystal(9, 1), 0.0);
        assertEquals(32.5, RawExplosion.crystal(6, 1), 0.0);
        assertEquals(RawExplosion.damage(3.0 / 12.0, 0.75), RawExplosion.crystal(3, 0.75), 0.0);
    }

    @Test
    void itRefusesWhatCannotBe() {
        assertThrows(IllegalArgumentException.class, () -> RawExplosion.damage(Double.NaN, 1));
        assertThrows(IllegalArgumentException.class, () -> RawExplosion.damage(-0.5, 1));
        assertThrows(IllegalArgumentException.class, () -> RawExplosion.damage(0.5, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> RawExplosion.damage(0.5, -0.25));
        assertThrows(IllegalArgumentException.class, () -> RawExplosion.damage(0.5, 1.25));
        assertThrows(IllegalArgumentException.class, () -> RawExplosion.crystal(Double.POSITIVE_INFINITY, 1));
    }
}
