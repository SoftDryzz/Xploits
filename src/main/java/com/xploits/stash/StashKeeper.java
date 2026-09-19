package com.xploits.stash;

import com.xploits.XploitsAddon;
import com.xploits.stash.core.ContainerKey;
import com.xploits.stash.core.ContainerSnapshot;
import com.xploits.stash.core.ContainerType;
import com.xploits.stash.core.NestedShulker;
import com.xploits.stash.core.StashIndex;
import com.xploits.stash.core.StashStore;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.InteractBlockEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Apunta el contenido de los contenedores que abres y de los shulkers que ves (spec §5).
 * Es PASIVO: no toca el interactionManager y no puede mover un solo ítem.
 */
public class StashKeeper extends Module {
    private static final int SAVE_EVERY_TICKS = 100;
    /** Ticks mínimos observando una pantalla antes de aceptar como buena una lectura vacía. */
    private static final int MIN_OBSERVE_TICKS = 20;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("notify")
        .description("Aviso local cuando se indexa un contenedor nuevo.")
        .defaultValue(false)
        .build()
    );

    private StashIndex index = new StashIndex();
    private StashStore store;

    private BlockPos candidate;

    /** syncId del ScreenHandler al que está atada la foto en curso, o null si no hay ninguna. */
    private Integer openSyncId;
    private ContainerKey openKey;
    private ContainerType openType;
    private Map<String, Integer> openItems = new LinkedHashMap<>();
    private List<NestedShulker> openNested = new ArrayList<>();
    private int observedTicks;
    private boolean sawContent;

    private boolean dirty;
    private int ticks;

    public StashKeeper() {
        super(XploitsAddon.CATEGORY, "stash-keeper", "Apunta qué hay en tus contenedores. Nunca mueve nada.");
    }

    public StashIndex index() {
        return index;
    }

    @Override
    public void onActivate() {
        store = new StashStore(storeFile());
        try {
            index = store.load();
        } catch (IOException e) {
            index = new StashIndex();
            error("No se pudo leer el índice: %s", e.getMessage());
        }
        clearOpen();
    }

    @Override
    public void onDeactivate() {
        flushOpen();
        saveNow();
    }

    @EventHandler
    private void onInteractBlock(InteractBlockEvent event) {
        if (mc.world == null) return;
        BlockPos pos = event.result.getBlockPos();
        if (typeOf(mc.world.getBlockState(pos).getBlock()) != null) candidate = pos.toImmutable();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        readOpenScreen();

        if (++ticks >= SAVE_EVERY_TICKS) {
            ticks = 0;
            if (dirty) saveNow();
        }
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        // Al cambiar de pantalla, lo que hubiera en curso ya es definitivo.
        flushOpen();
    }

    /** Relee los slots del contenedor abierto. Se hace por tick porque al abrir aún están vacíos (spec §2). */
    private void readOpenScreen() {
        if (mc.player == null) return;
        if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler)) return;

        if (openSyncId == null || openSyncId != handler.syncId) {
            // Pantalla distinta de la foto en curso: solo empieza una nueva si hay un candidato
            // pendiente de un InteractBlockEvent reconocido. Si no, esta pantalla no se toca:
            // evita heredar la clave de otro contenedor (dispensador, gotero, cofre de bote/minecart...).
            if (candidate == null || mc.world == null) return;

            ContainerType type = typeOf(mc.world.getBlockState(candidate).getBlock());
            if (type == null) {
                candidate = null;
                return;
            }

            openKey = keyFor(candidate, type);
            openType = type;
            openSyncId = handler.syncId;
            observedTicks = 0;
            sawContent = false;
            candidate = null; // el candidato se consume una sola vez
        }

        openItems = new LinkedHashMap<>();
        openNested = new ArrayList<>();

        int containerSlots = handler.getRows() * 9;
        for (int i = 0; i < containerSlots; i++) {
            Slot slot = handler.slots.get(i);
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;

            String id = Registries.ITEM.getId(stack.getItem()).toString();
            if (Utils.isShulker(stack.getItem())) {
                openNested.add(readShulker(i, stack, id));
            } else {
                openItems.merge(id, stack.getCount(), Integer::sum);
            }
        }

        observedTicks++;
        if (!openItems.isEmpty() || !openNested.isEmpty()) sawContent = true;
    }

    /** Lee el contenido de un shulker desde el propio ítem, sin abrirlo (spec §2). */
    private NestedShulker readShulker(int slot, ItemStack stack, String shulkerId) {
        ItemStack[] contents = new ItemStack[27];
        Utils.getItemsInContainerItem(stack, contents);

        Map<String, Integer> items = new LinkedHashMap<>();
        for (ItemStack inside : contents) {
            if (inside == null || inside.isEmpty()) continue;
            items.merge(Registries.ITEM.getId(inside.getItem()).toString(), inside.getCount(), Integer::sum);
        }

        String name = stack.contains(DataComponentTypes.CUSTOM_NAME)
            ? stack.getName().getString()
            : null;

        return new NestedShulker(slot, name, colorOf(shulkerId), items);
    }

    /** Deriva el color del id del ítem (p. ej. "minecraft:purple_shulker_box" -> "purple"). */
    private static String colorOf(String shulkerId) {
        String path = shulkerId.startsWith("minecraft:") ? shulkerId.substring("minecraft:".length()) : shulkerId;
        if (path.equals("shulker_box")) return null; // sin teñir
        String suffix = "_shulker_box";
        String color = path.endsWith(suffix) ? path.substring(0, path.length() - suffix.length()) : path;
        return color.isEmpty() ? null : color;
    }

    /**
     * Vuelca al índice la foto en curso, si hay alguna y es fiable: o bien se leyó contenido no
     * vacío en algún momento, o bien se observó la pantalla el tiempo suficiente para confiar en
     * que un vacío es real y no una lectura prematura (los paquetes con el contenido llegan
     * después de abrirse la pantalla, spec §2).
     */
    private void flushOpen() {
        if (openKey == null) return;

        if (sawContent || observedTicks >= MIN_OBSERVE_TICKS) {
            index.put(new ContainerSnapshot(openKey, openType, System.currentTimeMillis(), openItems, openNested));
            dirty = true;
            if (notify.get()) {
                ChatUtils.info("Xploits", "Indexado %s (%d tipos, %d shulkers).",
                    openKey.id(), openItems.size(), openNested.size());
            }
        }
        clearOpen();
    }

    private void clearOpen() {
        candidate = null;
        openSyncId = null;
        openKey = null;
        openType = null;
        openItems = new LinkedHashMap<>();
        openNested = new ArrayList<>();
        observedTicks = 0;
        sawContent = false;
    }

    private void saveNow() {
        if (store == null) return;
        try {
            store.save(index);
            dirty = false;
        } catch (IOException e) {
            error("No se pudo guardar el índice: %s", e.getMessage());
        }
    }

    /** Cofre doble: la clave canónica es la menor de las dos mitades (spec §4.2). */
    private ContainerKey keyFor(BlockPos pos, ContainerType type) {
        if (type == ContainerType.ENDER_CHEST) return ContainerKey.ENDER;

        String dimension = mc.world.getRegistryKey().getValue().getPath();
        if (mc.world.getBlockEntity(pos) instanceof ChestBlockEntity) {
            BlockPos other = otherHalf(pos);
            if (other != null) {
                return ContainerKey.doubleChest(dimension,
                    pos.getX(), pos.getY(), pos.getZ(),
                    other.getX(), other.getY(), other.getZ());
            }
        }
        return ContainerKey.block(dimension, pos.getX(), pos.getY(), pos.getZ());
    }

    private BlockPos otherHalf(BlockPos pos) {
        var state = mc.world.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock)) return null;
        var chestType = state.get(ChestBlock.CHEST_TYPE);
        if (chestType == net.minecraft.block.enums.ChestType.SINGLE) return null;
        return pos.offset(ChestBlock.getFacing(state));
    }

    private static ContainerType typeOf(Block block) {
        if (block == Blocks.CHEST) return ContainerType.CHEST;
        if (block == Blocks.TRAPPED_CHEST) return ContainerType.TRAPPED_CHEST;
        if (block == Blocks.BARREL) return ContainerType.BARREL;
        if (block == Blocks.ENDER_CHEST) return ContainerType.ENDER_CHEST;
        if (block.asItem() != null && Utils.isShulker(block.asItem())) return ContainerType.SHULKER_BLOCK;
        return null;
    }

    private Path storeFile() {
        return MeteorClient.FOLDER.toPath()
            .resolve("xploits").resolve("stash").resolve(Utils.getFileWorldName()).resolve("index.json");
    }

    public String status() {
        long shulkers = index.all().stream().mapToLong(s -> s.nested().size()).sum();
        return String.format("%d contenedores indexados, %d shulkers dentro. Recuerda: los cofres solo entran al abrirlos; los shulkers, con verlos.",
            index.size(), shulkers);
    }
}
