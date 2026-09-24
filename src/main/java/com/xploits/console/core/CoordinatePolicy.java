package com.xploits.console.core;

/**
 * What the console receives under the console module's {@code hide-coordinates} setting. Hiding
 * (the default): the half of each message without a position, and the sentinel holds back anything
 * that still looks like one. Showing: the same text as the chat, unchecked.
 */
public final class CoordinatePolicy {
    private CoordinatePolicy() {
    }

    public static <T> T pick(boolean hide, T chat, T log) {
        return hide ? log : chat;
    }

    public static boolean hold(boolean hide, String text) {
        return hide && CoordinateSentinel.isSuspect(text);
    }
}
