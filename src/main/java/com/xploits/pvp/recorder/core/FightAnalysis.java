package com.xploits.pvp.recorder.core;

import com.xploits.pvp.core.CombatState;
import com.xploits.pvp.core.DefensivePolicy;
import com.xploits.pvp.core.ManagedModules;
import com.xploits.pvp.recorder.core.CombatEvent.AttackerKind;
import com.xploits.pvp.recorder.core.FightRecord.DamageEvent;
import com.xploits.pvp.recorder.core.FightRecord.ModuleChange;
import com.xploits.pvp.recorder.core.FightRecord.Opponent;
import com.xploits.pvp.recorder.core.FightRecord.PhaseChange;
import com.xploits.pvp.recorder.core.FightRecord.Sample;
import com.xploits.shared.core.i18n.Msg;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Why a lost fight was lost: the probable causes, strongest first. Each cause is a rule over the record
 * with a fixed weight; only a {@link FightOutcome#LOST} fight has causes.
 *
 * <p>The killing blow is the lethal hits within {@link DamageLedger#WINDOW_TICKS} of the last lethal tick: a
 * lost fight ends on your death, so nothing lethal comes after it, and the ledger can date the hits of one
 * blow a few ticks apart. Earlier lethal hits are pops (a pop is only believed when the fight counted one);
 * a pop inside that window is taken as part of the blow (documented bias). The record keeps every lethal
 * hit past {@link FightTracker#MAX_DAMAGE_EVENTS}, so a long fight keeps its killing blow and its pops.
 *
 * <p>Shares are of the damage whose source is known: {@link DamageKind#UNSEEN} never counts in the
 * denominator. The crystal causes about your crystal play blame only enemy crystals (any crystal not
 * yours); your own are {@link CauseKind#SELF_CRYSTAL}.
 */
public final class FightAnalysis {
    /** A pop this close before death, with totems still in the inventory: the totem did not get back in time. */
    public static final int DOUBLE_POP_TICKS = 20;
    /** Share of the damage from your own crystals that makes them a cause even if they did not kill you. */
    public static final double SELF_CRYSTAL_SHARE = 0.25;
    /** Share of the damage from falls that makes them a (weak) cause even if a fall did not kill you. */
    public static final double FALL_SHARE = 0.30;
    /** The weight of falls when they did not deal the killing blow but reached {@link #FALL_SHARE}. */
    public static final int FALL_SHARE_WEIGHT = 35;
    /** Health (with absorption) you had right before the killing blow for it to count as a burst. */
    public static final double BURST_HEALTH = 12.0;
    /** Players that hit you, or were close at once, for you to be outnumbered. */
    public static final int OUTNUMBERED_PLAYERS = 2;
    /** Share of the damage from enemy crystals above which your own crystal play is looked at. */
    public static final double CRYSTAL_SHARE = 0.60;
    /** Below this fraction of the fight with a crystal, anchor or bed aura on, it counts as off. */
    public static final double CRYSTAL_OFFENSE_SECONDS = 0.50;
    /** You were outpaced when you placed fewer than this many crystals per enemy crystal near you. */
    public static final double OUTPACED_RATIO = 0.5;
    /** Health minus the damage already aimed at you under which you needed a defense: auto-pvp's own margin. */
    public static final double UNDEFENDED_MARGIN = DefensivePolicy.THREAT_MARGIN;
    public static final int UNDEFENDED_SECONDS = 3;
    public static final int OUT_OF_CRYSTALS_SECONDS = 5;
    /** Obsidian under which surround cannot close: the minimum auto-pvp asks of it. */
    public static final int OBSIDIAN_MINIMUM = ManagedModules.SURROUND.minimum();
    public static final int OUT_OF_OBSIDIAN_SECONDS = 5;
    public static final double ANVIL_SHARE = 0.30;
    public static final double MELEE_SHARE = 0.50;
    public static final double LOW_HEALTH = 8.0;
    /** An enemy this close (the tracker's near range) while you stayed at low health. */
    public static final double LOW_HEALTH_RANGE = FightTracker.NEAR_RANGE;
    public static final int LOW_HEALTH_SECONDS = 5;

    private static final double TICKS_PER_SECOND = 20.0;

    /** Every cause the analysis knows, with its weight. Ties are broken by this order. */
    public enum CauseKind {
        NO_TOTEMS(100),
        DOUBLE_POP(97),
        TOTEMS_LEFT(95),
        OUT_OF_TOTEMS(90),
        SELF_CRYSTAL(85),
        /** 90 when a fall dealt the killing blow; {@link #FALL_SHARE_WEIGHT} when falls only reached their share. */
        FALL(90),
        BURST(80),
        OUTNUMBERED(70),
        CRYSTAL_AURA_OFF(65),
        CRYSTAL_OUTPACED(60),
        UNDEFENDED(55),
        OUT_OF_CRYSTALS(50),
        OUT_OF_OBSIDIAN(45),
        ANVIL(40),
        ARMOR_BROKE(40),
        MELEE(35),
        GLIDING(30),
        CHASING(30),
        LOW_HEALTH_STAYED(25);

        private final int weight;

        CauseKind(int weight) {
            this.weight = weight;
        }

        public int weight() {
            return weight;
        }

        /** The catalog key of this cause's evidence line. */
        public RecorderText key() {
            return RecorderText.valueOf("CAUSE_" + name());
        }
    }

    /** One probable cause and what in the record points to it. */
    public record Cause(CauseKind kind, int weight, Msg evidence) {
    }

    private FightAnalysis() {
    }

    /** The probable causes of a lost fight, weight first, then {@link CauseKind} order; none for any other outcome. */
    public static List<Cause> causes(FightRecord f) {
        if (f.outcome() != FightOutcome.LOST) return List.of();

        List<Cause> found = new ArrayList<>();
        Long deathTick = lastLethalTick(f);
        List<DamageEvent> blow = killingBlow(f, deathTick);
        Long lastPopGap = lastPopGap(f, deathTick);
        FightRecord.SelfTotals self = f.self();

        if (self.totemsStart() == 0 && self.pops() == 0) add(found, CauseKind.NO_TOTEMS);

        boolean poppedJustBefore = lastPopGap != null && lastPopGap <= DOUBLE_POP_TICKS;
        if (poppedJustBefore && self.totemsEnd() > 0) {
            add(found, CauseKind.DOUBLE_POP, "seconds", lastPopGap / TICKS_PER_SECOND, "totems", self.totemsEnd());
        }
        if (self.totemsEnd() > 0 && !poppedJustBefore && !self.offhandTotemEnd()) {
            add(found, CauseKind.TOTEMS_LEFT, "totems", self.totemsEnd());
        }
        if (self.totemsEnd() == 0 && self.pops() > 0) add(found, CauseKind.OUT_OF_TOTEMS, "pops", self.pops());

        boolean ownBlow = any(blow, d -> d.kind() == DamageKind.CRYSTAL && d.by() == AttackerKind.SELF);
        double ownShare = seenShare(f, d -> d.kind() == DamageKind.CRYSTAL && d.by() == AttackerKind.SELF);
        if (ownBlow || ownShare >= SELF_CRYSTAL_SHARE) {
            add(found, CauseKind.SELF_CRYSTAL, "percent", percent(ownShare), "blow", blowTail(ownBlow));
        }

        boolean fallBlow = any(blow, d -> d.kind() == DamageKind.FALL);
        double fallShare = share(f, DamageKind.FALL);
        if (fallBlow || fallShare >= FALL_SHARE) {
            int weight = fallBlow ? CauseKind.FALL.weight() : FALL_SHARE_WEIGHT;
            found.add(new Cause(CauseKind.FALL, weight,
                Msg.of(CauseKind.FALL.key(), "percent", percent(fallShare), "blow", blowTail(fallBlow))));
        }

        double healthBeforeBlow = 0;
        for (DamageEvent d : blow) healthBeforeBlow += d.before();
        if (!blow.isEmpty() && healthBeforeBlow >= BURST_HEALTH) add(found, CauseKind.BURST, "health", healthBeforeBlow);

        int attackers = 0;
        for (Opponent o : f.opponents()) {
            if (o.hitsOnYou() > 0) attackers++;
        }
        if (attackers >= OUTNUMBERED_PLAYERS || f.maxHostilesNear() >= OUTNUMBERED_PLAYERS) {
            Msg detail = attackers >= OUTNUMBERED_PLAYERS
                ? Msg.of(RecorderText.CAUSE_OUTNUMBERED_HIT, "attackers", attackers)
                : Msg.of(RecorderText.CAUSE_OUTNUMBERED_CLOSE, "near", f.maxHostilesNear());
            add(found, CauseKind.OUTNUMBERED, "detail", detail);
        }

        // Enemy crystals: any crystal that was not yours (no attacker counts as the enemy's).
        double crystalShare = seenShare(f, d -> d.kind() == DamageKind.CRYSTAL && d.by() != AttackerKind.SELF);
        if (crystalShare >= CRYSTAL_SHARE) {
            int active = secondsActive(f, ModuleRole.CRYSTAL_OFFENSE);
            if (active < CRYSTAL_OFFENSE_SECONDS * f.durationSeconds()) {
                add(found, CauseKind.CRYSTAL_AURA_OFF, "percent", percent(crystalShare), "active", active,
                    "seconds", f.durationSeconds());
            }
            if (self.crystalsPlaced() < OUTPACED_RATIO * self.enemyCrystalsNear()) {
                add(found, CauseKind.CRYSTAL_OUTPACED, "percent", percent(crystalShare), "placed", self.crystalsPlaced(),
                    "enemy", self.enemyCrystalsNear());
            }
        }

        int undefended = 0;
        for (Sample s : f.samples()) {
            if (s.health() - s.incoming() < UNDEFENDED_MARGIN && !s.inHole() && !roleActiveAt(f, ModuleRole.DEFENSE, s.second())) {
                undefended++;
            }
        }
        if (undefended >= UNDEFENDED_SECONDS) {
            add(found, CauseKind.UNDEFENDED, "seconds", undefended, "margin", UNDEFENDED_MARGIN);
        }

        int noCrystals = longestRun(f.samples(), s -> s.crystals() <= 0 && s.nearestHostile() != null);
        if (noCrystals >= OUT_OF_CRYSTALS_SECONDS) add(found, CauseKind.OUT_OF_CRYSTALS, "seconds", noCrystals);

        int shortOfObsidian = 0;
        for (Sample s : f.samples()) {
            if (s.obsidian() < OBSIDIAN_MINIMUM) shortOfObsidian++;
        }
        if (shortOfObsidian >= OUT_OF_OBSIDIAN_SECONDS) {
            add(found, CauseKind.OUT_OF_OBSIDIAN, "seconds", shortOfObsidian, "minimum", OBSIDIAN_MINIMUM);
        }

        double anvilShare = share(f, DamageKind.ANVIL);
        if (anvilShare >= ANVIL_SHARE) add(found, CauseKind.ANVIL, "percent", percent(anvilShare));

        if (!f.samples().isEmpty()) {
            Sample first = f.samples().getFirst();
            Sample last = f.samples().getLast();
            if (last.armor() < first.armor()) add(found, CauseKind.ARMOR_BROKE, "start", first.armor(), "end", last.armor());
        }

        double meleeShare = share(f, DamageKind.MELEE);
        if (meleeShare >= MELEE_SHARE) {
            add(found, CauseKind.MELEE, "percent", percent(meleeShare), "attacker", topAttacker(f, DamageKind.MELEE));
        }

        if (!f.samples().isEmpty() && f.samples().getLast().gliding()) add(found, CauseKind.GLIDING);

        if (!f.phases().isEmpty()) {
            PhaseChange last = f.phases().getLast();
            if (last.state() == CombatState.CHASE) {
                add(found, CauseKind.CHASING, "target", last.target() == null ? RecorderText.NOBODY : last.target());
            }
        }

        int lowHealth = longestRun(f.samples(), s -> s.health() <= LOW_HEALTH && !s.inHole()
            && s.nearestHostile() != null && s.nearestHostile() <= LOW_HEALTH_RANGE);
        if (lowHealth >= LOW_HEALTH_SECONDS) {
            add(found, CauseKind.LOW_HEALTH_STAYED, "seconds", lowHealth, "health", LOW_HEALTH, "range", LOW_HEALTH_RANGE);
        }

        found.sort(Comparator.comparingInt(Cause::weight).reversed().thenComparing(Cause::kind));
        return List.copyOf(found);
    }

    /** The strongest cause, or "no clear cause" with the kind that dealt the most known damage. */
    public static Msg mainCause(FightRecord f) {
        List<Cause> causes = causes(f);
        if (!causes.isEmpty()) return causes.getFirst().evidence();

        DamageKind top = null;
        double most = 0;
        for (Map.Entry<DamageKind, Double> e : damageByKind(f).entrySet()) {
            if (e.getKey() != DamageKind.UNSEEN && e.getValue() > most) {
                top = e.getKey();
                most = e.getValue();
            }
        }
        Object source = top == null
            ? RecorderText.NOTHING
            : Msg.of(RecorderText.CAUSE_TOP_SOURCE, "kind", top.label(), "percent", percent(share(f, top)));
        return Msg.of(RecorderText.CAUSE_UNCLEAR, "source", source);
    }

    /** The damage you took per kind, {@link DamageKind#UNSEEN} included; kinds that dealt none are left out. */
    public static Map<DamageKind, Double> damageByKind(FightRecord f) {
        Map<DamageKind, Double> byKind = new EnumMap<>(DamageKind.class);
        for (DamageEvent d : f.damage()) byKind.merge(d.kind(), amount(d), Double::sum);
        return byKind;
    }

    /**
     * The fraction (0 to 1) of the damage with a known source that {@code kind} dealt. The denominator leaves
     * {@link DamageKind#UNSEEN} out, and the share of UNSEEN itself is 0: it is not a source.
     */
    public static double share(FightRecord f, DamageKind kind) {
        return seenShare(f, d -> d.kind() == kind);
    }

    /** Whether {@code module} was on during {@code second}: on at the start, then as its changes up to that second left it. */
    public static boolean activeAt(FightRecord f, String module, int second) {
        boolean on = f.modulesAtStart().contains(module);
        for (ModuleChange c : f.moduleChanges()) {
            if (c.second() <= second && c.module().equals(module)) on = c.on();
        }
        return on;
    }

    /** The seconds of the fight during which at least one module of {@code role} was on. */
    public static int secondsActive(FightRecord f, ModuleRole role) {
        int seconds = 0;
        for (int second = 0; second < f.durationSeconds(); second++) {
            if (roleActiveAt(f, role, second)) seconds++;
        }
        return seconds;
    }

    private static boolean roleActiveAt(FightRecord f, ModuleRole role, int second) {
        Set<String> modules = new HashSet<>(f.modulesAtStart());
        for (ModuleChange c : f.moduleChanges()) modules.add(c.module());
        for (String module : modules) {
            if (ModuleRole.of(module) == role && activeAt(f, module, second)) return true;
        }
        return false;
    }

    /** The tick of the last lethal hit, the death's; null when the record has none. */
    private static Long lastLethalTick(FightRecord f) {
        Long last = null;
        for (DamageEvent d : f.damage()) {
            if (d.lethal() && (last == null || d.tick() > last)) last = d.tick();
        }
        return last;
    }

    /** The lethal hits within {@link DamageLedger#WINDOW_TICKS} of the death tick: what killed you. */
    private static List<DamageEvent> killingBlow(FightRecord f, Long deathTick) {
        if (deathTick == null) return List.of();
        return f.damage().stream().filter(d -> d.lethal() && inBlow(d, deathTick)).toList();
    }

    private static boolean inBlow(DamageEvent d, long deathTick) {
        return d.tick() >= deathTick - DamageLedger.WINDOW_TICKS;
    }

    /** Ticks from your last pop (a lethal hit before the killing blow) to your death, or null if you did not pop. */
    private static Long lastPopGap(FightRecord f, Long deathTick) {
        if (f.self().pops() == 0 || deathTick == null) return null;
        Long pop = null;
        for (DamageEvent d : f.damage()) {
            if (d.lethal() && !inBlow(d, deathTick) && (pop == null || d.tick() > pop)) pop = d.tick();
        }
        return pop == null ? null : deathTick - pop;
    }

    private static double seenShare(FightRecord f, Predicate<DamageEvent> which) {
        double seen = 0;
        double part = 0;
        for (DamageEvent d : f.damage()) {
            if (d.kind() == DamageKind.UNSEEN) continue;
            seen += amount(d);
            if (which.test(d)) part += amount(d);
        }
        return seen > 0 ? part / seen : 0;
    }

    /** The player that dealt you the most damage of {@code kind}, or nobody. */
    private static Object topAttacker(FightRecord f, DamageKind kind) {
        Map<String, Double> byName = new LinkedHashMap<>();
        for (DamageEvent d : f.damage()) {
            if (d.kind() == kind && d.by() == AttackerKind.PLAYER) byName.merge(d.attacker(), amount(d), Double::sum);
        }
        String top = null;
        double most = 0;
        for (Map.Entry<String, Double> e : byName.entrySet()) {
            if (e.getValue() > most) {
                top = e.getKey();
                most = e.getValue();
            }
        }
        return top == null ? RecorderText.NOBODY : top;
    }

    private static int longestRun(List<Sample> samples, Predicate<Sample> condition) {
        int longest = 0;
        int run = 0;
        for (Sample s : samples) {
            run = condition.test(s) ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        return longest;
    }

    private static boolean any(List<DamageEvent> events, Predicate<DamageEvent> which) {
        for (DamageEvent d : events) {
            if (which.test(d)) return true;
        }
        return false;
    }

    private static Object blowTail(boolean killingBlow) {
        return killingBlow ? RecorderText.CAUSE_KILLING_BLOW : RecorderText.NOTHING;
    }

    private static double amount(DamageEvent d) {
        return d.before() - d.after();
    }

    private static int percent(double share) {
        return (int) Math.round(share * 100);
    }

    private static void add(List<Cause> found, CauseKind kind, Object... evidence) {
        found.add(new Cause(kind, kind.weight(), Msg.of(kind.key(), evidence)));
    }
}
