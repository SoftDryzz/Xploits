package com.xploits.shared.chat;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Protocolo de SnifferBuddy (spec §2): normaliza y clasifica mensajes, y construye los comandos salientes.
 * Todas las regex van ancladas: en 6b6t el chat público también llega como mensaje del sistema
 * ("[Rango] Nombre » texto"), y el anclaje es lo que impide suplantar mensajes desde ahí.
 */
public final class ChatPatterns {
    public static final String KITBOT = "SnifferBuddy";

    private static final Pattern ANTI_SPAM_SUFFIX = Pattern.compile(" \\(\\d{1,9}\\)$");
    private static final Pattern HEAD_PREFIX = Pattern.compile("^\\[(?:unknown player|[A-Za-z0-9_]{3,16}) head\\] ");
    private static final Pattern WHISPER = Pattern.compile("^([A-Za-z0-9_]{3,16}) whispers: (.+)$");
    private static final Pattern TPA = Pattern.compile("^([A-Za-z0-9_]{3,16}) wants to teleport to you\\.$");
    private static final Pattern COOLDOWN = Pattern.compile(
        "^You are on (?:order cooldown|cooldown because of consecutive rejections)\\. Try again in (\\d+) (minutes?|seconds?)\\.$");
    private static final Pattern UNREGISTERED = Pattern.compile("^Please register on \\S+ to use the kitbot\\.$");

    private static final String PLACED = "Your order has been placed successfully. Please wait for a courier to deliver it.";
    private static final String ACTIVE = "You already have an active order being delivered. Please wait for it to complete before placing a new one.";
    private static final String USAGE_PREFIX = "Use !kit <id> to place an order";
    private static final String READY = "Your order is completed, please accept a tpa request";
    private static final String PARTIAL = "Some requested shulkers were not found; delivering the rest";
    private static final String NOT_FOUND = "Requested shulker not found";
    private static final String DONE = "Order completed successfully";
    private static final String TIMED_OUT = "Order cancelled: teleportation timed out.";

    private ChatPatterns() {}

    /** Quita espacios, el sufijo " (N)" del anti-spam de BetterChat y un único prefijo "[X head] " que 6b6t añade desde 2026-09-16. */
    public static String normalize(String raw) {
        String withoutSuffix = ANTI_SPAM_SUFFIX.matcher(raw.strip()).replaceFirst("").strip();
        return HEAD_PREFIX.matcher(withoutSuffix).replaceFirst("");
    }

    public static Optional<ChatEvent> classify(String raw) {
        String msg = normalize(raw);

        Matcher tpa = TPA.matcher(msg);
        if (tpa.matches()) return Optional.of(new ChatEvent.Tpa(tpa.group(1)));

        Matcher whisper = WHISPER.matcher(msg);
        if (!whisper.matches()) return Optional.empty();

        String sender = whisper.group(1);
        String text = whisper.group(2);
        return sender.equals(KITBOT) ? Optional.of(kitbotEvent(text)) : courierEvent(sender, text);
    }

    public static String orderCommand(List<Integer> ids) {
        return "/w " + KITBOT + " !kit " + ids.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    public static String acceptCommand(String courier) {
        if (courier == null || courier.isBlank()) {
            throw new IllegalArgumentException("courier vacío: nunca se envía /tpy a secas.");
        }
        return "/tpy " + courier;
    }

    private static ChatEvent kitbotEvent(String text) {
        if (text.equals(PLACED)) return new ChatEvent.Placed();
        if (text.equals(ACTIVE)) return new ChatEvent.Active();
        if (text.startsWith(USAGE_PREFIX)) return new ChatEvent.Usage();
        if (UNREGISTERED.matcher(text).matches()) return new ChatEvent.Unregistered();

        Matcher cooldown = COOLDOWN.matcher(text);
        if (cooldown.matches()) {
            long amount = Long.parseLong(cooldown.group(1));
            return new ChatEvent.Cooldown(cooldown.group(2).startsWith("minute") ? amount * 60_000 : amount * 1_000);
        }

        return new ChatEvent.UnknownKitbot(text);
    }

    private static Optional<ChatEvent> courierEvent(String courier, String text) {
        return Optional.ofNullable(switch (text) {
            case READY -> new ChatEvent.Ready(courier);
            case PARTIAL -> new ChatEvent.Partial(courier);
            case NOT_FOUND -> new ChatEvent.NotFound(courier);
            case DONE -> new ChatEvent.Done(courier);
            case TIMED_OUT -> new ChatEvent.TimedOut(courier);
            default -> null;
        });
    }
}
