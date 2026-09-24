package com.xploits.autotpy.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Decides whether AutoTPY accepts a TPA. Keeps the time of the last acceptance per name so as not to answer the same repeated TPA twice. */
public final class AutoTpyPolicy {
    public static final long DUPLICATE_WINDOW_MS = 2_000;
    public static final long IGNORED_NOTICE_WINDOW_MS = 60_000;
    private static final int MAX_TRACKED_IGNORED = 256;

    public enum Decision { ACCEPT, NOT_ALLOWED, DUPLICATE, HANDLED_BY_KIT_REQUESTER, INVALID }

    private final Map<String, Long> lastAccepted = new HashMap<>();
    private final Map<String, Long> lastIgnoredNotice = new HashMap<>();

    /**
     * @param requester       name captured from "X wants to teleport to you."
     * @param users           the users setting's list (exact, case-sensitive)
     * @param isFriend        the adapter has already checked Meteor's friends
     * @param includeFriends  the include-friends setting
     * @param kitRequesterCouriers KitRequester's couriers if that module is active; empty otherwise
     * @param now             System.currentTimeMillis()
     */
    public Decision decide(String requester, Set<String> users, boolean isFriend, boolean includeFriends,
                           Set<String> kitRequesterCouriers, long now) {
        if (requester == null || requester.isBlank()) return Decision.INVALID;
        if (kitRequesterCouriers.contains(requester)) return Decision.HANDLED_BY_KIT_REQUESTER;
        boolean allowed = users.contains(requester) || (includeFriends && isFriend);
        if (!allowed) return Decision.NOT_ALLOWED;
        Long last = lastAccepted.get(requester);
        if (last != null && now >= last && now - last < DUPLICATE_WINDOW_MS) return Decision.DUPLICATE;
        lastAccepted.put(requester, now);
        return Decision.ACCEPT;
    }

    /** true if an "ignored" notice for this requester should be shown now (at most one per name every 60 s). */
    public boolean shouldReportIgnored(String requester, long now) {
        Long last = lastIgnoredNotice.get(requester);
        if (last != null && now >= last && now - last < IGNORED_NOTICE_WINDOW_MS) return false;
        if (lastIgnoredNotice.size() >= MAX_TRACKED_IGNORED) {
            lastIgnoredNotice.values().removeIf(t -> now < t || now - t >= IGNORED_NOTICE_WINDOW_MS);
        }
        lastIgnoredNotice.put(requester, now);
        return true;
    }
}
