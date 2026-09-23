package com.xploits.pvp.core;

import com.xploits.shared.core.i18n.Msg;

/** Un módulo que el director no enciende, y por qué (spec §6). */
public record Skipped(ManagedModule module, Msg reason) {}
