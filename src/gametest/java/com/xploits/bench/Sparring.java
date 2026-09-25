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

    private int pops;
    /** The bench tick of the first pop; -1 until it pops. */
    private int firstPopTick = -1;
    private int deaths;
    private double damageTaken;
    private double rawDamage;
    /** The bench tick of the last {@link #step}; a hit comes during the server tick after it. */
    private int lastStepTick;

    private Sparring(ServerWorld world, Script script, Arena arena) {
        super(world, new GameProfile(UUID.randomUUID(), NAME));
        this.script = script;
        this.arena = arena;
    }

    /**
     * Builds the script's blocks, then spawns the sparring at the script's spawn point, facing the
     * player: unbreakable netherite with blast protection IV, an offhand totem, full health, no effects.
     * The tab entry goes out before the entity, so the client never sees a player without one.
     */
    static Sparring spawn(MinecraftServer srv, ServerPlayerEntity player, Arena arena, Script script) {
        ServerWorld world = srv.getOverworld();
        script.build(arena, world);
        Sparring sparring = new Sparring(world, script, arena);
        for (EquipmentSlot slot : Arena.armourSlots()) sparring.wear(world, slot, Arena.armour(srv.getRegistryManager(), slot));
        if (sparring.getArmor() != FULL_ARMOUR || sparring.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS) != FULL_TOUGHNESS
            || sparring.getAttributeValue(EntityAttributes.EXPLOSION_KNOCKBACK_RESISTANCE) < 1) {
            throw new BenchException("the sparring's armour did not take effect");
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

    /** Removes the sparring from the world and from every client's tab list. */
    void despawn(MinecraftServer srv) {
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
     * {@link #applyDamage}); it is counted and the totem refilled at once, inside this same call.
     */
    @Override
    public boolean damage(ServerWorld world, DamageSource source, float amount) {
        boolean hadTotem = getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);
        boolean damaged = super.damage(world, source, amount);
        if (hadTotem && !getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            pops++;
            if (firstPopTick < 0) firstPopTick = lastStepTick + 1;
            setStackInHand(Hand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
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
     */
    boolean step(int benchTick, int sinceT0, ServerPlayerEntity player) {
        lastStepTick = benchTick;
        if (!isDead()) {
            if (timeUntilRegen > 0) timeUntilRegen--;
            if (hurtTime > 0) hurtTime--;
            tickStatusEffects();
            script.tick(this, new Script.Tick(getEntityWorld(), player, arena, sinceT0));
        }
        if (isDead()) {
            deaths++;
            return true;
        }
        return false;
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
     * The counters, with the first pop as ticks after {@code t0Tick} (-1 when it has not popped, or when
     * T0 has not come).
     */
    Stats stats(int t0Tick) {
        int firstPop = firstPopTick < 0 || t0Tick < 0 ? -1 : firstPopTick - t0Tick;
        return new Stats(pops, firstPop, damageTaken, rawDamage, deaths, getAbsorptionAmount(), getHealth());
    }

    /**
     * What the bench reads of the sparring. {@code damageTaken} is health plus absorption lost;
     * {@code rawDamage} is the sum of the amounts handed to {@code applyDamage} (before armour).
     */
    public record Stats(int pops, int firstPopTick, double damageTaken, double rawDamage, int deaths,
                        float absorption, float health) {
    }
}
