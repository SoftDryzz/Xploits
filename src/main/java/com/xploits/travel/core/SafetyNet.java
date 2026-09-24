package com.xploits.travel.core;

import com.xploits.shared.core.i18n.Msg;

/**
 * The pure rules of the safety net (AutoTravel spec §7): which prefixes can be watched, which
 * outgoing text is one of the Baritone commands we are directing, and what the player is told when
 * the restoration did not get anywhere.
 *
 * <p>There is no Minecraft here. The adapter only takes two things out of the packet -which channel
 * it leaves through and what payload it carries- and asks; the decision belongs to this class, which
 * is tested without starting the game. That matters because the module's reason to exist depends on
 * this {@code startsWith}: an {@code #elytra} that slips out announces to an anarchy server that you
 * are flying, and where to.
 *
 * <p><b>The subtlety of the channel.</b> The text of a server command does not travel with its
 * slash: the client strips it before building the packet. Comparing it as it is against the prefix
 * would mean comparing {@code "tpy Pepe"} instead of {@code "/tpy Pepe"}, so the slash is given back
 * before deciding: the net always compares against <b>the text the player would have typed</b>.
 */
public final class SafetyNet {
    private SafetyNet() {
    }

    /** The two paths through which a text typed in the chat leaves the client towards the server. */
    public enum Channel {
        /** Plain chat. It is where Baritone's commands travel, and the only one Baritone hooks. */
        CHAT,
        /** Server command. The text travels <b>without</b> the leading slash. */
        COMMAND
    }

    /**
     * The text as the player would have typed it in the chat, which is the form in which it can be
     * compared with a prefix.
     */
    public static String typedText(Channel channel, String payload) {
        if (channel == null) throw new IllegalArgumentException("an outgoing text has to come through some channel");
        if (payload == null) throw new IllegalArgumentException("an outgoing text cannot be null");
        return channel == Channel.COMMAND ? "/" + payload : payload;
    }

    /**
     * The command of a Baritone text without its arguments: {@code "#goal 1200 -800"} gives {@code
     * "#goal"}. It is what may go to the console when reporting that the net cut something: the
     * arguments may be coordinates (console spec §7).
     *
     * <p>The {@code "(empty)"} answer is only for completeness: callers gated by {@link #directs}
     * never reach it, since a text it accepts starts with a prefix that is never blank.
     */
    public static String verb(String text) {
        if (text == null) throw new IllegalArgumentException("an outgoing text cannot be null");
        String trimmed = text.strip();
        if (trimmed.isEmpty()) return "(empty)";
        return trimmed.split("\\s+", 2)[0];
    }

    /**
     * Whether this outgoing text is a Baritone command with the prefix the trip was launched with,
     * and so the net has to kill it before it reaches the server.
     *
     * <p>With a prefix that {@link #prefixRejection} would reject the answer is no, and that is not an
     * oversight: an empty prefix would make <i>every</i> text start with it, and the net would go from
     * cancelling Baritone commands to gagging the player's whole chat while diagnosing a leak that
     * does not exist. A useless prefix is rejected at launch -there, yes, with its reason and without
     * sending a single command-, which is where something can be done about it; not here any more.
     */
    public static boolean directs(String prefix, Channel channel, String payload) {
        if (prefixRejection(prefix) != null) return false;
        return typedText(channel, payload).startsWith(prefix);
    }

    /**
     * The reason why a prefix is no good for directing Baritone, or {@code null} if it is.
     *
     * <p>The interesting case is the third. A prefix that starts with {@code /} looks like an exotic
     * configuration and is actually the module's worst trap, for two reasons at once:
     *
     * <ul>
     *   <li>The client sends everything that starts with a slash down the command path, and
     *       Baritone's mixin hooks the plain chat one (spec §2), so the command would never reach
     *       Baritone: it would go to the server, which does not know it.</li>
     *   <li>And the net, which cancels everything that starts with the prefix, would also eat
     *       <b>all</b> the other slash commands while the trip lasts -{@code auto-tpy}'s {@code /tpy}
     *       and {@code kit-requester}'s whispers included-, diagnosing on top of that the opposite
     *       of what is happening.</li>
     * </ul>
     *
     * <p>It is not capped because there is nothing to cap: the repo's doctrine is <i>what still works
     * when capped is capped; what does not is rejected</i>, and a slash prefix does not work in any
     * way. It is rejected at launch, before arming the net and before sending a single command.
     */
    public static Msg prefixRejection(String prefix) {
        if (prefix == null || prefix.isEmpty()) return Msg.of(TravelText.PREFIX_EMPTY);
        if (prefix.isBlank()) return Msg.of(TravelText.PREFIX_BLANK);
        if (prefix.startsWith("/")) return Msg.of(TravelText.PREFIX_SLASH, "prefix", prefix);
        return null;
    }

    /**
     * What really happened with the restoration commands (spec §6.3). The module cannot announce
     * <i>"environment restored"</i> because it sent them: the net cancels everything that starts with
     * the prefix <b>including its own</b> -and that is deliberate and correct-, so "sent" and
     * "reached Baritone" are two different things, and the difference between them is whether
     * Baritone keeps flying on its own or not.
     */
    public enum Restoration {
        /** The commands left the client: either Baritone intercepted them, or they did not need cancelling. */
        DELIVERED,
        /** The net had to cancel commands of ours: they got nowhere. */
        CANCELLED,
        /** There was no player to send them to: none was sent. */
        NO_PLAYER;

        /** Whether it can be claimed that the environment was restored. */
        public boolean arrived() {
            return this == DELIVERED;
        }

        /**
         * The warning to show the player, or {@code null} if there is nothing to warn about. It says
         * the three things they need: that Baritone may keep flying, that the settings were left
         * written with the flight values, and what to type by hand to fix it.
         *
         * @param prefix the prefix with which Baritone was spoken to
         */
        public Msg warning(String prefix) {
            return switch (this) {
                case DELIVERED -> null;
                case CANCELLED -> Msg.of(TravelText.RESTORATION_CANCELLED, "prefix", prefix);
                case NO_PLAYER -> Msg.of(TravelText.RESTORATION_NO_PLAYER, "prefix", prefix);
            };
        }
    }
}
