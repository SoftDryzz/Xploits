package com.xploits.bench;

/**
 * Our player died during a MEASURE run, or a record of it ends LOST: ERROR, and the run is marked as a
 * death, which makes a crystal-aura++ comparison a REJECT (crystal-aura++ spec Q5).
 */
public final class PlayerDied extends BenchException {
    public PlayerDied(String message) {
        super(message);
    }
}
