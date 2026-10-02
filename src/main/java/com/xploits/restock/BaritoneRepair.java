package com.xploits.restock;

import com.xploits.printer.core.BaritoneSession;
import com.xploits.restock.core.RestockText;
import com.xploits.shared.XploitsModule;
import com.xploits.shared.baritone.BaritoneLink;
import com.xploits.travel.core.BaritoneScript;
import meteordevelopment.meteorclient.MeteorClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The player's Baritone values saved before a restock session's first {@code #set} (restock spec §3 "Baritone"). A session
 * that ended without its restoration reaching Baritone (a crash, a cut connection) leaves the file behind; the next world
 * join gives the values back, whichever module is on ({@link JoinWatch}), and until then the file is the source of the
 * player's values for a new session (ruling P26).
 */
final class BaritoneRepair {
    private BaritoneRepair() {
    }

    static Path file() {
        return MeteorClient.FOLDER.toPath().resolve("xploits").resolve("restock").resolve("baritone-session.txt");
    }

    static void save(BaritoneSession.Saved saved) throws IOException {
        Files.createDirectories(file().getParent());
        Files.write(file(), saved.toLines(), StandardCharsets.UTF_8);
    }

    /** The saved session; empty when there is none or it cannot be read (callers check the file exists first). */
    static Optional<BaritoneSession.Saved> read() {
        try {
            return BaritoneSession.Saved.fromLines(Files.readAllLines(file(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    static void delete() {
        try {
            Files.deleteIfExists(file());
        } catch (IOException e) {
            // Kept: the next join tries the restoration again, which is harmless.
        }
    }

    /** At the first tick after a world join, with a player. Client thread. {@code say} may be null. */
    static void run(XploitsModule say) {
        if (!Files.isRegularFile(file()) || !FabricLoader.getInstance().isModLoaded("baritone")) return;
        Optional<BaritoneSession.Saved> saved = read();
        if (saved.isEmpty()) {
            if (say != null) say.warning(RestockText.SAVED_UNREADABLE);
            return;
        }
        BaritoneLink link = new BaritoneLink(text -> {
        });
        boolean delivered;
        try {
            link.arm(saved.get().prefix());
            delivered = link.send(BaritoneScript.cancel(saved.get().prefix()));
            for (String command : BaritoneSession.restoration(saved.get())) delivered = link.send(command) && delivered;
        } finally {
            link.disarm();
        }
        if (delivered) {
            delete();
            if (say != null) say.info(RestockText.REPAIRED);
        } else if (say != null) {
            say.warning(RestockText.REPAIR_FAILED, "prefix", saved.get().prefix());
        }
    }
}
