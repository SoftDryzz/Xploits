package com.xploits.kitrequester.core;

import java.util.Set;

/**
 * Accept {@code requester}'s TPA? (spec §5)
 * Rules: empty name → no; known (exact, case-sensitive) → yes;
 * unknown → only if {@code trustUnknownCouriers} and it sent READY in this window.
 * "Only in AWAIT_COURIER" is guaranteed by OrderMachine, not this class.
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
