package com.xploits.pvp.shell.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The threat map (surround++ spec §5.1-5.3): every spot in the planning box where an opponent could put an end crystal,
 * now or after placing its base, that some opponent's eye reaches; how much it would hurt us; and what the blocks planned
 * this tick change about it.
 *
 * <p>A planned block changes three things: the spot it fills is gone; the spot above it gets a base if it is obsidian and
 * none if it is crying obsidian; and the rays from a spot to us that cross it are closed. The rays are three, from the
 * crystal's explosion to our feet, middle and eyes ({@link #BODY_POINTS}); a spot keeps the share of them still open
 * ({@link Threat#open}). The damage itself is measured once per spot per tick with the world as it is, so the blocks
 * already there are in it and only the planned ones go through the rays.
 */
public final class ThreatMap {
    public static final int SPOT_RADIUS = 4;
    public static final int SPOT_BELOW = 1;
    public static final int SPOT_ABOVE = 3;
    public static final double NEEDS_BASE_WEIGHT = 0.25;
    /** An opponent closes this much of the distance in the time we take to react: we plan against where he will reach. */
    public static final double REACH_MARGIN = 1.0;
    /** What a damage that is not a number reads as: the worst, so it is covered first. */
    public static final double UNKNOWN_DAMAGE = 36.0;
    /** Heights above our feet the rays aim at: feet, middle and eyes. */
    static final double[] BODY_POINTS = {0.2, 0.9, 1.6};

    private static final Comparator<Threat> WORST_FIRST = Comparator.comparingDouble(Threat::weighted).reversed()
        .thenComparing(t -> t.spot().y(), Comparator.reverseOrder())
        .thenComparingInt(t -> t.spot().x())
        .thenComparingInt(t -> t.spot().z());

    /** What every copy of one tick's map shares: the measured damage and the rays, which planned blocks do not change. */
    private static final class Shared {
        final Map<Cell, Double> damage = new HashMap<>();
        final Map<Cell, List<List<Cell>>> rays = new HashMap<>();
        Map<Cell, List<Cell>> crossing;
    }

    private final ShellSnapshot snapshot;
    private final DamageOracle oracle;
    private final Shared shared;
    private final Map<Cell, BlockKind> planned;

    public ThreatMap(ShellSnapshot snapshot, DamageOracle oracle) {
        this(snapshot, oracle, new Shared(), new LinkedHashMap<>());
    }

    private ThreatMap(ShellSnapshot snapshot, DamageOracle oracle, Shared shared, Map<Cell, BlockKind> planned) {
        this.snapshot = snapshot;
        this.oracle = oracle;
        this.shared = shared;
        this.planned = planned;
    }

    public ShellSnapshot snapshot() {
        return snapshot;
    }

    /** The same map with its own planned blocks from here on; the measurements stay shared. */
    public ThreatMap copy() {
        return new ThreatMap(snapshot, oracle, shared, new LinkedHashMap<>(planned));
    }

    public BlockKind kind(Cell c) {
        BlockKind kind = planned.get(c);
        return kind != null ? kind : snapshot.kind(c);
    }

    public Map<Cell, BlockKind> planned() {
        return Collections.unmodifiableMap(planned);
    }

    /** Some neighbour, there now or planned, gives a block placed here a face to be placed against. */
    public boolean supported(Cell c) {
        for (Cell n : c.neighbours()) {
            if (kind(n).supports()) return true;
        }
        return false;
    }

    /** Plans a block: from now on this map reads it as there. */
    public void place(Cell c, Material material) {
        if (!kind(c).isPlaceable()) throw new IllegalStateException("no block can go there");
        planned.put(c, material.kind());
    }

    public static boolean inSpotBox(Cell c) {
        return Math.abs(c.x()) <= SPOT_RADIUS && Math.abs(c.z()) <= SPOT_RADIUS && c.y() >= -SPOT_BELOW && c.y() <= SPOT_ABOVE;
    }

    /** The threat at this spot, if an opponent could put a crystal there that hurts us. */
    public Optional<Threat> threatAt(Cell spot) {
        if (!inSpotBox(spot) || kind(spot) != BlockKind.AIR) return Optional.empty();
        // Vanilla's rule: nothing in the one-by-two box above the base.
        if (snapshot.occupied(spot) || snapshot.occupied(spot.up())) return Optional.empty();
        Cell base = spot.down();
        BlockKind below = kind(base);
        Threat.Kind kind;
        if (below.isBase()) {
            kind = Threat.Kind.REAL;
        } else if (below.isPlaceable() && !snapshot.occupied(base) && supported(base)) {
            kind = Threat.Kind.NEEDS_BASE;
        } else {
            return Optional.empty();
        }
        if (!reachable(spot)) return Optional.empty();
        double open = open(spot);
        if (open == 0) return Optional.empty();
        double damage = damage(spot, kind);
        if (damage <= 0) return Optional.empty();
        return Optional.of(new Threat(spot, kind, damage, open));
    }

    public double weightedAt(Cell spot) {
        return threatAt(spot).map(Threat::weighted).orElse(0.0);
    }

    /** Every threat in the planning box, the worst first. */
    public List<Threat> threats() {
        List<Threat> all = new ArrayList<>();
        for (int y = -SPOT_BELOW; y <= SPOT_ABOVE; y++) {
            for (int x = -SPOT_RADIUS; x <= SPOT_RADIUS; x++) {
                for (int z = -SPOT_RADIUS; z <= SPOT_RADIUS; z++) threatAt(new Cell(x, y, z)).ifPresent(all::add);
            }
        }
        all.sort(WORST_FIRST);
        return all;
    }

    /**
     * How much the threats' total weight would drop with these blocks placed, in this order, without planning them: what
     * the planner compares its options by. Only the spots a block can change are looked at ({@link #affectedBy}).
     */
    public double value(List<Placement> blocks) {
        Set<Cell> affected = new LinkedHashSet<>();
        ThreatMap after = copy();
        for (Placement b : blocks) {
            affected.addAll(affectedBy(b.cell()));
            after.place(b.cell(), b.material());
        }
        double value = 0;
        for (Cell spot : affected) value += weightedAt(spot) - after.weightedAt(spot);
        return value;
    }

    /**
     * The spots a block here can change: its own; the one above it (the block is that spot's base); the ones above its
     * neighbours (it gives their base cell something to be placed against); and every spot one of whose rays to us it
     * crosses.
     */
    Set<Cell> affectedBy(Cell c) {
        Set<Cell> spots = new LinkedHashSet<>();
        spots.add(c);
        spots.add(c.up());
        for (Cell n : c.neighbours()) spots.add(n.up());
        spots.addAll(crossing().getOrDefault(c, List.of()));
        return spots;
    }

    /** The cells each of this spot's three rays to us crosses, without the spot's own cell or ours. */
    List<List<Cell>> rays(Cell spot) {
        return shared.rays.computeIfAbsent(spot, this::raysOf);
    }

    private List<List<Cell>> raysOf(Cell spot) {
        Set<Cell> body = snapshot.body();
        Vec from = spot.explosion();
        List<List<Cell>> result = new ArrayList<>();
        for (double height : BODY_POINTS) {
            Vec to = new Vec(snapshot.feet().x(), snapshot.feet().y() + height, snapshot.feet().z());
            List<Cell> cells = new ArrayList<>();
            for (Cell c : Segments.crossed(from, to)) {
                if (!c.equals(spot) && !body.contains(c)) cells.add(c);
            }
            result.add(List.copyOf(cells));
        }
        return List.copyOf(result);
    }

    /** For each cell, the spots in the box one of whose rays crosses it: built once per tick. */
    private Map<Cell, List<Cell>> crossing() {
        if (shared.crossing == null) {
            Map<Cell, List<Cell>> index = new HashMap<>();
            for (int y = -SPOT_BELOW; y <= SPOT_ABOVE; y++) {
                for (int x = -SPOT_RADIUS; x <= SPOT_RADIUS; x++) {
                    for (int z = -SPOT_RADIUS; z <= SPOT_RADIUS; z++) {
                        Cell spot = new Cell(x, y, z);
                        if (snapshot.kind(spot) != BlockKind.AIR) continue;
                        for (List<Cell> ray : rays(spot)) {
                            for (Cell c : ray) {
                                List<Cell> spots = index.computeIfAbsent(c, k -> new ArrayList<>());
                                if (!spots.contains(spot)) spots.add(spot);
                            }
                        }
                    }
                }
            }
            shared.crossing = index;
        }
        return shared.crossing;
    }

    /** The share of this spot's rays no planned block closes. */
    double open(Cell spot) {
        List<List<Cell>> rays = rays(spot);
        int open = 0;
        for (List<Cell> ray : rays) {
            boolean closed = false;
            for (Cell c : ray) {
                if (planned.containsKey(c)) {
                    closed = true;
                    break;
                }
            }
            if (!closed) open++;
        }
        return (double) open / rays.size();
    }

    private boolean reachable(Cell spot) {
        Vec at = spot.explosion();
        double reach = snapshot.settings().reach() + REACH_MARGIN;
        for (Vec eye : snapshot.hostileEyes()) {
            if (eye.squaredDistance(at) <= reach * reach) return true;
        }
        return false;
    }

    /**
     * The damage of a crystal here, measured once per tick. The bound first: a spot whose bound, weighted, stays below
     * {@link Threat#MIN_DANGER} keeps it (it never matters) and costs no raycast. A spot measured while it needed its base
     * keeps that value if our own obsidian then makes it real: the bound is never below the exact value, so it only errs
     * towards covering it.
     */
    private double damage(Cell spot, Threat.Kind kind) {
        Double known = shared.damage.get(spot);
        if (known != null) return known;
        double bound = checked(oracle.bound(spot));
        double value = bound * Threat.weightOf(kind) < Threat.MIN_DANGER ? bound : checked(oracle.exact(spot));
        shared.damage.put(spot, value);
        return value;
    }

    private static double checked(double damage) {
        return Double.isFinite(damage) && damage >= 0 ? damage : UNKNOWN_DAMAGE;
    }
}
