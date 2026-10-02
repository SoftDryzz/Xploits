package com.xploits.restock.litematica;

import com.xploits.printer.core.Guards;
import com.xploits.restock.TargetSource;
import net.fabricmc.loader.api.FabricLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * restock's door to Litematica (restock spec §3; the printer's M3). This class names Litematica only through reflection,
 * so the module can load it with or without the mod; {@link LitematicaSource}, which imports Litematica, is made only
 * after {@link #installed()} and the signature check passed. A missing member is a refusal naming the version built
 * against — never a {@code NoSuchMethodError} in the middle of a session.
 */
public final class LitematicaAccess {
    /** The Litematica version restock is compiled and tested against. */
    public static final String BUILT_AGAINST = "0.26.14";

    /** Class, method, parameter types ("int" for the primitive). */
    private static final String[][] METHODS = {
        {"fi.dy.masa.litematica.data.DataManager", "getSchematicPlacementManager"},
        {"fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager", "getSelectedSchematicPlacement"},
        {"fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager", "getAllSchematicsPlacements"},
        {"fi.dy.masa.litematica.schematic.placement.SchematicPlacement", "isEnabled"},
        {"fi.dy.masa.litematica.schematic.placement.SchematicPlacement", "getOrigin"},
        {"fi.dy.masa.litematica.schematic.placement.SchematicPlacement", "getRotation"},
        {"fi.dy.masa.litematica.schematic.placement.SchematicPlacement", "getMirror"},
        {"fi.dy.masa.litematica.schematic.placement.SchematicPlacement", "getEclosingBox"},
        {"fi.dy.masa.litematica.schematic.placement.SchematicPlacement", "getSchematic"},
        {"fi.dy.masa.litematica.schematic.placement.SchematicPlacement", "getSubRegionBoxes",
            "fi.dy.masa.litematica.schematic.placement.SubRegionPlacement$RequiredEnabled"},
        {"fi.dy.masa.litematica.selection.Box", "getPos1"},
        {"fi.dy.masa.litematica.selection.Box", "getPos2"},
        {"fi.dy.masa.litematica.schematic.LitematicaSchematic", "getSubRegionContainer", "java.lang.String"},
        {"fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer", "getBlockCounts"},
        {"fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer", "getPalette"},
        {"fi.dy.masa.litematica.schematic.container.ILitematicaBlockStatePalette", "getBlockState", "int"},
        {"fi.dy.masa.litematica.schematic.container.ILitematicaBlockStatePalette", "getPaletteSize"},
        {"fi.dy.masa.litematica.world.SchematicWorldHandler", "getSchematicWorld"},
        {"fi.dy.masa.litematica.world.WorldSchematic", "getChunkSource"},
        {"fi.dy.masa.litematica.world.ChunkManagerSchematic", "getChunkIfExists", "int", "int"},
        {"fi.dy.masa.litematica.world.ChunkSchematic", "getState"},
        {"fi.dy.masa.litematica.world.ChunkSchematicState", "atLeast", "fi.dy.masa.litematica.world.ChunkSchematicState"},
        {"fi.dy.masa.malilib.config.IConfigBoolean", "getBooleanValue"},
    };

    /** Class, static field. */
    private static final String[][] FIELDS = {
        {"fi.dy.masa.litematica.schematic.placement.SubRegionPlacement$RequiredEnabled", "PLACEMENT_ENABLED"},
        {"fi.dy.masa.litematica.world.ChunkSchematicState", "FILLED"},
        {"fi.dy.masa.litematica.config.Configs$Visuals", "ENABLE_RENDERING"},
        {"fi.dy.masa.litematica.config.Configs$Generic", "EASY_PLACE_MODE"},
        {"fi.dy.masa.litematica.config.Configs$Generic", "EASY_PLACE_POST_REWRITE"},
        {"fi.dy.masa.litematica.config.Configs$Generic", "PLACEMENT_RESTRICTION"},
    };

    private LitematicaAccess() {
    }

    public static boolean installed() {
        return FabricLoader.getInstance().isModLoaded("litematica") && FabricLoader.getInstance().isModLoaded("malilib");
    }

    /** The selected placement as a target source. Call only when {@link #installed()}. Client thread. */
    public static TargetSource open(long maxVolume) {
        List<String> missing = missing();
        if (!missing.isEmpty()) {
            return TargetSource.refusing(new Guards.Refusal(Guards.Reason.LITEMATICA_API, String.join(", ", missing)));
        }
        return new LitematicaSource(maxVolume);
    }

    /** The members of the signature that this Litematica lacks, as {@code Class.member}. */
    static List<String> missing() {
        List<String> out = new ArrayList<>();
        ClassLoader loader = LitematicaAccess.class.getClassLoader();
        for (String[] m : METHODS) {
            try {
                Class<?> owner = Class.forName(m[0], false, loader);
                Class<?>[] params = new Class<?>[m.length - 2];
                for (int i = 2; i < m.length; i++) params[i - 2] = type(m[i], loader);
                owner.getMethod(m[1], params);
            } catch (ReflectiveOperationException | LinkageError e) {
                out.add(simple(m[0]) + "." + m[1]);
            }
        }
        for (String[] f : FIELDS) {
            try {
                Class.forName(f[0], false, loader).getField(f[1]);
            } catch (ReflectiveOperationException | LinkageError e) {
                out.add(simple(f[0]) + "." + f[1]);
            }
        }
        return out;
    }

    private static Class<?> type(String name, ClassLoader loader) throws ClassNotFoundException {
        return name.equals("int") ? int.class : Class.forName(name, false, loader);
    }

    private static String simple(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }
}
