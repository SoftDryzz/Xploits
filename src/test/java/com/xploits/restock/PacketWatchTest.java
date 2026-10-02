package com.xploits.restock;

import com.xploits.printer.core.PaceRules;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.BundleItemSelectedC2SPacket;
import net.minecraft.network.packet.c2s.play.ButtonClickC2SPacket;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientTickEndC2SPacket;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.c2s.play.CraftRequestC2SPacket;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PickItemFromBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PickItemFromEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket;
import net.minecraft.network.packet.c2s.play.SlotChangedStateC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.recipe.NetworkRecipeId;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deferred L36/L37 (Task 9 review M5, M6): how {@link PacketWatch#classify} reads each packet that leaves. Inventory
 * actions the player makes are actions like any other (PaceRules: conservative on purpose), so a take never shares a
 * tick or a Grim tick with the player's pick-block or bundle scroll. The packets are built here; nothing is sent.
 */
class PacketWatchTest {
    private static final BlockPos AT = new BlockPos(1, 2, 3);

    @Test
    void thePlayersOwnInventoryActionsAreForeignActions() {
        List<Packet<?>> actions = List.of(
            new PickItemFromBlockC2SPacket(AT, false),
            new PickItemFromEntityC2SPacket(7, false),
            new BundleItemSelectedC2SPacket(9, 1),
            new ButtonClickC2SPacket(1, 0),
            new CraftRequestC2SPacket(1, new NetworkRecipeId(4), false),
            new SelectMerchantTradeC2SPacket(2),
            new SlotChangedStateC2SPacket(0, 1, true));
        for (Packet<?> p : actions) {
            assertEquals(PaceRules.Packet.of(PaceRules.Kind.ACTION_OTHER, false), PacketWatch.classify(p, false),
                p.getClass().getSimpleName());
            assertEquals(PaceRules.Packet.of(PaceRules.Kind.ACTION_OTHER, true), PacketWatch.classify(p, true),
                p.getClass().getSimpleName() + ", sent by restock");
        }
    }

    @Test
    void aCreativeInventoryActionIsAForeignActionToo() {
        // Its packet class holds an item stack codec, which needs the game's registries: it cannot be built outside the
        // game (Bootstrap fails without Fabric's loader), so only its place in the list is pinned here.
        assertTrue(PacketWatch.PLAYER_ACTIONS.contains(CreativeInventoryActionC2SPacket.class));
        assertEquals(8, PacketWatch.PLAYER_ACTIONS.size(), "the eight of the Task 9 review");
    }

    @Test
    void aMoveCarriesItsLookOnlyWhenItHasOne() {
        assertEquals(PaceRules.Packet.move(true, 90f, 10f),
            PacketWatch.classify(new PlayerMoveC2SPacket.Full(0.5, 64, 0.5, 90f, 10f, true, false), false));
        assertEquals(PaceRules.Packet.move(true, 45f, -5f),
            PacketWatch.classify(new PlayerMoveC2SPacket.LookAndOnGround(45f, -5f, true, false), true),
            "a move is never restock's action");
        assertFalse(PacketWatch.classify(new PlayerMoveC2SPacket.PositionAndOnGround(0.5, 64, 0.5, true, false), false)
            .rotation());
        assertEquals(PaceRules.Kind.MOVE,
            PacketWatch.classify(new PlayerMoveC2SPacket.OnGroundOnly(true, false), false).kind());
    }

    @Test
    void anInputMovesOnlyWithAMovementJumpOrSneakKey() {
        PlayerInput sprint = new PlayerInput(false, false, false, false, false, false, true);
        assertEquals(PaceRules.Packet.input(false), PacketWatch.classify(new PlayerInputC2SPacket(sprint), false),
            "sprint alone is not moving");
        PlayerInput none = new PlayerInput(false, false, false, false, false, false, false);
        assertEquals(PaceRules.Packet.input(false), PacketWatch.classify(new PlayerInputC2SPacket(none), true));
        for (int key = 0; key < 6; key++) {
            boolean[] k = new boolean[6];
            k[key] = true;
            PlayerInput in = new PlayerInput(k[0], k[1], k[2], k[3], k[4], k[5], false);
            assertTrue(PacketWatch.classify(new PlayerInputC2SPacket(in), false).flag(), "key " + key);
        }
    }

    @Test
    void thePlayerActionsByWhatTheyDo() {
        assertEquals(PaceRules.Kind.DIG_START, action(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK));
        assertEquals(PaceRules.Kind.DIG_STOP, action(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK));
        assertEquals(PaceRules.Kind.DIG_ABORT, action(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK));
        assertEquals(PaceRules.Kind.RELEASE_USE, action(PlayerActionC2SPacket.Action.RELEASE_USE_ITEM));
        assertEquals(PaceRules.Kind.INTERACT_ENTITY, action(PlayerActionC2SPacket.Action.STAB));
        assertEquals(PaceRules.Kind.ACTION_OTHER, action(PlayerActionC2SPacket.Action.DROP_ITEM));
        assertEquals(PaceRules.Kind.ACTION_OTHER, action(PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND));
    }

    @Test
    void theRestKeepTheirKindAndRestocksMark() {
        assertEquals(PaceRules.Packet.of(PaceRules.Kind.TICK_END, false),
            PacketWatch.classify(ClientTickEndC2SPacket.INSTANCE, false));
        assertEquals(PaceRules.Packet.of(PaceRules.Kind.USE_ITEM, true),
            PacketWatch.classify(new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, 0, 0f, 0f), true));
        assertEquals(PaceRules.Packet.of(PaceRules.Kind.CLOSE_SCREEN, true),
            PacketWatch.classify(new CloseHandledScreenC2SPacket(3), true));
        assertEquals(PaceRules.Packet.of(PaceRules.Kind.SWING, false),
            PacketWatch.classify(new HandSwingC2SPacket(Hand.MAIN_HAND), false));
        assertEquals(PaceRules.Packet.slot(4, false), PacketWatch.classify(new UpdateSelectedSlotC2SPacket(4), false));
    }

    @Test
    void sprintingStartsAndStopsWithTheClientCommand() {
        // A1 review m2: the command packet is built only from an Entity, so it is decoded as the server reads it.
        ClientCommandC2SPacket start = command(ClientCommandC2SPacket.Mode.START_SPRINTING);
        assertEquals(ClientCommandC2SPacket.Mode.START_SPRINTING, start.getMode());
        assertEquals(PaceRules.Packet.sprint(true), PacketWatch.classify(start, false));
        assertEquals(PaceRules.Packet.sprint(true), PacketWatch.classify(start, true));
        ClientCommandC2SPacket stop = command(ClientCommandC2SPacket.Mode.STOP_SPRINTING);
        assertEquals(ClientCommandC2SPacket.Mode.STOP_SPRINTING, stop.getMode());
        assertEquals(PaceRules.Packet.sprint(false), PacketWatch.classify(stop, false));
        assertEquals(PaceRules.Packet.of(PaceRules.Kind.OTHER, false),
            PacketWatch.classify(command(ClientCommandC2SPacket.Mode.OPEN_INVENTORY), false), "not a sprint");
    }

    @Test
    void aBlockInteractionIsAPlaceAndKeepsRestocksMark() {
        PlayerInteractBlockC2SPacket click = new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND,
            new BlockHitResult(new Vec3d(1.5, 2.5, 3.5), Direction.UP, AT, false), 1);
        assertEquals(PaceRules.Packet.of(PaceRules.Kind.PLACE, true), PacketWatch.classify(click, true));
        assertEquals(PaceRules.Packet.of(PaceRules.Kind.PLACE, false), PacketWatch.classify(click, false));
    }

    @Test
    void anEntityInteractionIsInteractEntity() {
        // Built only from an Entity too: decoded (entity id, ATTACK = ordinal 1, not sneaking).
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeVarInt(7);
        buf.writeVarInt(1);
        buf.writeBoolean(false);
        PlayerInteractEntityC2SPacket hit = PlayerInteractEntityC2SPacket.CODEC.decode(buf);
        assertEquals(0, buf.readableBytes(), "the whole packet was read");
        assertEquals(PaceRules.Packet.of(PaceRules.Kind.INTERACT_ENTITY, false), PacketWatch.classify(hit, false));
    }

    @Test
    void aSlotClickIsAClickSlotAndKeepsRestocksMark() {
        ClickSlotC2SPacket take = new ClickSlotC2SPacket(3, 0, (short) 0, (byte) 0, SlotActionType.QUICK_MOVE,
            Int2ObjectMaps.emptyMap(), ItemStackHash.EMPTY);
        assertEquals(PaceRules.Packet.of(PaceRules.Kind.CLICK_SLOT, true), PacketWatch.classify(take, true));
        assertEquals(PaceRules.Packet.of(PaceRules.Kind.CLICK_SLOT, false), PacketWatch.classify(take, false));
    }

    @Test
    void aStartThatBreaksAtOnceIsMarkedInstant() {
        PlayerActionC2SPacket start = new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,
            BlockPos.ORIGIN, Direction.UP, 1);
        assertEquals(PaceRules.Packet.digStart(true, true), PacketWatch.classify(start, true, true));
        assertEquals(PaceRules.Packet.digStart(false, false), PacketWatch.classify(start, false));
        assertEquals(PaceRules.Packet.of(PaceRules.Kind.DIG_STOP, true), PacketWatch.classify(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, BlockPos.ORIGIN, Direction.UP, 2), true, true),
            "only a START is ever instant");
    }

    /** A client command as the server decodes it: entity id, mode, mount jump. */
    private static ClientCommandC2SPacket command(ClientCommandC2SPacket.Mode mode) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeVarInt(7);
        buf.writeEnumConstant(mode);
        buf.writeVarInt(0);
        return ClientCommandC2SPacket.CODEC.decode(buf);
    }

    private static PaceRules.Kind action(PlayerActionC2SPacket.Action a) {
        PaceRules.Packet p = PacketWatch.classify(new PlayerActionC2SPacket(a, AT, Direction.UP), true);
        assertTrue(p.ours(), "an action keeps restock's mark");
        return p.kind();
    }
}
