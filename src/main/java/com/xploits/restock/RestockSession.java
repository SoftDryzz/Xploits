package com.xploits.restock;

import com.xploits.XploitsAddon;
import com.xploits.printer.core.BuildIndex;
import com.xploits.printer.core.GridBox;
import com.xploits.printer.core.Guards;
import com.xploits.printer.core.Point;
import com.xploits.printer.core.Pos;
import com.xploits.printer.core.PrinterLimits;
import com.xploits.printer.core.Target;
import com.xploits.pvp.AutoPvp;
import com.xploits.pvp.core.CombatState;
import com.xploits.restock.core.BorrowedShulkers;
import com.xploits.restock.core.MarkBook;
import com.xploits.restock.core.PartlyPlaced;
import com.xploits.restock.core.PlacedExtra;
import com.xploits.restock.core.PrintPause;
import com.xploits.restock.core.ReadyToLeave;
import com.xploits.restock.core.RestockLimits;
import com.xploits.restock.core.RestockMessages;
import com.xploits.restock.core.RestockNeeds;
import com.xploits.restock.core.RestockReason;
import com.xploits.restock.core.RestockSettings;
import com.xploits.restock.core.RestockText;
import com.xploits.restock.core.RestockTrip;
import com.xploits.restock.core.RunOut;
import com.xploits.restock.core.ShulkersLeft;
import com.xploits.restock.core.Source;
import com.xploits.restock.core.SourceChooser;
import com.xploits.restock.core.TakePlan;
import com.xploits.restock.core.UnpackChoice;
import com.xploits.restock.core.UnpackLimits;
import com.xploits.restock.core.UnpackPlan;
import com.xploits.restock.core.UnpackStops;
import com.xploits.shared.core.i18n.Msg;
import com.xploits.stash.StashKeeper;
import com.xploits.stash.core.StashIndex;
import com.xploits.stash.core.StashStore;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.AutoEat;
import meteordevelopment.meteorclient.systems.modules.player.AutoGap;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.world.TickRate;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * One restock session, from enable to stop (restock spec §3): it counts the selected placement, keeps the guards, says
 * when a material is due, unpacks a carried shulker box that holds it (owner ruling R44) or chooses its source and
 * drives the trip — which may carry a whole box back, unpacked at the build before the printer goes on. It keeps the
 * ledger of the boxes it borrowed, and gives an empty one back on a later visit or one last trip once the build is
 * done. Positions stay in memory. Client thread only.
 */
final class RestockSession {
    sealed interface Outcome permits Going, Paused, Stopped {
    }

    record Going() implements Outcome {
    }

    record Paused(RestockReason reason, String detail) implements Outcome {
    }

    record Stopped(RestockReason reason, String detail) implements Outcome {
    }

    /** What arrived on the Netty thread since the last tick. */
    record Received(List<BlockPos> changed, List<EntityDamageS2CPacket> damage, boolean setback) {
        /** Counts only (deferred L6): the changed blocks are positions, and a damage packet may carry its source's. */
        @Override
        public String toString() {
            return "Received[changed=" + changed.size() + ", damage=" + damage.size() + ", setback=" + setback + "]";
        }
    }

    /**
     * What ending the session did, for the module to say once all of it is done (ruling R33): {@code restored}, Baritone
     * took every command that gives its values back; {@code printerLeftPaused}, restock held litematica-printer off and
     * leaves it so; {@code left}, what is left out at the build and how many borrowed boxes are carried (phase B).
     */
    record Closed(boolean restored, boolean printerLeftPaused, ShulkersLeft left) {
    }

    private static final Going GOING = new Going();
    /** A placement that cannot be counted is tried again this often. */
    private static final int RETRY_TICKS = 20;
    /** Materials found nowhere are looked for again, silently, this often (a container refilled by hand). */
    private static final int NOWHERE_RETRY_TICKS = 1200;

    private final Restock module;
    private final MinecraftClient mc;
    private final Supplier<TargetSource> opener;
    private final Mover mover;
    private final PrintSwitch printer;
    private final RestockLimits limits = RestockLimits.DEFAULTS;
    /** A new Guards per session: its stop latch must not outlive the session (the printer's Task 9 review). */
    private final Guards guards = new Guards(PrinterLimits.DEFAULTS);
    private final StateFacts facts = new StateFacts();
    private final Sources sources = new Sources();
    /**
     * Remembers the index's pass count when each material ran out, so it lives exactly as long as {@link #index}: made
     * again whenever the index is rebuilt or dropped (ruling R26) — a new index counts its passes from 0 again.
     */
    private RunOut runOut = new RunOut(limits.duePasses());
    /** Items beyond one that matching positions already hold (ruling R6): kept beside {@link #index}, cleared with it. */
    private final PlacedExtra extra = new PlacedExtra();
    /** Positions holding fewer items than the build wants there (deferred m1): kept beside {@link #index}, cleared with it. */
    private final PartlyPlaced partly = new PartlyPlaced();
    /** Deferred m2: the player has stood ready to leave for {@code leaveTicks} ticks in a row; fed every session tick. */
    private final ReadyToLeave leave = new ReadyToLeave(limits.leaveTicks());
    private final RegistryKey<World> dimension;
    private final Map<String, Set<Pos>> stale = new HashMap<>();
    private final Set<Pos> unusable = new HashSet<>();
    private final Set<String> saidNowhere = new HashSet<>();
    /** The boxes restock carried away from a container this session (owner ruling R44). */
    private final BorrowedShulkers borrowed = new BorrowedShulkers();
    /** The trip under way carried a box: its later takes keep a slot free for the unpack (pre-flight 19-6). */
    private boolean carriedThisTrip;
    /** A trip brought a box that holds a material still out: unpacked here once the player stands ready (R32, m2). */
    private String pendingUnpack;
    /** The trip under way only gives borrowed boxes back (the build is done); the ledger's size when it left. */
    private boolean lastTrip;
    private int ledgerAtTripStart;
    /** Containers a last trip already went to this session: one each, so a full one is never a loop. */
    private final Set<Pos> lastTripTried = new HashSet<>();
    /** What restock's last container visit saw (ruling R54 reads it when that container fails). */
    private Pos lastSeenAt;
    private Map<String, Integer> lastSeenLoose = Map.of();
    private Map<String, Integer> lastSeenNested = Map.of();
    /** Ruling R70: the shulker box moves of the container visit under way, settled at its close. */
    private BorrowedShulkers.Visit visit;
    /** The trip under way passed over a container that held its material only inside boxes (ruling R54). */
    private boolean passedOverBoxes;
    private RestockSettings settings = RestockSettings.DEFAULTS;
    private TargetSource source;
    private BuildIndex index;
    private Map<String, Long> totals = Map.of();
    private RestockReason notCounted;
    private String notCountedDetail = "";
    private boolean recountPending;
    private TripDriver trip;
    private UnpackDriver unpack;
    /**
     * Owner ruling R42: the guard's stop the unpack under way finishes the break and the pick-up for, and its detail.
     */
    private RestockReason draining;
    private String drainDetail = "";
    /**
     * Materials whose unpack gave nothing this session (Review Focus 3): they go to the containers until carried again.
     */
    private final Set<String> gaveUp = new HashSet<>();
    /** Materials a carried box holds only in the main inventory with the hotbar full: said once. */
    private final Set<String> saidNoHotbarRoom = new HashSet<>();
    /**
     * Restock switched litematica-printer off and has not given it back: from the switch (before the trip is even
     * built, so a failure in between is still said and its marker deleted) to the return or the stop.
     */
    private boolean printerHeld;
    private StashIndex savedStash;
    private List<String> lastDue = List.of();
    private int lastMarks;
    private int lastStashSize;
    private long tick;

