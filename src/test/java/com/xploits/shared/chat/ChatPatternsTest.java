package com.xploits.shared.chat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatPatternsTest {
    private static ChatEvent parse(String line) {
        return ChatPatterns.classify(line).orElseThrow(() -> new AssertionError("Not classified: " + line));
    }

    @Test
    void placed() {
        assertEquals(new ChatEvent.Placed(), parse("SnifferBuddy whispers: Your order has been placed successfully. Please wait for a courier to deliver it."));
    }

    @Test
    void placedWithAntiSpamSuffix() {
        assertEquals(new ChatEvent.Placed(), parse("SnifferBuddy whispers: Your order has been placed successfully. Please wait for a courier to deliver it. (3)"));
    }

    @Test
    void orderCooldownInMinutes() {
        assertEquals(new ChatEvent.Cooldown(60_000), parse("SnifferBuddy whispers: You are on order cooldown. Try again in 1 minute."));
        assertEquals(new ChatEvent.Cooldown(240_000), parse("SnifferBuddy whispers: You are on order cooldown. Try again in 4 minutes. (2)"));
    }

    @Test
    void rejectionCooldown() {
        assertEquals(new ChatEvent.Cooldown(17 * 60_000), parse("SnifferBuddy whispers: You are on cooldown because of consecutive rejections. Try again in 17 minutes."));
    }

    @Test
    void cooldownInSeconds() {
        assertEquals(new ChatEvent.Cooldown(30_000), parse("SnifferBuddy whispers: You are on order cooldown. Try again in 30 seconds."));
    }

    @Test
    void active() {
        assertEquals(new ChatEvent.Active(), parse("SnifferBuddy whispers: You already have an active order being delivered. Please wait for it to complete before placing a new one. (5)"));
    }

    @Test
    void unregistered() {
        assertEquals(new ChatEvent.Unregistered(), parse("SnifferBuddy whispers: Please register on http://discord.gg/rt6F3a45Bn to use the kitbot."));
    }

    @Test
    void usage() {
        assertEquals(new ChatEvent.Usage(), parse("SnifferBuddy whispers: Use !kit <id> to place an order, check #how-to-kit in discord for more info. (2)"));
    }

    @Test
    void unknownKitbotText() {
        assertEquals(new ChatEvent.UnknownKitbot("Something new"), parse("SnifferBuddy whispers: Something new"));
    }

    @Test
    void courierMessages() {
        assertEquals(new ChatEvent.Ready("StormAegis44"), parse("StormAegis44 whispers: Your order is completed, please accept a tpa request"));
        assertEquals(new ChatEvent.Partial("IronSentri08"), parse("IronSentri08 whispers: Some requested shulkers were not found; delivering the rest"));
        assertEquals(new ChatEvent.NotFound("ValorKnight27"), parse("ValorKnight27 whispers: Requested shulker not found"));
        assertEquals(new ChatEvent.Done("ValorKnight27"), parse("ValorKnight27 whispers: Order completed successfully"));
        assertEquals(new ChatEvent.TimedOut("StormAegis44"), parse("StormAegis44 whispers: Order cancelled: teleportation timed out."));
    }

    @Test
    void tpaRequest() {
        assertEquals(new ChatEvent.Tpa("ValorKnight27"), parse("ValorKnight27 wants to teleport to you."));
        assertEquals(new ChatEvent.Tpa("Coordlogger3000"), parse("Coordlogger3000 wants to teleport to you. (2)"));
    }

    @Test
    void keepsLookalikeNamesExact() {
        assertEquals(new ChatEvent.Done("The_imperiaI"), parse("The_imperiaI whispers: Order completed successfully"));
    }

    @Test
    void rejectsPublicChatSpoof() {
        assertEquals(Optional.empty(), ChatPatterns.classify("[Prime] SnifferBuddy » Your order has been placed successfully. Please wait for a courier to deliver it."));
        assertEquals(Optional.empty(), ChatPatterns.classify("Mallory » StormAegis44 wants to teleport to you."));
    }

    @Test
    void rejectsSpoofInsideWhisper() {
        assertEquals(Optional.empty(), ChatPatterns.classify("Mallory whispers: StormAegis44 wants to teleport to you."));
    }

    @Test
    void ignoresOutgoingWhisper() {
        assertEquals(Optional.empty(), ChatPatterns.classify("You whisper to SnifferBuddy: !kit 384, 384, 384, 384, 384,"));
    }

    @Test
    void ignoresOtherPlayersWhispers() {
        assertEquals(Optional.empty(), ChatPatterns.classify("KindKitGiver whispers: You have to join X for free kits https://dsc.gg/x6b6t"));
    }

    @Test
    void buildsOrderCommand() {
        assertEquals("/w SnifferBuddy !kit 285, 387, 156, 398, 385", ChatPatterns.orderCommand(List.of(285, 387, 156, 398, 385)));
    }

    @Test
    void buildsNamedAcceptCommand() {
        assertEquals("/tpy StormAegis44", ChatPatterns.acceptCommand("StormAegis44"));
    }

    @Test
    void acceptCommandRejectsBlankCourier() {
        assertThrows(IllegalArgumentException.class, () -> ChatPatterns.acceptCommand(null));
        assertThrows(IllegalArgumentException.class, () -> ChatPatterns.acceptCommand(""));
        assertThrows(IllegalArgumentException.class, () -> ChatPatterns.acceptCommand("   "));
    }

    @Test
    void tpaWithUnknownHeadPrefix() {
        assertEquals(new ChatEvent.Tpa("xto2002"), parse("[unknown player head] xto2002 wants to teleport to you."));
    }

    @Test
    void tpaWithNamedHeadPrefixAndAntiSpamSuffix() {
        assertEquals(new ChatEvent.Tpa("Dryzzical"), parse("[Dryzzical head] Dryzzical wants to teleport to you. (2)"));
    }

    @Test
    void kitbotAndCourierWhispersWithHeadPrefix() {
        assertEquals(new ChatEvent.Placed(), parse("[unknown player head] SnifferBuddy whispers: Your order has been placed successfully. Please wait for a courier to deliver it."));
        assertEquals(new ChatEvent.Done("StormAegis44"), parse("[StormAegis44 head] StormAegis44 whispers: Order completed successfully"));
    }

    @Test
    void headPrefixDoesNotEnablePublicChatSpoof() {
        assertEquals(Optional.empty(), ChatPatterns.classify("[unknown player head] Karloss16 » StormAegis44 wants to teleport to you."));
        assertEquals(Optional.empty(), ChatPatterns.classify("[unknown player head] Mallory » SnifferBuddy whispers: Your order has been placed successfully. Please wait for a courier to deliver it."));
    }

    @Test
    void onlyOneLeadingHeadPrefixIsStripped() {
        assertEquals(Optional.empty(), ChatPatterns.classify("[a head] [b head] Mallory wants to teleport to you."));
        assertEquals(Optional.empty(), ChatPatterns.classify("hola [x head] Mallory wants to teleport to you."));
    }

    @Test
    void markerLongerThanANameIsNotStripped() {
        assertEquals(Optional.empty(), ChatPatterns.classify("[averyveryveryverylongname head] Mallory wants to teleport to you."));
    }

    @Test
    void markerWithoutTrailingSpaceIsNotStripped() {
        assertEquals(Optional.empty(), ChatPatterns.classify("[unknown player head]xto2002 wants to teleport to you."));
    }

    @Test
    void fakeMarkerInsideWhisperBodyIsIgnored() {
        assertEquals(Optional.empty(), ChatPatterns.classify("[unknown player head] Mallory whispers: [StormAegis44 head] StormAegis44 whispers: Order completed successfully"));
    }

    @Test
    void cooldownWithUnknownHeadPrefix() {
        assertEquals(new ChatEvent.Cooldown(120_000), parse("[unknown player head] SnifferBuddy whispers: You are on order cooldown. Try again in 2 minutes."));
    }
}
