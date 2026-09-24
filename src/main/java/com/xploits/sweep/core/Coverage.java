package com.xploits.sweep.core;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * The chunks the player has already seen, so that the sweep plans only over what is missing (Nether
 * Sweep spec §5.1). It keeps no record of its own: {@code NewerNewChunks} writes while flying, so
 * this only has to read what already exists, not accumulate it by itself.
 *
 * <p>This class does not touch the disk. It receives the lines already read -from one or several
 * files, as the adapter decides- and only understands the format: one line per chunk, with its chunk
 * coordinates separated by a comma. Who reads those five {@code NewerNewChunks} files and in which
 * order is the adapter's business; there is no path and no file name here.
 *
 * <p><b>A garbage line cannot drop the reading of the rest, let alone make an already seen chunk
 * look unseen.</b> {@code NewerNewChunks} writes these files while the player flies, and an abrupt
 * client shutdown leaves them half-written: the last line can end up empty, cut in half, or with a
 * field that is not a number. {@link #ofLines} ignores that line and carries on with the following
 * ones -it never aborts the whole read because of a single broken line-, because the two ways of
 * failing here are worse than losing a stray chunk: dropping the whole read sends the player to
 * repeat terrain already seen, and reading a half line as if it were a valid chunk can count as
 * combed an area that was never looked at.
 *
 * <p><b>And the second of those two cannot be closed by {@link #ofLines}</b>, because a cut line can
 * parse perfectly well: {@code "-412,1087"} truncated to {@code "-412,1"} is a valid {@code x,z} of
 * a chunk that was never seen. The only thing that tells a finished file apart from a cut one lies
 * outside the lines -the final newline-, so the fix lives in {@link #ofFileContent(String)}, which
 * receives the whole content. <b>It is the right entry point to read a file;</b> {@code ofLines}
 * stays for whoever already has the lines by some other route and knows they are complete.
 */
public final class Coverage {
    private final Set<ChunkPos> seen;

    private Coverage(Set<ChunkPos> seen) {
        this.seen = seen;
    }

    /** No chunk seen at all: the starting point when there is no file to read. */
    public static Coverage empty() {
        return new Coverage(Set.of());
    }

    /**
     * Reads the lines of one of the {@code NewerNewChunks} files, one per chunk. Every valid line has
     * exactly two comma-separated fields, {@code x,z} in chunk coordinates; anything else -an empty
     * line, one with spaces, one with a single field, one with a field that does not parse as an
     * integer, or a null line- is skipped instead of breaking the read.
     *
     * <p>The null line is on that list and it is not a minor detail: the javadoc promises to skip
     * "anything else", and a promise like that in the core is kept in full or is worth nothing.
     * Without it, a list with a null inside blew up with a {@code NullPointerException} from inside
     * the read, right on the path whose stated purpose is never to abort because of a bad line and
     * whose failure costs replanning terrain already seen.
     *
     * @throws NullPointerException if {@code lines} is null: that is not a bad line, it is having
     *                              brought nothing to read
     */
    public static Coverage ofLines(Iterable<String> lines) {
        if (lines == null) {
            throw new NullPointerException("the lines to read are needed: a null list is not an"
                + " empty file, it is having read nothing");
        }

        Set<ChunkPos> chunks = new HashSet<>();
        for (String line : lines) {
            ChunkPos pos = parseLine(line);
            if (pos != null) {
                chunks.add(pos);
            }
        }
        return new Coverage(chunks);
    }

    /**
     * Reads the whole content of one of the {@code NewerNewChunks} files, <b>dropping the last line
     * if the file does not end in a newline</b>.
     *
     * <p>It is the fix for the failure this class's javadoc names and that {@link #ofLines} cannot
     * close. {@code NewerNewChunks} writes while the player flies; if the client closes abruptly
     * mid-write, the last line is left cut. A cut line that does not parse is already skipped
     * -{@code "-412,"} or {@code "-41"}-, but <b>a cut line can parse perfectly well</b>: what was
     * going to be {@code "-412,1087"} is left as {@code "-412,1"}, which is a valid {@code x,z} of a
     * chunk that was never seen. Marking as seen a chunk that was not seen is worse than losing one
     * that was: if it was the one missing from its band, {@link SweepPlanner} skips the whole band
     * and a lane from end to end is not flown and is counted as combed anyway (spec §9).
     *
     * <p>There is no way to tell that line apart by looking at it, so <b>the file</b> is looked at:
     * the only sign that the write finished is the final newline. If it is there, every line can be
     * trusted; if it is not, the last one is dropped without further analysis.
     *
     * <p><b>What it costs when nothing has happened:</b> normally nothing. Checked on this instance's
     * files, {@code NewerNewChunks} leaves them ending in a newline, so the normal case does not lose
     * a single chunk; one is lost only in the file that really was left half-written, and losing it
     * there is exactly what is wanted. Even if it someday wrote without the final newline, the cost
     * would be one replanned chunk -flying a bit more-, which is the cheap side.
     *
     * <p>It receives the content and not a path: this class still does not touch the disk. Reading
     * the file is the adapter's job; knowing what it means that it does not end in a newline, this
     * one's.
     *
     * @throws NullPointerException if {@code content} is null
     */
    public static Coverage ofFileContent(String content) {
        if (content == null) {
            throw new NullPointerException("the coverage file content cannot be null");
        }
        if (content.isEmpty()) {
            return empty();
        }

        String[] lines = content.split(LINE_TERMINATORS, -1);
        int end = endsWithNewline(content) ? lines.length : lines.length - 1;

        Set<ChunkPos> chunks = new HashSet<>();
        for (int i = 0; i < end; i++) {
            ChunkPos pos = parseLine(lines[i]);
            if (pos != null) {
                chunks.add(pos);
            }
        }
        return new Coverage(chunks);
    }

    /**
     * How the content is split into lines: {@code \r\n} first so that a Windows line ending does not
     * count as two.
     *
     * <p>It is <b>not</b> {@code \R} on purpose, which also matches the vertical tab, the form feed
     * and three Unicode separators. Not because they are scary, but because {@link #endsWithNewline}
     * has to recognise exactly the same set: if one split on a character the other does not consider
     * a line ending, a file ending in it would be read as truncated and would lose its last good
     * line. Two lists that have to match are a source of bugs; a short list that covers what this
     * file can hold -digits, commas and newlines- is not.
     */
    private static final String LINE_TERMINATORS = "\r\n|\r|\n";

    /**
     * Whether the content ends in a newline, which is the only sign that the write of the last line
     * got to finish.
     *
     * <p>Looking at the last character is enough: of the three terminators of
     * {@link #LINE_TERMINATORS}, two are a single character and the third, {@code \r\n}, ends in one
     * of them.
     */
    private static boolean endsWithNewline(String content) {
        char last = content.charAt(content.length() - 1);
        return last == '\n' || last == '\r';
    }

    private static ChunkPos parseLine(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String[] fields = trimmed.split(",", -1);
        if (fields.length != 2) {
            return null;
        }
        try {
            int x = Integer.parseInt(fields[0].trim());
            int z = Integer.parseInt(fields[1].trim());
            return new ChunkPos(x, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The union of several coverage sets, without duplicates. {@code NewerNewChunks} spreads what
     * was seen across five different files; this is what joins their five reads into a single answer
     * to "has this chunk been seen yet?".
     *
     * <p>An empty collection -no file to read at all- gives {@link #empty()}, not an error: having no
     * previous record means starting from scratch, not that something failed.
     *
     * <p>And for the same reason <b>a null read inside the collection is skipped</b> instead of
     * dropping the whole union: it is the same criterion {@link #ofLines} applies to a bad line, and
     * the cost of breaking here is the same -the coverage is read as empty and the sweep replans
     * hours of terrain already seen-. Nothing having come out of one of the five files cannot take
     * down with it what did come out of the other four.
     *
     * @throws NullPointerException if {@code coverages} is null: that is not a missing file, it is
     *                              not having looked
     */
    public static Coverage merge(Collection<Coverage> coverages) {
        if (coverages == null) {
            throw new NullPointerException("the reads to merge are needed: a null collection is not"
                + " \"no file\", it is not having looked");
        }

        Set<ChunkPos> union = new HashSet<>();
        for (Coverage coverage : coverages) {
            if (coverage == null) {
                continue;
            }
            union.addAll(coverage.seen);
        }
        return new Coverage(union);
    }

    /** Whether this chunk has already been seen. */
    public boolean seen(ChunkPos pos) {
        return seen.contains(pos);
    }

    /**
     * How many distinct chunks there are in this coverage, <b>in the whole dimension</b>.
     *
     * <p>It is almost never the number to show the player. What tells them how much their previous
     * coverage saves them -and so whether the sweep is worth the hours it costs- is how many chunks
     * <b>of the area they asked for</b> were already seen, and that is {@link #seenIn(SweepArea)}.
     * This number can be larger than the whole area: with the 17,369 chunks accumulated in spec §1
     * and a new 60x60 rectangle, it would say "of the 3,600 chunks of the area, 17,369 were already
     * seen".
     */
    public int size() {
        return seen.size();
    }

    /**
     * How many chunks <b>of this area</b> have already been seen. It is the answer to "how much does
     * what I already have save me?", which is half of what the player needs to decide whether the
     * sweep is worth it; the other half is how many chunks the area has.
     *
     * <p>The area is walked and not the coverage because the area is the bounded one: the coverage of
     * a whole dimension can be much larger than the rectangle, and the other way round never matters
     * -a chunk seen outside the area does not save a single block of flight-.
     */
    public int seenIn(SweepArea area) {
        if (area == null) throw new NullPointerException("an area is needed to count inside it");

        int seenCount = 0;
        for (int x = area.minChunkX(); x <= area.maxChunkX(); x++) {
            for (int z = area.minChunkZ(); z <= area.maxChunkZ(); z++) {
                if (seen.contains(new ChunkPos(x, z))) {
                    seenCount++;
                }
            }
        }
        return seenCount;
    }
}
