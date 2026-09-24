package com.xploits.shared;

import com.xploits.shared.core.ChatLogMask;

/**
 * Game side of {@link ChatLogMask}: the mode chosen in the {@code xploits} module, read by the chat
 * log mixin. Starts at {@code ALL}, so lines logged before Meteor loads the saved setting are masked.
 */
public final class ChatLogFilter {
    private static volatile ChatLogMask.Mode mode = ChatLogMask.Mode.ALL;

    private ChatLogFilter() {
    }

    static void set(ChatLogMask.Mode value) {
        mode = value;
    }

    /** Never throws: a failure here must not cost the player a chat line. */
    public static String filter(String line) {
        try {
            return ChatLogMask.apply(mode, line);
        } catch (RuntimeException e) {
            return line;
        }
    }

    /** Same, for a line printed to standard output. */
    public static String filterStdout(String line) {
        try {
            return ChatLogMask.applyStdout(mode, line);
        } catch (RuntimeException e) {
            return line;
        }
    }
}
