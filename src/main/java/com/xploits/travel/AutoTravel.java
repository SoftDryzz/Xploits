package com.xploits.travel;

import com.xploits.XploitsAddon;
import com.xploits.console.core.GameSnapshot;
import com.xploits.elytra.ElytraReplace;
import com.xploits.shared.XploitsModule;
import com.xploits.shared.Texts;
import com.xploits.shared.core.PositionedMsg;
import com.xploits.shared.core.i18n.Msg;
import com.xploits.sweep.NetherSweep;
import com.xploits.travel.core.Axis;
import com.xploits.travel.core.BaritoneScript;
import com.xploits.travel.core.BorrowedModule;
import com.xploits.travel.core.Destination;
import com.xploits.travel.core.FireworkWatch;
import com.xploits.travel.core.FlightPattern;
import com.xploits.travel.core.PatternParams;
import com.xploits.travel.core.Route;
import com.xploits.travel.core.RoutePlanner;
import com.xploits.travel.core.SafetyNet;
import com.xploits.travel.core.StallWatch;
import com.xploits.travel.core.TravelText;
import com.xploits.travel.core.Waypoint;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
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
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ChatCommandSignedC2SPacket;
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import net.minecraft.sound.SoundEvents;

import java.util.List;
import java.util.Optional;

/**
 * AutoTravel adapter (spec §3): sets a destination, asks the core for the route with a decoy
 * pattern, orders the three systems that fight over the elytra, launches Baritone's flight through
 * chat commands and puts everything back in its place when it ends.
 *
 * <p>All the geometry and all the command building live in {@code travel.core}, which is tested
 * without starting the game. What is left here is only what needs Minecraft: reading the position,
 * sending the commands, watching the progress, turning modules on and off, and arming the safety net.
 *
 * <p><b>Turning the module on does not fly.</b> The player launches the trip with {@code .xploits
 * travel go}, because a module that takes off by itself when enabled sends you 100 000 blocks away
 * with one click.
 *
 * <p><b>The safety net (spec §7) is the module's reason to exist.</b> Sending {@code #elytra} to
 * Baritone means writing in the chat and trusting it to intercept it before the packet leaves.
 * Meteor's guard for that depends on {@code BaritoneUtils.IS_AVAILABLE}, which is {@code false} on
 * these instances even though Baritone is installed (spec §2), so trust is not enough: while the
 * module is in charge, it cancels by itself every outgoing chat packet whose text starts with
 * Baritone's prefix. On an anarchy server, an {@code #elytra} that slips out announces to the whole
 * server that you are flying, and where to.
 *
 * <p>That net lives in {@link ChatNet}, <b>subscribed to the bus on its own</b> and not as part of
 * the module, because Meteor unsubscribes the module right before {@code onDeactivate()} -which is
 * when the restoration sends its six commands-. And the decision of which text is one of our
 * commands is in {@link SafetyNet}, in the core and with tests.
 */
public class AutoTravel extends XploitsModule {
    /** The id Baritone registers with in the mod loader. */
    private static final String BARITONE_MOD_ID = "baritone";

    /** Without getting closer to the waypoint for this long, the trip is cut (spec §8). */
    private static final double STALL_SECONDS = 30;

    /** How much the distance has to drop to count as progress, in blocks. */
    private static final double PROGRESS_EPSILON = 1.0;

    /**
     * The three ways of asking for a destination (spec §4), as the settings' vocabulary.
     *
     * <p>Each one reads <b>its own</b> settings, and that is why there are three and not two with a
     * switch: see {@link #destination()}.
     */
    public enum DestinationMode {
        COORDINATES,
        RELATIVE,
        HIGHWAY
    }

    private final SettingGroup sgDestination = settings.getDefaultGroup();
    private final SettingGroup sgPattern = settings.createGroup("Pattern");
    private final SettingGroup sgFlight = settings.createGroup("Flight");
    private final SettingGroup sgNotify = settings.createGroup("Notify");

    // Destination (spec §4)

    private final Setting<DestinationMode> destinationMode = sgDestination.add(new EnumSetting.Builder<DestinationMode>()
        .name("destination-mode")
        .description(Texts.startupText(TravelText.SETTING_DESTINATION_MODE))
        .defaultValue(DestinationMode.COORDINATES)
        .build()
    );

    private final Setting<Double> destinationX = sgDestination.add(new DoubleSetting.Builder()
        .name("x")
        .description(Texts.startupText(TravelText.SETTING_X))
        .defaultValue(0)
        .sliderRange(-100_000, 100_000)
        .decimalPlaces(0)
        .visible(() -> destinationMode.get() == DestinationMode.COORDINATES)
        .build()
    );

    private final Setting<Double> destinationZ = sgDestination.add(new DoubleSetting.Builder()
        .name("z")
        .description(Texts.startupText(TravelText.SETTING_Z))
        .defaultValue(0)
        .sliderRange(-100_000, 100_000)
        .decimalPlaces(0)
        .visible(() -> destinationMode.get() == DestinationMode.COORDINATES)
        .build()
    );

    // The RELATIVE mode's offset has its OWN settings, and they are not x/z with another meaning.
    // Sharing them would be cheap to write and expensive to use: a far absolute destination already
    // configured would start reading as a huge offset from wherever you are as soon as you switched
    // mode, without touching a number. With their own settings, switching mode reinterprets nothing:
    // it reads other fields, and the previous ones still mean what they meant.

    private final Setting<Double> offsetX = sgDestination.add(new DoubleSetting.Builder()
        .name("offset-x")
        .description(Texts.startupText(TravelText.SETTING_OFFSET_X))
        .defaultValue(0)
        .sliderRange(-100_000, 100_000)
        .decimalPlaces(0)
        .visible(() -> destinationMode.get() == DestinationMode.RELATIVE)
        .build()
    );

    private final Setting<Double> offsetZ = sgDestination.add(new DoubleSetting.Builder()
        .name("offset-z")
        .description(Texts.startupText(TravelText.SETTING_OFFSET_Z))
        .defaultValue(0)
        .sliderRange(-100_000, 100_000)
        .decimalPlaces(0)
        .visible(() -> destinationMode.get() == DestinationMode.RELATIVE)
        .build()
    );

    private final Setting<Axis> axis = sgDestination.add(new EnumSetting.Builder<Axis>()
        .name("axis")
        .description(Texts.startupText(TravelText.SETTING_AXIS))
        .defaultValue(Axis.X_PLUS)
        .visible(() -> destinationMode.get() == DestinationMode.HIGHWAY)
        .build()
    );

