package com.xploits.restock.core;

import com.xploits.shared.core.i18n.Msg;

import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * Every refusal, stop and pause of restock as a message with its arguments (spec §5 "Messages"): what happened, what to
 * do, which setting. No position and no player name ever goes in.
 */
public final class RestockMessages {
    private RestockMessages() {
    }

    /** What the reasons' texts may name. */
    public record Facts(int playerDistance, double minHealth, String prefix, long maxVolume, String builtAgainst,
                        int stallSeconds) {
    }

    public static Msg reason(RestockReason r, String detail, Facts f) {
        RestockText key = RestockText.valueOf("REASON_" + r.name());
        return switch (r) {
            case LITEMATICA_API -> Msg.of(key, "version", f.builtAgainst(), "detail", detail);
            case PLACEMENT_TOO_LARGE -> Msg.of(key, "size", detail, "limit", f.maxVolume());
            case PREFIX_INVALID, BARITONE_NOT_LISTENING -> Msg.of(key, "prefix", f.prefix());
            case CONFLICTING_MODULE, INTERNAL -> Msg.of(key, "detail", detail);
            case COMBAT, COMBAT_REPEATED -> Msg.of(key, "detail",
                detail.isEmpty() ? Msg.of(RestockText.ANOTHER_MODULE) : detail);
            case PLAYER_NEAR -> Msg.of(key, "distance", f.playerDistance());
            case LOW_HEALTH -> Msg.of(key, "minHealth", f.minHealth());
            case BARITONE_SETTINGS_UNREADABLE -> Msg.of(key, "setting",
                detail.isEmpty() ? Msg.of(RestockText.A_SETTING) : detail, "prefix", f.prefix());
            case NO_PATH, NO_PATH_BACK -> Msg.of(key, "seconds", f.stallSeconds());
            case NOTHING_FITS -> Msg.of(key, "material", detail);
            default -> Msg.of(key);
        };
    }

    /** "glass ×4, stone ×12": sorted by item, the {@code minecraft:} namespace dropped; empty for none. */
    public static String materials(Map<String, ? extends Number> items) {
        StringJoiner out = new StringJoiner(", ");
        new TreeMap<>(items).forEach((item, n) -> out.add(itemName(item) + " ×" + n));
        return out.toString();
    }

    /** An item id as a player reads it: {@code minecraft:stone} → {@code stone}; other namespaces kept. */
    public static String itemName(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    /** The list, or the word for nothing. */
    public static Object orNone(String list) {
        return list.isEmpty() ? Msg.of(RestockText.NONE) : list;
    }
}
