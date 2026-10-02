package com.xploits.shared.baritone;

import com.xploits.travel.core.SafetyNet;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.ChatCommandSignedC2SPacket;
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Baritone through chat commands with Xploits' safety net (auto-travel spec §7), shared by auto-travel,
 * nether-sweep and later restock, so there is one net, not three copies of it.
 *
 * <p><b>Why the net is an object of its own.</b> Sending {@code #elytra} or {@code #goto} to Baritone means writing in the
 * chat and trusting Baritone to intercept it before the packet leaves; Meteor's guard for that depends on
 * {@code BaritoneUtils.IS_AVAILABLE}, which is false on these instances even with Baritone installed, so while a module
 * is in charge this net cancels every outgoing chat packet whose text starts with the active prefix. It is subscribed to
 * Meteor's bus on its own, not as an {@code @EventHandler} of a module, because {@code Module.toggle()} and
 * {@code Modules.onGameLeft} unsubscribe a module <i>before</i> calling {@code onDeactivate()}, exactly when a
 * restoration sends its commands, and a net built into the module would be dead then. Orbit 0.2.4's
 * {@code EventBus.subscribe(Object)} builds the listener with the lambda factory of the addon's package
 * ({@code com.xploits}), so any class of ours will do, and it caches listeners by object identity: {@link #arm} and
 * {@link #disarm} always use the same instance. Orbit never evicts that cache, so a consumer keeps <b>one link per
 * module in a final field</b>, not one per trip: every link ever subscribed stays referenced for the session.
 *
 * <p><b>Our own commands are cancelled too</b>, deliberately: if the net has to act, Baritone is not intercepting, and
 * then our command has nobody to reach. That is what {@link #send}'s answer says; the owning module is told the typed
 * text through the {@code caught} callback and warns in its own words.
 */
public final class BaritoneLink {
    private final Consumer<String> caught;
    private final Net net = new Net();
    private boolean armed;
    /** Whether the command {@link #send} is sending right now is ours. */
    private boolean emitting;
    /** Whether the net killed our last command: set by the listener, read by {@link #send}. */
    private boolean sendCaught;
    private String prefix = "";

    /** @param caught told the typed text of every prefixed packet the net cancels */
    public BaritoneLink(Consumer<String> caught) {
        this.caught = Objects.requireNonNull(caught, "caught");
    }

    /**
     * Arms the net for {@code prefix}. Idempotent.
     *
     * @throws IllegalArgumentException if {@link SafetyNet#prefixRejection} refuses the prefix: an armed net with an
     *         unusable prefix would catch nothing while {@link #armed} said it was on. An exception, not a boolean,
     *         because both callers already reject the prefix with its own message before arming, so for them it can
     *         only signal a programming error.
     */
    public void arm(String prefix) {
        if (SafetyNet.prefixRejection(prefix) != null) {
            throw new IllegalArgumentException("unusable Baritone prefix");
        }
        this.prefix = prefix;
        if (armed) return;
        armed = true;
        MeteorClient.EVENT_BUS.subscribe(net);
    }

    /** Disarms the net. Idempotent, so it can always run last, in a {@code finally}. */
    public void disarm() {
        if (!armed) return;
        armed = false;
        MeteorClient.EVENT_BUS.unsubscribe(net);
    }

    public boolean armed() {
        return armed;
    }

    /**
     * Sends a command through the player's chat ({@code ChatUtils.sendPlayerMsg(command, false)}: "as if the user had typed
     * it", which is what Baritone listens to; not added to the chat history, where the up key would leave it one Enter away
     * from being published). Client thread.
     *
     * @return whether it left the client towards Baritone. {@code false}: the net had to cancel it, or there was no player
     *         ({@code sendPlayerMsg} dereferences it unchecked). The path is synchronous: {@code sendPlayerMsg} ends in
     *         {@code ClientConnection.send}, where Meteor posts the event and the net answers before this returns.
     */
    public boolean send(String command) {
        if (MeteorClient.mc.player == null) return false;
        boolean outer = emitting;
        emitting = true;
        sendCaught = false;
        try {
            ChatUtils.sendPlayerMsg(command, false);
        } finally {
            emitting = outer;
        }
        return !sendCaught;
    }

    /** The half of the net that needs Minecraft: which channel a packet leaves through and what text it carries. */
    private final class Net {
        @EventHandler
        private void onPacketSend(PacketEvent.Send event) {
            if (!armed) return;
            SafetyNet.Channel channel;
            String payload;
            if (event.packet instanceof ChatMessageC2SPacket chat) {
                channel = SafetyNet.Channel.CHAT;
                payload = chat.chatMessage();
            } else if (event.packet instanceof CommandExecutionC2SPacket command) {
                channel = SafetyNet.Channel.COMMAND;
                payload = command.command();
            } else if (event.packet instanceof ChatCommandSignedC2SPacket command) {
                channel = SafetyNet.Channel.COMMAND;
                payload = command.command();
            } else {
                return;
            }
            if (!SafetyNet.directs(prefix, channel, payload)) return;
            event.cancel();
            if (emitting) sendCaught = true;
            caught.accept(SafetyNet.typedText(channel, payload));
        }
    }
}
