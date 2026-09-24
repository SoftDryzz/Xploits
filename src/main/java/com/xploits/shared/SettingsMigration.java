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
 * elements) and the root-only {@code config.nbt} (Meteor's own {@code hiddenModules}) only ever hold module
 * names as plain strings, so they get the string-only sweep alone.
 */
public final class SettingsMigration {
    private static final Notice NOTICE = new Notice();

    private SettingsMigration() {
    }

    public static void run() {
        Path root = MeteorClient.FOLDER.toPath();
        int rewritten = 0;
        try {
            for (Path file : settingsFiles(root)) {
                ModulesTree.Result r = migrate(file);
                if (ModulesFile.writeIfChanged(file, r, (tree, target) -> NbtIo.write((NbtCompound) toNbt(tree), target))) {
                    rewritten++;
                }
            }
            if (rewritten > 0) NOTICE.pending.add(Msg.of(MigrationText.DONE, "files", rewritten));
        } catch (IOException | RuntimeException e) {
            XploitsAddon.LOG.error("Xploits: settings migration failed", e);
            NOTICE.pending.add(Msg.of(MigrationText.FAILED, "detail", e.getClass().getSimpleName()));
        }
        if (ConsoleFolder.run(root.resolve("xploits")) == ConsoleFolder.Outcome.BUSY) {
            NOTICE.pending.add(Msg.of(MigrationText.CONSOLE_FOLDER_BUSY));
        }
        if (!NOTICE.pending.isEmpty()) MeteorClient.EVENT_BUS.subscribe(NOTICE);
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
        for (String name : List.of("modules.nbt", "hud.nbt", "config.nbt")) {
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
                // consola: registrado aparte
                ChatUtils.infoPrefix("Xploits", "%s", Texts.render(m));
            }
            pending.forEach(m -> XploitsAddon.LOG.info("Xploits: {}", Texts.render(m)));
            pending.clear();
            MeteorClient.EVENT_BUS.unsubscribe(this);
        }
    }
}
