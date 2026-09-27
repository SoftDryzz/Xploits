package com.xploits.pvp.crystal.core;

import com.xploits.pvp.crystal.core.CrystalSettings.AutoSwitch;
import com.xploits.pvp.crystal.core.CrystalSettings.PauseMode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Meteor's CrystalAura decision code, transcribed method by method from {@code CrystalAura.java} (1.21.11
 * sources; line numbers in each method) onto the core's facts, keeping Meteor's own state the way Meteor keeps
 * it: the {@code removed} set, the {@code waitingToExplode} counters, the {@code attemptedBreaks} map, the
 * attack window and the rotation flags. It shares no code with {@link CrystalBrain}, so the property test that
 * drives both with the same events compares two independent readings of Meteor.
 *
 * <p>The settings ++ fixes are at Meteor's defaults, so their branches are left out: break, place and switch
 * delays 0 (the break and place timers never run), only-own off, ticks-existed 0, support Disabled, yaw-steps
 * 180 ({@code doYawSteps} is always true), smart-delay and predict-movement off (the adapter measures), no
 * force-face-place key, face-place-missing-armor off. Measuring (ranges, damage, the raycast) is the adapter's,
 * so the reference reads the same measured facts the brain reads.
 */
final class MeteorReference {
    /** What Meteor does: the action, the hand it swings or places with, and whether it swaps to the crystals. */
    record Act(Decision.Kind kind, long ref, Action.Hand hand, boolean swap) {
        static Act of(Action a) {
            return new Act(a.decision().kind(), a.decision().ref(), a.hand(), a.switchToCrystals());
        }
    }

    /** {@code getLastRotationStopDelay()} with both delays 0 (lines 656-658). */
    private static final int LAST_ROTATION_STOP_DELAY = 10;

    private CrystalSettings s = CrystalSettings.defaults();

    // Meteor's fields, as onActivate leaves them (lines 615-640)
    private int ticksPassed;
    private int attacks;
    private int switchTimer;
    private boolean didRotateThisTick;
    private int lastRotationTimer = LAST_ROTATION_STOP_DELAY;
    private final Set<Integer> removed = new HashSet<>();
    private final Map<Integer, Integer> waitingToExplode = new LinkedHashMap<>();
    private final Map<Integer, Integer> attemptedBreaks = new HashMap<>();
    private List<TargetView> targets = List.of();
    /** {@code mainItem} and {@code offItem}, read at the start of the pre-tick (lines 693-694). */
    private CrystalTick.Hands handsAtHigh;
    /** The scan was registered this pre-tick (line 932). */
    private boolean scanRegistered;

    /** {@code onPreTick} at HIGH (lines 660-720), up to registering the scan. */
    Optional<Act> preTick(CrystalSettings settings, CrystalTick tick) {
        s = settings;
        didRotateThisTick = false;
        lastRotationTimer++;
        if (ticksPassed < 20) ticksPassed++;
        else {
            ticksPassed = 0;
            attacks = 0;
        }
        if (switchTimer > 0) switchTimer--;
        handsAtHigh = tick.hands();
        for (Iterator<Map.Entry<Integer, Integer>> it = waitingToExplode.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Integer> e = it.next();
            if (e.getValue() > 3) {
                it.remove();
                removed.remove(e.getKey());
            } else {
                e.setValue(e.getValue() + 1);
            }
        }
        findTargets(tick.targets());

        scanRegistered = false;
        Optional<Act> out = Optional.empty();
        if (!targets.isEmpty()) {
            if (!didRotateThisTick) out = doBreak(tick);
            if (!didRotateThisTick) scanRegistered = doPlace(tick);
        }
        return out;
    }

    boolean scanRegistered() {
        return scanRegistered;
    }

