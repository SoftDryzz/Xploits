package com.xploits.shared.chat;

/** Mensaje de chat reconocido. Lo produce {@link ChatPatterns#classify(String)}. */
public sealed interface ChatEvent {
    record Placed() implements ChatEvent {}
    record Cooldown(long millis) implements ChatEvent {}
    record Active() implements ChatEvent {}
    record Unregistered() implements ChatEvent {}
    record Usage() implements ChatEvent {}
    record UnknownKitbot(String text) implements ChatEvent {}
    record Ready(String courier) implements ChatEvent {}
    record Partial(String courier) implements ChatEvent {}
    record NotFound(String courier) implements ChatEvent {}
    record Done(String courier) implements ChatEvent {}
    record TimedOut(String courier) implements ChatEvent {}
    record Tpa(String requester) implements ChatEvent {}
}
