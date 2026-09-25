package com.xploits.pvp.profile.core;

import com.xploits.shared.core.PositionedMsg;
import com.xploits.shared.core.i18n.Msg;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The live {@link ProfileBook} and its {@link ProfileStore} for one game session (ruling R1: {@code
 * AutoPvp} owns one, the commands reach it through {@code AutoPvp}). Pure: it never prints and never
 * touches a setting; every call returns an {@link Outcome} saying which profile to apply, if any, and
 * what to say.
 *
 * <p>Persistence rules (design, precise rules "Active profile persistence"): {@link #use} and {@link
 * #next} always switch and ask to apply, then write {@code active}; with the file locked the switch
 * lives in memory only and {@code PROFILE_FILE_LOCKED} is said once per session. {@link #save} and
 * {@link #delete} change the book only if the file was written: a refused write changes nothing.
 */
public final class ProfileSession {
    private final ProfileStore store;
    private ProfileBook book;
    private Optional<PositionedMsg> loadWarning;
    private boolean lockedWarned;

    public ProfileSession(ProfileStore store) {
        this.store = store;
        ProfileStore.Result loaded = store.load();
        this.book = loaded.book();
        this.loadWarning = loaded.warning()
            .map(warning -> new PositionedMsg(warning, Msg.of(ProfileText.PROFILE_FILE_CORRUPT_LOG)));
    }

    public ProfileBook book() {
        return book;
    }

    /** The corrupt-file warning from loading, handed out once (then empty). The detail can carry a path: chat only. */
    public Optional<PositionedMsg> takeLoadWarning() {
        Optional<PositionedMsg> warning = loadWarning;
        loadWarning = Optional.empty();
        return warning;
    }

    /**
     * What a call decided.
     *
     * @param apply    the profile whose values the adapter must now set, or empty to leave them alone
     * @param infos    plain lines ({@code PROFILE_ACTIVE}, {@code PROFILE_SAVED}...)
     * @param warnings warnings, with their console half (which never carries a path)
     */
    public record Outcome(Optional<PvpProfile> apply, List<Msg> infos, List<PositionedMsg> warnings) {
        public Outcome {
            infos = List.copyOf(infos);
            warnings = List.copyOf(warnings);
        }

        /** Whether the call did what was asked (no warning that stopped it: see each method). */
        public boolean ok() {
            return warnings.stream().noneMatch(w -> STOPPERS.contains(w.chat().key()));
        }
    }

    private static final Set<ProfileText> STOPPERS = Set.of(ProfileText.PROFILE_UNKNOWN, ProfileText.PROFILE_BAD_NAME,
        ProfileText.PROFILE_LIMIT_REACHED, ProfileText.PROFILE_FILE_LOCKED, ProfileText.PROFILE_SAVE_FAILED);

    public Outcome use(String name) {
        ProfileBook.Outcome outcome = book.use(name);
        if (outcome instanceof ProfileBook.Rejected rejected) return refused(rejected.reason());
        return switched((ProfileBook.Applied) outcome);
    }

    public Outcome next() {
        return switched(book.next());
    }

    private Outcome switched(ProfileBook.Applied applied) {
        book = applied.book();
        List<PositionedMsg> warnings = new ArrayList<>();
        ProfileStore.SaveResult result = store.save(book);
        if (result instanceof ProfileStore.SaveRefused refused) {
            if (refused.reason().key() == ProfileText.PROFILE_FILE_LOCKED) {
                if (!lockedWarned) {
                    lockedWarned = true;
                    warnings.add(PositionedMsg.same(refused.reason()));
                }
            } else {
                warnings.add(failed(refused.reason()));
            }
        }
        // A switch is never undone by a failed write: the values are applied, the name lives in memory.
        return new Outcome(Optional.of(book.active()), applied.messages(), warnings);
    }

    public Outcome save(String name, int targetRange, int approachDistance, double threatMargin, Set<String> allowed) {
        return commit(book.save(name, targetRange, approachDistance, threatMargin, allowed), false);
    }

    public Outcome delete(String name) {
        // Whether this delete touches the active profile has to be asked BEFORE the book changes: a
        // built-in delete keeps the active name (it is reset to factory values in place, not removed),
        // and an own-profile delete changes it to balanced. Either way, the player asked to get rid of
        // what they were looking at, and the ruling is that always re-applies, whether or not the stored
        // entry happens to already match what comes out (e.g. resetting a built-in nobody had saved over,
        // while the *live* settings are still hand-edited and unsaved: the book alone cannot see that).
        return commit(book.delete(name), name.equals(book.activeName()));
    }

    private Outcome commit(ProfileBook.Outcome outcome, boolean deletingTheActiveProfile) {
        if (outcome instanceof ProfileBook.Rejected rejected) return refused(rejected.reason());
        ProfileBook.Applied applied = (ProfileBook.Applied) outcome;
        ProfileStore.SaveResult result = store.save(applied.book());
        if (result instanceof ProfileStore.SaveRefused refused) {
            return new Outcome(Optional.empty(), List.of(), List.of(refused.reason().key() == ProfileText.PROFILE_FILE_LOCKED
                ? PositionedMsg.same(refused.reason())
                : failed(refused.reason())));
        }
        PvpProfile activeBefore = book.active();
        book = applied.book();
        List<Msg> infos = new ArrayList<>();
        List<PositionedMsg> warnings = new ArrayList<>();
        for (Msg message : applied.messages()) {
            if (message.key() == ProfileText.PROFILE_NOTHING_ALLOWED || message.key() == ProfileText.PROFILE_NO_AUTOBREAK) {
                warnings.add(PositionedMsg.same(message));
            } else {
                infos.add(message);
            }
        }
        // save() only re-applies when the active profile's values actually moved (comparing the whole
        // profile, not just its name, so overwriting the active built-in in place is caught too); delete()
        // of the active profile always re-applies (see the comment in #delete), whether it fell back to
        // balanced or reset a built-in whose stored copy already matched its factory values.
        Optional<PvpProfile> apply = deletingTheActiveProfile || !activeBefore.equals(book.active())
            ? Optional.of(book.active())
            : Optional.empty();
        if (apply.isPresent()) infos.add(Msg.of(ProfileText.PROFILE_ACTIVE, "name", book.activeName()));
        return new Outcome(apply, infos, warnings);
    }

    /** Moves a corrupt file aside and writes the book in memory in its place, so saving works again. */
    public Outcome resetFile() {
        try {
            Msg reset = store.resetFile();
            lockedWarned = false;
            ProfileStore.SaveResult result = store.save(book);
            if (result instanceof ProfileStore.SaveRefused refused) {
                return new Outcome(Optional.empty(), List.of(reset), List.of(failed(refused.reason())));
            }
            return new Outcome(Optional.empty(), List.of(reset), List.of());
        } catch (IOException | RuntimeException e) {
            return new Outcome(Optional.empty(), List.of(), List.of(failed(
                Msg.of(ProfileText.PROFILE_SAVE_FAILED, "detail", String.valueOf(e.getMessage())))));
        }
    }

    private static Outcome refused(Msg reason) {
        return new Outcome(Optional.empty(), List.of(), List.of(PositionedMsg.same(reason)));
    }

    private static PositionedMsg failed(Msg chat) {
        return new PositionedMsg(chat, Msg.of(ProfileText.PROFILE_SAVE_FAILED_LOG));
    }
}