    private final Setting<Double> highwayDistance = sgDestination.add(new DoubleSetting.Builder()
        .name("highway-distance")
        .description(Texts.startupText(TravelText.SETTING_HIGHWAY_DISTANCE))
        .defaultValue(10_000)
        .min(0)
        .sliderRange(0, 100_000)
        .decimalPlaces(0)
        .visible(() -> destinationMode.get() == DestinationMode.HIGHWAY)
        .build()
    );

    private final Setting<Double> highwayMaxAmplitude = sgDestination.add(new DoubleSetting.Builder()
        .name("highway-max-amplitude")
        .description(Texts.startupText(TravelText.SETTING_HIGHWAY_MAX_AMPLITUDE))
        .defaultValue(300)
        .min(0)
        .sliderRange(0, 2_000)
        .decimalPlaces(0)
        .visible(() -> destinationMode.get() == DestinationMode.HIGHWAY)
        .build()
    );

    private final Setting<Double> waypointMargin = sgDestination.add(new DoubleSetting.Builder()
        .name("waypoint-margin")
        .description(Texts.startupText(TravelText.SETTING_WAYPOINT_MARGIN))
        .defaultValue(RoutePlanner.DEFAULT_WAYPOINT_MARGIN)
        .min(RoutePlanner.MIN_WAYPOINT_MARGIN)
        .sliderRange(RoutePlanner.MIN_WAYPOINT_MARGIN, 500)
        .decimalPlaces(0)
        .build()
    );

    // Pattern and its parameters (spec §5)

    private final Setting<FlightPattern> pattern = sgPattern.add(new EnumSetting.Builder<FlightPattern>()
        .name("pattern")
        .description(Texts.startupText(TravelText.SETTING_PATTERN))
        .defaultValue(FlightPattern.STRAIGHT)
        .build()
    );

    private final Setting<Double> amplitude = sgPattern.add(new DoubleSetting.Builder()
        .name("zigzag-amplitude")
        .description(Texts.startupText(TravelText.SETTING_ZIGZAG_AMPLITUDE))
        .defaultValue(PatternParams.defaults().amplitude())
        .min(0)
        .sliderRange(0, 2_000)
        .decimalPlaces(0)
        .visible(() -> pattern.get() == FlightPattern.ZIGZAG)
        .build()
    );

    private final Setting<Double> period = sgPattern.add(new DoubleSetting.Builder()
        .name("zigzag-period")
        .description(Texts.startupText(TravelText.SETTING_ZIGZAG_PERIOD))
        .defaultValue(PatternParams.defaults().period())
        .min(1)
        .sliderRange(100, 20_000)
        .decimalPlaces(0)
        .visible(() -> pattern.get() == FlightPattern.ZIGZAG)
        .build()
    );

    private final Setting<Double> legLength = sgPattern.add(new DoubleSetting.Builder()
        .name("swerve-leg")
        .description(Texts.startupText(TravelText.SETTING_SWERVE_LEG))
        .defaultValue(PatternParams.defaults().legLength())
        .min(1)
        .sliderRange(500, 40_000)
        .decimalPlaces(0)
        .visible(() -> pattern.get() == FlightPattern.SWERVE)
        .build()
    );

    private final Setting<Double> lateralOffset = sgPattern.add(new DoubleSetting.Builder()
        .name("swerve-offset")
        .description(Texts.startupText(TravelText.SETTING_SWERVE_OFFSET))
        .defaultValue(PatternParams.defaults().lateralOffset())
        .min(0)
        .sliderRange(0, 5_000)
        .decimalPlaces(0)
        .visible(() -> pattern.get() == FlightPattern.SWERVE)
        .build()
    );

    private final Setting<Double> spiralRadius = sgPattern.add(new DoubleSetting.Builder()
        .name("spiral-radius")
        .description(Texts.startupText(TravelText.SETTING_SPIRAL_RADIUS))
        .defaultValue(PatternParams.defaults().spiralRadius())
        .min(0)
        .sliderRange(100, 10_000)
        .decimalPlaces(0)
        .visible(() -> pattern.get() == FlightPattern.SPIRAL)
        .build()
    );

    private final Setting<Double> spiralTurns = sgPattern.add(new DoubleSetting.Builder()
        .name("spiral-turns")
        .description(Texts.startupText(TravelText.SETTING_SPIRAL_TURNS))
        .defaultValue(PatternParams.defaults().spiralTurns())
        .min(0)
        .sliderRange(0.5, 6)
        .decimalPlaces(1)
        .visible(() -> pattern.get() == FlightPattern.SPIRAL)
        .build()
    );

    private final Setting<Double> decoyAngle = sgPattern.add(new DoubleSetting.Builder()
        .name("decoy-angle")
        .description(Texts.startupText(TravelText.SETTING_DECOY_ANGLE))
        .defaultValue(PatternParams.defaults().decoyAngleDegrees())
        .range(0, 89)
        .sliderRange(0, 89)
        .decimalPlaces(0)
        .visible(() -> pattern.get() == FlightPattern.DECOY)
        .build()
    );

    private final Setting<Double> decoyFraction = sgPattern.add(new DoubleSetting.Builder()
        .name("decoy-fraction")
        .description(Texts.startupText(TravelText.SETTING_DECOY_FRACTION))
        .defaultValue(PatternParams.defaults().decoyFraction())
        .range(0.05, 0.95)
        .sliderRange(0.05, 0.95)
        .decimalPlaces(2)
        .visible(() -> pattern.get() == FlightPattern.DECOY)
        .build()
    );

    // Flight: the prefix and Baritone's four settings, each with its flight value and its resting
    // value. The two Meteor modules have NO resting setting: their state is read and noted down at
    // take-off (see BorrowedModule).
    //
    // The four resting values ship with Baritone 1.17.0's REAL default, read from the bytecode of its
    // Settings class (baritone/e.class, javap -p -c) in the installed jar: elytraAutoJump FALSE,
    // elytraAllowEmergencyLand TRUE, elytraConserveFireworks FALSE and elytraFireworkSpeed 1.2.
    // Putting another value here is not "leaving it as it was": Baritone persists its settings to
    // disk, so the first trip would reconfigure forever every #elytra the player runs by hand.

    private final Setting<String> prefix = sgFlight.add(new StringSetting.Builder()
        .name("baritone-prefix")
        .description(Texts.startupText(TravelText.SETTING_BARITONE_PREFIX))
        .defaultValue("#")
        .build()
    );

