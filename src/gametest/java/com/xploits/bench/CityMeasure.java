package com.xploits.bench;

import com.xploits.pvp.AutoPvp;
import com.xploits.pvp.core.CrystalModule;
import com.xploits.pvp.core.ManagedModules;
import com.xploits.pvp.crystal.CrystalAuraPlusPlus;
import com.xploits.pvp.crystal.core.CrystalSetting;
import com.xploits.pvp.crystal.core.RiskLevel;

import java.util.Objects;
import java.util.Optional;

/**
 * Task A3: the {@code city} fight — we stand in a surrounded 1x1 obsidian hole and our own side runs auto-pvp,
 * {@code crystal-module} {@code meteor} for {@code ca-city} and {@code xploits++} for the {@code capp-city}
 * family (the existing auto-pvp CHECKs' own pattern, {@link AutoPvpScene}), so it drives {@code surround}
 * (patching the opponent's {@link SurroundMiner} back) and the aura — this also measures today's
 * self-protection, the plan's P4 baseline. Everything else (the fight loadout, finite totems, gapples, the
 * death/win/loss outcome and metrics) is task A1's {@link FightMeasureRun}, exactly as {@link FightMeasure}
 * uses it for the other three fights; {@code city} is its own scenario class only because {@link AutoPvp}, not
 * a crystal aura directly, is what T0 enables here.
 */
final class CityMeasure implements Scenario {
    private final String name;
    private final CrystalModule module;
    private final String compareWith;
    /** crystal-aura++'s {@code risk} level; null when {@code module} is Meteor's. */
    private final RiskLevel risk;
    /** The script this run's {@link #arrange} built, read back in {@link #act} to log its behaviour counters
     * (task A3's own "Proof" requirement) once the run is over. */
    private Script builtScript;
    /** Runs arranged so far, the one in progress included: the {@code n} of the counters log line. */
    private int runs;

    private CityMeasure(String name, CrystalModule module, String compareWith, RiskLevel risk) {
        this.name = name;
        this.module = module;
        this.compareWith = compareWith;
        this.risk = risk;
    }

    /** Auto-pvp drives Meteor's CrystalAura ({@code crystal-module meteor}). */
    static CityMeasure meteor(String name) {
        return new CityMeasure(name, CrystalModule.METEOR, null, null);
    }

    /** Auto-pvp drives crystal-aura++ at Safe ({@code crystal-module xploits++}), judged against {@code compareWith}. */
    static CityMeasure plusPlus(String name, String compareWith) {
        return plusPlus(name, compareWith, RiskLevel.SAFE);
    }

    /** Auto-pvp drives crystal-aura++ at the level {@code risk}, judged against {@code compareWith}. */
    static CityMeasure plusPlus(String name, String compareWith, RiskLevel risk) {
        return new CityMeasure(name, CrystalModule.XPLOITS, compareWith, Objects.requireNonNull(risk, "risk"));
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
        AutoPvpScene.arrange(bench, "balanced", module);
        if (module == CrystalModule.XPLOITS) {
            // AutoPvpScene.arrange already reset crystal-aura++'s settings (including risk back to its own
            // default, Balanced); set the level this scenario's name asks for, exactly as FightMeasure does.
            CrystalAuraPlusPlus plusPlus = MeasureRun.crystalAuraPlusPlus(bench);
            CrystalSetting level = CrystalSetting.RISK;
            bench.setting(plusPlus, level.group().title(), level.id(), risk);
            Object took = bench.fromClient(client -> plusPlus.settings.getGroup(level.group().title()).get(level.id()).get());
            if (took != risk) throw new BenchException("crystal-aura++ runs at the risk level " + took + ", not " + risk);
        }
        bench.arena().fightLoadout();
        builtScript = Fights.city();
        bench.spawnForFight(builtScript);
    }

    @Override
    public Metrics act(Bench bench) {
        // T0 is FightMeasureRun's own (it enables AutoPvp itself): checked here, not through
        // AutoPvpScene.start, which would set T0 a second time.
        if (bench.fromClient(client -> AutoPvpScene.on(ManagedModules.CRYSTAL_AURA))) {
            throw new BenchException("crystal-aura was on before T0");
        }
        if (bench.fromClient(client -> AutoPvpScene.plusPlusOn())) {
            throw new BenchException("crystal-aura++ was on before T0");
        }
        Metrics metrics = new FightMeasureRun(bench, AutoPvp.class).play(seconds() * 20).metrics();
        // See FightMeasure#act: close() otherwise only runs at teardown, one cycle after this method returns.
        bench.onServer(srv -> builtScript.close());
        Fights.logCounters(name, runs, builtScript);
        return metrics;
    }
}
