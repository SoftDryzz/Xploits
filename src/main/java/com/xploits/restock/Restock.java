package com.xploits.restock;

import com.xploits.XploitsAddon;
import com.xploits.printer.core.BaritoneSession;
import com.xploits.printer.core.BuildIndex;
import com.xploits.printer.core.Guards;
import com.xploits.printer.core.PrinterLimits;
import com.xploits.restock.core.RestockLimits;
import com.xploits.restock.core.RestockMessages;
import com.xploits.restock.core.RestockReason;
import com.xploits.restock.core.RestockSetting;
import com.xploits.restock.core.RestockSettings;
import com.xploits.restock.core.RestockText;
import com.xploits.restock.core.RestockTrip;
import com.xploits.restock.core.ShulkersLeft;
import com.xploits.restock.core.UnpackPlan;
import com.xploits.restock.litematica.LitematicaAccess;
import com.xploits.shared.Texts;
import com.xploits.shared.XploitsModule;
import com.xploits.shared.core.i18n.Msg;
import com.xploits.stash.StashKeeper;
import com.xploits.sweep.NetherSweep;
import com.xploits.travel.AutoTravel;
import com.xploits.travel.core.SafetyNet;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code restock} (spec {@code 2026-10-01-restock-design}): when a block the selected Litematica placement still needs
 * runs out, it pauses litematica-printer, walks with Baritone to the nearest marked or stash-keeper container that has
 * it, takes what the rest of the build needs, walks back and resumes the printer. The decisions are in
 * {@code restock.core}; this module, {@link RestockSession} and {@link TripDriver} measure and execute.
 */
public class Restock extends XploitsModule {
    private static final String BARITONE_MOD_ID = "baritone";
    private static final RestockLimits LIMITS = RestockLimits.DEFAULTS;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgMaterial = settings.createGroup("Material");
    private final SettingGroup sgBaritone = settings.createGroup("Baritone");
    private final SettingGroup sgSafety = settings.createGroup("Safety");

