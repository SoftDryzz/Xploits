package com.xploits.pvp.shell.core;

import java.util.Optional;

/**
 * Holes, and the one to walk into (surround++ spec §7.1). Holes are entered from above: only those one block below our
 * feet level are walked into (a hole at our own level has a wall in the way). Scored by bedrock walls first (they cannot
 * be mined), then fewer crystal spots on its walls (obsidian with air on top: the face-place spots once we are in), then
 * fewer walls with a base under them (a mined wall there could hold a crystal), then distance.
 */
public final class Holes {
    public static final double MOVE_RADIUS = 3.0;
    /**
     * The full hit a spot must deal before we leave where we stand (its damage with its base placed, times the share of
     * its rays still open; not the planner's weight): twice the danger line.
     */
    public static final double MOVE_THREAT = 4.0;
    private static final double PATH_STEP = 0.25;
    private static final double EPSILON = 1e-6;

    private Holes() {
    }

    /** Air to stand in, air above it, something to stand on, and four hard walls. */
    public static boolean isHole(ShellSnapshot s, Cell c) {
        if (s.kind(c) != BlockKind.AIR || s.kind(c.up()) != BlockKind.AIR || !s.kind(c.down()).supports()) return false;
        for (Cell wall : c.sides()) {
            if (!s.kind(wall).isHard()) return false;
        }
        return true;
    }

    /** We stand in one: our box inside one block at feet level, and that block a hole. */
    public static boolean inHole(ShellSnapshot s) {
        Cell feet = null;
        int cells = 0;
        for (Cell c : s.body()) {
            if (c.y() != s.feetLevel()) continue;
            feet = c;
            cells++;
        }
        return cells == 1 && isHole(s, feet);
    }

    /** How good a hole is; the smaller the better. */
    public record Score(Cell cell, int bedrock, int wallSpots, int baseGaps, double distance) implements Comparable<Score> {
        @Override
        public int compareTo(Score o) {
            if (bedrock != o.bedrock) return Integer.compare(o.bedrock, bedrock);
            if (wallSpots != o.wallSpots) return Integer.compare(wallSpots, o.wallSpots);
            if (baseGaps != o.baseGaps) return Integer.compare(baseGaps, o.baseGaps);
            return Double.compare(distance, o.distance);
        }
    }

    static Score score(ShellSnapshot s, Cell c, double distance) {
        int bedrock = 0;
        int wallSpots = 0;
        int baseGaps = 0;
        for (Cell wall : c.sides()) {
            if (s.kind(wall) == BlockKind.BEDROCK) bedrock++;
            if (s.kind(wall).isBase() && s.kind(wall.up()) == BlockKind.AIR) wallSpots++;
            if (s.kind(wall.down()).isBase()) baseGaps++;
        }
        return new Score(c, bedrock, wallSpots, baseGaps, distance);
    }

    /**
     * The hole to walk into now, if we should: the setting is on, we stand on the ground in the open, not in a web, not
     * pressing a movement key, and {@code worstThreat}, the biggest full hit among the threats (a spot's damage with its
     * base placed, times the share of its rays still open, whether or not the base is there yet), is at least
     * {@link #MOVE_THREAT}.
     */
    public static Optional<Cell> target(ShellSnapshot s, double worstThreat, boolean userKeys) {
        if (!s.settings().moveToHole() || !s.onGround() || userKeys || s.inWeb() || worstThreat < MOVE_THREAT || inHole(s)) {
            return Optional.empty();
        }
        int y = s.feetLevel() - 1;
        int r = (int) Math.ceil(MOVE_RADIUS);
        Score best = null;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                Cell c = new Cell(x, y, z);
                if (!isHole(s, c) || s.occupiedByOthers(c)) continue;
                double distance = s.feet().horizontalDistance(c.centre());
                if (distance > MOVE_RADIUS || !pathClear(s, c)) continue;
                Score score = score(s, c, distance);
                if (best == null || score.compareTo(best) < 0) best = score;
            }
        }
        return best == null ? Optional.empty() : Optional.of(best.cell());
    }

    /**
     * A straight walk from our feet to the hole, for a box as wide as ours: at every step, room at feet and head level
     * and ground under every block the box overlaps; in the hole's own column, room at head level over it (the hole and
     * the cell above it are the hole's own checks).
     */
    static boolean pathClear(ShellSnapshot s, Cell target) {
        Vec from = s.feet();
        double tx = target.x() + 0.5;
        double tz = target.z() + 0.5;
        double length = Math.hypot(tx - from.x(), tz - from.z());
        int steps = Math.max(1, (int) Math.ceil(length / PATH_STEP));
        int level = s.feetLevel();
        double half = ShellSnapshot.HALF_WIDTH;
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            double x = from.x() + (tx - from.x()) * t;
            double z = from.z() + (tz - from.z()) * t;
            for (int cx = (int) Math.floor(x - half); cx <= (int) Math.floor(x + half - EPSILON); cx++) {
                for (int cz = (int) Math.floor(z - half); cz <= (int) Math.floor(z + half - EPSILON); cz++) {
                    if (cx == target.x() && cz == target.z()) {
                        if (!passable(s.kind(new Cell(cx, level + 1, cz)))) return false;
                        continue;
                    }
                    if (!passable(s.kind(new Cell(cx, level, cz))) || !passable(s.kind(new Cell(cx, level + 1, cz)))) return false;
                    if (!s.kind(new Cell(cx, level - 1, cz)).supports()) return false;
                }
            }
        }
        return true;
    }

    private static boolean passable(BlockKind kind) {
        return kind == BlockKind.AIR || kind == BlockKind.REPLACEABLE;
    }
}
