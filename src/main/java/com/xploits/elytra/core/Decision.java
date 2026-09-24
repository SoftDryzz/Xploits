package com.xploits.elytra.core;

/** What to do with the worn elytra (spec §4). */
public enum Decision {
    /** Swap it for the one in the given slot. */
    SWAP,
    /** It has to be swapped but there is no valid spare: warn and touch nothing. */
    NO_SPARE,
    /** Nothing to do. */
    OK,
    /** No elytra is worn. */
    NOT_WEARING
}
