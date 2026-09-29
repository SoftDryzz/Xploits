package com.xploits.bench;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import com.mojang.datafixers.util.Pair;
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.Unit;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.UUID;

/**
 * The scripted sparring partner (spec {@code 2026-09-25-ingame-bench}, §Sparring): a server-side
 * {@link FakePlayer} named {@value #NAME} that the client sees as a player, in the tab list too, and that
 * takes damage like one. Fabric's fake player neither ticks nor takes damage, so this class does by hand
 * what a player's tick would: its armour takes effect when put on, and once per bench tick in
 * {@link #step} the hurt cooldowns count down and the status effects run (the totem's absorption
 * expires).
 *
 * <p>Everything here runs on the server thread. Nothing reads out a position: the counters are all the
 * bench gets.
 */
public final class Sparring extends FakePlayer {
    public static final String NAME = "Sparring";
    /** Armour and toughness of a full netherite set. */
    private static final int FULL_ARMOUR = 20;
    private static final double FULL_TOUGHNESS = 12;

    private final Script script;
    private final Arena arena;
    /** Task A1 requirement 2 (opt-in): whether this sparring has only {@value Arena#FIGHT_TOTEMS} totems
     * in total instead of refilling forever. */
    private final boolean fightMode;
    /** Fight mode only: spare totems left besides the one in the offhand; meaningless otherwise. */
    private int totemsSpare;

    private int pops;
    /** The bench tick of the first pop; -1 until it pops. */
    private int firstPopTick = -1;
    private int deaths;
    private double damageTaken;
    private double rawDamage;
    /** Task B0b: hits that took health from a source someone else caused (the player breaking a crystal next to
     * it, say), never its own crystals: what tells the near-death fights' warm-up that OUR hit landed. */
    private int hitsFromOthers;
    /** Task B0b: pops and the death caused by someone else (the player's crystal), never by its own crystals: our
     * kills and pops, as the damage source's attacker says (a crystal explosion carries whoever broke it). */
    private int popsFromOthers;
    private boolean killedByOthers;
    /** The bench tick of the last {@link #step}; a hit comes during the server tick after it. */
    private int lastStepTick;
    /** Task A2 fix round 1: whether {@link Script#close()} has run yet — at most once, from whichever of
     * {@link #step} or {@link #despawn} gets there first. */
    private boolean scriptClosed;

    private Sparring(ServerWorld world, Script script, Arena arena, boolean fightMode) {
        super(world, new GameProfile(UUID.randomUUID(), NAME));
        this.script = script;
        this.arena = arena;
        this.fightMode = fightMode;
    }

    /**
     * Builds the script's blocks, then spawns the sparring at the script's spawn point, facing the
     * player: unbreakable netherite with blast protection IV, an offhand totem that refills forever, full
     * health, no effects. The tab entry goes out before the entity, so the client never sees a player
     * without one.
     */
    static Sparring spawn(MinecraftServer srv, ServerPlayerEntity player, Arena arena, Script script) {
        return spawn(srv, player, arena, script, false);
    }

