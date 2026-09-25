package com.xploits.pvp.recorder.core;

import com.xploits.pvp.core.CombatPosture;
import com.xploits.pvp.core.CombatState;
import com.xploits.pvp.recorder.core.CombatEvent.AttackerKind;
import com.xploits.pvp.recorder.core.FightRecord.DamageEvent;
import com.xploits.pvp.recorder.core.FightRecord.ModuleChange;
import com.xploits.pvp.recorder.core.FightRecord.Opponent;
import com.xploits.pvp.recorder.core.FightRecord.PhaseChange;
import com.xploits.pvp.recorder.core.FightRecord.ProfileChange;
import com.xploits.pvp.recorder.core.FightRecord.Sample;
import com.xploits.pvp.recorder.core.FightRecord.SelfTotals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Finished fights for the analysis and summary tests, built the way {@code FightBuilder} leaves them: one
 * sample per second, damage events in tick order with the killing blow last, opponents tallied from the
 * hits they dealt, totals that agree with the lists.
 *
 * <p>{@link #lost()} starts from a 20-second fight where no cause fires: one opponent, surround and crystal
 * aura on the whole time, in a hole at full health in every sample, five totems left with one in the offhand, and an unseen
 * killing blow from 4 health (so it adds nothing to any share). Each test adds the one thing it is about.
 * Damage ticks are counted back from the death tick, which falls inside the last second.
 */
final class Fights {
    static final String FOE = "Foo";
    static final String OTHER_FOE = "Bar";
    static final long STARTED_AT = 1_727_190_000_000L;
    static final long START_TICK = 1_000;

    private Fights() {
    }

    /** A lost fight with no cause in it yet. */
    static Builder lost() {
        return new Builder(FightOutcome.LOST);
    }

    /** The same fight with another outcome: no death, so no killing blow. */
    static Builder ending(FightOutcome outcome) {
        return new Builder(outcome);
    }

    /**
     * A crystal fight lost to {@link #FOE} after popping out of all eight totems, auto-pvp on the whole time,
     * like the one in the spec's JSON: crystals most of the damage, some of it unseen, outplaced, the hole and
     * surround left at 20 s, a piece of armor gone at 30 s, the last five seconds under the threat margin.
     * Its causes: OUT_OF_TOTEMS, CRYSTAL_OUTPACED, UNDEFENDED, ARMOR_BROKE.
     */
    static FightRecord crystalDeath() {
        Builder b = lost().seconds(43).totems(8, 0, false).crystals(20, 55)
            .modules("auto-totem", "crystal-aura", "surround")
            .moduleChange(20, "surround", false)
            .phase(0, CombatState.SURFACE, FOE)
            .samples(0, 43, row -> row.autoPvp = true)
            .samples(20, 43, row -> {
                row.inHole = false;
                row.health = 14;
            })
            .samples(30, 43, row -> row.armor = 3)
            .samples(38, 43, row -> row.health = 9.5);
        for (int i = 0; i < 7; i++) b.hit(DamageKind.CRYSTAL, FOE, 7.5);
        b.hit(DamageKind.MELEE, FOE, 6).unattributedHit(DamageKind.UNSEEN, 3.5);
        b.pop(300, DamageKind.CRYSTAL, FOE, 12).pop(200, DamageKind.CRYSTAL, FOE, 11).pop(100, DamageKind.UNSEEN, null, 10);
        return b.killedBy(DamageKind.CRYSTAL, FOE, 9.5).build();
    }

    /** One second of the fight, as a sample will carry it. Public fields: the tests set what they need. */
    static final class Row {
        double health = 20;
        double incoming;
        int totems;
        boolean offhandTotem = true;
        int crystals = 64;
        int obsidian = 64;
        int gapples = 32;
        int armor = 4;
        Double nearestHostile = 4.5;
        int hostilesNear = 1;
        boolean inHole = true;
        boolean gliding;
        boolean autoPvp;
        int placed = 1;
        int broken = 1;
        int spawnedNear = 1;
    }

    private record Hit(Integer ticksBeforeDeath, DamageKind kind, AttackerKind by, String attacker, double before,
                       double after, boolean lethal) {
    }

    private record Edit(int from, int to, Consumer<Row> edit) {
    }

    static final class Builder {
        private final FightOutcome outcome;
        private int seconds = 20;
        private int totemsStart = 8;
        private int totemsEnd = 5;
        private boolean offhandTotemEnd = true;
        private int pops;
        private int crystalsPlaced = 30;
        private int enemyCrystalsNear = 20;
        private int maxHostilesNear = 1;
        private List<String> modulesAtStart = List.of("auto-totem", "crystal-aura", "surround");
        private final List<String> opponents = new ArrayList<>(List.of(FOE));
        private final List<Hit> hits = new ArrayList<>();
        private final List<Hit> killingBlow = new ArrayList<>();
        private final List<ModuleChange> moduleChanges = new ArrayList<>();
        private final List<PhaseChange> phases = new ArrayList<>();
        private final List<Edit> edits = new ArrayList<>();
        private String profile;
        private final List<ProfileChange> profileChanges = new ArrayList<>();

        private Builder(FightOutcome outcome) {
            this.outcome = outcome;
        }

        Builder seconds(int value) {
            seconds = value;
            return this;
        }

        Builder totems(int start, int end, boolean offhandAtEnd) {
            totemsStart = start;
            totemsEnd = end;
            offhandTotemEnd = offhandAtEnd;
            return this;
        }

        /** {@code n} pops long before the death (ten seconds and more), crystals by {@link #FOE}. */
        Builder pops(int n) {
            for (int i = 0; i < n; i++) pop(200 + 20 * i, DamageKind.CRYSTAL, FOE, 10);
            return this;
        }

        /** A pop by a crystal of {@link #FOE}, this many ticks before the death tick. */
        Builder pop(int ticksBeforeDeath) {
            return pop(ticksBeforeDeath, DamageKind.CRYSTAL, FOE, 10);
        }

        /** A pop: a lethal hit from {@code before} this many ticks before the death tick; attacker null is nobody. */
        Builder pop(int ticksBeforeDeath, DamageKind kind, String attacker, double before) {
            pops++;
            hits.add(new Hit(ticksBeforeDeath, kind, attacker == null ? AttackerKind.NONE : AttackerKind.PLAYER, attacker,
                before, 0, true));
            return this;
        }

        /** A hit by a player that took {@code amount} (from 20 health, or from just above the amount if more). */
        Builder hit(DamageKind kind, String attacker, double amount) {
            return nonLethal(kind, AttackerKind.PLAYER, attacker, amount);
        }

        Builder hits(int n, DamageKind kind, String attacker, double amountEach) {
            for (int i = 0; i < n; i++) hit(kind, attacker, amountEach);
            return this;
        }

        /** A hit you dealt yourself (your own crystal). */
        Builder ownHit(DamageKind kind, double amount) {
            return nonLethal(kind, AttackerKind.SELF, null, amount);
        }

        /** A hit with no attacker: a fall, fire, an unseen hit. */
        Builder unattributedHit(DamageKind kind, double amount) {
            return nonLethal(kind, AttackerKind.NONE, null, amount);
        }

        /** Part of the killing blow on the death tick, by a player, from {@code before} health to 0. */
        Builder killedBy(DamageKind kind, String attacker, double before) {
            return killingHit(0, kind, AttackerKind.PLAYER, attacker, before);
        }

        /** Part of the killing blow on the death tick, dealt by yourself. */
        Builder killedByOwn(DamageKind kind, double before) {
            return killingHit(0, kind, AttackerKind.SELF, null, before);
        }

        /** Part of the killing blow on the death tick, with no attacker. */
        Builder killedUnattributed(DamageKind kind, double before) {
            return killingHit(0, kind, AttackerKind.NONE, null, before);
        }

        /** A lethal hit counted with the death, landing this many ticks before the death tick. */
        Builder killingHit(int ticksBeforeDeath, DamageKind kind, AttackerKind by, String attacker, double before) {
            killingBlow.add(new Hit(ticksBeforeDeath, kind, by, attacker, before, 0, true));
            return this;
        }

        /** Someone who fought you without hitting you (you hit them, or auto-pvp targeted them). */
        Builder opponent(String name) {
            if (!opponents.contains(name)) opponents.add(name);
            return this;
        }

        Builder hostilesNear(int max) {
            maxHostilesNear = max;
            return this;
        }

        Builder crystals(int placed, int enemyNear) {
            crystalsPlaced = placed;
            enemyCrystalsNear = enemyNear;
            return this;
        }

        Builder modules(String... atStart) {
            modulesAtStart = List.of(atStart);
            return this;
        }

        Builder moduleChange(int second, String module, boolean on) {
            moduleChanges.add(new ModuleChange(second, module, on));
            return this;
        }

        Builder phase(int second, CombatState state, String target) {
            phases.add(new PhaseChange(second, state, CombatPosture.CALM, target));
            return this;
        }

        /** The profile active when the fight opened; null (the default) is the pre-profiles shape. */
        Builder profile(String name) {
            profile = name;
            return this;
        }

        Builder profileChange(int second, String name) {
            profileChanges.add(new ProfileChange(second, name));
            return this;
        }

        /** Changes the samples of seconds {@code from} (included) to {@code to} (excluded). */
        Builder samples(int from, int to, Consumer<Row> edit) {
            edits.add(new Edit(from, to, edit));
            return this;
        }

        Builder lastSample(Consumer<Row> edit) {
            return samples(Integer.MAX_VALUE, Integer.MAX_VALUE, edit);
        }

        FightRecord build() {
            long deathTick = START_TICK + 20L * (seconds - 1) + 15;

            List<Hit> all = new ArrayList<>(hits);
            if (outcome == FightOutcome.LOST) {
                if (killingBlow.isEmpty()) all.add(new Hit(0, DamageKind.UNSEEN, AttackerKind.NONE, null, 4, 0, true));
                all.addAll(killingBlow);
            }
            List<DamageEvent> damage = new ArrayList<>();
            int unplaced = 0;
            for (Hit h : all) {
                int back = h.ticksBeforeDeath() != null ? h.ticksBeforeDeath() : 40 + 10 * unplaced++;
                damage.add(new DamageEvent(deathTick - back, h.kind(), h.by(), h.attacker(), h.before(), h.after(), h.lethal()));
            }
            damage.sort((a, b) -> Long.compare(a.tick(), b.tick()));

            double taken = 0;
            Map<String, double[]> tally = new LinkedHashMap<>();
            for (String name : opponents) tally.put(name, new double[2]);
            for (DamageEvent d : damage) {
                double amount = d.before() - d.after();
                taken += amount;
                if (d.by() == AttackerKind.PLAYER) {
                    double[] t = tally.computeIfAbsent(d.attacker(), n -> new double[2]);
                    t[0]++;
                    t[1] += amount;
                }
            }
            List<Opponent> opponentList = new ArrayList<>();
            for (Map.Entry<String, double[]> e : tally.entrySet()) {
                opponentList.add(new Opponent(e.getKey(), 0, false, (int) e.getValue()[0], e.getValue()[1], 4));
            }

            List<Sample> samples = new ArrayList<>();
            for (int second = 0; second < seconds; second++) {
                Row row = new Row();
                row.totems = totemsEnd;
                for (Edit e : edits) {
                    boolean last = e.from() == Integer.MAX_VALUE && second == seconds - 1;
                    if (last || (second >= e.from() && second < e.to())) e.edit().accept(row);
                }
                samples.add(new Sample(second, row.health, row.incoming, row.totems, row.offhandTotem, row.crystals,
                    row.obsidian, row.gapples, row.armor, row.nearestHostile, row.hostilesNear, row.inHole, row.gliding,
                    row.autoPvp, row.placed, row.broken, row.spawnedNear));
            }
            int autoPvpSeconds = (int) samples.stream().filter(Sample::autoPvp).count();

            SelfTotals self = new SelfTotals(pops, totemsStart, totemsEnd, offhandTotemEnd, taken, crystalsPlaced,
                Math.max(0, crystalsPlaced - 2), 0, enemyCrystalsNear);
            return new FightRecord(FightRecord.SCHEMA, "0.5.0", STARTED_AT, STARTED_AT + seconds * 1000L, seconds, outcome,
                false, FightMode.of(autoPvpSeconds, seconds), autoPvpSeconds, opponentList, maxHostilesNear, self, damage, 0,
                samples, modulesAtStart, moduleChanges, phases, profile, profileChanges);
        }

        private Builder nonLethal(DamageKind kind, AttackerKind by, String attacker, double amount) {
            double before = Math.max(20, amount + 1);
            hits.add(new Hit(null, kind, by, attacker, before, before - amount, false));
            return this;
        }
    }
}
