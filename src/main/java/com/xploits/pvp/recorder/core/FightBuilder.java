package com.xploits.pvp.recorder.core;

import com.xploits.pvp.core.CombatState;
import com.xploits.pvp.recorder.core.CombatEvent.AttackerKind;
import com.xploits.pvp.recorder.core.FightRecord.DamageEvent;
import com.xploits.pvp.recorder.core.FightRecord.ModuleChange;
import com.xploits.pvp.recorder.core.FightRecord.Opponent;
import com.xploits.pvp.recorder.core.FightRecord.PhaseChange;
import com.xploits.pvp.recorder.core.FightRecord.Sample;
import com.xploits.pvp.recorder.core.FightRecord.SelfTotals;
import com.xploits.pvp.recorder.core.TickInput.AutoPvpView;
import com.xploits.pvp.recorder.core.TickInput.Hostile;
import com.xploits.pvp.recorder.core.TickInput.SelfState;
import com.xploits.shared.core.i18n.Msg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * The fight being recorded, from its first tick to the {@link FightRecord}. {@link FightTracker} decides
 * when it starts and ends; this only accumulates. Measurements (your state, hostiles, modules, auto-pvp)
 * are taken only from ticks where you are alive: the tick you die carries events, never a state.
 */
final class FightBuilder {
    static final int TICKS_PER_SECOND = 20;

    private final String addonVersion;
    private final long startTick;
    private final long startedAt;
    private final int totemsStart;
    private final List<String> modulesAtStart;
    private final DamageLedger ledger = new DamageLedger();

    private final Map<String, Tally> opponents = new LinkedHashMap<>();
    private final Set<String> dead = new TreeSet<>();
    private final List<DamageEvent> damage = new ArrayList<>();
    /** Damage from hits after the last exchange: counted once another exchange comes, else left out. */
    private final List<DamageEvent> afterLastExchange = new ArrayList<>();
    private final List<Sample> samples = new ArrayList<>();
    private final List<ModuleChange> moduleChanges = new ArrayList<>();
    private final List<PhaseChange> phases = new ArrayList<>();
    private int damageEventsDropped;

    private long lastTick;
    private int exchanges;
    private long lastExchangeTick;
    private long lastExchangeMillis;
    private long lastLivingExchangeTick;
    private long lastDeathTick;

    private int pops;
    private int placed;
    private int broken;
    private int attacks;
    private int spawnedNear;
    private double damageTaken;
    private int secondPlaced;
    private int secondBroken;
    private int secondSpawned;
    private int maxHostilesNear;

    /** The last state measured while alive: the one before the fight until the first live tick. */
    private SelfState self;
    private boolean autoPvpOn;
    private List<Hostile> hostiles;
    private Set<String> modules;
    private AutoPvpView phase;

    /** The totals as they stood at the end of the last tick with an exchange. */
    private Frozen frozen;

    private record Frozen(int pops, int placed, int broken, int attacks, int spawnedNear, int maxHostilesNear,
                          SelfState self, int opponents) {
    }

    private static final class Tally {
        int pops;
        boolean died;
        int hitsOnYou;
        double damageToYou;
        int hitsByYou;
    }

    /**
     * A fight opening on {@code startTick}. {@code context} gives the modules, hostiles and auto-pvp at the
     * start: the opening tick, or your last tick alive when it opens on the tick you die. {@code before}
     * is your state before the first hit: the ledger counts that hit from its health.
     */
    FightBuilder(String addonVersion, long startTick, long startedAt, TickInput context, SelfState before) {
        this.addonVersion = addonVersion;
        this.startTick = startTick;
        this.startedAt = startedAt;
        self = before;
        totemsStart = Math.max(0, before.totems());
        ledger.start(before.health());
        modules = context.activeModules();
        modulesAtStart = List.copyOf(new TreeSet<>(context.activeModules()));
        autoPvpOn = context.autoPvpOn();
        hostiles = context.hostiles();
        lastTick = startTick;
        lastExchangeTick = startTick;
        lastExchangeMillis = startedAt;
        lastLivingExchangeTick = startTick;
        lastDeathTick = startTick;
    }