    /** No action: Meteor runs a keybind's action only while its module is on; {@link Marks} listens to it always. */
    private final Setting<Keybind> markKey = sgGeneral.add(new KeybindSetting.Builder()
        .name(RestockSetting.MARK_KEY.id())
        .description(Texts.startupText(RestockSetting.MARK_KEY.text()))
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Integer> maxDistance = sgMaterial.add(new IntSetting.Builder()
        .name(RestockSetting.MAX_DISTANCE.id())
        .description(Texts.startupText(RestockSetting.MAX_DISTANCE.text()))
        .defaultValue(RestockSettings.DEFAULTS.maxDistance())
        .range(RestockSettings.MIN_DISTANCE, RestockSettings.MAX_DISTANCE)
        .sliderRange(RestockSettings.MIN_DISTANCE, 128)
        .build()
    );

    private final Setting<Boolean> useStashKeeper = sgMaterial.add(new BoolSetting.Builder()
        .name(RestockSetting.USE_STASH_KEEPER.id())
        .description(Texts.startupText(RestockSetting.USE_STASH_KEEPER.text()))
        .defaultValue(RestockSettings.DEFAULTS.useStashKeeper())
        .build()
    );

    private final Setting<Boolean> useCarriedShulkers = sgMaterial.add(new BoolSetting.Builder()
        .name(RestockSetting.USE_CARRIED_SHULKERS.id())
        .description(Texts.startupText(RestockSetting.USE_CARRIED_SHULKERS.text()))
        .defaultValue(RestockSettings.DEFAULTS.useCarriedShulkers())
        .build()
    );

    private final Setting<BaritoneSession.Mode> baritoneSettings = sgBaritone.add(new EnumSetting.Builder<BaritoneSession.Mode>()
        .name(RestockSetting.BARITONE_SETTINGS.id())
        .description(Texts.startupText(RestockSetting.BARITONE_SETTINGS.text()))
        .defaultValue(RestockSettings.DEFAULTS.baritoneSettings())
        .build()
    );

    private final Setting<String> baritonePrefix = sgBaritone.add(new StringSetting.Builder()
        .name(RestockSetting.BARITONE_PREFIX.id())
        .description(Texts.startupText(RestockSetting.BARITONE_PREFIX.text()))
        .defaultValue(RestockSettings.DEFAULTS.baritonePrefix())
        .build()
    );

    private final Setting<Boolean> stopNearPlayers = sgSafety.add(new BoolSetting.Builder()
        .name(RestockSetting.STOP_NEAR_PLAYERS.id())
        .description(Texts.startupText(RestockSetting.STOP_NEAR_PLAYERS.text()))
        .defaultValue(RestockSettings.DEFAULTS.stopNearPlayers())
        .build()
    );

    private final Setting<Integer> playerDistance = sgSafety.add(new IntSetting.Builder()
        .name(RestockSetting.PLAYER_DISTANCE.id())
        .description(Texts.startupText(RestockSetting.PLAYER_DISTANCE.text()))
        .defaultValue(RestockSettings.DEFAULTS.playerDistance())
        .range(RestockSettings.MIN_DISTANCE, RestockSettings.MAX_DISTANCE)
        .sliderRange(RestockSettings.MIN_DISTANCE, 128)
        .build()
    );

    private final Setting<Double> minHealth = sgSafety.add(new DoubleSetting.Builder()
        .name(RestockSetting.MIN_HEALTH.id())
        .description(Texts.startupText(RestockSetting.MIN_HEALTH.text()))
        .defaultValue(RestockSettings.DEFAULTS.minHealth())
        .range(RestockSettings.MIN_HEALTH, RestockSettings.MAX_HEALTH)
        .sliderRange(RestockSettings.MIN_HEALTH, 20)
        .decimalPlaces(1)
        .build()
    );

    /** Filled on the Netty thread, read at the next tick (surround++'s idiom). */
    private final Queue<BlockPos> changed = new ConcurrentLinkedQueue<>();
    private final Queue<EntityDamageS2CPacket> damage = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean setback = new AtomicBoolean();

    private volatile RestockSession session;
    /** One for the module's life: each one's Baritone link is an Orbit listener, and Orbit never evicts its cache. */
    private BaritoneMover baritoneMover;
    private TargetSource benchSource;
    private Mover benchMover;
    private PrintSwitch benchPrinter;
    private boolean resumedByJoin;
    private boolean netCaughtWarned;
    private boolean pauseSaid;
    private int trips;
    private RestockReason lastReason;
    private RestockReason pausedFor;
    /** What the last stop left out at the build and the borrowed boxes carried then (phase B; the bench reads it). */
    private ShulkersLeft lastLeft = ShulkersLeft.NONE;
    /** Owner ruling R42: the guard's stop an unpack last finished its break and pick-up for, and its detail (M7). */
    private RestockReason lastDrained;
    private String lastDrainedDetail = "";
    /**
     * Ruling R65: the guards' own stop when an at-once reason in the same tick halted the unpack and named the stop
     * instead, and its detail; said as one more line after acting.
     */
    private RestockReason alsoStopping;
    private String alsoStoppingDetail = "";

    public Restock() {
        super(XploitsAddon.CATEGORY, "restock", Texts.startupText(RestockText.MODULE_DESC));
    }

    // --- what the bench and the command read -------------------------------------------------------------------

    /** The bench's built-in schematic, scripted walker and fake print mode (the seams); null, null, null for the real ones. */
    public void useForBench(TargetSource source, Mover mover, PrintSwitch printer) {
        this.benchSource = source;
        this.benchMover = mover;
        this.benchPrinter = printer;
    }

    /** A session is open: Baritone's settings are restock's (auto-travel and nether-sweep refuse meanwhile). */
    public boolean isRunning() {
        return session != null;
    }

    /** The last refusal or stop; empty while none happened since the module was turned on. */
    public Optional<RestockReason> lastReason() {
        return Optional.ofNullable(lastReason);
    }

    public Optional<RestockReason> pausedFor() {
        return Optional.ofNullable(pausedFor);
    }

    public Optional<RestockTrip.Phase> tripPhase() {
        RestockSession s = session;
        return s == null ? Optional.empty() : s.tripPhase();
    }

    /** Trips back at the build since the module was turned on. */
    public int tripsDone() {
        return trips;
    }

    public Optional<BuildIndex.Counts> counts() {
        RestockSession s = session;
        return s == null ? Optional.empty() : s.counts();
    }

    /** The materials said to be nowhere within reach in this session (item ids). */
    public Set<String> nowhere() {
        RestockSession s = session;
        return s == null ? Set.of() : s.nowhere();
    }

    /** How many containers this session found unusable: not reached, not opened, or no longer a container. */
    public int unusable() {
        RestockSession s = session;
        return s == null ? 0 : s.unusableCount();
    }

    public Optional<UnpackPlan.Phase> unpackPhase() {
        RestockSession s = session;
        return s == null ? Optional.empty() : s.unpackPhase();
    }

    public boolean unpackDigging() {
        RestockSession s = session;
        return s != null && s.unpackDigging();
    }

    /** What the last stop left out at the build and how many borrowed boxes were carried (the bench reads it). */
    public ShulkersLeft lastShulkersLeft() {
        return lastLeft;
    }

    /** The guard's stop the last unpack finished its break and pick-up for (owner ruling R42; the bench reads it). */
    public Optional<RestockReason> lastDrained() {
        return Optional.ofNullable(lastDrained);
    }

    /** Borrowed shulker boxes the player still carries (this session; the bench reads it). */
    public int borrowedCount() {
        RestockSession s = session;
        return s == null ? 0 : s.borrowedCarried();
    }

    /** The session lets an unpack finish for this stop (and its detail, for the reason's text). */
    void drained(RestockReason why, String detail) {
        lastDrained = why;
        lastDrainedDetail = detail;
    }

    /**
     * The session halted an unpack for an at-once reason that came in the same tick as this stop of the guards (ruling
     * R65): the stop is named after the at-once reason, and this one is said too, after acting.
     */
    void alsoStopping(RestockReason why, String detail) {
        alsoStopping = why;
        alsoStoppingDetail = detail;
    }

    public Msg status() {
        RestockSession s = session;
        return s == null ? Msg.of(RestockText.STATUS_OFF) : s.status();
    }

    @Override
    public String activity() {
        RestockSession s = session;
        return s == null ? "" : Texts.render(s.activity(pausedFor != null));
    }

    void countTrip() {
        trips++;
    }

    // --- lifecycle ---------------------------------------------------------------------------------------------

    @Override
    public void onActivate() {
        lastReason = null;
        pausedFor = null;
        pauseSaid = false;
        netCaughtWarned = false;
        trips = 0;
        lastLeft = ShulkersLeft.NONE;
        lastDrained = null;
        lastDrainedDetail = "";
        alsoStopping = null;
        alsoStoppingDetail = "";
        clearQueues();
        // Meteor turns every module still marked active back on during a world join (also after a crash): that is not the
        // player turning restock on, and it must not start a session. Said and undone at the first tick.
        if (JoinWatch.joining()) {
            resumedByJoin = true;
            return;
        }
        resumedByJoin = false;
        try {
            Optional<RestockReason.Refusal> refusal = start();
            if (refusal.isPresent()) {
                lastReason = refusal.get().reason();
                error(Msg.of(RestockText.REFUSED, "reason", reasonText(refusal.get().reason(), refusal.get().detail())));
                if (isActive()) toggle();
            }
        } catch (RuntimeException | LinkageError e) {
            failed(e);
        }
    }

    private Optional<RestockReason.Refusal> start() {
        if (mc.world == null || mc.player == null) return refusal(RestockReason.NO_WORLD);
        TargetSource source = openSource();
        boolean bench = benchMover != null;
        boolean baritone = !bench && FabricLoader.getInstance().isModLoaded(BARITONE_MOD_ID);
        PrintSwitch printer = benchPrinter != null ? benchPrinter : LitematicaPrinterSwitch.find();
        Optional<Guards.Refusal> guarded = Guards.refuse(new Guards.EnableInputs(true, mc.player.isAlive(),
            mc.getCameraEntity() == mc.player, mc.player.hasVehicle(), RotationQueue.readable(),
            source.refusal().orElse(null), travelling(), sweeping(), RestockSession.active(RestockLimits.CONFLICTING_MODULES),
            RestockSession.autoPvpEngaged(), RestockSession.nonFriendWithin(mc, playerDistance.get()), stopNearPlayers.get(),
            mc.player.getHealth() + mc.player.getAbsorptionAmount(), minHealth.get(), baritone,
            SafetyNet.prefixRejection(baritonePrefix.get()) == null));
        if (guarded.isPresent()) return Optional.of(RestockReason.of(guarded.get()));
        if (!bench && !baritone) return refusal(RestockReason.NO_BARITONE);
        if (printer.installed() && printer.printing() == null) return refusal(RestockReason.LITEMATICA_PRINTER_UNREADABLE);
        if (!bench && baritoneMover == null) baritoneMover = new BaritoneMover(this::warnNetCaught);
        Mover mover = bench ? benchMover : baritoneMover;
        Optional<RestockReason.Refusal> moverRefusal = mover.begin(baritonePrefix.get(), baritoneSettings.get());
        if (moverRefusal.isPresent()) return moverRefusal;
        RestockSession s;
        try {
            s = new RestockSession(this, mc, this::openSource, source, mover, printer);
        } catch (RuntimeException | LinkageError e) {
            // No session holds Baritone's values yet, so no session's close can give them back: given back here.
            String prefix = mover.prefix();
            if (!mover.end()) warning(RestockText.RESTORE_NOT_DELIVERED, "prefix", prefix);
            throw e;
        }
        session = s;
        announce(s, baritone, printer);
        return Optional.empty();
    }

    /** The bench's source, or Litematica's selected placement, or a refusal without Litematica. */
    private TargetSource openSource() {
        if (benchSource != null) return benchSource;
        if (!LitematicaAccess.installed()) {
            return TargetSource.refusing(new Guards.Refusal(Guards.Reason.NO_LITEMATICA, ""));
        }
        return LitematicaAccess.open(PrinterLimits.DEFAULTS.maxVolume());
    }

    private void announce(RestockSession s, boolean baritone, PrintSwitch printer) {
        info(RestockText.ENABLED, "distance", maxDistance.get());
        RestockSettings now = settingsNow();
        int marks = s.markCount();
        int stash = s.stashCount(now);
        if (marks + stash == 0) warning(RestockText.ENABLED_NO_SOURCES);
        else info(RestockText.ENABLED_SOURCES, "marks", marks, "stash", stash);
        StashKeeper keeper = Modules.get().get(StashKeeper.class);
        if (useStashKeeper.get() && (keeper == null || !keeper.isActive())) info(RestockText.ENABLED_STASH_OFF);
        if (baritone) info(RestockText.ENABLED_BARITONE, "mode", baritoneSettings.get().name());
        info(printer.installed() ? RestockText.ENABLED_PRINTER : RestockText.ENABLED_NO_PRINTER);
        if (stopNearPlayers.get()) info(RestockText.ENABLED_PLAYERS, "distance", playerDistance.get());
        else warning(RestockText.ENABLED_PLAYERS_OFF);
    }

    @Override
    public void onDeactivate() {
        resumedByJoin = false;
        try {
            say(finish(RestockReason.MODULE_OFF));
        } catch (RuntimeException | LinkageError e) {
            logFault(e);
        }
    }

    /**
     * Ends the session, if one is open, and acts before anything is said (ruling R33): the walk, the unpack, the
     * printer marker and Baritone's values are dealt with first, each on its own ({@link RestockSession#close});
     * what is left to say comes back, for the caller to say afterwards — with every stop, and turning restock off,
     * what is left out at the build and the borrowed boxes carried.
     */
    private List<Msg> finish(RestockReason why) {
        RestockSession s = session;
        if (s == null) return List.of();
        session = null;
        pausedFor = null;
        clearQueues();
        String prefix = s.prefix();
        RestockSession.Closed closed = s.close(why);
        lastLeft = closed.left();
        List<Msg> say = new ArrayList<>(6);
        if (closed.printerLeftPaused()) say.add(Msg.of(RestockText.PRINTER_LEFT_PAUSED));
        if (!closed.restored()) say.add(Msg.of(RestockText.RESTORE_NOT_DELIVERED, "prefix", prefix));
        say.addAll(RestockMessages.shulkersLeft(closed.left()));
        return say;
    }

    private void say(List<Msg> warnings) {
        for (Msg m : warnings) warning(m);
    }

    /** Before Meteor's own teardown (HIGHEST): the session ends with the world and its player still there (auto-travel's idiom). */
    @EventHandler(priority = EventPriority.HIGHEST)
    private void onGameLeft(GameLeftEvent event) {
        if (session == null) return;
        lastReason = RestockReason.LEFT;
        try {
            say(finish(RestockReason.LEFT));
        } catch (RuntimeException | LinkageError e) {
            logFault(e);
        }
    }

    // --- events ------------------------------------------------------------------------------------------------

    /** Netty thread: queue only. */
    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (session == null) return;
        if (event.packet instanceof BlockUpdateS2CPacket p) changed.add(p.getPos().toImmutable());
        else if (event.packet instanceof ChunkDeltaUpdateS2CPacket p) p.visitUpdates((pos, state) -> changed.add(pos.toImmutable()));
        else if (event.packet instanceof PlayerPositionLookS2CPacket) setback.set(true);
        else if (event.packet instanceof EntityDamageS2CPacket p) damage.add(p);
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onTick(TickEvent.Pre event) {
        try {
            tickSession();
        } catch (RuntimeException | LinkageError e) {
            failed(e);
        }
    }

    private void tickSession() {
        if (resumedByJoin) {
            resumedByJoin = false;
            info(RestockText.NOT_RESUMED);
            if (isActive()) toggle();
            return;
        }
        RestockSession s = session;
        if (s == null) return;
        RestockSession.Outcome outcome = s.tick(drain(), settingsNow());
        if (outcome instanceof RestockSession.Stopped stop) {
            stop(stop.reason(), stop.detail());
        } else if (outcome instanceof RestockSession.Paused pause) {
            if (pausedFor != pause.reason()) {
                pausedFor = pause.reason();
                pauseSaid = s.inTrip();
                if (pauseSaid) warning(Msg.of(RestockText.PAUSED, "reason", reasonText(pause.reason(), pause.detail())));
            }
        } else if (pausedFor != null) {
            // Owner ruling R42: an unpack finishing its break and pick-up toward a guard's stop is not restock carrying
            // on — the stop follows — so nothing is said then.
            if (pauseSaid && !s.draining()) info(RestockText.RESUMED);
            pausedFor = null;
            pauseSaid = false;
        }
    }

    /** Before {@code Rotations}' own listener of the same event (MEDIUM). */
    @EventHandler(priority = EventPriority.HIGH)
    private void onSendMovement(SendMovementPacketsEvent.Pre event) {
        RestockSession s = session;
        if (s == null) return;
        try {
            s.requestRotation();
        } catch (RuntimeException | LinkageError e) {
            failed(e);
        }
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    /**
     * Ruling R33: it acts before it speaks. The session ends first (the walk cancelled, the marker, Baritone's values,
     * each on its own), then the stop is said, then the module turns off, even if saying it failed.
     */
    private void stop(RestockReason why, String detail) {
        lastReason = why;
        String prefix = prefixNow();
        List<Msg> after = finish(why);
        try {
            // M7 (owner ruling R42): an unpack that ended otherwise while it finished for a guard's stop — its own
            // failure, or an at-once stop that came meanwhile — says that stop too, so a stranger near is never hidden.
            RestockReason drained = lastDrained;
            if (drained != null && drained != why) {
                warning(Msg.of(RestockText.UNPACK_DRAINED, "reason", reasonText(drained, lastDrainedDetail, prefix)));
            }
            Msg text = Msg.of(RestockText.STOPPED, "reason", reasonText(why, detail, prefix));
            warning(text);
            toast(text);
            // Ruling R65: an at-once reason that came in the tick of a stop that finishes first halted the unpack and
            // names the stop; the guards' own stop (a stranger near) is said too, so neither is hidden.
            RestockReason also = alsoStopping;
            if (also != null) {
                warning(Msg.of(RestockText.STOPPED_ALSO, "reason", reasonText(also, alsoStoppingDetail, prefix)));
            }
            say(after);
        } finally {
            if (isActive()) toggle();
        }
    }

    /**
     * Rulings R29 and R33: a fault in restock (an exception, or a {@code LinkageError} from a Meteor or Litematica build
     * that changed under it) stops it with a message instead of reaching the game, whose crash report would list every
     * loaded player's name and position. The session ends through the normal stop path with {@code INTERNAL} (Baritone
     * given back, the marker deleted, the printer said to stay paused). Only the class is logged and named: an
     * exception's message could carry a position. If that stop fails too, what it may have left undone is done here,
     * each step on its own, and nothing more is said.
     */
    private void failed(Throwable e) {
        logFault(e);
        try {
            stop(RestockReason.INTERNAL, e.getClass().getName());
        } catch (RuntimeException | LinkageError again) {
            logFault(again);
            lastReason = RestockReason.INTERNAL;
            pausedFor = null;
            RestockSession s = session;
            session = null;
            if (s != null) quietly(() -> s.close(RestockReason.INTERNAL));
            Mover m = benchMover != null ? benchMover : baritoneMover;
            if (m != null) {
                quietly(m::cancel);
                quietly(m::end);
            }
            quietly(() -> {
                if (isActive()) toggle();
            });
        }
    }

    private static void quietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError e) {
            logFault(e);
        }
    }