    RestockSession(Restock module, MinecraftClient mc, Supplier<TargetSource> opener, TargetSource first, Mover mover,
                   PrintSwitch printer) {
        this.module = module;
        this.mc = mc;
        this.opener = opener;
        this.mover = mover;
        this.printer = printer;
        this.dimension = mc.world.getRegistryKey();
        this.lastMarks = MarkStore.version();
        this.lastStashSize = stashKeeperSize();
        count(first);
    }

    // --- what the module and the bench read ---------------------------------------------------------------------

    boolean inTrip() {
        return trip != null || unpack != null || pendingUnpack != null;
    }

    Optional<RestockTrip.Phase> tripPhase() {
        return trip == null ? Optional.empty() : Optional.of(trip.phase());
    }

    Optional<UnpackPlan.Phase> unpackPhase() {
        return unpack == null ? Optional.empty() : Optional.of(unpack.phase());
    }

    boolean unpackDigging() {
        return unpack != null && unpack.digging();
    }

    /** An unpack finishes its break and pick-up toward a guard's stop (owner ruling R42): restock does not carry on. */
    boolean draining() {
        return draining != null;
    }

    /** Borrowed boxes the player carries now: never more than the ledger names, nor than are carried. */
    int borrowedCarried() {
        ClientPlayerEntity p = mc.player;
        return p == null ? 0 : borrowed.carried(ShulkerInventory.kinds(p.getInventory()));
    }

    /** No trip, no unpack and none waiting to start: a recount may happen now, and the ledger may be trimmed. */
    private boolean idle() {
        return trip == null && unpack == null && pendingUnpack == null;
    }

    Optional<BuildIndex.Counts> counts() {
        return index == null ? Optional.empty() : Optional.of(index.counts());
    }

    String prefix() {
        return mover.prefix();
    }

    int markCount() {
        return marks().size();
    }

    /** The materials said to be nowhere in this session (the bench reads it). */
    Set<String> nowhere() {
        return Set.copyOf(saidNowhere);
    }

    /** How many containers were found unusable in this session (the bench reads it; never which ones). */
    int unusableCount() {
        return unusable.size();
    }

    int stashCount(RestockSettings s) {
        StashIndex st = stash(s);
        if (st == null) return 0;
        return (int) sources.list(dimensionId(), List.of(), st).stream().filter(x -> x.kind() == Source.Kind.STASH).count();
    }

    RestockText activity(boolean paused) {
        if (paused) return RestockText.ACTIVITY_PAUSED;
        if (unpack != null || pendingUnpack != null) return RestockText.ACTIVITY_UNPACKING;
        if (trip == null) {
            return index != null && index.passes() >= 1 ? RestockText.ACTIVITY_WATCHING : RestockText.ACTIVITY_SCANNING;
        }
        return switch (trip.phase()) {
            case PAUSING -> RestockText.ACTIVITY_PAUSING;
            case APPROACH, TRAVEL, CHOOSING -> RestockText.ACTIVITY_WALKING;
            case OPEN, WAIT_SCREEN -> RestockText.ACTIVITY_OPENING;
            case TAKE, STOPPING -> RestockText.ACTIVITY_TAKING;
            case RETURN, DONE, STOPPED -> RestockText.ACTIVITY_RETURNING;
        };
    }

    Msg status() {
        if (index == null) {
            return Msg.of(RestockText.STATUS_NO_PLACEMENT, "reason",
                module.reasonText(notCounted == null ? RestockReason.NO_PLACEMENT : notCounted, notCountedDetail));
        }
        if (unpack != null || pendingUnpack != null) {
            return Msg.of(RestockText.STATUS_TRIP, "activity", Msg.of(RestockText.ACTIVITY_UNPACKING),
                "material", RestockMessages.itemName(unpack != null ? unpack.material() : pendingUnpack));
        }
        if (trip != null) {
            return Msg.of(RestockText.STATUS_TRIP, "activity", Msg.of(activity(false)),
                "material", RestockMessages.itemName(trip.material()));
        }
        if (index.passes() < 1) return Msg.of(RestockText.STATUS_SCANNING);
        ClientPlayerEntity p = mc.player;
        Map<String, Integer> carried = p == null ? Map.of() : StateFacts.carried(p.getInventory());
        return Msg.of(RestockText.STATUS_IDLE, "remaining", remaining(), "short", RestockMessages.orNone(
            RestockMessages.materials(RestockNeeds.need(totals, index.placed(), extra.byMaterial(), carried))));
    }

    /** Items of the build still to place, as {@link #status} counts them (rulings R6, m1); unknown positions count. */
    private long remaining() {
        return RestockNeeds.remaining(totals, index.placed(), extra.byMaterial());
    }

    // --- the tick ------------------------------------------------------------------------------------------------

