package com.xploits.restock;

import com.xploits.printer.core.BaritoneSession;
import com.xploits.restock.core.BaritoneValues;
import com.xploits.restock.core.RestockReason;
import com.xploits.shared.baritone.BaritoneLink;
import com.xploits.travel.core.BaritoneScript;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Baritone as restock's walker (restock spec §3 "Baritone"): {@code #goto x y z} (an exact goal block, VERIFIED) to a
 * stand spot or back, {@code #goto x z} towards an unloaded container, {@code #cancel} on arrival and first in every
 * stop, all through the shared safety net. Once per session it takes the player's values — from an unrestored saved
 * session if one exists (never overwritten, ruling P26), otherwise from {@code baritone/settings.txt} read as Baritone
 * reads it and saved before the first {@code #set} — sets the censor pair first and then turns breaking, placing and
 * water buckets off; {@link #end} gives them back. One link per module instance (Orbit never evicts its listener cache).
 */
final class BaritoneMover implements Mover {
    private final BaritoneLink link;
    private BaritoneSession.Saved saved;
    private boolean goal;

    BaritoneMover(Consumer<String> caught) {
        this.link = new BaritoneLink(caught);
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Optional<RestockReason.Refusal> begin(String prefix, BaritoneSession.Mode mode) {
        boolean pending = Files.isRegularFile(BaritoneRepair.file());
        Map<String, Boolean> player;
        if (pending) {
            Optional<BaritoneSession.Saved> unrestored = BaritoneRepair.read();
            if (unrestored.isEmpty()) return refusal(RestockReason.BARITONE_SAVED_UNREADABLE, "");
            player = unrestored.get().player();
        } else {
            Path settings = FabricLoader.getInstance().getGameDir().resolve("baritone").resolve("settings.txt");
            Map<String, String> file;
            try {
                List<String> lines = Files.isRegularFile(settings)
                    ? Files.readAllLines(settings, StandardCharsets.UTF_8) : List.of();
                file = BaritoneSession.parse(lines);
            } catch (IOException e) {
                return refusal(RestockReason.BARITONE_SETTINGS_UNREADABLE, "");
            }
            Optional<String> bad = BaritoneValues.unreadable(file);
            if (bad.isPresent()) return refusal(RestockReason.BARITONE_SETTINGS_UNREADABLE, bad.get());
            Optional<Map<String, Boolean>> values = BaritoneSession.playerValues(file);
            if (values.isEmpty()) return refusal(RestockReason.BARITONE_SETTINGS_UNREADABLE, "");
            player = values.get();
        }
        BaritoneSession.Saved session = new BaritoneSession.Saved(prefix, mode, player);
        if (!pending) {
            try {
                BaritoneRepair.save(session);
            } catch (IOException e) {
                return refusal(RestockReason.BARITONE_SAVE_FAILED, "");
            }
        }
        link.arm(prefix);
        int delivered = 0;
        List<String> preparation = BaritoneSession.preparation(prefix);
        for (String command : preparation) {
            if (link.send(command)) delivered++;
        }
        if (delivered < preparation.size()) {
            link.disarm();
            // Nothing reached Baritone: the player's settings are untouched, so the file we just wrote is not a debt.
            if (delivered == 0 && !pending) BaritoneRepair.delete();
            return refusal(RestockReason.BARITONE_NOT_LISTENING, "");
        }
        saved = session;
        return Optional.empty();
    }

    @Override
    public boolean goTo(BlockPos feet) {
        goal = true;
        return link.send(BaritoneScript.goToBlock(saved.prefix(), feet.getX(), feet.getY(), feet.getZ()));
    }

    @Override
    public boolean goToward(int x, int z) {
        goal = true;
        return link.send(BaritoneScript.goToColumn(saved.prefix(), x, z));
    }

    @Override
    public void cancel() {
        if (!goal || saved == null) return;
        goal = false;
        link.send(BaritoneScript.cancel(saved.prefix()));
    }

    @Override
    public boolean idle() {
        return !goal;
    }

    @Override
    public void tick() {
    }

    @Override
    public boolean end() {
        goal = false;
        if (saved == null) {
            link.disarm();
            return true;
        }
        boolean delivered;
        try {
            delivered = link.send(BaritoneScript.cancel(saved.prefix()));
            for (String command : BaritoneSession.restoration(saved)) delivered = link.send(command) && delivered;
        } finally {
            link.disarm();
        }
        if (delivered) BaritoneRepair.delete();
        saved = null;
        return delivered;
    }

    @Override
    public String prefix() {
        return saved == null ? "" : saved.prefix();
    }

    private static Optional<RestockReason.Refusal> refusal(RestockReason reason, String detail) {
        return Optional.of(new RestockReason.Refusal(reason, detail));
    }
}
