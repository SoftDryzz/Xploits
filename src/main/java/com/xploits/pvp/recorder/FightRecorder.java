package com.xploits.pvp.recorder;

import com.xploits.XploitsAddon;
import com.xploits.autotpy.AutoTpy;
import com.xploits.console.core.Level;
import com.xploits.kitrequester.KitRequester;
import com.xploits.pvp.AutoPvp;
import com.xploits.pvp.core.AllyPolicy;
import com.xploits.pvp.core.CombatState;
import com.xploits.pvp.core.Resource;
import com.xploits.pvp.recorder.core.CombatEvent;
import com.xploits.pvp.recorder.core.CombatEvent.AttackerKind;
import com.xploits.pvp.recorder.core.DamageKind;
import com.xploits.pvp.recorder.core.FightOutcome;
import com.xploits.pvp.recorder.core.FightRecord;
import com.xploits.pvp.recorder.core.FightStore;
import com.xploits.pvp.recorder.core.FightSummary;
import com.xploits.pvp.recorder.core.FightTracker;
import com.xploits.pvp.recorder.core.RecorderText;
import com.xploits.pvp.recorder.core.TickInput;
import com.xploits.pvp.recorder.core.TickInput.AutoPvpView;
import com.xploits.pvp.recorder.core.TickInput.Hostile;
import com.xploits.pvp.recorder.core.TickInput.SelfState;
import com.xploits.shared.Texts;
import com.xploits.shared.XploitsModule;
import com.xploits.shared.core.i18n.Msg;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.meteor.ActiveModulesChangedEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IPlayerInteractEntityC2SPacket;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.fakeplayer.FakePlayerEntity;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.s2c.play.DeathMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.world.GameMode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Records fights (spec {@code 2026-09-24-fight-recorder}, §Adapter). It is the <b>adapter</b>: it turns
 * packets and the client's own state into {@link TickInput}s, one per tick, and does what the pure
 * {@link FightTracker} answers: live lines to the console, and the finished fight to disk, to the console
 * and, when you lost it, to chat. Nothing is decided here.
 *
 * <p><b>No position is read into anything kept or said.</b> Players are turned into a name and a distance
 * the moment they are looked at, crystals into an id; the damage packet's source position is never read.
 *
 * <p><b>Threads.</b> {@code PacketEvent.Receive} is posted on the Netty thread before the packet is
 * applied: the packets are only queued there, and read on the next {@code TickEvent.Post}, on the game
 * thread and with the world already updated. Everything else runs on the game thread.
 *
 * <p><b>The tracker is fed every tick</b>, fight or not. It keeps your last tick alive as the health before
 * the first hit, and only when it is from the tick right before: skipping idle ticks would open every
 * fight from the health after the hit. And while a fight is open the quiet timer only runs on ticks it
 * sees. What the idle tick saves is the one scan that is not cheap: the damage aimed at you, which walks
 * every entity in the world.
 */
