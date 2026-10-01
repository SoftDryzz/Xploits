package com.xploits.pvp.crystal.core;

import com.xploits.pvp.crystal.core.MovementReach.ExposureFunction;
import com.xploits.pvp.crystal.core.MovementReach.Headroom;
import com.xploits.pvp.crystal.core.MovementReach.Offset;
import com.xploits.pvp.crystal.core.MovementReach.Ranked;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.7.2, crystal-aura++ under a roof: a raised reach point is lowered to the rise a box as big as ours can really make
 * in its own column (its {@link Headroom}), then read at its real exposure like any other point, instead of reading 1.0
 * for a box that would sit inside the roof. Fake geometry in every test: a point whose rise is above the true headroom
 * of its column overlaps the roof, and the fake exposure reads 1.0 there, as {@code ExposureAt} does; so the old
 * overload shows today's value. Every expected number is worked out by hand from the distance (vanilla's formula, then
 * the float cast), never with the code's own formula.
 */
class MovementReachHeadroomTest {
    private static final float NONE = 0f;

    /** The true geometry: 1.0 where the point's box would overlap the roof, {@code open} where it fits. */
    private static ExposureFunction inside(Headroom truth, double open) {
        return o -> o.dy() > truth.at(o.dx(), o.dz()) ? 1.0 : open;
    }

    /** A headroom of {@code free} everywhere, whose roof may vanish where {@code vanish} says. */
    private static Headroom vanishing(double free, BiPredicate<Double, Double> vanish) {
        return new Headroom() {
            @Override
            public double at(double dx, double dz) {
                return free;
            }

            @Override
            public boolean mayVanish(double dx, double dz) {
                return vanish.test(dx, dz);
            }
        };
    }

    /** The distinct points the search ranks, standing still with a landing bound of 6. */
    private static Set<Offset> rankedStill(double ex, double ey, double ez, Headroom headroom) {
        List<Ranked> ranked = MovementReach.rankedByWorstRaw(ex, ey, ez,
            MovementReach.clipped(MovementReach.reachPoints(0, 0, 6), headroom));
        return ranked.stream().map(Ranked::offset).collect(Collectors.toSet());
    }

    // 1. The lab case: a block right over the head, a crystal at our feet's level.

    @Test
    void theLabCaseNoLongerReadsTheRoofAsAnExposedJump() {
        // Standing still, a full block 0.2 over our box everywhere, the explosion at our feet's level 2.2 out and 1.2 to
        // the side, and nothing reaching us where the box fits (exposure 0). Today the halfway point (0, 0.625, 0) sits
        // in the roof: d = sqrt(2.2^2 + 0.625^2 + 1.2^2) = 2.582755, n = 1 - d/12 = 0.784770,
        // (n^2 + n) / 2 * 84 + 1 = 59.826668. Lowered, every raised point is (0, 0.2, 0), where the box fits: exposure
        // 0, so only the formula's + 1, the same as where we stand (d = 2.505993, exposure 0: 1.0).
        Headroom roof = (dx, dz) -> 0.2;
        ExposureFunction e = inside(roof, 0.0);
        assertEquals(59.826668f, MovementReach.worstReachRaw(2.2, 0, -1.2, 0, 0, 6, e, 1.0f), 0f, "today");
        assertEquals(1.0f, MovementReach.worstReachRaw(2.2, 0, -1.2, 0, 0, 6, roof, e, 1.0f), 0f, "lowered");
        assertEquals(Set.of(new Offset(0, 0, 0), new Offset(0, 0.2, 0)), rankedStill(2.2, 0, -1.2, roof));
    }

    // 2. The lowered point binds the result.

