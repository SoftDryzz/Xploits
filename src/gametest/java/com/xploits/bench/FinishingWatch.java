package com.xploits.bench;

import com.xploits.bench.core.FinishingTracker;
import com.xploits.bench.core.OwnHits;
import com.xploits.pvp.crystal.CrystalAuraPlusPlus;
import com.xploits.pvp.crystal.core.FinishKind;
import com.xploits.pvp.recorder.core.FightRecord;
import com.xploits.pvp.recorder.FightRecorder;
import com.xploits.pvp.recorder.core.FightRecord.DamageEvent;
import com.xploits.pvp.recorder.core.HitSource;
import meteordevelopment.meteorclient.systems.modules.Modules;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Task B0b: what a run watches every tick to tell crystal-aura++'s finishing blows apart from its ordinary hits
 * ({@link FinishingTracker}, the pure part): the finishing crystals the module has marked
 * ({@link CrystalAuraPlusPlus#finishingCrystalIds}) and which are still in the world, our health and the totems
 * we carry, whether the target holds a totem, the recorder's own clock (the one its damage events carry), and,
 * log only, on how many pre-ticks the module trusted a target. With Meteor's aura (or anything else under test)
 * it only counts our totems. Both the fight runs ({@link FightMeasureRun}) and the older measures
 * ({@link MeasureRun}) use it, so their finishing-blow metrics mean the same.
 */
final class FinishingWatch {
    private static Field recorderTickField;

    private final Bench bench;
    private final boolean plusPlus;
    private final FinishingTracker tracker = new FinishingTracker();
    private int totems = -1;
    private int trustedTicks;
    private int firstTrustedTick = -1;
    /** The recorder's claimed count when this run's first tick was observed (the recorder may have been active longer). */
    private long claimedBase = -1;
    /** Task C2 (I2): every crystal of ours seen so far (sticky: the brain forgets a crystal soon after it is gone). */
    private final Set<Integer> ownIds = new HashSet<>();
    /** The ones among {@link #ownIds} that we attacked ourselves. */
    private final Set<Integer> ownAttacked = new HashSet<>();
    /** The ones among {@link #ownIds} seen gone from the world without an attack of ours (log only). */
    private final Set<Integer> ownGoneUnattacked = new HashSet<>();

    FinishingWatch(Bench bench, boolean plusPlus) {
        this.bench = bench;
        this.plusPlus = plusPlus;
    }

    private record Read(Map<Integer, FinishKind> marked, Set<Integer> present, Set<Integer> attacked, int totems, int trusted, long recorderTick,
                        Map<Integer, Boolean> own, Set<Integer> ownPresent) {
    }

    private int lastTrusted;

    /** One tick's read, after the tick (crystal-aura++ only; anything else just counts our totems). */
    void observe(double health) {
        if (claimedBase < 0) claimedBase = bench.fromClient(client -> Modules.get().get(FightRecorder.class).claimedCrystalCount());
        Read read = bench.fromClient(client -> {
            Map<Integer, FinishKind> marked = Map.of();
            Set<Integer> attacked = Set.of();
            Set<Integer> present = new HashSet<>();
            int trusted = 0;
            Map<Integer, Boolean> own = Map.of();
            Set<Integer> ownPresent = new HashSet<>();
            if (plusPlus) {
                CrystalAuraPlusPlus module = Modules.get().get(CrystalAuraPlusPlus.class);
                marked = module.finishingCrystalKinds();
                attacked = module.finishingCrystalsAttacked();
                for (Integer id : marked.keySet()) {
                    if (client.world != null && client.world.getEntityById(id) != null) present.add(id);
                }
                trusted = module.trustedTargets();
                own = module.ownCrystals();
                for (Integer id : own.keySet()) {
                    if (client.world != null && client.world.getEntityById(id) != null) ownPresent.add(id);
                }
            }
            return new Read(marked, present, attacked, client.player == null ? 0 : Arena.totemsCarried(client.player), trusted,
                plusPlus ? recorderTick(Modules.get().get(FightRecorder.class)) : 0, own, ownPresent);
        });
        if (plusPlus) {
            tracker.observe(read.marked(), read.present(), read.attacked(), health, read.totems(), read.recorderTick());
            lastTrusted = read.trusted();
            for (Map.Entry<Integer, Boolean> e : read.own().entrySet()) {
                ownIds.add(e.getKey());
                if (e.getValue()) ownAttacked.add(e.getKey());
            }
            for (Integer id : ownIds) {
                if (!read.ownPresent().contains(id) && !ownAttacked.contains(id) && !read.own().getOrDefault(id, false)) {
                    ownGoneUnattacked.add(id);
                }
            }
            if (read.trusted() > 0) {
                trustedTicks++;
                if (firstTrustedTick < 0) firstTrustedTick = bench.sinceT0();
            }
        }
        totems = read.totems();
    }

    /** Forgets the blows so far (the near-death moment: warm-up blows do not count). */
    void reset() {
        tracker.reset();
    }

    /** How many targets crystal-aura++ trusted on the last tick observed. */
    int lastTrusted() {
        return lastTrusted;
    }

    /** The recorder's own tick count, the clock its damage events carry (a private counter read in place). */
    private static long recorderTick(FightRecorder recorder) {
        try {
            if (recorderTickField == null) {
                recorderTickField = FightRecorder.class.getDeclaredField("tick");
                recorderTickField.setAccessible(true);
            }
            return recorderTickField.getLong(recorder);
        } catch (ReflectiveOperationException e) {
            throw new BenchException("cannot read the recorder tick: " + e);
        }
    }

    /** Whether the module under test is crystal-aura++, the only one with finishing blows to watch. */
    boolean active() {
        return plusPlus;
    }

    /** The totems we carried on the last tick observed; -1 before the first. */
    int totems() {
        return totems;
    }

    int trustedTicks() {
        return trustedTicks;
    }

    int firstTrustedTick() {
        return firstTrustedTick;
    }

    /** The recorder's damage events of {@code records}, in order: the list the indexes of a resolution refer to. */
    static List<DamageEvent> events(List<FightRecord> records) {
        return records.stream().flatMap(r -> r.damage().stream()).toList();
    }

    /**
     * Task C2 (I2): the events among {@code damage} that are ours for the reserve rules: the recorder's own
     * attribution plus every hit whose direct source was a crystal we placed, even one the opponent's autobreak
     * set off ({@link OwnHits}). Without crystal-aura++ under test only the recorder's attribution is known.
     */
    Set<Integer> ownIndexes(List<DamageEvent> damage) {
        // Meteor's runs too: the recorder claims crystals from the placement packets, whichever module sent them.
        List<HitSource> sources = bench.fromClient(client -> Modules.get().get(FightRecorder.class).hitSources());
        Set<Integer> ids = new HashSet<>(ownIds);
        ids.addAll(bench.fromClient(client -> Modules.get().get(FightRecorder.class).claimedCrystalIds()));
        return OwnHits.indexes(damage, sources, ids);
    }

    /** Health lost to hits that are ours ({@link #ownIndexes}), finishing hits included. */
    double ownDamage(List<FightRecord> records) {
        List<DamageEvent> damage = events(records);
        double sum = 0;
        for (int i : ownIndexes(damage)) sum += damage.get(i).before() - damage.get(i).after();
        return sum;
    }

    /** How many crystals the recorder claimed as ours during this run (a count only, log only). */
    long claimedCrystals() {
        return bench.fromClient(client -> Modules.get().get(FightRecorder.class).claimedCrystalCount()) - Math.max(0, claimedBase);
    }

    /** How many hits on us came from a crystal of ours that the recorder blamed on someone else (log only). */
    int ownHitsBlamedElsewhere(List<FightRecord> records) {
        List<DamageEvent> damage = events(records);
        Set<Integer> self = OwnHits.selfIndexes(damage);
        return (int) ownIndexes(damage).stream().filter(i -> !self.contains(i)).count();
    }

    /**
     * How many crystals of ours went gone without an attack of ours (log only). An upper bound of what the
     * opponent broke, and blind to a crystal broken in the tick it appeared (never sampled).
     */
    int ownCrystalsGoneUnattacked() {
        return (int) ownGoneUnattacked.stream().filter(id -> !ownAttacked.contains(id)).count();
    }

    /** The blows among what was watched, given the closed records' events ({@link FinishingTracker#resolve}). */
    FinishingTracker.Resolution resolve(List<FightRecord> records) {
        List<DamageEvent> damage = events(records);
        return tracker.resolve(damage, ownIndexes(damage));
    }

    /**
     * The lowest health for the reserve rules, from ordinary hits only (S1-S3): the samples outside the
     * confirmed blows' windows, and the health after every event that is not a finishing hit, so an ordinary
     * breach inside a blow's window still shows. {@code raw} when this is not crystal-aura++.
     */
    double ordinaryMinHealth(List<FightRecord> records, double raw) {
        if (!plusPlus) return raw;
        List<DamageEvent> damage = events(records);
        FinishingTracker.Resolution r = tracker.resolve(damage, ownIndexes(damage));
        double min = tracker.minHealthOutside(r.blows()).orElse(Double.POSITIVE_INFINITY);
        min = Math.min(min, FinishingTracker.ordinaryEventMin(damage, r).orElse(Double.POSITIVE_INFINITY));
        return Double.isFinite(min) ? min : raw;
    }

    /**
     * The finishing-blow metrics, only when a blow happened (a crystal the module marked that exploded and
     * dealt us damage): how many, the lowest health after one, the fewest totems carried at one, how many the
     * module marked kill-grade and pop-grade, how many cost us a totem, and how many broke the pop-grade rule.
     */
    void put(Metrics metrics, FinishingTracker.Resolution r) {
        if (r.offenseCount() == 0) return;
        // Offense (task T2): our marked crystals we attacked that went off, hurt us or not.
        metrics.put(Metrics.FINISHING_BLOWS, r.offenseCount());
        metrics.put(Metrics.FINISHING_KILL_BLOWS, r.offenseKills());
        metrics.put(Metrics.FINISHING_POP_BLOWS, r.offenseCount() - r.offenseKills());
        // Safety: the ones that hurt us; everything the safety rules read is over those alone.
        metrics.put(Metrics.FINISHING_HITS, r.count());
        metrics.put(Metrics.FINISHING_KILL_HITS, r.kills());
        metrics.put(Metrics.FINISHING_POP_HITS, r.popBlows());
        if (r.count() == 0) return;
        metrics.put(Metrics.MIN_HEALTH_AFTER_FINISHING_HIT, r.minHealthAfter().orElseThrow());
        metrics.put(Metrics.TOTEMS_AT_FINISHING_HIT_MIN, r.minTotems().orElseThrow());
        metrics.put(Metrics.FINISHING_POPS, r.pops());
        metrics.put(Metrics.FINISHING_POP_GRADE_VIOLATIONS, r.popGradeViolations());
    }
}