    /**
     * Task A1 requirement 1 and 2 (opt-in): {@code fightMode} wears the fight armour
     * ({@link Arena#fightArmour}) instead of the standard one, and starts with
     * {@value Arena#FIGHT_TOTEMS} totems in total — the offhand one plus {@link #totemsSpare} spare —
     * instead of refilling forever ({@link #damage}).
     */
    static Sparring spawn(MinecraftServer srv, ServerPlayerEntity player, Arena arena, Script script, boolean fightMode) {
        ServerWorld world = srv.getOverworld();
        script.build(arena, world);
        Sparring sparring = new Sparring(world, script, arena, fightMode);
        sparring.totemsSpare = fightMode ? Arena.FIGHT_TOTEMS - 1 : Integer.MAX_VALUE;
        for (EquipmentSlot slot : Arena.armourSlots()) {
            ItemStack piece = fightMode ? Arena.fightArmour(srv.getRegistryManager(), slot) : Arena.armour(srv.getRegistryManager(), slot);
            sparring.wear(world, slot, piece);
        }
        // getArmor()/ARMOR_TOUGHNESS come from the netherite pieces alone, whichever enchantment they carry.
        // Full explosion knockback resistance only comes from 4x Blast Protection IV (the standard loadout);
        // the fight loadout wears it on the leggings alone (task A1 requirement 1), so that part of the
        // check does not apply to it — fight mode instead verifies the actual enchantment levels below
        // (task A1 fix round 1), the same check the player's own fightLoadout() makes.
        if (sparring.getArmor() != FULL_ARMOUR || sparring.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS) != FULL_TOUGHNESS
            || (!fightMode && sparring.getAttributeValue(EntityAttributes.EXPLOSION_KNOCKBACK_RESISTANCE) < 1)) {
            throw new BenchException("the sparring's armour did not take effect");
        }
        if (fightMode && !Arena.hasFightArmourEnchantments(srv.getRegistryManager(), sparring)) {
            throw new BenchException("the sparring's fight armour is not Protection IV plus Blast Protection IV on the leggings");
        }
        sparring.setStackInHand(Hand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        sparring.clearStatusEffects();
        sparring.setHealth(sparring.getMaxHealth());
        Vec3d at = script.spawnPoint(arena);
        float yaw = yaw(player.getX() - at.x, player.getZ() - at.z);
        sparring.refreshPositionAndAngles(at.x, at.y, at.z, yaw, 0);
        sparring.setHeadYaw(yaw);
        sparring.setBodyYaw(yaw);
        sparring.setOnGround(true);
        srv.getPlayerManager().sendToAll(PlayerListS2CPacket.entryFromPlayer(List.of(sparring)));
        world.spawnEntity(sparring);
        return sparring;
    }

    /**
     * Puts on an armour piece with its effect. A living entity applies an item's attribute modifiers
     * (armour, toughness) and its enchantments' attribute effects (blast protection's knockback
     * resistance) in its tick, which a fake player never runs; this does the same for one piece, as
     * {@code LivingEntity.getEquipmentChanges} does.
     *
     * <p>The piece is made unbreakable first: hits wear armour down, and a piece that broke would drop
     * its blast protection while the attributes applied here stayed (and the client, which gets no
     * equipment update from a fake player, would keep showing it whole), so the numbers would depend on
     * when it broke.
     */
    private void wear(ServerWorld world, EquipmentSlot slot, ItemStack stack) {
        stack.set(DataComponentTypes.UNBREAKABLE, Unit.INSTANCE);
        if (stack.isDamageable()) throw new BenchException("the sparring's armour is still breakable");
        equipStack(slot, stack);
        stack.applyAttributeModifiers(slot, (attribute, modifier) -> {
            EntityAttributeInstance instance = getAttributes().getCustomInstance(attribute);
            if (instance != null) {
                instance.removeModifier(modifier.id());
                instance.addTemporaryModifier(modifier);
            }
        });
        EnchantmentHelper.applyLocationBasedEffects(world, stack, this, slot);
    }

    /** Removes the sparring from the world and from every client's tab list. Task A2 fix round 1: a safety
     * net, closing the script first if {@link #step} never got the chance to (any reason a run ends with
     * something still pending besides the sparring's own death, e.g. the time limit). */
    void despawn(MinecraftServer srv) {
        closeScriptOnce();
        discard();
        srv.getPlayerManager().sendToAll(new PlayerRemoveS2CPacket(List.of(getUuid())));
    }

    // --- Damage ------------------------------------------------------------------------------------

    @Override
    public boolean isInvulnerableTo(ServerWorld world, DamageSource source) {
        return false;
    }

