package com.xploits.pvp.shell.core;

import com.xploits.pvp.crystal.core.SelfBudget;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The block plan (surround++ spec §5.3-5.6). The worst threat first; for it, the option that takes the most threat away
 * per block ({@link ThreatMap#value}): filling the spot; crying obsidian where the opponent would have to place its base;
 * a block in the way of its rays to us; or, for a spot with nothing to place against, a block next to it and then the
 * spot. Ties go to the option generated first, which puts plain obsidian before crying obsidian: crying obsidian is kept
 * for where it does better. Until the budget is spent, the hotbar is empty, or nothing worth {@link Threat#MIN_DANGER}
 * is left. A threat nothing can be done about this tick is passed over for the next one.
 *
 * <p>crystal-aura++'s spot is left free (spec §5.5) unless a crystal there, all of it getting through, would take us
 * below {@link SelfBudget#FLOOR}: then it is covered and the plan says so. Closure after a totem pop (spec §7.4) comes
 * after the threats, with what the budget has left.
 */
public final class ShellPlanner {
    /** Our own reach for placing: vanilla's block interaction range, from the eyes to the block's centre. */
    public static final double PLACE_REACH = 4.5;

    private ShellPlanner() {
    }

    /**
     * What was planned.
     *
     * @param placements   the blocks, in the order to place them
     * @param map          the map with them planned, for what comes after it this tick
     * @param auraOverride whether a block went where crystal-aura++ is placing, because a crystal there could take us below the floor
     */
    public record Result(List<Placement> placements, ThreatMap map, boolean auraOverride) {
        public Result {
            placements = List.copyOf(placements);
        }
    }

    /** One way of dealing with a threat: its blocks in order, and how much threat they take away. */
    record Option(List<Placement> blocks, double value) {
        double perBlock() {
            return value / blocks.size();
        }
    }

    /** What the hotbar still holds as the plan spends it. */
    static final class Stock {
        private int obsidian;
        private int crying;

        Stock(int obsidian, int crying) {
            this.obsidian = obsidian;
            this.crying = crying;
        }

        boolean any() {
            return obsidian > 0 || crying > 0;
        }

        boolean has(Material m, int count) {
            return (m == Material.OBSIDIAN ? obsidian : crying) >= count;
        }

        /** The materials there are, plain obsidian first. */
        List<Material> kinds() {
            List<Material> kinds = new ArrayList<>(2);
            if (obsidian > 0) kinds.add(Material.OBSIDIAN);
            if (crying > 0) kinds.add(Material.CRYING_OBSIDIAN);
            return kinds;
        }

        void take(Material m) {
            if (m == Material.OBSIDIAN) obsidian--;
            else crying--;
        }
    }

    public static Result plan(ThreatMap map, int budget, boolean holdClosed) {
        ShellSnapshot s = map.snapshot();
        Stock stock = new Stock(s.obsidian(), s.settings().useCryingObsidian() ? s.cryingObsidian() : 0);
        List<Placement> placements = new ArrayList<>();
        Set<Cell> givenUp = new HashSet<>();
        boolean auraOverride = false;
        while (placements.size() < budget && stock.any()) {
            Threat worst = worstLeft(map, givenUp);
            if (worst == null) break;
            boolean auraSpot = s.auraSpot().filter(worst.spot()::equals).isPresent();
            if (auraSpot && !lethal(s, worst)) {
                givenUp.add(worst.spot());
                continue;
            }
            Option best = best(options(map, worst, stock, auraSpot));
            if (best == null) {
                givenUp.add(worst.spot());
                continue;
            }
            auraOverride |= auraSpot;
            for (Placement block : best.blocks()) {
                if (placements.size() >= budget) break;
                map.place(block.cell(), block.material());
                stock.take(block.material());
                placements.add(block);
            }
        }
        if (holdClosed) close(map, stock, placements, budget);
        return new Result(placements, map, auraOverride);
    }

    private static Threat worstLeft(ThreatMap map, Set<Cell> givenUp) {
        for (Threat t : map.threats()) {
            if (t.weighted() < Threat.MIN_DANGER) return null;
            if (!givenUp.contains(t.spot())) return t;
        }
        return null;
    }

    /** A crystal there, all of it getting through, would leave us below the floor. */
    private static boolean lethal(ShellSnapshot s, Threat t) {
        return t.damage() * t.open() > s.health() - SelfBudget.FLOOR;
    }

    private static Option best(List<Option> options) {
        Option best = null;
        for (Option o : options) {
            if (o.value() <= 0) continue;
            if (best == null || o.perBlock() > best.perBlock()) best = o;
        }
        return best;
    }

    static List<Option> options(ThreatMap map, Threat worst, Stock stock, boolean allowAura) {
        List<Option> options = new ArrayList<>();
        Cell spot = worst.spot();
        for (Material m : stock.kinds()) single(map, options, spot, m, ShellReason.SPOT, allowAura);
        if (worst.kind() == Threat.Kind.NEEDS_BASE && stock.has(Material.CRYING_OBSIDIAN, 1)) {
            single(map, options, spot.down(), Material.CRYING_OBSIDIAN, ShellReason.BASE, allowAura);
        }
        Set<Cell> shields = new LinkedHashSet<>();
        for (List<Cell> ray : map.rays(spot)) shields.addAll(ray);
        for (Cell c : shields) {
            for (Material m : stock.kinds()) single(map, options, c, m, ShellReason.SHIELD, allowAura);
        }
        if (placeableButUnsupported(map, spot, allowAura)) {
            for (Cell n : supportsFor(spot)) {
                if (!canPlace(map, n, allowAura)) continue;
                for (Material first : stock.kinds()) {
                    for (Material second : stock.kinds()) {
                        if (!stock.has(first, first == second ? 2 : 1) || !stock.has(second, 1)) continue;
                        List<Placement> pair = List.of(new Placement(n, first, ShellReason.SUPPORT),
                            new Placement(spot, second, ShellReason.SPOT));
                        options.add(new Option(pair, map.value(pair)));
                    }
                }
            }
        }
        return options;
    }

    private static void single(ThreatMap map, List<Option> options, Cell c, Material m, ShellReason reason, boolean allowAura) {
        if (!canPlace(map, c, allowAura)) return;
        List<Placement> one = List.of(new Placement(c, m, reason));
        options.add(new Option(one, map.value(one)));
    }

    /** The cells that can hold a block placed at the spot: the one below it, then its four sides. */
    private static List<Cell> supportsFor(Cell spot) {
        return List.of(spot.down(), spot.plus(1, 0, 0), spot.plus(-1, 0, 0), spot.plus(0, 0, 1), spot.plus(0, 0, -1));
    }

    /**
     * A block can be planned here: the cell takes one, no entity (us included) is in it, something holds it, we reach it,
     * and it is not crystal-aura++'s spot unless that is allowed.
     */
    static boolean canPlace(ThreatMap map, Cell c, boolean allowAura) {
        ShellSnapshot s = map.snapshot();
        if (!allowAura && s.auraSpot().filter(c::equals).isPresent()) return false;
        return map.kind(c).isPlaceable() && !s.occupied(c) && map.supported(c) && inReach(s, c);
    }

    private static boolean placeableButUnsupported(ThreatMap map, Cell c, boolean allowAura) {
        ShellSnapshot s = map.snapshot();
        if (!allowAura && s.auraSpot().filter(c::equals).isPresent()) return false;
        return map.kind(c).isPlaceable() && !s.occupied(c) && !map.supported(c) && inReach(s, c);
    }

    private static boolean inReach(ShellSnapshot s, Cell c) {
        return s.eye().distance(c.centre()) <= PLACE_REACH;
    }

    private static void close(ThreatMap map, Stock stock, List<Placement> placements, int budget) {
        for (Cell c : closure(map.snapshot())) {
            if (placements.size() >= budget || !stock.any()) return;
            if (!canPlace(map, c, false)) continue;
            Material m = stock.has(Material.CRYING_OBSIDIAN, 1) ? Material.CRYING_OBSIDIAN : Material.OBSIDIAN;
            map.place(c, m);
            stock.take(m);
            placements.add(new Placement(c, m, ShellReason.CLOSURE));
        }
    }

    /**
     * The closure after a pop (spec §7.4): the ring around our box at every level of it, feet first; then a block above
     * the first top-level ring cell, to place the roof against; then the cells over our head.
     */
    static List<Cell> closure(ShellSnapshot s) {
        Set<Cell> body = s.body();
        Set<Cell> cells = new LinkedHashSet<>();
        for (int y = s.feetLevel(); y <= s.headLevel(); y++) cells.addAll(ring(body, y));
        List<Cell> top = ring(body, s.headLevel());
        if (!top.isEmpty()) cells.add(top.getFirst().up());
        for (Cell c : body) {
            if (c.y() == s.headLevel()) cells.add(c.up());
        }
        return List.copyOf(cells);
    }

    /** The cells next to our box at this level, in the order of each body cell's sides. */
    static List<Cell> ring(Set<Cell> body, int y) {
        Set<Cell> ring = new LinkedHashSet<>();
        for (Cell c : body) {
            if (c.y() != y) continue;
            for (Cell side : c.sides()) {
                if (!body.contains(side)) ring.add(side);
            }
        }
        return List.copyOf(ring);
    }
}
