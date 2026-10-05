package com.xploits.restock.core;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * The materials restock has already said it finds nowhere, so the retry a minute later stays silent (the field's
 * promise): said once per material until the sources change. {@link #afterTrip} and {@link #firstTime} are the two
 * places that say "nowhere"; both note the material.
 */
public final class NowhereNotes {
    private final Set<String> said = new HashSet<>();

    /** Whether "nowhere" has not been said for {@code material} yet; noted from now on. */
    public boolean firstTime(String material) {
        return said.add(material);
    }

    /**
     * What a trip that found no source left says for {@code material}: nothing while one is left, else the
     * "after the trip" text (the boxes' one when a container was passed over for holding it only inside boxes), noted
     * as said so the later retry does not say "nowhere" again.
     */
    public Optional<RestockText> afterTrip(String material, boolean sourceLeft, boolean passedOverBoxes) {
        if (sourceLeft) return Optional.empty();
        said.add(material);
        return Optional.of(passedOverBoxes ? RestockText.NOWHERE_AFTER_TRIP_BOXES : RestockText.NOWHERE_AFTER_TRIP);
    }

    /** A new source appeared: everything may be said again. */
    public void clear() {
        said.clear();
    }

    /** The materials said to be nowhere. */
    public Set<String> materials() {
        return Set.copyOf(said);
    }
}
