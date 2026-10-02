package com.xploits.restock;

import com.xploits.restock.core.PrintPause;
import com.xploits.restock.core.RestockText;
import com.xploits.shared.XploitsModule;
import meteordevelopment.meteorclient.MeteorClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The on-disk note that restock holds litematica-printer's print mode off (restock spec §3 "Printer control"): written
 * before the switch at a trip's start, deleted at the return and at every stop but leaving the world, and given back at
 * the next world join if the game closed in between ({@link PrintPause#atJoin}). One line, no position.
 */
public final class PrinterMarker {
    private PrinterMarker() {
    }

    public static Path file() {
        return MeteorClient.FOLDER.toPath().resolve("xploits").resolve("restock").resolve("printer-paused.txt");
    }

    static void write() throws IOException {
        Files.createDirectories(file().getParent());
        Files.write(file(), PrintPause.markerLines(), StandardCharsets.UTF_8);
    }

    static void delete() {
        try {
            Files.deleteIfExists(file());
        } catch (IOException e) {
            // Left: at the next join it finds the printer as the player left it and only switches it on if it is off.
        }
    }

    /** Its lines; empty when there is no marker; one line that is not the marker when it cannot be read. */
    static List<String> read() {
        if (!Files.isRegularFile(file())) return List.of();
        try {
            return Files.readAllLines(file(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of("unreadable");
        }
    }

    /** At the first tick after a world join. Client thread. {@code say} may be null. */
    static void repairAtJoin(XploitsModule say, PrintSwitch printer) {
        List<String> lines = read();
        if (lines.isEmpty()) return;
        if (PrintPause.atJoin(lines, printer.installed(), printer.printing()) == PrintPause.Action.SWITCH_ON
            && printer.set(true) && say != null) {
            say.info(RestockText.PRINTER_REPAIRED);
        }
        delete();
    }
}
