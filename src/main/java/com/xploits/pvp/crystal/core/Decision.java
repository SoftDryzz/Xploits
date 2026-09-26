package com.xploits.pvp.crystal.core;

import java.util.Objects;

/**
 * The one action of a tick (Meteor: break or place, one at a time) and why.
 *
 * @param kind   what to do
 * @param ref    the crystal id for {@link Kind#BREAK}, the candidate's {@link Candidate#pos} for
 *               {@link Kind#PLACE}, 0 for {@link Kind#NONE}
 * @param reason why
 */
public record Decision(Kind kind, long ref, Reason reason) {
    public enum Kind { NONE, BREAK, PLACE }

    public Decision {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(reason, "reason");
        if (kind == Kind.NONE && ref != 0) throw new IllegalArgumentException("no action with ref " + ref);
        if (kind == Kind.BREAK && ref != (int) ref) throw new IllegalArgumentException("crystal id " + ref);
    }

    public static Decision none(Reason reason) {
        return new Decision(Kind.NONE, 0, reason);
    }

    public static Decision breakCrystal(int id, Reason reason) {
        return new Decision(Kind.BREAK, id, reason);
    }

    public static Decision place(long pos, Reason reason) {
        return new Decision(Kind.PLACE, pos, reason);
    }
}