    /** The scan and its {@code after} (lines 926-1008), with the health read during the scan (line 953). */
    Optional<Act> scanAfter(double health, List<Candidate> candidates) {
        if (!scanRegistered) throw new IllegalStateException("no scan registered");
        scanRegistered = false;
        double bestDamage = 0;
        Candidate best = null;
        for (Candidate c : candidates) {
            if (!c.inRange()) continue;
            double selfDamage = c.selfDamage();
            if (selfDamage > s.maxDamage() || (s.antiSuicide() && selfDamage >= health)) continue;
            float damage = damageToTargets(c.targetDamage());
            double minimumDamage = Math.min(s.minDamage(), shouldFacePlace() ? 1.5 : s.minDamage());
            if (damage < minimumDamage) continue;
            if (intersectsWithEntities(c)) continue;
            if (damage > bestDamage) {
                bestDamage = damage;
                best = c;
            }
        }
        if (bestDamage == 0) return Optional.empty();
        if (s.rotate()) setRotation();
        // placeCrystal (lines 1036-1043): findInHotbar looks at the offhand first; otherwise it swaps (auto-switch
        // not None) and places with the main hand. Swapping to the slot already held changes nothing, so a swap
        // is only reported when the crystals are in neither hand.
        boolean off = handsAtHigh.offhandCrystals();
        boolean swap = s.autoSwitch() == AutoSwitch.NORMAL && !off && !handsAtHigh.mainHandCrystals();
        return Optional.of(new Act(Decision.Kind.PLACE, best.pos(), off ? Action.Hand.OFF : Action.Hand.MAIN, swap));
    }

    /** {@code onEntityAdded} (lines 731-744), with the settings read now. */
    Optional<Act> entityAdded(CrystalSettings settings, CrystalSeen crystal, double health, CrystalTick.Hands hands) {
        s = settings;
        if (s.fastBreak() && !didRotateThisTick && attacks < s.attackFrequency()) {
            float damage = getBreakDamage(crystal, health);
            if (damage > s.minDamage()) return doBreak(crystal, hands);
        }
        return Optional.empty();
    }

    /** {@code onEntityRemoved} (lines 747-752). */
    void entityRemoved(int id) {
        removed.remove(id);
        waitingToExplode.remove(id);
    }

    /** The end of {@code attackCrystal} (line 891), where the attack packet goes out. */
    void attackSent() {
        attacks++;
    }

    /** {@code onPreTickLast} (lines 722-727). */
    boolean rotatesToLastRotation() {
        return s.rotate() && lastRotationTimer < LAST_ROTATION_STOP_DELAY && !didRotateThisTick;
    }

    // Break

    /** Lines 770-789. */
    private Optional<Act> doBreak(CrystalTick tick) {
        if (!s.breakCrystals() || switchTimer > 0 || attacks >= s.attackFrequency()) return Optional.empty();
        if (shouldPause(tick, PauseMode.BREAK)) return Optional.empty();
        float bestDamage = 0;
        CrystalSeen crystal = null;
        for (CrystalSeen c : tick.crystals()) {
            float damage = getBreakDamage(c, tick.health());
            if (damage > bestDamage) {
                bestDamage = damage;
                crystal = c;
            }
        }
        return crystal == null ? Optional.empty() : doBreak(crystal, tick.hands());
    }

    /** Lines 791-822 (the age check never holds with ticks-existed 0). */
    private float getBreakDamage(CrystalSeen crystal, double health) {
        if (removed.contains(crystal.id())) return 0;
        if (attemptedBreaks.getOrDefault(crystal.id(), 0) > s.breakAttempts()) return 0;
        if (!crystal.inBreakRange()) return 0;
        double selfDamage = crystal.selfDamage();
        if (selfDamage > s.maxDamage() || (s.antiSuicide() && selfDamage >= health)) return 0;
        float damage = damageToTargets(crystal.targetDamage());
        double minimumDamage = shouldFacePlace() ? Math.min(s.minDamage(), 1.5d) : s.minDamage();
        if (damage < minimumDamage) return 0f;
        return damage;
    }