    /**
     * Whether {@code e} is an exchange: something you and another player did to each other. Named events
     * about a player count when {@code opponent} knows the name or the player is within engage range.
     */
    static boolean exchange(CombatEvent e, TickInput in, Predicate<String> opponent) {
        return switch (e) {
            case CombatEvent.SelfDamaged h -> !h.attackerOurs() && (h.kind().isCombat() || h.by() == AttackerKind.PLAYER);
            case CombatEvent.Popped p -> p.name() == null || opponent.test(p.name()) || inRange(in, p.name());
            case CombatEvent.CrystalPlaced c -> anyInRange(in, name -> true);
            case CombatEvent.CrystalBroken c -> anyInRange(in, name -> true);
            case CombatEvent.Attacked a -> true;
            case CombatEvent.OpponentDamaged o -> o.byYou();
            case CombatEvent.PlayerDied d -> opponent.test(d.name());
            case CombatEvent.SelfDied d -> false;
            case CombatEvent.CrystalSpawnedNear c -> false;
        };
    }

    private static boolean inRange(TickInput in, String name) {
        return anyInRange(in, name::equals);
    }

    private static boolean anyInRange(TickInput in, Predicate<String> which) {
        for (Hostile h : in.hostiles()) {
            if (h.distance() <= FightTracker.ENGAGE_RANGE && which.test(h.name())) return true;
        }
        return false;
    }

    boolean isOpponent(String name) {
        return opponents.containsKey(name);
    }

    /**
     * Feeds one tick. {@code died} is the tick you die on: its hits are lethal and nothing is measured on
     * it. Live lines go to {@code live}, only once the fight has had an exchange.
     */
    void tick(TickInput in, boolean died, List<Msg> live) {
        long tick = in.tick();
        lastTick = tick;
        boolean measured = in.alive() && !died;
        boolean announced = exchanges > 0;
        List<Msg> lines = new ArrayList<>();
        boolean ownPop = false;

        for (CombatEvent e : in.events()) {
            boolean exchange = exchange(e, in, this::isOpponent);
            switch (e) {
                case CombatEvent.SelfDamaged h -> {
                    ledger.noteHit(tick, h);
                    if (h.by() == AttackerKind.PLAYER && !h.attackerOurs()) opponent(h.attacker());
                }
                case CombatEvent.OpponentDamaged o -> {
                    if (o.byYou()) opponent(o.name()).hitsByYou++;
                }
                case CombatEvent.Popped p -> {
                    if (p.name() == null) {
                        ownPop = true;
                        pops++;
                        int left = measured ? in.self().totems() : self.totems();
                        lines.add(Msg.of(RecorderText.LIVE_SELF_POP, "count", pops, "totems", left));
                    } else if (exchange) {
                        Tally t = opponent(p.name());
                        t.pops++;
                        lines.add(Msg.of(RecorderText.LIVE_OPPONENT_POP, "name", p.name(), "count", t.pops));
                    }
                }
                case CombatEvent.PlayerDied d -> {
                    if (exchange && dead.add(d.name())) {
                        opponent(d.name()).died = true;
                        lastDeathTick = tick;
                        lines.add(Msg.of(RecorderText.LIVE_OPPONENT_DIED, "name", d.name()));
                    }
                }
                case CombatEvent.CrystalPlaced c -> {
                    placed++;
                    secondPlaced++;
                }
                case CombatEvent.CrystalBroken c -> {
                    broken++;
                    secondBroken++;
                }
                case CombatEvent.CrystalSpawnedNear c -> {
                    spawnedNear++;
                    secondSpawned++;
                }
                case CombatEvent.Attacked a -> {
                    attacks++;
                    opponent(a.name());
                }
                case CombatEvent.SelfDied d -> {
                }
            }
            if (exchange) {
                exchanges++;
                lastExchangeTick = tick;
                for (DamageEvent d : afterLastExchange) record(d);
                afterLastExchange.clear();
                lastExchangeMillis = in.epochMillis();
                if (living(e, in)) lastLivingExchangeTick = tick;
            }
        }

        List<DamageEvent> hits = died ? ledger.lethal(tick) : ledger.observe(tick, in.self().health(), ownPop);
        for (DamageEvent d : hits) {
            // A hit's event carries the hit's tick, so a drop that lands after the last exchange still counts.
            if (d.tick() <= lastExchangeTick) {
                record(d);
            } else {
                afterLastExchange.add(d);
            }
            if (!d.lethal() && d.before() - d.after() >= FightTracker.BIG_HIT) {
                lines.add(Msg.of(RecorderText.LIVE_BIG_HIT, "amount", d.before() - d.after(), "kind", d.kind().label(),
                    "by", by(d)));
            }
        }

        if (measured) measure(in, lines);
        if (lastExchangeTick == tick && exchanges > 0) {
            frozen = new Frozen(pops, placed, broken, attacks, spawnedNear, maxHostilesNear, self, opponents.size());
        }

        if (exchanges == 0) return;
        if (!announced) live.add(Msg.of(RecorderText.LIVE_STARTED, "names", names()));
        live.addAll(lines);
    }

