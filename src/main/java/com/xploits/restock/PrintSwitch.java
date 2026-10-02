package com.xploits.restock;

/**
 * litematica-printer's print mode as restock pauses and resumes it (restock spec §3 "Printer control"; the third seam):
 * the real one by reflection, a fake flag in the bench. Client thread only.
 */
public interface PrintSwitch {
    boolean installed();

    /** Whether it prints now; null when it cannot be read. */
    Boolean printing();

    /** Sets the print mode; false when it could not be set. */
    boolean set(boolean on);

    /** litematica-printer is not installed. */
    PrintSwitch NONE = new PrintSwitch() {
        @Override
        public boolean installed() {
            return false;
        }

        @Override
        public Boolean printing() {
            return null;
        }

        @Override
        public boolean set(boolean on) {
            return false;
        }
    };
}
