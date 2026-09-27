package com.xploits.pvp.crystal.core;

/**
 * Meteor's {@code swing-mode} (CrystalAura lines 1382-1395): how to swing the crystal hand when hitting or
 * placing (lines 888-889, 1051-1052). The display names are what Meteor saves and what a player types:
 * never change them.
 */
public enum SwingMode {
    BOTH("Both"),
    PACKET("Packet"),
    CLIENT("Client"),
    NONE("None");

    private final String display;

    SwingMode(String display) {
        this.display = display;
    }

    /** Swing the hand on your screen. */
    public boolean client() {
        return this == CLIENT || this == BOTH;
    }

    /** Send the swing to the server. */
    public boolean packet() {
        return this == PACKET || this == BOTH;
    }

    @Override
    public String toString() {
        return display;
    }
}