    Outcome tick(Received in, RestockSettings s) {
        tick++;
        settings = s;
        if (trip != null) trip.newTick();
        if (unpack != null) unpack.newTick();
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return new Stopped(RestockReason.NO_WORLD, "");
        Guards.Inputs inputs = guardInputs(in, s, p);
        Guards.Verdict verdict = guards.tick(inputs);
        if (verdict instanceof Guards.Stop stop) {
            Optional<Stopped> now = guardStop(RestockReason.of(stop.reason()), stop.detail(), inputs);
            if (now.isPresent()) return now.get();
        }
        // While an unpack finishes after a guard's stop (owner ruling R42) the guards answer that stop every tick; the
        // pause and the yield are read from their inputs instead.
        boolean paused = verdict instanceof Guards.Pause
            || (draining != null && UnpackStops.holds(inputs, PrinterLimits.DEFAULTS));
        // Deferred L55: Guards' Run.yielding — another module rotated or acted — blocks every click as a pause does (no
        // click, take, close, place, dig or slot change; no tick counted towards a wait), so restock's action never
        // shares a tick with someone else's. It is only ever true while a trip or an unpack clicks (see guardInputs'
        // acting).
        boolean yielding = verdict instanceof Guards.Run run && run.yielding();
        mover.tick();
        watchSources();
        if (index != null) {
            for (BlockPos b : in.changed()) classify(WorldRay.pos(b));
            if (source.changed()) {
                if (idle()) recount();
                else recountPending = true;
            }
        } else if (idle() && tick % RETRY_TICKS == 0) {
            // A refusing source never reports a change: open it again, so counting resumes once the player fixes it.
            recount();
        }
        if (index != null) {
            for (Pos pos : index.nextToScan(PrinterLimits.DEFAULTS.scanBudget())) classify(pos);
        }
        boolean ready = leave.tick(mayLeave(p));
        if (unpack != null) {
            UnpackDriver.Result r = unpack.tick(paused || yielding);
            if (r instanceof UnpackDriver.Ended end) {
                return new Stopped(end.reason(), end.reason() == draining ? drainDetail : end.detail());
            }
            if (r instanceof UnpackDriver.Done done) unpacked(done, p);
        } else if (trip != null) {
            TripDriver.Result r = trip.tick(paused || yielding);
            if (r instanceof TripDriver.Ended end) return new Stopped(end.reason(), end.detail());
        } else if (pendingUnpack != null) {
            // The unpack a trip brought a box for, the printer still off: a movement key ends it as it would the trip;
            // it starts once the player has stood ready for leaveTicks ticks (pre-flight 19-9).
            if (TripDriver.movementKeys(mc.options)) return new Stopped(RestockReason.PLAYER_MOVED, "");
            if (ready && !paused) {
                Optional<Stopped> stopped = startPendingUnpack(s, p);
                if (stopped.isPresent()) return stopped.get();
            }
        } else if (!paused) {
            Optional<Stopped> stopped = startTripIfDue(s, p, ready);
            if (stopped.isPresent()) return stopped.get();
        }
        // While nothing runs and nothing is on the cursor or in a screen, the ledger forgets the boxes of a kind the
        // player no longer carries as many of, the newest first (pre-flight 19-23: one put away by hand).
        if (idle() && mc.currentScreen == null && p.currentScreenHandler.getCursorStack().isEmpty()) {
            borrowed.trim(ShulkerInventory.kinds(p.getInventory()));
        }
        if (verdict instanceof Guards.Pause pause) return new Paused(RestockReason.of(pause.reason()), pause.detail());
        return GOING;
    }

    /**
     * Owner ruling R42 for the guards' stop ({@link UnpackStops#onGuardStop}). With no unpack it stops now, as in phase
     * A. Halt: the unpack stops now — the dig aborted, or the slot given back — and the box stays where it is, which
     * the stop then says; an at-once reason read in the same tick names the stop (with no detail) and the guards' own
     * stop is said too, after acting (ruling R65). Drain: the break and the pick-up finish first
     * ({@link UnpackDriver#drain}); the guards hold their first stop, so the at-once reasons are read again every tick
     * from their inputs; the unpack then ends with that stop, or halts for an at-once reason read meanwhile (whose stop
     * has no detail; the drained one is said too, M7).
     */
    private Optional<Stopped> guardStop(RestockReason why, String detail, Guards.Inputs inputs) {
        UnpackDriver u = unpack;
        if (u == null) return Optional.of(new Stopped(why, detail));
        return switch (UnpackStops.onGuardStop(why, u.outside(), draining != null, inputs)) {
            case UnpackStops.Carry c -> Optional.empty();
            case UnpackStops.Drain d -> {
                draining = d.reason();
                drainDetail = detail;
                module.drained(d.reason(), detail);
                u.drain(d.reason());
                yield Optional.empty();
            }
            case UnpackStops.Halt h -> {
                u.halt(h.reason(), UnpackStops.holds(inputs, PrinterLimits.DEFAULTS));
                h.also().ifPresent(also -> module.alsoStopping(also, detail));
                yield Optional.of(new Stopped(h.reason(), h.reason() == why ? detail : ""));
            }
        };
    }

    /** From {@code SendMovementPacketsEvent.Pre} at {@code HIGH}. */
    void requestRotation() {
        UnpackDriver u = unpack;
        if (u != null) {
            u.requestRotation();
            return;
        }
        TripDriver t = trip;
        if (t != null) t.requestRotation();
    }

    /**
     * Ends the session (ruling R33): a trip or an unpack under way stops walking and closes restock's container or box
     * screen if it can (an unpack never in a tick a dig packet went out), what is left out at the build is read, the
     * printer marker goes (leaving the world keeps it for the next join) and Baritone's values go back — each step on its
     * own, so one that fails never skips the next. A dig's ABORT is never sent here: only the tick path sends one.
     * Nothing is said here: the module speaks once all of it is done. Never throws.
     */
    Closed close(RestockReason why) {
        TripDriver t = trip;
        trip = null;
        UnpackDriver u = unpack;
        unpack = null;
        draining = null;
        // An unpack a trip brought a box for and that has not started: the box is carried, nothing is out.
        pendingUnpack = null;
        if (t != null) {
            quietly("stopping the walk", t::abort);
            // Deferred L59: a stop during TAKE closes restock's screen as any close would (never while leaving).
            if (why != RestockReason.LEFT) quietly("closing restock's container", t::closeOwnScreen);
        }
        ShulkersLeft[] left = {ShulkersLeft.NONE};
        if (u != null) {
            quietly("stopping the unpacking", u::abort);
            if (why != RestockReason.LEFT) quietly("closing restock's shulker box screen", u::closeOwnScreen);
            // What cannot be looked for is "could not check" once a box went out, and nothing before (ruling R66).
            left[0] = ShulkersLeft.UNCHECKED;
            quietly("looking for the shulker box", () -> {
                left[0] = u.unread();
                left[0] = u.left();
            });
        }
        // Ruling R70: a stop during a container visit settles its box moves before the borrowed boxes are counted.
        quietly("settling the borrowed shulker boxes", this::settleVisit);
        int[] borrowedNow = new int[1];
        quietly("counting the borrowed shulker boxes", () -> borrowedNow[0] = borrowedCarried());
        boolean leftPaused = printerHeld && !PrintPause.keepMarker(why);
        printerHeld = false;
        if (leftPaused) quietly("deleting the printer marker", PrinterMarker::delete);
        boolean[] restored = new boolean[1];
        quietly("giving Baritone its values back", () -> restored[0] = mover.end());
        return new Closed(restored[0], leftPaused, left[0].withBorrowed(borrowedNow[0]));
    }

