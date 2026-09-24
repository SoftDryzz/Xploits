package com.xploits.sweep.core;

/**
 * A Nether chunk identified by its chunk coordinates (not block coordinates). It is the unit the
 * sweep is thought in: coverage is marked chunk by chunk, not block by block.
 */
public record ChunkPos(int x, int z) {
}
