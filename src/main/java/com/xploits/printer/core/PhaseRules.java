package com.xploits.printer.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What phase 1 places, what matches, what is wrong and what is never broken (printer spec §5.1), as tested tables (item
 * 4b). Blocks are registry ids; the facts no table can know come measured in {@link BlockFacts}. The tables err on the
 * safe side: a block wrongly listed as interactive or as hanging on its neighbour is only left alone and reported.
 */
public final class PhaseRules {
    private PhaseRules() {
    }

    /** What a position holds against what the schematic wants (§5.1 items 2–3a). */
    public enum Contents { OUTSIDE, UNKNOWN, AIR_TARGET, LATER, MATCHES, CONVERTED, MISSING, DIFFERENT }

    /** Why a block is not broken (§5.1 item 4), in the order {@link #neverBreak} tests them. */
    public enum NeverBreak {
        OUTSIDE, UNKNOWN, AIR_TARGET, NOT_DIFFERENT, BLOCK_ENTITY, UNBREAKABLE, NEIGHBOURS_UNKNOWN, STANDING_ON,
        HOLDS_FALLING, HOLDS_ATTACHED, NEXT_TO_FLUID, TOO_SLOW, ALREADY_BROKEN
    }

    /**
     * Everything the never-break rules read about one position.
     *
     * @param neighbours        the world's six neighbours; a missing one is unknown, and unknown is never "safe"
     * @param standingOn        the player's feet rest on this block
     * @param brokenBefore      the printer already broke this position in this session (the loop guard)
     * @param ticksWithBestTool held-mining ticks with the best usable hotbar tool, -1 when no tool qualifies
     * @param capTicks          {@link PrinterLimits#breakCapTicks()}
     */
    public record BreakView(Cell cell, Map<Face, BlockFacts> neighbours, boolean standingOn, boolean brokenBefore,
                            int ticksWithBestTool, int capTicks) {
    }

