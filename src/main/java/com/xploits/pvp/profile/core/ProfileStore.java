package com.xploits.pvp.profile.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.xploits.shared.core.i18n.Msg;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongSupplier;

/**
 * Loads and saves the player's style profiles at one file, {@code profiles.json} under {@code
 * meteor-client/xploits/pvp/} (design §2), schema 1: {@code active}, {@code profiles[]}. Follows
 * {@link com.xploits.pvp.recorder.core.FightStore}: Gson pretty, a {@code .tmp} file moved in with
 * {@code ATOMIC_MOVE} (falling back when that is not supported), the {@code .tmp} removed if the move
 * or the write fails.
 *
 * <p>Unlike {@code FightStore}, one file can be locked: a file {@link #load()} could not understand is
 * never touched, and {@link #save(ProfileBook)} then refuses (so a corrupt file is never silently
 * replaced) until {@link #resetFile()} moves it aside. A missing file is not an error: {@link #load()}
 * returns the built-in profiles, unlocked.
 */
public final class ProfileStore {
    public static final int SCHEMA = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Mover mover;
    private final LongSupplier clock;
    private boolean locked;

    public ProfileStore(Path file) {
        this(file, ProfileStore::moveIntoPlace, System::currentTimeMillis);
    }

    ProfileStore(Path file, Mover mover) {
        this(file, mover, System::currentTimeMillis);
    }

    ProfileStore(Path file, Mover mover, LongSupplier clock) {
        this.file = file;
        this.mover = mover;
        this.clock = clock;
    }

    public Path file() {
        return file;
    }

    /** Whether the last {@link #load()} found a file it could not understand: {@link #save} refuses until {@link #resetFile()}. */
    public boolean locked() {
        return locked;
    }

    /** The book to load: the built-in profiles for a missing file, or for one that could not be understood (with {@code warning} set and the store now {@link #locked()}); the file itself is never touched by a failed load. */
    public record Result(ProfileBook book, Optional<Msg> warning) {
    }

    public Result load() {
        if (!Files.exists(file)) {
            locked = false;
            return new Result(ProfileBook.defaults(), Optional.empty());
        }
        try {
            ProfileBook book = readBook();
            locked = false;
            return new Result(book, Optional.empty());
        } catch (IOException | RuntimeException e) {
            locked = true;
            Msg warning = Msg.of(ProfileText.PROFILE_FILE_CORRUPT, "detail", String.valueOf(e.getMessage()));
            return new Result(ProfileBook.defaults(), Optional.of(warning));
        }
    }

    private ProfileBook readBook() throws IOException {
        String json = Files.readString(file);
        FileDto dto;
        try {
            dto = GSON.fromJson(json, FileDto.class);
        } catch (JsonParseException e) {
            throw new IOException("profiles.json is corrupt: " + e.getMessage(), e);
        }
        if (dto == null) throw new IOException("profiles.json is empty");
        if (dto.schema != SCHEMA) throw new IOException("profiles.json has schema " + dto.schema + ", expected " + SCHEMA);
        return dto.toBook();
    }

    /** The result of {@link #save}: either it was written, or it was refused with a reason. */
    public sealed interface SaveResult permits Saved, SaveRefused {
    }

    public record Saved() implements SaveResult {
    }

    public record SaveRefused(Msg reason) implements SaveResult {
    }

    /** Writes {@code book} atomically. {@code PROFILE_FILE_LOCKED} while {@link #locked()}; {@code PROFILE_SAVE_FAILED} if the write itself fails (the {@code .tmp} is always cleaned up either way). */
    public SaveResult save(ProfileBook book) {
        if (locked) {
            return new SaveRefused(Msg.of(ProfileText.PROFILE_FILE_LOCKED));
        }
        try {
            writeBook(book);
            return new Saved();
        } catch (IOException | RuntimeException e) {
            return new SaveRefused(Msg.of(ProfileText.PROFILE_SAVE_FAILED, "detail", String.valueOf(e.getMessage())));
        }
    }

    private void writeBook(ProfileBook book) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, GSON.toJson(FileDto.of(book)));
            mover.move(tmp, file);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    /**
     * Moves a file {@link #load()} could not understand aside, as {@code profiles.json.corrupt-<time>},
     * and unlocks the store ({@code PROFILE_FILE_RESET}); a no-op, still unlocked, if there is nothing
     * to move.
     */
    public Msg resetFile() throws IOException {
        if (Files.exists(file)) {
            Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + clock.getAsLong());
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        locked = false;
        return Msg.of(ProfileText.PROFILE_FILE_RESET);
    }

    /** Moves the finished {@code .tmp} file onto its final name. A seam so a test can make the move fail. */
    @FunctionalInterface
    interface Mover {
        void move(Path from, Path to) throws IOException;
    }

    private static void moveIntoPlace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // Private DTOs, schema 1: {"schema": 1, "active": "...", "profiles": [{"name": ..., "targetRange": ...,
    // "approachDistance": ..., "threatMargin": ..., "allowed": [...]}, ...]}.

    private static final class FileDto {
        int schema;
        String active;
        List<ProfileDto> profiles;

        static FileDto of(ProfileBook book) {
            FileDto dto = new FileDto();
            dto.schema = SCHEMA;
            dto.active = book.activeName();
            List<ProfileDto> list = new ArrayList<>();
            for (PvpProfile profile : book.profiles()) list.add(ProfileDto.of(profile));
            dto.profiles = list;
            return dto;
        }

        ProfileBook toBook() {
            List<PvpProfile> list = new ArrayList<>();
            if (profiles != null) {
                for (ProfileDto dto : profiles) list.add(dto.toProfile());
            }
            return ProfileBook.of(list, active);
        }
    }

    private static final class ProfileDto {
        String name;
        int targetRange;
        int approachDistance;
        double threatMargin;
        List<String> allowed;

        static ProfileDto of(PvpProfile profile) {
            ProfileDto dto = new ProfileDto();
            dto.name = profile.name();
            dto.targetRange = profile.targetRange();
            dto.approachDistance = profile.approachDistance();
            dto.threatMargin = profile.threatMargin();
            dto.allowed = new ArrayList<>(new TreeSet<>(profile.allowed()));
            return dto;
        }

        PvpProfile toProfile() {
            if (name == null) throw new IllegalArgumentException("a profile entry has no name");
            Set<String> modules = allowed == null ? Set.of() : Set.copyOf(allowed);
            return new PvpProfile(name, targetRange, approachDistance, threatMargin, modules);
        }
    }
}
