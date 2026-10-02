package com.xploits.restock.core;

import com.xploits.printer.core.Point;
import com.xploits.printer.core.Pos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * The containers the player marked in one server or world (restock spec §4): one mark per container and dimension, with
 * the spot the player stood on when marking it. Pressing the key on a marked container unmarks it, from wherever it is
 * pressed. Lists give the dimension and the distance, never the position. The file holds positions, like stash-keeper's
 * index; it is never printed.
 */
public final class MarkBook {
    public record Mark(String dimension, Pos container, Pos stand) {
    }

    /** One line of a list: the dimension and the distance in whole blocks; −1 in another dimension. */
    public record Listed(String dimension, long distance) {
    }

    private static final String HEADER = "xploits-restock-marks 1";

    private final List<Mark> marks;

    public MarkBook(List<Mark> marks) {
        this.marks = new ArrayList<>(marks);
    }

    /** Marks {@code m}, or unmarks the mark on the same container in the same dimension; true when it is now marked. */
    public boolean toggle(Mark m) {
        Iterator<Mark> it = marks.iterator();
        while (it.hasNext()) {
            Mark old = it.next();
            if (old.dimension().equals(m.dimension()) && old.container().equals(m.container())) {
                it.remove();
                return false;
            }
        }
        marks.add(m);
        return true;
    }

    public int size() {
        return marks.size();
    }

    public List<Mark> all() {
        return List.copyOf(marks);
    }

    public List<Mark> in(String dimension) {
        return marks.stream().filter(m -> m.dimension().equals(dimension)).toList();
    }

    /** Removes every mark; how many there were. */
    public int clear() {
        int n = marks.size();
        marks.clear();
        return n;
    }

    /** This dimension's marks nearest first, then the others by dimension. */
    public List<Listed> listed(String dimension, Point player) {
        List<Listed> here = new ArrayList<>();
        List<Listed> away = new ArrayList<>();
        for (Mark m : marks) {
            if (m.dimension().equals(dimension)) {
                here.add(new Listed(dimension, Math.round(Math.sqrt(m.container().distanceSq(player)))));
            } else {
                away.add(new Listed(m.dimension(), -1));
            }
        }
        here.sort(Comparator.comparingLong(Listed::distance));
        away.sort(Comparator.comparing(Listed::dimension));
        List<Listed> out = new ArrayList<>(here);
        out.addAll(away);
        return out;
    }

    public List<String> toLines() {
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        for (Mark m : marks) {
            Pos c = m.container();
            Pos s = m.stand();
            lines.add(m.dimension() + " " + c.x() + " " + c.y() + " " + c.z() + " " + s.x() + " " + s.y() + " " + s.z());
        }
        return lines;
    }

    /** The book a file holds; empty when the file is not one this class wrote. */
    public static Optional<MarkBook> fromLines(List<String> lines) {
        if (lines.isEmpty() || !lines.get(0).equals(HEADER)) return Optional.empty();
        List<Mark> marks = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] p = line.split(" ");
            if (p.length != 7 || p[0].isBlank()) return Optional.empty();
            try {
                marks.add(new Mark(p[0], new Pos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])),
                    new Pos(Integer.parseInt(p[4]), Integer.parseInt(p[5]), Integer.parseInt(p[6]))));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return Optional.of(new MarkBook(marks));
    }
}
