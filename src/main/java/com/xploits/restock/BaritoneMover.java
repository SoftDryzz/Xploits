package com.xploits.restock;

import com.xploits.printer.core.BaritoneSession;
import com.xploits.restock.core.BaritoneSaveRules;
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
        saved = null;
        goal = false;
        Optional<RestockReason.Refusal> badPrefix = BaritoneSaveRules.prefixRefusal(prefix);
        if (badPrefix.isPresent()) return badPrefix;
        boolean pending = Files.isRegularFile(BaritoneRepair.file());
        Optional<BaritoneSession.Saved> unrestored = pending ? BaritoneRepair.read() : Optional.empty();
        Optional<List<String>> settingsLines = Optional.empty();
        if (!pending) {
            Path settings = FabricLoader.getInstance().getGameDir().resolve("baritone").resolve("settings.txt");
            try {
                settingsLines = Optional.of(Files.isRegularFile(settings)
                    ? Files.readAllLines(settings, StandardCharsets.UTF_8) : List.of());
            } catch (IOException e) {
                settingsLines = Optional.empty();
            }
        }
        BaritoneSaveRules.Values values = BaritoneSaveRules.playerValues(pending, unrestored, settingsLines);
        if (values.refusal().isPresent()) return values.refusal();
        BaritoneSession.Saved session = new BaritoneSession.Saved(prefix, mode, values.player());
        boolean ours = BaritoneSaveRules.saveBeforeFirstSet(pending);
        if (ours) {
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
            if (BaritoneSaveRules.deleteAfterFailedBegin(delivered, ours)) BaritoneRepair.delete();
            return refusal(RestockReason.BARITONE_NOT_LISTENING, "");
        }
        saved = session;
        return Optional.empty();
    }

    @Override
    public boolean goTo(BlockPos feet) {
        if (!ready()) return false;
        goal = true;
        return link.send(BaritoneScript.goToBlock(saved.prefix(), feet.getX(), feet.getY(), feet.getZ()));
    }

    @Override
    public boolean goToward(int x, int z) {
        if (!ready()) return false;
        goal = true;
        return link.send(BaritoneScript.goToColumn(saved.prefix(), x, z));
    }

    @Override
    public void cancel() {
        if (!goal || !ready()) return;
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
        if (BaritoneSaveRules.deleteAfterRestoration(delivered)) BaritoneRepair.delete();
        saved = null;
        return delivered;
    }

    @Override
    public String prefix() {
        return saved == null ? "" : saved.prefix();
    }

    /** A session exists and the net is armed: without either, a command would reach the server as plain chat. */
    private boolean ready() {
        return saved != null && link.armed();
    }

    private static Optional<RestockReason.Refusal> refusal(RestockReason reason, String detail) {
        return Optional.of(new RestockReason.Refusal(reason, detail));
    }
}
