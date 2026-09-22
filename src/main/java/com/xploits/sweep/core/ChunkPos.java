package com.xploits.sweep.core;

/**
 * Un chunk del Nether identificado por sus coordenadas de chunk (no de bloque). Es la unidad en la
 * que se piensa el barrido: la cobertura se marca chunk a chunk, no bloque a bloque.
 */
public record ChunkPos(int x, int z) {
}
