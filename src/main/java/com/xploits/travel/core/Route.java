package com.xploits.travel.core;

import com.xploits.shared.core.i18n.Msg;

import java.util.List;

/**
 * El resultado de planear un viaje: o bien la lista de waypoints a seguir, o bien un rechazo con
 * su motivo (spec AutoTravel: el señuelo con destino de autopista se rechaza, no se degrada).
 *
 * <p>Una ruta rechazada lleva siempre la lista de waypoints vacía: nadie debe leer {@code
 * waypoints()} de una ruta rechazada esperando encontrar algo utilizable.
 */
public record Route(List<Waypoint> waypoints, Msg rejection) {
    public Route {
        waypoints = List.copyOf(waypoints);
        if (rejection == null && waypoints.isEmpty()) {
            throw new IllegalArgumentException(
                "una ruta aceptada no puede tener la lista de waypoints vacía: nadie sabría interpretarla"); // i18n: allowed (exception message, continuation line)
        }
    }

    /** Una ruta aceptada, con sus waypoints. */
    public static Route of(List<Waypoint> waypoints) {
        return new Route(waypoints, null);
    }

    /** Una ruta rechazada: sin waypoints, con el motivo del rechazo. */
    public static Route rejected(Msg reason) {
        return new Route(List.of(), reason);
    }

    public boolean isRejected() {
        return rejection != null;
    }
}
