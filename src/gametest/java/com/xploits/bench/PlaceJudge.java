package com.xploits.bench;

import com.xploits.bench.mixin.ServerMiningAccessor;
import com.xploits.printer.core.Aim;
import com.xploits.printer.core.Face;
import com.xploits.printer.core.Point;
import com.xploits.printer.core.Pos;
import com.xploits.printer.core.PrinterLimits;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.List;

/**
 * The server's own re-check of every block interaction and dig START the player sends during a CHECK (restock spec §3
 * "look at the container (reach ≤ 4.5)"; the printer's N-M6). It runs on the server thread just before the server acts on
 * the packet ({@code PlaceJudgeMixin}), with the server's world, the position and eye height the server knows and the
 * rotation it last received. An interaction is judged on four counts: what it clicks is a real block (no airplace), the
 * hit point is within 4.5 blocks of the eye, the face looks at the eye, and the vanilla raycast from the eye along the
 * server's rotation returns that block and face — which also proves the rotation arrived before the click. A dig START
 * is judged on reach, face and ray. Container clicks and closes are judged too ({@link ContainerVerdict}), owner ruling
 * R43's one click in the player's own inventory with a verdict of its own ({@link #ownInventoryMove}). Only the bench's
 * own player, between {@link #start} and {@link #stop}. Never prints a position.
 */
public final class PlaceJudge {
    /** Far enough past the reach for the ray to show what really lies on the line of sight. */
    private static final double RAY = 6.0;
    /** Below this, a movement packet did not move the player (as the bench's rules recorder reads one). */
    private static final double MOVED = 1.0E-4;

    enum Kind { PLACE, DIG }

    /** One judged packet: which of the counts failed. */
    record Verdict(Kind kind, boolean airplace, boolean tooFar, boolean faceAway, boolean rayMisses) {
        boolean ok() {
            return !airplace && !tooFar && !faceAway && !rayMisses;
        }
    }

    /**
     * One container click or close as the server received it (restock spec §3: only QUICK_MOVE, in the screen restock
     * opened, with the cursor empty): which of the counts failed. {@code closedByServer}: the first close after the server
     * dropped a container screen on its own (it moved the player back to their own screen, as when the chest went out of
     * range or was broken), carrying exactly that screen's syncId: the client's close crossed the server's, which is
     * neither a wrong screen nor a failure. Any other close while the server is on the player's own screen (another
     * syncId, the same one a second time, or one the server never dropped) is a wrong screen. {@code ownMove}: owner
     * ruling R43's one click in the player's own inventory, judged as narrowly as the server can see it
     * ({@link #ownInventoryMove}: syncId 0, QUICK_MOVE, button 0, the server on the player's own screen, nothing on the
     * cursor, a main-inventory slot holding a shulker box with items in it, a free hotbar slot for it to go to, no dig
     * under way, the player standing still); that one is not a wrong screen, and every CHECK says how many it expects.
     * Any other syncId-0 click stays a wrong screen.
     */
    record ContainerVerdict(boolean close, boolean notQuickMove, boolean wrongScreen, boolean cursorFull,
                            boolean closedByServer, boolean ownMove) {
        ContainerVerdict(boolean close, boolean notQuickMove, boolean wrongScreen, boolean cursorFull) {
            this(close, notQuickMove, wrongScreen, cursorFull, false, false);
        }

        ContainerVerdict(boolean close, boolean notQuickMove, boolean wrongScreen, boolean cursorFull,
                         boolean closedByServer) {
            this(close, notQuickMove, wrongScreen, cursorFull, closedByServer, false);
        }

        boolean ok() {
            return !notQuickMove && !wrongScreen && !cursorFull;
        }
    }

    private static final List<Verdict> VERDICTS = new ArrayList<>();
    private static final List<ContainerVerdict> CONTAINER = new ArrayList<>();
    /** The block each judged interaction clicked, in order; compared, never printed. */
    private static final List<BlockPos> CLICKED = new ArrayList<>();
    private static String judged;
    /** The syncId of the container screen the server last dropped on its own, until the next close comes; 0: none. */
    private static int dropped;
    /** The last movement packet the server got from the judged player moved them (owner ruling R43: still only). */
    private static boolean moved;

