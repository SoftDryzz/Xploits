package com.xploits.travel.core;

/** AutoTravel's flight patterns: the decoy that stands between origin and destination. */
public enum FlightPattern {
    /** A straight line, with no decoy: a single waypoint, the destination. */
    STRAIGHT,
    /** A lateral deviation that switches sides with a short step and a small amplitude. */
    ZIGZAG,
    /** The same deviation as ZIGZAG, but with long legs and wide offsets. */
    SWERVE,
    /** A final spiral stretch that closes in on the destination. */
    SPIRAL,
    /** First aims far from the real destination and corrects afterwards. Rejected on a highway. */
    DECOY
}
