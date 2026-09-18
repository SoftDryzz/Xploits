package com.kitbot.kitrequester.core;

import java.util.Set;

/**
 * ¿Aceptar la TPA de {@code requester}? (spec §5)
 * Reglas: nombre vacío → no; conocido (exacto, distingue mayúsculas) → sí;
 * desconocido → solo si {@code trustUnknownCouriers} y ha enviado READY en esta ventana.
 * "Solo en AWAIT_COURIER" lo garantiza OrderMachine, no esta clase.
 */
public final class CourierPolicy {
    private CourierPolicy() {}

    public static boolean shouldAccept(String requester, boolean readySeenFromRequester,
                                       Set<String> knownCouriers, boolean trustUnknownCouriers) {
        if (requester == null || requester.isBlank()) return false;
        if (knownCouriers.contains(requester)) return true;
        return trustUnknownCouriers && readySeenFromRequester;
    }
}
