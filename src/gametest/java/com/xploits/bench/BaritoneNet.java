package com.xploits.bench;

import com.xploits.shared.baritone.BaritoneLink;
import meteordevelopment.meteorclient.utils.player.ChatUtils;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * CHECK {@code baritone-net} (printer phase-1 plan, Task 11): the chat safety net auto-travel, nether-sweep and later
 * restock share. The bench has no Baritone, so nothing intercepts a prefixed command: armed, the net must cancel it before
 * it leaves (the send says so, the module is told the typed text, no chat packet leaves). The last step, a prefixed line
 * sent once the net is disarmed, only proves the counter sees real chat, so the armed count staying put is not vacuous.
 */
final class BaritoneNet implements Scenario {
    @Override
    public String name() {
        return "baritone-net";
    }

    @Override
    public Kind kind() {
        return Kind.CHECK;
    }

    @Override
    public int seconds() {
        return 5;
    }

    @Override
    public void arrange(Bench bench) {
    }

    @Override
    public Metrics act(Bench bench) {
        OutgoingChat chat = bench.fromClient(client -> OutgoingChat.get());
        long before = chat.left();
        List<String> caught = new CopyOnWriteArrayList<>();
        BaritoneLink link = new BaritoneLink(caught::add);
        boolean delivered = bench.fromClient(client -> {
            try {
                link.arm("#");
                return link.send("#cancel");
            } finally {
                link.disarm();
            }
        });
        bench.ticks(2);
        Bench.check(!delivered, "the net let a Baritone command leave with no Baritone to take it");
        Bench.check(caught.equals(List.of("#cancel")), "the net did not report the command it cancelled");
        Bench.check(chat.left() == before, "a prefixed command reached the server as chat while the net was armed");
        Bench.check(!bench.fromClient(client -> link.armed()), "the net stayed armed after disarm");
        bench.onClient(client -> ChatUtils.sendPlayerMsg("#cancel", false));
        bench.ticks(2);
        Bench.check(chat.left() == before + 1, "the net still cancelled after it was disarmed");
        return Metrics.none();
    }
}
