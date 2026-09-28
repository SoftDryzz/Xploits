package com.xploits.bench;

import com.xploits.pvp.crystal.CrystalAuraPlusPlus;
import com.xploits.pvp.crystal.core.CrystalSetting;
import com.xploits.pvp.crystal.core.RiskLevel;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.CrystalAura;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Task A3: the real fights {@code exchange}, {@code hole-standoff} and {@code near-death} ({@code city} runs
 * separately, {@link CityMeasure}, since auto-pvp drives our own side there rather than the aura directly) —
 * the fight-mode twin of {@link CrystalAuraMeasure}: fight loadout ({@link Arena#fightLoadout()}), finite
 * totems on both sides, gapples after every pop, our death and the sparring's death outcomes rather than
 * errors (task A1, {@link FightMeasureRun}). Each is judged against its Meteor twin the same way
 * ({@link Scenario#compareWith}); crystal-aura++ runs at the {@code risk} level its scenario name says, exactly
 * as {@link CrystalAuraMeasure}. Runs 30 s (task A1 requirement 5), or less if either player dies first.
 */
final class FightMeasure implements Scenario {
    private final String name;
    private final Class<? extends Module> aura;
    private final Class<? extends Module> other;
    private final String compareWith;
    private final Supplier<Script> script;
    /** crystal-aura++'s {@code risk} level; null for Meteor's aura. */
    private final RiskLevel risk;
    /** {@code near-death} only (task A3): sets both players' starting health and the opponent's totem before
     * T0; null for every other fight. */
    private final NearDeathSetup startingLow;
    /** The script this run's {@link #arrange} built, read back in {@link #act} to log its behaviour counters
     * (task A3's own "Proof" requirement) once the run is over. */
    private Script builtScript;
    /** Runs arranged so far, the one in progress included: the {@code n} of the counters log line. */
    private int runs;

    private FightMeasure(String name, Class<? extends Module> aura, Class<? extends Module> other, String compareWith,
                         Supplier<Script> script, RiskLevel risk, NearDeathSetup startingLow) {
        this.name = name;
        this.aura = aura;
        this.other = other;
        this.compareWith = compareWith;
        this.script = script;
        this.risk = risk;
        this.startingLow = startingLow;
    }

    /** Meteor's CrystalAura. */
    static FightMeasure meteor(String name, Supplier<Script> script) {
        return new FightMeasure(name, CrystalAura.class, CrystalAuraPlusPlus.class, null, script, null, null);
    }

    /** crystal-aura++ at Safe, judged against the Meteor scenario {@code compareWith}. */
    static FightMeasure plusPlus(String name, String compareWith, Supplier<Script> script) {
        return plusPlus(name, compareWith, script, RiskLevel.SAFE);
    }

    /** crystal-aura++ at the level {@code risk}, judged against the Meteor scenario {@code compareWith}. */
    static FightMeasure plusPlus(String name, String compareWith, Supplier<Script> script, RiskLevel risk) {
        return new FightMeasure(name, CrystalAuraPlusPlus.class, CrystalAura.class, compareWith, script,
            Objects.requireNonNull(risk, "risk"), null);
    }

    /**
     * Task A3 ({@code near-death}): both players start near death ({@value NearDeathSetup#HEALTH} health, no
     * absorption) holding a totem; the opponent's totem is stripped unless {@code totem}, so our first
     * successful hit against it is a real kill rather than a pop.
     */
    FightMeasure startingLow(boolean totem) {
        return new FightMeasure(name, aura, other, compareWith, script, risk, new NearDeathSetup(totem));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Kind kind() {
        return Kind.MEASURE;
    }

    @Override
    public int seconds() {
        return 30;
    }

    @Override
    public Optional<RiskLevel> risk() {
        return Optional.ofNullable(risk);
    }

    @Override
    public boolean simulatesPing() {
        return true;
    }

    @Override
    public Optional<String> compareWith() {
        return Optional.ofNullable(compareWith);
    }

    @Override
    public void arrange(Bench bench) {
        runs++;
        if (aura == CrystalAura.class) {
            MeasureRun.crystalAura(bench);
        } else {
            CrystalAuraPlusPlus plusPlus = MeasureRun.crystalAuraPlusPlus(bench);
            CrystalSetting level = CrystalSetting.RISK;
            bench.setting(plusPlus, level.group().title(), level.id(), risk);
            Object took = bench.fromClient(client -> plusPlus.settings.getGroup(level.group().title()).get(level.id()).get());
            if (took != risk) throw new BenchException("crystal-aura++ runs at the risk level " + took + ", not " + risk);
        }
        bench.onClient(client -> {
            Module module = Modules.get().get(other);
            if (module == null) throw new BenchException("no module " + other.getSimpleName());
            if (module.isActive()) module.disable();
        });
        bench.arena().fightLoadout();
        builtScript = script.get();
        bench.spawnForFight(builtScript);
        if (startingLow != null) startingLow.apply(bench);
    }

    @Override
    public Metrics act(Bench bench) {
        if (bench.fromClient(client -> Modules.get().get(other).isActive())) {
            throw new BenchException(other.getSimpleName() + " was on before T0");
        }
        Metrics metrics = new FightMeasureRun(bench, aura).play(seconds() * 20).metrics();
        // Script#close() otherwise only runs at teardown (Sparring#despawn, after this method returns): a
        // cycle still pending at exactly the last tick (TIME_UP) would log one short of spawned() ==
        // explosions() + brokenFirst() + abandoned() if read before it. Calling it here is safe even though
        // Sparring will call it again at teardown: every close() this task wrote (CrystalAttack, SurroundMiner)
        // is idempotent past its first call (it only acts while a crystal is still pending).
        bench.onServer(srv -> builtScript.close());
        Fights.logCounters(name, runs, builtScript);
        return metrics;
    }

    /** Task A3 ({@code near-death}): both players' starting health and the opponent's totem, set once, after
     * the spawn, before T0. */
    private record NearDeathSetup(boolean totem) {
        static final float HEALTH = 6f;

        void apply(Bench bench) {
            String player = bench.player();
            bench.onServer(srv -> {
                ServerPlayerEntity p = Arena.player(srv, player);
                p.setHealth(HEALTH);
                p.setAbsorptionAmount(0f);
            });
            Sparring sparring = bench.sparring();
            bench.onServer(srv -> {
                sparring.setHealth(HEALTH);
                sparring.setAbsorptionAmount(0f);
                if (!totem) sparring.disarmTotem();
            });
        }
    }
}