public class FightRecorder extends XploitsModule {
    /** How many entity ids are remembered, for players' names and for crystals. */
    private static final int REMEMBERED_IDS = 256;
    /** Worn armor is read from these four slots: a piece that breaks leaves its slot empty. */
    private static final List<EquipmentSlot> ARMOR_SLOTS =
        List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
    /** Not fighting modules: this one, and the console window. */
    private static final Set<String> NOT_RECORDED = Set.of("fight-recorder", "console");
    /** The name of {@code PlayerInteractEntityC2SPacket.InteractType.ATTACK}; see {@link #isAttack}. */
    private static final String ATTACK = "ATTACK";

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> deathNotice = sgGeneral.add(new BoolSetting.Builder()
        .name("death-notice")
        .description(Texts.startupText(RecorderText.SETTING_DEATH_NOTICE))
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> liveConsole = sgGeneral.add(new BoolSetting.Builder()
        .name("live-console")
        .description(Texts.startupText(RecorderText.SETTING_LIVE_CONSOLE))
        .defaultValue(true)
        .build()
    );

    /** Packets from the Netty thread, read on the next tick. */
    private final ConcurrentLinkedQueue<Packet<?>> inbox = new ConcurrentLinkedQueue<>();
    /** What you did (placed, broke, attacked) and crystals appearing near you, for the next tick. */
    private final ConcurrentLinkedQueue<CombatEvent> ownEvents = new ConcurrentLinkedQueue<>();

    /**
     * Player ids to names, refreshed every tick from the players loaded. It outlives the player entity: the
     * one who broke the crystal that hit you may already be out of sight when the packet is read.
     */
    private final Map<Integer, String> playerNames = recentIds();
    /**
     * End crystals seen appearing. A crystal that explodes is removed from the world in the same batch of
     * packets as the damage it dealt, so by the time the damage is read it can no longer be looked up.
     */
    private final Set<Integer> crystalIds = Collections.newSetFromMap(recentIds());

    private final FightTracker tracker = new FightTracker(addonVersion());
    private FightStore store;
    /** Whether saving already failed in this activation: said once, not once per fight. */
    private boolean saveWarned;
    private long tick;

    /** The player entity of the previous tick: the client builds a new one when you respawn. */
    private ClientPlayerEntity previousPlayer;
    private boolean previousAlive;
    /**
     * The player entity whose death was already reported. The death message and the health reaching 0 both
     * report it, in either order and on different ticks; an entity dies only once, and after a respawn the
     * client builds a new one, so keying on it reports every death exactly once.
     */
    private ClientPlayerEntity reportedDeath;

    /** The combat and Xploits modules on, recomputed only when a module is turned on or off. */
    private Set<String> activeModules = Set.of();
    private volatile boolean modulesChanged = true;

    public FightRecorder() {
        super(XploitsAddon.CATEGORY, "fight-recorder", Texts.startupText(RecorderText.MODULE_DESC));
    }

    @Override
    public void onActivate() {
        tracker.reset();
        inbox.clear();
        ownEvents.clear();
        playerNames.clear();
        crystalIds.clear();
        saveWarned = false;
        previousPlayer = null;
        previousAlive = false;
        reportedDeath = null;
        modulesChanged = true;
        // Crystals already there when the recorder is turned on never fire EntityAddedEvent.
        if (mc.world != null) {
            for (Entity entity : mc.world.getEntities()) {
                if (entity instanceof EndCrystalEntity) crystalIds.add(entity.getId());
            }
        }
    }

    @Override
    public void onDeactivate() {
        abortFight();
    }

    /**
     * Leaving the world cuts the fight short. {@code HIGHEST}, as in auto-travel: {@code Modules} tears the
     * modules down on the same event, and this has to see the fight before that. The {@code onDeactivate()}
     * that follows finds nothing left to abort.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    private void onGameLeft(GameLeftEvent event) {
        abortFight();
        inbox.clear();
        ownEvents.clear();
        previousPlayer = null;
        reportedDeath = null;
    }

    @EventHandler
    private void onActiveModulesChanged(ActiveModulesChangedEvent event) {
        modulesChanged = true;
    }

    /** Netty thread: queue only, the world is not touched here. */
    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        Packet<?> packet = event.packet;
        if (packet instanceof EntityStatusS2CPacket status) {
            byte code = status.getStatus();
            if (code == EntityStatuses.USE_TOTEM_OF_UNDYING || code == EntityStatuses.PLAY_DEATH_SOUND_OR_ADD_PROJECTILE_HIT_PARTICLES) {
                inbox.add(packet);
            }
        } else if (packet instanceof EntityDamageS2CPacket || packet instanceof DeathMessageS2CPacket) {
            inbox.add(packet);
        }
    }

