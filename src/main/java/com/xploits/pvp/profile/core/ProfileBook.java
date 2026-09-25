package com.xploits.pvp.profile.core;

import com.xploits.shared.core.i18n.Msg;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The player's style profiles (design §2): the three built-ins first, then the player's own by name,
 * plus which one is active. Immutable: every mutation returns a new book (or a {@link Rejected} with
 * nothing changed) instead of a plain value or exception, so a caller never needs to catch one for an
 * everyday mistake like an unknown name.
 *
 * <p>{@code ProfileStore} is the only thing that persists a book; this class is pure and never prints.
 */
public final class ProfileBook {
    /** At most this many profiles of the player's own, on top of the three built-ins (design §2). */
    public static final int PROFILE_LIMIT = 20;

    private final List<PvpProfile> profiles;
    private final String active;

    private ProfileBook(List<PvpProfile> profiles, String active) {
        this.profiles = profiles;
        this.active = active;
    }

    /** The three built-ins, nothing of the player's, {@code balanced} active. */
    public static ProfileBook defaults() {
        return new ProfileBook(BuiltInProfiles.ALL, BuiltInProfiles.BALANCED.name());
    }

    /**
     * Rebuilds a book from stored profiles (in any order) and an active name, the same way a fresh
     * save/delete does: the three built-in names must all be present (missing one throws), player
     * profiles beyond {@link #PROFILE_LIMIT} throw, and {@code active} must name one of them. {@code
     * ProfileStore} treats such an exception as a corrupt file.
     */
    public static ProfileBook of(List<PvpProfile> profiles, String active) {
        return build(profiles, active);
    }

    private static ProfileBook build(List<PvpProfile> source, String active) {
        Map<String, PvpProfile> byName = new LinkedHashMap<>();
        for (PvpProfile profile : source) {
            if (byName.put(profile.name(), profile) != null) {
                throw new IllegalArgumentException("duplicate profile name: " + profile.name());
            }
        }
        for (PvpProfile builtIn : BuiltInProfiles.ALL) {
            if (!byName.containsKey(builtIn.name())) {
                throw new IllegalArgumentException("missing built-in profile: " + builtIn.name());
            }
        }
        int playerCount = byName.size() - BuiltInProfiles.ALL.size();
        if (playerCount > PROFILE_LIMIT) {
            throw new IllegalArgumentException("too many player profiles: " + playerCount);
        }
        if (!byName.containsKey(active)) {
            throw new IllegalArgumentException("active profile not found: " + active);
        }

        List<PvpProfile> ordered = new ArrayList<>();
        for (PvpProfile builtIn : BuiltInProfiles.ALL) ordered.add(byName.remove(builtIn.name()));
        byName.values().stream().sorted(Comparator.comparing(PvpProfile::name)).forEach(ordered::add);
        return new ProfileBook(List.copyOf(ordered), active);
    }

    /** Built-ins first (fixed order), then the player's own by name. */
    public List<PvpProfile> profiles() {
        return profiles;
    }

    public String activeName() {
        return active;
    }

    public PvpProfile active() {
        for (PvpProfile profile : profiles) {
            if (profile.name().equals(active)) return profile;
        }
        throw new IllegalStateException("active profile vanished: " + active);
    }

    /** Whether the given values differ from the active profile's (design §2: no state kept here). */
    public boolean modified(int targetRange, int approachDistance, double threatMargin, Set<String> allowed) {
        return active().modified(targetRange, approachDistance, threatMargin, allowed);
    }

    /** The result of a mutation: either the new book (with any informational messages) or nothing changed. */
    public sealed interface Outcome permits Applied, Rejected {
    }

    public record Applied(ProfileBook book, List<Msg> messages) implements Outcome {
        public Applied {
            messages = List.copyOf(messages);
        }
    }

    public record Rejected(Msg reason) implements Outcome {
    }

    /** Switches the active profile; {@code PROFILE_UNKNOWN} if {@code name} is not in the book. */
    public Outcome use(String name) {
        if (profiles.stream().noneMatch(p -> p.name().equals(name))) {
            return new Rejected(unknown(name));
        }
        return new Applied(new ProfileBook(profiles, name), List.of(activeMessage(name)));
    }