    private static void logFault(Throwable e) {
        XploitsAddon.LOG.error("restock: an unexpected {} stopped it", e.getClass().getName());
    }

    Msg reasonText(RestockReason why, String detail) {
        return reasonText(why, detail, prefixNow());
    }

    private Msg reasonText(RestockReason why, String detail, String prefix) {
        return RestockMessages.reason(why, detail, new RestockMessages.Facts(playerDistance.get(), minHealth.get(),
            prefix, PrinterLimits.DEFAULTS.maxVolume(), LitematicaAccess.BUILT_AGAINST, LIMITS.walkStallTicks() / 20));
    }

    /** The prefix the session speaks with, or the setting's when there is none. */
    private String prefixNow() {
        RestockSession s = session;
        return s == null || s.prefix().isEmpty() ? baritonePrefix.get() : s.prefix();
    }

    private void warnNetCaught(String text) {
        if (netCaughtWarned) return;
        netCaughtWarned = true;
        // Only the verb: the command may be a #goto with coordinates, and restock never prints one.
        Msg message = Msg.of(RestockText.NET_CAUGHT, "verb", SafetyNet.verb(text));
        warning(message);
        toast(message);
    }

    /** MeteorToast plays its default sound; never pass a null one (auto-travel's note: an NPE on the render thread). */
    private void toast(Msg message) {
        mc.getToastManager().add(new MeteorToast.Builder("Xploits").text(Texts.render(message)).icon(Items.CHEST).build());
    }

    private RestockSettings settingsNow() {
        return new RestockSettings(maxDistance.get(), useStashKeeper.get(), baritoneSettings.get(), baritonePrefix.get(),
            stopNearPlayers.get(), playerDistance.get(), minHealth.get(), useCarriedShulkers.get());
    }

    private RestockSession.Received drain() {
        List<BlockPos> c = new ArrayList<>();
        for (BlockPos p; (p = changed.poll()) != null; ) c.add(p);
        List<EntityDamageS2CPacket> d = new ArrayList<>();
        for (EntityDamageS2CPacket x; (x = damage.poll()) != null; ) d.add(x);
        return new RestockSession.Received(c, d, setback.getAndSet(false));
    }

    private void clearQueues() {
        changed.clear();
        damage.clear();
        setback.set(false);
    }

    private static Optional<RestockReason.Refusal> refusal(RestockReason reason) {
        return Optional.of(new RestockReason.Refusal(reason, ""));
    }

    private static boolean travelling() {
        AutoTravel travel = Modules.get().get(AutoTravel.class);
        return travel != null && travel.isTravelling();
    }

    private static boolean sweeping() {
        NetherSweep sweep = Modules.get().get(NetherSweep.class);
        return sweep != null && sweep.isSweeping();
    }
}
