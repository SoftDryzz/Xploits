package com.xploits.pvp.core;

/** Un módulo que el director no enciende, y por qué (spec §6). */
public record Skipped(ManagedModule module, String reason) {}