    private final Setting<Boolean> autoJump = sgFlight.add(new BoolSetting.Builder()
        .name("auto-jump")
        .description(Texts.startupText(TravelText.SETTING_AUTO_JUMP))
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoJumpResting = sgFlight.add(new BoolSetting.Builder()
        .name("auto-jump-resting")
        .description(Texts.startupText(TravelText.SETTING_AUTO_JUMP_RESTING))
        .defaultValue(BaritoneScript.baritoneDefaults().autoJump())
        .build()
    );

    private final Setting<Boolean> allowEmergencyLand = sgFlight.add(new BoolSetting.Builder()
        .name("emergency-land")
        .description(Texts.startupText(TravelText.SETTING_EMERGENCY_LAND))
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> allowEmergencyLandResting = sgFlight.add(new BoolSetting.Builder()
        .name("emergency-land-resting")
        .description(Texts.startupText(TravelText.SETTING_EMERGENCY_LAND_RESTING))
        .defaultValue(BaritoneScript.baritoneDefaults().allowEmergencyLand())
        .build()
    );

    private final Setting<Boolean> conserveFireworks = sgFlight.add(new BoolSetting.Builder()
        .name("conserve-fireworks")
        .description(Texts.startupText(TravelText.SETTING_CONSERVE_FIREWORKS))
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> conserveFireworksResting = sgFlight.add(new BoolSetting.Builder()
        .name("conserve-fireworks-resting")
        .description(Texts.startupText(TravelText.SETTING_CONSERVE_FIREWORKS_RESTING))
        .defaultValue(BaritoneScript.baritoneDefaults().conserveFireworks())
        .build()
    );

    private final Setting<Double> fireworkSpeed = sgFlight.add(new DoubleSetting.Builder()
        .name("firework-speed")
        .description(Texts.startupText(TravelText.SETTING_FIREWORK_SPEED))
        .defaultValue(1)
        .min(0)
        .sliderRange(0.5, 3)
        .decimalPlaces(2)
        .build()
    );

    private final Setting<Double> fireworkSpeedResting = sgFlight.add(new DoubleSetting.Builder()
        .name("firework-speed-resting")
        .description(Texts.startupText(TravelText.SETTING_FIREWORK_SPEED_RESTING))
        .defaultValue(BaritoneScript.baritoneDefaults().fireworkSpeed())
        .min(0)
        .sliderRange(0.5, 3)
        .decimalPlaces(2)
        .build()
    );

    private final Setting<String> netherSeed = sgFlight.add(new StringSetting.Builder()
        .name("nether-seed")
        .description(Texts.startupText(TravelText.SETTING_NETHER_SEED))
        .defaultValue("")
        .build()
    );

    // Notify

    private final Setting<Boolean> notify = sgNotify.add(new BoolSetting.Builder()
        .name("notify")
        .description(Texts.startupText(TravelText.SETTING_NOTIFY))
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifySound = sgNotify.add(new BoolSetting.Builder()
        .name("notify-sound")
        .description(Texts.startupText(TravelText.SETTING_NOTIFY_SOUND))
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> fireworkThreshold = sgNotify.add(new IntSetting.Builder()
        .name("firework-warning")
        .description(Texts.startupText(TravelText.SETTING_FIREWORK_WARNING))
        .defaultValue(16)
        .min(0)
        .sliderRange(0, 128)
        .build()
    );

    /** Whether a trip is under way: the only thing that tells "enabled" apart from "in charge". */
    private boolean travelling;

    /**
     * Whether what is happening right now is the teardown on leaving the world, during which <b>no
     * Meteor module can be turned on or off</b> (spec §6.4).
     *
     * <p>{@link #onGameLeft(GameLeftEvent)} sets it and {@link #onActivate()} clears it, which is
     * the way back in for the module on joining: {@code Modules.onGameJoined} subscribes each active
     * module and calls its {@code onActivate()}.
     *
     * <p><b>Why the handler can be trusted to run first.</b> Checked in the orbit 0.2.4 sources:
     * {@code EventBus.insert()} walks the listeners already subscribed and puts the new one before
     * the first whose priority is <b>strictly lower</b>. The {@code Modules} handler -the one that
     * tears down- declares no priority, so it is {@code EventPriority.MEDIUM} (0); ours is {@code
     * HIGHEST} (200), so it always stays ahead, whoever subscribes first. And {@code EventBus.post()}
     * walks that list in order.
     */
    private boolean leavingWorld;

    /**
     * The two Meteor modules the trip borrows (spec §6.2, step 5). The decision of what to do with
     * them on take-off and on landing is in {@link BorrowedModule}, in the core and with tests: here
     * only the {@code isActive()} values are read and whatever it answers is carried out.
     *
     * <p>Neither has a resting setting, and that is the difference with Baritone's four settings:
     * the argument of spec §6.1 -they cannot be read, so what to go back to must be declared- holds
     * for Baritone and not here, because {@code Module.isActive()} can be read.
     */
    private final BorrowedModule elytraFly = new BorrowedModule("elytra-fly", false);
    private final BorrowedModule elytraReplace = new BorrowedModule("elytra-replace", true);

    /**
     * The safety net's listener (spec §7), <b>subscribed to the bus on its own</b> and not as part
     * of the module. This is not a design whim: it is the fix for a hole verified in Meteor's
     * sources.
     *
     * <p>{@code Module.toggle()} unsubscribes the module from the bus <b>before</b> calling {@code
     * onDeactivate()}, and {@code Modules.onGameLeft} does exactly the same. If the net lived in an
     * {@code @EventHandler} of the module, the restoration -six prefixed commands, {@code cancel}
     * included- would be sent with the module already unsubscribed: the net would not run, nothing
     * would be cancelled, and the six lines would go out to public chat precisely in the two exits
     * in which the player is still connected and the packets really leave. Turning the module off is
     * the natural panic reaction, and it is what a bind does.
     *
     * <p>A standalone object depends on none of that: it subscribes on arming and unsubscribes in
     * {@link #disarmNet()}, after the last command. Checked against the orbit 0.2.4 sources: {@code
     * EventBus.subscribe(Object)} reflects over the annotated methods of the object's class and
     * builds the listener with the lambda factory of the addon's package -{@code com.xploits}, which
     * {@code MeteorClient} registers through {@code MeteorAddon.getPackage()}-, so any class of ours
     * will do. And it is always the <b>same instance</b>, because {@code EventBus} caches listeners
     * by object identity and {@code unsubscribe} needs to find the same ones there.
     */
    private final ChatNet net = new ChatNet();

    /**
     * Whether the safety net is armed, which now means exactly whether {@link #net} is subscribed
     * to the bus. The module can be on with no trip under way, and then it has no business eating
     * the commands the player types by hand: the net is armed before the first command is sent and
     * disarmed when none is left to send.
     */
    private boolean netArmed;

    /** Whether the command {@link #send(String)} is sending right now is ours. */
    private boolean emitting;

    /** Whether the net killed our last command: set by the listener, read by {@link #send(String)}. */
    private boolean sendCaught;

    /** Whether the player was already told that the net had to cancel something on this trip (spec §7: only once). */
    private boolean netCaughtWarned;

    /**
     * The prefix this trip started with. The net and the restoration use this one, not the
     * setting's: if the player edits the prefix mid-flight, the commands already in flight are still
     * the old ones, and the restoration has to speak to Baritone in the same language it was spoken
     * to at take-off.
     */
    private String activePrefix = "";

    private List<Waypoint> waypoints = List.of();
    private int index;

    /**
     * The stall watch (spec §8), in the core and with tests. The adapter only gives it the waypoint
     * it is heading to and the distance left; it keeps the count and decides whether to cut,
     * including resetting itself on a waypoint change.
     */
    private final StallWatch stallWatch = StallWatch.ofSeconds(STALL_SECONDS, PROGRESS_EPSILON);

    /**
     * The firework warning decision (spec §8), also in the core. Here the inventory's fireworks are
     * only counted and the toast shown; when to warn and when to rearm the warning is its call. It
     * is built when each trip is launched because the threshold is a setting and may have changed.
     */
    private FireworkWatch fireworkWatch = new FireworkWatch(0);

    public AutoTravel() {
        super(XploitsAddon.CATEGORY, "auto-travel", Texts.startupText(TravelText.MODULE_DESC));
    }

    @Override
    public void onActivate() {
        // Turning on does not fly: it waits for the player's command.
        //
        // This is also where we come back on joining the world -Modules.onGameJoined subscribes each
        // active module and calls its onActivate()-, so it is the place to forget that we were
        // leaving. Whatever was left pending to give back is NOT applied here: we are inside the
        // dispatch of GameJoinedEvent, which is exactly when Modules is subscribing modules, and
        // turning one on in the middle of that is the same hole that led here. It is applied on the
        // first tick.
        leavingWorld = false;
        resetTrip();

        // Turning this module on does nothing visible, and a module that does nothing when turned on
        // and does not say so cannot be told apart from a broken one: that is exactly the conclusion
        // the player reached the first time. The addon's other five stay quiet because they start
        // working on their own; this one is the only one that waits for a second order, so it is the
        // only one that has to say so. It is repeated on joining the world on purpose: if the module
        // is still on, knowing that it is armed and with which destination is worth more than saving
        // a chat line.
        info(TravelText.ARMED);
        infoPrivate(new PositionedMsg(
            Msg.of(TravelText.ARMED_SUMMARY, "pattern", pattern.get().name(), "destination", describeDestination()),
            Msg.of(TravelText.ARMED_SUMMARY, "pattern", pattern.get().name(), "destination", describeDestinationWithoutPosition())));
    }

    @Override
    public void onDeactivate() {
        // Exit 5: the module is turned off. Leaving the world also passes through here -Modules calls
        // onDeactivate() on every active module-, but by then onGameLeft has already closed the trip:
        // it runs first by priority (spec §6.4), and finish() is idempotent, so this second pass
        // restores nothing. That is exactly what is needed, because here a normal turn-off can no
        // longer be told apart from the teardown.
        finish(TravelText.REASON_MODULE_OFF, false);
        // And whatever happens, the net does not outlive the module: a listener subscribed with no
        // trip under way would silently eat every prefixed command the player typed by hand, and
        // with the module off there would be nobody to disarm it nor to say so. It is idempotent.
        disarmNet();
    }

    /**
     * Exit 4: disconnection or world change. It runs at {@code HIGHEST} on purpose, and it is not
     * an ordering preference: it is what lets the restoration know it is inside the teardown.
     *
     * <p>{@code Modules.onGameLeft} walks the active modules doing {@code unsubscribe(module);
     * module.onDeactivate();} and does <b>not</b> set {@code active = false}, so that they come back
     * by themselves on the next join. Our {@code onDeactivate()} restores, and restoring turned on
     * {@code elytra-fly}: {@code Module.toggle()} subscribes it to the bus in the middle of the
     * teardown and, if the loop had already reached it, it ends up subscribed and active. On the
     * next join {@code Modules.onGameJoined} subscribes it again and orbit's {@code
     * EventBus.insert()} does not deduplicate, so its handlers ran twice per event for the rest of
     * the session -and turning it off did not fix it: {@code unsubscribe} uses {@code List.remove},
     * which removes a single copy-.
     *
     * <p>With {@code HIGHEST} priority this handler runs before the {@code Modules} one: it marks
     * that we are leaving and ends the trip itself, with Baritone's commands -which can still be
     * sent, because {@code GameLeftEvent} is posted with the world and the player still alive- and
     * without touching any module. The {@code onDeactivate()} that comes afterwards finds the trip
     * already closed.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    private void onGameLeft(GameLeftEvent event) {
        leavingWorld = true;
        finish(TravelText.REASON_LEFT_WORLD, false);
    }

    /**
     * The half of the safety net (spec §7) that needs Minecraft: getting from the outgoing packet
     * which channel it goes through and what text it carries. Deciding whether that text is one of
     * the Baritone commands we are directing belongs to {@link SafetyNet}, which is tested without
     * starting the game.
     *
     * <p>Meteor's {@code ClientConnectionMixin} posts this event on entering {@code
     * ClientConnection.send} and cancels the send if the event is cancelled, so this is not a
     * warning: the packet dies inside the client.
     *
     * <p><b>Our own commands</b> are cancelled too, and that is deliberate: if the net has to act it
     * is because Baritone is not intercepting, and then our command has nobody to reach either. What
     * cannot be done is to take the restoration as good afterwards, and that is what {@link
     * #sendCaught} is for.
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
     * that: any command typed by hand would be published. Once per trip, and loud: if it repeated for
     * every preparation command it would be one line per #set in a row and the only one that matters
     * would get lost.
     */
    private void warnNetCaught(String text) {
        if (netCaughtWarned) return;
        netCaughtWarned = true;

        Msg message = Msg.of(TravelText.NET_CAUGHT, "command", text);
        // The cancelled command may be a #goal with coordinates: only its verb goes to the console.
        Msg withoutArguments = Msg.of(TravelText.NET_CAUGHT_VERB, "verb", SafetyNet.verb(text));
        warningPrivate(new PositionedMsg(message, withoutArguments));
        loudToast(message, Items.BARRIER);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // Whatever was left to give back on leaving the world is given back here, on the first tick
        // after joining again: by then Modules.onGameJoined has finished subscribing everyone, so
        // turning a module on subscribes it only once.
        applyPendingModules();

        if (!travelling) return;

        if (mc.player == null || mc.world == null) {
            finish(TravelText.REASON_LOST_WORLD, true);
            return;
        }
        // Exit 3: death. Meteor has no death event, so it is watched here; the player still exists
        // on the death screen, so the restoration can still talk to Baritone.
        if (!mc.player.isAlive()) {
            finish(TravelText.REASON_DIED, true);
            return;
        }

        checkFireworks();

        Waypoint here = new Waypoint(mc.player.getX(), mc.player.getZ());
        double distance = here.distanceTo(waypoints.get(index));

        // The margin within which a waypoint counts as reached is not the same for all of them -the
        // last one is the only place where Baritone must land-, and that decision lives in the core
        // with tests: see RoutePlanner.reachedMargin.
        if (distance <= RoutePlanner.reachedMargin(index, waypoints.size(), waypointMargin.get())) {
            index++;
            // Exit 1: arrival.
            if (index >= waypoints.size()) {
                finish(TravelText.REASON_ARRIVED, false);
                return;
            }
            if (notify.get()) info(TravelText.WAYPOINT_REACHED, "index", index, "total", waypoints.size());
            aimAtCurrentWaypoint();
            return;
        }

        // Exit 6: stall. The only thing observable from outside is whether the distance drops;
        // Baritone reports nothing else (spec §8). The index goes into the call on purpose: it is
        // what keeps the distance jump on a waypoint change from reading as thirty seconds without
        // progress.
        if (stallWatch.tick(index, distance)) {
            Msg message = Msg.of(TravelText.STALLED, "index", index + 1, "seconds", stallWatch.limitSeconds(),
                "distance", Math.round(distance));
            warning(message);
            loudToast(message, Items.ELYTRA);
            finish(TravelText.REASON_STALL, false);
        }
    }

    /**
     * The loud firework warning (spec §8: <i>"Finding out at 100k matters"</i>). All the adapter does
     * is count them; whether the warning is due -and whether it already went out- is decided by
     * {@link FireworkWatch}.
     *
     * <p>Path checked against the remapped sources of {@code meteor-client:1.21.11-SNAPSHOT}:
     * {@code InvUtils.find(Item...)} walks {@code mc.player.getInventory().getStack(i)} from 0 to
     * {@code size()} and adds up {@code getCount()} of every matching stack, so it covers the hotbar,
     * the main inventory, the armour and the offhand; it returns {@code count 0} without a player
     * instead of blowing up. It is the same path Meteor's own {@code ElytraFlightMode} uses for its
     * fireworks. What is inside a shulker is not counted, same as in {@code elytra-replace}.
     */
    private void checkFireworks() {
        int fireworks = InvUtils.find(Items.FIREWORK_ROCKET).count();
        if (!fireworkWatch.observe(fireworks)) return;

        Msg message = fireworks == 0
            ? Msg.of(TravelText.OUT_OF_FIREWORKS)
            : Msg.of(TravelText.LOW_FIREWORKS, "count", fireworks, "threshold", fireworkWatch.threshold());
        warning(message);
        loudToast(message, Items.FIREWORK_ROCKET);
    }

    /**
     * Launches the trip (exit of none: it is the entry). Returns the message the command has to
     * show, be it the launch one or the reason why there is no flight. No failure is silent and,
     * when in doubt, not a single command is sent (spec §9).
     */
    public PositionedMsg start() {
        if (!isActive()) return PositionedMsg.same(Msg.of(TravelText.START_MODULE_OFF));
        if (travelling) return PositionedMsg.same(Msg.of(TravelText.START_ALREADY_TRAVELLING));
        Msg sweepRunning = netherSweepRejection();
        if (sweepRunning != null) return PositionedMsg.same(sweepRunning);
        if (mc.player == null || mc.world == null) return PositionedMsg.same(Msg.of(TravelText.START_NO_WORLD));
        if (!mc.player.isAlive()) {
            // Without this, every other guard passes from the death screen: the net is armed, the ten
            // preparation commands are sent, and on the next tick onTick sees the dead player and
            // sends the six restoration ones. Fourteen commands and a "Trip launched" for nothing.
            return PositionedMsg.same(Msg.of(TravelText.START_DEAD));
        }
        if (!FabricLoader.getInstance().isModLoaded(BARITONE_MOD_ID)) {
            // BaritoneUtils.IS_AVAILABLE is NOT used on purpose: Meteor sets it to true after a
            // Class.forName("baritone.api.BaritoneAPI") on a class the obfuscated jar does not
            // expose, so there it is false even though Baritone is perfectly installed (spec §2).
            return PositionedMsg.same(Msg.of(TravelText.START_NO_BARITONE));
        }
        if (InvUtils.find(Items.FIREWORK_ROCKET).count() == 0) {
            // And it is also what keeps checkFireworks()'s warning honest: always taking off with
            // some, "you ran OUT of fireworks mid-flight" can only be said when they really ran out
            // mid-flight.
            return PositionedMsg.same(Msg.of(TravelText.START_NO_FIREWORKS));
        }
        if (!wearsElytra()) {
            // elytra-replace does not cover this: its policy answers NOT_WEARING when the chest slot
            // holds no elytra and does nothing, on purpose -putting an elytra on for you is not its
            // job-.
            return PositionedMsg.same(Msg.of(TravelText.START_NO_ELYTRA));
        }
        Msg chestSwapRejection = chestSwapRejection();
        if (chestSwapRejection != null) return PositionedMsg.same(chestSwapRejection);

        String launchPrefix = prefix.get();
        Msg prefixRejection = SafetyNet.prefixRejection(launchPrefix);
        if (prefixRejection != null) {
            // Checked here, before arming the net and before the first command: arming it over a
            // useless prefix is having a net without knowing what it watches.
            return PositionedMsg.same(Msg.of(TravelText.NOT_FLYING, "reason", prefixRejection));
        }

        Waypoint origin = new Waypoint(mc.player.getX(), mc.player.getZ());
        Route route = RoutePlanner.plan(origin, destination(), pattern.get(), params(), highwayMaxAmplitude.get(),
            waypointMargin.get());
        if (route.isRejected()) return PositionedMsg.same(Msg.of(TravelText.NOT_FLYING, "reason", route.rejection()));

        activePrefix = launchPrefix;
        waypoints = route.waypoints();
        index = 0;
        travelling = true;
        stallWatch.reset();
        // The threshold is a setting: it is taken at take-off, so that it does not change mid-flight.
        fireworkWatch = new FireworkWatch(fireworkThreshold.get());

        // The preparation order is spec §6.2's: the net BEFORE the first command is sent.
        armNet();
        prepare();

        // And one last check before sending the #elytra, because preparing moves armour: turning off
        // elytra-fly with chest-swap on Always puts the chestplate where the elytra was. That is
        // rejected above, before touching anything, so it should no longer be able to happen here;
        // this is the net in case some other module takes the elytra between one line and the next.
        // Launching now would mean saying "Trip launched" and finding out thirty seconds later from
        // the stall cut.
        if (!wearsElytra()) return PositionedMsg.same(undoLaunch());

        aimAtCurrentWaypoint();

        Waypoint target = waypoints.get(waypoints.size() - 1);
        long distance = Math.round(origin.distanceTo(target));
        return new PositionedMsg(
            Msg.of(TravelText.LAUNCHED, "pattern", pattern.get().name(), "count", waypoints.size(),
                "x", Math.round(target.x()), "z", Math.round(target.z()), "distance", distance),
            Msg.of(TravelText.LAUNCHED_NO_POSITION, "pattern", pattern.get().name(), "count", waypoints.size(),
                "distance", distance));
    }

    /**
     * The reason why flying is not possible with {@code nether-sweep} sweeping, or {@code null} if
     * it is not.
     *
     * <p><b>Both modules direct the same Baritone through the same commands</b>, and neither asked
     * about the other even though they are used on the same trip -you fly to the area with this one
     * and sweep on arrival-. What happens if they overlap, in order: {@code #goal} only takes one
     * goal, so the second to launch keeps Baritone; the first sees its distance to its own goal
     * grow, after 45 s its stall watch fires and it sends its {@code #cancel} and its whole
     * restoration, which <b>stops the second one's flight mid-way</b> and also puts {@code
     * elytraFireworkSpeed} back to a resting value different from the one it thinks it is using; the
     * second notices nothing and 45 s later diagnoses a stall that does not exist. And since each
     * borrows {@code elytra-fly} and {@code elytra-replace} with its own {@link BorrowedModule}, the
     * second notes down as "the player's resting state" the state the first left, and leaves it
     * there when it ends.
     *
     * <p>It is symmetric: the same guard is in {@code NetherSweep.start()} looking this way.
     */
    private Msg netherSweepRejection() {
        NetherSweep sweep = Modules.get().get(NetherSweep.class);
        if (sweep == null || !sweep.isSweeping()) return null;

        return Msg.of(TravelText.SWEEP_RUNNING);
    }

    /** Whether the chest slot holds an elytra, which is the only thing Baritone can fly with. */
    private boolean wearsElytra() {
        return mc.player != null && mc.player.getEquippedStack(EquipmentSlot.CHEST).getItem() == Items.ELYTRA;
    }

    /**
     * The reason why it cannot launch with {@code elytra-fly}'s {@code chest-swap} configured, or
     * {@code null} if there is no conflict.
     *
     * <p>Checked in the {@code meteor-client:1.21.11-SNAPSHOT} sources: {@code
     * ElytraFly.onDeactivate()} calls {@code ChestSwap.swap()} if {@code chest-swap} is on {@code
     * Always} and you are wearing the elytra -it swaps you to the chestplate-, and if it is on {@code
     * WaitForGround} it subscribes a listener that makes that same swap <b>as soon as you touch the
     * ground</b>. The preparation turns {@code elytra-fly} off as its first step and two lines later
     * sends {@code #elytra}: with {@code Always} Baritone cannot fly because you are no longer
     * wearing an elytra, and with {@code WaitForGround} it takes it off you on landing, when the
     * module believes it has restored everything.
     *
     * <p>It is rejected instead of worked around, and rejected <b>before</b> arming the net and
     * sending a single command (spec §9.2). Working around it would mean setting {@code chest-swap}
     * to {@code Never} behind the player's back and giving it back later, which is one more setting
     * Meteor persists to disk and that would be lost with the client if the session is cut: exactly
     * the kind of trail this module exists not to leave.
     *
     * <p>Out of the box {@code chest-swap} is {@code Never}, so this only happens to whoever
     * configured it -which is precisely the profile that uses this module-.
     */
    private Msg chestSwapRejection() {
        ElytraFly module = Modules.get().get(ElytraFly.class);
        // If it is not on, the preparation does not turn it off, and without turning it off there is no armour swap.
        if (module == null || !module.isActive()) return null;

        ElytraFly.ChestSwapMode mode = module.chestSwap.get();
        if (mode == ElytraFly.ChestSwapMode.Never) return null;

        Msg consequence = mode == ElytraFly.ChestSwapMode.Always
            ? Msg.of(TravelText.CHEST_SWAP_ALWAYS, "prefix", prefix.get())
            : Msg.of(TravelText.CHEST_SWAP_WAIT_FOR_GROUND);

        return Msg.of(TravelText.CHEST_SWAP_REJECTED, "mode", mode.toString(), "consequence", consequence);
    }

    /**
     * Undoes a preparation that can no longer end in flight and answers why. {@link
     * #finish(TravelText, boolean)} is not called on purpose: here there is no trip to end -not a
     * single {@code goal} nor {@code elytra} was sent-, and saying "trip finished" for something that
     * never started is the same confusion this module avoids in the restoration.
     */
    private Msg undoLaunch() {
        // No need to check whether a module was left pending: this only ever runs from start(), with
        // the world loaded, which is exactly when they can be touched.
        travelling = false;
        SafetyNet.Restoration undone = restore();
        Msg pending = undone.warning(activePrefix);

        if (pending == null) return Msg.of(TravelText.UNDONE);

        loudToast(Msg.of(TravelText.TOAST_UNDONE_NOT_RESTORED), Items.BARRIER);
        return Msg.of(TravelText.UNDONE_NOT_RESTORED, "pending", pending);
    }

    /** Exit 2: the player cancels. Returns the message the command has to show. */
    public Msg stop() {
        if (!travelling) return Msg.of(TravelText.STOP_NOT_TRAVELLING);
        // Whatever the command answers cannot claim more than what just happened: if the restoration
        // did not arrive, finish() has already said it in full and here it is only wrapped up
        // without repeating it.
        if (finish(TravelText.REASON_CANCELLED, false).arrived()) return Msg.of(TravelText.STOP_RESTORED);
        return Msg.of(TravelText.STOP_NOT_RESTORED);
    }

    public boolean isTravelling() {
        return travelling;
    }

    /** Where the trip is, without coordinates: waypoint, total and blocks left. */
    public Optional<GameSnapshot.Progress> progress() {
        if (!travelling || mc.player == null || waypoints.isEmpty()) return Optional.empty();
        Waypoint here = new Waypoint(mc.player.getX(), mc.player.getZ());
        return Optional.of(new GameSnapshot.Progress(index + 1, waypoints.size(),
            Math.round(RoutePlanner.remainingBlocks(waypoints, index, here))));
    }

    @Override
    public String activity() {
        return travelling
            ? Texts.render(TravelText.NOW_WAYPOINT, "index", index + 1, "total", waypoints.size())
            : Texts.render(TravelText.NOW_ARMED);
    }

    /**
     * Preparation of spec §6.2, steps 5 and 6: the two modules and, in one piece, the sequence of
     * Baritone settings. All the {@code #set} go out together on purpose -{@code elytraAutoSwap}
     * included-: it is the sequence the core builds and tests as one thing, and splitting it to put
     * one of our modules being turned on in the middle changes nothing observable.
     */
    private void prepare() {
        // Before noting anything down, give back whatever was left pending from a previous trip:
        // otherwise what would be noted down as "the player's resting state" would be the state that
        // trip left, not theirs.
        applyPendingModules();
        takeModule(Modules.get().get(ElytraFly.class), elytraFly);
        takeModule(Modules.get().get(ElytraReplace.class), elytraReplace);
        for (String command : BaritoneScript.preparation(activePrefix, flightSettings())) send(command);
    }

    /** Sets the current waypoint and relaunches the flight: Baritone takes the goal on starting, not afterwards. */
    private void aimAtCurrentWaypoint() {
        send(BaritoneScript.goTo(activePrefix, waypoints.get(index)));
        send(BaritoneScript.launch(activePrefix));
    }

    /**
     * The one ending of the six exit paths (spec §6.3). It is idempotent: whoever arrives second does
     * nothing, which is exactly what is needed when turning the module off and leaving the world
     * overlap.
     *
     * @return what really happened with the restoration, for whoever has to answer something
     *         afterwards. Whoever arrives second restores nothing and answers {@code DELIVERED}: the
     *         first already said whatever had to be said, and repeating it would show the same
     *         warning twice.
     */
    private SafetyNet.Restoration finish(TravelText reason, boolean warn) {
        if (!travelling) return SafetyNet.Restoration.DELIVERED;
        travelling = false;

        SafetyNet.Restoration restoration = restore();
        Msg pending = restoration.warning(activePrefix);
        warnPendingModules();

        if (pending == null) {
            Msg message = Msg.of(TravelText.FINISHED, "reason", reason);
            if (warn) warning(message);
            else if (notify.get()) info(message);
            return restoration;
        }

        // Sending is not arriving, and saying "environment restored" when nothing arrived is the
        // module's most expensive lie: the player believes they have landed and Baritone keeps
        // flying. It always goes out, whether notifications were asked for or not, and loud: it is
        // the worst state this module can leave you in.
        warning(TravelText.FINISHED_NOT_RESTORED, "reason", reason, "pending", pending);
        loudToast(Msg.of(TravelText.TOAST_FINISHED_NOT_RESTORED), Items.BARRIER);
        return restoration;
    }

    /**
     * Puts the five points that were touched back to their resting state (spec §6.3) and says
     * whether they really arrived. The net is disarmed last, when not a single command is left to
     * send: disarming it earlier would leave the {@code cancel} and the restoration uncovered, which
     * is exactly when the most commands go out at once. And it is in a {@code finally} because an
     * armed net that survived an exception would silently eat every command the player typed by
     * hand.
     */
    private SafetyNet.Restoration restore() {
        try {
            SafetyNet.Restoration outcome;
            if (mc.player == null) {
                outcome = SafetyNet.Restoration.NO_PLAYER;
            }
            else {
                // The && goes last on purpose: first it is sent, always, and then accumulated. With
                // the condition first, the first cancelled command would take the next five down with
                // it.
                boolean delivered = send(BaritoneScript.cancel(activePrefix));
                for (String command : BaritoneScript.restoration(activePrefix, restingSettings())) {
                    delivered = send(command) && delivered;
                }
                outcome = delivered ? SafetyNet.Restoration.DELIVERED : SafetyNet.Restoration.CANCELLED;
            }

            // The two modules are ours and do not travel through the chat: they are given back
            // whether there is a player or not, and what happens to them does not change the
            // commands' verdict. The only thing that holds them back is the teardown on leaving the
            // world (spec §6.4): there the give-back stays pending and the first tick of the next
            // join does it.
            releaseModule(Modules.get().get(ElytraFly.class), elytraFly);
            releaseModule(Modules.get().get(ElytraReplace.class), elytraReplace);

            return outcome;
        }
        finally {
            disarmNet();
            resetTrip();
        }
    }

    /**
     * The warning about the modules that could not be given back yet (spec §6.4). It goes out loud
     * and with a toast, not as an ordinary chat line, for two reasons: <i>"landing with ElytraFly
     * off without knowing it is as bad as the original problem"</i> (spec §6.3), and because the
     * only moment this happens is on leaving the world, where the chat goes away with the
     * disconnection and the only thing the player gets to read is the toast.
     */
    private void warnPendingModules() {
        Object names;
        if (elytraFly.hasPending() && elytraReplace.hasPending()) {
            names = Msg.of(TravelText.MODULES_BOTH, "first", elytraFly.name(), "second", elytraReplace.name());
        } else if (elytraFly.hasPending()) {
            names = elytraFly.name();
        } else if (elytraReplace.hasPending()) {
            names = elytraReplace.name();
        } else {
            return;
        }

        Msg message = Msg.of(TravelText.MODULES_LEFT_AS_IN_FLIGHT, "modules", names);
        warning(message);
        loudToast(message, Items.ELYTRA);
    }

    private void resetTrip() {
        travelling = false;
        waypoints = List.of();
        index = 0;
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

    /** Notes down how the module is and leaves it in its flight state. */
    private static void takeModule(Module module, BorrowedModule loan) {
        if (module == null) {
            // What cannot be read is not noted down, and what was not taken is not given back.
            loan.forget();
            return;
        }
        apply(module, loan.take(module.isActive()));
    }

    /** Gives the module back to where it was, or leaves the give-back pending if it cannot be touched. */
    private void releaseModule(Module module, BorrowedModule loan) {
        if (module == null) {
            loan.forget();
            return;
        }
        apply(module, loan.release(module.isActive(), !leavingWorld));
    }

    /**
     * Does whatever was left pending from the teardown on leaving the world. It is idempotent and
     * cheap: with nothing pending it does not even query the module registry, which is what allows
     * calling it every tick.
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
     * Sends a command through the player's chat. Path checked against the {@code
     * meteor-client:1.21.11-SNAPSHOT} sources: {@code ChatUtils.sendPlayerMsg} sends the text "as if
     * the user had typed it in the chat", which is exactly what Baritone listens to. {@code
     * addToHistory = false} is passed so as not to fill the chat history with hash commands, where
     * the up key would leave them one enter away from being published.
     *
     * <p>Without a player nothing is sent: {@code sendPlayerMsg} dereferences it without checking.
     *
     * @return whether the command left the client towards Baritone. {@code false} means the net had
     *         to cancel it -Baritone did not intercept it, so it had nobody to reach- or that there
     *         was no player. The whole path is synchronous: {@code sendPlayerMsg} ends in {@code
     *         ClientConnection.send}, where the mixin posts the event and our listener answers
     *         before this call returns, so {@link #sendCaught} is already settled here.
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

    /**
     * The destination handed to the core, read from the settings of the current mode.
     *
     * <p><b>Each mode reads its own settings</b>, and that is why {@code offset-x} and {@code
     * offset-z} exist instead of reusing {@code x} and {@code z} with another meaning. With a single
     * pair of fields, whoever had a far absolute destination set and switched to RELATIVE would find
     * those numbers turned into an offset from where they are: the same setting, untouched, would
     * mean something else, and the trip would look like neither of the two the player ever asked
     * for. Here switching mode reinterprets no number: it reads others.
     */
    private Destination destination() {
        return switch (destinationMode.get()) {
            case HIGHWAY -> Destination.highway(axis.get(), highwayDistance.get());
            case RELATIVE -> Destination.relative(offsetX.get(), offsetZ.get());
            case COORDINATES -> Destination.coordinates(destinationX.get(), destinationZ.get());
        };
    }

    private PatternParams params() {
        return new PatternParams(amplitude.get(), period.get(), legLength.get(), lateralOffset.get(),
            spiralRadius.get(), spiralTurns.get(), decoyAngle.get(), decoyFraction.get());
    }

    private BaritoneScript.FlightSettings flightSettings() {
        return new BaritoneScript.FlightSettings(autoJump.get(), allowEmergencyLand.get(),
            conserveFireworks.get(), fireworkSpeed.get(), netherSeed.get().strip());
    }

    private BaritoneScript.FlightSettings restingSettings() {
        // The seed is not restored: it was never a change of ours, only a piece of data handed to
        // Baritone if we had it, and the core does not write it in the restoration.
        return new BaritoneScript.FlightSettings(autoJumpResting.get(), allowEmergencyLandResting.get(),
            conserveFireworksResting.get(), fireworkSpeedResting.get(), "");
    }

    /** The visual half of a loud warning: the toast that goes with the chat. */
    private void loudToast(Msg message, Item icon) {
        MeteorToast.Builder toast = new MeteorToast.Builder("Xploits").text(Texts.render(message)).icon(icon);
        // MeteorToast.update() calls play(customSound) without a null check and vanilla dereferences it:
        // NPE on the render thread. Never pass null; it is silenced with zero volume, same as ElytraReplace.
        if (!notifySound.get()) {
            toast.sound(PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), 1.2f, 0f));
        }
        mc.getToastManager().add(toast.build());
    }

    public PositionedMsg status() {
        return new PositionedMsg(status(true), status(false));
    }

    private Msg status(boolean withPosition) {
        if (!isActive()) return Msg.of(TravelText.STATUS_OFF);
        if (!travelling) {
            return Msg.of(TravelText.STATUS_IDLE, "pattern", pattern.get().name(),
                "destination", withPosition ? describeDestination() : describeDestinationWithoutPosition());
        }

        Waypoint target = waypoints.get(index);
        Msg position = withPosition
            ? Msg.of(TravelText.STATUS_AT, "x", Math.round(target.x()), "z", Math.round(target.z()))
            : Msg.of(TravelText.NOTHING);
        Msg progress = Msg.of(TravelText.NOTHING);
        if (mc.player != null) {
            Waypoint here = new Waypoint(mc.player.getX(), mc.player.getZ());
            progress = Msg.of(TravelText.STATUS_PROGRESS, "distance", Math.round(here.distanceTo(target)),
                "left", waypoints.size() - index - 1);
        }
        return Msg.of(TravelText.STATUS_FLYING, "pattern", pattern.get().name(), "index", index + 1,
            "total", waypoints.size(), "position", position, "progress", progress,
            "net", netArmed ? TravelText.NET_ARMED : TravelText.NET_DISARMED,
            "caught", netCaughtWarned ? TravelText.STATUS_NET_CAUGHT : TravelText.NOTHING);
    }

    private Msg describeDestination() {
        return switch (destinationMode.get()) {
            case HIGHWAY -> Msg.of(TravelText.DESTINATION_HIGHWAY, "distance", Math.round(highwayDistance.get()),
                "axis", axis.get().name());
            // The offset is stated as it is, with its sign and saying that it is an offset: if it were
            // resolved to coordinates here, the mode that exists so as not to write the destination
            // anywhere would be writing it in the chat.
            case RELATIVE -> Msg.of(TravelText.DESTINATION_RELATIVE,
                "dx", String.format("%+d", Math.round(offsetX.get())), "dz", String.format("%+d", Math.round(offsetZ.get())));
            case COORDINATES -> Msg.of(TravelText.DESTINATION_COORDINATES,
                "x", Math.round(destinationX.get()), "z", Math.round(destinationZ.get()));
        };
    }

    /** The destination without coordinates, for the console: in highway mode it no longer has them; otherwise, the distance. */
    private Msg describeDestinationWithoutPosition() {
        return switch (destinationMode.get()) {
            case HIGHWAY -> describeDestination();
            // Only the distance: the offset with its sign gives the heading from a point that can be
            // guessed (the spawn, a highway), and only distances go to the console.
            case RELATIVE -> Msg.of(TravelText.DESTINATION_RELATIVE_DISTANCE,
                "distance", Math.round(Math.hypot(offsetX.get(), offsetZ.get())));
            case COORDINATES -> {
                if (mc.player == null) yield Msg.of(TravelText.DESTINATION_BY_COORDINATES);
                Waypoint here = new Waypoint(mc.player.getX(), mc.player.getZ());
                Waypoint target = new Waypoint(destinationX.get(), destinationZ.get());
                yield Msg.of(TravelText.DESTINATION_BY_COORDINATES_DISTANCE,
                    "distance", Math.round(here.distanceTo(target)));
            }
        };
    }
}
