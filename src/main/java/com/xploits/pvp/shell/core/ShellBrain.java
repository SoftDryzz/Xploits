package com.xploits.pvp.shell.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * surround++'s decision for one tick (spec §4-§7), in the order that matters: the crystal to break next to us (the one in
 * a mined gap is the worst there is); then a walk under way goes on, and while walking nothing is built (a block would
 * stand in the way); while the player holds a movement key, jump or sneak himself, nothing is built either (his keys
 * win, spec §2: a block ahead of him would wall him in) and only the crystal is broken, until the tick he lets go;
 * otherwise a better hole is chosen, or the block plan, then an opponent's hole with what the budget leaves; then
 * whether to centre and whether to burrow.
 *
 * <p>The planner ranks spots by their weight ({@link Threat#weighted}: a spot that still needs its base counts at
 * {@link ThreatMap#NEEDS_BASE_WEIGHT}); walking to a hole and burrow read a spot's full hit instead ({@link #fullHit}):
 * the damage if the crystal went there with its base placed. Before a walk starts, the biggest are measured again with
 * their raycast ({@link #REMEASURED_BEFORE_WALK}): the map may hold only a spot's distance bound.
 *
 * <p>It remembers only what a tick cannot see: the walk, the {@link #HOLD_TICKS} after a totem pop when the shell is held
 * closed, and the waits between two centrings and two burrows. Centring happens only when our box sticks out of its
 * block and something is left open (or the shell is held closed), never while a key is pressed, at most once every
 * {@link #CENTRE_COOLDOWN_TICKS}: it never pins you.
 */
public final class ShellBrain {
    public static final int HOLD_TICKS = 100;
    public static final int CENTRE_COOLDOWN_TICKS = 20;
    public static final int BURROW_COOLDOWN_TICKS = 100;
    /** A spot at our head whose full hit ({@link #fullHit}) is this big, still open after the plan, is what burrow is for. */
    public static final double BURROW_THREAT = 6.0;
    /** How many spots, the biggest full hits first, are measured again with their raycast before a walk starts. */
    public static final int REMEASURED_BEFORE_WALK = 3;
    /**
     * Blocks of horizontal movement in one tick above which the player "really walks". Vanilla, per tick: walking
     * about 0.22, sneaking about 0.065, walking in a cobweb about a quarter of walking (0.05). A player pressed
     * against a wall, or held in place in a hole whose walls stop his feet, moves about 0, and only that case is
     * meant to read as not moving. 0.03 sits well under every real walk (even the web crawl and sneaking), so a
     * walk is never read as standing, and well over the rounding noise of a player who does not move.
     */
    public static final double MOVING = 0.03;

    /**
     * What a tick knows about us beyond the world.
     *
     * @param anyKey    any key held by the player's own hand, jump and sneak included: a walk never starts or goes on
     *                  while one is ({@code HoleWalk}, {@code Holes}: his keys win)
     * @param movementKeys forward, back, left or right held by the player's own hand
     * @param moving    his feet moved horizontally more than {@link #MOVING} blocks since the last tick; with
     *                  {@code movementKeys} it means he really walks, and nothing is built
     * @param yaw       where we face, for the walk's keys
     * @param walkingTo the hole the walk under way is heading for, relative to our feet block now; empty when lost
     * @param popped    one of our totems popped since the last tick
     */
    public record Motion(boolean anyKey, boolean movementKeys, boolean moving, float yaw, Optional<Cell> walkingTo,
                         boolean popped) {
        public static Motion still() {
            return new Motion(false, false, false, 0f, Optional.empty(), false);
        }
    }

    private final HoleWalk walk = new HoleWalk();
    private int holdLeft;
    private int centreCooldown;
    private int burrowCooldown;
    private int auraOverrides;

    public void reset() {
        walk.reset();
        holdLeft = 0;
        centreCooldown = 0;
        burrowCooldown = 0;
        auraOverrides = 0;
    }

    public boolean walking() {
        return walk.walking();
    }

    /** Ticks since the last reset on which a block went where crystal-aura++ was placing, because it could take us below the floor. */
    public int auraOverrides() {
        return auraOverrides;
    }

    public ShellTick tick(ShellSnapshot s, DamageOracle oracle, Motion motion) {
        if (motion.popped()) holdLeft = HOLD_TICKS;
        boolean hold = holdLeft > 0;
        if (holdLeft > 0) holdLeft--;
        if (centreCooldown > 0) centreCooldown--;
        if (burrowCooldown > 0) burrowCooldown--;

        ThreatMap map = new ThreatMap(s, oracle);
        Optional<Integer> toBreak = CrystalBreaker.choose(s, oracle).map(StandingCrystal::id);

        if (walk.walking()) {
            HoleWalk.Step step = walk.step(motion.walkingTo().map(c -> towards(s, c)), motion.anyKey(), s.inWeb(), motion.yaw());
            return new ShellTick(List.of(), toBreak, step.keys(), Optional.empty(), step.stopped(), false, false, status(map, s));
        }
        walk.idle();
        // The player's own keys win (spec §2): while he really walks (a movement key held and his feet moving), a block
        // could wall him in, so nothing is placed, not even the closure after a pop or an opponent's hole; the crystal
        // next to him is still broken. Held in place by a web or a wall, or only sneaking or jumping, he is built for.
        if (motion.movementKeys() && motion.moving()) {
            return new ShellTick(List.of(), toBreak, Set.of(), Optional.empty(), false, false, false, status(map, s));
        }
        List<Threat> threats = map.threats();
        // Whether to leave reads the biggest full hit, not the planner's weight: the threats come by weight, so the first
        // is not necessarily the biggest.
        double biggestHit = threats.stream().mapToDouble(ShellBrain::fullHit).max().orElse(0);
        if (walk.ready()) {
            Optional<Cell> hole = Holes.target(s, biggestHit, motion.anyKey());
            if (hole.isPresent() && stillThreatened(threats, oracle)) {
                Set<HoleWalk.Key> keys = walk.start(towards(s, hole.get()), motion.yaw());
                return new ShellTick(List.of(), toBreak, keys, hole, false, false, false, status(map, s));
            }
        }

        ShellPlanner.Result plan = ShellPlanner.plan(map, s.settings().blocksPerTick(), hold);
        if (plan.auraOverride()) auraOverrides++;
        List<Placement> placements = new ArrayList<>(plan.placements());
        denyHole(plan.map(), placements, s);
        List<Threat> left = open(plan.map());
        boolean centre = !motion.anyKey() && s.onGround() && centreCooldown == 0 && s.sticksOut() && (hold || !left.isEmpty());
        if (centre) centreCooldown = CENTRE_COOLDOWN_TICKS;
        boolean burrow = s.settings().burrow() && burrowCooldown == 0 && s.onGround() && Holes.inHole(s)
            && left.stream().anyMatch(t -> t.spot().y() >= s.headLevel() && fullHit(t) >= BURROW_THREAT);
        if (burrow) burrowCooldown = BURROW_COOLDOWN_TICKS;
        return new ShellTick(placements, toBreak, Set.of(), Optional.empty(), false, centre, burrow,
            new ShellStatus(headCovered(plan.map()), left.size(), underAttack(s)));
    }

    /**
     * What a crystal on this spot would deal us once its base is there, through the rays no planned block closes: its
     * damage times its open share, never the planner's weight.
     */
    static double fullHit(Threat t) {
        return t.damage() * t.open();
    }

    /**
     * Whether a threat, measured with its raycast, still reaches {@link Holes#MOVE_THREAT}: the last check before a walk
     * starts. The map keeps a spot's distance bound, with no raycast, where the bound weighted stays under the danger line;
     * for a spot that still needs its base that is any bound under 8, over the walk's line, so a spot behind cover could
     * start a walk on its bound alone. The biggest full hits, at most {@link #REMEASURED_BEFORE_WALK}, are measured again
     * (their exact damage times their open share); one under the line on its bound cannot reach it (the exact value is
     * never above the bound) and costs no raycast.
     */
    private static boolean stillThreatened(List<Threat> threats, DamageOracle oracle) {
        return threats.stream()
            .sorted(Comparator.comparingDouble(ShellBrain::fullHit).reversed())
            .limit(REMEASURED_BEFORE_WALK)
            .filter(t -> fullHit(t) >= Holes.MOVE_THREAT)
            .anyMatch(t -> ThreatMap.checked(oracle.exact(t.spot())) * t.open() >= Holes.MOVE_THREAT);
    }

    /** The hole's centre minus our feet, on the ground. */
    private static Vec towards(ShellSnapshot s, Cell hole) {
        return new Vec(hole.x() + 0.5 - s.feet().x(), 0, hole.z() + 0.5 - s.feet().z());
    }

    private static void denyHole(ThreatMap map, List<Placement> placements, ShellSnapshot s) {
        if (placements.size() >= s.settings().blocksPerTick()) return;
        long obsidianUsed = placements.stream().filter(p -> p.material() == Material.OBSIDIAN).count();
        if (s.obsidian() - obsidianUsed <= 0) return;
        HoleDenial.choose(map).ifPresent(c -> {
            map.place(c, Material.OBSIDIAN);
            placements.add(new Placement(c, Material.OBSIDIAN, ShellReason.DENY_HOLE));
        });
    }

    private static List<Threat> open(ThreatMap map) {
        return map.threats().stream().filter(t -> t.weighted() >= Threat.MIN_DANGER).toList();
    }

    private static ShellStatus status(ThreatMap map, ShellSnapshot s) {
        return new ShellStatus(headCovered(map), open(map).size(), underAttack(s));
    }

    private static boolean headCovered(ThreatMap map) {
        ShellSnapshot s = map.snapshot();
        for (Cell c : ShellPlanner.ring(s.body(), s.headLevel())) {
            if (map.kind(c).isPlaceable()) return false;
        }
        return true;
    }

    private static int underAttack(ShellSnapshot s) {
        int count = 0;
        for (Cell c : s.mining().keySet()) {
            if (s.kind(c).isHard() && Math.abs(c.x()) <= 2 && Math.abs(c.z()) <= 2 && c.y() >= -1 && c.y() <= 2) count++;
        }
        return count;
    }
}