    /** Cyclic: the profile after the active one in {@link #profiles()}, wrapping past the last. Cannot fail. */
    public Applied next() {
        int index = indexOfActive();
        String nextName = profiles.get((index + 1) % profiles.size()).name();
        return new Applied(new ProfileBook(profiles, nextName), List.of(activeMessage(nextName)));
    }

    private int indexOfActive() {
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).name().equals(active)) return i;
        }
        throw new IllegalStateException("active profile vanished: " + active);
    }

    /**
     * Saves the current values under {@code name}: a new player profile, or an overwrite in place (a
     * built-in keeps its name and can be {@link #delete}d back to factory values). {@code
     * PROFILE_BAD_NAME} for a name outside {@link PvpProfile#NAME}, {@code PROFILE_LIMIT_REACHED} for a
     * new name at {@link #PROFILE_LIMIT} already. On success, always {@code PROFILE_SAVED}, plus {@code
     * PROFILE_NOTHING_ALLOWED} for an empty {@code allowed} and/or {@code PROFILE_NO_AUTOBREAK} when it
     * does not include {@code crystal-aura}.
     */
    public Outcome save(String name, int targetRange, int approachDistance, double threatMargin, Set<String> allowed) {
        if (!PvpProfile.NAME.matcher(name).matches()) {
            return new Rejected(Msg.of(ProfileText.PROFILE_BAD_NAME, "name", name));
        }
        boolean exists = profiles.stream().anyMatch(p -> p.name().equals(name));
        if (!exists && playerProfileCount() >= PROFILE_LIMIT) {
            return new Rejected(Msg.of(ProfileText.PROFILE_LIMIT_REACHED, "limit", PROFILE_LIMIT));
        }

        PvpProfile saved = new PvpProfile(name, targetRange, approachDistance, threatMargin, allowed);

        List<PvpProfile> updated = new ArrayList<>();
        boolean replaced = false;
        for (PvpProfile profile : profiles) {
            if (profile.name().equals(name)) {
                updated.add(saved);
                replaced = true;
            } else {
                updated.add(profile);
            }
        }
        if (!replaced) updated.add(saved);

        ProfileBook book = build(updated, active);

        List<Msg> messages = new ArrayList<>();
        messages.add(Msg.of(ProfileText.PROFILE_SAVED, "name", name));
        if (saved.allowed().isEmpty()) messages.add(Msg.of(ProfileText.PROFILE_NOTHING_ALLOWED, "name", name));
        if (!saved.hasCrystalAura()) messages.add(Msg.of(ProfileText.PROFILE_NO_AUTOBREAK, "name", name));
        return new Applied(book, messages);
    }

    /**
     * A built-in is reset to its factory values instead of removed ({@code PROFILE_RESET}); a player
     * profile is removed ({@code PROFILE_DELETED}), and if it was active, the active profile falls back
     * to {@code balanced}. {@code PROFILE_UNKNOWN} for a name not in the book.
     */
    public Outcome delete(String name) {
        if (profiles.stream().noneMatch(p -> p.name().equals(name))) {
            return new Rejected(unknown(name));
        }

        if (BuiltInProfiles.isBuiltIn(name)) {
            PvpProfile factory = BuiltInProfiles.factory(name);
            List<PvpProfile> updated = profiles.stream().map(p -> p.name().equals(name) ? factory : p).toList();
            ProfileBook book = build(updated, active);
            return new Applied(book, List.of(Msg.of(ProfileText.PROFILE_RESET, "name", name)));
        }

        List<PvpProfile> updated = profiles.stream().filter(p -> !p.name().equals(name)).toList();
        String newActive = active.equals(name) ? BuiltInProfiles.BALANCED.name() : active;
        ProfileBook book = build(updated, newActive);
        return new Applied(book, List.of(Msg.of(ProfileText.PROFILE_DELETED, "name", name)));
    }

    private int playerProfileCount() {
        return (int) profiles.stream().filter(p -> !BuiltInProfiles.isBuiltIn(p.name())).count();
    }

    private static Msg activeMessage(String name) {
        return Msg.of(ProfileText.PROFILE_ACTIVE, "name", name);
    }

    private Msg unknown(String name) {
        String list = profiles.stream().map(PvpProfile::name).collect(Collectors.joining(", "));
        return Msg.of(ProfileText.PROFILE_UNKNOWN, "name", name, "list", list);
    }
}
