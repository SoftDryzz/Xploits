package com.kitbot.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Decide si AutoTPY acepta una TPA. Guarda la hora de la última aceptación por nombre para no responder dos veces a la misma TPA repetida. */
public final class AutoTpyPolicy {
    public static final long DUPLICATE_WINDOW_MS = 2_000;

    public enum Decision { ACCEPT, NOT_ALLOWED, DUPLICATE, HANDLED_BY_KIT_REQUESTER, INVALID }

    private final Map<String, Long> lastAccepted = new HashMap<>();

    /**
     * @param requester       nombre capturado de "X wants to teleport to you."
     * @param users           lista del ajuste users (exacta, distingue mayúsculas)
     * @param isFriend        el adaptador ya consultó los amigos de Meteor
     * @param includeFriends  ajuste include-friends
     * @param kitRequesterCouriers couriers de KitRequester si ese módulo está activo; vacío si no
     * @param now             System.currentTimeMillis()
     */
    public Decision decide(String requester, Set<String> users, boolean isFriend, boolean includeFriends,
                           Set<String> kitRequesterCouriers, long now) {
        if (requester == null || requester.isBlank()) return Decision.INVALID;
        if (kitRequesterCouriers.contains(requester)) return Decision.HANDLED_BY_KIT_REQUESTER;
        boolean allowed = users.contains(requester) || (includeFriends && isFriend);
        if (!allowed) return Decision.NOT_ALLOWED;
        Long last = lastAccepted.get(requester);
        if (last != null && now - last < DUPLICATE_WINDOW_MS) return Decision.DUPLICATE;
        lastAccepted.put(requester, now);
        return Decision.ACCEPT;
    }
}
