package com.xploits.bench;

import com.xploits.shared.Texts;
import com.xploits.shared.core.i18n.MessageKey;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;

import java.util.ArrayList;
import java.util.List;

/**
 * The lines the client's chat shows during a run, in order: Meteor posts {@link ReceiveMessageEvent} at the head of
 * {@code ChatHud.addMessage}, which every module line goes through ({@code ChatUtils.sendMsg}), on the client thread. A
 * CHECK asks which lines held one of a module's texts, rendered the way the module renders it ({@link Texts#render}).
 * Subscribed before T0, unsubscribed at the teardown. The lines stay in memory and are never printed: a chat line could
 * carry anything.
 */
final class ChatLines {
    private final List<String> lines = new ArrayList<>();

    private ChatLines() {
    }

    /** A fresh capture, subscribed now; the teardown unsubscribes it. */
    static ChatLines start(Bench bench) {
        ChatLines chat = new ChatLines();
        bench.onClient(client -> MeteorClient.EVENT_BUS.subscribe(chat));
        bench.atDespawn(() -> bench.onClient(client -> MeteorClient.EVENT_BUS.unsubscribe(chat)));
        return chat;
    }

    /** At {@code LOWEST}: a line another listener cancelled never reaches the chat, nor this list (spike S8). */
    @EventHandler(priority = EventPriority.LOWEST)
    private void onMessage(ReceiveMessageEvent event) {
        String line = event.getMessage().getString();
        synchronized (this) {
            lines.add(line);
        }
    }

    /**
     * The indexes of the lines that held {@code key}'s text with these names and values, rendered as the module renders
     * it now, in order (none: the text was never said).
     */
    List<Integer> at(MessageKey key, Object... namesAndValues) {
        String text = Texts.render(key, namesAndValues);
        List<Integer> at = new ArrayList<>();
        synchronized (this) {
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains(text)) at.add(i);
            }
        }
        return at;
    }
}
