package com.xploits.restock;

import com.xploits.printer.core.BreakPlan;
import com.xploits.printer.core.PrinterLimits;
import com.xploits.restock.core.Weapons;
import net.minecraft.block.BlockState;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffectUtil;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The player's side and the hotbar's side of vanilla's breaking formula ({@code PlayerEntity.getBlockBreakingSpeed},
 * {@code AbstractBlock.calcBlockBreakingDelta}), for the shulker box restock set down (spike S7): a weapon is never a
 * digging tool, so a fight's weapon is never worn or swapped away ({@link Weapons}: swords, axes and spears by their
 * item tags, the mace and the trident by item — pre-flight V11, correcting P17's wording). Client thread.
 */
final class DigFacts {
    private DigFacts() {
    }

    static BreakPlan.Body body(PlayerEntity p) {
        int haste = StatusEffectUtil.hasHaste(p) ? StatusEffectUtil.getHasteAmplifier(p) : -1;
        StatusEffectInstance fatigue = p.getStatusEffect(StatusEffects.MINING_FATIGUE);
        return new BreakPlan.Body(haste, fatigue == null ? -1 : fatigue.getAmplifier(),
            (float) p.getAttributeValue(EntityAttributes.BLOCK_BREAK_SPEED), p.isSubmergedIn(FluidTags.WATER),
            (float) p.getAttributeInstance(EntityAttributes.SUBMERGED_MINING_SPEED).getValue(), p.isOnGround());
    }

    /**
     * One hotbar slot as a tool for {@code state}; Efficiency read from the stack, as if it were held. Level 0 when the
     * server's data packs hold no Efficiency (Minor 4, ruling R64): no stack can carry it then, so vanilla's delta has
     * none either — never a throw in the middle of an unpack.
     */
    static BreakPlan.Tool tool(PlayerEntity p, int slot, BlockState state) {
        ItemStack stack = p.getInventory().getStack(slot);
        Optional<RegistryEntry.Reference<Enchantment>> efficiency = p.getEntityWorld().getRegistryManager()
            .getOptional(RegistryKeys.ENCHANTMENT).flatMap(r -> r.getOptional(Enchantments.EFFICIENCY));
        int level = efficiency.map(e -> EnchantmentHelper.getLevel(e, stack)).orElse(0);
        int left = stack.isDamageable() ? stack.getMaxDamage() - stack.getDamage() : -1;
        return new BreakPlan.Tool(slot, stack.getMiningSpeedMultiplier(state), stack.isSuitableFor(state), level, left);
    }

    /** {@link Weapons#of} for this stack: its item id and the ids of its item tags. */
    static boolean weapon(ItemStack stack) {
        if (stack.isEmpty()) return false;
        Set<String> tags = stack.streamTags().map(t -> t.id().toString()).collect(Collectors.toSet());
        return Weapons.of(StateFacts.itemId(stack), tags);
    }

    /** The fastest hotbar slot that is not a weapon, within 5 s and above the durability floor; empty for none. */
    static Optional<BreakPlan.Choice> bestTool(PlayerEntity p, BlockView world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        List<BreakPlan.Tool> hotbar = new ArrayList<>(PlayerInventory.HOTBAR_SIZE);
        for (int i = 0; i < PlayerInventory.HOTBAR_SIZE; i++) {
            if (!weapon(p.getInventory().getStack(i))) hotbar.add(tool(p, i, state));
        }
        return BreakPlan.choose(hotbar, p.getInventory().getSelectedSlot(), body(p),
            new BreakPlan.Hardness(state.getHardness(world, pos), state.isToolRequired()), PrinterLimits.DEFAULTS);
    }
}
