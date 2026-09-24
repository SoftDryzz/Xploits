package com.xploits.shared;

import com.xploits.XploitsAddon;
import com.xploits.shared.core.i18n.Msg;
import com.xploits.shared.core.migration.ConsoleFolder;
import com.xploits.shared.core.migration.MigrationText;
import com.xploits.shared.core.migration.ModulesFile;
import com.xploits.shared.core.migration.ModulesTree;
import com.xploits.shared.core.migration.SettingsRenames;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The 0.4.0 settings migration (code-in-English design §7). Runs first in {@code onInitialize}, before
 * Meteor loads anything; never throws; tells the player once, when a world exists.
 *
 * <p>{@code modules.nbt} keeps module, group, setting and value names in a known tree shape, handled by
 * {@link ModulesTree#migrate}; but some modules also save other modules' names as plain strings in a
 * setting list (CrystalAura's {@code pause-modules}, Surround's {@code modules}), so {@code modules.nbt}
 * gets a second, string-only sweep with {@link ModulesTree#renameStrings} on top. {@code hud.nbt} (hidden
 * elements) only ever holds module names as plain strings, so it gets the string-only sweep alone.
 * {@code config.nbt} is deliberately left out: Meteor's own {@code Systems.init()} loads it before any
 * addon's {@code onInitialize} runs and rewrites it whole on shutdown, so anything this class wrote to it
 * would just be discarded — migrating it here would be a no-op at best.
 *
 * <p>Each settings file is migrated independently: one corrupt or locked file is reported and skipped, the
 * rest still get migrated. Likewise, a settings-file failure never stops the separate console-folder move.
 */
public final class SettingsMigration {
    private static final Notice NOTICE = new Notice();

    private SettingsMigration() {
    }

    public static void run() {
        try {
            Path root = MeteorClient.FOLDER.toPath();
            migrateSettings(root);
            migrateConsoleFolder(root);
            // Subscribing can throw too (Orbit throws when it has no lambda factory for the handler's
            // package); a missed notice must not stop the addon from loading.
            if (!NOTICE.pending.isEmpty()) MeteorClient.EVENT_BUS.subscribe(NOTICE);
        } catch (RuntimeException e) {
            XploitsAddon.LOG.error("Xploits: settings migration could not finish", e);
        }
    }

    private static void migrateSettings(Path root) {
        List<Path> files;
        try {
            files = settingsFiles(root);
        } catch (IOException | RuntimeException e) {
            XploitsAddon.LOG.error("Xploits: settings migration could not list settings files ({})", e.getClass().getSimpleName(), e);
            files = List.of();
        }
        int rewritten = 0;
        for (Path file : files) {
            try {
                ModulesTree.Result r = migrate(file);
                if (ModulesFile.writeIfChanged(file, r, (tree, target) -> NbtIo.write((NbtCompound) toNbt(tree), target))) {
                    rewritten++;
                }
            } catch (IOException | RuntimeException e) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                XploitsAddon.LOG.error("Xploits: settings migration failed for {} ({})", relative, e.getClass().getSimpleName(), e);
                NOTICE.pending.add(Msg.of(MigrationText.FAILED, "file", relative, "detail", e.getClass().getSimpleName()));
            }
        }
        if (rewritten > 0) {
            XploitsAddon.LOG.info("Xploits: settings migration done ({} files rewritten)", rewritten);
            NOTICE.pending.add(Msg.of(MigrationText.DONE, "files", rewritten));
        }
    }

    private static void migrateConsoleFolder(Path root) {
        try {
            ConsoleFolder.Outcome outcome = ConsoleFolder.run(root.resolve("xploits"));
            switch (outcome) {
                case BUSY, PARTIAL -> {
                    XploitsAddon.LOG.warn("Xploits: console folder move {} ({}): will retry next start",
                        outcome == ConsoleFolder.Outcome.BUSY ? "could not start" : "partially failed", outcome);
                    NOTICE.pending.add(Msg.of(MigrationText.CONSOLE_FOLDER_BUSY));
                }
                case NEW_ALREADY_EXISTS ->
                    XploitsAddon.LOG.warn("Xploits: xploits/console already exists next to xploits/consola: left both untouched");
                case MOVED, NOTHING -> {
                    // Nothing to tell the player.
                }
            }
        } catch (RuntimeException e) {
            XploitsAddon.LOG.error("Xploits: console folder migration failed", e);
        }
    }

    /** {@code modules.nbt} gets the structured migration plus a string sweep; every other file, only the sweep. */
    private static ModulesTree.Result migrate(Path file) throws IOException {
        Object tree = toTree(NbtIo.read(file));
        if (!file.getFileName().toString().equals("modules.nbt")) {
            return ModulesTree.renameStrings(tree, SettingsRenames.V0_4_0.modules());
        }
        ModulesTree.Result structured = ModulesTree.migrate(tree, SettingsRenames.V0_4_0);
        ModulesTree.Result strings = ModulesTree.renameStrings(structured.tree(), SettingsRenames.V0_4_0.modules());
        return new ModulesTree.Result(strings.tree(), structured.changed() || strings.changed());
    }

    private static List<Path> settingsFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String name : List.of("modules.nbt", "hud.nbt")) {
            if (Files.isRegularFile(root.resolve(name))) files.add(root.resolve(name));
        }
        Path profiles = root.resolve("profiles");
        if (Files.isDirectory(profiles)) {
            try (Stream<Path> dirs = Files.list(profiles)) {
                for (Path d : dirs.filter(Files::isDirectory).toList()) {
                    for (String name : List.of("modules.nbt", "hud.nbt")) {
                        if (Files.isRegularFile(d.resolve(name))) files.add(d.resolve(name));
                    }
                }
            }
        }
        return files;
    }

    static Object toTree(NbtElement e) {
        if (e instanceof NbtCompound c) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (String k : c.getKeys()) m.put(k, toTree(c.get(k)));
            return m;
        }
        if (e instanceof NbtList l) {
            List<Object> out = new ArrayList<>();
            for (NbtElement x : l) out.add(toTree(x));
            return out;
        }
        if (e instanceof NbtString s) return s.value();
        return e;
    }

    static NbtElement toNbt(Object o) {
        if (o instanceof Map<?, ?> m) {
            NbtCompound c = new NbtCompound();
            m.forEach((k, v) -> c.put((String) k, toNbt(v)));
            return c;
        }
        if (o instanceof List<?> l) {
            NbtList out = new NbtList();
            for (Object v : l) out.add(toNbt(v));
            return out;
        }
        if (o instanceof String s) return NbtString.of(s);
        return (NbtElement) o;
    }

    private static final class Notice {
        private final List<Msg> pending = new ArrayList<>();

        @EventHandler
        private void onTick(TickEvent.Post event) {
            if (MeteorClient.mc.world == null) return;
            for (Msg m : pending) {
                // console: logged separately
                ChatUtils.infoPrefix("Xploits", "%s", Texts.render(m));
            }
            pending.clear();
            MeteorClient.EVENT_BUS.unsubscribe(this);
        }
    }
}
