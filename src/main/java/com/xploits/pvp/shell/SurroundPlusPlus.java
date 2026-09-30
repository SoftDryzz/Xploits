package com.xploits.pvp.shell;

import com.xploits.XploitsAddon;
import com.xploits.pvp.crystal.CrystalAuraPlusPlus;
import com.xploits.pvp.crystal.ExposureAt;
import com.xploits.pvp.crystal.core.CrystalBrain;
import com.xploits.pvp.crystal.core.Decision;
import com.xploits.pvp.crystal.core.ExplosionMath;
import com.xploits.pvp.crystal.core.ServerValues;
import com.xploits.pvp.shell.core.BlockKind;
import com.xploits.pvp.shell.core.Cell;
import com.xploits.pvp.shell.core.DamageOracle;
import com.xploits.pvp.shell.core.HoleWalk;
import com.xploits.pvp.shell.core.HurtWindow;
import com.xploits.pvp.shell.core.Material;
import com.xploits.pvp.shell.core.Placement;
import com.xploits.pvp.shell.core.ShellBrain;
import com.xploits.pvp.shell.core.ShellSetting;
import com.xploits.pvp.shell.core.ShellSettings;
import com.xploits.pvp.shell.core.ShellSnapshot;
import com.xploits.pvp.shell.core.ShellStatus;
import com.xploits.pvp.shell.core.ShellText;
import com.xploits.pvp.shell.core.ShellTick;
import com.xploits.pvp.shell.core.StandingCrystal;
import com.xploits.pvp.shell.core.Vec;
import com.xploits.shared.Texts;
import com.xploits.shared.XploitsModule;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.Burrow;
import meteordevelopment.meteorclient.systems.modules.combat.SelfTrap;
import meteordevelopment.meteorclient.systems.modules.combat.Surround;
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.BlockBreakingProgressS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.explosion.Explosion;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * surround++ (spec {@code 2026-09-30-surround-pp-design}): once per tick it measures the blocks, players, crystals and
 * mining around you, your hotbar and your health, and hands them to {@link ShellBrain}; then it does what that decides
 * with Meteor's own utilities: {@code BlockUtils.place} with a rotation for the blocks, an attack as Meteor's crystal
 * aura makes one for a crystal, the movement key bindings as Meteor's AutoWalk holds them for a walk.
 *
 * <p>Packets arrive on the Netty thread and are only queued there; the next pre-tick reads them. Absolute positions live
 * only in this class's memory (blocks sent and not yet seen, blocks being mined, the hole a walk heads for): the core
 * sees offsets from your feet, and nothing is written anywhere.
 */