    private static final List<String> COLORS = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime",
        "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");

    /** The dyed carpets, the one non-full family of phase 1 (support: the block below is not air). */
    static final Set<String> CARPETS = suffixed(COLORS, "_carpet");

    /** Vanilla's {@code #minecraft:replaceable} tag, 1.21.11 (read in the yarn jar). */
    static final Set<String> REPLACEABLE = ids("air", "water", "lava", "short_grass", "fern", "dead_bush", "bush",
        "short_dry_grass", "tall_dry_grass", "seagrass", "tall_seagrass", "fire", "soul_fire", "snow", "vine",
        "glow_lichen", "resin_clump", "light", "tall_grass", "large_fern", "structure_void", "void_air", "cave_air",
        "bubble_column", "warped_roots", "nether_sprouts", "crimson_roots", "leaf_litter", "hanging_roots");

    /** Blocks a click opens, toggles or uses instead of placing against (also never a support, §5.2). */
    static final Set<String> INTERACTIVE = ids("crafting_table", "crafter", "furnace", "blast_furnace", "smoker",
        "barrel", "hopper", "dispenser", "dropper", "brewing_stand", "enchanting_table", "beacon", "grindstone",
        "stonecutter", "loom", "cartography_table", "smithing_table", "note_block", "jukebox", "lectern", "bell", "lever",
        "repeater", "comparator", "daylight_detector", "cake", "composter", "respawn_anchor", "flower_pot",
        "decorated_pot", "chiseled_bookshelf", "structure_block", "jigsaw", "test_block", "test_instance_block",
        "campfire", "soul_campfire", "dragon_egg", "redstone_ore", "deepslate_redstone_ore", "vault", "trial_spawner",
        "sweet_berry_bush", "cave_vines", "cave_vines_plant");
    static final List<String> INTERACTIVE_SUFFIXES = List.of("_door", "_trapdoor", "_fence_gate", "_button", "_bed",
        "shulker_box", "_sign", "candle_cake", "_shelf", "chest", "anvil", "cauldron", "_bulb", "command_block");
    static final List<String> INTERACTIVE_PREFIXES = List.of("minecraft:potted_");

    /** Blocks that turn into something else on their own (§5.1 item 1). */
    static final Set<String> CHANGES_BY_ITSELF = union(ids("dirt", "grass_block", "mycelium", "farmland", "frosted_ice",
        "ice", "snow", "sponge", "wet_sponge", "copper_block", "exposed_copper", "weathered_copper", "oxidized_copper",
        "cut_copper", "exposed_cut_copper", "weathered_cut_copper", "oxidized_cut_copper", "chiseled_copper",
        "exposed_chiseled_copper", "weathered_chiseled_copper", "oxidized_chiseled_copper", "tube_coral_block",
        "brain_coral_block", "bubble_coral_block", "fire_coral_block", "horn_coral_block"),
        suffixed(COLORS, "_concrete_powder"));

    /** A target → what nature makes of it; such a block is neither matching nor wrong (§5.1 item 3). */
    static final Map<String, Set<String>> NATURAL_CONVERSIONS = conversions();

    /** Properties the world or time sets, never compared (§5.1 item 2; phase 4 compares the others). */
    static final Set<String> WORLD_SET_PROPERTIES = Set.of("snowy", "distance", "persistent", "note", "instrument",
        "powered", "north", "east", "south", "west", "up", "down", "age", "stage", "moisture", "power", "lit",
        "triggered", "enabled", "in_wall", "attached", "disarmed", "occupied", "honey_level", "hatch", "drag", "level",
        "thickness", "shrieking", "can_summon", "bloom");

    /** Blocks that stand on the block below them. */
    static final Set<String> NEEDS_SUPPORT_BELOW = ids("torch", "soul_torch", "redstone_torch", "copper_torch",
        "redstone_wire", "repeater", "comparator", "rail", "powered_rail", "detector_rail", "activator_rail", "snow",
        "cactus", "cactus_flower", "sugar_cane", "bamboo", "bamboo_sapling", "flower_pot", "cake", "candle",
        "sea_pickle", "lily_pad", "dead_bush", "bush", "firefly_bush", "short_grass", "tall_grass", "fern", "large_fern",
        "short_dry_grass", "tall_dry_grass", "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "oxeye_daisy",
        "cornflower", "lily_of_the_valley", "wither_rose", "torchflower", "sunflower", "lilac", "rose_bush", "peony",
        "pitcher_plant", "pitcher_crop", "torchflower_crop", "open_eyeblossom", "closed_eyeblossom", "brown_mushroom",
        "red_mushroom", "crimson_fungus", "warped_fungus", "crimson_roots", "warped_roots", "nether_sprouts", "wheat",
        "carrots", "potatoes", "beetroots", "melon_stem", "pumpkin_stem", "attached_melon_stem", "attached_pumpkin_stem",
        "nether_wart", "sweet_berry_bush", "scaffolding", "azalea", "flowering_azalea", "big_dripleaf",
        "big_dripleaf_stem", "small_dripleaf", "pink_petals", "wildflowers", "leaf_litter", "lever", "kelp",
        "kelp_plant", "seagrass", "tall_seagrass", "turtle_egg", "frogspawn");
    static final List<String> NEEDS_SUPPORT_BELOW_SUFFIXES = List.of("_carpet", "_pressure_plate", "_sapling",
        "_banner", "_sign", "_door", "_candle", "_tulip", "_button", "_coral", "_coral_fan");

    /** Blocks that may hang on the side of the block next to them (the facing is not checked: safe side). */
    static final Set<String> HANGS_ON_SIDE = ids("ladder", "lever", "tripwire_hook", "vine", "glow_lichen",
        "sculk_vein", "resin_clump", "cocoa", "wall_torch", "soul_wall_torch", "redstone_wall_torch", "copper_wall_torch",
        "small_amethyst_bud", "medium_amethyst_bud", "large_amethyst_bud", "amethyst_cluster", "bell");
    static final List<String> HANGS_ON_SIDE_SUFFIXES = List.of("_wall_sign", "_wall_hanging_sign", "_wall_banner",
        "_button", "_coral_wall_fan");

    /** Blocks that may hang from the block above them. */
    static final Set<String> HANGS_BELOW = ids("lantern", "soul_lantern", "hanging_roots", "spore_blossom",
        "pointed_dripstone", "weeping_vines", "weeping_vines_plant", "cave_vines", "cave_vines_plant", "vine",
        "glow_lichen", "sculk_vein", "resin_clump", "bell", "small_amethyst_bud", "medium_amethyst_bud",
        "large_amethyst_bud", "amethyst_cluster", "mangrove_propagule", "pale_hanging_moss", "lever");
    static final List<String> HANGS_BELOW_SUFFIXES = List.of("_hanging_sign", "_lantern", "_button");

    // ---- the tables as functions -------------------------------------------------------------------------------

    public static boolean carpet(String id) {
        return CARPETS.contains(id);
    }

    public static boolean interactive(String id) {
        return INTERACTIVE.contains(id) || endsWithAny(id, INTERACTIVE_SUFFIXES)
            || INTERACTIVE_PREFIXES.stream().anyMatch(id::startsWith);
    }

    public static boolean changesByItself(String id) {
        return CHANGES_BY_ITSELF.contains(id);
    }

    public static boolean naturalConversion(String targetId, String worldId) {
        return NATURAL_CONVERSIONS.getOrDefault(targetId, Set.of()).contains(worldId);
    }

    public static boolean ignoredProperty(String name) {
        return WORLD_SET_PROPERTIES.contains(name);
    }

    public static boolean needsSupportBelow(String id) {
        return NEEDS_SUPPORT_BELOW.contains(id) || endsWithAny(id, NEEDS_SUPPORT_BELOW_SUFFIXES);
    }

    public static boolean hangsOnSide(String id) {
        return HANGS_ON_SIDE.contains(id) || endsWithAny(id, HANGS_ON_SIDE_SUFFIXES);
    }

    public static boolean hangsBelow(String id) {
        return HANGS_BELOW.contains(id) || endsWithAny(id, HANGS_BELOW_SUFFIXES);
    }

    // ---- §5.1 ---------------------------------------------------------------------------------------------------

    /** Item 1: the phase-1 allow-list. */
    public static boolean phaseOne(BlockFacts t) {
        return !t.air() && t.propertyFree() && t.itemPlacesIt() && (t.fullCube() || carpet(t.id()))
            && !t.blockEntity() && !interactive(t.id()) && !t.falling() && !t.waterloggable()
            && !changesByItself(t.id()) && !t.fluid() && !t.replaceable();
    }

    /** Item 2: same block; properties are never compared. */
    public static boolean matches(BlockFacts target, BlockFacts world) {
        return target.id().equals(world.id());
    }

    /** Item 3a: placed into directly — air, or replaceable by both the table and Minecraft. */
    public static boolean placeableInto(BlockFacts world) {
        return world.air() || (world.replaceable() && REPLACEABLE.contains(world.id()));
    }

    /** §5.2: a face may be clicked — not air, not a fluid, replaceable by neither source, not interactive. */
    public static boolean support(BlockFacts world) {
        return !world.air() && !world.fluid() && !world.replaceable() && !REPLACEABLE.contains(world.id())
            && !interactive(world.id());
    }

    /** Items 1–3a. */
    public static Contents classify(Cell cell) {
        if (!cell.inBoxes()) return Contents.OUTSIDE;
        return switch (cell.target().kind()) {
            case UNKNOWN -> Contents.UNKNOWN;
            case AIR -> Contents.AIR_TARGET;
            case BLOCK -> {
                BlockFacts t = cell.target().block();
                BlockFacts w = cell.world();
                if (!phaseOne(t)) yield Contents.LATER;
                if (matches(t, w)) yield Contents.MATCHES;
                if (naturalConversion(t.id(), w.id())) yield Contents.CONVERTED;
                if (placeableInto(w)) yield Contents.MISSING;
                yield Contents.DIFFERENT;
            }
        };
    }

    /** Item 4: the first rule that forbids breaking this position, or empty when it may be broken. */
    public static Optional<NeverBreak> neverBreak(BreakView v) {
        Contents c = classify(v.cell());
        if (c == Contents.OUTSIDE) return Optional.of(NeverBreak.OUTSIDE);
        if (c == Contents.UNKNOWN) return Optional.of(NeverBreak.UNKNOWN);
        if (c == Contents.AIR_TARGET) return Optional.of(NeverBreak.AIR_TARGET);
        if (c != Contents.DIFFERENT) return Optional.of(NeverBreak.NOT_DIFFERENT);
        BlockFacts w = v.cell().world();
        if (w.blockEntity()) return Optional.of(NeverBreak.BLOCK_ENTITY);
        if (w.unbreakable()) return Optional.of(NeverBreak.UNBREAKABLE);
        if (v.neighbours().size() < Face.values().length) return Optional.of(NeverBreak.NEIGHBOURS_UNKNOWN);
        if (v.standingOn()) return Optional.of(NeverBreak.STANDING_ON);
        if (v.neighbours().get(Face.UP).falling()) return Optional.of(NeverBreak.HOLDS_FALLING);
        if (holdsAttached(v.neighbours())) return Optional.of(NeverBreak.HOLDS_ATTACHED);
        if (w.fluid() || v.neighbours().values().stream().anyMatch(BlockFacts::fluid)) {
            return Optional.of(NeverBreak.NEXT_TO_FLUID);
        }
        if (v.ticksWithBestTool() < 0 || v.ticksWithBestTool() > v.capTicks()) return Optional.of(NeverBreak.TOO_SLOW);
        if (v.brokenBefore()) return Optional.of(NeverBreak.ALREADY_BROKEN);
        return Optional.empty();
    }

    private static boolean holdsAttached(Map<Face, BlockFacts> n) {
        if (needsSupportBelow(n.get(Face.UP).id())) return true;
        for (Face side : List.of(Face.NORTH, Face.SOUTH, Face.WEST, Face.EAST)) {
            if (hangsOnSide(n.get(side).id())) return true;
        }
        return hangsBelow(n.get(Face.DOWN).id());
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static boolean endsWithAny(String id, List<String> suffixes) {
        for (String s : suffixes) {
            if (id.endsWith(s)) return true;
        }
        return false;
    }

    private static Set<String> ids(String... names) {
        Set<String> set = new HashSet<>();
        for (String n : names) set.add("minecraft:" + n);
        return Set.copyOf(set);
    }

    private static Set<String> suffixed(List<String> stems, String suffix) {
        Set<String> set = new HashSet<>();
        for (String s : stems) set.add("minecraft:" + s + suffix);
        return Set.copyOf(set);
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> set = new HashSet<>(a);
        set.addAll(b);
        return Set.copyOf(set);
    }

    private static Map<String, Set<String>> conversions() {
        Map<String, Set<String>> m = new HashMap<>();
        put(m, "dirt", "grass_block", "mycelium");
        put(m, "grass_block", "dirt");
        put(m, "mycelium", "dirt");
        put(m, "sponge", "wet_sponge");
        put(m, "wet_sponge", "sponge");
        put(m, "ice", "water");
        put(m, "mud", "clay");
        for (String form : List.of("copper", "cut_copper", "chiseled_copper")) {
            String fresh = form.equals("copper") ? "copper_block" : form;
            put(m, fresh, "exposed_" + form, "weathered_" + form, "oxidized_" + form);
            put(m, "exposed_" + form, "weathered_" + form, "oxidized_" + form);
            put(m, "weathered_" + form, "oxidized_" + form);
        }
        for (String coral : List.of("tube", "brain", "bubble", "fire", "horn")) {
            put(m, coral + "_coral_block", "dead_" + coral + "_coral_block");
        }
        for (String color : COLORS) put(m, color + "_concrete_powder", color + "_concrete");
        return Map.copyOf(m);
    }

    private static void put(Map<String, Set<String>> m, String target, String... becomes) {
        m.put("minecraft:" + target, ids(becomes));
    }
}
