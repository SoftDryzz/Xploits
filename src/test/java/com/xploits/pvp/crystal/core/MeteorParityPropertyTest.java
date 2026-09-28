package com.xploits.pvp.crystal.core;

import com.xploits.pvp.crystal.core.CrystalSettings.AutoSwitch;
import com.xploits.pvp.crystal.core.CrystalSettings.PauseMode;
import com.xploits.pvp.crystal.core.MeteorReference.Act;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static com.xploits.pvp.crystal.core.CrystalSetting.RISK;
import static com.xploits.pvp.crystal.core.CrystalSetting.SELF_BUDGET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With {@code self-budget} off, crystal-aura++ decides exactly what Meteor's CrystalAura decides: many generated
 * fights, each with its own random value for every setting the brain reads, drive {@link CrystalBrain} and
 * {@link MeteorReference} (Meteor's code transcribed on its own) with the same events in the game's order, and
 * every answer must be the same: each break, anti-weakness swap and placement with its hand and swap, whether
 * the scan runs, and the last-rotation hold.
 *
 * <p>The fights keep to what the game can produce: crystals appear (our placements after a delay, others at
 * random) and disappear (mostly after an attack), are measured again each tick, and settings change now and
 * then, also between two ticks. Values sit on a grid of quarters and on each setting's own boundary, so the
 * comparisons land exactly on Meteor's edges.
 */
class MeteorParityPropertyTest {
    private static final int FIGHTS = 600;
    private static final int PRE_TICKS = 80;
    private static final List<String> PLAYERS = List.of("a", "b", "c");
    private static final int SPOTS = 12;

    @Test
    @Covers({SELF_BUDGET, RISK})
    void withTheBudgetOffEveryDecisionIsMeteors() {
        int decisions = 0;
        for (long seed = 1; seed <= FIGHTS; seed++) decisions += new Fight(seed).run();
        // The fights must decide things, or the comparison proves nothing.
        assertTrue(decisions > FIGHTS * 10, "only " + decisions + " actions in " + FIGHTS + " fights");
    }

    /** One generated fight. */
    private static final class Fight {
        private final long seed;
        private final Random r;
        private final CrystalBrain brain = new CrystalBrain();
        private final MeteorReference meteor = new MeteorReference();
        private CrystalSettings s;
        /** The end crystals in the world, in entity order. */
        private final Map<Integer, CrystalSeen> world = new LinkedHashMap<>();
        private final Set<Integer> attacked = new HashSet<>();
        /** Our placements whose crystal is on its way: position, and the gaps left before it appears. */
        private final List<long[]> coming = new ArrayList<>();
        /** Attack packets that go out after the next pre-tick (a fast-break's rotation callback). */
        private int attacksAfterNextPreTick;
        private int nextId = 1;
        private long tick;
        private int actions;

        Fight(long seed) {
            this.seed = seed;
            this.r = new Random(seed);
            this.s = settings();
        }

        int run() {
            for (tick = 1; tick <= PRE_TICKS; tick++) {
                between();
                preTick();
            }
            return actions;
        }

        private String at(String what) {
            return "fight " + seed + ", pre-tick " + tick + ", " + what + ", settings " + s;
        }

        private void same(Optional<Action> ours, Optional<Act> meteors, String what) {
            assertEquals(meteors, ours.map(Act::of), at(what));
            if (ours.isPresent()) actions++;
        }

        private void attackSent() {
            brain.attackSent();
            meteor.attackSent();
        }

        // Between two pre-ticks: removals, a setting changed, new crystals (EntityAdded and fast-break).

        private void between() {
            for (Iterator<Integer> it = world.keySet().iterator(); it.hasNext(); ) {
                int id = it.next();
                if (r.nextDouble() < (attacked.contains(id) ? 0.6 : 0.04)) {
                    it.remove();
                    brain.crystalRemoved(id);
                    meteor.entityRemoved(id);
                }
            }
            if (r.nextDouble() < 0.03) s = changeOne(s);

            List<Long> arriving = new ArrayList<>();
            for (Iterator<long[]> it = coming.iterator(); it.hasNext(); ) {
                long[] c = it.next();
                if (--c[1] <= 0) {
                    it.remove();
                    arriving.add(c[0]);
                }
            }
            if (r.nextDouble() < 0.25) arriving.add(1L + r.nextInt(SPOTS));
            for (long pos : arriving) {
                CrystalSeen c = crystal(nextId++, pos);
                world.put(c.id(), c);
                // Now and then a crystal is first seen in a pre-tick list, with no EntityAdded before it.
                if (r.nextDouble() < 0.1) continue;
                // Meteor reads its settings, your health and your hands as they are at that moment.
                if (r.nextDouble() < 0.05) s = changeOne(s);
                double health = health();
                CrystalTick.Hands hands = hands();
                Optional<Action> ours = brain.crystalAdded(s, c, health, hands);
                Optional<Act> meteors = meteor.entityAdded(s, c, health, hands);
                same(ours, meteors, "crystal " + c.id() + " added");
                if (ours.isPresent() && ours.get().decision().kind() == Decision.Kind.BREAK) {
                    attacked.add(c.id());
                    // With rotate off the attack goes out at once (line 861), otherwise in the rotation
                    // callback, which runs after the next pre-tick.
                    if (!s.rotate()) attackSent();
                    else attacksAfterNextPreTick++;
                }
            }
        }

        // One pre-tick: break at HIGH, the scan and its placement, the last-rotation hold, then the attacks.

        private void preTick() {
            world.replaceAll((id, c) -> r.nextDouble() < 0.3 ? crystal(id, c.pos()) : c);
            double health = health();
            boolean using = r.nextDouble() < 0.1;
            boolean mining = r.nextDouble() < 0.1;
            boolean lagging = r.nextDouble() < 0.05;
            boolean pauseModule = r.nextDouble() < 0.05;
            CrystalTick t = new CrystalTick(tick, health, r.nextInt(3), using, mining, lagging, pauseModule, hands(),
                targets(), List.copyOf(world.values()), List.of());

            Optional<Action> ours = brain.breakPhase(s, t);
            Optional<Act> meteors = meteor.preTick(s, t);
            same(ours, meteors, "break phase");
            int attacksNow = 0;
            if (ours.isPresent() && ours.get().decision().kind() == Decision.Kind.BREAK) {
                attacked.add((int) ours.get().decision().ref());
                if (!s.rotate()) attackSent();
                else attacksNow++;
            }

            assertEquals(meteor.scanRegistered(), brain.wantsPlacement(), at("scan"));
            if (brain.wantsPlacement()) {
                double scanHealth = r.nextDouble() < 0.8 ? health : health();
                List<Candidate> spots = candidates();
                Optional<Action> place = brain.placePhase(scanHealth, spots);
                same(place, meteor.scanAfter(scanHealth, spots), "place phase");
                if (place.isPresent()) {
                    long pos = place.get().decision().ref();
                    brain.placed(pos, r.nextInt(8));
                    if (r.nextDouble() < 0.7) coming.add(new long[] {pos, 1 + r.nextInt(9)});
                }
            }
            assertEquals(meteor.rotatesToLastRotation(), brain.holdLastRotation(), at("last-rotation hold"));

            // The rotation callbacks (SendMovementPacketsEvent.Post): this pre-tick's attack and the fast-breaks
            // decided before it. Now and then one never runs.
            for (int i = attacksNow + attacksAfterNextPreTick; i > 0; i--) {
                if (r.nextDouble() < 0.95) attackSent();
            }
            attacksAfterNextPreTick = 0;
        }

        // What the world looks like

        private double quarters(int max) {
            return r.nextInt(max * 4 + 1) / 4.0;
        }

        private double oneOf(double... values) {
            return values[r.nextInt(values.length)];
        }

        /** A value on the grid, or on the setting's own boundary (at it, or a quarter either side). */
        private double near(double boundary, int max) {
            return switch (r.nextInt(4)) {
                case 0 -> boundary;
                case 1 -> boundary + 0.25;
                case 2 -> Math.max(0, boundary - 0.25);
                default -> quarters(max);
            };
        }

        private double health() {
            return switch (r.nextInt(3)) {
                case 0 -> near(s.pauseHealth(), 24);
                case 1 -> near(s.maxDamage(), 24);
                default -> oneOf(20, 12, 9, 7, 6, 5.25, 3, 1.5);
            };
        }

        private CrystalTick.Hands hands() {
            boolean inHotbar = r.nextDouble() < 0.9;
            boolean main = inHotbar && r.nextDouble() < 0.5;
            boolean off = inHotbar && r.nextDouble() < 0.25;
            boolean mainBreaks = r.nextDouble() < 0.5;
            // The budget is always off here (settings(), below): finishing-blow never reads this either way.
            return new CrystalTick.Hands(inHotbar, main, off, r.nextDouble() < 0.15, r.nextDouble() < 0.1,
                effect(), effect(), mainBreaks, mainBreaks || r.nextDouble() < 0.6, r.nextDouble() < 0.5);
        }

        private int effect() {
            return r.nextDouble() < 0.2 ? r.nextInt(3) : CrystalTick.Hands.NO_EFFECT;
        }

        private List<TargetView> targets() {
            List<TargetView> targets = new ArrayList<>();
            double range = s.targetRange() * s.targetRange();
            for (String name : PLAYERS) {
                if (r.nextDouble() < 0.2) continue;
                double squared = switch (r.nextInt(4)) {
                    case 0 -> range;
                    case 1 -> Math.nextUp(range);
                    case 2 -> oneOf(0, 9, 25, 100, 144);
                    default -> quarters(300);
                };
                double health = r.nextBoolean() ? near(s.facePlaceHealth(), 36) : 20;
                double armor = switch (r.nextInt(3)) {
                    case 0 -> TargetView.NO_ARMOR;
                    case 1 -> near(s.facePlaceDurability(), 100);
                    default -> quarters(100);
                };
                targets.add(new TargetView(name, squared, health, armor, r.nextDouble() < 0.05,
                    r.nextDouble() < 0.95, r.nextDouble() < 0.05));
            }
            return targets;
        }

        private Map<String, Double> damages() {
            Map<String, Double> damage = new LinkedHashMap<>();
            for (String name : PLAYERS) {
                if (r.nextDouble() < 0.1) continue;
                damage.put(name, switch (r.nextInt(4)) {
                    case 0 -> near(s.minDamage(), 12);
                    case 1 -> oneOf(0, 0.25, 1.25, 1.5, 1.75);
                    default -> quarters(14);
                });
            }
            return damage;
        }

        private double selfDamage() {
            return r.nextBoolean() ? near(s.maxDamage(), 12) : quarters(8);
        }

        /** The budget's exact self damage: Meteor's, or up to 2 above it. Only the budget reads it. */
        private double budgetSelfDamage(double self) {
            return r.nextBoolean() ? self : self + quarters(2);
        }

        private CrystalSeen crystal(int id, long pos) {
            double self = selfDamage();
            return new CrystalSeen(id, pos, damages(), self, budgetSelfDamage(self), quarters(14), r.nextDouble() < 0.85);
        }

        private List<Candidate> candidates() {
            List<Candidate> spots = new ArrayList<>();
            List<Integer> ids = new ArrayList<>(world.keySet());
            for (long pos = 1; pos <= SPOTS; pos++) {
                if (r.nextDouble() < 0.4) continue;
                Set<Integer> inBox = new HashSet<>();
                for (CrystalSeen c : world.values()) {
                    if (c.pos() == pos) inBox.add(c.id());
                }
                if (!ids.isEmpty() && r.nextDouble() < 0.05) inBox.add(ids.get(r.nextInt(ids.size())));
                double self = selfDamage();
                spots.add(new Candidate(pos, damages(), self, budgetSelfDamage(self), r.nextDouble() < 0.9, inBox,
                    r.nextDouble() < 0.1));
            }
            // BlockIterator's order is not the spots' order.
            java.util.Collections.shuffle(spots, r);
            return spots;
        }

        // Settings: every one the brain reads, at random, with the budget off.

        private CrystalSettings settings() {
            return CrystalSettings.builder()
                .targetRange(oneOf(0, 3, 5, 10, 16, quarters(16)))
                .minDamage(oneOf(0, 1, 1.5, 4, 6, 8, quarters(12)))
                .maxDamage(oneOf(0, 4, 6, 8, 36, quarters(36)))
                .antiSuicide(r.nextDouble() < 0.8)
                .rotate(r.nextDouble() < 0.6)
                .autoSwitch(r.nextBoolean() ? AutoSwitch.NORMAL : AutoSwitch.NONE)
                .noGapSwitch(r.nextDouble() < 0.8)
                .noBowSwitch(r.nextDouble() < 0.8)
                .antiWeakness(r.nextDouble() < 0.8)
                .place(r.nextDouble() < 0.9)
                .facePlace(r.nextDouble() < 0.8)
                .facePlaceHealth(oneOf(1, 8, 12, 1 + quarters(35)))
                .facePlaceDurability(oneOf(1, 2, 50, 100, 1 + quarters(99)))
                .breakCrystals(r.nextDouble() < 0.9)
                .breakAttempts(r.nextInt(6))
                .attackFrequency(r.nextBoolean() ? 1 + r.nextInt(30) : (int) oneOf(1, 2, 3, 25))
                .fastBreak(r.nextDouble() < 0.8)
                .pauseOnUse(PauseMode.values()[r.nextInt(4)])
                .pauseOnMine(PauseMode.values()[r.nextInt(4)])
                .pauseOnLag(r.nextBoolean())
                .pauseHealth(oneOf(0, 5, 10, quarters(36)))
                .risk(RiskLevel.values()[r.nextInt(RiskLevel.values().length)])
                .selfBudget(false)
                .reserve(2 + quarters(18))
                .safeSelfDamage(quarters(2))
                .build();
        }

        /** The same settings with one of them changed, as a player does between two ticks. */
        private CrystalSettings changeOne(CrystalSettings from) {
            CrystalSettings fresh = settings();
            CrystalSettings.Builder b = from.toBuilder();
            switch (r.nextInt(22)) {
                case 0 -> b.targetRange(fresh.targetRange());
                case 1 -> b.minDamage(fresh.minDamage());
                case 2 -> b.maxDamage(fresh.maxDamage());
                case 3 -> b.antiSuicide(!from.antiSuicide());
                case 4 -> b.rotate(!from.rotate());
                case 5 -> b.autoSwitch(fresh.autoSwitch());
                case 6 -> b.noGapSwitch(!from.noGapSwitch());
                case 7 -> b.noBowSwitch(!from.noBowSwitch());
                case 8 -> b.antiWeakness(!from.antiWeakness());
                case 9 -> b.place(!from.place());
                case 10 -> b.facePlace(!from.facePlace());
                case 11 -> b.facePlaceHealth(fresh.facePlaceHealth());
                case 12 -> b.facePlaceDurability(fresh.facePlaceDurability());
                case 13 -> b.breakCrystals(!from.breakCrystals());
                case 14 -> b.breakAttempts(fresh.breakAttempts());
                case 15 -> b.attackFrequency(fresh.attackFrequency());
                case 16 -> b.fastBreak(!from.fastBreak());
                case 17 -> b.pauseOnUse(fresh.pauseOnUse());
                case 18 -> b.pauseOnMine(fresh.pauseOnMine());
                case 19 -> b.pauseOnLag(!from.pauseOnLag());
                case 20 -> b.risk(fresh.risk());
                default -> b.pauseHealth(fresh.pauseHealth());
            }
            return b.build();
        }
    }
}