    private void measure(TickInput in, List<Msg> lines) {
        long elapsed = in.tick() - startTick;
        int second = (int) (elapsed / TICKS_PER_SECOND);
        SelfState now = in.self();
        if (self.crystals() > 0 && now.crystals() <= 0) {
            lines.add(Msg.of(RecorderText.LIVE_OUT_OF, "material", RecorderText.MATERIAL_CRYSTALS));
        }
        if (self.totems() > 0 && now.totems() <= 0) {
            lines.add(Msg.of(RecorderText.LIVE_OUT_OF, "material", RecorderText.MATERIAL_TOTEMS));
        }
        self = now;
        autoPvpOn = in.autoPvpOn();
        hostiles = in.hostiles();
        maxHostilesNear = Math.max(maxHostilesNear, hostilesNear());

        if (!in.activeModules().equals(modules)) {
            Set<String> all = new TreeSet<>(modules);
            all.addAll(in.activeModules());
            for (String module : all) {
                boolean on = in.activeModules().contains(module);
                if (on != modules.contains(module)) change(new ModuleChange(second, module, on));
            }
            modules = in.activeModules();
        }

        AutoPvpView view = in.autoPvp();
        if (view != null) {
            if (view.target() != null) opponent(view.target());
            if (!view.equals(phase)) phase(new PhaseChange(second, view.state(), view.posture(), view.target()));
            phase = view;
        }

        if (elapsed % TICKS_PER_SECOND == TICKS_PER_SECOND - 1) sample(second);
    }

    /** Whether an exchange was with a player still alive, which keeps a fight with a dead opponent open. */
    private boolean living(CombatEvent e, TickInput in) {
        return switch (e) {
            case CombatEvent.SelfDamaged h when h.by() == AttackerKind.PLAYER -> !dead.contains(h.attacker());
            case CombatEvent.Popped p when p.name() != null -> !dead.contains(p.name());
            case CombatEvent.Attacked a -> !dead.contains(a.name());
            case CombatEvent.OpponentDamaged o -> !dead.contains(o.name());
            case CombatEvent.PlayerDied d -> false;
            default -> anyInRange(in, name -> !dead.contains(name));
        };
    }

    private Tally opponent(String name) {
        return opponents.computeIfAbsent(name, n -> new Tally());
    }

    private void record(DamageEvent d) {
        double amount = d.before() - d.after();
        damageTaken += amount;
        if (d.by() == AttackerKind.PLAYER) {
            Tally t = opponents.get(d.attacker());
            if (t != null) {
                t.hitsOnYou++;
                t.damageToYou += amount;
            }
        }
        if (damage.size() < FightTracker.MAX_DAMAGE_EVENTS) {
            damage.add(d);
        } else {
            damageEventsDropped++;
        }
    }

    private void change(ModuleChange c) {
        if (moduleChanges.size() < FightTracker.MAX_CHANGES) moduleChanges.add(c);
    }

    /** Past the cap the newest phase replaces the last one kept: the phase you ended in is never lost. */
    private void phase(PhaseChange c) {
        if (phases.size() < FightTracker.MAX_CHANGES) {
            phases.add(c);
        } else {
            phases.set(phases.size() - 1, c);
        }
    }

    private void sample(int second) {
        samples.add(new Sample(second, self.health(), self.incoming(), self.totems(), self.offhandTotem(), self.crystals(),
            self.obsidian(), self.gapples(), self.armor(), nearestHostile(), hostilesNear(), self.inHole(), self.gliding(),
            autoPvpOn, secondPlaced, secondBroken, secondSpawned));
        secondPlaced = 0;
        secondBroken = 0;
        secondSpawned = 0;
    }

    private Double nearestHostile() {
        Double nearest = null;
        for (Hostile h : hostiles) {
            if (h.distance() <= FightTracker.ENGAGE_RANGE && (nearest == null || h.distance() < nearest)) nearest = h.distance();
        }
        return nearest;
    }

