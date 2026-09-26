package com.xploits.bench;

import com.xploits.bench.core.CrystalRefill;
import com.xploits.bench.core.Settle;
import com.xploits.bench.core.SettleVerification;
import com.xploits.pvp.crystal.CrystalAuraPlusPlus;
import com.xploits.pvp.crystal.core.CrystalBrain;
import com.xploits.pvp.crystal.core.CrystalSetting;
import com.xploits.pvp.crystal.core.RiskLevel;
import com.xploits.pvp.recorder.core.FightRecord;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.CrystalAura;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.rule.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * MEASURE {@code ca-still}, {@code ca-circler} and {@code ca-defender} (spec {@code
 * 2026-09-25-ingame-bench}, §Scenarios, §Metrics): Meteor's CrystalAura, reset with {@code pause-on-lag}
 * off, against a scripted sparring for 30 s, from the standard loadout (AutoTotem Strict) with the
 * recorder on. And {@code capp-still}, {@code capp-circler} and {@code capp-defender} (crystal-aura++
 * spec §4, P6): crystal-aura++ the same way, on the same arenas and scripts, each judged against its
 * {@code ca-} twin ({@link #compareWith}). Each of them also has a healing twin, {@code <name>-regen}
 * ({@link #healing}), on the same arena and script with natural regeneration on (crystal-aura++ spec,
 * Round 2 (b)); those that run in the full bench are judged against each other too. crystal-aura++ runs at
 * the {@code risk} level its scenario names ({@link #plusPlus(String, String, Supplier, RiskLevel)}): the
 * {@code capp-X} ones at Safe (R2-5's original level; the {@code risk} setting's own default is Balanced since
 * R3-8), and {@code capp-balanced-X} and {@code capp-aggressive-X} at those levels (R2-5), each set and read
 * back before T0. The fight situations (R3-5: above, below, approach,
 * strafe) run the same way, only as healing twins ({@link Scenarios}).
 *
 * <p>Each run turns the other aura off before T0, so only the aura under test acts.
 *
 * <p>From T0 to the end of the run the server tops the crystal stack in hotbar slot 0 back up to
 * {@value CrystalRefill#FULL} after every tick (R3-7, {@link CrystalRefill}), for both auras, so no run runs out of
 * crystals and ammunition never decides a number. The crystals the refills added are logged once per run, not a
 * metric.
 *
 * <p>A run lasts 30 s, unless it settles first (R3-6, {@link Settle}): natural regeneration off (the scenario's
 * and the world's rule, read at T0, which must agree), a static script
 * ({@link Script#isStatic}), and {@value Settle#SETTLE_TICKS} ticks in a row with no end crystal in the world
 * (the server's or the client's), no block or entity interaction sent (so no placement and no attack), and
 * neither our health plus absorption nor the sparring's nor its pops changing. Nothing can happen after that, so the run ends there
 * with the numbers it would have had at 30 s, and says so in one log line.
 *
 * <p>With {@code -Pbench.verifySettle} (the system property {@value #VERIFY_SETTLE}) a run that settles does not
 * end: its metrics are snapshotted at the first settled tick, it runs on to 30 s, and its final metrics must equal
 * the snapshot and it must stay settled to the end ({@link SettleVerification}); anything else is ERROR, naming
 * what changed.
 *
 * <p>Metrics: {@code damage_dealt}, {@code sparring_pops}, {@code first_pop_s} (only in a run that
 * popped), {@code no_pop_runs} (1 for a run without a pop), {@code self_damage}, {@code self_pops},
 * {@code min_health} and {@code placements_per_s}, the placements over the nominal 30 s even when the run
 * settled earlier. A crystal-aura++ run also logs how many placements it held
 * back for the sparring's hurt window (R3-3); that count is not a metric.
 */
final class CrystalAuraMeasure implements Scenario {
    private static final Logger LOG = LoggerFactory.getLogger("xploits-bench");
    /** The system property {@code -Pbench.verifySettle} sets (build.gradle.kts). */
    static final String VERIFY_SETTLE = "xploits.bench.verify-settle";

    private final String name;
    private final Class<? extends Module> aura;
    private final Class<? extends Module> other;
    private final String compareWith;
    private final Supplier<Script> script;
    private final boolean regeneration;
    /** crystal-aura++'s {@code self-budget}; always on but for the CHECK {@code capp-budget-off-parity}. */
    private final boolean selfBudget;
    /** crystal-aura++'s {@code risk} level; null for Meteor's aura. */
    private final RiskLevel risk;
    private MeasureRun run;
    /** Runs arranged so far, the one in progress included: the {@code n} of the settle and refill lines. */
    private int runs;
    /** The refills of the run in progress. */
    private CrystalRefill refill;

    private CrystalAuraMeasure(String name, Class<? extends Module> aura, Class<? extends Module> other,
                               String compareWith, Supplier<Script> script, boolean regeneration, boolean selfBudget,
                               RiskLevel risk) {
        this.name = name;
        this.aura = aura;
        this.other = other;
        this.compareWith = compareWith;
        this.script = script;
        this.regeneration = regeneration;
        this.selfBudget = selfBudget;
        this.risk = risk;
    }

    /** Meteor's CrystalAura. */
    static CrystalAuraMeasure meteor(String name, Supplier<Script> script) {
        return new CrystalAuraMeasure(name, CrystalAura.class, CrystalAuraPlusPlus.class, null, script, false, true, null);
    }

    /**
     * crystal-aura++ at Safe (R2-5's original level; the {@code risk} setting's own default is Balanced since
     * R3-8, but these {@code capp-*} scenarios' numbers were tuned to Safe and must keep running at it),
     * judged against the Meteor scenario {@code compareWith} on the same arena and script.
     */
    static CrystalAuraMeasure plusPlus(String name, String compareWith, Supplier<Script> script) {
        return plusPlus(name, compareWith, script, RiskLevel.SAFE);
    }

    /** crystal-aura++ at the level {@code risk}, judged against the Meteor scenario {@code compareWith}. */
    static CrystalAuraMeasure plusPlus(String name, String compareWith, Supplier<Script> script, RiskLevel risk) {
        return new CrystalAuraMeasure(name, CrystalAuraPlusPlus.class, CrystalAura.class, compareWith, script, false, true,
            Objects.requireNonNull(risk, "risk"));
    }

    /**
     * crystal-aura++ with {@code self-budget} off: Meteor's offense and nothing else, for the CHECK
     * {@code capp-budget-off-parity} ({@link CappBudgetOffParity}), which does the judging. Its level is Safe
     * (kept explicit, not the {@code risk} setting's default); with the budget off no level changes anything.
     */
    static CrystalAuraMeasure plusPlusWithoutBudget(String name, Supplier<Script> script) {
        return new CrystalAuraMeasure(name, CrystalAuraPlusPlus.class, CrystalAura.class, null, script, false, false,
            RiskLevel.SAFE);
    }

    /** The suffix of a healing twin's name. */
    static final String HEALING = "-regen";

    /**
     * This scenario's healing twin: {@code <name>-regen}, the same aura, arena, script, loadout and timing,
     * with natural regeneration on; a crystal-aura++ one is judged against {@code <compareWith>-regen}.
     */
    CrystalAuraMeasure healing() {
        if (regeneration) throw new IllegalStateException(name + " already heals");
        return new CrystalAuraMeasure(name + HEALING, aura, other, compareWith == null ? null : compareWith + HEALING,
            script, true, selfBudget, risk);
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
    public boolean naturalRegeneration() {
        return regeneration;
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
            if (!selfBudget) {
                CrystalSetting budget = CrystalSetting.SELF_BUDGET;
                bench.setting(plusPlus, budget.group().title(), budget.id(), false);
                if (bench.fromClient(client -> (Boolean) plusPlus.settings.getGroup(budget.group().title()).get(budget.id()).get())) {
                    throw new BenchException("crystal-aura++ kept its self-budget on");
                }
            }
        }
        run = new MeasureRun(bench, aura);
        bench.onClient(client -> {
            Module module = Modules.get().get(other);
            if (module == null) throw new BenchException("no module " + other.getSimpleName());
            if (module.isActive()) module.disable();
        });
        bench.arena().loadout(false);
        bench.spawn(script.get());
    }

    @Override
    public Metrics act(Bench bench) {
        if (bench.fromClient(client -> Modules.get().get(other).isActive())) {
            throw new BenchException(other.getSimpleName() + " was on before T0");
        }
        refill = new CrystalRefill();
        run.start();
        SettleVerification verification = waitOut(bench);
        LOG.info("[bench] {} run {}: used {} crystal(s) (the stack was refilled, log only)", name, runs, refill.used());
        if (aura == CrystalAuraPlusPlus.class) {
            int held = bench.fromClient(client -> Modules.get().get(CrystalAuraPlusPlus.class).deferredForTargetWindow());
            LOG.info("[bench] {}: crystal-aura++ held {} placement(s) for the target's hurt window (log only, not a metric)",
                name, held);
        }
        Sparring.Stats sparring = bench.sparringStats();
        run.close();

        // The sparring cannot lose more than it was dealt (§Proving the bench works, sparring validity).
        if (sparring.damageTaken() > sparring.rawDamage() + 1e-3) {
            throw new BenchException("the sparring lost more health than it was dealt");
        }
        Metrics metrics = metrics(sparring, run.selfDamage(), run.selfPops(), run.minHealth(), run.crystalsPlaced());
        if (verification != null) verify(verification, metrics);
        return metrics;
    }

    /** The run's metrics from their parts; the same for the close and for a settle's snapshot. */
    private Metrics metrics(Sparring.Stats sparring, double selfDamage, int selfPops, double minHealth, int crystalsPlaced) {
        Metrics metrics = new Metrics()
            .put(Metrics.DAMAGE_DEALT, sparring.damageTaken())
            .put(Metrics.SPARRING_POPS, sparring.pops());
        if (sparring.firstPopTick() >= 0) metrics.put(Metrics.FIRST_POP_S, sparring.firstPopTick() / 20.0);
        return metrics
            .put(Metrics.NO_POP_RUNS, sparring.pops() == 0 ? 1 : 0)
            .put(Metrics.SELF_DAMAGE, selfDamage)
            .put(Metrics.SELF_POPS, selfPops)
            .put(Metrics.MIN_HEALTH, minHealth)
            .put(Metrics.PLACEMENTS_PER_S, SettleVerification.perNominalSecond(crystalsPlaced, seconds()));
    }

    /**
     * The run's {@link #seconds()}, tick by tick, or less once it has settled ({@link Settle}); a run that cannot
     * settle waits them all. At T0 and after every tick the crystal stack is topped up, in the one server call of
     * that tick ({@link #observe}, or {@link #topUp} alone when nothing is observed). With {@value #VERIFY_SETTLE}
     * set, a settled run runs on to the end and the verification is returned for the close; otherwise null.
     */
    private SettleVerification waitOut(Bench bench) {
        int nominal = seconds() * 20;
        boolean worldRegeneration = bench.fromServer(srv ->
            srv.getOverworld().getGameRules().getValue(GameRules.NATURAL_HEALTH_REGENERATION));
        Settle settle;
        try {
            settle = new Settle(regeneration, worldRegeneration, bench.sparring().script().isStatic());
        } catch (IllegalStateException e) {
            throw new BenchException(name + ": " + e.getMessage());
        }
        String player = bench.player();
        if (!settle.possible()) {
            topUp(bench, player, refill);
            for (int tick = 1; tick <= nominal; tick++) {
                bench.ticks(1);
                topUp(bench, player, refill);
            }
            return null;
        }
        SettleVerification verification = Boolean.getBoolean(VERIFY_SETTLE) ? new SettleVerification() : null;
        boolean plusPlus = aura == CrystalAuraPlusPlus.class;
        settle.observe(observe(bench, plusPlus, player, refill));
        for (int tick = 1; tick <= nominal; tick++) {
            bench.ticks(1);
            boolean settled = settle.observe(observe(bench, plusPlus, player, refill));
            if (verification == null) {
                if (settled && tick < nominal) {
                    LOG.info("[bench] {} run {}: settled after {} s, the remaining {} s could not change anything", name,
                        runs, seconds(tick), seconds(nominal - tick));
                    return null;
                }
            } else if (tick < nominal || verification.settledAt() >= 0) {
                // A settle on the last tick would cut nothing, so it arms nothing either.
                if (verification.tick(tick, settled)) verification.snapshot(snapshot(bench).values());
            }
        }
        return verification;
    }

    /**
     * The metrics as the close would give them now, without ending the run: the sparring's counters, our lowest
     * health so far, and the records so far ({@link MeasureRun#recordsSoFar}).
     */
    private Metrics snapshot(Bench bench) {
        Sparring.Stats sparring = bench.sparringStats();
        List<FightRecord> records = run.recordsSoFar();
        return metrics(sparring, MeasureRun.selfDamage(records), MeasureRun.selfPops(records), run.minHealth(),
            MeasureRun.crystalsPlaced(records));
    }

    /** A settled run's final metrics against its snapshot: the verified line, or ERROR naming what changed. */
    private void verify(SettleVerification verification, Metrics atEnd) {
        int at = verification.settledAt();
        if (at < 0) return;
        int nominal = seconds() * 20;
        List<String> problems = verification.problems(atEnd.values());
        if (!problems.isEmpty()) {
            throw new BenchException(name + " run " + runs + " settled at " + seconds(at) + " s but "
                + String.join("; ", problems));
        }
        LOG.info("[bench] {} run {}: settle verified at {} s, nothing changed in the remaining {} s", name, runs,
            seconds(at), seconds(nominal - at));
    }

    /** Ticks as seconds, with two decimals. */
    private static String seconds(int ticks) {
        return String.format(Locale.ROOT, "%.2f", ticks / 20.0);
    }

    /** Tops the crystal stack up ({@link #topUp(MinecraftServer, String, CrystalRefill)}) in one server call. */
    private static void topUp(Bench bench, String player, CrystalRefill refill) {
        bench.onServer(srv -> topUp(srv, player, refill));
    }

    /**
     * On the server thread: tops the end crystals in {@code player}'s hotbar slot 0 up to {@value CrystalRefill#FULL}
     * and counts what it added in {@code refill}; a stack already full is left alone. A top-up is sent to the client
     * at once: the client takes a crystal off its own stack when it places one, and without the slot the server has
     * already refilled it might never hear that the stack is full again. Anything but end crystals in the slot is
     * ERROR, naming it.
     */
    private static void topUp(MinecraftServer srv, String player, CrystalRefill refill) {
        ServerPlayerEntity serverPlayer = Arena.player(srv, player);
        PlayerInventory inventory = serverPlayer.getInventory();
        ItemStack stack = inventory.getStack(0);
        if (!stack.isOf(Items.END_CRYSTAL)) {
            throw new BenchException("hotbar slot 0 holds " + (stack.isEmpty() ? "nothing"
                : stack.getCount() + " " + Registries.ITEM.getId(stack.getItem())) + ", not end crystals");
        }
        if (refill.topUp(stack.getCount()) > 0) {
            stack.setCount(CrystalRefill.FULL);
            serverPlayer.networkHandler.sendPacket(inventory.createSlotSetPacket(0));
        }
    }

    /**
     * What {@link Settle} looks at, now, in one client call and one server call: the end crystals the client and
     * the server have, the block and entity interaction packets sent so far (every placement and every attack,
     * and more), our health plus absorption and, for crystal-aura++, its longest timer from our ping on the client;
     * the sparring's health plus absorption and its pops on the server. Meteor's timers are fixed at their short
     * defaults ({@link Settle}), so its timer is 0. The server call also tops the crystal stack up first
     * ({@link #topUp(MinecraftServer, String, CrystalRefill)}): that changes none of what is observed.
     */
    private static Settle.Observation observe(Bench bench, boolean plusPlus, String player, CrystalRefill refill) {
        ClientSide client = bench.fromClient(mc -> {
            if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) {
                throw new BenchException("the client has no player");
            }
            int crystals = 0;
            for (Entity entity : mc.world.getEntities()) {
                if (entity instanceof EndCrystalEntity) crystals++;
            }
            int timer = 0;
            if (plusPlus) {
                // The ping crystal-aura++ itself takes (CrystalAuraPlusPlus.pingTicks): our latency in the player list.
                PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
                int latency = entry == null ? CrystalBrain.UNKNOWN_LATENCY : entry.getLatency();
                timer = Settle.plusPlusLongestTimer(CrystalBrain.pingTicks(latency));
            }
            return new ClientSide(crystals, PlacementCounter.get().blockInteractionsSent(),
                PlacementCounter.get().entityInteractionsSent(),
                (double) mc.player.getHealth() + mc.player.getAbsorptionAmount(), timer);
        });
        Sparring sparring = bench.sparring();
        ServerSide server = bench.fromServer(srv -> {
            topUp(srv, player, refill);
            return new ServerSide(srv.getOverworld().getEntitiesByType(EntityType.END_CRYSTAL, e -> true).size(),
                (double) sparring.getHealth() + sparring.getAbsorptionAmount(), sparring.stats(-1).pops());
        });
        return new Settle.Observation(client.crystals() + server.crystals(), client.blockInteractions(),
            client.entityInteractions(), client.health(), server.sparringHealth(), server.sparringPops(), client.timer());
    }

    /** What {@link #observe} reads on the client, in one call. */
    private record ClientSide(int crystals, int blockInteractions, int entityInteractions, double health, int timer) {
    }

    /** What {@link #observe} reads on the server, in one call. */
    private record ServerSide(int crystals, double sparringHealth, int sparringPops) {
    }
}