public class SurroundPlusPlus extends XploitsModule {
    /** Meteor's Surround places at this rotation priority; it outranks crystal-aura++'s 50: the shell comes first. */
    private static final int ROTATION_PRIORITY = 100;
    /** A block sent and not seen yet counts as there for this many ticks; then it is planned again. */
    private static final int PENDING_TICKS = 4;
    /** A crystal attacked is not attacked again for this many ticks (Meteor's own wait after an attack). */
    private static final int ATTACK_WAIT_TICKS = 5;
    /** Raycasts the oracle may spend per tick, as crystal-aura++'s. */
    private static final int RAYCASTS_PER_TICK = 4000;
    /** A mining report older than this is dropped: a miner who left without a word. */
    private static final int MINING_STALE_TICKS = 40;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name(ShellSetting.BLOCKS_PER_TICK.id())
        .description(Texts.startupText(ShellSetting.BLOCKS_PER_TICK.text()))
        .defaultValue(ShellSettings.DEFAULT_BLOCKS_PER_TICK)
        .range(ShellSettings.MIN_BLOCKS_PER_TICK, ShellSettings.MAX_BLOCKS_PER_TICK)
        .sliderRange(ShellSettings.MIN_BLOCKS_PER_TICK, ShellSettings.MAX_BLOCKS_PER_TICK)
        .build()
    );

    private final Setting<Boolean> useCryingObsidian = sgGeneral.add(new BoolSetting.Builder()
        .name(ShellSetting.USE_CRYING_OBSIDIAN.id())
        .description(Texts.startupText(ShellSetting.USE_CRYING_OBSIDIAN.text()))
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> moveToHole = sgGeneral.add(new BoolSetting.Builder()
        .name(ShellSetting.MOVE_TO_HOLE.id())
        .description(Texts.startupText(ShellSetting.MOVE_TO_HOLE.text()))
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> denyHoles = sgGeneral.add(new BoolSetting.Builder()
        .name(ShellSetting.DENY_HOLES.id())
        .description(Texts.startupText(ShellSetting.DENY_HOLES.text()))
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> breakCrystals = sgGeneral.add(new BoolSetting.Builder()
        .name(ShellSetting.BREAK_CRYSTALS.id())
        .description(Texts.startupText(ShellSetting.BREAK_CRYSTALS.text()))
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> useBurrow = sgGeneral.add(new BoolSetting.Builder()
        .name(ShellSetting.BURROW.id())
        .description(Texts.startupText(ShellSetting.BURROW.text()))
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> reach = sgGeneral.add(new DoubleSetting.Builder()
        .name(ShellSetting.REACH.id())
        .description(Texts.startupText(ShellSetting.REACH.text()))
        .defaultValue(ShellSettings.DEFAULT_REACH)
        .range(1, 8)
        .sliderRange(3, 8)
        .build()
    );

    private record Mining(int stage, long tick) {
    }

    private record Pending(BlockKind kind, long tick) {
    }

    /** What one tick's packets said about us. */
    private record Read(boolean popped, int explosions, int others) {
    }

    private final ShellBrain brain = new ShellBrain();
    private final ExposureAt.Budget raycasts = new ExposureAt.Budget(RAYCASTS_PER_TICK);
    private final Queue<BlockBreakingProgressS2CPacket> miningPackets = new ConcurrentLinkedQueue<>();
    private final Queue<EntityDamageS2CPacket> damagePackets = new ConcurrentLinkedQueue<>();
    private final Queue<EntityStatusS2CPacket> statusPackets = new ConcurrentLinkedQueue<>();
    private final Map<BlockPos, Mining> mining = new HashMap<>();
    private final Map<BlockPos, Pending> pending = new HashMap<>();
    private final Map<Integer, Long> attacked = new HashMap<>();
    private final EnumSet<HoleWalk.Key> pressed = EnumSet.noneOf(HoleWalk.Key.class);
    private BlockPos walkTarget;
    private long tick;
    private double lastHealth = -1;
    private long windowStart;
    private HurtWindow window = HurtWindow.NONE;
    private ShellStatus status;
    private boolean warnedNoBlocks;
    private boolean warnedMeteorShell;
    private int placedObsidian;
    private int placedCrying;
    private int crystalsBroken;
    private int centred;
    private int walks;

    public SurroundPlusPlus() {
        super(XploitsAddon.CATEGORY, "surround++", Texts.startupText(ShellText.MODULE_DESC));
    }

    @Override
    public void onActivate() {
        brain.reset();
        miningPackets.clear();
        damagePackets.clear();
        statusPackets.clear();
        mining.clear();
        pending.clear();
        attacked.clear();
        walkTarget = null;
        tick = 0;
        lastHealth = -1;
        window = HurtWindow.NONE;
        status = null;
        warnedNoBlocks = false;
        warnedMeteorShell = false;
        placedObsidian = 0;
        placedCrying = 0;
        crystalsBroken = 0;
        centred = 0;
        walks = 0;
        releaseKeys();
    }

    @Override
    public void onDeactivate() {
        releaseKeys();
        walkTarget = null;
        status = null;
        miningPackets.clear();
        damagePackets.clear();
        statusPackets.clear();
    }

    // What the rest of Xploits reads

    /** What the auto-pvp panel shows, while on. Game thread. */
    public Optional<ShellStatus> status() {
        return isActive() ? Optional.ofNullable(status) : Optional.empty();
    }

    /** Obsidian placed since activation (for the lab). */
    public int placedObsidian() {
        return placedObsidian;
    }

    /** Crying obsidian placed since activation (for the lab). */
    public int placedCrying() {
        return placedCrying;
    }

    /** Opponent crystals attacked since activation (for the lab). */
    public int crystalsBroken() {
        return crystalsBroken;
    }

    /** Times it centred you since activation (for the lab). */
    public int centred() {
        return centred;
    }

    /** Walks to a hole started since activation (for the lab). */
    public int walks() {
        return walks;
    }

    /** Ticks on which a block went where crystal-aura++ was placing, since activation (for the lab). */
    public int auraOverrides() {
        return brain.auraOverrides();
    }

    // Events

    /** Netty thread: queue only; the world is not touched here. */
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof BlockBreakingProgressS2CPacket p) miningPackets.add(p);
        else if (event.packet instanceof EntityDamageS2CPacket p) damagePackets.add(p);
        else if (event.packet instanceof EntityStatusS2CPacket p) statusPackets.add(p);
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onPreTick(TickEvent.Pre event) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || !p.isAlive()) {
            releaseKeys();
            walkTarget = null;
            // Nothing from before a death or a world change says anything about the next life.
            miningPackets.clear();
            damagePackets.clear();
            statusPackets.clear();
            window = HurtWindow.NONE;
            lastHealth = -1;
            return;
        }
        tick++;
        raycasts.reset(RAYCASTS_PER_TICK);
        Read read = readPackets(p);
        OptionalDouble health = ServerValues.ownHealth(p.getHealth(), p.getAbsorptionAmount());
        Vec3d pos = p.getEntityPos();
        // A health or a position that is not a number (a broken or spoofing server): nothing is decided on it this tick.
        if (health.isEmpty() || !Double.isFinite(pos.x) || !Double.isFinite(pos.y) || !Double.isFinite(pos.z)) {
            releaseKeys();
            return;
        }
        BlockPos feet = p.getBlockPos();
        double fx = pos.x - feet.getX();
        double fy = pos.y - feet.getY();
        double fz = pos.z - feet.getZ();
        // Feet off their own block (a coordinate at the edge of a double's precision, or past the int range): skip.
        if (!(fx >= 0 && fx < 1 && fy >= 0 && fy < 1 && fz >= 0 && fz < 1)) {
            releaseKeys();
            return;
        }
        updateWindow(health.getAsDouble(), read);
        ShellSnapshot snapshot = snapshot(p, feet, pos, health.getAsDouble());
        warnings(snapshot);
        ShellTick decided = brain.tick(snapshot, new Oracle(p, feet),
            new ShellBrain.Motion(userKeys(), p.getYaw(), walkingTo(feet), read.popped()));
        status = decided.status();
        execute(decided, feet);
    }

    // Reading

    private Read readPackets(ClientPlayerEntity p) {
        for (BlockBreakingProgressS2CPacket packet; (packet = miningPackets.poll()) != null; ) {
            // Our own mining is no attack on us.
            if (packet.getEntityId() == p.getId()) continue;
            BlockPos pos = packet.getPos().toImmutable();
            int stage = packet.getProgress();
            if (stage < 0 || stage > 9) mining.remove(pos);
            else mining.put(pos, new Mining(stage, tick));
        }
        mining.values().removeIf(m -> tick - m.tick() > MINING_STALE_TICKS);
        int explosions = 0;
        int others = 0;
        for (EntityDamageS2CPacket packet; (packet = damagePackets.poll()) != null; ) {
            if (packet.entityId() != p.getId()) continue;
            if (packet.sourceType().matchesKey(DamageTypes.EXPLOSION) || packet.sourceType().matchesKey(DamageTypes.PLAYER_EXPLOSION)) {
                explosions++;
            } else {
                others++;
            }
        }
        boolean popped = false;
        for (EntityStatusS2CPacket packet; (packet = statusPackets.poll()) != null; ) {
            if (packet.getStatus() == EntityStatuses.USE_TOTEM_OF_UNDYING && packet.getEntity(mc.world) == p) popped = true;
        }
        return new Read(popped, explosions, others);
    }

    /**
     * Our hurt window (spec §6.4), counted as crystal-aura++'s TargetWindows counts a target's: from the pre-tick before the
     * hit was read, so the ticks left never come out long. Only a tick whose only full hit on us was one explosion, with
     * the health it took, opens one; any other full hit restarts the server's window with a size we cannot know and closes
     * ours; a pop, or {@link HurtWindow#WINDOW_TICKS}, closes it too.
     */
    private void updateWindow(double health, Read read) {
        if (read.popped()) {
            window = HurtWindow.NONE;
        } else if (read.explosions() + read.others() > 0) {
            double drop = lastHealth - health;
            boolean measured = read.explosions() == 1 && read.others() == 0 && lastHealth >= 0 && drop > 0;
            if (measured) {
                windowStart = tick;
                window = new HurtWindow(1, drop);
            } else {
                window = HurtWindow.NONE;
            }
        } else if (window.ticksSince() >= 0) {
            int since = (int) (tick - windowStart) + 1;
            window = since < HurtWindow.WINDOW_TICKS ? new HurtWindow(since, window.damage()) : HurtWindow.NONE;
        }
        lastHealth = health;
    }
    private ShellSnapshot snapshot(ClientPlayerEntity p, BlockPos feet, Vec3d pos, double health) {
        ShellSnapshot.Builder b = ShellSnapshot.builder();
        pending.values().removeIf(sent -> tick - sent.tick() > PENDING_TICKS);
        BlockPos.Mutable at = new BlockPos.Mutable();
        for (int dy = -ShellSnapshot.BELOW; dy <= ShellSnapshot.ABOVE; dy++) {
            for (int dx = -ShellSnapshot.RADIUS; dx <= ShellSnapshot.RADIUS; dx++) {
                for (int dz = -ShellSnapshot.RADIUS; dz <= ShellSnapshot.RADIUS; dz++) {
                    at.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    BlockKind kind = kindOf(mc.world.getBlockState(at));
                    if (!kind.isPlaceable()) {
                        pending.remove(at);
                    } else {
                        // A block we sent and have not seen yet counts as there: planning it again would place it twice.
                        Pending sent = pending.get(at);
                        if (sent != null) kind = sent.kind();
                    }
                    if (kind != BlockKind.AIR) b.block(dx, dy, dz, kind);
                }
            }
        }
        b.feet(new Vec(pos.x - feet.getX(), pos.y - feet.getY(), pos.z - feet.getZ())).onGround(p.isOnGround());
        Set<Integer> ours = ownCrystals();
        Box area = new Box(feet).expand(ShellSnapshot.RADIUS + 1);
        for (Entity e : mc.world.getEntities()) {
            if (e == p || e.isRemoved()) continue;
            if (e instanceof PlayerEntity other && hostile(other)) {
                b.hostile(relative(other.getEntityPos(), feet), relative(other.getEyePos(), feet));
            }
            if (!(e instanceof PlayerEntity) && !(e instanceof EndCrystalEntity)) continue;
            Box box = e.getBoundingBox();
            if (!box.intersects(area)) continue;
            occupy(b, box, feet);
            if (e instanceof EndCrystalEntity crystal) {
                Cell cell = cell(crystal.getBlockPos(), feet);
                if (ShellSnapshot.inBox(cell)) b.crystal(new StandingCrystal(crystal.getId(), cell, ours.contains(crystal.getId())));
            }
        }
        for (Map.Entry<BlockPos, Mining> m : mining.entrySet()) {
            Cell cell = cell(m.getKey(), feet);
            if (ShellSnapshot.inBox(cell)) b.mining(cell, m.getValue().stage());
        }
        return b.health(health).window(window).pingTicks(pingTicks())
            .obsidian(InvUtils.findInHotbar(Items.OBSIDIAN).count())
            .cryingObsidian(InvUtils.findInHotbar(Items.CRYING_OBSIDIAN).count())
            .auraSpot(auraSpot(feet))
            .settings(settingsNow())
            .build();
    }

    private static BlockKind kindOf(BlockState state) {
        if (state.isAir()) return BlockKind.AIR;
        if (state.isOf(Blocks.OBSIDIAN)) return BlockKind.OBSIDIAN;
        if (state.isOf(Blocks.CRYING_OBSIDIAN)) return BlockKind.CRYING_OBSIDIAN;
        if (state.isOf(Blocks.BEDROCK)) return BlockKind.BEDROCK;
        if (state.isOf(Blocks.COBWEB)) return BlockKind.COBWEB;
        if (state.isReplaceable()) return BlockKind.REPLACEABLE;
        return BlockKind.OTHER;
    }

    /** Every cell an entity's box overlaps: vanilla refuses a crystal or a block there. */
    private static void occupy(ShellSnapshot.Builder b, Box box, BlockPos feet) {
        int x0 = MathHelper.floor(box.minX);
        int x1 = MathHelper.floor(box.maxX - 1e-6);
        int y0 = MathHelper.floor(box.minY);
        int y1 = MathHelper.floor(box.maxY - 1e-6);
        int z0 = MathHelper.floor(box.minZ);
        int z1 = MathHelper.floor(box.maxZ - 1e-6);
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    Cell c = new Cell(x - feet.getX(), y - feet.getY(), z - feet.getZ());
                    if (ShellSnapshot.inBox(c)) b.occupied(c);
                }
            }
        }
    }

    /** A player who may attack us: alive, not watching, and not a friend. */
    private static boolean hostile(PlayerEntity other) {
        return other.isAlive() && !other.isSpectator() && Friends.get().shouldAttack(other);
    }

    private static Vec relative(Vec3d v, BlockPos feet) {
        return new Vec(v.x - feet.getX(), v.y - feet.getY(), v.z - feet.getZ());
    }

    private static Cell cell(BlockPos pos, BlockPos feet) {
        return new Cell(pos.getX() - feet.getX(), pos.getY() - feet.getY(), pos.getZ() - feet.getZ());
    }

    /** crystal-aura++'s own crystals, which it looks after itself. */
    private Set<Integer> ownCrystals() {
        CrystalAuraPlusPlus aura = Modules.get().get(CrystalAuraPlusPlus.class);
        return aura != null && aura.isActive() ? aura.ownCrystals().keySet() : Set.of();
    }

    /** Where crystal-aura++ is placing right now: the cell above the base its last decision names. */
    private Cell auraSpot(BlockPos feet) {
        CrystalAuraPlusPlus aura = Modules.get().get(CrystalAuraPlusPlus.class);
        if (aura == null || !aura.isActive()) return null;
        Decision decision = aura.lastDecision();
        if (decision.kind() != Decision.Kind.PLACE) return null;
        return cell(BlockPos.fromLong(decision.ref()).up(), feet);
    }

    private int pingTicks() {
        if (mc.getNetworkHandler() == null || mc.player == null) return CrystalBrain.pingTicks(CrystalBrain.UNKNOWN_LATENCY);
        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return CrystalBrain.pingTicks(entry == null ? CrystalBrain.UNKNOWN_LATENCY : entry.getLatency());
    }

    private ShellSettings settingsNow() {
        return new ShellSettings(blocksPerTick.get(), useCryingObsidian.get(), moveToHole.get(), denyHoles.get(),
            breakCrystals.get(), useBurrow.get(), reach.get());
    }

    private Optional<Cell> walkingTo(BlockPos feet) {
        return walkTarget == null ? Optional.empty() : Optional.of(cell(walkTarget, feet));
    }

    /** A movement key, jump or sneak held by the player's own hand: Meteor's record of the keys, not the bindings we press. */
    private boolean userKeys() {
        return Input.isPressed(mc.options.forwardKey) || Input.isPressed(mc.options.backKey)
            || Input.isPressed(mc.options.leftKey) || Input.isPressed(mc.options.rightKey)
            || Input.isPressed(mc.options.jumpKey) || Input.isPressed(mc.options.sneakKey);
    }

    private void warnings(ShellSnapshot s) {
        boolean noBlocks = s.obsidian() == 0 && s.cryingObsidian() == 0;
        if (noBlocks && !warnedNoBlocks) warning(ShellText.NO_BLOCKS);
        warnedNoBlocks = noBlocks;
        Module meteor = meteorShellOn();
        if (meteor != null && !warnedMeteorShell) warning(ShellText.METEOR_SHELL_ON, "module", meteor.name);
        warnedMeteorShell = meteor != null;
    }

    private static Module meteorShellOn() {
        Module surround = Modules.get().get(Surround.class);
        if (surround != null && surround.isActive()) return surround;
        Module trap = Modules.get().get(SelfTrap.class);
        return trap != null && trap.isActive() ? trap : null;
    }

    // The damage

    /** The damage of a crystal on a spot (spec §5.2): crystal-aura++'s exact path, the raycasts rationed per tick. */
    private final class Oracle implements DamageOracle {
        private final ClientPlayerEntity player;
        private final BlockPos feet;
        private final boolean creative;

        Oracle(ClientPlayerEntity player, BlockPos feet) {
            this.player = player;
            this.feet = feet;
            // Meteor predicts nothing for a player in creative, and the server deals nothing.
            this.creative = EntityUtils.getGameMode(player) == GameMode.CREATIVE;
        }

        @Override
        public double bound(Cell spot) {
            return creative ? 0 : reduced(raw(explosion(spot), 1.0));
        }

        @Override
        public double exact(Cell spot) {
            if (creative) return 0;
            Vec3d explosion = explosion(spot);
            if (Math.sqrt(player.squaredDistanceTo(explosion)) > ExplosionMath.CRYSTAL_RADIUS) return 0;
            // Vanilla's exposure for where we stand; 1.0, the cautious value, once this tick's raycasts are spent.
            return reduced(raw(explosion, ExposureAt.at(player, explosion, 0, 0, 0, raycasts)));
        }

        private float raw(Vec3d explosion, double exposure) {
            double distance = Math.sqrt(player.squaredDistanceTo(explosion));
            return distance > ExplosionMath.CRYSTAL_RADIUS ? 0f : ExplosionMath.rawDamage(distance, exposure);
        }

        private double reduced(float raw) {
            return raw <= 0 ? 0 : DamageUtils.calculateReductions(raw, player, mc.world.getDamageSources().explosion((Explosion) null));
        }

        private Vec3d explosion(Cell spot) {
            return new Vec3d(feet.getX() + spot.x() + 0.5, feet.getY() + spot.y(), feet.getZ() + spot.z() + 0.5);
        }
    }

    // Acting

    private void execute(ShellTick decided, BlockPos feet) {
        decided.breakCrystal().ifPresent(this::attack);
        for (Placement placement : decided.placements()) place(placement, feet);
        if (decided.centre()) {
            PlayerUtils.centerPlayer();
            centred++;
        }
        if (decided.burrow()) burrow();
        decided.walkTo().ifPresent(c -> {
            walkTarget = feet.add(c.x(), c.y(), c.z());
            walks++;
        });
        if (decided.walkEnded()) walkTarget = null;
        press(decided.keys());
    }

    private void place(Placement placement, BlockPos feet) {
        BlockPos pos = feet.add(placement.cell().x(), placement.cell().y(), placement.cell().z());
        boolean crying = placement.material() == Material.CRYING_OBSIDIAN;
        FindItemResult item = InvUtils.findInHotbar(crying ? Items.CRYING_OBSIDIAN : Items.OBSIDIAN);
        if (!item.found()) return;
        // Meteor checks the cell is free, rotates, and places in the rotation's callback, against a neighbour's face when
        // there is one (the plan only picks cells that have one): true means it is on its way.
        if (!BlockUtils.place(pos, item, true, ROTATION_PRIORITY, true, true, true)) return;
        pending.put(pos, new Pending(placement.material().kind(), tick));
        if (crying) placedCrying++;
        else placedObsidian++;
    }

    private void attack(int id) {
        attacked.values().removeIf(t -> tick - t >= ATTACK_WAIT_TICKS);
        if (attacked.containsKey(id)) return;
        if (!(mc.world.getEntityById(id) instanceof EndCrystalEntity crystal)) return;
        attacked.put(id, tick);
        Rotations.rotate(Rotations.getYaw(crystal), Rotations.getPitch(crystal), ROTATION_PRIORITY, () -> {
            if (crystal.isRemoved() || mc.player == null || mc.interactionManager == null) return;
            mc.interactionManager.attackEntity(mc.player, crystal);
            mc.player.swingHand(Hand.MAIN_HAND);
            crystalsBroken++;
        });
    }

    /** Meteor's burrow, on for one use: it burrows and turns itself off. */
    private static void burrow() {
        Burrow burrow = Modules.get().get(Burrow.class);
        if (burrow != null && !burrow.isActive()) burrow.toggle();
    }

    private void press(Set<HoleWalk.Key> keys) {
        for (HoleWalk.Key key : HoleWalk.Key.values()) {
            KeyBinding binding = binding(key);
            if (keys.contains(key)) {
                binding.setPressed(true);
                pressed.add(key);
            } else if (pressed.remove(key)) {
                // Handed back as the player's own hand holds it.
                binding.setPressed(Input.isPressed(binding));
            }
        }
    }

    private void releaseKeys() {
        if (mc.options == null) {
            pressed.clear();
            return;
        }
        press(Set.of());
    }

    private KeyBinding binding(HoleWalk.Key key) {
        return switch (key) {
            case FORWARD -> mc.options.forwardKey;
            case BACK -> mc.options.backKey;
            case LEFT -> mc.options.leftKey;
            case RIGHT -> mc.options.rightKey;
        };
    }
}
