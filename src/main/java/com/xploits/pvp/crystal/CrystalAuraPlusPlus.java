package com.xploits.pvp.crystal;

import com.xploits.XploitsAddon;
import com.xploits.mixin.XploitsInteractionInvoker;
import com.xploits.pvp.crystal.core.Action;
import com.xploits.pvp.crystal.core.Candidate;
import com.xploits.pvp.crystal.core.CrystalBrain;
import com.xploits.pvp.crystal.core.CrystalSeen;
import com.xploits.pvp.crystal.core.CrystalSetting;
import com.xploits.pvp.crystal.core.CrystalSettings;
import com.xploits.pvp.crystal.core.CrystalSettings.AutoSwitch;
import com.xploits.pvp.crystal.core.CrystalSettings.PauseMode;
import com.xploits.pvp.crystal.core.CrystalText;
import com.xploits.pvp.crystal.core.CrystalTick;
import com.xploits.pvp.crystal.core.Decision;
import com.xploits.pvp.crystal.core.Reach;
import com.xploits.pvp.crystal.core.Reason;
import com.xploits.pvp.crystal.core.Refusal;
import com.xploits.pvp.crystal.core.ServerValues;
import com.xploits.pvp.crystal.core.SwingMode;
import com.xploits.pvp.crystal.core.TargetView;
import com.xploits.shared.Texts;
import com.xploits.shared.XploitsModule;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.entity.EntityRemovedEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ModuleListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.BedAura;
import meteordevelopment.meteorclient.systems.modules.combat.CrystalAura;
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.Target;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockIterator;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.component.type.AttributeModifierSlot;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * crystal-aura++ (spec §2): Meteor's CrystalAura with its default settings, measured with Meteor's own public
 * utilities, decided by {@link CrystalBrain} (Meteor's rules plus the self-damage budget), and acted on exactly
 * as Meteor acts (line numbers from {@code CrystalAura.java} in the 1.21.11 sources).
 *
 * <p>The game's order, as the brain expects it:
 * <ul>
 *   <li>{@code TickEvent.Pre} at HIGH: measure (health, pauses, hands, targets, every crystal), then
 *   {@link CrystalBrain#breakPhase}; if {@link CrystalBrain#wantsPlacement()}, register the
 *   {@code BlockIterator} scan (lines 931-932), which reports the spots and, in {@code BlockIterator.after},
 *   hands them to {@link CrystalBrain#placePhase} with the health read during the scan;</li>
 *   <li>each action through {@code Rotations.rotate(..., 50, callback)} with {@code rotate} on, at once
 *   otherwise; {@link CrystalBrain#attackSent()} and {@link CrystalBrain#placed} when the packet goes out;</li>
 *   <li>{@code EntityAddedEvent} / {@code EntityRemovedEvent} for end crystals: ownership, fast-break and the
 *   in-flight ledger;</li>
 *   <li>{@code TickEvent.Pre} at {@code LOWEST - 666}: Meteor's last-rotation hold (lines 722-727).</li>
 * </ul>
 *
 * <p>While Meteor's crystal-aura is on it does nothing, warns once each time that starts and never turns
 * itself off (Q3); when Meteor's is off again it starts afresh, as on activation, because Meteor's aura acted
 * on the crystals in the meantime. It never touches Meteor's CrystalAura: it only asks whether it is on.
 */
public class CrystalAuraPlusPlus extends XploitsModule {
    // Meteor's settings that ++ fixes at their defaults (spec §2, Q6). The brain assumes these values; the
    // ones with a natural place here are used where Meteor uses the setting.
    /** {@code support}: Disabled. Only obsidian and bedrock bases. */
    static final boolean SUPPORT = false;
    /** {@code 1.12-placement}: off. One block of air above the base, a box two blocks tall. */
    static final boolean PLACEMENT_112 = false;
    /** {@code yaw-steps}: 180, so every rotation is done in one step and the yaw-steps check never holds one back. */
    static final double YAW_STEPS = 180;
    /** {@code yaw-steps-mode}: Break (with 180 degrees it never matters). */
    static final String YAW_STEPS_MODE = "Break";
    /** {@code predict-movement}: off. */
    static final boolean PREDICT_MOVEMENT = false;
    /** {@code smart-delay}: off. */
    static final boolean SMART_DELAY = false;
    /** {@code place-delay}, {@code break-delay} and {@code switch-delay}: 0 ticks. */
    static final int PLACE_DELAY = 0;
    static final int BREAK_DELAY = 0;
    static final int SWITCH_DELAY = 0;
    /** {@code only-own}: off. */
    static final boolean ONLY_OWN = false;
    /** {@code face-place-missing-armor}: off. */
    static final boolean FACE_PLACE_MISSING_ARMOR = false;
    /** {@code ignore-nakeds}: off. */
    static final boolean IGNORE_NAKEDS = false;
    /** {@code ticks-existed}: 0. */
    static final int TICKS_EXISTED = 0;
    /** {@code force-face-place}: no key. */
    static final boolean FORCE_FACE_PLACE = false;
    /** {@code entities}: players only (§1; Meteor's default also has the warden and the wither). */
    static final Set<EntityType<?>> ENTITIES = Set.of(EntityType.PLAYER);
    /** Render: off. */
    static final boolean RENDER = false;

    /** Meteor's rotation priority for breaking and placing (lines 852, 998). */
    private static final int ROTATION_PRIORITY = 50;
    /** Meteor's priority for the last-rotation hold (line 726). */
    private static final int HOLD_ROTATION_PRIORITY = -100;
    /** Pause-on-lag: this long since the last server tick (line 1158). */
    private static final float LAG_SECONDS = 1.0f;

    // Every setting's name and group come from CrystalSetting, the list the core's coverage test holds the
    // module to; the bench checks that the module shows exactly that list.
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPlace = settings.createGroup(CrystalSetting.Group.PLACE.title());
    private final SettingGroup sgBreak = settings.createGroup(CrystalSetting.Group.BREAK.title());
    private final SettingGroup sgPause = settings.createGroup(CrystalSetting.Group.PAUSE.title());
    private final SettingGroup sgSafety = settings.createGroup(CrystalSetting.Group.SAFETY.title());

    // General

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name(CrystalSetting.TARGET_RANGE.id())
        .description(Texts.startupText(CrystalSetting.TARGET_RANGE.text()))
        .defaultValue(10)
        .min(0)
        .sliderMax(16)
        .build()
    );

    private final Setting<Double> minDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name(CrystalSetting.MIN_DAMAGE.id())
        .description(Texts.startupText(CrystalSetting.MIN_DAMAGE.text()))
        .defaultValue(6)
        .min(0)
        .build()
    );

    private final Setting<Double> maxDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name(CrystalSetting.MAX_DAMAGE.id())
        .description(Texts.startupText(CrystalSetting.MAX_DAMAGE.text()))
        .defaultValue(6)
        .range(0, 36)
        .sliderMax(36)
        .build()
    );

    private final Setting<Boolean> antiSuicide = sgGeneral.add(new BoolSetting.Builder()
        .name(CrystalSetting.ANTI_SUICIDE.id())
        .description(Texts.startupText(CrystalSetting.ANTI_SUICIDE.text()))
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name(CrystalSetting.ROTATE.id())
        .description(Texts.startupText(CrystalSetting.ROTATE.text()))
        .defaultValue(true)
        .build()
    );

    private final Setting<AutoSwitch> autoSwitch = sgGeneral.add(new EnumSetting.Builder<AutoSwitch>()
        .name(CrystalSetting.AUTO_SWITCH.id())
        .description(Texts.startupText(CrystalSetting.AUTO_SWITCH.text()))
        .defaultValue(AutoSwitch.NORMAL)
        .build()
    );

    private final Setting<Boolean> noGapSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name(CrystalSetting.NO_GAP_SWITCH.id())
        .description(Texts.startupText(CrystalSetting.NO_GAP_SWITCH.text()))
        .defaultValue(true)
        .visible(() -> autoSwitch.get() == AutoSwitch.NORMAL)
        .build()
    );

    private final Setting<Boolean> noBowSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name(CrystalSetting.NO_BOW_SWITCH.id())
        .description(Texts.startupText(CrystalSetting.NO_BOW_SWITCH.text()))
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> antiWeakness = sgGeneral.add(new BoolSetting.Builder()
        .name(CrystalSetting.ANTI_WEAKNESS.id())
        .description(Texts.startupText(CrystalSetting.ANTI_WEAKNESS.text()))
        .defaultValue(true)
        .build()
    );

    private final Setting<SwingMode> swingMode = sgGeneral.add(new EnumSetting.Builder<SwingMode>()
        .name(CrystalSetting.SWING_MODE.id())
        .description(Texts.startupText(CrystalSetting.SWING_MODE.text()))
        .defaultValue(SwingMode.BOTH)
        .build()
    );

    // Place

    private final Setting<Boolean> place = sgPlace.add(new BoolSetting.Builder()
        .name(CrystalSetting.PLACE.id())
        .description(Texts.startupText(CrystalSetting.PLACE.text()))
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> placeRange = sgPlace.add(new DoubleSetting.Builder()
        .name(CrystalSetting.PLACE_RANGE.id())
        .description(Texts.startupText(CrystalSetting.PLACE_RANGE.text()))
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Double> placeWallsRange = sgPlace.add(new DoubleSetting.Builder()
        .name(CrystalSetting.PLACE_WALLS_RANGE.id())
        .description(Texts.startupText(CrystalSetting.PLACE_WALLS_RANGE.text()))
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Boolean> facePlace = sgPlace.add(new BoolSetting.Builder()
        .name(CrystalSetting.FACE_PLACE.id())
        .description(Texts.startupText(CrystalSetting.FACE_PLACE.text()))
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> facePlaceHealth = sgPlace.add(new DoubleSetting.Builder()
        .name(CrystalSetting.FACE_PLACE_HEALTH.id())
        .description(Texts.startupText(CrystalSetting.FACE_PLACE_HEALTH.text()))
        .defaultValue(8)
        .min(1)
        .sliderMin(1)
        .sliderMax(36)
        .visible(() -> facePlace.get())
        .build()
    );

    private final Setting<Double> facePlaceDurability = sgPlace.add(new DoubleSetting.Builder()
        .name(CrystalSetting.FACE_PLACE_DURABILITY.id())
        .description(Texts.startupText(CrystalSetting.FACE_PLACE_DURABILITY.text()))
        .defaultValue(2)
        .min(1)
        .sliderMin(1)
        .sliderMax(100)
        .visible(() -> facePlace.get())
        .build()
    );

    // Break

    private final Setting<Boolean> breakCrystals = sgBreak.add(new BoolSetting.Builder()
        .name(CrystalSetting.BREAK.id())
        .description(Texts.startupText(CrystalSetting.BREAK.text()))
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> breakRange = sgBreak.add(new DoubleSetting.Builder()
        .name(CrystalSetting.BREAK_RANGE.id())
        .description(Texts.startupText(CrystalSetting.BREAK_RANGE.text()))
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Double> breakWallsRange = sgBreak.add(new DoubleSetting.Builder()
        .name(CrystalSetting.BREAK_WALLS_RANGE.id())
        .description(Texts.startupText(CrystalSetting.BREAK_WALLS_RANGE.text()))
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Integer> breakAttempts = sgBreak.add(new IntSetting.Builder()
        .name(CrystalSetting.BREAK_ATTEMPTS.id())
        .description(Texts.startupText(CrystalSetting.BREAK_ATTEMPTS.text()))
        .defaultValue(2)
        .min(0)
        .sliderMin(1)
        .sliderMax(5)
        .build()
    );

    private final Setting<Integer> attackFrequency = sgBreak.add(new IntSetting.Builder()
        .name(CrystalSetting.ATTACK_FREQUENCY.id())
        .description(Texts.startupText(CrystalSetting.ATTACK_FREQUENCY.text()))
        .defaultValue(25)
        .min(1)
        .sliderRange(1, 30)
        .build()
    );

    private final Setting<Boolean> fastBreak = sgBreak.add(new BoolSetting.Builder()
        .name(CrystalSetting.FAST_BREAK.id())
        .description(Texts.startupText(CrystalSetting.FAST_BREAK.text()))
        .defaultValue(true)
        .build()
    );

    // Pause

    private final Setting<PauseMode> pauseOnUse = sgPause.add(new EnumSetting.Builder<PauseMode>()
        .name(CrystalSetting.PAUSE_ON_USE.id())
        .description(Texts.startupText(CrystalSetting.PAUSE_ON_USE.text()))
        .defaultValue(PauseMode.PLACE)
        .build()
    );

    private final Setting<PauseMode> pauseOnMine = sgPause.add(new EnumSetting.Builder<PauseMode>()
        .name(CrystalSetting.PAUSE_ON_MINE.id())
        .description(Texts.startupText(CrystalSetting.PAUSE_ON_MINE.text()))
        .defaultValue(PauseMode.NONE)
        .build()
    );

    private final Setting<Boolean> pauseOnLag = sgPause.add(new BoolSetting.Builder()
        .name(CrystalSetting.PAUSE_ON_LAG.id())
        .description(Texts.startupText(CrystalSetting.PAUSE_ON_LAG.text()))
        .defaultValue(true)
        .build()
    );

    @SuppressWarnings("unchecked")
    private final Setting<List<Module>> pauseModules = sgPause.add(new ModuleListSetting.Builder()
        .name(CrystalSetting.PAUSE_MODULES.id())
        .description(Texts.startupText(CrystalSetting.PAUSE_MODULES.text()))
        .defaultValue(BedAura.class)
        .build()
    );

    private final Setting<Double> pauseHealth = sgPause.add(new DoubleSetting.Builder()
        .name(CrystalSetting.PAUSE_HEALTH.id())
        .description(Texts.startupText(CrystalSetting.PAUSE_HEALTH.text()))
        .defaultValue(5)
        .range(0, 36)
        .sliderRange(0, 36)
        .build()
    );

    // Safety

    private final Setting<Boolean> selfBudget = sgSafety.add(new BoolSetting.Builder()
        .name(CrystalSetting.SELF_BUDGET.id())
        .description(Texts.startupText(CrystalSetting.SELF_BUDGET.text()))
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> reserve = sgSafety.add(new DoubleSetting.Builder()
        .name(CrystalSetting.RESERVE.id())
        .description(Texts.startupText(CrystalSetting.RESERVE.text()))
        .defaultValue(CrystalSettings.defaults().reserve())
        .min(2)
        .sliderRange(2, 20)
        .visible(() -> selfBudget.get())
        .build()
    );

    private final Setting<Double> safeSelfDamage = sgSafety.add(new DoubleSetting.Builder()
        .name(CrystalSetting.SAFE_SELF_DAMAGE.id())
        .description(Texts.startupText(CrystalSetting.SAFE_SELF_DAMAGE.text()))
        .defaultValue(CrystalSettings.defaults().safeSelfDamage())
        .min(0)
        .sliderMax(2)
        .visible(() -> selfBudget.get())
        .build()
    );

    private final Refusal refusal = new Refusal();
    private CrystalBrain brain = new CrystalBrain();
    /** Late own crystals counted by brains replaced after a refusal, since activation. */
    private int lateOwnBefore;
    /** This activation's pre-tick number. */
    private long tick;

    /** Your eyes at this pre-tick, for the range raycasts (Meteor sets them at HIGH, line 711). */
    private Vec3d eyePos = Vec3d.ZERO;
    /** This pre-tick's targets, by the name the brain knows them by, in the world's entity order. */
    private final Map<String, PlayerEntity> targets = new LinkedHashMap<>();
    /** This pre-tick's end crystals, by id. */
    private final Map<Integer, EndCrystalEntity> crystals = new HashMap<>();
    /** Where the last rotation looked, for Meteor's last-rotation hold (lines 755-766). */
    private Vec3d lastRotationPos;

    public CrystalAuraPlusPlus() {
        super(XploitsAddon.CATEGORY, "crystal-aura++", Texts.startupText(CrystalText.MODULE_DESC));
    }

    @Override
    public void onActivate() {
        brain = new CrystalBrain();
        lateOwnBefore = 0;
        tick = 0;
        refusal.update(false);
        forgetTick();
        lastRotationPos = null;
        refusingNow();
    }

    @Override
    public void onDeactivate() {
        forgetTick();
        lastRotationPos = null;
        refusal.update(false);
    }

    // What the rest of Xploits reads

    /**
     * Q3: true while ++ refuses because Meteor's crystal-aura is on, or while this pre-tick something passed
     * Meteor's checks and the budget refused all of it.
     */
    public boolean holding() {
        return refusal.refusing() || brain.holding();
    }

    /** Our crystals that appeared after their pending placement had expired (Q2), since activation. */
    public int lateOwnCrystals() {
        return lateOwnBefore + brain.lateOwnCrystals();
    }

    /** The last thing decided, or why nothing was. */
    public Decision lastDecision() {
        return refusal.refusing() ? Decision.none(Reason.METEOR_AURA_ON) : brain.lastDecision();
    }

    // Events

    @EventHandler(priority = EventPriority.HIGH)
    private void onPreTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        if (refusingNow()) return;
        // Our health not a valid number (a broken or spoofing server): do nothing this tick.
        OptionalDouble health = health();
        if (health.isEmpty()) return;
        tick++;
        ClientPlayerEntity p = mc.player;
        eyePos = new Vec3d(p.getEntityPos().x, p.getEntityPos().y + p.getEyeHeight(p.getPose()), p.getEntityPos().z);

        List<TargetView> seen = measureTargets();
        List<CrystalSeen> standing = new ArrayList<>();
        EndCrystalEntity first = null;
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof EndCrystalEntity crystal)) continue;
            crystals.put(crystal.getId(), crystal);
            standing.add(measure(crystal, targets.values()));
            if (first == null) first = crystal;
        }

        CrystalTick measured = new CrystalTick(tick, health.getAsDouble(), totems(), usingItem(), mc.interactionManager.isBreakingBlock(),
            TickRate.INSTANCE.getTimeSinceLastTick() >= LAG_SECONDS, pauseModuleActive(), hands(first), seen, standing, List.of());
        CrystalSettings settings = settingsNow();
        CrystalBrain b = brain;
        b.breakPhase(settings, measured).ifPresent(a -> execute(a, crystals::get));
        if (b.wantsPlacement()) scan(b, tick);
    }

    @EventHandler(priority = EventPriority.LOWEST - 666)
    private void onPreTickLast(TickEvent.Pre event) {
        if (mc.player == null || refusal.refusing() || lastRotationPos == null) return;
        if (brain.holdLastRotation()) {
            Rotations.rotate(Rotations.getYaw(lastRotationPos), Rotations.getPitch(lastRotationPos), HOLD_ROTATION_PRIORITY, null);
        }
    }

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (!(event.entity instanceof EndCrystalEntity crystal) || mc.player == null || mc.world == null) return;
        if (refusingNow()) return;
        OptionalDouble health = health();
        if (health.isEmpty()) return;
        // Fast-break measures against the previous pre-tick's targets (lines 740-743).
        List<PlayerEntity> previous = new ArrayList<>();
        for (String name : brain.targets()) {
            PlayerEntity target = targets.get(name);
            if (target != null) previous.add(target);
        }
        CrystalSeen seen = measure(crystal, previous);
        // Meteor reads its settings live here (lines 740-742): a change since the pre-tick already applies.
        brain.crystalAdded(settingsNow(), seen, health.getAsDouble(), hands(crystal)).ifPresent(a -> execute(a, id -> id == crystal.getId() ? crystal : null));
    }

    @EventHandler
    private void onEntityRemoved(EntityRemovedEvent event) {
        if (!(event.entity instanceof EndCrystalEntity crystal) || refusal.refusing()) return;
        brain.crystalRemoved(crystal.getId());
    }

    // Refusal

    /**
     * Whether ++ must do nothing now because Meteor's crystal-aura is on; warns once when that starts, and
     * starts afresh when it ends.
     */
    private boolean refusingNow() {
        switch (refusal.update(Modules.get().get(CrystalAura.class).isActive())) {
            case STARTED -> warning(CrystalText.METEOR_AURA_ON);
            case ENDED -> {
                lateOwnBefore += brain.lateOwnCrystals();
                brain = new CrystalBrain();
                forgetTick();
                lastRotationPos = null;
            }
            case NONE -> {
            }
        }
        return refusal.refusing();
    }

    private void forgetTick() {
        targets.clear();
        crystals.clear();
    }

    // Measuring

    /** The brain's view of the settings now. */
    private CrystalSettings settingsNow() {
        return CrystalSettings.builder()
            .targetRange(targetRange.get())
            .minDamage(minDamage.get())
            .maxDamage(maxDamage.get())
            .antiSuicide(antiSuicide.get())
            .rotate(rotate.get())
            .autoSwitch(autoSwitch.get())
            .noGapSwitch(noGapSwitch.get())
            .noBowSwitch(noBowSwitch.get())
            .antiWeakness(antiWeakness.get())
            .place(place.get())
            .facePlace(facePlace.get())
            .facePlaceHealth(facePlaceHealth.get())
            .facePlaceDurability(facePlaceDurability.get())
            .breakCrystals(breakCrystals.get())
            .breakAttempts(breakAttempts.get())
            .attackFrequency(attackFrequency.get())
            .fastBreak(fastBreak.get())
            .pauseOnUse(pauseOnUse.get())
            .pauseOnMine(pauseOnMine.get())
            .pauseOnLag(pauseOnLag.get())
            .pauseHealth(pauseHealth.get())
            .selfBudget(selfBudget.get())
            .reserve(reserve.get())
            .safeSelfDamage(safeSelfDamage.get())
            .build();
    }

    /**
     * Every other player, in the world's entity order, for the brain to pick its targets from; and, in
     * {@link #targets}, the ones Meteor would target (lines 1222-1253), which the damage is measured against.
     * A player's name for the brain is its UUID, which no two share. A player whose health or distance is not a
     * valid number is left out ({@link ServerValues#target}).
     */
    private List<TargetView> measureTargets() {
        targets.clear();
        crystals.clear();
        List<TargetView> seen = new ArrayList<>();
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof PlayerEntity player) || player == mc.player) continue;
            String name = player.getUuidAsString();
            double squared = player.squaredDistanceTo(mc.player);
            boolean creative = player.getAbilities().creativeMode;
            boolean friend = !Friends.get().shouldAttack(player);
            Optional<TargetView> view = ServerValues.target(name, squared, player.getHealth(),
                player.getAbsorptionAmount(), lowestArmorPercent(player), creative, player.isAlive(), friend);
            if (view.isEmpty()) continue;
            seen.add(view.get());
            if (creative || !player.isAlive() || friend || !ENTITIES.contains(player.getType())) continue;
            if (!Reach.inTargetRange(squared, targetRange.get())) continue;
            targets.put(name, player);
        }
        return seen;
    }

    /**
     * The lowest durability left, in percent, among the worn pieces that can wear out (lines 1136-1145); a
     * piece with no durability never counts for face-placing there (Meteor's division gives NaN).
     */
    private static double lowestArmorPercent(PlayerEntity player) {
        double lowest = TargetView.NO_ARMOR;
        for (EquipmentSlot slot : AttributeModifierSlot.ARMOR) {
            ItemStack stack = player.getEquippedStack(slot);
            if (stack == null || stack.isEmpty()) continue;
            OptionalDouble percent = ServerValues.armorPercent(stack.getMaxDamage(), stack.getDamage());
            if (percent.isPresent()) lowest = Math.min(lowest, percent.getAsDouble());
        }
        return lowest;
    }

    /**
     * One end crystal as Meteor's {@code getBreakDamage} measures it (lines 791-822). Odd damage to us makes it
     * deadly and an odd distance puts it next to us ({@link ServerValues}).
     */
    private CrystalSeen measure(EndCrystalEntity crystal, Iterable<PlayerEntity> against) {
        Vec3d pos = crystal.getEntityPos();
        BlockPos base = crystal.getBlockPos().down();
        ClientPlayerEntity p = mc.player;
        double self = ServerValues.crystalSelfDamage(DamageUtils.crystalDamage(p, pos, PREDICT_MOVEMENT, base));
        double distance = ServerValues.crystalDistance(PlayerUtils.distance(p.getEntityPos().x, p.getEntityPos().y, p.getEntityPos().z, pos.x, pos.y, pos.z));
        return new CrystalSeen(crystal.getId(), base.asLong(), damageTo(against, pos, base), self, distance,
            !outOfRange(pos, crystal.getBlockPos(), false));
    }

    private static Map<String, Double> damageTo(Iterable<PlayerEntity> against, Vec3d pos, BlockPos base) {
        Map<String, Double> damage = new LinkedHashMap<>();
        for (PlayerEntity target : against) {
            damage.put(target.getUuidAsString(), ServerValues.targetDamage(DamageUtils.crystalDamage(target, pos, PREDICT_MOVEMENT, base)));
        }
        return damage;
    }

    /** Meteor's {@code isOutOfRange} (lines 1164-1172): the eye raycast decides between the two ranges. */
    private boolean outOfRange(Vec3d pos, BlockPos blockPos, boolean placing) {
        HitResult result = mc.world.raycast(new RaycastContext(eyePos, pos, RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE, mc.player));
        boolean behindWall = !(result instanceof BlockHitResult hit) || !hit.getBlockPos().equals(blockPos);
        // PlayerUtils.isWithin(pos, r) is this squared distance from the feet <= r * r.
        double squared = PlayerUtils.squaredDistanceTo(pos.x, pos.y, pos.z);
        return Reach.outOfRange(squared, Reach.rangeFor(placing, behindWall, placeRange.get(), placeWallsRange.get(),
            breakRange.get(), breakWallsRange.get()));
    }

    /** Our health plus absorption, or nothing if the server made it an odd number. */
    private OptionalDouble health() {
        return ServerValues.ownHealth(mc.player.getHealth(), mc.player.getAbsorptionAmount());
    }

    private int totems() {
        return InvUtils.find(Items.TOTEM_OF_UNDYING).count();
    }

    /** Pause-on-use (lines 1154-1156). */
    private boolean usingItem() {
        return mc.player.isUsingItem() || mc.options.useKey.isPressed();
    }

    /** Pause-modules (line 1159). */
    private boolean pauseModuleActive() {
        return CrystalTick.anyOn(pauseModules.get(), Module::isActive);
    }

    /**
     * The hands as Meteor reads them (lines 693-694, 826-838, 908-919). Whether an item hurts a crystal under
     * Weakness is asked of {@code against} (Meteor asks it of the crystal it is about to hit; a crystal has no
     * armour, so any one answers the same); with no crystal there is nothing to hit.
     */
    private CrystalTick.Hands hands(EndCrystalEntity against) {
        ClientPlayerEntity p = mc.player;
        Item main = p.getMainHandStack().getItem();
        Item off = p.getOffHandStack().getItem();
        StatusEffectInstance weakness = p.getStatusEffect(StatusEffects.WEAKNESS);
        StatusEffectInstance strength = p.getStatusEffect(StatusEffects.STRENGTH);
        boolean mainBreaks = false;
        boolean hotbarBreaks = false;
        if (weakness != null && against != null) {
            mainBreaks = hurts(p.getMainHandStack(), against);
            hotbarBreaks = InvUtils.findInHotbar(stack -> hurts(stack, against)).found();
        }
        boolean mainCrystals = main == Items.END_CRYSTAL;
        boolean offCrystals = off == Items.END_CRYSTAL;
        return new CrystalTick.Hands(InvUtils.testInHotbar(Items.END_CRYSTAL) || mainCrystals || offCrystals,
            mainCrystals, offCrystals,
            main == Items.GOLDEN_APPLE || off == Items.GOLDEN_APPLE || main == Items.ENCHANTED_GOLDEN_APPLE || off == Items.ENCHANTED_GOLDEN_APPLE,
            main == Items.BOW || off == Items.BOW,
            ServerValues.amplifier(weakness != null, weakness == null ? 0 : weakness.getAmplifier()),
            ServerValues.amplifier(strength != null, strength == null ? 0 : strength.getAmplifier()),
            mainBreaks, hotbarBreaks || mainBreaks);
    }

    /** Meteor's {@code isValidWeaknessItem} (lines 877-879). */
    private boolean hurts(ItemStack stack, Entity crystal) {
        return DamageUtils.getAttackDamage(mc.player, crystal, stack) > 0;
    }

    // Placing: the scan

    /**
     * Meteor's scan (lines 931-978): obsidian or bedrock with air above, in range, reported in
     * {@code BlockIterator}'s order; the brain does every other check. Spots out of range are not reported:
     * the brain would drop them first.
     */
    private void scan(CrystalBrain b, long decidedAt) {
        List<Candidate> spots = new ArrayList<>();
        OptionalDouble[] scanHealth = {null};
        int radius = (int) Math.ceil(placeRange.get());
        BlockIterator.register(radius, radius, (bp, state) -> {
            // Meteor reads your health while checking each spot (line 953).
            scanHealth[0] = health();
            spot(bp, state).ifPresent(spots::add);
        });
        BlockIterator.after(() -> {
            // The refusal first: one ending here replaces the brain, and then this scan is stale.
            if (!isActive() || mc.player == null || refusingNow() || b != brain || decidedAt != tick) return;
            OptionalDouble h = scanHealth[0] != null ? scanHealth[0] : health();
            if (h.isEmpty()) return;
            b.placePhase(h.getAsDouble(), spots).ifPresent(a -> execute(a, crystals::get));
        });
    }

    private Optional<Candidate> spot(BlockPos bp, BlockState state) {
        boolean hasBlock = state.isOf(Blocks.BEDROCK) || state.isOf(Blocks.OBSIDIAN);
        if (!hasBlock) return Optional.empty(); // support is Disabled
        BlockPos above = bp.up();
        if (!mc.world.getBlockState(above).isAir()) return Optional.empty();
        if (PLACEMENT_112 && !mc.world.getBlockState(above.up()).isAir()) return Optional.empty();

        Vec3d pos = new Vec3d(bp.getX() + 0.5, bp.getY() + 1, bp.getZ() + 0.5);
        if (outOfRange(pos, above, true)) return Optional.empty();

        BlockPos base = bp.toImmutable();
        // A spot whose damage to us cannot be measured is not an option (ServerValues).
        OptionalDouble self = ServerValues.spotSelfDamage(DamageUtils.crystalDamage(mc.player, pos, PREDICT_MOVEMENT, base));
        if (self.isEmpty()) return Optional.empty();
        Map<String, Double> damage = damageTo(targets.values(), pos, base);

        double x = bp.getX();
        double y = bp.getY() + 1;
        double z = bp.getZ();
        Box box = new Box(x, y, z, x + 1, y + (PLACEMENT_112 ? 1 : 2), z + 1);
        Set<Integer> crystalsInBox = new HashSet<>();
        // Meteor's intersectsWithEntities (line 1257), split: crystals go to the brain, which knows which ones
        // it attacked; anything else that is not a spectator takes the spot.
        boolean other = EntityUtils.intersectsWithEntity(box, entity -> {
            if (entity instanceof EndCrystalEntity) {
                crystalsInBox.add(entity.getId());
                return false;
            }
            return !entity.isSpectator();
        });
        return Optional.of(new Candidate(base.asLong(), damage, self.getAsDouble(), true, crystalsInBox, other));
    }

    // Acting

    private interface CrystalLookup {
        EndCrystalEntity get(int id);
    }

    private void execute(Action action, CrystalLookup lookup) {
        Decision d = action.decision();
        switch (d.kind()) {
            case BREAK -> {
                EndCrystalEntity crystal = lookup.get((int) d.ref());
                if (crystal != null) hit(crystal);
            }
            case PLACE -> placeOn(BlockPos.fromLong(d.ref()));
            case SWAP_WEAPON -> {
                // Anti-weakness (lines 826-838): swap to what hurts the crystal, and do not hit it this time.
                EndCrystalEntity crystal = lookup.get((int) d.ref());
                if (crystal != null) InvUtils.swap(InvUtils.findInHotbar(stack -> hurts(stack, crystal)).slot(), false);
            }
            case NONE -> {
            }
        }
    }

    /** Lines 843-863: rotate and attack, or attack at once. */
    private void hit(EndCrystalEntity crystal) {
        CrystalBrain b = brain;
        if (rotate.get()) {
            lastRotationPos = crystal.getEntityPos();
            Rotations.rotate(Rotations.getYaw(crystal), Rotations.getPitch(crystal, Target.Feet), ROTATION_PRIORITY,
                () -> attack(crystal, b));
        } else {
            attack(crystal, b);
        }
    }

    /**
     * Lines 881-892. A fast-break's callback runs in the next tick, after the next pre-tick, as Meteor's does,
     * so it is not tied to the tick it was decided in; only to this activation and this brain.
     */
    private void attack(EndCrystalEntity crystal, CrystalBrain b) {
        // The refusal first: one ending here replaces the brain, and then this action is stale.
        if (!isActive() || mc.player == null || refusingNow() || b != brain) return;
        mc.player.networkHandler.sendPacket(PlayerInteractEntityC2SPacket.attack(crystal, mc.player.isSneaking()));
        Hand hand = InvUtils.findInHotbar(Items.END_CRYSTAL).getHand();
        if (hand == null) hand = Hand.MAIN_HAND;
        swing(hand);
        b.attackSent();
    }

    /** Lines 981-1007: the side to click, then rotate and place, or place at once. */
    private void placeOn(BlockPos base) {
        BlockHitResult result = placeInfo(base);
        Vec3d face = faceCentre(result.getBlockPos(), result.getSide());
        CrystalBrain b = brain;
        long decidedAt = tick;
        if (rotate.get()) {
            lastRotationPos = face;
            Rotations.rotate(Rotations.getYaw(face), Rotations.getPitch(face), ROTATION_PRIORITY, () -> placeCrystal(result, b, decidedAt));
        } else {
            placeCrystal(result, b, decidedAt);
        }
    }

    /** The centre of one face of a block (lines 987-989): where the rotation looks. */
    private static Vec3d faceCentre(BlockPos pos, Direction side) {
        return new Vec3d(
            pos.getX() + 0.5 + side.getVector().getX() * 1.0 / 2.0,
            pos.getY() + 0.5 + side.getVector().getY() * 1.0 / 2.0,
            pos.getZ() + 0.5 + side.getVector().getZ() * 1.0 / 2.0);
    }

    /**
     * Meteor's {@code getPlaceInfo} (lines 1010-1030): the first side the eyes see, else the top or bottom.
     *
     * <p>When no side is seen, Meteor's result carries its shared {@code vec3d} as the hit position, and
     * {@code after} then overwrites that very vector with the centre of the chosen face (lines 1029, 986-990).
     * So the packet Meteor sends clicks the face centre, not the eyes; the server rejects a click too far from
     * the block, which the eyes always are. The same centre is used here.
     */
    private BlockHitResult placeInfo(BlockPos base) {
        ClientPlayerEntity p = mc.player;
        Vec3d eyes = new Vec3d(p.getX(), p.getY() + p.getEyeHeight(p.getPose()), p.getZ());
        for (Direction side : Direction.values()) {
            Vec3d end = new Vec3d(
                base.getX() + 0.5 + side.getVector().getX() * 0.5,
                base.getY() + 0.5 + side.getVector().getY() * 0.5,
                base.getZ() + 0.5 + side.getVector().getZ() * 0.5);
            BlockHitResult result = mc.world.raycast(new RaycastContext(eyes, end, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, p));
            if (result != null && result.getType() == HitResult.Type.BLOCK && result.getBlockPos().equals(base)) return result;
        }
        Direction side = base.getY() > eyes.y ? Direction.DOWN : Direction.UP;
        return new BlockHitResult(faceCentre(base, side), side, base, false);
    }

    /**
     * Lines 1032-1057 without support: swap to the crystals unless {@code auto-switch} is None or they are in
     * the offhand (no swap back), place through the sequenced packet, swing, and tell the brain. Only in the
     * tick it was decided in: {@link CrystalBrain#placed} belongs to that tick.
     */
    private void placeCrystal(BlockHitResult result, CrystalBrain b, long decidedAt) {
        // The refusal first: one ending here replaces the brain, and then this placement is stale.
        if (!isActive() || mc.player == null || refusingNow() || b != brain || decidedAt != tick) return;
        FindItemResult item = InvUtils.findInHotbar(Items.END_CRYSTAL);
        if (!item.found()) return;
        if (autoSwitch.get() != AutoSwitch.NONE && !item.isOffhand()) InvUtils.swap(item.slot(), false);
        Hand hand = item.getHand();
        if (hand == null) return;

        ((XploitsInteractionInvoker) mc.interactionManager).xploits$sendSequencedPacket(mc.world,
            sequence -> new PlayerInteractBlockC2SPacket(hand, result, sequence));
        swing(hand);
        b.placed(result.getBlockPos().asLong(), pingTicks());
    }

    private void swing(Hand hand) {
        SwingMode mode = swingMode.get();
        if (mode.client()) mc.player.swingHand(hand);
        if (mode.packet()) mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(hand));
    }

    /** Q6: your latency in the player list, in ticks rounded up; 5 when it cannot be read. */
    private int pingTicks() {
        if (mc.getNetworkHandler() == null || mc.player == null) return CrystalBrain.pingTicks(CrystalBrain.UNKNOWN_LATENCY);
        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return CrystalBrain.pingTicks(entry == null ? CrystalBrain.UNKNOWN_LATENCY : entry.getLatency());
    }
}