    /** One step of the stop: a failure is logged by its class name only (its message could carry a position). */
    private static void quietly(String step, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError e) {
            XploitsAddon.LOG.error("restock: {} failed at the stop ({})", step, e.getClass().getName());
        }
    }

    // --- what the trip driver calls ------------------------------------------------------------------------------

    /**
     * What the build still needs beyond what is carried: the due materials in the order they ran out, then the rest. A
     * container trip does not fetch what a box restock would unpack first holds (pre-flight 18-13; the take from a box
     * set down at the build counts loose items only: {@link #innerNeed}).
     */
    Map<String, Long> orderedNeed() {
        ClientPlayerEntity p = mc.player;
        Map<String, Integer> carried = p == null ? Map.of() : UnpackChoice.available(
            ShulkerInventory.choiceInventory(p.getInventory(), borrowed), settings.useCarriedShulkers(), gaveUp);
        Map<String, Long> need = index == null ? Map.of()
            : RestockNeeds.need(totals, index.placed(), extra.byMaterial(), carried);
        Map<String, Long> ordered = new LinkedHashMap<>();
        for (String m : lastDue) {
            if (need.containsKey(m)) ordered.put(m, need.get(m));
        }
        need.forEach(ordered::putIfAbsent);
        return ordered;
    }

    void saw(Pos container, Map<String, Integer> loose, Map<String, Integer> nested) {
        sources.saw(container, loose, nested);
        lastSeenAt = container;
        lastSeenLoose = Map.copyOf(loose);
        lastSeenNested = Map.copyOf(nested);
    }

    /**
     * A container trip carries a whole shulker box that holds what the build needs when no loose stack of it is there
     * (spec §2 phase B; owner ruling R40), only while one can be carried ({@link ContainerScreen#carryRoom}, ruling
     * R54).
     */
    boolean carryOnTrips() {
        return true;
    }

    /** Empty slots a container trip's loose takes keep free: one once it carried a box. */
    int takeReserve() {
        return carriedThisTrip ? 1 : 0;
    }

    /** The items the build places: a filled box of one of these is never carried (pre-flight 17-3). */
    Set<String> buildItems() {
        return Set.copyOf(totals.keySet());
    }

    /**
     * The enabled sub-region boxes of the placement counted now: where a shulker box is never set down. Empty while
     * nothing is counted (M6: it fails closed — with no build known there is no spot at all, never "anywhere").
     */
    Optional<List<GridBox>> boxes() {
        return source == null || index == null ? Optional.empty() : Optional.of(source.boxes());
    }

    /** The cache of block-state facts (the unpack's support and safety checks read it). */
    StateFacts stateFacts() {
        return facts;
    }

    /**
     * For the take from a box set down at the build: what the build needs beyond the loose items only (pre-flight
     * 18-13: another carried box must not make it take nothing), the unpack's material first, then the order things
     * ran out.
     */
    Map<String, Long> innerNeed(String material) {
        ClientPlayerEntity p = mc.player;
        Map<String, Integer> loose = p == null ? Map.of() : StateFacts.carried(p.getInventory());
        Map<String, Long> need = index == null ? Map.of()
            : RestockNeeds.need(totals, index.placed(), extra.byMaterial(), loose);
        Map<String, Long> ordered = new LinkedHashMap<>();
        if (need.containsKey(material)) ordered.put(material, need.get(material));
        for (String m : lastDue) {
            if (need.containsKey(m)) ordered.putIfAbsent(m, need.get(m));
        }
        need.forEach(ordered::putIfAbsent);
        return ordered;
    }

    /** A QUICK_MOVE that moves a box the ledger cares for: one borrowed from the container, or one given back to it. */
    record BoxMove(boolean borrow, BorrowedShulkers.Borrowed entry) {
    }

    /**
     * Right before a QUICK_MOVE of slot {@code slot} of restock's container screen, with nothing sent yet: a box that
     * holds items leaving the container would be borrowed from it, a box going into it given back; an empty box taken
     * as a building block is material, not borrowed. The visit's first box move notes the boxes carried before it
     * (ruling R70). Nothing goes in the ledger here: {@link #moved} notes it once the click's packet left.
     */
    Optional<BoxMove> boxMove(ClientPlayerEntity p, int slot, Pos container) {
        ScreenHandler h = p.currentScreenHandler;
        if (slot < 0 || slot >= h.slots.size()) return Optional.empty();
        ItemStack stack = h.slots.get(slot).getStack();
        if (!ShulkerInventory.isBox(stack)) return Optional.empty();
        boolean borrow = slot < ContainerScreen.containerSlots(h);
        if (borrow && !Utils.hasItems(stack)) return Optional.empty();
        if (visit == null) visit = new BorrowedShulkers.Visit(ShulkerInventory.held(p.getInventory()));
        return Optional.of(new BoxMove(borrow,
            new BorrowedShulkers.Borrowed(ShulkerInventory.kind(stack), dimensionId(), container)));
    }

    /**
     * The click of {@code m} left: the borrow, or the give-back, goes in the ledger at once (the visit's own need
     * counts the box) and in the visit, which its close settles against what the server let go (ruling R70). A
     * container a box is borrowed from may get a last trip again.
     */
    void moved(BoxMove m) {
        BorrowedShulkers.Borrowed b = m.entry();
        if (m.borrow()) {
            borrowed.borrow(b);
            visit.borrowed(b);
            carriedThisTrip = true;
            lastTripTried.remove(b.origin());
        } else if (borrowed.giveBack(b.dimension(), b.origin(), b.kind())) {
            visit.gaveBack(b);
        }
    }

    /**
     * Ruling R70, as restock's container screen closes, and at a stop before the borrowed boxes are counted: the
     * ledger keeps only the carries and give-backs the server let go ({@link BorrowedShulkers#settle}).
     */
    void settleVisit() {
        BorrowedShulkers.Visit v = visit;
        visit = null;
        ClientPlayerEntity p = mc.player;
        if (v != null && p != null) borrowed.settle(v, ShulkerInventory.held(p.getInventory()));
    }

    /** A box this visit carried came back (its click refused): the visit carries nothing more (ruling R70). */
    boolean carriedBoxCameBack(ClientPlayerEntity p) {
        BorrowedShulkers.Visit v = visit;
        return v != null && v.cameBack(ShulkerInventory.held(p.getInventory()));
    }

    /** The empty borrowed boxes in the player's part of restock's open screen that go back into {@code container}. */
    List<TakePlan.Slot> returning(ClientPlayerEntity p, Pos container) {
        ScreenHandler h = p.currentScreenHandler;
        int n = ContainerScreen.containerSlots(h);
        if (n <= 0 || borrowed.isEmpty()) return List.of();
        return borrowed.toReturn(dimensionId(), container, ShulkerInventory.heldInScreen(h, n),
            ContainerScreen.freeSlots(h));
    }

    /**
     * A source failed: noted (stale or only filled: for that material; unusable: for the session) and said, then the
     * next nearest for the same material, measured from where the trip started. Ruling R54 (R60): a container restock
     * just saw holding the material only inside shulker boxes, while no box can be carried, is passed over, not marked
     * stale ({@link SourceChooser#passedOver}) — R40's nested rank skips it until a box can be carried, then chooses it
     * again — and nothing is said for it unless no source is left, when the boxes are said even if another container
     * failed after it. {@code carry}: whether a box could be carried, as the take that failed read it (a failure
     * before any take reads it now). A last trip never retargets and notes nothing (pre-flight 19-14).
     */
    Optional<Source> next(String material, Pos failed, RestockTrip.Failure failure, Pos resume, boolean carry) {
        if (lastTrip) return Optional.empty();
        // The trip's Close tells saw() what it saw before the STALE that follows it: the container that failed.
        boolean seen = failed.equals(lastSeenAt);
        boolean inBoxes = SourceChooser.passedOver(failure, carry, seen ? lastSeenLoose : Map.of(),
            seen ? lastSeenNested : Map.of(), material);
        if (inBoxes) {
            passedOverBoxes = true;
        } else {
            switch (failure) {
                case STALE, FILLED_ONLY -> {
                    stale.computeIfAbsent(material, k -> new HashSet<>()).add(failed);
                    // Deferred L86: a container whose only stacks of it hold items is said as such, not as stale.
                    module.info(failure == RestockTrip.Failure.STALE ? RestockText.TRIP_STALE
                        : RestockText.TRIP_FILLED_ONLY, "material", RestockMessages.itemName(material));
                }
                case UNUSABLE -> {
                    unusable.add(failed);
                    module.info(RestockText.TRIP_UNUSABLE);
                }
            }
        }
        String dim = dimensionId();
        Optional<Source> next = SourceChooser.nearest(sources.list(dim, marks(), stash(settings)), material, dim,
            new Point(resume.x() + 0.5, resume.y(), resume.z() + 0.5), settings.maxDistance(), unusable,
            stale.getOrDefault(material, Set.of()), carry);
        if (next.isEmpty()) {
            module.info(passedOverBoxes ? RestockText.NOWHERE_AFTER_TRIP_BOXES : RestockText.NOWHERE_AFTER_TRIP,
                "material", RestockMessages.itemName(material));
        }
        return next;
    }

    /**
     * Back where the trip started. A box it brought that holds a material still out is unpacked before the printer goes
     * on (owner ruling R44): the printer stays off, and the unpack starts once the player stands ready (pre-flight
     * 19-9). Otherwise the printer is given back if restock paused it and it is still off ({@code resumePrinter}, the
     * trip switched it off, is restock's hold, {@code printerHeld}, which decides). It acts, then speaks, as before. A
     * last trip only says how many boxes went back.
     */
    void finished(boolean resumePrinter, boolean took, String material, Map<String, Integer> before) {
        trip = null;
        module.countTrip();
        ClientPlayerEntity p = mc.player;
        if (lastTrip) {
            lastTrip = false;
            givePrinterBack();
            module.info(RestockText.LAST_TRIP_DONE, "count", Math.max(0, ledgerAtTripStart - borrowed.count()));
            if (recountPending) recount();
            return;
        }
        String unpackNext = null;
        if (p != null) {
            Map<String, Integer> loose = StateFacts.carried(p.getInventory());
            List<String> stillOut = lastDue.stream().filter(m -> loose.getOrDefault(m, 0) <= 0).toList();
            UnpackChoice.Choice c = UnpackChoice.choose(stillOut,
                ShulkerInventory.choiceInventory(p.getInventory(), borrowed), settings.useCarriedShulkers(), gaveUp);
            if (c instanceof UnpackChoice.Unpack u) unpackNext = u.material();
        }
        pendingUnpack = unpackNext;
        if (unpackNext == null) givePrinterBack();
        if (took && p != null) {
            // Both ends measured alike, loose plus inside the boxes carried (pre-flight 19-21).
            Map<String, Integer> now = StateFacts.withShulkers(p.getInventory());
            module.info(RestockText.TRIP_DONE, "taken",
                RestockMessages.orNone(RestockMessages.materials(gained(before, now))));
        } else {
            runOut.nowhere(material);
        }
        if (unpackNext == null && recountPending) recount();
    }

    // --- counting --------------------------------------------------------------------------------------------------

    private void recount() {
        recountPending = false;
        boolean wasCounting = index != null;
        count(opener.get());
        if (index != null && wasCounting) module.info(RestockText.RECOUNT);
    }

    private void count(TargetSource s) {
        source = s;
        Optional<Guards.Refusal> refusal = s.refusal();
        if (refusal.isPresent()) {
            RestockReason why = RestockReason.of(refusal.get().reason());
            if (index != null || why != notCounted) {
                module.warning(Msg.of(RestockText.PLACEMENT_NOT_COUNTED, "reason",
                    module.reasonText(why, refusal.get().detail())));
            }
            index = null;
            extra.clear();
            partly.clear();
            runOut = new RunOut(limits.duePasses());
            totals = Map.of();
            notCounted = why;
            notCountedDetail = refusal.get().detail();
            return;
        }
        index = new BuildIndex(s.boxes());
        extra.clear();
        partly.clear();
        runOut = new RunOut(limits.duePasses());
        totals = RestockNeeds.totals(facts.counted(s.wholeBuild()));
        notCounted = null;
        notCountedDetail = "";
    }

    private void classify(Pos pos) {
        if (!index.contains(pos)) return;
        BlockPos b = WorldRay.block(pos);
        if (!mc.world.isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)) {
            index.set(pos, BuildIndex.Status.UNKNOWN, null);
            extra.set(pos, null, 0);
            partly.set(pos, null);
            return;
        }
        BlockState t = source.target(b);
        Target target = t == null ? Target.UNKNOWN : Target.of(facts.of(t));
        int perBlock = t == null ? 0 : facts.perBlock(t);
        BlockState w = mc.world.getBlockState(b);
        RestockNeeds.Classified c = RestockNeeds.classify(target, perBlock, facts.of(w), facts.perBlock(w));
        index.set(pos, c.status(), c.material());
        extra.set(pos, c.material(), c.extra());
        partly.set(pos, c.partial() ? c.material() : null);
    }

    // --- choosing and starting a trip -----------------------------------------------------------------------------

    /**
     * Rulings R32 and m2: a due trip waits (no stop) until the player has stood for {@code leaveTicks} ticks in a row with
     * no screen open, nothing on the cursor, no movement key held, not sneaking and on the ground.
     */
    private boolean mayLeave(ClientPlayerEntity p) {
        boolean screenFree = mc.currentScreen == null && p.currentScreenHandler.getCursorStack().isEmpty();
        return RestockTrip.mayLeave(screenFree, TripDriver.movementKeys(mc.options), p.isSneaking(), p.isOnGround());
    }

    private Optional<Stopped> startTripIfDue(RestockSettings s, ClientPlayerEntity p, boolean ready) {
        if (index == null) return Optional.empty();
        Map<String, Integer> carried = StateFacts.carried(p.getInventory());
        // A material carried again (loose) may be unpacked again.
        gaveUp.removeIf(m -> carried.getOrDefault(m, 0) > 0);
        Map<String, Long> need = RestockNeeds.need(totals, index.placed(), extra.byMaterial(), carried);
        // Ruling R31: only a material the index knows a missing position of is due — or a partly filled one, one slab
        // where a double goes (deferred m1); the need (how much to take) is whole. Due on the loose items carried: a
        // box that holds it does not stop it running out (pre-flight 18-13).
        List<String> due = runOut.due(tick, need, carried, index.passes(), RestockNeeds.knownMissing(index, partly));
        lastDue = due;
        // Rulings R32 and m2: it waits while the player walks, sneaks or is in the air, and leaves once they have stood
        // for leaveTicks ticks in a row — not on the one tick a key was let go. The last trip too (pre-flight 19-9).
        if (!ready) return Optional.empty();
        if (due.isEmpty()) return lastTripIfDone(s, p);
        // Owner ruling R44: a box the player carries that holds a due material is unpacked here, before any trip.
        UnpackChoice.Choice choice = UnpackChoice.choose(due,
            ShulkerInventory.choiceInventory(p.getInventory(), borrowed), s.useCarriedShulkers(), gaveUp);
        if (choice instanceof UnpackChoice.Unpack u) return startUnpackNow(u, p);
        for (String m : ((UnpackChoice.None) choice).noHotbarRoom()) {
            if (saidNoHotbarRoom.add(m)) {
                module.info(RestockText.NO_HOTBAR_ROOM_HINT, "material", RestockMessages.itemName(m));
            }
        }
        String dim = dimensionId();
        List<Source> list = sources.list(dim, marks(), stash(s));
        Point from = new Point(p.getX(), p.getY(), p.getZ());
        // A source that holds the material only inside boxes is chosen only while a box could be carried (17-4, R40):
        // the same test the trip's take makes (ruling R54), one definition.
        boolean carry = ContainerScreen.carryRoom(p.getInventory()) > 0;
        for (String material : due) {
            Optional<Source> chosen = SourceChooser.nearest(list, material, dim, from, s.maxDistance(), unusable,
                stale.getOrDefault(material, Set.of()), carry);
            if (chosen.isPresent()) return startTrip(material, chosen.get(), p, from);
            runOut.nowhere(material);
            if (saidNowhere.add(material)) {
                // Ruling R54: "free a hotbar slot and one more" only when no other source is left.
                boolean onlyInBoxes = !carry && SourceChooser.nearest(list, material, dim, from, s.maxDistance(),
                    unusable, stale.getOrDefault(material, Set.of()), true).isPresent();
                if (onlyInBoxes) {
                    module.info(RestockText.NOWHERE_HOTBAR_FULL, "material", RestockMessages.itemName(material));
                } else {
                    module.info(RestockText.NOWHERE, "material", RestockMessages.itemName(material), "count",
                        need.get(material), "distance", s.maxDistance());
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Stopped> startTrip(String material, Source chosen, ClientPlayerEntity p, Point from) {
        PrinterOff off = pausePrinter();
        if (off == PrinterOff.FAILED) return Optional.of(new Stopped(RestockReason.LITEMATICA_PRINTER_UNREADABLE, ""));
        boolean paused = off == PrinterOff.SWITCHED;
        carriedThisTrip = false;
        passedOverBoxes = false;
        ledgerAtTripStart = borrowed.count();
        RestockTrip core = new RestockTrip(new RestockTrip.Plan(material, chosen.container(), chosen.stand(),
            WorldRay.pos(p.getBlockPos()), paused), limits);
        trip = new TripDriver(this, mc, mover, core, limits, StateFacts.withShulkers(p.getInventory()));
        if (!lastTrip) {
            module.info(RestockText.TRIP_STARTED, "material", RestockMessages.itemName(material),
                "kind", Msg.of(chosen.kind() == Source.Kind.MARK ? RestockText.KIND_MARK : RestockText.KIND_STASH),
                "distance", Math.round(Math.sqrt(chosen.container().distanceSq(from))));
        }
        return Optional.empty();
    }

    /**
     * Nothing due and nothing of the build left to place (pre-flight 19-11): one last trip to the nearest container a
     * carried empty borrowed box goes back to (spec §2 phase B; owner ruling R44), once per container and session, so a
     * full one is never a loop. It goes to the mark's spot, or approaches a stash-keeper container, like any trip, only
     * within {@code max-distance} as every trip, and never retargets. Its "material" is the box's item: the build needs
     * nothing, so its take only gives back, and then finds that item loose in the container (no stale note).
     */
    private Optional<Stopped> lastTripIfDone(RestockSettings s, ClientPlayerEntity p) {
        if (borrowed.isEmpty() || index.passes() < limits.duePasses() || remaining() > 0) return Optional.empty();
        String dim = dimensionId();
        List<BorrowedShulkers.Held> held = ShulkerInventory.held(p.getInventory());
        Point from = new Point(p.getX(), p.getY(), p.getZ());
        Optional<Pos> origin = borrowed.lastTripOrigin(dim, from, held, lastTripTried);
        // The nearest one: when it is out of reach, every other one is too.
        if (origin.isEmpty() || origin.get().distanceSq(from) > (double) s.maxDistance() * s.maxDistance()) {
            return Optional.empty();
        }
        lastTripTried.add(origin.get());
        List<TakePlan.Slot> back = borrowed.toReturn(dim, origin.get(), held, 1);
        Optional<Pos> stand = marks().stream().filter(m -> m.container().equals(origin.get())).map(MarkBook.Mark::stand)
            .findFirst();
        Source to = stand.map(st -> Source.unknownMark(dim, origin.get(), st))
            .orElseGet(() -> Source.stash(dim, origin.get(), Map.of(), Map.of()));
        lastTrip = true;
        Optional<Stopped> stopped = startTrip(back.get(0).item(), to, p, from);
        if (stopped.isPresent()) {
            lastTrip = false;
        } else {
            module.info(RestockText.LAST_TRIP, "count", back.size());
        }
        return stopped;
    }

    // --- the printer ---------------------------------------------------------------------------------------------

    /** How switching litematica-printer off for a trip or an unpack went. */
    private enum PrinterOff { NOT_PRINTING, SWITCHED, FAILED }

    /**
     * Switches litematica-printer off if it prints, for a trip or an unpack (pre-flight 19-10): the marker first (a
     * crash between the two finds the printer still on, and the join leaves it so), then the switch; restock holds it
     * from now.
     */
    private PrinterOff pausePrinter() {
        if (PrintPause.atTripStart(printer.installed(), printer.printing()) != PrintPause.Action.SWITCH_OFF) {
            return PrinterOff.NOT_PRINTING;
        }
        try {
            PrinterMarker.write();
        } catch (IOException e) {
            module.warning(RestockText.MARKER_WRITE_FAILED);
        }
        if (!printer.set(false)) {
            PrinterMarker.delete();
            return PrinterOff.FAILED;
        }
        printerHeld = true;
        return PrinterOff.SWITCHED;
    }

    /** The printer given back if restock holds it and it is still off (deferred L60 kept); the marker gone. */
    private void givePrinterBack() {
        if (!printerHeld) return;
        Boolean printing = printer.printing();
        RestockText said = null;
        boolean warn = false;
        if (PrintPause.atReturn(true, printing) == PrintPause.Action.SWITCH_ON) {
            warn = !printer.set(true);
            said = warn ? RestockText.PRINTER_LEFT_PAUSED : RestockText.PRINTER_RESUMED;
        } else if (Boolean.TRUE.equals(printing)) {
            said = RestockText.PRINTER_LEFT_ON;
        } else if (PrintPause.unknownAtReturn(true, printing)) {
            // Deferred L60: it may still be off, and nothing else would say so.
            said = RestockText.PRINTER_UNKNOWN_AT_RETURN;
            warn = true;
        }
        PrinterMarker.delete();
        printerHeld = false;
        if (said != null && warn) module.warning(said);
        else if (said != null) module.info(said);
    }

    // --- unpacking a carried shulker box ---------------------------------------------------------------------------

    /**
     * Owner ruling R44: a carried box is unpacked here — with no trip, or the one a trip brought — the printer switched
     * off first if it prints, and then it settles.
     */
    private Optional<Stopped> startUnpackNow(UnpackChoice.Unpack u, ClientPlayerEntity p) {
        PrinterOff off = pausePrinter();
        if (off == PrinterOff.FAILED) return Optional.of(new Stopped(RestockReason.LITEMATICA_PRINTER_UNREADABLE, ""));
        startUnpack(u, p, off == PrinterOff.SWITCHED);
        return Optional.empty();
    }

    /**
     * The unpack a trip brought a box for, once the player stands ready: chosen again now (the inventory may have
     * changed), and only for a material still out (none carried loose); else the printer goes back on. It starts as a
     * carried box's unpack does: the printer is switched off again — and settles — only if it prints again by now (the
     * player may have switched it on while a screen kept the unpack waiting; review Minor 2).
     */
    private Optional<Stopped> startPendingUnpack(RestockSettings s, ClientPlayerEntity p) {
        String material = pendingUnpack;
        pendingUnpack = null;
        UnpackChoice.Choice c = StateFacts.carried(p.getInventory()).getOrDefault(material, 0) > 0
            ? new UnpackChoice.None(List.of())
            : UnpackChoice.choose(List.of(material), ShulkerInventory.choiceInventory(p.getInventory(), borrowed),
                s.useCarriedShulkers(), gaveUp);
        if (c instanceof UnpackChoice.Unpack u) return startUnpackNow(u, p);
        givePrinterBack();
        if (recountPending) recount();
        return Optional.empty();
    }

    private void startUnpack(UnpackChoice.Unpack u, ClientPlayerEntity p, boolean printerJustPaused) {
        PlayerInventory inv = p.getInventory();
        // M4: boxes of this kind (item and custom name), as UnpackDriver counts them every tick.
        int carried = ShulkerInventory.kinds(inv).getOrDefault(u.kind(), 0);
        UnpackPlan core = new UnpackPlan(new UnpackPlan.Plan(u.material(), u.kind().item(), carried,
            WorldRay.pos(p.getBlockPos()), inv.getSelectedSlot(), printerJustPaused), UnpackLimits.DEFAULTS, limits);
        unpack = new UnpackDriver(this, mc, mover, core, u.kind(), facts);
        module.info(RestockText.UNPACK_STARTED, "material", RestockMessages.itemName(u.material()));
    }

    /**
     * The box is back: the printer given back first (it acts, then speaks, as phase A's {@link #finished}), then what
     * the box gave; a box still standing (a late placement) said; a material it gave none of noted.
     */
    private void unpacked(UnpackDriver.Done done, ClientPlayerEntity p) {
        String material = unpack.material();
        unpack = null;
        draining = null;
        givePrinterBack();
        module.info(RestockText.UNPACK_DONE, "taken", RestockMessages.orNone(RestockMessages.materials(done.taken())));
        for (Msg m : RestockMessages.shulkersLeft(done.left())) module.warning(m);
        if (StateFacts.carried(p.getInventory()).getOrDefault(material, 0) == 0 && gaveUp.add(material)) {
            module.info(RestockText.UNPACK_GAVE_UP, "material", RestockMessages.itemName(material));
        }
        if (recountPending) recount();
    }

    // --- sources ---------------------------------------------------------------------------------------------------

    private void watchSources() {
        int marks = MarkStore.version();
        int stashSize = stashKeeperSize();
        if (marks != lastMarks || stashSize != lastStashSize) {
            lastMarks = marks;
            lastStashSize = stashSize;
            runOut.sourcesChanged();
            saidNowhere.clear();
        } else if (tick % NOWHERE_RETRY_TICKS == 0) {
            runOut.sourcesChanged();
        }
    }

    private static int stashKeeperSize() {
        StashKeeper keeper = Modules.get().get(StashKeeper.class);
        return keeper != null && keeper.isActive() ? keeper.index().size() : -1;
    }

    private List<MarkBook.Mark> marks() {
        MarkBook book = MarkStore.book();
        return book == null ? List.of() : book.in(dimensionId());
    }

    /** stash-keeper's live index while it is on; its saved one, read once, while it is off; none when not used. */
    private StashIndex stash(RestockSettings s) {
        if (!s.useStashKeeper()) return null;
        StashKeeper keeper = Modules.get().get(StashKeeper.class);
        if (keeper != null && keeper.isActive()) return keeper.index();
        if (savedStash == null) {
            try {
                savedStash = new StashStore(MeteorClient.FOLDER.toPath().resolve("xploits").resolve("stash")
                    .resolve(Utils.getFileWorldName()).resolve("index.json")).load();
            } catch (IOException e) {
                savedStash = new StashIndex();
                module.warning(RestockText.STASH_UNREADABLE);
            }
        }
        return savedStash;
    }

    private String dimensionId() {
        return mc.world.getRegistryKey().getValue().toString();
    }

    private static Map<String, Integer> gained(Map<String, Integer> before, Map<String, Integer> now) {
        Map<String, Integer> m = new TreeMap<>();
        now.forEach((item, n) -> {
            int d = n - before.getOrDefault(item, 0);
            if (d > 0) m.put(item, d);
        });
        return m;
    }

    // --- guards ----------------------------------------------------------------------------------------------------

    /**
     * The source's own stop is always null here: restock raises Easy Place's restriction only as a refusal at enable; if it
     * comes on later, the click it cancels is the trip's stop {@code CONTAINER_REFUSED}, whose text names it.
     */
    private Guards.Inputs guardInputs(Received in, RestockSettings s, ClientPlayerEntity p) {
        PacketWatch watch = PacketWatch.get();
        boolean acting = (trip != null && trip.clicking() && (trip.actingAtRequest() || watch.foreignActionLastTick()))
            || (unpack != null && unpack.clicking() && (unpack.actingAtRequest() || watch.foreignActionLastTick()));
        boolean attacked = false;
        for (EntityDamageS2CPacket d : in.damage()) {
            if (d.entityId() != p.getId() || d.sourceCauseId() < 0) continue;
            Entity cause = mc.world.getEntityById(d.sourceCauseId());
            if (cause instanceof PlayerEntity other && other != p && !Friends.get().isFriend(other)) attacked = true;
        }
        return new Guards.Inputs(TickRate.INSTANCE.getTimeSinceLastTick(), eating(p), acting, active(Guards.COMBAT_MODULES),
            autoPvpEngaged(), nonFriendWithin(mc, s.playerDistance()), s.stopNearPlayers(), attacked,
            p.getHealth() + p.getAbsorptionAmount(), s.minHealth(), in.setback() && (trip != null || unpack != null),
            null, active(RestockLimits.CONFLICTING_MODULES), false, !p.isAlive(),
            !mc.world.getRegistryKey().equals(dimension), false);
    }

    /** The names in {@code names} whose Meteor module is on (a name Meteor does not register is skipped). */
    static List<String> active(List<String> names) {
        List<String> on = new ArrayList<>();
        for (String name : names) {
            Module module = Modules.get().get(name);
            if (module != null && module.isActive()) on.add(name);
        }
        return on;
    }

    /** Auto-pvp has a target (its own plan's state, not its rotations). */
    static boolean autoPvpEngaged() {
        AutoPvp autoPvp = Modules.get().get(AutoPvp.class);
        return autoPvp != null && autoPvp.currentPlan().map(plan -> plan.state() != CombatState.NO_COMBAT).orElse(false);
    }

    /** A player who is not the player and not a Meteor friend, within {@code distance} blocks (within tracking range). */
    static boolean nonFriendWithin(MinecraftClient mc, int distance) {
        ClientPlayerEntity me = mc.player;
        if (me == null || mc.world == null) return false;
        for (AbstractClientPlayerEntity other : mc.world.getPlayers()) {
            if (other == me || Friends.get().isFriend(other)) continue;
            if (other.squaredDistanceTo(me) <= (double) distance * distance) return true;
        }
        return false;
    }

    private static boolean eating(ClientPlayerEntity p) {
        AutoEat autoEat = Modules.get().get(AutoEat.class);
        AutoGap autoGap = Modules.get().get(AutoGap.class);
        return p.isUsingItem() || (autoEat != null && autoEat.eating) || (autoGap != null && autoGap.isEating());
    }
}
