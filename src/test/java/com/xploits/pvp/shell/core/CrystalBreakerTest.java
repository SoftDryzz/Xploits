package com.xploits.pvp.shell.core;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Breaking an opponent's crystal next to us (surround++ spec §6.4). */
class CrystalBreakerTest {
    private static final StandingCrystal GAP = new StandingCrystal(7, new Cell(1, 0, 0), false);

    private static Optional<StandingCrystal> choose(ShellSnapshot.Builder s, FakeOracle oracle) {
        return CrystalBreaker.choose(s.build(), oracle);
    }

    @Test
    void aCrystalNextToUsIsBrokenWhenItLeavesUsTwoOrMore() {
        assertEquals(Optional.of(GAP), choose(Scenes.obsidianFloor().crystal(GAP).health(36), new FakeOracle().at(1, 0, 0, 10)));
        assertEquals(Optional.of(GAP), choose(Scenes.obsidianFloor().crystal(GAP).health(12), new FakeOracle().at(1, 0, 0, 10)),
            "12 - 10 = 2: exactly the floor");
    }

    @Test
    void notWhenItWouldTakeUsBelowTwo() {
        assertEquals(Optional.empty(), choose(Scenes.obsidianFloor().crystal(GAP).health(11), new FakeOracle().at(1, 0, 0, 10)));
    }

    @Test
    void butYesWhenItLandsInsideOurHurtWindow() {
        FakeOracle oracle = new FakeOracle().at(1, 0, 0, 10);
        // 2 ticks since a 12, 3 ticks of round trip: 5 < 10 and 10 <= 11.5.
        assertEquals(Optional.of(GAP), choose(Scenes.obsidianFloor().crystal(GAP).health(11)
            .window(new HurtWindow(2, 12)).pingTicks(3), oracle));
        assertEquals(Optional.empty(), choose(Scenes.obsidianFloor().crystal(GAP).health(11)
            .window(new HurtWindow(2, 12)).pingTicks(8), oracle), "2 + 8 = 10: the window is gone when it lands");
    }

    @Test
    void ourOwnCrystalsAreLeftToTheAura() {
        StandingCrystal ours = new StandingCrystal(7, new Cell(1, 0, 0), true);
        assertEquals(Optional.empty(), choose(Scenes.obsidianFloor().crystal(ours), new FakeOracle().at(1, 0, 0, 10)));
    }

    @Test
    void aCrystalMoreThanTwoBlocksAwayIsLeftAlone() {
        StandingCrystal far = new StandingCrystal(8, new Cell(3, 0, 0), false);
        assertEquals(Optional.empty(), choose(Scenes.obsidianFloor().crystal(far), new FakeOracle().at(3, 0, 0, 10)));
    }

    @Test
    void aHarmlessCrystalIsLeftAlone() {
        assertEquals(Optional.empty(), choose(Scenes.obsidianFloor().crystal(GAP), new FakeOracle().at(1, 0, 0, 1.9)));
    }

    @Test
    void theMostDangerousGoesFirst() {
        StandingCrystal head = new StandingCrystal(9, new Cell(0, 1, 1), false);
        assertEquals(Optional.of(head), choose(Scenes.obsidianFloor().crystal(GAP).crystal(head),
            new FakeOracle().at(1, 0, 0, 10).at(0, 1, 1, 12)));
    }

    @Test
    void aDamageThatIsNotANumberIsNotRiskedAtLowHealth() {
        assertEquals(Optional.empty(), choose(Scenes.obsidianFloor().crystal(GAP).health(20),
            new FakeOracle().bound(1, 0, 0, 10).exact(1, 0, 0, Double.NaN)));
    }

    @Test
    void offWithTheSettingOff() {
        ShellSettings off = new ShellSettings(2, true, true, true, false, false, 6);
        assertTrue(choose(Scenes.obsidianFloor().crystal(GAP).settings(off), new FakeOracle().at(1, 0, 0, 10)).isEmpty());
    }
}
