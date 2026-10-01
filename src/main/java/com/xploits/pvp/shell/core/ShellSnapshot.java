package com.xploits.pvp.shell.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One tick of the world around us, as surround++ plans from it (spec §4.1). Every position is relative to the player's
 * feet block ({@link Cell}, {@link Vec}): nothing here, or built from it, carries an absolute position.
 *
 * <p>The adapter reads every cell of the box ({@link #RADIUS} around, {@link #BELOW} below, {@link #ABOVE} above); a
 * cell inside it that was not given reads as air, one outside it as {@link BlockKind#UNKNOWN}.
 */
public final class ShellSnapshot {
    public static final int RADIUS = 5;
    public static final int BELOW = 2;
    public static final int ABOVE = 4;
    /** A player's box in 1.21.11: 0.6 wide, 1.8 tall, eyes at 1.62 when standing. */
    public static final double HALF_WIDTH = 0.3;
    public static final double HEIGHT = 1.8;
    public static final double EYE_HEIGHT = 1.62;
    private static final double EPSILON = 1e-6;

    private final Map<Cell, BlockKind> blocks;
    private final Set<Cell> occupied;
    private final Vec feet;
    private final boolean onGround;
    private final List<Vec> hostileEyes;
    private final List<Vec> hostileFeet;
    private final List<StandingCrystal> crystals;
    private final Map<Cell, Integer> mining;
    private final double health;
    private final HurtWindow window;
    private final int pingTicks;
    private final int obsidian;
    private final int cryingObsidian;
    private final Optional<Cell> auraSpot;
    private final ShellSettings settings;
    private final Set<Cell> body;

    private ShellSnapshot(Builder b) {
        if (!(b.feet.x() >= 0 && b.feet.x() < 1 && b.feet.y() >= 0 && b.feet.y() < 1 && b.feet.z() >= 0 && b.feet.z() < 1)) {
            throw new IllegalArgumentException("the feet are not inside their own block");
        }
        if (!Double.isFinite(b.health) || b.health < 0) throw new IllegalArgumentException("health " + b.health);
        if (b.pingTicks < 0) throw new IllegalArgumentException("ping ticks " + b.pingTicks);
        if (b.obsidian < 0 || b.cryingObsidian < 0) throw new IllegalArgumentException("a negative block count");
        for (int stage : b.mining.values()) {
            if (stage < 0 || stage > 9) throw new IllegalArgumentException("mining stage " + stage);
        }
        blocks = Map.copyOf(b.blocks);
        occupied = Set.copyOf(b.occupied);
        feet = b.feet;
        onGround = b.onGround;
        hostileEyes = List.copyOf(b.hostileEyes);
        hostileFeet = List.copyOf(b.hostileFeet);
        crystals = List.copyOf(b.crystals);
        mining = Map.copyOf(b.mining);
        health = b.health;
        window = Objects.requireNonNull(b.window, "window");
        pingTicks = b.pingTicks;
        obsidian = b.obsidian;
        cryingObsidian = b.cryingObsidian;
        auraSpot = Optional.ofNullable(b.auraSpot);
        settings = Objects.requireNonNull(b.settings, "settings");
        body = bodyOf(feet);
    }

    private static Set<Cell> bodyOf(Vec feet) {
        Set<Cell> cells = new LinkedHashSet<>();
        int x0 = (int) Math.floor(feet.x() - HALF_WIDTH);
        int x1 = (int) Math.floor(feet.x() + HALF_WIDTH - EPSILON);
        int z0 = (int) Math.floor(feet.z() - HALF_WIDTH);
        int z1 = (int) Math.floor(feet.z() + HALF_WIDTH - EPSILON);
        int y0 = (int) Math.floor(feet.y());
        int y1 = (int) Math.floor(feet.y() + HEIGHT - EPSILON);
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) cells.add(new Cell(x, y, z));
            }
        }
        return Collections.unmodifiableSet(cells);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Whether the adapter reads this cell. */
    public static boolean inBox(Cell c) {
        return Math.abs(c.x()) <= RADIUS && Math.abs(c.z()) <= RADIUS && c.y() >= -BELOW && c.y() <= ABOVE;
    }

    public BlockKind kind(Cell c) {
        BlockKind kind = blocks.get(c);
        if (kind != null) return kind;
        return inBox(c) ? BlockKind.AIR : BlockKind.UNKNOWN;
    }

    /** The cells our own box overlaps. */
    public Set<Cell> body() {
        return body;
    }

    public Vec feet() {
        return feet;
    }

    public Vec eye() {
        return feet.plus(0, EYE_HEIGHT, 0);
    }

    public boolean onGround() {
        return onGround;
    }

    /** Whether an entity's box overlaps the cell: another player, a crystal, or us. */
    public boolean occupied(Cell c) {
        return body.contains(c) || occupied.contains(c);
    }

    /** The same, leaving us out. */
    public boolean occupiedByOthers(Cell c) {
        return occupied.contains(c);
    }

    /** The level of our feet block, 0 unless our feet stand below it. */
    public int feetLevel() {
        return (int) Math.floor(feet.y());
    }

    /** The highest level our box reaches. */
    public int headLevel() {
        return (int) Math.floor(feet.y() + HEIGHT - EPSILON);
    }

    public boolean inWeb() {
        for (Cell c : body) {
            if (kind(c) == BlockKind.COBWEB) return true;
        }
        return false;
    }

    /** Whether our box reaches into more than one block at feet level. */
    public boolean sticksOut() {
        int cells = 0;
        for (Cell c : body) {
            if (c.y() == feetLevel()) cells++;
        }
        return cells > 1;
    }

    public List<Vec> hostileEyes() {
        return hostileEyes;
    }

    public List<Vec> hostileFeet() {
        return hostileFeet;
    }

    public List<StandingCrystal> crystals() {
        return crystals;
    }

    /** Blocks someone else is mining, with the stage the server last sent (0 to 9). */
    public Map<Cell, Integer> mining() {
        return mining;
    }

    /** Health plus absorption. */
    public double health() {
        return health;
    }

    public HurtWindow window() {
        return window;
    }

    /** The round trip to the server, in ticks. */
    public int pingTicks() {
        return pingTicks;
    }

    /** Obsidian in the hotbar. */
    public int obsidian() {
        return obsidian;
    }

    /** Crying obsidian in the hotbar. */
    public int cryingObsidian() {
        return cryingObsidian;
    }

    /** Where crystal-aura++ is placing its crystal right now, if it is. */
    public Optional<Cell> auraSpot() {
        return auraSpot;
    }

    public ShellSettings settings() {
        return settings;
    }

    public static final class Builder {
        private final Map<Cell, BlockKind> blocks = new HashMap<>();
        private final Set<Cell> occupied = new HashSet<>();
        private Vec feet = new Vec(0.5, 0, 0.5);
        private boolean onGround = true;
        private final List<Vec> hostileEyes = new ArrayList<>();
        private final List<Vec> hostileFeet = new ArrayList<>();
        private final List<StandingCrystal> crystals = new ArrayList<>();
        private final Map<Cell, Integer> mining = new HashMap<>();
        private double health = 36;
        private HurtWindow window = HurtWindow.NONE;
        private int pingTicks;
        private int obsidian = 64;
        private int cryingObsidian = 64;
        private Cell auraSpot;
        private ShellSettings settings = ShellSettings.DEFAULTS;

        private Builder() {
        }

        public Builder block(Cell c, BlockKind kind) {
            blocks.put(c, kind);
            return this;
        }

        public Builder block(int x, int y, int z, BlockKind kind) {
            return block(new Cell(x, y, z), kind);
        }

        /** Every cell between the two corners, both included. */
        public Builder fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockKind kind) {
            for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
                for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                    for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) block(x, y, z, kind);
                }
            }
            return this;
        }

        /** The layer right under the feet, across the whole box. */
        public Builder floor(BlockKind kind) {
            return fill(-RADIUS, -1, -RADIUS, RADIUS, -1, RADIUS, kind);
        }

        /** An entity other than us overlaps this cell. */
        public Builder occupied(Cell c) {
            occupied.add(c);
            return this;
        }

        public Builder feet(Vec v) {
            feet = v;
            return this;
        }

        public Builder onGround(boolean v) {
            onGround = v;
            return this;
        }

        /** A player who may attack us, standing with its feet here, eyes a standing player's height up. */
        public Builder hostile(Vec feetAt) {
            return hostile(feetAt, feetAt.plus(0, EYE_HEIGHT, 0));
        }

        /** A player who may attack us, with its real eyes (sneaking, swimming). */
        public Builder hostile(Vec feetAt, Vec eyes) {
            hostileFeet.add(feetAt);
            hostileEyes.add(eyes);
            return this;
        }

        public Builder crystal(StandingCrystal c) {
            crystals.add(c);
            return this;
        }

        public Builder mining(Cell c, int stage) {
            mining.put(c, stage);
            return this;
        }

        public Builder health(double v) {
            health = v;
            return this;
        }

        public Builder window(HurtWindow w) {
            window = w;
            return this;
        }

        public Builder pingTicks(int v) {
            pingTicks = v;
            return this;
        }

        public Builder obsidian(int v) {
            obsidian = v;
            return this;
        }

        public Builder cryingObsidian(int v) {
            cryingObsidian = v;
            return this;
        }

        /** Where crystal-aura++ is placing now; null for nowhere. */
        public Builder auraSpot(Cell c) {
            auraSpot = c;
            return this;
        }

        public Builder settings(ShellSettings s) {
            settings = s;
            return this;
        }

        public ShellSnapshot build() {
            return new ShellSnapshot(this);
        }
    }
}
