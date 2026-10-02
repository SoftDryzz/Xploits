package com.xploits.bench;

import com.xploits.printer.core.Aim;
import com.xploits.printer.core.Face;
import com.xploits.printer.core.Point;
import com.xploits.printer.core.Pos;
import com.xploits.printer.core.PrinterLimits;
import net.minecraft.block.BlockState;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.world.ServerWorld;
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
 * is judged on reach, face and ray. Only the bench's own player, between {@link #start} and {@link #stop}. Never prints a
 * position.
 */
public final class PlaceJudge {
    /** Far enough past the reach for the ray to show what really lies on the line of sight. */
    private static final double RAY = 6.0;

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
     * syncId, the same one a second time, or one the server never dropped) is a wrong screen.
     */
    record ContainerVerdict(boolean close, boolean notQuickMove, boolean wrongScreen, boolean cursorFull,
                            boolean closedByServer) {
        ContainerVerdict(boolean close, boolean notQuickMove, boolean wrongScreen, boolean cursorFull) {
            this(close, notQuickMove, wrongScreen, cursorFull, false);
        }

        boolean ok() {
            return !notQuickMove && !wrongScreen && !cursorFull;
        }
    }

    private static final List<Verdict> VERDICTS = new ArrayList<>();
    private static final List<ContainerVerdict> CONTAINER = new ArrayList<>();
    private static String judged;
    /** The syncId of the container screen the server last dropped on its own, until the next close comes; 0: none. */
    private static int dropped;

    private PlaceJudge() {
    }

    /** From now on, judges {@code playerName}'s packets; forgets every earlier verdict. Any thread. */
    static synchronized void start(String playerName) {
        VERDICTS.clear();
        CONTAINER.clear();
        dropped = 0;
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

    /** Block interactions judged (the container clicks; phase B's placements). */
    static synchronized int places() {
        return (int) VERDICTS.stream().filter(v -> v.kind() == Kind.PLACE).count();
    }

    /** "2 slot click(s) and 1 close(s) seen by the server: not a quick move 0, not the open screen 0, cursor not empty 0, already closed by the server 0". */
    static synchronized String containerWords() {
        int clicks = 0;
        int closes = 0;
        int move = 0;
        int screen = 0;
        int cursor = 0;
        int already = 0;
        for (ContainerVerdict v : CONTAINER) {
            if (v.close()) closes++;
            else clicks++;
            if (v.notQuickMove()) move++;
            if (v.wrongScreen()) screen++;
            if (v.cursorFull()) cursor++;
            if (v.closedByServer()) already++;
        }
        return clicks + " slot click(s) and " + closes + " close(s) seen by the server: not a quick move " + move
            + ", not the open screen " + screen + ", cursor not empty " + cursor
            + ", already closed by the server " + already;
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
        addContainer(new ContainerVerdict(false, packet.actionType() != SlotActionType.QUICK_MOVE,
            packet.syncId() == 0 || packet.syncId() != player.currentScreenHandler.syncId,
            !player.currentScreenHandler.getCursorStack().isEmpty()));
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