    @Test
    void theLoweredPointIsReadAtItsRealExposureAndBindsTheResult() {
        // The explosion at head height (2.2, 1.0, -1.2), a quarter of us exposed where the box fits. Standing:
        // d = 2.698148, n = 0.775154 * 0.25 = 0.193789, raw 10.716390. Today the full jump (0, 1.25, 0) is in the roof
        // and the closest: d = 2.518432, n = 0.790131, raw 60.406361. Lowered to (0, 0.2, 0): d = 2.630589,
        // n = 0.780784 * 0.25 = 0.195196, raw 10.798497, above the standing value, so it is the answer.
        Headroom roof = (dx, dz) -> 0.2;
        ExposureFunction e = inside(roof, 0.25);
        assertEquals(60.40636f, MovementReach.worstReachRaw(2.2, 1.0, -1.2, 0, 0, 6, e, 10.71639f), 0f, "today");
        assertEquals(10.798497f, MovementReach.worstReachRaw(2.2, 1.0, -1.2, 0, 0, 6, roof, e, 10.71639f), 0f, "lowered");
    }

    // 3. A roof two blocks up is read exactly: a partial rise.

    @Test
    void aRoofOneFreeBlockUpIsReadAtTheRiseTheBoxReallyMakes() {
        // The roof one free block over the head: the box rises 1.2 of the jump's 1.25. The explosion 2.5 up and 3 out,
        // in the open everywhere (exposure 1). Standing: d = sqrt(2.5^2 + 3^2) = 3.905125, raw 48.444107 (what dropping
        // the jump would read). The rise the box makes, (0, 1.2, 0): d = sqrt(1.3^2 + 3^2) = 3.269557, n = 0.727537,
        // raw 53.787575. Today the jump reads its unreachable 1.25: d = 3.25, raw 53.955730; skipping every
        // overlapping point would keep only the halfway point (0, 0.625, 0): d = 3.537743, raw 51.504089.
        Headroom roof = (dx, dz) -> 1.2;
        ExposureFunction e = o -> 1.0;
        float lowered = MovementReach.worstReachRaw(0, 2.5, 3, 0, 0, 6, roof, e, 48.444107f);
        assertEquals(53.787575f, lowered, 0f);
        assertEquals(53.95573f, MovementReach.worstReachRaw(0, 2.5, 3, 0, 0, 6, e, 48.444107f), 0f, "today");
        assertTrue(lowered > 51.50409f, "above what skipping the overlapping points reads");
        assertTrue(lowered > 48.444107f, "above what dropping the jump reads");
    }

    // 4. Each column has its own headroom.

    @Test
    void aRingPointThatHasLeftAOneBlockRoofKeepsItsFullJump() {
        // Moving slowly: v = (0.125, 0) over 7 pre-ticks, a ring of radius 0.875 (exact in binary). A one-block roof:
        // the box rises 0.2 where it is still under it (|dx| < 0.8 and |dz| < 0.8), freely elsewhere. The explosion at
        // (1.875, 1.25, 0), in the open (exposure 1). The ring point (0.875, 1.25, 0) has left the roof and keeps its
        // jump: d = 1.0, n = 11/12, raw 74.791664. Read with the centre's headroom it would be (0.875, 0.2, 0):
        // d = 1.45, raw 70.388229.
        Headroom oneBlock = (dx, dz) -> Math.abs(dx) < 0.8 && Math.abs(dz) < 0.8 ? 0.2 : Double.POSITIVE_INFINITY;
        assertEquals(74.791664f, MovementReach.worstReachRaw(1.875, 1.25, 0, 0.125, 0, 7, oneBlock, o -> 1.0, NONE), 0f);
    }

    // 5. The points are lowered after the halfway points are added.

    @Test
    void theHalfwayPointsAreLoweredToo() {
        // The same slow ring and one-block roof, nothing reaching us where the box fits (exposure 0). Every point
        // then fits once lowered, so the answer is the formula's + 1 (the floor here). Lowered before the halfway
        // points were added, the halfway point (0.4375, 1.25, 0) of the unlowered ring point (0.875, 1.25, 0) would
        // sit in the roof and read 1.0: d = 1.4375, raw 70.508949.
        Headroom oneBlock = (dx, dz) -> Math.abs(dx) < 0.8 && Math.abs(dz) < 0.8 ? 0.2 : Double.POSITIVE_INFINITY;
        assertEquals(1.0f, MovementReach.worstReachRaw(1.875, 1.25, 0, 0.125, 0, 7, oneBlock, inside(oneBlock, 0.0), 1.0f),
            0f);
    }

