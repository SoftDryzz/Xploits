package com.xploits.restock;

import com.xploits.XploitsAddon;
import com.xploits.printer.core.BuildIndex;
import com.xploits.printer.core.Guards;
import com.xploits.printer.core.Point;
import com.xploits.printer.core.Pos;
import com.xploits.printer.core.PrinterLimits;
import com.xploits.printer.core.Target;
import com.xploits.pvp.AutoPvp;
import com.xploits.pvp.core.CombatState;
import com.xploits.restock.core.MarkBook;
import com.xploits.restock.core.PlacedExtra;
import com.xploits.restock.core.PrintPause;
import com.xploits.restock.core.RestockLimits;
import com.xploits.restock.core.RestockMessages;
import com.xploits.restock.core.RestockNeeds;
import com.xploits.restock.core.RestockReason;
import com.xploits.restock.core.RestockSettings;
import com.xploits.restock.core.RestockText;
import com.xploits.restock.core.RestockTrip;
import com.xploits.restock.core.RunOut;
import com.xploits.restock.core.Source;
import com.xploits.restock.core.SourceChooser;
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
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.registry.RegistryKey;
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
 * when a material is due, chooses its source and drives the trip. Positions stay in memory. Client thread only.
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
    }

    /**
     * What ending the session did, for the module to say once all of it is done (ruling R33): {@code restored}, Baritone
     * took every command that gives its values back; {@code printerLeftPaused}, restock held litematica-printer off and
     * leaves it so.
     */
    record Closed(boolean restored, boolean printerLeftPaused) {
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
    private final RegistryKey<World> dimension;
    private final Map<String, Set<Pos>> stale = new HashMap<>();
    private final Set<Pos> unusable = new HashSet<>();
    private final Set<String> saidNowhere = new HashSet<>();
    private RestockSettings settings = RestockSettings.DEFAULTS;
    private TargetSource source;
    private BuildIndex index;
    private Map<String, Long> totals = Map.of();
    private RestockReason notCounted;
    private String notCountedDetail = "";
    private boolean recountPending;
    private TripDriver trip;
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
        return trip != null;
    }

    Optional<RestockTrip.Phase> tripPhase() {
        return trip == null ? Optional.empty() : Optional.of(trip.phase());
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

    int stashCount(RestockSettings s) {
        StashIndex st = stash(s);
        if (st == null) return 0;
        return (int) sources.list(dimensionId(), List.of(), st).stream().filter(x -> x.kind() == Source.Kind.STASH).count();
    }

    RestockText activity(boolean paused) {
        if (paused) return RestockText.ACTIVITY_PAUSED;
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
        if (trip != null) {
            return Msg.of(RestockText.STATUS_TRIP, "activity", Msg.of(activity(false)),
                "material", RestockMessages.itemName(trip.material()));
        }
        if (index.passes() < 1) return Msg.of(RestockText.STATUS_SCANNING);
        ClientPlayerEntity p = mc.player;
        Map<String, Integer> carried = p == null ? Map.of() : StateFacts.carried(p.getInventory());
        Map<String, Integer> placed = index.placed();
        Map<String, Integer> held = extra.byMaterial();
        long remaining = 0;
        for (Map.Entry<String, Long> e : totals.entrySet()) {
            remaining += Math.max(0, e.getValue() - placed.getOrDefault(e.getKey(), 0) - held.getOrDefault(e.getKey(), 0));
        }
        return Msg.of(RestockText.STATUS_IDLE, "remaining", remaining, "short",
            RestockMessages.orNone(RestockMessages.materials(RestockNeeds.need(totals, placed, held, carried))));
    }

    // --- the tick ------------------------------------------------------------------------------------------------

    Outcome tick(Received in, RestockSettings s) {
        tick++;
        settings = s;
        if (trip != null) trip.newTick();
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return new Stopped(RestockReason.NO_WORLD, "");
        Guards.Verdict verdict = guards.tick(guardInputs(in, s, p));
        if (verdict instanceof Guards.Stop stop) return new Stopped(RestockReason.of(stop.reason()), stop.detail());
        boolean paused = verdict instanceof Guards.Pause;
        mover.tick();
        watchSources();
        if (index != null) {
            for (BlockPos b : in.changed()) classify(WorldRay.pos(b));
            if (source.changed()) {
                if (trip == null) recount();
                else recountPending = true;
            }
        } else if (trip == null && tick % RETRY_TICKS == 0) {
            // A refusing source never reports a change: open it again, so counting resumes once the player fixes it.
            recount();
        }
        if (index != null) {
            for (Pos pos : index.nextToScan(PrinterLimits.DEFAULTS.scanBudget())) classify(pos);
        }
        if (trip != null) {
            TripDriver.Result r = trip.tick(paused);
            if (r instanceof TripDriver.Ended end) return new Stopped(end.reason(), end.detail());
        } else if (!paused) {
            Optional<Stopped> stopped = startTripIfDue(s, p);
            if (stopped.isPresent()) return stopped.get();
        }
        if (verdict instanceof Guards.Pause pause) return new Paused(RestockReason.of(pause.reason()), pause.detail());
        return GOING;
    }

    /** From {@code SendMovementPacketsEvent.Pre} at {@code HIGH}. */
    void requestRotation() {
        TripDriver t = trip;
        if (t != null) t.requestRotation();
    }

    /**
     * Ends the session (ruling R33): a trip under way stops walking and closes restock's container screen if it can, the
     * printer marker goes (leaving the world keeps it for the next join) and Baritone's values go back — each step on its
     * own, so one that fails never skips the next.
     * Nothing is said here: the module speaks once all of it is done. Never throws.
     */
    Closed close(RestockReason why) {
        TripDriver t = trip;
        trip = null;
        if (t != null) {
            quietly("stopping the walk", t::abort);
            // Deferred L59: a stop during TAKE closes restock's screen as any close would (never while leaving).
            if (why != RestockReason.LEFT) quietly("closing restock's container", t::closeOwnScreen);
        }
        boolean leftPaused = printerHeld && !PrintPause.keepMarker(why);
        printerHeld = false;
        if (leftPaused) quietly("deleting the printer marker", PrinterMarker::delete);
        boolean[] restored = new boolean[1];
        quietly("giving Baritone its values back", () -> restored[0] = mover.end());
        return new Closed(restored[0], leftPaused);
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

    /** What the build still needs beyond what is carried: the due materials in the order they ran out, then the rest. */
    Map<String, Long> orderedNeed() {
        ClientPlayerEntity p = mc.player;
        Map<String, Integer> carried = p == null ? Map.of() : StateFacts.carried(p.getInventory());
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
    }

    /** A source failed: noted, then the next nearest for the same material, measured from where the trip started. */
    Optional<Source> next(String material, Pos failed, boolean wasStale, Pos resume) {
        if (wasStale) {
            stale.computeIfAbsent(material, k -> new HashSet<>()).add(failed);
            module.info(RestockText.TRIP_STALE, "material", RestockMessages.itemName(material));
        } else {
            unusable.add(failed);
            module.info(RestockText.TRIP_UNUSABLE);
        }
        String dim = dimensionId();
        Optional<Source> next = SourceChooser.nearest(sources.list(dim, marks(), stash(settings)), material, dim,
            new Point(resume.x() + 0.5, resume.y(), resume.z() + 0.5), settings.maxDistance(), unusable,
            stale.getOrDefault(material, Set.of()), false);
        if (next.isEmpty()) module.info(RestockText.NOWHERE_AFTER_TRIP, "material", RestockMessages.itemName(material));
        return next;
    }

    /** Back where the trip started: the printer given back if restock paused it and it is still off. */
    void finished(boolean resumePrinter, boolean took, String material, Map<String, Integer> before) {
        trip = null;
        module.countTrip();
        if (resumePrinter) {
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
        ClientPlayerEntity p = mc.player;
        if (took && p != null) {
            module.info(RestockText.TRIP_DONE, "taken",
                RestockMessages.orNone(RestockMessages.materials(gained(before, StateFacts.carried(p.getInventory())))));
        } else {
            runOut.nowhere(material);
        }
        if (recountPending) recount();
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
            runOut = new RunOut(limits.duePasses());
            totals = Map.of();
            notCounted = why;
            notCountedDetail = refusal.get().detail();
            return;
        }
        index = new BuildIndex(s.boxes());
        extra.clear();
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
            return;
        }
        BlockState t = source.target(b);
        Target target = t == null ? Target.UNKNOWN : Target.of(facts.of(t));
        int perBlock = t == null ? 0 : facts.perBlock(t);
        BlockState w = mc.world.getBlockState(b);
        RestockNeeds.Classified c = RestockNeeds.classify(target, perBlock, facts.of(w), facts.perBlock(w));
        index.set(pos, c.status(), c.material());
        extra.set(pos, c.material(), c.extra());
    }

    // --- choosing and starting a trip -----------------------------------------------------------------------------

    private Optional<Stopped> startTripIfDue(RestockSettings s, ClientPlayerEntity p) {
        if (index == null) return Optional.empty();
        Map<String, Integer> carried = StateFacts.carried(p.getInventory());
        Map<String, Long> need = RestockNeeds.need(totals, index.placed(), extra.byMaterial(), carried);
        // Ruling R31: only a material the index knows a missing position of is due; the need (how much to take) is whole.
        List<String> due = runOut.due(tick, need, carried, index.passes(), index.remaining(false).keySet());
        lastDue = due;
        if (due.isEmpty()) return Optional.empty();
        boolean screenFree = mc.currentScreen == null && p.currentScreenHandler.getCursorStack().isEmpty();
        // Ruling R32: it waits while the player walks, sneaks or is in the air, and leaves once they stand.
        if (!RestockTrip.mayLeave(screenFree, TripDriver.movementKeys(mc.options), p.isSneaking(), p.isOnGround())) {
            return Optional.empty();
        }
        String dim = dimensionId();
        List<Source> list = sources.list(dim, marks(), stash(s));
        Point from = new Point(p.getX(), p.getY(), p.getZ());
        for (String material : due) {
            Optional<Source> chosen = SourceChooser.nearest(list, material, dim, from, s.maxDistance(), unusable,
                stale.getOrDefault(material, Set.of()), false);
            if (chosen.isPresent()) return startTrip(material, chosen.get(), p, from);
            runOut.nowhere(material);
            if (saidNowhere.add(material)) {
                module.info(RestockText.NOWHERE, "material", RestockMessages.itemName(material), "count",
                    need.get(material), "distance", s.maxDistance());
            }
        }
        return Optional.empty();
    }

    private Optional<Stopped> startTrip(String material, Source chosen, ClientPlayerEntity p, Point from) {
        boolean paused = false;
        if (PrintPause.atTripStart(printer.installed(), printer.printing()) == PrintPause.Action.SWITCH_OFF) {
            // The marker first: a crash between the two finds the printer still on, and the join leaves it so.
            try {
                PrinterMarker.write();
            } catch (IOException e) {
                module.warning(RestockText.MARKER_WRITE_FAILED);
            }
            if (!printer.set(false)) {
                PrinterMarker.delete();
                return Optional.of(new Stopped(RestockReason.LITEMATICA_PRINTER_UNREADABLE, ""));
            }
            printerHeld = true;
            paused = true;
        }
        RestockTrip core = new RestockTrip(new RestockTrip.Plan(material, chosen.container(), chosen.stand(),
            WorldRay.pos(p.getBlockPos()), paused), limits);
        trip = new TripDriver(this, mc, mover, core, limits, StateFacts.carried(p.getInventory()));
        module.info(RestockText.TRIP_STARTED, "material", RestockMessages.itemName(material),
            "kind", Msg.of(chosen.kind() == Source.Kind.MARK ? RestockText.KIND_MARK : RestockText.KIND_STASH),
            "distance", Math.round(Math.sqrt(chosen.container().distanceSq(from))));
        return Optional.empty();
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
        boolean acting = trip != null && trip.clicking()
            && (trip.actingAtRequest() || PacketWatch.get().foreignActionLastTick());
        boolean attacked = false;
        for (EntityDamageS2CPacket d : in.damage()) {
            if (d.entityId() != p.getId() || d.sourceCauseId() < 0) continue;
            Entity cause = mc.world.getEntityById(d.sourceCauseId());
            if (cause instanceof PlayerEntity other && other != p && !Friends.get().isFriend(other)) attacked = true;
        }
        return new Guards.Inputs(TickRate.INSTANCE.getTimeSinceLastTick(), eating(p), acting, active(Guards.COMBAT_MODULES),
            autoPvpEngaged(), nonFriendWithin(mc, s.playerDistance()), s.stopNearPlayers(), attacked,
            p.getHealth() + p.getAbsorptionAmount(), s.minHealth(), in.setback() && trip != null, null,
            active(RestockLimits.CONFLICTING_MODULES), false, !p.isAlive(), !mc.world.getRegistryKey().equals(dimension),
            false);
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
