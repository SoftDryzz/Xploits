package com.xploits.restock;

import com.xploits.printer.core.Aim;
import com.xploits.printer.core.BreakPlan;
import com.xploits.printer.core.Face;
import com.xploits.printer.core.HotbarPlan;
import com.xploits.printer.core.PlacePlanner;
import com.xploits.printer.core.Point;
import com.xploits.printer.core.Pos;
import com.xploits.printer.core.PrinterLimits;
import com.xploits.restock.core.BorrowedShulkers;
import com.xploits.restock.core.ContainerAim;
import com.xploits.restock.core.RestockLimits;
import com.xploits.restock.core.RestockMessages;
import com.xploits.restock.core.RestockReason;
import com.xploits.restock.core.RestockTrip;
import com.xploits.restock.core.ShulkerSpot;
import com.xploits.restock.core.ShulkersLeft;
import com.xploits.restock.core.UnpackPlan;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * One shulker box unpacked at the build (restock spec §3 "Shulkers at the build"): it measures {@link UnpackPlan.Facts}
 * once a tick and executes the core's action. The take from the box set down is an inner {@link TripDriver} (it keeps a
 * slot free, takes against the loose need, never carries); once it ends, its own screen left open by a take cut short,
 * or a screen that answers its click late, is still closed until the unpack ends — never in a tick a dig under way
 * swings, stops or aborts in (M2). Aims as the trip does — requested from {@code SendMovementPacketsEvent.Pre} only
 * while Meteor's rotation queue is empty, the click the next tick only if the server holds that exact rotation and the
 * ray still sees the face; the dig's rotation stays requested from START to STOP. Right before each packet what must
 * hold is checked again, and nothing is sent when it does not (ruling R27; R43 for the one inventory click). A tick in
 * which a container action went out reads as "screen not free" for the core, so no second action shares it. The box,
 * its drop and the boxes carried are matched by kind (item and custom name, M4). Nothing is sent from {@link #abort}
 * (pre-flight 19-19). Positions stay in memory. Client thread.
 */
final class UnpackDriver {
    sealed interface Result permits Running, Done, Ended {
    }

    record Running() implements Result {
    }

    /** The box is back, the slot too: what the unpack brought in (loose), and any box still standing (a late place). */
    record Done(Map<String, Integer> taken, ShulkersLeft left) implements Result {
    }

    record Ended(RestockReason reason, String detail) implements Result {
    }

    /** HotbarPlan's name for this unpack's box: its kind, holding the material. */
    private static final String MARK = "restock:unpacking";
    private static final Running RUNNING = new Running();
    /** Blocks around the cell a dropped box is looked for in. */
    private static final double DROP_RANGE = 8;
    /** Vanilla's default slipperiness ({@code AbstractBlock.Settings}): a floor a drop stays on (ruling R56). */
    private static final float DEFAULT_SLIPPERINESS = 0.6f;

    private final RestockSession session;
    private final MinecraftClient mc;
    private final Mover mover;
    private final UnpackPlan plan;
    private final BorrowedShulkers.Kind kind;
    private final StateFacts facts;
    private final RestockLimits limits = RestockLimits.DEFAULTS;
    private final Sender sender;
    private final Map<String, Integer> before;
    private Aim.Rotation wanted;
    private BlockHitResult hit;
    private UnpackPlan.Click click = UnpackPlan.Click.NONE;
    private TripDriver inner;
    private TripDriver innerDone;
    private UnpackPlan.Contents contents = UnpackPlan.Contents.RUNNING;
    private RestockReason contentsStop;
    private volatile boolean actingAtRequest;
    /** I1: a place went out and no box has been seen standing since (the server has not answered yet). */
    private boolean placeUnseen;
    /** M17: the dig's STOP, or an instant START, went out; the plan still waits in DUG for the cell to empty. */
    private boolean stopSent;

    UnpackDriver(RestockSession session, MinecraftClient mc, Mover mover, UnpackPlan plan, BorrowedShulkers.Kind kind,
                 StateFacts facts) {
        this.session = session;
        this.mc = mc;
        this.mover = mover;
        this.plan = plan;
        this.kind = kind;
        this.facts = facts;
        this.sender = new Sender(mc);
        this.before = StateFacts.carried(mc.player.getInventory());
    }

    UnpackPlan.Phase phase() {
        return plan.phase();
    }

    String material() {
        return plan.material();
    }

    boolean outside() {
        return plan.outside();
    }

    boolean digging() {
        return plan.digging();
    }

    boolean clicking() {
        TripDriver t = inner;
        return t != null ? t.clicking() : plan.clicking();
    }

    boolean actingAtRequest() {
        TripDriver t = inner;
        return t != null ? t.actingAtRequest() : actingAtRequest;
    }

    /** At the start of every session tick (pre-flight 19-4). */
    void newTick() {
        if (inner != null) inner.newTick();
        if (innerDone != null) innerDone.newTick();
    }

    void drain(RestockReason reason) {
        plan.drain(reason);
    }

    Result tick(boolean paused) {
        ClientPlayerEntity p = mc.player;
        boolean acted = false;
        TripDriver t = inner;
        if (t != null) {
            if (plan.draining().isPresent()) t.hurry();
            TripDriver.Result r = t.tick(paused);
            acted = t.actedThisTick();
            if (r instanceof TripDriver.Finished) {
                contents = UnpackPlan.Contents.DONE;
                inner = null;
                innerDone = t;
            } else if (r instanceof TripDriver.Ended e) {
                contents = UnpackPlan.Contents.FAILED;
                contentsStop = e.reason();
                inner = null;
                innerDone = t;
            }
        } else if (innerDone != null && !plan.digging()) {
            // M2: no close in a tick a dig under way swings, stops or aborts in; a screen that opens mid-dig makes the
            // core let go (screenFree false), and the close follows once the dig is let go.
            acted = innerDone.lateTick(paused);
        }
        UnpackPlan.Facts f = facts(p, paused, acted);
        click = UnpackPlan.Click.NONE;
        return execute(plan.step(f), p);
    }

    /** Owner ruling R42, at once: the core's stop with the one action it allows. Tick path only (TickEvent.Pre). */
    Ended halt(RestockReason reason, boolean paused) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || plan.over()) return new Ended(reason, "");
        TripDriver t = inner;
        TripDriver done = innerDone;
        boolean acted = (t != null && t.actedThisTick()) || (done != null && done.actedThisTick());
        Result r = execute(plan.halt(facts(p, paused, acted), reason), p);
        return r instanceof Ended e ? e : new Ended(reason, "");
    }

    /** From {@code SendMovementPacketsEvent.Pre} at {@code HIGH}: request the aim only when nobody else is rotating. */
    void requestRotation() {
        TripDriver t = inner;
        if (t != null) {
            t.requestRotation();
            return;
        }
        List<?> queue = RotationQueue.read();
        actingAtRequest = queue != null && !queue.isEmpty();
        Aim.Rotation r = wanted;
        if (r == null || queue == null || actingAtRequest) return;
        if (plan.phase() != UnpackPlan.Phase.PLACE && plan.phase() != UnpackPlan.Phase.DIG) return;
        Rotations.rotate(r.yaw(), r.pitch(), PrinterLimits.DEFAULTS.rotationPriority(), null);
    }

    /** The session ends: the walk stops; no packet is sent here, from the tick or not (pre-flight 19-19). */
    void abort() {
        mover.cancel();
        wanted = null;
        TripDriver t = inner;
        if (t != null) t.abort();
    }

    /**
     * Deferred L59 for the take: restock's own box screen closed as any close would be (never while leaving: the
     * caller's rule). Once the inner take has ended, its own screen if a take cut short left it open, else the late
     * answer to its click (M3), under the same guards.
     */
    void closeOwnScreen() {
        TripDriver t = inner;
        if (t != null) {
            t.closeOwnScreen();
            return;
        }
        TripDriver done = innerDone;
        if (done != null) done.closeOwnOrLateScreen();
    }

    /**
     * What is left out at the build, from the world: the boxes standing on any cell tried — "or just broken" while the
     * dig's STOP waits for the server (M17) —, the broken one on the ground or gone, and "could not check" (I1) when a
     * place went out and no box has been seen since (with no client prediction neither the block nor the inventory
     * changes before the server answers), or when no box stands, none was broken and fewer boxes of this kind are
     * carried than when the unpack began. A broken box that is neither seen on the ground nor missing from the count
     * came back in the very tick of the stop, before the core saw it: it is not said to be gone.
     */
    ShulkersLeft left() {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return ShulkersLeft.UNCHECKED;
        Point at = feet(p);
        int standing = 0;
        long nearest = -1;
        for (Pos t : plan.tried()) {
            if (!holdsBox(t)) continue;
            standing++;
            long d = Math.round(Math.sqrt(t.distanceSq(at)));
            nearest = nearest < 0 ? d : Math.min(nearest, d);
        }
        int carriedNow = ShulkerInventory.kinds(p.getInventory()).getOrDefault(kind, 0);
        boolean fewer = carriedNow < plan.plan().carried();
        long onGround = -1;
        boolean lost = false;
        if (plan.dropped()) {
            Optional<ItemEntity> e = dropEntity();
            if (e.isPresent()) onGround = Math.round(e.get().getEntityPos().distanceTo(p.getEntityPos()));
            else lost = fewer;
        }
        boolean unchecked = placeUnseen || (standing == 0 && !plan.dropped() && fewer);
        return new ShulkersLeft(standing, nearest, onGround, lost, unchecked, 0, standing > 0 && stopSent);
    }

    private UnpackPlan.Facts facts(ClientPlayerEntity p, boolean paused, boolean acted) {
        PlayerInventory inv = p.getInventory();
        boolean screenFree = TripDriver.screenFree(mc) && !acted;
        boolean slotOk = PacketWatch.get().slotChangeAllowed() && !acted;
        HotbarPlan.Step hotbar = HotbarPlan.forMaterial(hotbarSlots(inv), inv.getSelectedSlot(), MARK);
        Vec3d e = p.getEyePos();
        Point eye = new Point(e.x, e.y, e.z);
        PlacePlanner.RayOracle oracle = (block, side, r) -> WorldRay.ray(mc, block, side, r, limits.reach()) != null;
        Optional<Pos> standing = standing();
        // What left() reports after a stop, kept up to date every tick (halt measures too): I1 and M17.
        if (standing.isPresent() || plan.phase() != UnpackPlan.Phase.PLACED) placeUnseen = false;
        if (plan.phase() != UnpackPlan.Phase.DUG) stopSent = false;
        Optional<ShulkerSpot.Choice> spot = Optional.empty();
        Optional<UnpackPlan.DigAim> digAim = Optional.empty();
        Optional<BreakPlan.Choice> tool = Optional.empty();
        Optional<Pos> drop = Optional.empty();
        boolean aimHeld = false;
        float vanilla = 0f;
        boolean arrived = false;
        double distance = Double.NaN;
        switch (plan.phase()) {
            case PLACE -> {
                // M6: with no build counted there is no spot at all.
                spot = session.boxes().flatMap(boxes -> ShulkerSpot.choose(boxes, feet(p), eye, p.getYaw(),
                    plan.tried(), limits.reach(), limits.hitMargin(), spotWorld(), oracle));
                aimHeld = spot.isPresent() && held(spot.get().support(), Face.UP, spot.get().rotation());
            }
            case DIG -> {
                Pos cell = plan.cell().orElseThrow();
                if (standing.equals(Optional.of(cell))) {
                    BlockPos c = WorldRay.block(cell);
                    Optional<ContainerAim.Aiming> a = ContainerAim.choose(cell, eye, p.getYaw(), limits.reach(),
                        limits.hitMargin(), oracle);
                    if (a.isPresent()) {
                        digAim = Optional.of(new UnpackPlan.DigAim(a.get().side(), a.get().rotation()));
                        aimHeld = !plan.digging() && held(cell, a.get().side(), a.get().rotation());
                    }
                    tool = DigFacts.bestTool(p, mc.world, c);
                    vanilla = mc.world.getBlockState(c).calcBlockBreakingDelta(p, mc.world, c);
                }
            }
            case PICK_UP -> drop = dropEntity().map(i -> WorldRay.pos(i.getBlockPos()));
            case RETURN -> {
                BlockPos goal = WorldRay.block(plan.plan().resume());
                distance = Vec3d.ofBottomCenter(goal).distanceTo(p.getEntityPos());
                arrived = p.getBlockPos().equals(goal) && p.isOnGround();
            }
            default -> {
            }
        }
        int carried = ShulkerInventory.kinds(inv).getOrDefault(kind, 0);
        return new UnpackPlan.Facts(TripDriver.movementKeys(mc.options), paused, TripDriver.still(p, mover), screenFree,
            slotOk, hotbar, inv.getSelectedSlot(), spot, aimHeld, click, standing, contents,
            Optional.ofNullable(contentsStop), tool, digAim, vanilla, carried, drop, arrived, distance);
    }

    /** The server holds the rotation asked for, and the ray with it sees {@code block}'s {@code side}: the hit. */
    private boolean held(Pos block, Face side, Aim.Rotation r) {
        hit = null;
        if (!r.equals(wanted) || !PacketWatch.get().aimHeld(r)) return false;
        hit = WorldRay.ray(mc, block, side, r, limits.reach());
        return hit != null;
    }

    /** The cell the box was set on while a box stands there, else the first other tried cell one stands on (18-4). */
    private Optional<Pos> standing() {
        Optional<Pos> cell = plan.cell();
        if (cell.isPresent() && holdsBox(cell.get())) return cell;
        for (Pos t : plan.tried()) {
            if (holdsBox(t)) return Optional.of(t);
        }
        return Optional.empty();
    }

    private boolean holdsBox(Pos pos) {
        ClientWorld w = mc.world;
        BlockPos b = WorldRay.block(pos);
        return w != null && w.isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)
            && w.getBlockState(b).getBlock() instanceof ShulkerBoxBlock;
    }

    /**
     * The nearest dropped box of this unpack's kind (item and custom name, M4) within {@value #DROP_RANGE} blocks of
     * the cell: another box of that colour lying near is never taken for it.
     */
    private Optional<ItemEntity> dropEntity() {
        Optional<Pos> cell = plan.cell();
        if (cell.isEmpty() || mc.world == null) return Optional.empty();
        BlockPos c = WorldRay.block(cell.get());
        Vec3d centre = Vec3d.ofCenter(c);
        return mc.world.getEntitiesByClass(ItemEntity.class, new Box(c).expand(DROP_RANGE),
                i -> i.isAlive() && ShulkerInventory.isBox(i.getStack())
                    && ShulkerInventory.kind(i.getStack()).equals(kind))
            .stream().min(Comparator.comparingDouble(i -> i.getEntityPos().squaredDistanceTo(centre)));
    }

    /** The 36 slots for HotbarPlan: this unpack's boxes — its kind, holding the material — under one name. */
    private List<HotbarPlan.Slot> hotbarSlots(PlayerInventory inv) {
        List<HotbarPlan.Slot> slots = new ArrayList<>(PlayerInventory.MAIN_SIZE);
        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) slots.add(HotbarPlan.Slot.EMPTY);
            else if (ours(stack)) slots.add(new HotbarPlan.Slot(MARK, 1));
            else slots.add(new HotbarPlan.Slot(StateFacts.itemId(stack), stack.getCount()));
        }
        return slots;
    }

    private boolean ours(ItemStack stack) {
        return ShulkerInventory.isBox(stack) && ShulkerInventory.kind(stack).equals(kind)
            && ShulkerInventory.contents(stack).getOrDefault(plan.material(), 0) > 0;
    }

    /**
     * {@link ShulkerSpot.World} over the client's world, each method as the committed Javadoc there says (rulings R27,
     * R51, R56–R59). {@code empty} is asked only about the box's own cell and its lid, so it includes entities
     * ({@code canPlace}: a living entity there refuses the cell, as the server would); {@code dropPasses} ignores
     * them — the player stands in one of the neighbour columns, and {@code canPlace} is false wherever a living entity
     * is.
     */
    private ShulkerSpot.World spotWorld() {
        ClientWorld w = mc.world;
        BlockState box = Blocks.SHULKER_BOX.getDefaultState();
        return new ShulkerSpot.World() {
            /** Inside the world's height too: a box cannot be placed above the build limit or below the floor. */
            @Override
            public boolean empty(Pos cell) {
                BlockPos b = WorldRay.block(cell);
                if (!loaded(w, b) || w.isOutOfHeightLimit(b)) return false;
                BlockState s = w.getBlockState(b);
                return s.isReplaceable() && s.getCollisionShape(w, b).isEmpty() && w.getFluidState(b).isEmpty()
                    && w.canPlace(box, b, ShapeContext.absent());
            }

            @Override
            public boolean support(Pos block) {
                BlockPos b = WorldRay.block(block);
                if (!loaded(w, b)) return false;
                BlockState s = w.getBlockState(b);
                return ShulkerSpot.support(facts.of(s), s.isSideSolidFullSquare(w, b, Direction.UP));
            }

            @Override
            public boolean safe(Pos block) {
                BlockPos b = WorldRay.block(block);
                return loaded(w, b) && ShulkerSpot.safe(facts.of(w.getBlockState(b)));
            }

            /**
             * Rulings R57, R59: an empty collision shape and an empty fluid state; entities and replaceability are
             * not asked (an open fence gate passes, a closed one does not).
             */
            @Override
            public boolean dropPasses(Pos cell) {
                BlockPos b = WorldRay.block(cell);
                if (!loaded(w, b)) return false;
                BlockState s = w.getBlockState(b);
                return s.getCollisionShape(w, b).isEmpty() && s.getFluidState().isEmpty();
            }

            /**
             * Rulings R58, R59: a full cube ({@code isFullCube}: not a fence, pane, wall, chain, door or scaffolding)
             * with the default slipperiness — an icy full cube is neither a wall nor a passage.
             */
            @Override
            public boolean stopsDrop(Pos cell) {
                BlockPos b = WorldRay.block(cell);
                if (!loaded(w, b)) return false;
                BlockState s = w.getBlockState(b);
                return s.isFullCube(w, b) && s.getBlock().getSlipperiness() == DEFAULT_SLIPPERINESS;
            }

            /**
             * Rulings R56, R59: {@code isSideSolidFullSquare(UP)} and the default slipperiness (no ice, packed,
             * frosted or blue ice, no slime); not air or the void below the world, no fluid, safe. Asked for the
             * box's own support too.
             */
            @Override
            public boolean floor(Pos block) {
                BlockPos b = WorldRay.block(block);
                if (!loaded(w, b) || w.isOutOfHeightLimit(b)) return false;
                BlockState s = w.getBlockState(b);
                return !s.isAir() && s.isSideSolidFullSquare(w, b, Direction.UP)
                    && s.getBlock().getSlipperiness() == DEFAULT_SLIPPERINESS && s.getFluidState().isEmpty()
                    && ShulkerSpot.safe(facts.of(s));
            }
        };
    }

    private static boolean loaded(ClientWorld w, BlockPos b) {
        return w.isChunkLoaded(b.getX() >> 4, b.getZ() >> 4);
    }

    private Result execute(UnpackPlan.Action action, ClientPlayerEntity p) {
        switch (action) {
            case UnpackPlan.Wait w -> {
            }
            case UnpackPlan.Select s -> sender.select(s.slot());
            case UnpackPlan.MoveToHotbar m -> {
                // Owner ruling R43, re-checked right before the click; otherwise nothing is sent (the core allows one).
                if (movable(p, m.screenSlot())) sender.moveToHotbar(m.screenSlot());
            }
            case UnpackPlan.AimAt a -> wanted = a.rotation();
            case UnpackPlan.Place pl -> place(pl, p);
            case UnpackPlan.OpenContents o -> open(o, p);
            case UnpackPlan.DigStart d -> digStart(d);
            case UnpackPlan.Swing s -> sender.swing();
            case UnpackPlan.DigStop d -> {
                sender.digStop(WorldRay.block(d.cell()), WorldRay.direction(d.side()));
                stopSent = true;
                wanted = null;
            }
            case UnpackPlan.DigAbort d -> sender.digAbort(WorldRay.block(d.cell()), WorldRay.direction(d.side()));
            case UnpackPlan.GoTo g -> {
                if (!mover.goTo(WorldRay.block(g.feet()))) return new Ended(RestockReason.BARITONE_NOT_LISTENING, "");
            }
            case UnpackPlan.StopWalking s -> mover.cancel();
            case UnpackPlan.Finish f -> {
                mover.cancel();
                wanted = null;
                return new Done(gained(p), left());
            }
            case UnpackPlan.Stopped st -> {
                mover.cancel();
                wanted = null;
                st.abort().ifPresent(a -> sender.digAbort(WorldRay.block(a.cell()), WorldRay.direction(a.side())));
                st.select().ifPresent(sender::select);
                return new Ended(st.reason(),
                    st.reason() == RestockReason.NOTHING_FITS ? RestockMessages.itemName(plan.material()) : "");
            }
        }
        return RUNNING;
    }

    /**
     * Ruling R27 for the place, right before the packet: the support (a floor too, R56), the cell and its lid (no
     * living entity in the cell: the server's own check), the box in hand, the ray. The landing area was judged by
     * {@code ShulkerSpot.choose} on this same tick's facts — the Place comes from this tick's step — so the world it
     * read is the world now.
     */
    private void place(UnpackPlan.Place pl, ClientPlayerEntity p) {
        BlockHitResult h = hit;
        wanted = null;
        hit = null;
        ShulkerSpot.World w = spotWorld();
        ShulkerSpot.Choice s = pl.spot();
        boolean ok = h != null && h.getBlockPos().equals(WorldRay.block(s.support())) && h.getSide() == Direction.UP
            && w.support(s.support()) && w.floor(s.support()) && w.empty(s.cell())
            && w.empty(s.cell().offset(Face.UP)) && ours(p.getMainHandStack());
        if (!ok) {
            click = UnpackPlan.Click.WITHHELD;
            return;
        }
        long sent = PacketWatch.get().oursSent();
        sender.place(h);
        click = PacketWatch.get().oursSent() == sent + 1 ? UnpackPlan.Click.SENT : UnpackPlan.Click.REFUSED;
        if (click == UnpackPlan.Click.SENT) placeUnseen = true;
    }

    /** Only the cell restock set its box on, while a box stands there, right before START. */
    private void digStart(UnpackPlan.DigStart d) {
        if (!holdsBox(d.cell())) {
            click = UnpackPlan.Click.WITHHELD;
            return;
        }
        long sent = PacketWatch.get().oursSent();
        sender.digStart(WorldRay.block(d.cell()), WorldRay.direction(d.side()), d.instant());
        click = PacketWatch.get().oursSent() == sent + 1 ? UnpackPlan.Click.SENT : UnpackPlan.Click.REFUSED;
        // An instant START breaks the box at once (M17: the plan then waits in DUG as after a STOP).
        if (d.instant() && click == UnpackPlan.Click.SENT) stopSent = true;
    }

    private void open(UnpackPlan.OpenContents o, ClientPlayerEntity p) {
        contents = UnpackPlan.Contents.RUNNING;
        contentsStop = null;
        Pos here = WorldRay.pos(p.getBlockPos());
        RestockTrip core = new RestockTrip(
            new RestockTrip.Plan(plan.material(), o.cell(), Optional.of(here), here, false), limits);
        inner = new TripDriver(session, mc, mover, core, limits, StateFacts.withShulkers(p.getInventory()), true);
    }

    /**
     * Owner ruling R43, right before the one click: no screen, nothing on the cursor, a main-inventory slot holding
     * exactly this unpack's box, and a free hotbar slot for vanilla to move it to.
     */
    private boolean movable(ClientPlayerEntity p, int slot) {
        if (slot < PlayerInventory.HOTBAR_SIZE || slot >= PlayerInventory.MAIN_SIZE) return false;
        if (mc.currentScreen != null || p.currentScreenHandler != p.playerScreenHandler) return false;
        if (!p.playerScreenHandler.getCursorStack().isEmpty()) return false;
        if (!ours(p.getInventory().getStack(slot))) return false;
        for (int i = 0; i < PlayerInventory.HOTBAR_SIZE; i++) {
            if (p.getInventory().getStack(i).isEmpty()) return true;
        }
        return false;
    }

    private Map<String, Integer> gained(ClientPlayerEntity p) {
        Map<String, Integer> m = new TreeMap<>();
        StateFacts.carried(p.getInventory()).forEach((item, n) -> {
            int d = n - before.getOrDefault(item, 0);
            if (d > 0) m.put(item, d);
        });
        return m;
    }

    private static Point feet(ClientPlayerEntity p) {
        return new Point(p.getX(), p.getY(), p.getZ());
    }
}