    // 6. No roof, nothing changes.

    @Test
    void withNothingOverheadThePointsAndTheResultAreTodaysBitForBit() {
        Random rnd = new Random(41);
        int checked = 0;
        for (int i = 0; i < 3000; i++) {
            long ticks = 1 + rnd.nextInt(12);
            double speed = rnd.nextDouble() * (MovementReach.STILL_RADIUS / ticks);
            double angle = rnd.nextDouble() * 2 * Math.PI;
            double vx = speed * Math.cos(angle), vz = speed * Math.sin(angle);
            if (!MovementReach.readsRealExposure(vx, vz, ticks)) continue;
            double ex = (rnd.nextDouble() - 0.5) * 14, ey = (rnd.nextDouble() - 0.5) * 8, ez = (rnd.nextDouble() - 0.5) * 14;
            float floor = rnd.nextFloat() * 3;
            double cover = rnd.nextDouble();
            ExposureFunction e = o -> Math.abs(o.dx() * 7 + o.dz() * 3) % 1.0 < 0.5 ? cover : 1.0;
            double high = i % 3 == 0 ? Double.POSITIVE_INFINITY : MovementReach.JUMP_HEIGHT + rnd.nextDouble() * 5;
            Headroom free = (dx, dz) -> high;
            List<Offset> reach = MovementReach.reachPoints(vx, vz, ticks);
            assertEquals(reach, MovementReach.clipped(reach, free));
            // Today's still branch as it was before the headroom existed: the reach points, ranked, branch and bound.
            float today = MovementReach.worstRawDamage(MovementReach.rankedByWorstRaw(ex, ey, ez, reach), e, floor);
            float now = MovementReach.worstReachRaw(ex, ey, ez, vx, vz, ticks, free, e, floor);
            assertEquals(Float.floatToRawIntBits(today), Float.floatToRawIntBits(now), "trial " + i);
            checked++;
        }
        assertTrue(checked > 1000, "the property must exercise the still branch: " + checked);
    }

    // 7. A reading that is not a headroom changes nothing.

