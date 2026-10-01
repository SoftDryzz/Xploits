package com.xploits.printer.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;

/**
 * The whole build, position by position (printer spec §4, M12): a status byte and a material index per position of the
 * enabled sub-region boxes, a scan cursor the adapter advances a budget of positions a tick, and counters kept on every
 * change. A position covered by two boxes belongs to the first; in the later boxes it is a {@code SHADOW}, never counted.
 */
public final class BuildIndex {
    public enum Status { SHADOW, OUTSIDE, UNSCANNED, UNKNOWN, AIR_TARGET, LATER, MATCHES, CONVERTED, MISSING, WRONG, KEPT, SKIPPED }

    public record Counts(int unscanned, int unknown, int airTargets, int later, int matches, int converted, int missing,
                         int wrong, int kept, int skipped) {
    }

    private static final Status[] STATUSES = Status.values();

    private final List<GridBox> boxes;
    private final byte[][] status;
    private final short[][] material;
    private final List<String> materials = new ArrayList<>();
    private final Map<String, Short> materialIds = new HashMap<>();
    private final int[] counts = new int[STATUSES.length];
    /** y → material index → {missing, wrong}. */
    private final TreeMap<Integer, Map<Short, int[]>> layers = new TreeMap<>();
    private final Map<Short, Integer> matches = new HashMap<>();
    private int cursorBox;
    private int cursorIndex;
    private int passes;

    public BuildIndex(List<GridBox> boxes) {
        if (boxes.isEmpty()) throw new IllegalArgumentException("a build needs at least one box");
        this.boxes = List.copyOf(boxes);
        status = new byte[boxes.size()][];
        material = new short[boxes.size()][];
        for (int b = 0; b < boxes.size(); b++) {
            long volume = boxes.get(b).volume();
            if (volume > Integer.MAX_VALUE) throw new IllegalArgumentException("a box is too large to index");
            status[b] = new byte[(int) volume];
            material[b] = new short[(int) volume];
            Arrays.fill(status[b], (byte) Status.UNSCANNED.ordinal());
            Arrays.fill(material[b], (short) -1);
            for (int a = 0; a < b; a++) shadow(b, boxes.get(a));
            for (byte s : status[b]) {
                if (s == Status.UNSCANNED.ordinal()) counts[Status.UNSCANNED.ordinal()]++;
            }
        }
    }

    /** The sum of the boxes' volumes: an upper bound of the build's size, for the size refusal. */
    public static long volume(List<GridBox> boxes) {
        long v = 0;
        for (GridBox b : boxes) v += b.volume();
        return v;
    }

    private void shadow(int b, GridBox earlier) {
        GridBox box = boxes.get(b);
        if (!box.intersects(earlier)) return;
        for (int y = Math.max(box.min().y(), earlier.min().y()); y <= Math.min(box.max().y(), earlier.max().y()); y++) {
            for (int z = Math.max(box.min().z(), earlier.min().z()); z <= Math.min(box.max().z(), earlier.max().z()); z++) {
                for (int x = Math.max(box.min().x(), earlier.min().x()); x <= Math.min(box.max().x(), earlier.max().x()); x++) {
                    status[b][index(box, x, y, z)] = (byte) Status.SHADOW.ordinal();
                }
            }
        }
    }

    private static int index(GridBox box, int x, int y, int z) {
        return ((y - box.min().y()) * box.sizeZ() + (z - box.min().z())) * box.sizeX() + (x - box.min().x());
    }

    private static Pos pos(GridBox box, int i) {
        int x = i % box.sizeX();
        int z = (i / box.sizeX()) % box.sizeZ();
        int y = i / (box.sizeX() * box.sizeZ());
        return new Pos(box.min().x() + x, box.min().y() + y, box.min().z() + z);
    }

    private int owner(Pos p) {
        for (int b = 0; b < boxes.size(); b++) {
            if (boxes.get(b).contains(p)) return b;
        }
        return -1;
    }

    public boolean contains(Pos p) {
        return owner(p) >= 0;
    }

    public Status status(Pos p) {
        int b = owner(p);
        if (b < 0) return Status.OUTSIDE;
        return STATUSES[status[b][index(boxes.get(b), p.x(), p.y(), p.z())]];
    }

    public void set(Pos p, Status s, String mat) {
        if (s == Status.SHADOW || s == Status.UNSCANNED) throw new IllegalArgumentException("not a scan result: " + s);
        if ((s == Status.MISSING || s == Status.WRONG || s == Status.MATCHES) && mat == null) {
            throw new IllegalArgumentException(s + " needs its material");
        }
        int b = owner(p);
        if (b < 0) throw new IllegalArgumentException("a position outside the build");
        int i = index(boxes.get(b), p.x(), p.y(), p.z());
        Status old = STATUSES[status[b][i]];
        short oldMat = material[b][i];
        short newMat = mat == null ? -1 : materialId(mat);
        if (old == s && oldMat == newMat) return;
        count(old, oldMat, p.y(), -1);
        count(s, newMat, p.y(), 1);
        status[b][i] = (byte) s.ordinal();
        material[b][i] = newMat;
    }

