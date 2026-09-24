package com.xploits.pvp.recorder.core;

import java.util.Map;

/**
 * What hurt you, as far as the damage packet tells. The packet carries the damage type and who caused
 * it, never the amount: the amount comes from your own health drop ({@code DamageLedger}).
 */
public enum DamageKind {
    CRYSTAL,
    EXPLOSION,
    RESPAWN_POINT,
    ANVIL,
    MELEE,
    PROJECTILE,
    FALL,
    FIRE,
    MAGIC,
    MOB,
    OTHER,
    /** A health drop with no damage packet near a hit (the invulnerability window). Only the ledger makes it. */
    UNSEEN;

    private static final String VANILLA = "minecraft:";

    private static final Map<String, DamageKind> BY_TYPE = Map.ofEntries(
        Map.entry("bad_respawn_point", RESPAWN_POINT),
        Map.entry("falling_anvil", ANVIL),
        Map.entry("player_attack", MELEE),
        Map.entry("mace_smash", MELEE),
        Map.entry("spear", MELEE),
        Map.entry("thorns", MELEE),
        Map.entry("arrow", PROJECTILE),
        Map.entry("trident", PROJECTILE),
        Map.entry("fireworks", PROJECTILE),
        Map.entry("thrown", PROJECTILE),
        Map.entry("wind_charge", PROJECTILE),
        Map.entry("mob_projectile", PROJECTILE),
        Map.entry("fireball", PROJECTILE),
        Map.entry("unattributed_fireball", PROJECTILE),
        Map.entry("wither_skull", PROJECTILE),
        Map.entry("spit", PROJECTILE),
        Map.entry("fall", FALL),
        Map.entry("fly_into_wall", FALL),
        Map.entry("ender_pearl", FALL),
        Map.entry("stalagmite", FALL),
        Map.entry("falling_stalactite", FALL),
        Map.entry("in_fire", FIRE),
        Map.entry("on_fire", FIRE),
        Map.entry("lava", FIRE),
        Map.entry("hot_floor", FIRE),
        Map.entry("campfire", FIRE),
        Map.entry("lightning_bolt", FIRE),
        Map.entry("magic", MAGIC),
        Map.entry("indirect_magic", MAGIC),
        Map.entry("wither", MAGIC),
        Map.entry("dragon_breath", MAGIC),
        Map.entry("sting", MOB),
        Map.entry("sonic_boom", MOB));

    /**
     * The kind of a vanilla damage type id such as {@code minecraft:player_explosion}. An explosion is
     * a {@link #CRYSTAL} only when its direct source is an end crystal; the flag means nothing for any
     * other type. Other namespaces and unknown ids are {@link #OTHER}; this never answers {@link #UNSEEN}.
     */
    public static DamageKind classify(String typeId, boolean directIsCrystal) {
        if (typeId == null || !typeId.startsWith(VANILLA)) return OTHER;
        String path = typeId.substring(VANILLA.length());
        if (path.equals("player_explosion") || path.equals("explosion")) return directIsCrystal ? CRYSTAL : EXPLOSION;
        if (path.startsWith("mob_attack")) return MOB;
        return BY_TYPE.getOrDefault(path, OTHER);
    }

    /** Whether this is damage someone deals you in a fight (CRYSTAL to PROJECTILE); falls, fire and mobs are not. */
    public boolean isCombat() {
        return ordinal() <= PROJECTILE.ordinal();
    }

    /** How the kind is named to the player. */
    public RecorderText label() {
        return RecorderText.valueOf("KIND_" + name());
    }
}
