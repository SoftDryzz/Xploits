package com.xploits.restock.core;

import com.xploits.printer.core.Pos;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A container restock may fetch from (restock spec §3 "Sources and choice"): one the player marked, with the spot they
 * stood on when marking it, or one stash-keeper remembers, which restock approaches first and picks a spot near once its
 * chunk is loaded. The contents are a hint, checked on opening; {@code known} is false for a marked container nobody
 * has looked into yet. Positions stay in memory; nothing here is ever printed.
 *
 * @param loose  item id → count of the loose stacks
 * @param nested item id → count inside the shulker boxes it holds
 */
public record Source(Kind kind, String dimension, Pos container, Optional<Pos> stand, boolean known,
                     Map<String, Integer> loose, Map<String, Integer> nested) {
    public enum Kind { MARK, STASH }

    public Source {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(stand, "stand");
        loose = Map.copyOf(loose);
        nested = Map.copyOf(nested);
        if (!known && (!loose.isEmpty() || !nested.isEmpty())) {
            throw new IllegalArgumentException("contents nobody has seen hold nothing");
        }
        if (kind == Kind.STASH && (!known || stand.isPresent())) {
            throw new IllegalArgumentException("a stash-keeper entry has contents and no stand spot");
        }
        if (kind == Kind.MARK && stand.isEmpty()) throw new IllegalArgumentException("a mark has its stand spot");
    }

    public static Source mark(String dimension, Pos container, Pos stand, Map<String, Integer> loose,
                              Map<String, Integer> nested) {
        return new Source(Kind.MARK, dimension, container, Optional.of(stand), true, loose, nested);
    }

    public static Source unknownMark(String dimension, Pos container, Pos stand) {
        return new Source(Kind.MARK, dimension, container, Optional.of(stand), false, Map.of(), Map.of());
    }

    public static Source stash(String dimension, Pos container, Map<String, Integer> loose,
                               Map<String, Integer> nested) {
        return new Source(Kind.STASH, dimension, container, Optional.empty(), true, loose, nested);
    }
}
