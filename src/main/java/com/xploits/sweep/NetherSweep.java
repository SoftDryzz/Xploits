package com.xploits.sweep;

import com.xploits.XploitsAddon;
import com.xploits.console.core.GameSnapshot;
import com.xploits.elytra.ElytraReplace;
import com.xploits.shared.Texts;
import com.xploits.shared.XploitsModule;
import com.xploits.shared.core.i18n.Msg;
import com.xploits.shared.core.PositionedMsg;
import com.xploits.sweep.core.ChunkPos;
import com.xploits.sweep.core.Coverage;
import com.xploits.sweep.core.FuelBudget;
import com.xploits.sweep.core.Odometer;
import com.xploits.sweep.core.SweepArea;
import com.xploits.sweep.core.SweepPlanner;
import com.xploits.sweep.core.SweepRoute;
import com.xploits.sweep.core.SweepTally;
import com.xploits.sweep.core.SweepText;
import com.xploits.sweep.core.WidthProbe;
import com.xploits.travel.AutoTravel;
import com.xploits.travel.core.BaritoneScript;
import com.xploits.travel.core.BorrowedModule;
import com.xploits.travel.core.FireworkWatch;
import com.xploits.travel.core.RoutePlanner;
import com.xploits.travel.core.SafetyNet;
import com.xploits.travel.core.StallWatch;
import com.xploits.travel.core.Waypoint;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ChatCommandSignedC2SPacket;
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.World;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Adapter for the Nether sweep (Nether Sweep spec §2, §5.1, §6 and §8): it turns the rectangle the
 * player asks for into lanes, reads the coverage that {@code NewerNewChunks} has been piling up for
 * months so that it flies <b>only what is missing</b>, and steers Baritone's elytra flight through
 * chat commands.
 *
 * <p>All the geometry and all the bookkeeping live in {@code sweep.core}, which is tested without
 * starting the game. What is left here is only what needs Minecraft: reading the position and the
 * inventory, reading the other mod's files, sending the commands, watching the progress, turning
 * modules on and off, and arming the safety net.
 *
 * <p><b>This module flies; it detects nothing</b> (spec §2, which is normative). Bases are found by
 * {@code BaseFinder}, containers by {@code stash-finder}, and coverage is recorded by
 * {@code NewerNewChunks}; all three are already installed and better than anything that could be
 * reimplemented here. The one thing none of them does is get the terrain to pass in front of the
 * client, and that is exactly what this module does. That is why {@link #start()} checks that all
 * three are on <b>before</b> taking off: without them the sweep covers terrain and records nothing,
 * which is flying for three hours for nothing.
 *
 * <p><b>Turning the module on does not fly.</b> The player launches the sweep with {@code .xploits
 * sweep go}, just as in {@code auto-travel}: a module that takes off by itself when enabled sends you
 * hours away from home with one click. While it is on and not flying it does a single useful thing,
 * and an important one: it feeds {@link WidthProbe} with the chunks it receives, so that at launch
 * the lane width comes out <b>measured</b> and not made up (spec §5).
 *
 * <p><b>The safety net lives in {@link ChatNet}, subscribed to the bus on its own</b> and not as
 * part of the module. Meteor unsubscribes the module just <i>before</i> {@code onDeactivate()},
 * which is when the restoration sends its burst of prefixed commands: a net built on an
 * {@code @EventHandler} of the module itself would be dead right then, and the six lines would go
 * out to the public chat of an anarchy server. It is the same fix as {@code travel/AutoTravel}, and
 * the full reasoning is in its javadoc.
 */
public class NetherSweep extends XploitsModule {
    /** The id Baritone registers with in the mod loader. */
    private static final String BARITONE_MOD_ID = "baritone";

    /** The name {@code NewerNewChunks} registers with as a Meteor module. */
    private static final String MODULE_NEWER_NEW_CHUNKS = "NewerNewChunks";

    /** The name {@code BaseFinder} registers with as a Meteor module. */
    private static final String MODULE_BASE_FINDER = "BaseFinder";

    /** The name Meteor's container finder registers with. */
    private static final String MODULE_STASH_FINDER = "stash-finder";

    /**
     * The five files {@code NewerNewChunks} keeps per server and dimension (spec §5.1). Their union is
     * exactly "which chunks have I received", and it is the coverage record this module consumes
     * instead of keeping its own.
     *
     * <p>Names read from the <b>bytecode of the installed jar</b>, {@code trouser-streak-1.6.1}, class
     * {@code pwn.noobs.trouserstreak.modules.NewerNewChunks}, and confirmed against the files that
     * already exist on disk in this instance.
     */
    private static final String[] COVERAGE_FILES = {
        "NewChunkData.txt",
        "OldChunkData.txt",
        "OldGenerationChunkData.txt",
        "BeingUpdatedChunkData.txt",
        "BlockExploitChunkData.txt"
    };

    /**
     * Everything {@code NewerNewChunks} puts into a folder name goes through this replacement, so we
     * have to apply <b>exactly the same one</b> or we would be looking in a folder that does not exist.
     *
     * <p>It is an allow list, not a deny list, and that is why Meteor's
     * {@code Utils.getFileWorldName()} is <b>not</b> used here: Meteor's is {@code [\s\\/:*?"<>|]}, a
     * different deny list, and for any address with a character that one cleans and the other does
     * not -a {@code ~} or a {@code !}, for example- the two names diverge and the previous coverage
     * would be read as empty. Empty means replanning the whole rectangle: hours of flight repeating
     * terrain already seen.
     */
    private static final String INVALID_CHARACTERS = "[^a-zA-Z0-9._\\-]";

    /**
     * Every how many blocks flown a sample is handed to {@link FuelBudget}, and why it is not every
     * tick.
     *
     * <p>{@link FuelBudget#sample} only counts as measured spending the legs in which the fireworks
     * really went down. Sampling every tick, a firework burns in one tick and the other hundred ticks
     * of the interval do not count: the rate would come from dividing <b>one tick of flight</b> -a
     * couple of blocks- by one firework, that is about 2 blocks per firework instead of the real
     * hundred-odd. With that rate {@link FuelBudget#willRunOut} would cut the sweep after a few
     * seconds, every time.
     *
     * <p>So the interval has to be long enough for some firework to burn in almost every one. A
     * thousand blocks are about thirty seconds of elytra cruising, during which Baritone burns on the
     * order of ten fireworks: enough for the leg to measure real spending, and short enough for the
     * first projection to arrive within a minute of flight, which is what the spec promises (§6: "the
     * projection will arrive within a few minutes of flight").
     */
    private static final double BLOCKS_PER_FIREWORK_SAMPLE = 1_000;

    /**
     * The floor of {@code no-projection-grace}, and why it cannot be one sampling interval.
     *
     * <p>A spending rate needs <b>two</b> samples: the first only sets the reference the second is
     * measured from (see {@link FuelBudget#sample}). So at the first sample
     * {@link #blocksWithoutProjection} is exactly one interval and at the second, two, if by then no
     * firework has gone down yet -which is normal at the start of a flight with
     * {@code elytraConserveFireworks} on-.
     *
     * <p>With the grace at a single interval, the comparison at the first sample was
     * {@code 1000 < 1000}, false, and <b>the sweep was cut during the approach</b>: without ever
     * getting to give the half-grace warning, deterministically, and with a value the slider itself
     * offered. The floor has to let through the two samples the rate needs and a few more, so it is
     * three intervals: the second one warns -2,000 is already past half the grace- and only the third
     * one cuts.
     *
     * <p>This does not leave the module broken for anyone who had the old 1,000 saved: Meteor's
     * {@code Setting.set} returns {@code false} without writing anything when the value does not pass
     * {@code isValueValid}, and {@code DoubleSetting.load} loads through it, so a persisted value below
     * the floor is dropped on load and the setting stays at its factory value.
     */
    private static final double MIN_NO_PROJECTION_GRACE = 3 * BLOCKS_PER_FIREWORK_SAMPLE;

    /** The side of a chunk, in blocks: the link between two lanes comes from this. */
    private static final int BLOCKS_PER_CHUNK = 16;

    /** Without getting closer to the waypoint for this long, the sweep is cut. */
    private static final double STALL_SECONDS = 45;

    /** How much the distance has to drop to count as progress, in blocks. */
    private static final double PROGRESS_EPSILON = 1.0;

    private final SettingGroup sgArea = settings.getDefaultGroup();
    private final SettingGroup sgLane = settings.createGroup("Lane");
    private final SettingGroup sgFireworks = settings.createGroup("Fireworks");
    private final SettingGroup sgFlight = settings.createGroup("Flight");
    private final SettingGroup sgNotify = settings.createGroup("Notify");

    // The rectangle, in Nether chunks (spec §4: the player gives the rectangle).

    private final Setting<Integer> chunkX1 = sgArea.add(new IntSetting.Builder()
        .name("chunk-x-1")
        .description(Texts.startupText(SweepText.SETTING_CHUNK_X_1))
        .defaultValue(0)
        .sliderRange(-10_000, 10_000)
        .build()
    );

    private final Setting<Integer> chunkZ1 = sgArea.add(new IntSetting.Builder()
        .name("chunk-z-1")
        .description(Texts.startupText(SweepText.SETTING_CHUNK_Z_1))
        .defaultValue(0)
        .sliderRange(-10_000, 10_000)
        .build()
    );

    private final Setting<Integer> chunkX2 = sgArea.add(new IntSetting.Builder()
        .name("chunk-x-2")
        .description(Texts.startupText(SweepText.SETTING_CHUNK_X_2))
        .defaultValue(0)
        .sliderRange(-10_000, 10_000)
        .build()
    );

    private final Setting<Integer> chunkZ2 = sgArea.add(new IntSetting.Builder()
        .name("chunk-z-2")
        .description(Texts.startupText(SweepText.SETTING_CHUNK_Z_2))
        .defaultValue(0)
        .sliderRange(-10_000, 10_000)
        .build()
    );

    // The lane width (spec §5). The ids "lane-width" and "lane-width-margin" are the ones
    // SweepPlanner's rejection reasons name, on purpose and with a test that pins them there: if they
    // diverged, the message would send the player to look in the ClickGUI for something that does
    // not exist under that name. They move together or not at all.

    private final Setting<Integer> laneWidth = sgLane.add(new IntSetting.Builder()
        .name("lane-width")
        .description(Texts.startupText(SweepText.SETTING_LANE_WIDTH))
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 64)
        .build()
    );

    private final Setting<Double> laneWidthMargin = sgLane.add(new DoubleSetting.Builder()
        .name("lane-width-margin")
        .description(Texts.startupText(SweepText.SETTING_LANE_WIDTH_MARGIN))
        .defaultValue(0.2)
        .range(0, 0.9)
        .sliderRange(0, 0.9)
        .decimalPlaces(2)
        .build()
    );

    private final Setting<Double> waypointMargin = sgLane.add(new DoubleSetting.Builder()
        .name("waypoint-margin")
        .description(Texts.startupText(SweepText.SETTING_WAYPOINT_MARGIN))
        .defaultValue(RoutePlanner.DEFAULT_WAYPOINT_MARGIN)
        .min(RoutePlanner.MIN_WAYPOINT_MARGIN)
        .sliderRange(RoutePlanner.MIN_WAYPOINT_MARGIN, 500)
        .decimalPlaces(0)
        .build()
    );

    // Fireworks (spec §6): the real limit of a sweep is not time.

    private final Setting<Double> fireworkReserve = sgFireworks.add(new DoubleSetting.Builder()
        .name("firework-reserve")
        .description(Texts.startupText(SweepText.SETTING_FIREWORK_RESERVE))
        .defaultValue(0.2)
        .min(0)
        .sliderRange(0, 1)
        .decimalPlaces(2)
        .build()
    );

    private final Setting<Boolean> countReturnTrip = sgFireworks.add(new BoolSetting.Builder()
        .name("count-return-trip")
        .description(Texts.startupText(SweepText.SETTING_COUNT_RETURN_TRIP))
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> blocksPerFirework = sgFireworks.add(new DoubleSetting.Builder()
        .name("blocks-per-firework")
        .description(Texts.startupText(SweepText.SETTING_BLOCKS_PER_FIREWORK))
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 400)
        .decimalPlaces(1)
        .build()
    );

    private final Setting<Double> noProjectionGrace = sgFireworks.add(new DoubleSetting.Builder()
        .name("no-projection-grace")
        .description(Texts.startupText(SweepText.SETTING_NO_PROJECTION_GRACE))
        .defaultValue(5_000)
        .min(MIN_NO_PROJECTION_GRACE)
        .sliderRange(MIN_NO_PROJECTION_GRACE, 50_000)
        .decimalPlaces(0)
        .build()
    );

    // Flight: the prefix and the four Baritone settings, each with its flight value and its resting
    // value. The resting values default to Baritone's REAL default because Baritone PERSISTS its
    // settings to disk: a made-up resting value would reconfigure forever every #elytra the player
    // runs by hand. The full reasoning, with the source of each number, is in
    // BaritoneScript.baritoneDefaults().

    private final Setting<String> baritonePrefix = sgFlight.add(new StringSetting.Builder()
        .name("baritone-prefix")
        .description(Texts.startupText(SweepText.SETTING_BARITONE_PREFIX))
        .defaultValue("#")
        .build()
    );

    private final Setting<Boolean> autoJump = sgFlight.add(new BoolSetting.Builder()
        .name("auto-jump")
        .description(Texts.startupText(SweepText.SETTING_AUTO_JUMP))
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoJumpResting = sgFlight.add(new BoolSetting.Builder()
        .name("auto-jump-resting")
        .description(Texts.startupText(SweepText.SETTING_AUTO_JUMP_RESTING))
        .defaultValue(BaritoneScript.baritoneDefaults().autoJump())
        .build()
    );

    private final Setting<Boolean> emergencyLand = sgFlight.add(new BoolSetting.Builder()
        .name("emergency-land")
        .description(Texts.startupText(SweepText.SETTING_EMERGENCY_LAND))
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> emergencyLandResting = sgFlight.add(new BoolSetting.Builder()
        .name("emergency-land-resting")
        .description(Texts.startupText(SweepText.SETTING_EMERGENCY_LAND_RESTING))
        .defaultValue(BaritoneScript.baritoneDefaults().allowEmergencyLand())
        .build()
    );

    private final Setting<Boolean> conserveFireworks = sgFlight.add(new BoolSetting.Builder()
        .name("conserve-fireworks")
        .description(Texts.startupText(SweepText.SETTING_CONSERVE_FIREWORKS))
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> conserveFireworksResting = sgFlight.add(new BoolSetting.Builder()
        .name("conserve-fireworks-resting")
        .description(Texts.startupText(SweepText.SETTING_CONSERVE_FIREWORKS_RESTING))
        .defaultValue(BaritoneScript.baritoneDefaults().conserveFireworks())
        .build()
    );

    private final Setting<Double> fireworkSpeed = sgFlight.add(new DoubleSetting.Builder()
        .name("firework-speed")
        .description(Texts.startupText(SweepText.SETTING_FIREWORK_SPEED))
        .defaultValue(1)
        .min(0)
        .sliderRange(0.5, 3)
        .decimalPlaces(2)
        .build()
    );

    private final Setting<Double> fireworkSpeedResting = sgFlight.add(new DoubleSetting.Builder()
        .name("firework-speed-resting")
        .description(Texts.startupText(SweepText.SETTING_FIREWORK_SPEED_RESTING))
        .defaultValue(BaritoneScript.baritoneDefaults().fireworkSpeed())
        .min(0)
        .sliderRange(0.5, 3)
        .decimalPlaces(2)
        .build()
    );

    private final Setting<String> netherSeed = sgFlight.add(new StringSetting.Builder()
        .name("nether-seed")
        .description(Texts.startupText(SweepText.SETTING_NETHER_SEED))
        .defaultValue("")
        .build()
    );

    // Notify

    private final Setting<Boolean> notify = sgNotify.add(new BoolSetting.Builder()
        .name("notify")
        .description(Texts.startupText(SweepText.SETTING_NOTIFY))
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> coverageFloor = sgNotify.add(new DoubleSetting.Builder()
        .name("coverage-floor")
        .description(Texts.startupText(SweepText.SETTING_COVERAGE_FLOOR))
        .defaultValue(0.95)
        .range(0, 1)
        .sliderRange(0, 1)
        .decimalPlaces(2)
        .build()
    );

    private final Setting<Boolean> notifySound = sgNotify.add(new BoolSetting.Builder()
        .name("notify-sound")
        .description(Texts.startupText(SweepText.SETTING_NOTIFY_SOUND))
        .defaultValue(true)
        .build()
    );

    /** Whether a sweep is running: the only thing that tells "on" apart from "steering". */
    private boolean sweeping;

    /**
     * Whether what is happening right now is the teardown on leaving the world, during which a
     * Meteor module cannot be turned on or off without leaving it subscribed twice to the bus for the
     * rest of the session. Set by {@link #onGameLeft(GameLeftEvent)} and cleared by
     * {@link #onActivate()}.
     */
    private boolean leavingWorld;

    /**
     * The safety net's listener, <b>subscribed to the bus on its own</b> and not as part of the
     * module, because Meteor unsubscribes the module just before {@code onDeactivate()} -which is when
     * the restoration sends its commands-. The full reasoning, with the orbit sources checked, is in
     * {@code travel/AutoTravel}.
     */
    private final ChatNet net = new ChatNet();

    /** Whether the net is armed, which is the same as whether {@link #net} is subscribed to the bus. */
    private boolean netArmed;

    /** Whether the command {@link #send(String)} is sending right now is ours. */
    private boolean emitting;

    /** Whether the net killed our last command: set by the listener, read by {@link #send(String)}. */
    private boolean sendCaught;

    /** Whether the player was already warned that the net had to cancel something in this sweep (once only). */
    private boolean netCaughtWarned;

    /**
     * The prefix this sweep started with. The net and the restoration use this one, not the
     * setting's: if the player edits it mid-flight, the restoration has to speak to Baritone in the
     * same language it was spoken to at takeoff.
     */
    private String activePrefix = "";

    /**
     * The two Meteor modules the sweep borrows. The decision about what to do with them at takeoff
     * and at the end lives in {@link BorrowedModule}, in the core and with tests.
     */
    private final BorrowedModule elytraFly = new BorrowedModule("elytra-fly", false);
    private final BorrowedModule elytraReplace = new BorrowedModule("elytra-replace", true);

    /**
     * The trip being flown: the vertices in order and every distance that has to be budgeted,
     * approach and return included. It is {@code null} while no sweep is running, and everything
     * that reads it is behind {@link #sweeping}.
     */
    private SweepRoute route;

    /** Which vertex of {@link #route} the sweep is at. */
    private int index;

    /** How many lanes the plan being flown has, for the status and the notices. */
    private int planLanes;

    /** The lane width the plan was made with, in chunks. */
    private int usedWidth;

    /** Whether the player typed that width instead of it being measured from the chunk stream. */
    private boolean typedWidth;

    /**
     * The width at which the server sends chunks, measured from the stream itself (spec §5). It is
     * fed <b>with the module on even when not flying</b>: that way there are already samples at
     * launch and the lane width comes out measured instead of typed. It is thrown away on entering or
     * leaving a world, because the next server's reach need not be this one's.
     */
    private WidthProbe probe = new WidthProbe();

    /**
     * The ceiling the last sample was taken with, in chunks, or 0 if none has been taken yet. It is
     * used to notice that the effective render distance has changed and to throw away a measurement
     * that can no longer be compared with the new one.
     */
    private int lastCeiling;

    /** The measurement of the firework spending of <b>this</b> flight (spec §6). */
    private FuelBudget fuel = new FuelBudget();

    /**
     * The player's odometer, <b>for the whole session and not for one sweep</b>: it is fed every tick
     * with the module on, flying or not, because two things that are needed in both states come from
     * it. One is the speed of the last tick, which is what decides whether a width sample is valid
     * (see {@link WidthProbe#sample}); the other is the blocks flown, which are measured as a
     * difference against {@link #blocksAtTakeoff} instead of by resetting the counter, so that the
     * step of the takeoff tick does not come out falsely as zero just when the probe looks at it.
     */
    private final Odometer odometer = new Odometer();

    /** At which odometer reading this sweep started. */
    private double blocksAtTakeoff;

    /** Where the player was on the previous tick, to measure the real movement. */
    private Waypoint previousPosition = new Waypoint(0, 0);

    /**
     * Whether {@link #previousPosition} really is the one from the last tick. It is false on entering
     * the world and while there is no player: without a previous position there is no movement to
     * measure, and assuming one would feed the odometer a jump nobody flew.
     */
    private boolean hasPreviousPosition;

    /** At which odometer reading the last firework sample was taken. */
    private double blocksAtLastSample;

    /** Blocks flown without {@link FuelBudget#blocksPerRocket()} being able to give a rate. */
    private double blocksWithoutProjection;

    /** Whether the player was already warned once that there is no firework projection, so it is not repeated every sample. */
    private boolean noProjectionWarningGiven;

    /**
     * The count of how many chunks of the area have really arrived (spec §9). It is set up at
     * planning time -with the previous coverage already marked- and fed with every chunk that
     * arrives while flying, so that the final message can say <b>how much was looked at</b> and not
     * only that the route is finished. It is {@code null} while no sweep is running.
     */
    private SweepTally tally;

    /**
     * The stall watch, in the core and with tests. The adapter only gives it the waypoint it is
     * heading for and the distance left.
     */
    private final StallWatch stallWatch = StallWatch.ofSeconds(STALL_SECONDS, PROGRESS_EPSILON);

    /**
     * The low-fireworks warning. The threshold is not a setting here: in a sweep the real protection
     * is {@link FuelBudget}'s projection, and this warning is only the reminder that they are running
     * out. It is armed at takeoff with 10% of the starting fireworks, a number relative to what the
     * player decided to carry instead of a fixed one that does not mean the same with 64 fireworks as
     * with 1,500.
     */
    private FireworkWatch fireworkWatch = new FireworkWatch(0);

    public NetherSweep() {
        super(XploitsAddon.CATEGORY, "nether-sweep", Texts.startupText(SweepText.MODULE_DESC));
    }

    @Override
    public void onActivate() {
        // Turning it on does not fly: it waits for the player's command. This is also called again on
        // entering the world -Modules.onGameJoined subscribes every active module and calls its
        // onActivate()-, so it is the place where we forget that we were leaving and where the
        // previous server's width measurement is thrown away.
        leavingWorld = false;
        probe = new WidthProbe();
        lastCeiling = 0;
        resetSweep();

        info(SweepText.ARMED);
        info(SweepText.MEASURING);
    }

    @Override
    public void onDeactivate() {
        // Leaving the world also goes through here, but by then onGameLeft has already closed the
        // sweep: it runs first by priority and finish() is idempotent.
        finish(SweepText.REASON_MODULE_OFF, false);
        // And whatever happens, the net does not outlive the module: a listener subscribed with no
        // sweep running would silently swallow every prefixed command the player typed by hand.
        disarmNet();
    }

    /**
     * Leaving: disconnection or world change. It runs at {@code HIGHEST} on purpose, to run before
     * the {@code Modules} handler that tears down the active modules: that way the restoration knows
     * it is in the teardown and does not turn any Meteor module on or off -doing so there leaves them
     * subscribed twice to the bus for the rest of the session-.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    private void onGameLeft(GameLeftEvent event) {
        leavingWorld = true;
        finish(SweepText.REASON_LEFT_WORLD, false);
        probe = new WidthProbe();
        lastCeiling = 0;
    }

    /**
     * The measurement of the real width at which the server sends chunks (spec §5). <b>One event
     * arrives per chunk the server sends</b>, published from {@code ClientPlayNetworkHandlerMixin}
     * with {@code @At("TAIL")} of {@code onChunkData}, which runs on the main thread behind
     * {@code forceMainThread}: it is the honest measurement of coverage, and the module's state can
     * be touched from here without races.
     *
     * <p>It is fed whenever the module is on, flying or not, because the width has to be measured
     * <b>before</b> launching. The Chebyshev distance, the maximum over the mean and the discarding
     * of artifacts are decided by {@link WidthProbe}, in the core and with tests; here only the
     * position, the chunk's position and the ceiling are read.
     *
     * <p><b>The ceiling is the only thing that stops a late chunk from widening the lanes for the
     * rest of the session</b> (see the javadoc of {@link WidthProbe}). It comes from
     * {@code getClampedViewDistance()}, whose bytecode literally says {@code serverViewDistance > 0 ?
     * min(viewDistance, serverViewDistance) : viewDistance}: it is exactly "the smaller of the two"
     * of spec §3 -what the player has set and what the server has declared-, which is the limit above
     * which a sample cannot come from the server.
     */
    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (mc.player == null || event.chunk() == null) return;

        int ceiling = mc.options.getClampedViewDistance();
        // A ceiling below 1 is not a real game state -the minimum render distance is 2-, but if it
        // ever were, sampling with it would throw an exception for every chunk received. Here it
        // stays quiet and does not measure, which is the safe side: without samples the module
        // refuses to take off instead of flying with a made-up width.
        if (ceiling < 1) return;

        if (ceiling != lastCeiling) {
            // The render distance has changed -the player touched it, or the server declared another
            // one-. The old samples were taken against a ceiling that no longer holds, and if the
            // ceiling went DOWN the stored maximum may be above what the server sends now: exactly
            // the gap the ceiling exists to close, coming in through the other door. The measurement
            // is thrown away and measured again.
            if (lastCeiling > 0 && probe.sampleCount() > 0) {
                info(SweepText.RENDER_DISTANCE_CHANGED, "from", lastCeiling, "to", ceiling);
            }
            lastCeiling = ceiling;
            probe = new WidthProbe();
        }

        int playerX = (int) Math.floor(mc.player.getX()) >> 4;
        int playerZ = (int) Math.floor(mc.player.getZ()) >> 4;
        int chunkX = event.chunk().getPos().x;
        int chunkZ = event.chunk().getPos().z;

        // This is the check that the terrain has really arrived (spec §9), and it comes from here and
        // not from re-reading the other mod's files because this is where the fact is: one event per
        // chunk the server sends, without depending on NewerNewChunks being on or on it having
        // flushed to disk.
        if (sweeping && tally != null) tally.record(chunkX, chunkZ);

        // The speed of the last tick decides whether this sample measures the server's reach or the
        // drift of its queue: the full reason is in WidthProbe's javadoc. And while there is no
        // previous position to compare with there is no measured speed, only a startup zero:
        // sampling with it is declaring still a player who may be flying in -the case of turning the
        // module on mid-flight-, and that sample would come in inflated. Without a measurement
        // nothing is measured; the next tick will have one.
        if (!hasPreviousPosition) return;
        probe.sample(
            new ChunkPos(playerX, playerZ),
            new ChunkPos(chunkX, chunkZ),
            ceiling,
            odometer.lastStep());
    }

    /**
     * The half of the safety net that needs Minecraft: getting from the outgoing packet which
     * channel it goes through and what text it carries. Deciding whether that text is one of the
     * Baritone commands we steer with belongs to {@link SafetyNet}, which is tested without starting
     * the game.
     */
    private final class ChatNet {
        @EventHandler
        private void onPacketSend(PacketEvent.Send event) {
            if (!netArmed) return;

            SafetyNet.Channel channel;
            String payload;
            if (event.packet instanceof ChatMessageC2SPacket chat) {
                channel = SafetyNet.Channel.CHAT;
                payload = chat.chatMessage();
            }
            else if (event.packet instanceof CommandExecutionC2SPacket command) {
                channel = SafetyNet.Channel.COMMAND;
                payload = command.command();
            }
            else if (event.packet instanceof ChatCommandSignedC2SPacket command) {
                channel = SafetyNet.Channel.COMMAND;
                payload = command.command();
            }
            else return;

            if (!SafetyNet.directs(activePrefix, channel, payload)) return;

            event.cancel();
            if (emitting) sendCaught = true;
            warnNetCaught(SafetyNet.typedText(channel, payload));
        }
    }

    /**
     * The net having had to act means Baritone is not intercepting, and the player wants to know
     * that: any command they type by hand would indeed be published. Once per sweep, and loud.
     */
    private void warnNetCaught(String text) {
        if (netCaughtWarned) return;
        netCaughtWarned = true;

        Msg message = Msg.of(SweepText.NET_CAUGHT, "command", text);
        // The cancelled command may be a #goal with coordinates: only its verb goes to the console.
        Msg withoutArguments = Msg.of(SweepText.NET_CAUGHT_VERB, "verb", SafetyNet.verb(text));
        warningPrivate(new PositionedMsg(message, withoutArguments));
        loudToast(message, Items.BARRIER);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // Whatever was left to hand back on leaving the world is handed back here, on the first tick
        // after entering again: by then Modules.onGameJoined has finished subscribing everyone.
        applyPendingModules();

        // The odometer always runs, flying or not: with the module on and no sweep, its only useful
        // job is measuring the lane width, and to know whether a sample is valid we need to know how
        // fast the player was going when it arrived.
        measureMovement();

        if (!sweeping) return;

        if (mc.player == null || mc.world == null) {
            finish(SweepText.REASON_LOST_WORLD, true);
            return;
        }
        if (!mc.player.isAlive()) {
            // Meteor has no death event, so it is observed here; the player still exists on the
            // death screen, so the restoration can still talk to Baritone.
            finish(SweepText.REASON_DIED, true);
            return;
        }

        Waypoint here = new Waypoint(mc.player.getX(), mc.player.getZ());

        checkFireworks();
        if (sampleFireworks()) return;

        double distance = here.distanceTo(route.waypoints().get(index));

        // The margin at which a waypoint counts as reached is not the same for all of them -the last
        // one is the only place where Baritone should land-, and that decision lives in the core.
        if (distance <= RoutePlanner.reachedMargin(index, route.size(), waypointMargin.get())) {
            index++;
            if (index >= route.size()) {
                finish(SweepText.REASON_DONE, false);
                return;
            }
            if (notify.get() && index % 2 == 0) {
                info(SweepText.LANE_PROGRESS, "lane", index / 2 + 1, "total", planLanes);
            }
            aimAtCurrentWaypoint();
            return;
        }

        // The only thing observable from outside is whether the distance goes down; Baritone reports
        // nothing else. The index goes into the call on purpose: it is what keeps the jump in
        // distance when switching waypoints from being read as forty-five seconds without progress.
        if (stallWatch.tick(index, distance)) {
            Msg message = Msg.of(SweepText.STALLED, "index", index + 1, "seconds", stallWatch.limitSeconds(),
                "distance", Math.round(distance));
            warning(message);
            loudToast(message, Items.ELYTRA);
            finish(SweepText.REASON_STALL, false);
        }
    }

    /**
     * Measures how far the player has moved in this tick and passes it to the odometer, which decides
     * whether that was flight or a jump.
     *
     * <p><b>A teleport is not flight</b> -a portal, a {@code /tpa}, respawning, a server
     * rubber-band- and adding it to the blocks flown inflates the blocks-per-firework rate towards
     * the dangerous side: the projection answers that the fireworks will last when they will not.
     * The filter and its reasoning live in {@link Odometer}, in the core and with tests.
     *
     * <p>Without a previous position -on entering the world, or after a tick with no player- there
     * is no movement to measure, so zero is recorded: making one up would be feeding the odometer
     * exactly the jump it exists not to count.
     */
    private void measureMovement() {
        if (mc.player == null) {
            hasPreviousPosition = false;
            odometer.advance(0);
            return;
        }

        Waypoint here = new Waypoint(mc.player.getX(), mc.player.getZ());
        odometer.advance(hasPreviousPosition ? here.distanceTo(previousPosition) : 0);
        previousPosition = here;
        hasPreviousPosition = true;
    }

    /**
     * The blocks flown since this sweep's takeoff, which is what is passed to {@link FuelBudget}. It
     * comes as a difference against the session odometer -see {@link #odometer}- and already comes
     * without the legs that were not flown.
     */
    private double sweepBlocksFlown() {
        return odometer.blocksFlown() - blocksAtTakeoff;
    }

    /**
     * The low-fireworks reminder. The real protection is {@link #sampleFireworks()}; this only warns
     * that they are running out, and {@link FireworkWatch} decides when and whether it already went
     * out.
     *
     * <p>{@code InvUtils.find(Item...)} walks the player's inventory and adds up the {@code count} of
     * the matching stacks: it covers the hotbar, the main inventory, the armor and the offhand, and
     * returns {@code count 0} with no player instead of blowing up. Whatever is inside a shulker is
     * not counted.
     */
    private void checkFireworks() {
        int fireworks = InvUtils.find(Items.FIREWORK_ROCKET).count();
        if (!fireworkWatch.observe(fireworks)) return;

        Msg message = fireworks == 0
            ? Msg.of(SweepText.OUT_OF_FIREWORKS)
            : Msg.of(SweepText.LOW_FIREWORKS, "count", fireworks, "threshold", fireworkWatch.threshold());
        warning(message);
        loudToast(message, Items.FIREWORK_ROCKET);
    }

    /**
     * The measurement of firework spending and the projection of whether they will last (spec §6,
     * second half: <i>"During: the real spending per block flown is measured and projected. If the
     * projection falls short, it cuts and says so before leaving you stranded"</i>).
     *
     * <p><b>The branch that matters is the "I don't know" one, and it cannot be a silence.</b>
     * {@link FuelBudget#blocksPerRocket()} is empty in two normal situations: at the start, until two
     * consecutive samples measure real spending, and mid-flight, if the player restocks fireworks
     * more often than it samples and the measured rate expires. In both,
     * {@link FuelBudget#willRunOut} <b>throws</b> on purpose, because making up a measurement that
     * does not exist is worse than admitting it. What this method does <b>not</b> do is catch that
     * exception and keep flying: that would silently make the firework protection disappear, which
     * is exactly the kind of failure the module exists not to make. So {@code willRunOut} is neither
     * called without a rate, nor is flying continued indefinitely without one: it warns, and past the
     * grace it cuts.
     *
     * @return whether the sweep has been cut and the caller has to stop touching its state
     */
    private boolean sampleFireworks() {
        double flown = sweepBlocksFlown();
        double sinceLast = flown - blocksAtLastSample;
        if (sinceLast < BLOCKS_PER_FIREWORK_SAMPLE) return false;
        blocksAtLastSample = flown;

        int fireworks = InvUtils.find(Items.FIREWORK_ROCKET).count();
        fuel.sample(flown, fireworks);

        OptionalDouble rate = fuel.blocksPerRocket();
        if (rate.isEmpty()) {
            blocksWithoutProjection += sinceLast;
            if (blocksWithoutProjection < noProjectionGrace.get()) {
                if (!noProjectionWarningGiven && blocksWithoutProjection >= noProjectionGrace.get() / 2) {
                    noProjectionWarningGiven = true;
                    warning(SweepText.NO_PROJECTION_WARNING, "flown", Math.round(blocksWithoutProjection),
                        "left", Math.round(noProjectionGrace.get() - blocksWithoutProjection));
                }
                return false;
            }

            Msg message = Msg.of(SweepText.NO_PROJECTION_CUT, "flown", Math.round(blocksWithoutProjection),
                "grace", Math.round(noProjectionGrace.get()));
            warning(message);
            loudToast(message, Items.FIREWORK_ROCKET);
            finish(SweepText.REASON_NO_PROJECTION, false);
            return true;
        }

        blocksWithoutProjection = 0;
        noProjectionWarningGiven = false;

        double remaining = remainingBlocks();
        if (!fuel.willRunOut(remaining, fireworks, fireworkReserve.get())) return false;

        long needed = (long) Math.ceil(remaining / rate.getAsDouble() * (1 + fireworkReserve.get()));
        // It is the only cut message that arrives with the player far from home, so it names the two
        // settings its numbers come from: without them, "about 420 fireworks" is a figure nobody
        // knows the source of, and the player has nothing to change for next time.
        Msg message = Msg.of(SweepText.FIREWORKS_SHORT, "rate", Math.round(rate.getAsDouble()),
            "remaining", Math.round(remaining),
            "return", countReturnTrip.get()
                ? SweepText.FIREWORKS_SHORT_WITH_RETURN
                : SweepText.FIREWORKS_SHORT_WITHOUT_RETURN,
            "reserve", Math.round(fireworkReserve.get() * 100), "needed", needed, "count", fireworks,
            "counts", countReturnTrip.get() ? SweepText.RETURN_COUNTED : SweepText.RETURN_NOT_COUNTED);
        warning(message);
        loudToast(message, Items.FIREWORK_ROCKET);
        finish(SweepText.REASON_OUT_OF_FIREWORKS, false);
        return true;
    }

    /**
     * What the player still has to fly to finish: from where they are to the vertex they are heading
     * for, plus the rest of the route, plus the return if it counts. It is exactly the distance
     * {@link FuelBudget#willRunOut} asks for, and not {@code SweepPlan.totalBlocks()}, which measures
     * only the sweep.
     */
    private double remainingBlocks() {
        if (mc.player == null) return route.remainingFrom(index);
        return route.remainingFrom(index, new Waypoint(mc.player.getX(), mc.player.getZ()));
    }

    /**
     * Launches the sweep. Returns the message the command has to show, whether it is the launch one
     * or the reason why it does not fly; when in doubt, not a single command is sent.
     *
     * <p>The §8 warnings -detectors off, typed width- go out through the chat <b>before</b> takeoff
     * and not in the return value, because there are several and each one deserves its own line.
     */
    public Msg start() {
        if (!isActive()) return Msg.of(SweepText.START_MODULE_OFF);
        if (sweeping) return Msg.of(SweepText.START_ALREADY_SWEEPING);
        Msg travelRunning = autoTravelRejection();
        if (travelRunning != null) return travelRunning;
        if (mc.player == null || mc.world == null) return Msg.of(SweepText.START_NO_WORLD);
        if (!mc.player.isAlive()) return Msg.of(SweepText.START_DEAD);
        if (!World.NETHER.equals(mc.world.getRegistryKey())) {
            // The whole module is built on one Nether block covering 64 times more Overworld surface:
            // the area is typed in Nether chunks and what is announced as equivalent coverage comes
            // from multiplying by 8. Flying it in another dimension does not break the geometry, but
            // it turns that announcement into a lie, and it is the number by which the player decides
            // whether the sweep is worth the hours it costs.
            return Msg.of(SweepText.START_NOT_NETHER);
        }
        if (!FabricLoader.getInstance().isModLoaded(BARITONE_MOD_ID)) {
            // BaritoneUtils.IS_AVAILABLE is NOT used on purpose: Meteor sets it to true after a
            // Class.forName on a class the obfuscated jar does not expose, so there it is false
            // even though Baritone is perfectly installed.
            return Msg.of(SweepText.START_NO_BARITONE);
        }
        if (InvUtils.find(Items.FIREWORK_ROCKET).count() == 0) return Msg.of(SweepText.START_NO_FIREWORKS);
        if (!wearsElytra()) return Msg.of(SweepText.START_NO_ELYTRA);
        Msg chestSwapRejection = chestSwapRejection();
        if (chestSwapRejection != null) return chestSwapRejection;

        String launchPrefix = baritonePrefix.get();
        Msg prefixRejection = SafetyNet.prefixRejection(launchPrefix);
        if (prefixRejection != null) {
            // Before arming the net and before the first command: arming it on an unusable prefix is
            // having a net without knowing what it watches.
            return Msg.of(SweepText.NOT_SWEEPING_PREFIX, "reason", prefixRejection);
        }

        SweepArea area = SweepArea.ofChunks(chunkX1.get(), chunkZ1.get(), chunkX2.get(), chunkZ2.get());

        // The size is checked here, BEFORE reading the coverage and before planning, because both
        // walk the whole rectangle chunk by chunk on the main thread -Coverage.seenIn to count what
        // was already seen and SweepPlanner to decide which bands to skip-. With an area typed too
        // big, the client hangs inside a command and not even the rejection arrives.
        Msg sizeRejection = area.oversizeRejection();
        if (sizeRejection != null) return Msg.of(SweepText.NOT_SWEEPING, "reason", sizeRejection);

        Msg widthRejection = resolveWidth();
        if (widthRejection != null) return widthRejection;

        CoverageRead reading = readCoverage();
        Coverage seen = reading.coverage();
        // Of the area, not of the whole dimension: what tells the player how much their previous
        // coverage saves them is what falls INSIDE the rectangle they asked for. With size() the
        // message went as far as announcing more chunks seen than the area has.
        //
        // And the tally is set up here, not at the end, because it has to start knowing which chunks
        // of the area did NOT need to be seen again: those of the bands the planner skips. The
        // "how much have I looked at" of the final message comes from it (spec §9).
        SweepTally newTally = SweepTally.of(area, seen);

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, seen, usedWidth);
        if (plan.isRejected()) return Msg.of(SweepText.NOT_SWEEPING, "reason", plan.rejection());
        if (plan.lanes().isEmpty()) {
            return Msg.of(SweepText.NOTHING_TO_SWEEP, "chunks", area.chunkCount(), "reading", reading.summary(),
                "equivalent", area.overworldEquivalent());
        }

        Waypoint here = new Waypoint(mc.player.getX(), mc.player.getZ());
        // Every distance of the trip -approach included- is computed by SweepRoute, in the core and
        // with tests: none is redone by hand here, which is how a "total" that did not include the
        // approach slipped in twice and ended up deciding whether there were enough fireworks.
        SweepRoute plannedRoute = SweepRoute.of(plan.lanes(), here, countReturnTrip.get());

        Msg spacingRejection = spacingRejection(plannedRoute);
        if (spacingRejection != null) return spacingRejection;

        warnAboutDetectors();
        warnAboutShortLinks(plannedRoute);
        if (reading.unknownServer()) {
            // It is not "nothing recorded": it is "I did not even look". The plan comes out the same
            // as if starting from scratch, so without saying so the player flies three hours
            // repeating terrain they have been piling up for months, without noticing that their
            // previous coverage did not make it into the count.
            Msg message = Msg.of(SweepText.UNKNOWN_SERVER);
            warning(message);
            loudToast(message, Items.BARRIER);
        }
        if (typedWidth) {
            Msg message = Msg.of(SweepText.TYPED_WIDTH, "width", usedWidth);
            warning(message);
            loudToast(message, Items.BARRIER);
        }

        activePrefix = launchPrefix;
        route = plannedRoute;
        tally = newTally;
        previousPosition = here;
        hasPreviousPosition = true;
        index = 0;
        planLanes = plan.lanes().size();
        blocksAtTakeoff = odometer.blocksFlown();
        blocksAtLastSample = 0;
        blocksWithoutProjection = 0;
        noProjectionWarningGiven = false;
        fuel = new FuelBudget();
        stallWatch.reset();
        fireworkWatch = new FireworkWatch(warningThreshold());
        sweeping = true;

        // Order matters: the net BEFORE sending the first command.
        armNet();
        prepare();

        // And one last check before the #elytra, because preparing moves armor: turning off
        // elytra-fly with chest-swap on Always puts the chestplate where the elytra was. That is
        // rejected above, so here it should no longer be able to happen; this is the net in case
        // another module takes the elytra away between one line and the next.
        if (!wearsElytra()) return undoLaunch();

        aimAtCurrentWaypoint();

        // And the probe is thrown away here, already flying. A sweep cannot plan with the maximum
        // left over from the previous one: the width has to come from samples taken with the player
        // standing still -those from a flight are discarded, see WidthProbe-, and dragging the old
        // measurement along is planning on a reach the server had three hours ago. To relaunch it is
        // measured again, which is a few seconds of walking.
        probe = new WidthProbe();

        return Msg.of(SweepText.LAUNCHED, "lanes", plan.lanes().size(), "width", usedWidth,
            "how", typedWidth ? SweepText.WIDTH_TYPED : SweepText.WIDTH_MEASURED, "chunks", area.chunkCount(),
            "seen", newTally.alreadySeen(), "reading", reading.summary(), "approach", Math.round(plannedRoute.approachBlocks()),
            "sweep", Math.round(plannedRoute.sweepBlocks()),
            "return", countReturnTrip.get()
                ? Msg.of(SweepText.LAUNCHED_RETURN, "blocks", Math.round(plannedRoute.returnBlocks()))
                : Msg.of(SweepText.NOTHING),
            "total", Math.round(plannedRoute.totalBlocks()), "equivalent", area.overworldEquivalent(),
            "estimate", fireworkEstimate(plannedRoute.totalBlocks()));
    }

    /**
     * Resolves which lane width the plan is made with and leaves it in {@link #usedWidth}, or returns
     * the reason why it cannot sweep yet.
     *
     * <p>The measurement rules (spec §5 and §9: <i>"no number that can be measured is assumed"</i>).
     * If the player typed it, it is respected but loudly warned about at launch; if they did not type
     * it and the probe has no samples yet, <b>it does not fly</b>: making up the spacing is exactly
     * the way to end up with unlooked-at strips while believing the area is clean.
     */
    private Msg resolveWidth() {
        if (laneWidth.get() > 0) {
            usedWidth = laneWidth.get();
            typedWidth = true;
            return null;
        }
        if (!probe.hasEnoughSamples()) {
            return Msg.of(SweepText.NOT_ENOUGH_SAMPLES, "samples", probe.sampleCount(),
                "needed", WidthProbe.MIN_SAMPLES, "discarded", discardedSamplesNote());
        }
        usedWidth = probe.laneWidthInChunks(laneWidthMargin.get());
        typedWidth = false;
        return null;
    }

    /**
     * The reason why the route cannot be flown with the configured waypoint margin, or {@code null}
     * if it can.
     *
     * <p><b>What is checked is the shortest lane, not the shortest gap</b>, and that is half the fix.
     * Two vertices that fit inside the margin are consumed almost back to back: the adapter drops the
     * first and on the next tick drops the second, so Baritone never gets to fly towards the one in
     * between. If those two vertices are <b>the ends of a lane</b>, that lane is never flown and the
     * sweep counts it as combed anyway: the lie of spec §9. If they are the end of one lane and the
     * start of the next, what is lost is the corner and not the terrain -the target becomes the end
     * of the next lane and Baritone crosses the band diagonally-, so that is warned about in
     * {@link #warnAboutShortLinks(SweepRoute)} and not rejected. The full reason is in
     * {@link SweepRoute#shortestLane()} and {@link SweepRoute#shortestLink()}.
     *
     * <p><b>What not telling them apart cost:</b> the shortest gap of a sweep is almost always a
     * link, and against the floor of the evasion routes -{@code RoutePlanner.minimumSpacing}, twice
     * the margin and never less than 300 blocks- it took a width of 19 chunks, that is an observed
     * radius of 12. A server that sent 8, 10 or 11 -normal on a busy anarchy- saw <b>every measured
     * sweep rejected, always and for any rectangle</b>, and none of the three ways out the message
     * offered worked: below 150 the margin did not move the floor, enlarging the area does not
     * separate the bands, and raising the width by hand does not apply to whoever has it measured.
     * Now the only thing rejected is what really loses terrain, and that only happens in an area that
     * is tiny along its long axis, where "enlarge the area" is indeed a way out.
     */
    private Msg spacingRejection(SweepRoute plannedRoute) {
        double minimum = SweepRoute.minimumGap(waypointMargin.get());
        double lane = plannedRoute.shortestLane();
        if (lane > minimum) return null;

        return Msg.of(SweepText.LANE_TOO_SHORT, "lane", Math.round(lane),
            "margin", Math.round(waypointMargin.get()), "minimum", Math.round(minimum),
            "chunks", (long) Math.ceil(minimum / BLOCKS_PER_CHUNK),
            "fix", lane > RoutePlanner.MIN_WAYPOINT_MARGIN
                ? Msg.of(SweepText.LANE_TOO_SHORT_LOWER_MARGIN, "lane", Math.round(lane),
                    "min", Math.round(RoutePlanner.MIN_WAYPOINT_MARGIN))
                : Msg.of(SweepText.LANE_TOO_SHORT_MARGIN_NOT_ENOUGH, "min", Math.round(RoutePlanner.MIN_WAYPOINT_MARGIN)));
    }

    /**
     * The warnings about the links between lanes: what is lost when the bands end up close together.
     * <b>They warn, they do not reject</b>, and the difference is the usual criterion: neither case
     * loses a lane, and the one thing this module cannot do is count as combed what it did not look
     * at.
     *
     * <ul>
     *   <li><b>The link fits inside the margin.</b> The adapter consumes it without flying it, so
     *       Baritone never receives the corner: its target becomes the end of the next lane and it
     *       flies there diagonally, crossing the band anyway. The clean corner is lost, not the
     *       terrain -reasoned in {@link SweepRoute#shortestLink()}-, but the player has to know
     *       because the edges of that band pass further from the client than planned.</li>
     *   <li><b>The link is shorter than what an elytra flies as a leg</b>
     *       ({@link RoutePlanner#MIN_WAYPOINT_SPACING}, about four turning radii). Baritone overshoots
     *       and comes back for the vertex: slower and more fireworks, but the lane is flown in
     *       full.</li>
     * </ul>
     *
     * <p>A single-lane route has no link at all, and then {@code shortestLink()} is
     * {@code Double.MAX_VALUE}: it falls into neither warning, which is correct.
     */
    private void warnAboutShortLinks(SweepRoute plannedRoute) {
        double link = plannedRoute.shortestLink();
        if (link <= SweepRoute.minimumGap(waypointMargin.get())) {
            warning(SweepText.LINK_INSIDE_MARGIN, "link", Math.round(link),
                "margin", Math.round(waypointMargin.get()));
            return;
        }
        if (link >= RoutePlanner.MIN_WAYPOINT_SPACING) return;

        warning(SweepText.LINK_TOO_SHORT, "link", Math.round(link),
            "spacing", Math.round(RoutePlanner.MIN_WAYPOINT_SPACING));
    }

    /**
     * The reason why it cannot sweep with {@code auto-travel} flying, or {@code null} if it is not.
     *
     * <p><b>Both modules steer the same Baritone through the same commands</b>, and neither asked
     * about the other even though they are used on the same trip -you fly to the area with
     * {@code auto-travel} and sweep on arrival-. What happens if they overlap, in order: {@code #goal}
     * only takes one target, so the second to launch keeps Baritone; the first sees the distance to
     * its own target grow, after 45 s its stall watch fires and sends its {@code #cancel} and its
     * whole restoration, which <b>stops the second one's flight halfway</b> and also sets
     * {@code elytraFireworkSpeed} back to a resting value different from the one it believes it is
     * using; the second notices nothing and 45 s later diagnoses a stall that does not exist. And
     * since each one borrows {@code elytra-fly} and {@code elytra-replace} with its own
     * {@code BorrowedModule}, the second records as "the player's resting state" the state the first
     * one left, and leaves it there when it finishes.
     *
     * <p>It is symmetric: the same guard is in {@code AutoTravel.start()} looking this way.
     */
    private Msg autoTravelRejection() {
        AutoTravel travel = Modules.get().get(AutoTravel.class);
        if (travel == null || !travel.isTravelling()) return null;

        return Msg.of(SweepText.AUTO_TRAVEL_RUNNING);
    }

    /**
     * The firework estimate before takeoff (spec §6). It comes from the spending measured in earlier
     * sweeps, which the module keeps between sessions in its own setting; <b>without any previous
     * sweep it says there is no data</b> instead of showing a made-up number that looks like a
     * measurement.
     *
     * <p>The distance passed in is the whole trip -approach, sweep and return-, not
     * {@code SweepPlan.totalBlocks()}: that one measures from the start of the first lane to the end
     * of the last, and in a sweep far from home the approach is the longest leg of all.
     */
    private Msg fireworkEstimate(double totalBlocks) {
        int carried = InvUtils.find(Items.FIREWORK_ROCKET).count();
        double rate = blocksPerFirework.get();
        if (rate <= 0) {
            return Msg.of(SweepText.ESTIMATE_NO_DATA, "count", carried);
        }
        long needed = (long) Math.ceil(totalBlocks / rate * (1 + fireworkReserve.get()));
        if (needed <= carried) {
            return Msg.of(SweepText.ESTIMATE_ENOUGH, "needed", needed, "rate", Math.round(rate), "count", carried);
        }
        return Msg.of(SweepText.ESTIMATE_SHORT, "needed", needed, "rate", Math.round(rate), "count", carried,
            "missing", needed - carried);
    }

    /**
     * The threshold of the low-fireworks warning: 10% of those carried at takeoff, and never less
     * than one. Relative and not fixed because "you have 16 left" does not mean the same leaving with
     * 64 as leaving with 1,500.
     */
    private int warningThreshold() {
        return Math.max(1, InvUtils.find(Items.FIREWORK_ROCKET).count() / 10);
    }

    /**
     * The warnings of spec §5.1 and §8, the ones that have to be given <b>before</b> takeoff and not
     * after three hours: this module flies and detects nothing. With the detectors off the sweep
     * covers terrain and records nothing, and with {@code NewerNewChunks} off, on top of that, the
     * earlier coverage is not used and no trace is left for next time.
     *
     * <p>The three modules are looked up <b>by name</b> and not by class on purpose: two of them come
     * from another mod, {@code trouser-streak}, which is not a compile dependency of this addon.
     * Looking up by name means that if the player uninstalls it, this says "not installed" instead of
     * blowing up with a {@code NoClassDefFoundError} when the module loads.
     */
    private void warnAboutDetectors() {
        warnAboutDetector(MODULE_NEWER_NEW_CHUNKS, SweepText.DETECTOR_NEWER_NEW_CHUNKS);
        warnAboutDetector(MODULE_BASE_FINDER, SweepText.DETECTOR_BASE_FINDER);
        warnAboutDetector(MODULE_STASH_FINDER, SweepText.DETECTOR_STASH_FINDER);
    }

    private void warnAboutDetector(String name, SweepText consequence) {
        Module module = Modules.get().get(name);
        if (module != null && module.isActive()) return;

        Msg message = Msg.of(SweepText.DETECTOR_WARNING, "name", name,
            "state", module == null ? SweepText.DETECTOR_NOT_INSTALLED : SweepText.DETECTOR_OFF,
            "consequence", consequence);
        warning(message);
        loudToast(message, Items.BARRIER);
    }

    /** Whether the chest slot holds an elytra, which is the only thing Baritone can fly with. */
    private boolean wearsElytra() {
        return mc.player != null && mc.player.getEquippedStack(EquipmentSlot.CHEST).getItem() == Items.ELYTRA;
    }

    /**
     * The reason why it cannot launch with {@code elytra-fly}'s {@code chest-swap} as configured, or
     * {@code null} if there is no conflict. {@code ElytraFly.onDeactivate()} swaps the elytra for the
     * chestplate if {@code chest-swap} is on {@code Always}, and leaves a listener that makes that
     * swap on touching the ground if it is on {@code WaitForGround}: the preparation turns it off as
     * its first step and sends {@code #elytra} two lines later. It is rejected instead of worked
     * around because working around it would mean touching, behind the player's back, a setting that
     * Meteor persists to disk.
     */
    private Msg chestSwapRejection() {
        ElytraFly module = Modules.get().get(ElytraFly.class);
        if (module == null || !module.isActive()) return null;

        ElytraFly.ChestSwapMode mode = module.chestSwap.get();
        if (mode == ElytraFly.ChestSwapMode.Never) return null;

        Msg consequence = mode == ElytraFly.ChestSwapMode.Always
            ? Msg.of(SweepText.CHEST_SWAP_ALWAYS, "prefix", baritonePrefix.get())
            : Msg.of(SweepText.CHEST_SWAP_WAIT_FOR_GROUND);

        return Msg.of(SweepText.CHEST_SWAP_REJECTED, "mode", mode.toString(), "consequence", consequence);
    }

    /**
     * Undoes a preparation that can no longer end in flight and answers why. It does not call
     * {@link #finish(SweepText, boolean)} on purpose: there is no sweep here to declare finished
     * -neither a {@code goal} nor an {@code elytra} has been sent-.
     */
    private Msg undoLaunch() {
        sweeping = false;
        SafetyNet.Restoration undone = restore();
        Msg pending = undone.warning(activePrefix);
        if (pending == null) return Msg.of(SweepText.UNDONE);

        loudToast(Msg.of(SweepText.TOAST_UNDONE_NOT_RESTORED), Items.BARRIER);
        return Msg.of(SweepText.UNDONE_NOT_RESTORED, "pending", pending);
    }

    /** Cancellation by the player. Returns the message the command has to show. */
    public Msg stop() {
        if (!sweeping) return Msg.of(SweepText.STOP_NOT_SWEEPING);
        if (finish(SweepText.REASON_CANCELLED, false).arrived()) return Msg.of(SweepText.STOP_RESTORED);
        return Msg.of(SweepText.STOP_NOT_RESTORED);
    }

    public boolean isSweeping() {
        return sweeping;
    }

    /** How far the sweep has got: lane, total and blocks left, return included if it counts. */
    public Optional<GameSnapshot.Progress> progress() {
        if (!sweeping || route == null) return Optional.empty();
        return Optional.of(new GameSnapshot.Progress(Math.min(index / 2 + 1, planLanes), planLanes,
            Math.round(remainingBlocks())));
    }

    @Override
    public String activity() {
        return sweeping
            ? Texts.render(SweepText.NOW_LANE, "lane", Math.min(index / 2 + 1, planLanes), "total", planLanes)
            : Texts.render(SweepText.NOW_ARMED);
    }

    /**
     * Preparation: the two borrowed modules and, in one piece, the sequence of Baritone settings that
     * the core builds and tests as a single thing.
     */
    private void prepare() {
        // Before recording anything, hand back whatever was left pending from an earlier sweep:
        // otherwise what would be recorded as "the player's resting state" would be the state that
        // sweep left behind.
        applyPendingModules();
        takeModule(Modules.get().get(ElytraFly.class), elytraFly);
        takeModule(Modules.get().get(ElytraReplace.class), elytraReplace);
        for (String command : BaritoneScript.preparation(activePrefix, flightSettings())) send(command);
    }

    /** Sets the current vertex and relaunches the flight: Baritone takes the target at start, not afterwards. */
    private void aimAtCurrentWaypoint() {
        send(BaritoneScript.goTo(activePrefix, route.waypoints().get(index)));
        send(BaritoneScript.launch(activePrefix));
    }

    /**
     * The single end of every exit path. It is idempotent: whoever arrives second does nothing, which
     * is exactly what is needed when turning the module off and leaving the world overlap.
     *
     * <p><b>This is where it is checked that the terrain arrived</b>, and not taking it for granted
     * is the only thing that separates this module from the one lie it cannot tell (spec §9). Before,
     * the sweep walked the vertices and on reaching the last one announced "finished" without ever
     * having checked whether the rectangle's chunks had arrived: with the server delivering late, or
     * flying faster than it delivers, a fraction of every band never arrives, and the player reads
     * "Sweep finished", crosses the area off and does not come back. The self-correction of spec §7
     * -relaunching replans over the gaps- only works if the player knows they have to relaunch, and
     * the only one that can know is the module.
     *
     * <p>What is counted and how is in {@link SweepTally}; here it is only read before restoring
     * -{@link #restore()} resets the sweep state- and the tone is decided. <b>Below
     * {@code coverage-floor} the warning goes out loud and with a toast</b>, not in an {@code info}
     * that can moreover be turned off: a sweep that covered half cannot look like one that covered
     * everything, because both finish and only one has to be repeated.
     *
     * @return what really happened with the restoration, for whoever has to answer something later
     */
    private SafetyNet.Restoration finish(SweepText reason, boolean warn) {
        if (!sweeping) return SafetyNet.Restoration.DELIVERED;
        sweeping = false;

        // What was measured in this flight is kept for the next one's advance estimate (spec §6), and
        // only if there is a measurement: an expired or missing rate does NOT overwrite a good one
        // that was saved, which would be trading a fact for an "I don't know".
        fuel.blocksPerRocket().ifPresent(rate -> blocksPerFirework.set(rate));

        // Before restoring: restore() calls resetSweep() in a finally and the tally is thrown away there.
        Msg coverageSummary = tally == null ? null : tally.summary();
        boolean fellShort = tally != null && tally.shortOfCoverage(coverageFloor.get());
        int missing = tally == null ? 0 : tally.missing();

        SafetyNet.Restoration restoration = restore();
        Msg pending = restoration.warning(activePrefix);
        warnPendingModules();

        Msg coverage = coverageSummary == null
            ? Msg.of(SweepText.NOTHING)
            : Msg.of(SweepText.FINISHED_COVERAGE, "summary", coverageSummary);
        SweepText relaunch = fellShort ? SweepText.FINISHED_RELAUNCH : SweepText.NOTHING;
        // Sending is not arriving, and saying "environment restored" when nothing has arrived is the
        // module's most expensive lie: the player thinks they have landed and Baritone keeps flying.
        Msg message = pending == null
            ? Msg.of(SweepText.FINISHED, "reason", reason, "coverage", coverage, "relaunch", relaunch)
            : Msg.of(SweepText.FINISHED_NOT_RESTORED, "reason", reason, "pending", pending, "coverage", coverage,
                "relaunch", relaunch);

        if (pending != null) {
            warning(message);
            loudToast(Msg.of(SweepText.TOAST_FINISHED_NOT_RESTORED), Items.BARRIER);
            return restoration;
        }
        if (fellShort) {
            warning(message);
            loudToast(Msg.of(SweepText.TOAST_SHORT_OF_COVERAGE, "missing", missing), Items.BARRIER);
            return restoration;
        }
        if (warn) warning(message);
        else if (notify.get()) info(message);
        return restoration;
    }

    /**
     * Returns everything that was touched to its resting state and says whether it really arrived.
     * The net is disarmed last, when there is not a single command left to send: disarming it earlier
     * would leave the {@code cancel} and the restoration uncovered, which is exactly when the most
     * commands are sent at once. And it goes in a {@code finally} because an armed net that survived
     * an exception would silently swallow every command the player typed by hand.
     */
    private SafetyNet.Restoration restore() {
        try {
            SafetyNet.Restoration outcome;
            if (mc.player == null) {
                outcome = SafetyNet.Restoration.NO_PLAYER;
            }
            else {
                // The && goes after on purpose: first it sends, always, and then it accumulates. With
                // the condition first, the first cancelled command would take the rest down with it.
                boolean delivered = send(BaritoneScript.cancel(activePrefix));
                for (String command : BaritoneScript.restoration(activePrefix, restingSettings())) {
                    delivered = send(command) && delivered;
                }
                outcome = delivered ? SafetyNet.Restoration.DELIVERED : SafetyNet.Restoration.CANCELLED;
            }

            releaseModule(Modules.get().get(ElytraFly.class), elytraFly);
            releaseModule(Modules.get().get(ElytraReplace.class), elytraReplace);

            return outcome;
        }
        finally {
            disarmNet();
            resetSweep();
        }
    }

    /**
     * The warning about the modules that could not be handed back yet. It goes out loud and with a
     * toast because the only time this happens is on leaving the world, where the chat goes away with
     * the disconnection and the only thing the player gets to read is the toast.
     */
    private void warnPendingModules() {
        Object names;
        if (elytraFly.hasPending() && elytraReplace.hasPending()) {
            names = Msg.of(SweepText.MODULES_BOTH, "first", elytraFly.name(), "second", elytraReplace.name());
        }
        else if (elytraFly.hasPending()) names = elytraFly.name();
        else if (elytraReplace.hasPending()) names = elytraReplace.name();
        else return;

        Msg message = Msg.of(SweepText.MODULES_LEFT_AS_IN_FLIGHT, "modules", names);
        warning(message);
        loudToast(message, Items.ELYTRA);
    }

    private void resetSweep() {
        sweeping = false;
        route = null;
        tally = null;
        index = 0;
        planLanes = 0;
        blocksAtTakeoff = odometer.blocksFlown();
        blocksAtLastSample = 0;
        blocksWithoutProjection = 0;
        noProjectionWarningGiven = false;
        stallWatch.reset();
        fireworkWatch.reset();
    }

    /** Subscribes the net's listener to the bus. Idempotent: arming twice does not duplicate the subscription. */
    private void armNet() {
        netCaughtWarned = false;
        if (netArmed) return;
        netArmed = true;
        MeteorClient.EVENT_BUS.subscribe(net);
    }

    /** Unsubscribes the listener. Idempotent, which is what makes the {@code onDeactivate} call safe. */
    private void disarmNet() {
        if (!netArmed) return;
        netArmed = false;
        MeteorClient.EVENT_BUS.unsubscribe(net);
    }

    /** Records how the module is and leaves it in its flight state. */
    private static void takeModule(Module module, BorrowedModule loan) {
        if (module == null) {
            loan.forget();
            return;
        }
        apply(module, loan.take(module.isActive()));
    }

    /** Puts the module back where it was, or leaves the hand-back pending if it cannot be touched. */
    private void releaseModule(Module module, BorrowedModule loan) {
        if (module == null) {
            loan.forget();
            return;
        }
        apply(module, loan.release(module.isActive(), !leavingWorld));
    }

    /**
     * Does what was left pending from the teardown on leaving the world. It is idempotent and cheap:
     * with nothing pending it does not even query the module registry.
     */
    private void applyPendingModules() {
        if (elytraFly.hasPending()) applyPending(Modules.get().get(ElytraFly.class), elytraFly);
        if (elytraReplace.hasPending()) applyPending(Modules.get().get(ElytraReplace.class), elytraReplace);
    }

    private static void applyPending(Module module, BorrowedModule loan) {
        if (module == null) {
            loan.forget();
            return;
        }
        apply(module, loan.claimPending());
    }

    private static void apply(Module module, BorrowedModule.Action action) {
        switch (action) {
            case TURN_ON -> {
                if (!module.isActive()) module.enable();
            }
            case TURN_OFF -> {
                if (module.isActive()) module.disable();
            }
            case NONE -> { }
        }
    }

    /**
     * Sends a command through the player's chat. {@code ChatUtils.sendPlayerMsg} sends the text "as
     * if the user had typed it in the chat", which is exactly what Baritone listens to;
     * {@code addToHistory = false} is passed so as not to leave hash-prefixed commands one Enter away
     * from being published in the chat history.
     *
     * @return whether the command left the client towards Baritone. {@code false} means that the net
     *         had to cancel it -Baritone did not intercept it, so it had no one to reach- or that
     *         there was no player. The whole path is synchronous, so {@link #sendCaught} is already
     *         decided when this call returns.
     */
    private boolean send(String command) {
        if (mc.player == null) return false;

        boolean outer = emitting;
        emitting = true;
        sendCaught = false;
        try {
            ChatUtils.sendPlayerMsg(command, false);
        }
        finally {
            emitting = outer;
        }
        return !sendCaught;
    }

    private BaritoneScript.FlightSettings flightSettings() {
        return new BaritoneScript.FlightSettings(autoJump.get(), emergencyLand.get(), conserveFireworks.get(),
            fireworkSpeed.get(), netherSeed.get().strip());
    }

    private BaritoneScript.FlightSettings restingSettings() {
        // The seed is not restored: it was never a change of ours, only a fact passed to Baritone if
        // we had it, and the core does not write it in the restoration.
        return new BaritoneScript.FlightSettings(autoJumpResting.get(), emergencyLandResting.get(),
            conserveFireworksResting.get(), fireworkSpeedResting.get(), "");
    }

    /** The visual half of a loud warning: the toast that goes with the chat. */
    private void loudToast(Msg message, Item icon) {
        MeteorToast.Builder toast = new MeteorToast.Builder("Xploits").text(Texts.render(message)).icon(icon);
        // MeteorToast.update() calls play(customSound) without checking for null and vanilla
        // dereferences it: NPE on the render thread. Never pass null; it is silenced with zero volume.
        if (!notifySound.get()) {
            toast.sound(PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), 1.2f, 0f));
        }
        mc.getToastManager().add(toast.build());
    }

    public Msg status() {
        if (!isActive()) return Msg.of(SweepText.STATUS_OFF);

        if (!sweeping) {
            return Msg.of(SweepText.STATUS_IDLE, "width", widthDescription(),
                "rate", blocksPerFirework.get() > 0
                    ? Msg.of(SweepText.STATUS_RATE, "rate", Math.round(blocksPerFirework.get()))
                    : Msg.of(SweepText.STATUS_NO_RATE));
        }

        Msg coverage = tally == null
            ? Msg.of(SweepText.NOTHING)
            : Msg.of(SweepText.STATUS_COVERAGE, "summary", tally.summary());
        Msg left = mc.player == null
            ? Msg.of(SweepText.NOTHING)
            : Msg.of(SweepText.STATUS_LEFT, "blocks", Math.round(remainingBlocks()),
                "return", countReturnTrip.get() ? SweepText.STATUS_WITH_RETURN : SweepText.STATUS_WITHOUT_RETURN);
        OptionalDouble measuredRate = fuel.blocksPerRocket();
        Msg rate = measuredRate.isPresent()
            ? Msg.of(SweepText.STATUS_RATE, "rate", Math.round(measuredRate.getAsDouble()))
            : Msg.of(SweepText.STATUS_NO_FLIGHT_RATE, "blocks", Math.round(blocksWithoutProjection));
        return Msg.of(SweepText.STATUS_SWEEPING, "lane", Math.min(index / 2 + 1, planLanes),
            "total", planLanes, "width", usedWidth,
            "how", typedWidth ? SweepText.WIDTH_TYPED : SweepText.WIDTH_MEASURED,
            "coverage", coverage, "flown", Math.round(sweepBlocksFlown()), "left", left, "rate", rate,
            "net", netArmed ? SweepText.NET_ARMED : SweepText.NET_DISARMED,
            "caught", netCaughtWarned ? SweepText.STATUS_NET_CAUGHT : SweepText.NOTHING);
    }

    private Msg widthDescription() {
        if (laneWidth.get() > 0) {
            return Msg.of(SweepText.WIDTH_TYPED_DESC, "width", laneWidth.get());
        }
        if (!probe.hasEnoughSamples()) {
            return Msg.of(SweepText.WIDTH_MEASURING, "samples", probe.sampleCount(),
                "needed", WidthProbe.MIN_SAMPLES, "discarded", discardedSamplesNote());
        }
        return Msg.of(SweepText.WIDTH_MEASURED_DESC, "width", probe.laneWidthInChunks(laneWidthMargin.get()),
            "radius", probe.observedRadiusInChunks(), "ceiling", lastCeiling, "margin", laneWidthMargin.get(),
            "discarded", discardedSamplesNote());
    }

    /**
     * The tail note about discarded samples. It is shown because it is the explanation of why the
     * measurement is what it is, and <b>the two kinds of discard mean different things</b>: one is a
     * lagging server and the other is the player flying, which is fixed by stopping. Without seeing
     * them, all one sees is that the width does not come out.
     */
    private Msg discardedSamplesNote() {
        Msg late = probe.discardedSamples() > 0
            ? Msg.of(SweepText.DISCARDED_LATE, "count", probe.discardedSamples())
            : Msg.of(SweepText.NOTHING);
        Msg moving = probe.movingSamples() > 0
            ? Msg.of(SweepText.DISCARDED_MOVING, "count", probe.movingSamples())
            : Msg.of(SweepText.NOTHING);
        return Msg.of(SweepText.DISCARDED, "late", late, "moving", moving);
    }

    // --- The coverage that already exists (spec §5.1) -------------------------------------------

    /**
     * What came out of reading the five {@code NewerNewChunks} files: the merged coverage and how
     * many files it came from, so that it can be said without pretending more was read than there
     * was.
     *
     * <p><b>"I could not find out where to look" and "nothing was recorded" are different things</b>
     * and have a field of their own. Both give the same empty coverage and the same plan -the whole
     * rectangle-, but they mean the opposite to the player: one is "you start from scratch", which
     * is correct and costs nothing; the other is "I did not even look", and then the three hours of
     * flight are going to repeat terrain that has been piling up for months. Counting them as the
     * same thing is letting them take off believing the first when the second is happening.
     */
    private record CoverageRead(Coverage coverage, int read, int broken, boolean unknownServer) {
        /** The read that never happened because it was not known which folder to look in. */
        static CoverageRead nowhereToLook() {
            return new CoverageRead(Coverage.empty(), 0, 0, true);
        }

        Msg summary() {
            if (unknownServer) return Msg.of(SweepText.READING_UNKNOWN_SERVER);
            if (read == 0 && broken == 0) return Msg.of(SweepText.READING_NONE);
            return broken == 0
                ? Msg.of(SweepText.READING_FILES, "read", read, "total", COVERAGE_FILES.length)
                : Msg.of(SweepText.READING_FILES_BROKEN, "read", read, "total", COVERAGE_FILES.length,
                    "broken", broken);
        }
    }

    /**
     * Reads the five {@code NewerNewChunks} files for the active server and dimension and merges them
     * (spec §5.1). With 17,000 chunks already seen, starting from scratch would be repeating terrain
     * the player has been piling up for months.
     *
     * <p><b>A missing file is not an error</b>: {@code NewerNewChunks} only writes the ones it has
     * something to write in, and none existing amounts to starting from scratch. One that cannot be
     * read does not drop the rest either -it is counted and reported-, for the same reason that
     * {@link Coverage} skips a broken line instead of aborting: dropping the whole read sends the
     * player to repeat terrain already seen.
     *
     * <p>It is read as {@code ISO-8859-1} and not UTF-8 on purpose: the content is digits, commas and
     * newlines, and that charset cannot throw {@code MalformedInputException} on a file the other mod
     * left half-written when the client closed abruptly.
     *
     * <p>And the <b>whole</b> file is read with {@code readString}, not line by line, because
     * {@link Coverage#ofFileContent} needs to see whether it ends in a newline: it is the only thing
     * that tells a closed file apart from one cut off mid-write, and a cut line can parse as a
     * perfectly valid chunk that was never seen. The full reason is in its javadoc.
     */
    private CoverageRead readCoverage() {
        Path folder = coverageFolder();
        if (folder == null) return CoverageRead.nowhereToLook();

        List<Coverage> parts = new ArrayList<>(COVERAGE_FILES.length);
        int read = 0;
        int broken = 0;
        for (String fileName : COVERAGE_FILES) {
            Path path = folder.resolve(fileName);
            if (!Files.isRegularFile(path)) continue;
            try {
                parts.add(Coverage.ofFileContent(Files.readString(path, StandardCharsets.ISO_8859_1)));
                read++;
            } catch (IOException | RuntimeException e) {
                broken++;
                warning(SweepText.UNREADABLE_FILE, "file", fileName, "error", e.getClass().getSimpleName());
            }
        }
        return new CoverageRead(Coverage.merge(parts), read, broken, false);
    }

    /**
     * The folder where {@code NewerNewChunks} keeps the coverage of the active server and dimension,
     * or {@code null} if it cannot be known which one it is.
     *
     * <p><b>Verified by reading the bytecode of the installed jar</b> -{@code trouser-streak-1.6.1},
     * {@code pwn.noobs.trouserstreak.modules.NewerNewChunks}- and confirmed against the folders that
     * already exist on disk, because a guessed path would be read as empty, and "empty" here means
     * replanning the whole rectangle and repeating hours of terrain already seen:
     *
     * <ul>
     *   <li>The root is {@code FabricLoader.getGameDir() / "TrouserStreak" / "NewChunks"}.</li>
     *   <li>The server is {@code mc.getCurrentServerEntry().address} -the same place Meteor's
     *       {@code Utils.getWorldName()} takes it from-, or, in a local world, the name of the folder
     *       that contains the world's folder.</li>
     *   <li>The dimension is {@code mc.world.getRegistryKey().getValue().toString()}, that is
     *       {@code "minecraft:the_nether"}.</li>
     *   <li>Both go through the same {@link #INVALID_CHARACTERS} replacement, which is what turns
     *       {@code minecraft:the_nether} into {@code minecraft_the_nether}.</li>
     * </ul>
     */
    private Path coverageFolder() {
        if (mc.world == null) return null;

        String server = serverName();
        if (server == null) return null;

        String dimension = sanitize(mc.world.getRegistryKey().getValue().toString());
        return FabricLoader.getInstance().getGameDir()
            .resolve("TrouserStreak")
            .resolve("NewChunks")
            .resolve(server)
            .resolve(dimension);
    }

    /** The server's folder name, with the same logic as {@code NewerNewChunks}. */
    private String serverName() {
        if (mc.isInSingleplayer()) {
            if (mc.getServer() == null) return "singleplayer";
            Path parent = mc.getServer().getSavePath(WorldSavePath.ROOT).getParent();
            if (parent == null || parent.getFileName() == null) return "singleplayer";
            return sanitize(parent.getFileName().toString());
        }

        ServerInfo entry = mc.getCurrentServerEntry();
        if (entry == null) return null;
        return sanitize(entry.address);
    }

    /** The same folder-name sanitizing {@code NewerNewChunks} applies, no more and no less. */
    private static String sanitize(String name) {
        return name.replaceAll(INVALID_CHARACTERS, "_");
    }

}
