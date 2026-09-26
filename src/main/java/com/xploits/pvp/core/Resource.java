package com.xploits.pvp.core;

/**
 * What a managed module needs to carry to be of any use (spec §6).
 *
 * <p>Every managed module places or mines something, the three {@code anti-} ones of the defensive
 * posture (redesign §5) included: checked in the {@code meteor-client:1.21.11-SNAPSHOT} sources, each
 * looks in the hotbar with {@code InvUtils.findInHotbar} for what it places.
 * {@code AntiAnvil} places obsidian between you and the anvil, {@code AntiBed} string where the bed
 * would go and {@code AntiAnchor} a slab over your head. There used to be a {@code NONE} here for
 * them, on the belief that they only listened: they were turned on without any of it, and then they
 * placed nothing.
 */
public enum Resource {
    CRYSTALS, OBSIDIAN, WEBS, ANVILS, PICKAXE,
    /** {@code Items.STRING}, what {@code anti-bed} places. */
    STRING,
    /**
     * Any slab: {@code anti-anchor} takes the first hotbar item whose block is a {@code SlabBlock}
     * ({@code Block.getBlockFromItem(item) instanceof SlabBlock}), whatever its material.
     */
    SLABS
}
