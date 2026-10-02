package com.xploits.restock.core;

import com.xploits.printer.core.BuildIndex;
import com.xploits.printer.core.GridBox;
import com.xploits.printer.core.Guards;
import com.xploits.printer.core.Pos;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The selected placement's fingerprint (restock spec §3, from the printer's C3/N-M3): what makes restock refuse to start,
 * and any change after which it counts the build again — the selected placement itself (identity, enabled, origin,
 * rotation, mirror, enabled sub-regions and their boxes), or another enabled placement's box coming to overlap it (the
 * schematic world merges every enabled placement). The layer range is not part of it: restock counts the whole placement.
 */
public final class PlacementWatch {
    /** Another placement: whether it is enabled and its enclosing box. */
    public record Other(long identity, boolean enabled, GridBox enclosing) {
        /** Never a position ({@link HiddenPositions}): the box is one. */
        @Override
        public String toString() {
            return "Other[identity=" + identity + ", enabled=" + enabled + ", enclosing=" + HiddenPositions.HIDDEN + "]";
        }
    }

    /**
     * The selected placement as the adapter reads it.
     *
     * @param identity the placement object's identity
     * @param regions  the enabled sub-regions by name, with their world boxes
     */
    public record View(long identity, boolean enabled, Pos origin, String rotation, String mirror,
                       Map<String, GridBox> regions, List<Other> others) {
        public View {
            regions = Map.copyOf(regions);
            others = List.copyOf(others);
        }

        /** Never a position ({@link HiddenPositions}): the origin and the boxes are; the regions show by name. */
        @Override
        public String toString() {
            return "View[identity=" + identity + ", enabled=" + enabled + ", origin=" + HiddenPositions.HIDDEN
                + ", rotation=" + rotation + ", mirror=" + mirror + ", regions=" + new TreeSet<>(regions.keySet())
                + ", others=" + others + "]";
        }
    }

    private final View start;

    public PlacementWatch(View start) {
        this.start = start;
    }

    /** Why counting cannot start with this view; {@code view} null when no placement is selected. */
    public static Optional<Guards.Reason> refusal(View view, long maxVolume) {
        if (view == null) return Optional.of(Guards.Reason.NO_PLACEMENT);
        if (!view.enabled()) return Optional.of(Guards.Reason.PLACEMENT_DISABLED);
        if (view.regions().isEmpty()) return Optional.of(Guards.Reason.NO_ENABLED_REGION);
        if (BuildIndex.volume(List.copyOf(view.regions().values())) > maxVolume) {
            return Optional.of(Guards.Reason.PLACEMENT_TOO_LARGE);
        }
        if (overlapped(view)) return Optional.of(Guards.Reason.PLACEMENT_OVERLAP);
        return Optional.empty();
    }

    /** Whether the build must be counted again; {@code now} null when no placement is selected any more. */
    public boolean changed(View now) {
        if (now == null || now.identity() != start.identity() || now.enabled() != start.enabled()
            || !now.origin().equals(start.origin()) || !now.rotation().equals(start.rotation())
            || !now.mirror().equals(start.mirror()) || !now.regions().equals(start.regions())) {
            return true;
        }
        return overlapped(now);
    }

    private static boolean overlapped(View view) {
        for (Other other : view.others()) {
            if (!other.enabled()) continue;
            for (GridBox region : view.regions().values()) {
                if (region.intersects(other.enclosing())) return true;
            }
        }
        return false;
    }
}