    private PlaceJudge() {
    }

    /** From now on, judges {@code playerName}'s packets; forgets every earlier verdict. Any thread. */
    static synchronized void start(String playerName) {
        VERDICTS.clear();
        CONTAINER.clear();
        CLICKED.clear();
        dropped = 0;
        moved = false;
        judged = playerName;
    }

    static synchronized void stop() {
        judged = null;
    }

    static synchronized List<Verdict> verdicts() {
        return List.copyOf(VERDICTS);
    }

    static synchronized List<ContainerVerdict> containerVerdicts() {
        return List.copyOf(CONTAINER);
    }

    /** The block each judged interaction clicked, in order. Positions: compare them, never print them. */
    static synchronized List<BlockPos> clicked() {
        return List.copyOf(CLICKED);
    }

    /** Block interactions judged (the container clicks; phase B's placements). */
    static synchronized int places() {
        return (int) VERDICTS.stream().filter(v -> v.kind() == Kind.PLACE).count();
    }

    /** Owner ruling R43's clicks the server saw ({@link #ownInventoryMove}). */
    static synchronized int ownMoves() {
        return (int) CONTAINER.stream().filter(ContainerVerdict::ownMove).count();
    }

    /**
     * "2 slot click(s) and 1 close(s) seen by the server: not a quick move 0, not the open screen 0, cursor not empty
     * 0, already closed by the server 0, own-inventory moves 0".
     */
    static synchronized String containerWords() {
        int clicks = 0;
        int closes = 0;
        int move = 0;
        int screen = 0;
        int cursor = 0;
        int already = 0;
        int own = 0;
        for (ContainerVerdict v : CONTAINER) {
            if (v.close()) closes++;
            else clicks++;
            if (v.notQuickMove()) move++;
            if (v.wrongScreen()) screen++;
            if (v.cursorFull()) cursor++;
            if (v.closedByServer()) already++;
            if (v.ownMove()) own++;
        }
        return clicks + " slot click(s) and " + closes + " close(s) seen by the server: not a quick move " + move
            + ", not the open screen " + screen + ", cursor not empty " + cursor
            + ", already closed by the server " + already + ", own-inventory moves " + own;
    }

    /** "1 interaction(s) and 0 dig start(s) judged: airplace 0, beyond reach 0, …" (words between every count). */
    static synchronized String words() {
        int interacts = 0;
        int digs = 0;
        int airplace = 0;
        int far = 0;
        int away = 0;
        int misses = 0;
        for (Verdict v : VERDICTS) {
            if (v.kind() == Kind.PLACE) interacts++;
            else digs++;
            if (v.airplace()) airplace++;
            if (v.tooFar()) far++;
            if (v.faceAway()) away++;
            if (v.rayMisses()) misses++;
        }
        return interacts + " interaction(s) and " + digs + " dig start(s) judged: airplace " + airplace
            + ", beyond reach " + far + ", face turned away " + away + ", ray on another block or face " + misses;
    }

    /** Server thread, from the mixin: a block interaction about to be handled. */
    public static void place(ServerPlayerEntity player, PlayerInteractBlockC2SPacket packet) {
        if (!judging(player)) return;
        ServerWorld world = player.getEntityWorld();
        BlockHitResult hit = packet.getBlockHitResult();
        Vec3d eye = player.getEyePos();
        BlockState clicked = world.getBlockState(hit.getBlockPos());
        boolean airplace = clicked.isAir() || clicked.isReplaceable();
        boolean tooFar = eye.distanceTo(hit.getPos()) > PrinterLimits.DEFAULTS.maxReach();
        boolean faceAway = !Aim.facesEye(pos(hit.getBlockPos()), face(hit.getSide()), point(eye));
        BlockHitResult ray = ray(player, world, eye);
        boolean rayMisses = ray.getType() != HitResult.Type.BLOCK || !ray.getBlockPos().equals(hit.getBlockPos())
            || ray.getSide() != hit.getSide();
        add(new Verdict(Kind.PLACE, airplace, tooFar, faceAway, rayMisses));
        addClicked(hit.getBlockPos().toImmutable());
    }