    /**
     * A pop is a hit after which the offhand totem is gone (the totem is used after
     * {@link #applyDamage}); it is counted, and the totem refilled at once, inside this same call. Outside
     * fight mode the refill is unconditional, as before; in fight mode (task A1 requirement 2) it only
     * happens while {@link #totemsSpare} is left, so the sparring's totems run out after
     * {@value Arena#FIGHT_TOTEMS}: the next lethal hit with no totem in the offhand then kills it for real,
     * through vanilla's own damage handling (nothing here needs to detect that death itself; {@link #step}
     * already reads {@link #isDead()}).
     */
    @Override
    public boolean damage(ServerWorld world, DamageSource source, float amount) {
        boolean hadTotem = getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);
        boolean wasAlive = !isDead();
        boolean byOther = source.getAttacker() != null && source.getAttacker() != this;
        float healthBefore = getHealth() + getAbsorptionAmount();
        boolean damaged = super.damage(world, source, amount);
        if (damaged && getHealth() + getAbsorptionAmount() < healthBefore
            && source.getAttacker() != null && source.getAttacker() != this) {
            hitsFromOthers++;
        }
        if (wasAlive && isDead() && byOther) killedByOthers = true;
        if (hadTotem && !getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            pops++;
            if (byOther) popsFromOthers++;
            if (firstPopTick < 0) firstPopTick = lastStepTick + 1;
            if (!fightMode || totemsSpare > 0) {
                if (fightMode) totemsSpare--;
                setStackInHand(Hand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            }
            showHands();
        }
        return damaged;
    }

    /** Health and absorption lost to this hit (after armour, never below zero), and the hit's raw amount. */
    @Override
    protected void applyDamage(ServerWorld world, DamageSource source, float amount) {
        float before = getHealth() + getAbsorptionAmount();
        super.applyDamage(world, source, amount);
        damageTaken += before - (getHealth() + getAbsorptionAmount());
        rawDamage += amount;
    }

    // --- Ticking -----------------------------------------------------------------------------------

    /**
     * One bench tick: the hurt cooldowns count down, the status effects run, then the script. Returns
     * true when the sparring is dead (counted once in {@link Stats#deaths}): the run is then ERROR.
     *
     * <p>Task A2 fix round 1: when the sparring is already dead here — its death happened earlier in this
     * same server tick, before this step ran — {@link #script}'s own {@link Script#tick} never runs again to
     * notice it, so this is the one place that ever will: {@link Script#close()} runs here instead, once, the
     * first tick that death is observed.
     */
    boolean step(int benchTick, int sinceT0, ServerPlayerEntity player) {
        lastStepTick = benchTick;
        if (!isDead()) {
            if (timeUntilRegen > 0) timeUntilRegen--;
            if (hurtTime > 0) hurtTime--;
            tickStatusEffects();
            script.tick(this, new Script.Tick(getEntityWorld(), player, arena, sinceT0));
        } else {
            closeScriptOnce();
        }
        if (isDead()) {
            deaths++;
            return true;
        }
        return false;
    }

    /** Task A2 fix round 1: {@link Script#close()}, at most once total across {@link #step} and
     * {@link #despawn}, whichever calls it first. */
    private void closeScriptOnce() {
        if (scriptClosed) return;
        scriptClosed = true;
        script.close();
    }

    // --- Moving, for the scripts ---------------------------------------------------------------------

    /** Turns body and head toward the player, and stays on the ground. */
    public void face(ServerPlayerEntity player) {
        float yaw = yaw(player.getX() - getX(), player.getZ() - getZ());
        setYaw(yaw);
        setHeadYaw(yaw);
        setBodyYaw(yaw);
        setOnGround(true);
    }

    /** Moves to {@code pos} with body and head turned to {@code yaw}; the tracker sends it to the clients. */
    public void place(Vec3d pos, float yaw, boolean onGround) {
        setPosition(pos.x, pos.y, pos.z);
        setYaw(yaw);
        setHeadYaw(yaw);
        setBodyYaw(yaw);
        setOnGround(onGround);
    }

