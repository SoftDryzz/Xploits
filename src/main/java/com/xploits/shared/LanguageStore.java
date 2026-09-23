package com.xploits.shared;

import com.xploits.shared.core.i18n.LanguageChoice;
import com.xploits.shared.core.i18n.LanguageFile;
import meteordevelopment.meteorclient.MeteorClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** {@code meteor-client/xploits/language.txt}, the authority on the chosen language (spec §5). */
final class LanguageStore {
    private LanguageStore() {
    }

    static Path file() {
        return MeteorClient.FOLDER.toPath().resolve("xploits").resolve("language.txt");
    }

    /** Empty when the file does not exist; throws {@link IOException} when the file exists but cannot be read. */
    static Optional<String> read() throws IOException {
        Path f = file();
        if (!Files.exists(f)) return Optional.empty();
        return Optional.of(Files.readString(f, StandardCharsets.UTF_8));
    }

    static void write(LanguageChoice choice) throws IOException {
        Path f = file();
        Files.createDirectories(f.getParent());
        Files.writeString(f, LanguageFile.write(choice), StandardCharsets.UTF_8);
    }
}
