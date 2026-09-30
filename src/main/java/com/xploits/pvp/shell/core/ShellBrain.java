package com.xploits.pvp.shell.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * surround++'s decision for one tick (spec §4-§7), in the order that matters: the crystal to break next to us (the one in
 * a mined gap is the worst there is); then a walk under way goes on, or a better hole is chosen, and while walking
 * nothing is built (a block would stand in the way); otherwise the block plan, then an opponent's hole with what the
 * budget leaves; then whether to centre and whether to burrow.
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
    /** A spot at our head this heavy, still open after the plan, is what burrow is for. */
    public static final double BURROW_THREAT = 6.0;

    /**
     * What a tick knows about us beyond the world.
     *
     * @param userKeys  a movement key, jump or sneak held by the player's own hand
     * @param yaw       where we face, for the walk's keys
     * @param walkingTo the hole the walk under way is heading for, relative to our feet block now; empty when lost
     * @param popped    one of our totems popped since the last tick
     */
    public record Motion(boolean userKeys, float yaw, Optional<Cell> walkingTo, boolean popped) {
        public static Motion still() {
            return new Motion(false, 0f, Optional.empty(), false);
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
            HoleWalk.Step step = walk.step(motion.walkingTo().map(c -> towards(s, c)), motion.userKeys(), s.inWeb(), motion.yaw());
            return new ShellTick(List.of(), toBreak, step.keys(), Optional.empty(), step.stopped(), false, false, status(map, s));
        }
        walk.idle();
        List<Threat> threats = map.threats();
        double worst = threats.isEmpty() ? 0 : threats.getFirst().weighted();
        if (walk.ready()) {
            Optional<Cell> hole = Holes.target(s, worst, motion.userKeys());
            if (hole.isPresent()) {
                Set<HoleWalk.Key> keys = walk.start(towards(s, hole.get()), motion.yaw());
                return new ShellTick(List.of(), toBreak, keys, hole, false, false, false, status(map, s));
            }
        }

        ShellPlanner.Result plan = ShellPlanner.plan(map, s.settings().blocksPerTick(), hold);
        if (plan.auraOverride()) auraOverrides++;
        List<Placement> placements = new ArrayList<>(plan.placements());
        denyHole(plan.map(), placements, s);
        List<Threat> left = open(plan.map());
        boolean centre = !motion.userKeys() && s.onGround() && centreCooldown == 0 && s.sticksOut() && (hold || !left.isEmpty());
        if (centre) centreCooldown = CENTRE_COOLDOWN_TICKS;
        boolean burrow = s.settings().burrow() && burrowCooldown == 0 && s.onGround() && Holes.inHole(s)
            && left.stream().anyMatch(t -> t.spot().y() >= s.headLevel() && t.weighted() >= BURROW_THREAT);
        if (burrow) burrowCooldown = BURROW_COOLDOWN_TICKS;
        return new ShellTick(placements, toBreak, Set.of(), Optional.empty(), false, centre, burrow,
            new ShellStatus(headCovered(plan.map()), left.size(), underAttack(s)));
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