    private int hostilesNear() {
        int n = 0;
        for (Hostile h : hostiles) {
            if (h.distance() <= FightTracker.NEAR_RANGE) n++;
        }
        return n;
    }

    private static Object by(DamageEvent d) {
        return switch (d.by()) {
            case PLAYER -> Msg.of(RecorderText.LIVE_BY, "name", d.attacker());
            case SELF -> RecorderText.BY_SELF;
            case NONE -> RecorderText.BY_NONE;
        };
    }

    /** The opponents so far as "Foo, Bar", or nobody. */
    Object names() {
        Object head = null;
        for (String name : opponents.keySet()) head = head == null ? name : Msg.of(RecorderText.JOIN_COMMA, "first", head, "rest", name);
        return head == null ? RecorderText.NOBODY : head;
    }

    int exchanges() {
        return exchanges;
    }

    long startTick() {
        return startTick;
    }

    long lastTick() {
        return lastTick;
    }

    long lastExchangeTick() {
        return lastExchangeTick;
    }

    boolean anyDied() {
        return !dead.isEmpty();
    }

    /** The tick the settling after an opponent's death counts from: that death or a later exchange with the living. */
    long settlingSince() {
        return Math.max(lastDeathTick, lastLivingExchangeTick);
    }

    /**
     * The record. {@code atLastExchange} ends it at the last exchange (a fight that went quiet): every total,
     * the damage, the samples and the changes stop there, and what came after is left out. Otherwise it ends
     * at {@code endMillis} and the second in progress is sampled. A wall clock that stepped back never ends
     * it before it started.
     */
    FightRecord build(FightOutcome outcome, boolean atLastExchange, long endMillis, boolean truncated) {
        long endedAt;
        List<Sample> keptSamples;
        List<ModuleChange> keptChanges;
        List<PhaseChange> keptPhases;
        Frozen totals;
        if (atLastExchange) {
            endedAt = Math.max(startedAt, lastExchangeMillis);
            int last = (int) ((lastExchangeTick - startTick) / TICKS_PER_SECOND);
            keptSamples = samples.stream().filter(s -> s.second() <= last).toList();
            keptChanges = moduleChanges.stream().filter(c -> c.second() <= last).toList();
            keptPhases = phases.stream().filter(p -> p.second() <= last).toList();
            totals = frozen;
        } else {
            endedAt = Math.max(startedAt, endMillis);
            for (DamageEvent d : afterLastExchange) record(d);
            afterLastExchange.clear();
            int current = (int) ((lastTick - startTick) / TICKS_PER_SECOND);
            if (samples.isEmpty() || samples.getLast().second() < current) sample(current);
            keptSamples = samples;
            keptChanges = moduleChanges;
            keptPhases = phases;
            totals = new Frozen(pops, placed, broken, attacks, spawnedNear, maxHostilesNear, self, opponents.size());
        }
        int seconds = keptSamples.size();
        int autoPvpSeconds = (int) keptSamples.stream().filter(Sample::autoPvp).count();
        List<Opponent> opponentList = new ArrayList<>();
        for (Map.Entry<String, Tally> e : opponents.entrySet()) {
            if (opponentList.size() == totals.opponents()) break;
            Tally t = e.getValue();
            opponentList.add(new Opponent(e.getKey(), t.pops, t.died, t.hitsOnYou, t.damageToYou, t.hitsByYou));
        }
        SelfTotals self = new SelfTotals(totals.pops(), totemsStart, Math.max(0, totals.self().totems()),
            totals.self().offhandTotem(), damageTaken, totals.placed(), totals.broken(), totals.attacks(),
            Math.max(0, totals.spawnedNear() - totals.placed()));
        return new FightRecord(FightRecord.SCHEMA, addonVersion, startedAt, endedAt, seconds, outcome, truncated,
            FightMode.of(autoPvpSeconds, seconds), autoPvpSeconds, opponentList, totals.maxHostilesNear(), self, damage,
            damageEventsDropped, keptSamples, modulesAtStart, keptChanges, keptPhases);
    }

    /** Whether auto-pvp is engaged in a fight on this tick (its phase is not NO_COMBAT). */
    static boolean engaged(TickInput in) {
        return in.autoPvp() != null && in.autoPvp().state() != CombatState.NO_COMBAT;
    }
}
