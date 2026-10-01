package com.xploits.printer.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * At most one action per tick (printer spec §4, §5.2): one place or one dig, lowest first, then nearest, among what is in
 * reach; a place only against a valid support face; a dig only of a wrong block the never-break rules allow. The adapter's
 * vanilla raycast is asked, candidate by candidate, through {@link RayOracle}.
 */
public final class PlacePlanner {
    public sealed interface Action permits Place, Dig, Idle, Halt {
    }

    /** Click {@code support}'s {@code side} face at {@code hit}; the block lands at {@code target}. */
    public record Place(Pos target, Pos support, Face side, Point hit, Aim.Rotation rotation, String material)
        implements Action {
    }

    /** Mine {@code target} from its {@code side} face with {@code tool}; {@code material} goes there afterwards. */
    public record Dig(Pos target, Face side, Point hit, Aim.Rotation rotation, BreakPlan.Choice tool, String material)
        implements Action {
    }

    public enum IdleReason { NOTHING_TO_DO, NO_FACE, RAY_BUDGET }

    public record Idle(IdleReason reason) implements Action {
    }

    /** The loop guard: a block the printer broke in this session is wrong again; stop and say so. */
    public record Halt(PhaseRules.NeverBreak reason, Pos at) implements Action {
    }

    /** Does the vanilla raycast from the eye, with exactly this rotation, return this block and face within reach? */
    @FunctionalInterface
    public interface RayOracle {
        boolean sees(Pos block, Face side, Aim.Rotation rotation);
    }

    private record Candidate(Cell cell, boolean missing) {
    }

    private record Option(Pos block, Face side, Point hit, double distance, Aim.Rotation rotation) {
    }

    private final PrinterLimits limits;

    public PlacePlanner(PrinterLimits limits) {
        this.limits = limits;
    }

    public Action decide(BuildSnapshot s, RayOracle oracle) {
        List<Candidate> candidates = new ArrayList<>();
        for (Cell cell : s.cells().values()) {
            Pos p = cell.pos();
            if (s.pending().contains(p) || s.digging().contains(p) || s.skipped().contains(p)) continue;
            PhaseRules.Contents c = PhaseRules.classify(cell);
            if (c != PhaseRules.Contents.MISSING && c != PhaseRules.Contents.DIFFERENT) continue;
            String material = cell.target().block().item();
            if (s.carried().getOrDefault(material, 0) <= 0) continue;
            if (c == PhaseRules.Contents.MISSING) {
                if (s.entityBlocked().contains(p)) continue;
                if (PhaseRules.carpet(cell.target().block().id())) {
                    Cell below = s.cells().get(p.offset(Face.DOWN));
                    if (below == null || below.world().air()) continue;
                }
                candidates.add(new Candidate(cell, true));
            } else {
                if (!s.fixWrongBlocks()) continue;
                Optional<PhaseRules.NeverBreak> why = PhaseRules.neverBreak(breakView(s, cell, limits));
                if (why.isPresent()) {
                    if (why.get() == PhaseRules.NeverBreak.ALREADY_BROKEN) return new Halt(why.get(), p);
                    continue;
                }
                candidates.add(new Candidate(cell, false));
            }
        }
        if (candidates.isEmpty()) return new Idle(IdleReason.NOTHING_TO_DO);
        candidates.sort(Comparator.<Candidate>comparingInt(c -> c.cell().pos().y())
            .thenComparingDouble(c -> c.cell().pos().distanceSq(s.eye()))
            .thenComparingInt(c -> c.cell().pos().x())
            .thenComparingInt(c -> c.cell().pos().z()));
        double reach = Math.min(s.reach(), limits.maxReach());
        int rays = 0;
        for (Candidate candidate : candidates) {
            List<Option> options = candidate.missing() ? placeOptions(s, candidate.cell(), reach)
                : digOptions(s, candidate.cell(), reach);
            for (Option o : options) {
                if (rays >= limits.rayBudget()) return new Idle(IdleReason.RAY_BUDGET);
                rays++;
                if (!oracle.sees(o.block(), o.side(), o.rotation())) continue;
                String material = candidate.cell().target().block().item();
                if (candidate.missing()) {
                    return new Place(candidate.cell().pos(), o.block(), o.side(), o.hit(), o.rotation(), material);
                }
                return new Dig(candidate.cell().pos(), o.side(), o.hit(), o.rotation(),
                    s.breakChoices().get(candidate.cell().pos()), material);
            }
        }
        return new Idle(IdleReason.NO_FACE);
    }

    /** The never-break view of a cell from this snapshot (also used by the adapter's scanner). */
    public static PhaseRules.BreakView breakView(BuildSnapshot s, Cell cell, PrinterLimits limits) {
        Map<Face, BlockFacts> neighbours = new EnumMap<>(Face.class);
        for (Face f : Face.values()) {
            Cell n = s.cells().get(cell.pos().offset(f));
            if (n != null) neighbours.put(f, n.world());
        }
        BreakPlan.Choice tool = s.breakChoices().get(cell.pos());
        return new PhaseRules.BreakView(cell, neighbours, s.standingOn().contains(cell.pos()),
            false, s.broken().contains(cell.pos()), tool == null ? -1 : tool.ticks(), limits.breakCapTicks());
    }

    private List<Option> placeOptions(BuildSnapshot s, Cell cell, double reach) {
        List<Option> options = new ArrayList<>();
        for (Face f : Face.values()) {
            Pos support = cell.pos().offset(f);
            Face side = f.opposite();
            Cell sc = s.cells().get(support);
            if (sc == null || !PhaseRules.support(sc.world())) continue;
            addIfReachable(options, s, support, side, reach);
        }
        sortOptions(options);
        return options;
    }

    private List<Option> digOptions(BuildSnapshot s, Cell cell, double reach) {
        List<Option> options = new ArrayList<>();
        for (Face f : Face.values()) addIfReachable(options, s, cell.pos(), f, reach);
        sortOptions(options);
        return options;
    }

    private void addIfReachable(List<Option> options, BuildSnapshot s, Pos block, Face side, double reach) {
        if (!Aim.facesEye(block, side, s.eye())) return;
        Point hit = Aim.hitPoint(block, side, s.eye(), limits.hitMargin());
        double distance = hit.distance(s.eye());
        if (distance > reach) return;
        options.add(new Option(block, side, hit, distance, Aim.rotation(s.eye(), hit, s.yaw())));
    }

    private static void sortOptions(List<Option> options) {
        options.sort(Comparator.comparingDouble(Option::distance).thenComparingInt(o -> o.side().ordinal()));
    }
}
