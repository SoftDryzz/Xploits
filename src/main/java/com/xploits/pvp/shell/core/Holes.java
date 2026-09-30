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
    /** How much a threat must weigh before we leave where we stand: a hit this size, twice the danger line. */
    public static final double MOVE_THREAT = 4.0;
    private static final double PATH_STEP = 0.25;

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
     * pressing a movement key, and the worst threat weighs at least {@link #MOVE_THREAT}.
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

    /** A straight walk from our feet to the hole: room for our body at every step and ground under it, up to the hole. */
    static boolean pathClear(ShellSnapshot s, Cell target) {
        Vec from = s.feet();
        double tx = target.x() + 0.5;
        double tz = target.z() + 0.5;
        double length = Math.hypot(tx - from.x(), tz - from.z());
        int steps = Math.max(1, (int) Math.ceil(length / PATH_STEP));
        int level = s.feetLevel();
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            int x = (int) Math.floor(from.x() + (tx - from.x()) * t);
            int z = (int) Math.floor(from.z() + (tz - from.z()) * t);
            if (x == target.x() && z == target.z()) continue;
            for (int dy = 0; dy <= 1; dy++) {
                BlockKind k = s.kind(new Cell(x, level + dy, z));
                if (k != BlockKind.AIR && k != BlockKind.REPLACEABLE) return false;
            }
            if (!s.kind(new Cell(x, level - 1, z)).supports()) return false;
        }
        return true;
    }
}