    /** Server thread, from the mixin: a player action about to be handled; only a dig START is judged. */
    public static void action(ServerPlayerEntity player, PlayerActionC2SPacket packet) {
        if (packet.getAction() != PlayerActionC2SPacket.Action.START_DESTROY_BLOCK || !judging(player)) return;
        ServerWorld world = player.getEntityWorld();
        Vec3d eye = player.getEyePos();
        BlockHitResult ray = ray(player, world, eye);
        boolean rayMisses = ray.getType() != HitResult.Type.BLOCK || !ray.getBlockPos().equals(packet.getPos())
            || ray.getSide() != packet.getDirection();
        double distance = rayMisses ? eye.distanceTo(Vec3d.ofCenter(packet.getPos())) : eye.distanceTo(ray.getPos());
        boolean tooFar = distance > PrinterLimits.DEFAULTS.maxReach();
        boolean faceAway = !Aim.facesEye(pos(packet.getPos()), face(packet.getDirection()), point(eye));
        add(new Verdict(Kind.DIG, false, tooFar, faceAway, rayMisses));
    }

    /** Server thread, from the mixin: a slot click about to be handled. */
    public static void click(ServerPlayerEntity player, ClickSlotC2SPacket packet) {
        if (!judging(player)) return;
        boolean ownMove = ownInventoryMove(player, packet);
        addContainer(new ContainerVerdict(false, packet.actionType() != SlotActionType.QUICK_MOVE,
            !ownMove && (packet.syncId() == 0 || packet.syncId() != player.currentScreenHandler.syncId),
            !player.currentScreenHandler.getCursorStack().isEmpty(), false, ownMove));
    }

    /**
     * Owner ruling R43, judged before the server handles the click (the slot still holds what was clicked), as narrowly
     * as the server can see it: syncId 0, QUICK_MOVE, button 0; the server on the player's own screen (no container
     * open) with nothing on the cursor; a main-inventory slot (9–35) holding a shulker box that holds items (the box
     * about to be unpacked holds the material; an empty one is never unpacked); a free hotbar slot, so vanilla's
     * {@code PlayerScreenHandler.quickMove} moves it main inventory → hotbar (slots 9–35 go to 36–44, the first empty
     * one first; verified with {@code javap -c}) and nowhere else; no dig under way as the server holds it
     * ({@link ServerMiningAccessor}); and the player standing still as the server knows it — the last movement packet
     * did not move them, the last input packet held no movement, jump, sneak or sprint, on the ground, not sprinting,
     * sneaking or using an item. That stillness is stricter than restock's own ({@code TripDriver.still}, ruling R25:
     * input, ground, no walking goal): a client still in place stops sending positions but for a heartbeat every 20
     * ticks, so for up to about 20 ticks after a walk ends the last position packet still moved the player, and a click
     * then is refused. Restock never makes this click right after a walk (a carried box lands in the hotbar; the unpack
     * of a carried box starts after the leave gate), so this only fails on the safe side. Which box restock was about
     * to unpack the server cannot know: each CHECK proves that with the slots it reads (M13).
     */
    static boolean ownInventoryMove(ServerPlayerEntity player, ClickSlotC2SPacket packet) {
        if (packet.syncId() != 0 || packet.actionType() != SlotActionType.QUICK_MOVE || packet.button() != 0) {
            return false;
        }
        PlayerScreenHandler own = player.playerScreenHandler;
        if (player.currentScreenHandler != own || own.syncId != 0) return false;
        if (!own.getCursorStack().isEmpty()) return false;
        int slot = packet.slot();
        if (slot < PlayerScreenHandler.INVENTORY_START || slot >= PlayerScreenHandler.INVENTORY_END) return false;
        ItemStack box = own.getSlot(slot).getStack();
        ContainerComponent inside = box.get(DataComponentTypes.CONTAINER);
        if (!box.isIn(ItemTags.SHULKER_BOXES) || inside == null || !inside.iterateNonEmpty().iterator().hasNext()) {
            return false;
        }
        boolean hotbarRoom = false;
        for (int i = PlayerScreenHandler.HOTBAR_START; i < PlayerScreenHandler.HOTBAR_END; i++) {
            if (own.getSlot(i).getStack().isEmpty()) hotbarRoom = true;
        }
        if (!hotbarRoom) return false;
        if (((ServerMiningAccessor) player.interactionManager).xploits$mining()) return false;
        return !lastMoveMoved() && PlayerInput.DEFAULT.equals(player.getPlayerInput()) && player.isOnGround()
            && !player.isSprinting() && !player.isSneaking() && !player.isUsingItem();
    }