    @Test
    void aHeadroomThatIsNotANumberNorAboveZeroLeavesTheLabCaseAsToday() {
        // The real roof is still 0.2 over the head (the exposure says so); only the reading is broken.
        ExposureFunction e = inside((dx, dz) -> 0.2, 0.0);
        for (double broken : new double[] {Double.NaN, -1, -1e-7, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            assertEquals(59.826668f, MovementReach.worstReachRaw(2.2, 0, -1.2, 0, 0, 6, (dx, dz) -> broken, e, 1.0f), 0f,
                "headroom " + broken);
        }
    }

    // 8. No room at all.

    @Test
    void aHeadroomOfZeroFoldsTheJumpIntoStandingStill() {
        // The box cannot rise at all: the jump and its halfway point are where we stand, one single point, +0.0 so
        // that it is the very same point (a record compares -0.0 and 0.0 as different).
        for (double none : new double[] {0.0, -0.0}) {
            Headroom roof = (dx, dz) -> none;
            assertEquals(1.0f, MovementReach.worstReachRaw(2.2, 0, -1.2, 0, 0, 6, roof, inside(roof, 0.0), 1.0f), 0f);
            assertEquals(Set.of(new Offset(0, 0, 0)), rankedStill(2.2, 0, -1.2, roof), "headroom " + none);
        }
    }

    // 9. The moving branch never asks.

    @Test
    void aboveTheStillRadiusTheHeadroomIsNeverAskedAndTheResultIs070s() {
        Random rnd = new Random(72);
        int checked = 0;
        for (int i = 0; i < 3000; i++) {
            double vx = (rnd.nextDouble() - 0.5) * 0.8, vz = (rnd.nextDouble() - 0.5) * 0.8;
            long ticks = rnd.nextInt(14);
            if (MovementReach.readsRealExposure(vx, vz, ticks)) continue;
            double ex = (rnd.nextDouble() - 0.5) * 14, ey = (rnd.nextDouble() - 0.5) * 8, ez = (rnd.nextDouble() - 0.5) * 14;
            float floor = rnd.nextFloat() * 10;
            AtomicInteger asked = new AtomicInteger();
            Headroom counting = new Headroom() {
                @Override
                public double at(double dx, double dz) {
                    asked.incrementAndGet();
                    return 0.2;
                }

                @Override
                public boolean mayVanish(double dx, double dz) {
                    asked.incrementAndGet();
                    return false;
                }
            };
            float got = MovementReach.worstReachRaw(ex, ey, ez, vx, vz, ticks, counting, o -> 0.0, floor);
            assertEquals(Math.max(floor, MovementReach.worstRawDamage(ex, ey, ez, vx, vz, ticks)), got, 0f, "trial " + i);
            assertEquals(0, asked.get(), "no headroom is asked above the still radius");
            checked++;
        }
        assertTrue(checked > 1000, "the property must exercise the moving branch: " + checked);
    }

    // 10. Never raised, never moved sideways.

    @Test
    void aPointIsOnlyEverLoweredAndNeverMovedSideways() {
        double[] readings = {Double.NaN, -1, -0.0, 0.0, 0.1, 0.2, 0.5, 0.625, 1.0, 1.2, 1.25, 2, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY};
        Random rnd = new Random(13);
        int lowered = 0;
        for (int i = 0; i < 500; i++) {
            long ticks = 1 + rnd.nextInt(12);
            double speed = rnd.nextDouble() * (MovementReach.STILL_RADIUS / ticks);
            double angle = rnd.nextDouble() * 2 * Math.PI;
            int salt = rnd.nextInt(1000);
            Headroom h = (dx, dz) -> readings[Math.floorMod((int) Math.round(dx * 97 + dz * 31) + salt, readings.length)];
            for (Offset o : MovementReach.reachPoints(speed * Math.cos(angle), speed * Math.sin(angle), ticks)) {
                Offset c = MovementReach.clipped(o, h);
                assertEquals(o.dx(), c.dx(), 0.0, "never sideways");
                assertEquals(o.dz(), c.dz(), 0.0, "never sideways");
                if (o.dy() > 0) {
                    assertTrue(c.dy() >= 0 && c.dy() <= o.dy(), "lowered to between our feet and its rise: " + o + " -> " + c);
                } else {
                    assertEquals(o, c, "a point at our feet stays");
                }
                if (c.dy() < o.dy()) lowered++;
            }
        }
        assertTrue(lowered > 1000, "the property must lower points: " + lowered);
    }

    // 11. The owner's decision: a roof that may vanish (someone is mining it, or a crystal can break it) keeps its
    // column's full jump.

    @Test
    void aColumnWhoseRoofMayVanishKeepsItsJumpAsToday() {
        // The lab case again, but the block over our head may be gone before the crystal explodes (mined, or blown
        // away), and a jump would then reach the full height. Only that column is left as it is.
        ExposureFunction e = inside((dx, dz) -> 0.2, 0.0);
        Headroom overUs = vanishing(0.2, (dx, dz) -> Math.abs(dx) < 0.8 && Math.abs(dz) < 0.8);
        assertEquals(59.826668f, MovementReach.worstReachRaw(2.2, 0, -1.2, 0, 0, 6, overUs, e, 1.0f), 0f);
        Headroom elsewhere = vanishing(0.2, (dx, dz) -> Math.abs(dx) >= 0.8 || Math.abs(dz) >= 0.8);
        assertEquals(1.0f, MovementReach.worstReachRaw(2.2, 0, -1.2, 0, 0, 6, elsewhere, e, 1.0f), 0f);
        assertEquals(new Offset(0, 1.25, 0), MovementReach.clipped(new Offset(0, 1.25, 0), overUs));
        assertEquals(new Offset(0, 0.2, 0), MovementReach.clipped(new Offset(0, 1.25, 0), elsewhere));
    }
}
