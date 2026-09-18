package com.kitbot.autotpy.core;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AutoTpyPolicyTest {
    @Test
    void acceptsUserInList() {
        assertEquals(AutoTpyPolicy.Decision.ACCEPT,
            new AutoTpyPolicy().decide("xto2002", Set.of("xto2002"), false, true, Set.of(), 0));
    }

    @Test
    void usersAreCaseSensitive() {
        assertEquals(AutoTpyPolicy.Decision.NOT_ALLOWED,
            new AutoTpyPolicy().decide("XTO2002", Set.of("xto2002"), false, true, Set.of(), 0));
    }

    @Test
    void acceptsFriendWhenIncludeFriends() {
        assertEquals(AutoTpyPolicy.Decision.ACCEPT,
            new AutoTpyPolicy().decide("Dryzzical", Set.of(), true, true, Set.of(), 0));
    }

    @Test
    void ignoresFriendWhenIncludeFriendsOff() {
        assertEquals(AutoTpyPolicy.Decision.NOT_ALLOWED,
            new AutoTpyPolicy().decide("Dryzzical", Set.of(), true, false, Set.of(), 0));
    }

    @Test
    void rejectsStranger() {
        assertEquals(AutoTpyPolicy.Decision.NOT_ALLOWED,
            new AutoTpyPolicy().decide("Mallory", Set.of("xto2002"), false, true, Set.of(), 0));
    }

    @Test
    void invalidName() {
        AutoTpyPolicy policy = new AutoTpyPolicy();
        assertEquals(AutoTpyPolicy.Decision.INVALID, policy.decide(null, Set.of("xto2002"), false, true, Set.of(), 0));
        assertEquals(AutoTpyPolicy.Decision.INVALID, policy.decide("", Set.of("xto2002"), false, true, Set.of(), 0));
        assertEquals(AutoTpyPolicy.Decision.INVALID, policy.decide("  ", Set.of("xto2002"), false, true, Set.of(), 0));
    }

    @Test
    void leavesKitRequesterCouriersAlone() {
        assertEquals(AutoTpyPolicy.Decision.HANDLED_BY_KIT_REQUESTER,
            new AutoTpyPolicy().decide("StormAegis44", Set.of("StormAegis44"), false, true, Set.of("StormAegis44"), 0));
    }

    @Test
    void duplicateWithinWindow() {
        AutoTpyPolicy policy = new AutoTpyPolicy();
        Set<String> users = Set.of("xto2002");
        assertEquals(AutoTpyPolicy.Decision.ACCEPT, policy.decide("xto2002", users, false, true, Set.of(), 1000));
        assertEquals(AutoTpyPolicy.Decision.DUPLICATE, policy.decide("xto2002", users, false, true, Set.of(), 2999));
        assertEquals(AutoTpyPolicy.Decision.ACCEPT, policy.decide("xto2002", users, false, true, Set.of(), 3000));
    }

    @Test
    void duplicateDoesNotExtendWindow() {
        AutoTpyPolicy policy = new AutoTpyPolicy();
        Set<String> users = Set.of("xto2002");
        assertEquals(AutoTpyPolicy.Decision.ACCEPT, policy.decide("xto2002", users, false, true, Set.of(), 0));
        assertEquals(AutoTpyPolicy.Decision.DUPLICATE, policy.decide("xto2002", users, false, true, Set.of(), 1500));
        assertEquals(AutoTpyPolicy.Decision.ACCEPT, policy.decide("xto2002", users, false, true, Set.of(), 2000));
    }

    @Test
    void windowIsPerRequester() {
        AutoTpyPolicy policy = new AutoTpyPolicy();
        Set<String> users = Set.of("a1b", "c2d");
        assertEquals(AutoTpyPolicy.Decision.ACCEPT, policy.decide("a1b", users, false, true, Set.of(), 0));
        assertEquals(AutoTpyPolicy.Decision.ACCEPT, policy.decide("c2d", users, false, true, Set.of(), 100));
    }

    @Test
    void clockWentBackwardsIsNotDuplicate() {
        AutoTpyPolicy policy = new AutoTpyPolicy();
        Set<String> users = Set.of("xto2002");
        assertEquals(AutoTpyPolicy.Decision.ACCEPT, policy.decide("xto2002", users, false, true, Set.of(), 5000));
        assertEquals(AutoTpyPolicy.Decision.ACCEPT, policy.decide("xto2002", users, false, true, Set.of(), 1000));
    }

    @Test
    void ignoredNoticeOncePerMinute() {
        AutoTpyPolicy policy = new AutoTpyPolicy();
        assertEquals(true, policy.shouldReportIgnored("Mallory", 0));
        assertEquals(false, policy.shouldReportIgnored("Mallory", 59_999));
        assertEquals(true, policy.shouldReportIgnored("Mallory", 60_000));
    }

    @Test
    void ignoredNoticeIsPerRequester() {
        AutoTpyPolicy policy = new AutoTpyPolicy();
        assertEquals(true, policy.shouldReportIgnored("a1b", 0));
        assertEquals(true, policy.shouldReportIgnored("c2d", 10));
    }

    @Test
    void ignoredNoticeAfterClockWentBackwards() {
        AutoTpyPolicy policy = new AutoTpyPolicy();
        assertEquals(true, policy.shouldReportIgnored("Mallory", 100_000));
        assertEquals(true, policy.shouldReportIgnored("Mallory", 5_000));
    }
}
