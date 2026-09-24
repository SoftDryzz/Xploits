package com.xploits.pvp.core;

import com.xploits.shared.core.i18n.Msg;

/** A module the director does not enable, and why (spec §6). */
public record Skipped(ManagedModule module, Msg reason) {}