    /**
     * What you send. Crystal aura sends its packets directly, so {@code AttackEntityEvent} misses them;
     * the packets do not. A placement is a block interaction with an end crystal in that hand.
     */
    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (mc.player == null || mc.world == null) return;
        if (event.packet instanceof PlayerInteractBlockC2SPacket place) {
            if (mc.player.getStackInHand(place.getHand()).isOf(Items.END_CRYSTAL)) ownEvents.add(new CombatEvent.CrystalPlaced());
        } else if (event.packet instanceof IPlayerInteractEntityC2SPacket interact && isAttack(interact)) {
            Entity target = interact.meteor$getEntity();
            if (target instanceof EndCrystalEntity) {
                ownEvents.add(new CombatEvent.CrystalBroken());
            } else if (target instanceof PlayerEntity player && player != mc.player) {
                // Allies are filtered on the tick, with that tick's lists.
                ownEvents.add(new CombatEvent.Attacked(nameOf(player)));
            }
        }
    }

    /**
     * {@code InteractType} is package-private in Minecraft: Meteor widens it for itself only, not for
     * addons, so the constant cannot be named here. Its {@code name()} is the constructor's string, which
     * the remapping leaves alone ({@code ldc "ATTACK"} in the class initializer, checked with javap on
     * the yarn 1.21.11+build.3 jar), so it is the same in development and in the game.
     */
    private static boolean isAttack(IPlayerInteractEntityC2SPacket interact) {
        Enum<?> type = interact.meteor$getType();
        return type != null && ATTACK.equals(type.name());
    }

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (!(event.entity instanceof EndCrystalEntity crystal)) return;
        crystalIds.add(crystal.getId());
        if (mc.player != null && mc.player.distanceTo(crystal) <= FightTracker.CRYSTAL_NEAR_RANGE) {
            ownEvents.add(new CombatEvent.CrystalSpawnedNear());
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) {
            inbox.clear();
            ownEvents.clear();
            return;
        }
        try {
            record();
        } catch (RuntimeException e) {
            // A recorder bug must not take the game down with it: the fight in progress is dropped and
            // recording starts over on the next tick.
            XploitsAddon.LOG.error("fight-recorder: tick failed, the fight in progress was dropped", e);
            tracker.reset();
        }
    }

    private void record() {
        tick++;
        ClientPlayerEntity me = mc.player;
        boolean respawned = previousPlayer != null && previousPlayer != me;
        refreshNames(me);
        Allies allies = allies();

        List<CombatEvent> events = new ArrayList<>();
        boolean deathMessage = drainPackets(me, respawned, allies, events);
        drainOwnEvents(allies, events);

        boolean alive = me.isAlive();
        // One SelfDied per death, on the tick of its first sign, after that tick's hits. A death message
        // read on the tick the client already respawned you belongs to the entity that died.
        ClientPlayerEntity dying = null;
        if (deathMessage) dying = respawned ? previousPlayer : me;
        else if (!respawned && previousAlive && !alive) dying = me;
        if (dying != null && dying != reportedDeath) {
            reportedDeath = dying;
            events.add(new CombatEvent.SelfDied());
        }
        // Past the respawn tick nothing can report the old entity's death again, and keeping it would keep
        // its world (chunks included) alive until the next death.
        if (reportedDeath != null && reportedDeath != me) reportedDeath = null;
        previousPlayer = me;
        previousAlive = alive;

        List<Hostile> hostiles = hostiles(me, allies);
        AutoPvp autoPvp = Modules.get().get(AutoPvp.class);
        boolean autoPvpOn = autoPvp != null && autoPvp.isActive();
        AutoPvpView view = autoPvpView(autoPvp);
        boolean idle = !tracker.fighting() && events.isEmpty() && hostiles.isEmpty()
            && (view == null || view.state() == CombatState.NO_COMBAT);

        TickInput in = new TickInput(tick, System.currentTimeMillis(), alive, measureSelf(me, autoPvp, !idle && alive),
            hostiles, activeModules(), autoPvpOn, view, events);
        FightTracker.Step step = tracker.tick(in);
        if (liveConsole.get()) {
            for (Msg line : step.live()) logToConsole(Level.INFO, line);
        }
        step.finished().ifPresent(this::finish);
    }

    /** Remembers the name of every player loaded but you. */
    private void refreshNames(ClientPlayerEntity me) {
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player != me) playerNames.put(player.getId(), nameOf(player));
        }
    }

    /**
     * Turns the queued packets into events. Returns whether your death message was among them: it is
     * turned into {@link CombatEvent.SelfDied} by the caller, which also watches your health.
     */
    private boolean drainPackets(ClientPlayerEntity me, boolean respawned, Allies allies, List<CombatEvent> events) {
        boolean deathMessage = false;
        for (Packet<?> packet; (packet = inbox.poll()) != null; ) {
            switch (packet) {
                case EntityStatusS2CPacket status -> {
                    Entity entity = status.getEntity(mc.world);
                    if (!(entity instanceof PlayerEntity player)) continue;
                    boolean pop = status.getStatus() == EntityStatuses.USE_TOTEM_OF_UNDYING;
                    if (player == me) {
                        if (pop) events.add(new CombatEvent.Popped(null));
                        continue;
                    }
                    String name = nameOf(player);
                    if (allies.ours(name)) continue;
                    events.add(pop ? new CombatEvent.Popped(name) : new CombatEvent.PlayerDied(name));
                }
                case EntityDamageS2CPacket damage -> {
                    if (damage.entityId() == me.getId()) {
                        events.add(selfDamaged(damage, me, allies));
                    } else {
                        String name = playerNames.get(damage.entityId());
                        if (name != null && !allies.ours(name)) {
                            events.add(new CombatEvent.OpponentDamaged(name, damage.sourceCauseId() == me.getId()));
                        }
                    }
                }
                case DeathMessageS2CPacket death -> {
                    if (death.playerId() == me.getId() || (respawned && death.playerId() == previousPlayer.getId())) {
                        deathMessage = true;
                    }
                }
                default -> {
                }
            }
        }
        return deathMessage;
    }

    /**
     * A hit on you. What hit you comes from the damage type, whether the direct source was a crystal from
     * the crystals seen; who, from the cause: you, a player by name, or nobody known (a mob, a player
     * never loaded).
     */
    private CombatEvent.SelfDamaged selfDamaged(EntityDamageS2CPacket damage, ClientPlayerEntity me, Allies allies) {
        int direct = damage.sourceDirectId();
        boolean crystal = direct >= 0
            && (crystalIds.contains(direct) || mc.world.getEntityById(direct) instanceof EndCrystalEntity);
        // getIdAsString() gives "minecraft:player_explosion"; getKey().toString() would give the key's
        // debug form and classify everything as OTHER.
        DamageKind kind = DamageKind.classify(damage.sourceType().getIdAsString(), crystal);
        int cause = damage.sourceCauseId();
        if (cause == me.getId()) return new CombatEvent.SelfDamaged(kind, AttackerKind.SELF, null, false);
        String name = cause >= 0 ? playerNames.get(cause) : null;
        if (name == null) return new CombatEvent.SelfDamaged(kind, AttackerKind.NONE, null, false);
        return new CombatEvent.SelfDamaged(kind, AttackerKind.PLAYER, name, allies.ours(name));
    }

    /** Your placements, breaks and crystals near you as they are; attacks on one of ours are dropped. */
    private void drainOwnEvents(Allies allies, List<CombatEvent> events) {
        for (CombatEvent event; (event = ownEvents.poll()) != null; ) {
            if (event instanceof CombatEvent.Attacked attacked && allies.ours(attacked.name())) continue;
            events.add(event);
        }
    }

    /**
     * The players within engage range, by name and distance, with auto-pvp's target filter: alive, not
     * ours, in survival (a Meteor fake player unless it is set not to be hit).
     */
    private List<Hostile> hostiles(ClientPlayerEntity me, Allies allies) {
        List<Hostile> hostiles = new ArrayList<>();
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == me || player.isDead() || player.getHealth() <= 0) continue;
            double distance = me.distanceTo(player);
            if (!(distance <= FightTracker.ENGAGE_RANGE)) continue;
            String name = nameOf(player);
            if (allies.ours(name)) continue;
            if (player instanceof FakePlayerEntity fake) {
                if (fake.noHit) continue;
            } else if (EntityUtils.getGameMode(player) != GameMode.SURVIVAL) {
                continue;
            }
            hostiles.add(new Hostile(name, distance));
        }
        return hostiles;
    }

    /**
     * Your state. {@code full} adds the damage aimed at you, the one reading that walks every entity:
     * idle ticks skip it (0). They are only kept as the state before a fight's first hit, and a fight
     * opening on a live tick measures that tick in full.
     */
    private SelfState measureSelf(ClientPlayerEntity me, AutoPvp autoPvp, boolean full) {
        ItemStack offhand = me.getOffHandStack();
        boolean offhandTotem = offhand.isOf(Items.TOTEM_OF_UNDYING);
        int totems = offhandTotem ? offhand.getCount() : 0;
        int gapples = isGapple(offhand) ? offhand.getCount() : 0;
        PlayerInventory inventory = me.getInventory();
        for (int slot = 0; slot < PlayerInventory.MAIN_SIZE; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isOf(Items.TOTEM_OF_UNDYING)) totems += stack.getCount();
            else if (isGapple(stack)) gapples += stack.getCount();
        }
        // Crystals and obsidian as auto-pvp counts them (the hotbar), so the review and auto-pvp agree.
        Map<Resource, Integer> hotbar = autoPvp == null ? Map.of() : autoPvp.hotbarResources().orElse(Map.of());
        int armor = 0;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            if (!me.getEquippedStack(slot).isEmpty()) armor++;
        }
        double incoming = full ? PlayerUtils.possibleHealthReductions() : 0;
        return new SelfState(PlayerUtils.getTotalHealth(), incoming, totems, offhandTotem,
            hotbar.getOrDefault(Resource.CRYSTALS, 0), hotbar.getOrDefault(Resource.OBSIDIAN, 0), gapples, armor,
            PlayerUtils.isInHole(false), me.isGliding());
    }

    private static boolean isGapple(ItemStack stack) {
        return stack.isOf(Items.GOLDEN_APPLE) || stack.isOf(Items.ENCHANTED_GOLDEN_APPLE);
    }

    /** auto-pvp's phase, posture and target, or null when it is off or has no plan (it released everything). */
    private static AutoPvpView autoPvpView(AutoPvp autoPvp) {
        if (autoPvp == null) return null;
        return autoPvp.currentPlan()
            .map(plan -> new AutoPvpView(plan.state(), plan.posture(), autoPvp.currentTarget().orElse(null)))
            .orElse(null);
    }

    /** Meteor's combat modules and Xploits' that are on, but this one and the console. */
    private Set<String> activeModules() {
        if (!modulesChanged) return activeModules;
        modulesChanged = false;
        List<Module> active = Modules.get().getActive();
        List<Module> copy;
        // Modules adds and removes under this same lock.
        synchronized (active) {
            copy = new ArrayList<>(active);
        }
        Set<String> names = new HashSet<>();
        for (Module module : copy) {
            if (module.category != Categories.Combat && module.category != XploitsAddon.CATEGORY) continue;
            if (!NOT_RECORDED.contains(module.name)) names.add(module.name);
        }
        activeModules = Set.copyOf(names);
        return activeModules;
    }

    /** Stores the fight, writes its review to the console and, if you lost, says why in one chat line. */
    private void finish(FightRecord record) {
        try {
            FightStore fights = store();
            fights.save(record);
            fights.prune();
        } catch (IOException e) {
            if (!saveWarned) {
                saveWarned = true;
                warning(RecorderText.SAVE_FAILED, "detail", String.valueOf(e.getMessage()));
            }
        }
        if (liveConsole.get()) {
            for (Msg line : FightSummary.review(record, 1)) logToConsole(Level.INFO, line);
        }
        if (record.outcome() == FightOutcome.LOST && deathNotice.get()) info(FightSummary.deathNotice(record));
    }

    /** Cuts the fight in progress short, if it had an exchange: ABORTED. */
    private void abortFight() {
        tracker.abort(System.currentTimeMillis()).ifPresent(this::finish);
    }

    /** Where the fights are kept, for the review command too: it works with the module off. */
    public FightStore store() {
        if (store == null) {
            Path folder = MeteorClient.FOLDER.toPath().resolve("xploits").resolve("pvp").resolve("fights");
            store = new FightStore(folder);
        }
        return store;
    }

    @Override
    public String activity() {
        return tracker.fighting() ? Texts.render(RecorderText.ACTIVITY, "seconds", tracker.seconds()) : "";
    }

    /** Kit-requester's couriers and auto-tpy's users, trimmed once per tick, plus Meteor's friends. */
    private record Allies(Set<String> couriers, Set<String> tpyUsers) {
        boolean ours(String name) {
            return AllyPolicy.of(name, Friends.get().get(name) != null, couriers, tpyUsers).isOurs();
        }
    }

    /** Whether those modules are on or off, as auto-pvp reads them: who is on your side does not depend on it. */
    private static Allies allies() {
        KitRequester kits = Modules.get().get(KitRequester.class);
        AutoTpy tpy = Modules.get().get(AutoTpy.class);
        return new Allies(AllyPolicy.names(kits == null ? Set.of() : kits.knownCouriers()),
            AllyPolicy.names(tpy == null ? Set.of() : tpy.users()));
    }

    private static String nameOf(PlayerEntity player) {
        return player.getGameProfile().name();
    }

    private static String addonVersion() {
        return FabricLoader.getInstance().getModContainer("xploits")
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
    }

    /** An id-keyed map that forgets the least recently used entry past {@link #REMEMBERED_IDS}. */
    private static <V> Map<Integer, V> recentIds() {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, V> eldest) {
                return size() > REMEMBERED_IDS;
            }
        };
    }
}
