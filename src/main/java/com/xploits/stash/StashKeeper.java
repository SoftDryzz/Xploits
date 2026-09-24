package com.xploits.stash;

import com.xploits.XploitsAddon;
import com.xploits.console.core.Level;
import com.xploits.shared.XploitsModule;
import com.xploits.shared.Texts;
import com.xploits.shared.core.PositionedMsg;
import com.xploits.shared.core.i18n.Msg;
import com.xploits.stash.core.ContainerKey;
import com.xploits.stash.core.ContainerSnapshot;
import com.xploits.stash.core.ContainerType;
import com.xploits.stash.core.NestedShulker;
import com.xploits.stash.core.StashIndex;
import com.xploits.stash.core.StashStore;
import com.xploits.stash.core.StashText;
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
import net.minecraft.block.enums.ChestType;
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
 * Records the contents of the containers you open and of the shulkers you see (spec §5).
 * It is PASSIVE: it does not touch the interactionManager and cannot move a single item.
 */
public class StashKeeper extends XploitsModule {
    private static final int SAVE_EVERY_TICKS = 100;
    /** Minimum ticks watching a screen before an empty read is accepted as good. */
    private static final int MIN_OBSERVE_TICKS = 20;
    /** Ticks a candidate may wait without a screen before it expires (spec: one second of margin). */
    private static final int CANDIDATE_TIMEOUT_TICKS = 20;
    /** Save failures in a row before giving up and warning only once. */
    private static final int MAX_SAVE_FAILURES = 3;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("notify")
        .description(Texts.startupText(StashText.SETTING_NOTIFY))
        .defaultValue(false)
        .build()
    );

    private StashIndex index = new StashIndex();
    private StashStore store;

    private BlockPos candidate;
    /** Ticks `candidate` has been waiting for a screen to consume it. */
    private int candidateTicks;

    /** syncId of the ScreenHandler the snapshot in progress is tied to, or null if there is none. */
    private Integer openSyncId;
    private ContainerKey openKey;
    private ContainerType openType;
    private Map<String, Integer> openItems = new LinkedHashMap<>();
    private List<NestedShulker> openNested = new ArrayList<>();
    private int observedTicks;
    private boolean sawContent;

    /** syncId of the last container screen that opened without a candidate and was already warned about. */
    private Integer unindexedWarnedSyncId;

    private boolean dirty;
    private int ticks;
    private int saveFailures;
    private boolean saveDisabled;

    public StashKeeper() {
        super(XploitsAddon.CATEGORY, "stash-keeper", Texts.startupText(StashText.MODULE_DESC));
    }

    public StashIndex index() {
        return index;
    }

    @Override
    public void onActivate() {
        saveFailures = 0;
        saveDisabled = false;
        store = new StashStore(storeFile());
        try {
            index = store.load();
        } catch (IOException e) {
            // Do not carry on with an empty index: that is what would erase the corrupt file on the
            // next save. Warn, leave the module without a store (saveNow() does nothing without
            // one) and turn it off, just as KitRequester.onActivate() does with the same problem.
            errorPrivate(new PositionedMsg(Msg.of(StashText.READ_FAILED, "detail", String.valueOf(e.getMessage())),
                Msg.of(StashText.READ_FAILED_LOG)));
            index = new StashIndex();
            store = null;
            toggle();
            return;
        }
        dirty = false;
        clearOpen();
    }

    @Override
    public void onDeactivate() {
        flushOpen();
        if (dirty) saveNow();
    }

    @EventHandler
    private void onInteractBlock(InteractBlockEvent event) {
        if (mc.world == null) return;
        BlockPos pos = event.result.getBlockPos();
        if (typeOf(mc.world.getBlockState(pos).getBlock()) != null) {
            candidate = pos.toImmutable();
            candidateTicks = 0;
        } else {
            // Not a container: whatever candidate was pending no longer makes sense.
            candidate = null;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        readOpenScreen();
        expireCandidate();

        if (++ticks >= SAVE_EVERY_TICKS) {
            ticks = 0;
            if (dirty) saveNow();
        }
    }

    /**
     * With no guarantee that the server opens the screen (denied, lost packet, a click that does
     * not go through...), an unconsumed candidate would stay pending forever and the next unrelated
     * screen to open would inherit it (e.g. a minecart or a boat with a chest, which are entities
     * and never fire InteractBlockEvent). It expires on its own, with the same margin that
     * MIN_OBSERVE_TICKS already uses for premature snapshots.
     */
    private void expireCandidate() {
        if (candidate == null) return;
        if (++candidateTicks >= CANDIDATE_TIMEOUT_TICKS) candidate = null;
    }

    /**
     * Warns that this container screen opened without a valid candidate to tie it to, and so will
     * not be indexed. It may be down to lag (the candidate expires before the screen arrives, in
     * which case reopening does work) or to GenericContainerScreenHandler also backing dispensers,
     * droppers and minecart/boat chests, which this module never indexes (there is no way to tell
     * one case from the other from here, so the text promises nothing that does not hold in both).
     * Only once per screen, using the syncId so as not to repeat the warning on every tick while
     * it stays open.
     */
    private void warnUnindexed(int syncId) {
        if (unindexedWarnedSyncId != null && unindexedWarnedSyncId == syncId) return;
        unindexedWarnedSyncId = syncId;
        warning(StashText.NOT_INDEXED);
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        // When the screen changes, whatever was in progress is final.
        flushOpen();
    }

    /** Rereads the slots of the open container. Done every tick because they are still empty on opening (spec §2). */
    private void readOpenScreen() {
        if (mc.player == null) return;
        if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler)) return;

        if (openSyncId == null || openSyncId != handler.syncId) {
            // A screen other than the snapshot in progress: a new one only starts if there is a
            // candidate pending from an InteractBlockEvent on a known container. Otherwise this
            // screen is left alone, so it cannot inherit another container's key (dispenser,
            // dropper, boat/minecart chest...).
            if (candidate == null || mc.world == null) {
                warnUnindexed(handler.syncId);
                return;
            }

            ContainerType type = typeOf(mc.world.getBlockState(candidate).getBlock());
            if (type == null) {
                candidate = null;
                warnUnindexed(handler.syncId);
                return;
            }

            openKey = keyFor(candidate, type);
            openType = type;
            openSyncId = handler.syncId;
            observedTicks = 0;
            sawContent = false;
            candidate = null; // the candidate is consumed only once
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

    /** Reads a shulker's contents from the item itself, without opening it (spec §2). */
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

    /** Derives the color from the item id (e.g. "minecraft:purple_shulker_box" -> "purple"). */
    private static String colorOf(String shulkerId) {
        String path = shulkerId.startsWith("minecraft:") ? shulkerId.substring("minecraft:".length()) : shulkerId;
        if (path.equals("shulker_box")) return null; // undyed
        String suffix = "_shulker_box";
        String color = path.endsWith(suffix) ? path.substring(0, path.length() - suffix.length()) : path;
        return color.isEmpty() ? null : color;
    }

    /**
     * Flushes the snapshot in progress into the index, if there is one and it is reliable: either
     * non-empty contents were read at some point, or the screen was watched long enough to trust
     * that an empty one is real and not a premature read (the packets with the contents arrive
     * after the screen opens, spec §2).
     */
    private void flushOpen() {
        if (openKey == null) return;

        if (sawContent || observedTicks >= MIN_OBSERVE_TICKS) {
            index.put(new ContainerSnapshot(openKey, openType, System.currentTimeMillis(), openItems, openNested));
            dirty = true;
            if (notify.get()) {
                // console: logged separately
                ChatUtils.info("Xploits", "%s", Texts.render(StashText.INDEXED, // i18n: allowed (chat prefix and format)
                    "container", openKey.id(), "types", openItems.size(), "shulkers", openNested.size()));
                logToConsole(Level.INFO, Msg.of(StashText.INDEXED_LOG,
                    "where", openKey.withoutPosition(null, null, null), "types", openItems.size(), "shulkers", openNested.size()));
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
        if (store == null || saveDisabled) return;
        try {
            store.save(index);
            dirty = false;
            saveFailures = 0;
        } catch (IOException e) {
            saveFailures++;
            if (saveFailures >= MAX_SAVE_FAILURES) {
                // Without this, a persistent failure (full disk, permissions...) would print a new
                // line every SAVE_EVERY_TICKS forever. One warning, and it stops trying until the
                // next activation.
                saveDisabled = true;
                errorPrivate(new PositionedMsg(
                    Msg.of(StashText.SAVE_FAILED, "attempts", saveFailures, "detail", String.valueOf(e.getMessage())),
                    Msg.of(StashText.SAVE_FAILED_LOG, "attempts", saveFailures)));
            }
        }
    }

    /** Double chest: the canonical key is the lesser of the two halves (spec §4.2). */
    private ContainerKey keyFor(BlockPos pos, ContainerType type) {
        if (type == ContainerType.ENDER_CHEST) return ContainerKey.ENDER;

        String dimension = mc.world.getRegistryKey().getValue().toString();
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
        if (chestType == ChestType.SINGLE) return null;
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

    public Msg status() {
        return Msg.of(StashText.STATUS, "containers", index.size(), "shulkers", index.totalShulkers());
    }

    @Override
    public String activity() {
        return Texts.render(StashText.NOW_CONTAINERS, "count", index.size());
    }
}