    /** Minecraft's yaw for a horizontal direction (0 faces +z, 90 faces -x). */
    static float yaw(double dx, double dz) {
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
    }

    // --- Counters ------------------------------------------------------------------------------------

    public Script script() {
        return script;
    }

    /**
     * Task A2+ requirement 6 ("escape"): totems left in total, offhand plus spare — {@link Integer#MAX_VALUE}
     * outside fight mode, where {@link #totemsSpare} never runs out and the offhand always refills.
     */
    public int totemsLeft() {
        if (!fightMode) return Integer.MAX_VALUE;
        boolean inHand = getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);
        return totemsSpare + (inHand ? 1 : 0);
    }

    /**
     * Task A3 ({@code near-death}, base variant): strips every totem — the offhand emptied, no spares left —
     * so the sparring's next lethal hit is a real kill through vanilla's own damage handling, not a pop
     * ({@link #damage} only pops while {@link #totemsLeft()} would be positive). Fight mode only, and meant
     * to be called once, before T0, right after {@link #spawn}.
     */
    void disarmTotem() {
        if (!fightMode) throw new BenchException("disarmTotem is fight-mode only");
        setStackInHand(Hand.OFF_HAND, ItemStack.EMPTY);
        totemsSpare = 0;
        showHands();
    }

    /**
     * Task B0c/B0b (near-death, no totem): a visible non-totem item in the main hand, so the target's hands read
     * as visible (at least one shows an item) and, holding no totem, crystal-aura++ counts a blow on it as a kill,
     * never a pop. Identical for both auras.
     */
    void holdVisibleItem() {
        setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.NETHERITE_SWORD));
        showHands();
    }

    /**
     * Task B0b (near-death fights, at the near-death moment): the totems it had at the start of a fight run, all
     * {@value Arena#FIGHT_TOTEMS} of them, after the warm-up may have used some. Fight mode only.
     */
    void rearmTotems() {
        if (!fightMode) throw new BenchException("rearmTotems is fight-mode only");
        setStackInHand(Hand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        totemsSpare = Arena.FIGHT_TOTEMS - 1;
        showHands();
    }

    /**
     * Sends both hands to the players tracking it. A living entity sends its equipment changes from its own
     * tick ({@code LivingEntity.tick}), which this fake player never runs (it is stepped by hand), so a change
     * made after the spawn packet would never reach the client: the client would go on reading the spawn-time
     * hands (task T2: the offhand totem and an empty main hand after the near-death moment took both away, so
     * crystal-aura++ could not tell a kill from a pop). Called after every change of the hands, for both auras.
     */
    private void showHands() {
        ((ServerWorld) getEntityWorld()).getChunkManager().sendToNearbyPlayers(this, new EntityEquipmentUpdateS2CPacket(getId(),
            List.of(Pair.of(EquipmentSlot.MAINHAND, getMainHandStack().copy()), Pair.of(EquipmentSlot.OFFHAND, getOffHandStack().copy()))));
    }

    /**
     * The counters, with the first pop as ticks after {@code t0Tick} (-1 when it has not popped, or when
     * T0 has not come).
     */
    Stats stats(int t0Tick) {
        int firstPop = firstPopTick < 0 || t0Tick < 0 ? -1 : firstPopTick - t0Tick;
        return new Stats(pops, firstPop, damageTaken, rawDamage, deaths, getAbsorptionAmount(), getHealth(), hitsFromOthers, popsFromOthers, killedByOthers);
    }

    /**
     * What the bench reads of the sparring. {@code damageTaken} is health plus absorption lost;
     * {@code rawDamage} is the sum of the amounts handed to {@code applyDamage} (before armour).
     */
    public record Stats(int pops, int firstPopTick, double damageTaken, double rawDamage, int deaths,
                        float absorption, float health, int hitsFromOthers, int popsFromOthers,
                        boolean killedByOthers) {
    }
}