    /**
     * Server thread, from the mixin: a movement packet about to be handled — whether it moves the player from where
     * the server has them (owner ruling R43's click is judged only while still).
     */
    public static void move(ServerPlayerEntity player, PlayerMoveC2SPacket packet) {
        if (!judging(player)) return;
        boolean moves = packet.changesPosition() && (Math.abs(packet.getX(player.getX()) - player.getX()) > MOVED
            || Math.abs(packet.getY(player.getY()) - player.getY()) > MOVED
            || Math.abs(packet.getZ(player.getZ()) - player.getZ()) > MOVED);
        setMoved(moves);
    }

    private static synchronized void setMoved(boolean moves) {
        moved = moves;
    }

    private static synchronized boolean lastMoveMoved() {
        return moved;
    }

    /**
     * Server thread, from the mixin: a screen close about to be handled. The screen the server dropped is forgotten at
     * every close the client sends: only the first one after the drop can be the one that crossed it, since the client
     * sends no close for a screen the server closed and cannot open another one in between.
     */
    public static void close(ServerPlayerEntity player, CloseHandledScreenC2SPacket packet) {
        if (!judging(player)) return;
        int droppedByServer = forgetDropped();
        boolean serverHome = player.currentScreenHandler == player.playerScreenHandler;
        boolean closedByServer = serverHome && droppedByServer != 0 && packet.getSyncId() == droppedByServer;
        addContainer(new ContainerVerdict(true, false,
            !closedByServer && packet.getSyncId() != player.currentScreenHandler.syncId,
            !player.currentScreenHandler.getCursorStack().isEmpty(), closedByServer));
    }

    /**
     * Server thread, from the mixin: the server is about to drop the player's screen on its own (out of range, the
     * container broken, another screen opened), with no close from the client. Remembers a container screen's syncId.
     */
    public static void serverClosed(ServerPlayerEntity player) {
        if (!judging(player) || player.currentScreenHandler == player.playerScreenHandler) return;
        remember(player.currentScreenHandler.syncId);
    }

    private static synchronized void remember(int syncId) {
        dropped = syncId;
    }

    private static synchronized int forgetDropped() {
        int was = dropped;
        dropped = 0;
        return was;
    }

    private static synchronized void addContainer(ContainerVerdict v) {
        CONTAINER.add(v);
    }

    private static synchronized boolean judging(ServerPlayerEntity player) {
        return judged != null && judged.equals(player.getGameProfile().name());
    }

    private static synchronized void add(Verdict v) {
        VERDICTS.add(v);
    }

    private static synchronized void addClicked(BlockPos block) {
        CLICKED.add(block);
    }

    /** The vanilla crosshair ray: outline shapes, fluids ignored, from the eye along the rotation the server holds. */
    private static BlockHitResult ray(ServerPlayerEntity player, ServerWorld world, Vec3d eye) {
        Vec3d end = eye.add(player.getRotationVec(1.0f).multiply(RAY));
        return world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE,
            RaycastContext.FluidHandling.NONE, player));
    }

    private static Pos pos(BlockPos b) {
        return new Pos(b.getX(), b.getY(), b.getZ());
    }

    private static Face face(Direction d) {
        return Face.valueOf(d.name());
    }

    private static Point point(Vec3d v) {
        return new Point(v.x, v.y, v.z);
    }
}
