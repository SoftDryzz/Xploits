package com.xploits.bench;

import meteordevelopment.meteorclient.systems.modules.combat.AutoTotem;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.Difficulty;
import net.minecraft.world.rule.GameRules;

import java.util.List;
import java.util.function.Predicate;

/**
 * The arena of one run (spec {@code 2026-09-25-ingame-bench}, §Scripts and geometry, §Arena and
 * loadouts): the world rules, the player centred on its block, the blast-proof floor, the pads and
 * cells the scripts need, and the two loadouts.
 *
 * <p>Every block is placed relative to F, the player's feet block, taken once on the server after the
 * centring. F is server-side arithmetic only: it is never printed, logged or put in a report.
 */
public final class Arena {
    /** The floor spans F-10..F+10 on both horizontal axes (21 by 21). */
    public static final int FLOOR_RADIUS = 10;
    /** Layers above the floor that are cleared to air, so nothing but the bench's blocks stands in the arena. */
    private static final int CLEAR_HEIGHT = 4;
    /** Totems of the standard loadout's inventory (the offhand one is extra). */
    public static final int INVENTORY_TOTEMS = 16;
    /** How long the client may take to see a loadout the server gave. */
    private static final int LOADOUT_SYNC_TICKS = 40;

    private static final List<EquipmentSlot> ARMOUR = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST,
        EquipmentSlot.LEGS, EquipmentSlot.FEET);

    private final Bench bench;
    /** F, the player's feet block; set by {@link #prepare()}. Never printed. */
    private BlockPos feet;

    Arena(Bench bench) {
        this.bench = bench;
    }

    // --- Common preparation (§Run timeline, Arrange) ----------------------------------------------

    /**
     * Difficulty normal, no natural regeneration, the player in survival and centred on its block; then F
     * is taken, the arena's air is cleared and the floor is laid.
     */
    void prepare() {
        // The command fails when the difficulty is already the one asked for (a new world's default).
        if (bench.fromServer(srv -> srv.getSaveProperties().getDifficulty()) != Difficulty.NORMAL) {
            bench.command("difficulty normal");
        }
        bench.command("gamerule natural_health_regeneration false");
        bench.command("gamemode survival " + Bench.PLAYER);
        bench.command("execute at " + Bench.PLAYER + " align xz run tp " + Bench.PLAYER + " ~0.5 ~ ~0.5");
        boolean set = bench.fromServer(srv -> srv.getSaveProperties().getDifficulty() == Difficulty.NORMAL
            && !srv.getOverworld().getGameRules().getValue(GameRules.NATURAL_HEALTH_REGENERATION));
        if (!set) throw new BenchException("the difficulty or the regeneration rule did not change");

        String name = bench.player();
        feet = bench.fromServer(srv -> player(srv, name).getBlockPos());
        bench.onServer(srv -> {
            ServerWorld world = srv.getOverworld();
            fill(world, -FLOOR_RADIUS, 0, -FLOOR_RADIUS, FLOOR_RADIUS, CLEAR_HEIGHT - 1, FLOOR_RADIUS, Blocks.AIR);
            fill(world, -FLOOR_RADIUS, -1, -FLOOR_RADIUS, FLOOR_RADIUS, -1, FLOOR_RADIUS, Blocks.REINFORCED_DEEPSLATE);
        });
    }

    // --- Builds (server thread) -----------------------------------------------------------------

    /** F plus an offset. Server-side arithmetic only. */
    BlockPos at(Vec3i offset) {
        if (feet == null) throw new BenchException("the arena was used before it was prepared");
        return feet.add(offset);
    }

    /** Where an entity stands on the block at {@code offset}: the centre of its bottom face. */
    Vec3d standingAt(Vec3i offset) {
        return at(offset).toBottomCenterPos();
    }

    /** Sets every block of the box between two offsets from F (both included), on the server thread. */
    void fill(ServerWorld world, int x1, int y1, int z1, int x2, int y2, int z2, Block block) {
        BlockState state = block.getDefaultState();
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    BlockPos pos = at(new Vec3i(x, y, z));
                    if (!world.getBlockState(pos).isOf(block)) world.setBlockState(pos, state);
                }
            }
        }
    }

    /** A square pad of obsidian at y-1, {@code radius} blocks around the anchor (1 gives 3 by 3). */
    void pad(ServerWorld world, Vec3i anchor, int radius) {
        fill(world, anchor.getX() - radius, anchor.getY() - 1, anchor.getZ() - radius,
            anchor.getX() + radius, anchor.getY() - 1, anchor.getZ() + radius, Blocks.OBSIDIAN);
    }

    /** {@code defense-attacker}'s 3 by 3 obsidian floor under F (a declared exception to the fairness rule). */
    public void obsidianFloor() {
        bench.onServer(srv -> fill(srv.getOverworld(), -1, -1, -1, 1, -1, 1, Blocks.OBSIDIAN));
    }

    // --- Loadouts -------------------------------------------------------------------------------

    /**
     * The standard loadout: netherite with blast protection IV, an offhand totem and
     * {@value #INVENTORY_TOTEMS} more in the inventory, AutoTotem on in Strict mode; hotbar 0 end
     * crystals, hotbar 1 obsidian, hotbar 2 a diamond pickaxe when {@code pickaxe}. It returns once the
     * client holds it.
     */
    public void loadout(boolean pickaxe) {
        AutoTotem totem = bench.meteor(AutoTotem.class);
        bench.setting(totem, "General", "mode", AutoTotem.Mode.Strict);
        String name = bench.player();
        bench.onServer(srv -> {
            ServerPlayerEntity player = player(srv, name);
            PlayerInventory inventory = player.getInventory();
            inventory.clear();
            for (EquipmentSlot slot : ARMOUR) player.equipStack(slot, armour(srv.getRegistryManager(), slot));
            player.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            for (int i = 0; i < INVENTORY_TOTEMS; i++) {
                inventory.setStack(PlayerInventory.HOTBAR_SIZE + i, new ItemStack(Items.TOTEM_OF_UNDYING));
            }
            inventory.setStack(0, new ItemStack(Items.END_CRYSTAL, 64));
            inventory.setStack(1, new ItemStack(Items.OBSIDIAN, 64));
            if (pickaxe) inventory.setStack(2, new ItemStack(Items.DIAMOND_PICKAXE));
        });
        boolean armoured = bench.fromServer(srv -> {
            RegistryEntry<Enchantment> blast = blastProtection(srv.getRegistryManager());
            ServerPlayerEntity player = player(srv, name);
            return ARMOUR.stream().allMatch(slot -> EnchantmentHelper.getLevel(blast, player.getEquippedStack(slot)) == 4);
        });
        if (!armoured) throw new BenchException("the loadout's armour has no blast protection IV");
        awaitClient(player -> holds(player, INVENTORY_TOTEMS + 1, Items.TOTEM_OF_UNDYING)
            && holds(player, 64, Items.END_CRYSTAL) && holds(player, 64, Items.OBSIDIAN)
            && (!pickaxe || holds(player, 1, Items.DIAMOND_PICKAXE))
            && player.getEquippedStack(EquipmentSlot.FEET).isOf(Items.NETHERITE_BOOTS), "the standard loadout");
        bench.onClient(client -> {
            if (!totem.isActive()) totem.enable();
        });
    }

    /** The bare loadout: one offhand totem, nothing else, AutoTotem off. It returns once the client holds it. */
    public void bare() {
        AutoTotem totem = bench.meteor(AutoTotem.class);
        bench.onClient(client -> {
            if (totem.isActive()) totem.disable();
        });
        String name = bench.player();
        bench.onServer(srv -> {
            ServerPlayerEntity player = player(srv, name);
            player.getInventory().clear();
            player.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        });
        awaitClient(player -> player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)
            && holds(player, 1, Items.TOTEM_OF_UNDYING) && ARMOUR.stream().allMatch(s -> player.getEquippedStack(s).isEmpty()),
            "the bare loadout");
    }

    /** A netherite armour piece for {@code slot} with blast protection IV (the player's and the sparring's). */
    static ItemStack armour(DynamicRegistryManager registries, EquipmentSlot slot) {
        Item item = switch (slot) {
            case HEAD -> Items.NETHERITE_HELMET;
            case CHEST -> Items.NETHERITE_CHESTPLATE;
            case LEGS -> Items.NETHERITE_LEGGINGS;
            case FEET -> Items.NETHERITE_BOOTS;
            default -> throw new BenchException("no armour for the slot " + slot.getName());
        };
        ItemStack stack = new ItemStack(item);
        stack.addEnchantment(blastProtection(registries), 4);
        return stack;
    }

    static List<EquipmentSlot> armourSlots() {
        return ARMOUR;
    }

    private static RegistryEntry<Enchantment> blastProtection(DynamicRegistryManager registries) {
        return registries.getOrThrow(RegistryKeys.ENCHANTMENT).getOrThrow(Enchantments.BLAST_PROTECTION);
    }

    /** The real player on the server, by name. */
    static ServerPlayerEntity player(MinecraftServer srv, String name) {
        ServerPlayerEntity player = srv.getPlayerManager().getPlayer(name);
        if (player == null) throw new BenchException("the player is not on the server");
        return player;
    }

    /** Exactly {@code count} of {@code item} across the player's inventory, offhand and armour included. */
    private static boolean holds(PlayerEntity player, int count, Item item) {
        int found = 0;
        PlayerInventory inventory = player.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isOf(item)) found += stack.getCount();
        }
        return found == count;
    }

    /** Waits until the client's player satisfies {@code ready}, at most {@value #LOADOUT_SYNC_TICKS} ticks. */
    private void awaitClient(Predicate<PlayerEntity> ready, String what) {
        for (int i = 0; i < LOADOUT_SYNC_TICKS; i++) {
            if (bench.fromClient((MinecraftClient client) -> client.player != null && ready.test(client.player))) return;
            bench.ticks(1);
        }
        throw new BenchException("the client did not get " + what + " within " + LOADOUT_SYNC_TICKS + " ticks");
    }
}
