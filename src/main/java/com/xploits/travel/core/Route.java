package com.xploits.travel.core;

import com.xploits.shared.core.i18n.Msg;

import java.util.List;

/**
 * The result of planning a trip: either the list of waypoints to follow, or a rejection with its
 * reason (AutoTravel spec: the decoy with a highway destination is rejected, not downgraded).
 *
 * <p>A rejected route always carries an empty waypoint list: nobody should read {@code waypoints()}
 * from a rejected route expecting to find something usable.
 */
public record Route(List<Waypoint> waypoints, Msg rejection) {
    public Route {
        waypoints = List.copyOf(waypoints);
        if (rejection == null && waypoints.isEmpty()) {
            throw new IllegalArgumentException(
                "an accepted route cannot have an empty waypoint list: nobody would know how to read it");
        }
    }

    /** An accepted route, with its waypoints. */
    public static Route of(List<Waypoint> waypoints) {
        return new Route(waypoints, null);
    }

    /** A rejected route: no waypoints, with the rejection's reason. */
    public static Route rejected(Msg reason) {
        return new Route(List.of(), reason);
    }

    public boolean isRejected() {
        return rejection != null;
    }
}