    private short materialId(String mat) {
        Short id = materialIds.get(mat);
        if (id != null) return id;
        if (materials.size() >= Short.MAX_VALUE) throw new IllegalStateException("too many materials");
        short next = (short) materials.size();
        materials.add(mat);
        materialIds.put(mat, next);
        return next;
    }

    private void count(Status s, short mat, int y, int delta) {
        counts[s.ordinal()] += delta;
        if (s == Status.MATCHES && mat >= 0) matches.merge(mat, delta, Integer::sum);
        if (s != Status.MISSING && s != Status.WRONG) return;
        Map<Short, int[]> layer = layers.computeIfAbsent(y, k -> new HashMap<>());
        int[] c = layer.computeIfAbsent(mat, k -> new int[2]);
        c[s == Status.MISSING ? 0 : 1] += delta;
        if (c[0] == 0 && c[1] == 0) layer.remove(mat);
        if (layer.isEmpty()) layers.remove(y);
    }

    public Counts counts() {
        return new Counts(counts[Status.UNSCANNED.ordinal()], counts[Status.UNKNOWN.ordinal()],
            counts[Status.AIR_TARGET.ordinal()], counts[Status.LATER.ordinal()], counts[Status.MATCHES.ordinal()],
            counts[Status.CONVERTED.ordinal()], counts[Status.MISSING.ordinal()], counts[Status.WRONG.ordinal()],
            counts[Status.KEPT.ordinal()], counts[Status.SKIPPED.ordinal()]);
    }

    /** Missing (and, with {@code fixWrong}, wrong) positions per material, sorted by material. */
    public Map<String, Integer> remaining(boolean fixWrong) {
        Map<String, Integer> m = new TreeMap<>();
        for (Map<Short, int[]> layer : layers.values()) {
            layer.forEach((mat, c) -> {
                int n = c[0] + (fixWrong ? c[1] : 0);
                if (n > 0) m.merge(materials.get(mat), n, Integer::sum);
            });
        }
        return m;
    }

    /** Matching positions per material. */
    public Map<String, Integer> placed() {
        Map<String, Integer> m = new TreeMap<>();
        matches.forEach((mat, n) -> {
            if (n > 0) m.put(materials.get(mat), n);
        });
        return m;
    }

    public OptionalInt lowestLayer(Set<String> carried, boolean fixWrong) {
        for (Map.Entry<Integer, Map<Short, int[]>> e : layers.entrySet()) {
            for (Map.Entry<Short, int[]> m : e.getValue().entrySet()) {
                if (!carried.contains(materials.get(m.getKey()))) continue;
                if (m.getValue()[0] > 0 || (fixWrong && m.getValue()[1] > 0)) return OptionalInt.of(e.getKey());
            }
        }
        return OptionalInt.empty();
    }

    public List<Pos> actionableAt(int y, Set<String> carried, boolean fixWrong) {
        List<Pos> out = new ArrayList<>();
        for (int b = 0; b < boxes.size(); b++) {
            GridBox box = boxes.get(b);
            if (y < box.min().y() || y > box.max().y()) continue;
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) {
                    int i = index(box, x, y, z);
                    Status s = STATUSES[status[b][i]];
                    boolean wanted = s == Status.MISSING || (fixWrong && s == Status.WRONG);
                    if (wanted && carried.contains(materials.get(material[b][i]))) out.add(new Pos(x, y, z));
                }
            }
        }
        return out;
    }

    public Optional<Pos> nearestUnknown(Point from) {
        Pos best = null;
        double bestD = Double.POSITIVE_INFINITY;
        for (int b = 0; b < boxes.size(); b++) {
            GridBox box = boxes.get(b);
            for (int i = 0; i < status[b].length; i++) {
                byte s = status[b][i];
                if (s != Status.UNKNOWN.ordinal() && s != Status.UNSCANNED.ordinal()) continue;
                Pos p = pos(box, i);
                double d = p.distanceSq(from);
                if (d < bestD) {
                    bestD = d;
                    best = p;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** Up to {@code budget} owned positions from the cursor on; a call stops where a full pass ends. */
    public List<Pos> nextToScan(int budget) {
        List<Pos> out = new ArrayList<>(Math.min(budget, 4096));
        while (out.size() < budget) {
            GridBox box = boxes.get(cursorBox);
            if (cursorIndex >= status[cursorBox].length) {
                cursorIndex = 0;
                cursorBox++;
                if (cursorBox == boxes.size()) {
                    cursorBox = 0;
                    passes++;
                    break;
                }
                continue;
            }
            int i = cursorIndex++;
            if (status[cursorBox][i] == Status.SHADOW.ordinal()) continue;
            out.add(pos(box, i));
        }
        return out;
    }

    /** Full passes the scan cursor has completed. */
    public int passes() {
        return passes;
    }
}