    /** Lines 824-875 and the hand of {@code attackCrystal} (lines 885-886). */
    private Optional<Act> doBreak(CrystalSeen crystal, CrystalTick.Hands hands) {
        if (s.antiWeakness()) {
            boolean weakness = hands.weaknessAmplifier() != CrystalTick.Hands.NO_EFFECT;
            boolean strength = hands.strengthAmplifier() != CrystalTick.Hands.NO_EFFECT;
            if (weakness && (!strength || hands.strengthAmplifier() <= hands.weaknessAmplifier())) {
                if (!hands.mainHandBreaksWeakened()) {
                    // InvUtils.swap(-1) is false when nothing in the hotbar hurts the crystal (line 835).
                    if (!hands.hotbarBreaksWeakened()) return Optional.empty();
                    switchTimer = 1;
                    return Optional.of(new Act(Decision.Kind.SWAP_WEAPON, crystal.id(), Action.Hand.MAIN, false));
                }
            }
        }
        if (s.rotate()) setRotation();
        removed.add(crystal.id());
        attemptedBreaks.merge(crystal.id(), 1, Integer::sum);
        waitingToExplode.put(crystal.id(), 0);
        return Optional.of(new Act(Decision.Kind.BREAK, crystal.id(),
            hands.offhandCrystals() ? Action.Hand.OFF : Action.Hand.MAIN, false));
    }

    // Place

    /** Lines 903-924: whether the scan is registered. */
    private boolean doPlace(CrystalTick tick) {
        if (!s.place()) return false;
        if (shouldPause(tick, PauseMode.PLACE)) return false;
        CrystalTick.Hands h = handsAtHigh;
        if (!h.crystalsInHotbar()) return false;
        if (s.autoSwitch() != AutoSwitch.NONE) {
            if (s.noGapSwitch() && s.autoSwitch() == AutoSwitch.NORMAL && !h.offhandCrystals()) {
                if (h.gappleInHand()) return false;
            }
            if (s.noBowSwitch() && h.bowInHand()) return false;
        } else if (!h.mainHandCrystals() && !h.offhandCrystals()) return false;
        for (CrystalSeen c : tick.crystals()) {
            if (getBreakDamage(c, tick.health()) > 0) return false;
        }
        return true;
    }

    /** Line 1257: any entity in the box but spectators (the adapter leaves them out) and the crystals in removed. */
    private boolean intersectsWithEntities(Candidate c) {
        if (c.otherEntityInBox()) return true;
        for (int id : c.crystalsInBox()) {
            if (!removed.contains(id)) return true;
        }
        return false;
    }

    // Others

    /** Lines 755-766. */
    private void setRotation() {
        didRotateThisTick = true;
        lastRotationTimer = 0;
    }

    /** Lines 1127-1149. */
    private boolean shouldFacePlace() {
        if (!s.facePlace()) return false;
        for (TargetView target : targets) {
            if (target.totalHealth() <= s.facePlaceHealth()) return true;
            if (target.lowestArmorPercent() <= s.facePlaceDurability()) return true;
        }
        return false;
    }

    /** Lines 1153-1162, with Meteor's {@code PauseMode.equals} (lines 1377-1379). */
    private boolean shouldPause(CrystalTick tick, PauseMode process) {
        if (tick.usingItem() && (s.pauseOnUse() == process || s.pauseOnUse() == PauseMode.BOTH)) return true;
        if (s.pauseOnLag() && tick.lagging()) return true;
        if (tick.pauseModuleActive()) return true;
        if ((s.pauseOnMine() == process || s.pauseOnMine() == PauseMode.BOTH) && tick.mining()) return true;
        return tick.health() <= s.pauseHealth();
    }

    /** Lines 1197-1211: summed in float over the targets, in their order. */
    private float damageToTargets(Map<String, Double> perTarget) {
        float damage = 0;
        for (TargetView target : targets) {
            Double dmg = perTarget.get(target.name());
            damage += dmg == null ? 0f : dmg.floatValue();
        }
        return damage;
    }

    /** Lines 1222-1253, players only. */
    private void findTargets(List<TargetView> seen) {
        List<TargetView> found = new java.util.ArrayList<>();
        for (TargetView t : seen) {
            if (t.creative()) continue;
            if (!t.alive() || t.friend()) continue;
            if (t.squaredDistance() > s.targetRange() * s.targetRange()) continue;
            found.add(t);
        }
        targets = List.copyOf(found);
    }
}
