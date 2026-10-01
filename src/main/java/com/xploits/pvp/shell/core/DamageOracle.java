package com.xploits.pvp.shell.core;

/**
 * The damage an end crystal would deal us from a spot, as the server computes it (surround++ spec §5.2): the adapter
 * answers with vanilla's exposure raycast and Meteor's armour reductions, a test with plain numbers.
 */
public interface DamageOracle {
    /**
     * The damage after armour of a crystal exploding at the bottom centre of {@code spot}, with full exposure: no
     * raycast, and never below {@link #exact}.
     */
    double bound(Cell spot);

    /** The same with the real exposure, raycast in the world as it is; {@link #bound} once this tick's raycasts are spent. */
    double exact(Cell spot);
}
