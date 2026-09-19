package com.xploits.elytra.core;

/** Qué hacer con la elytra puesta (spec §4). */
public enum Decision {
    /** Cambiarla por la del slot indicado. */
    SWAP,
    /** Hay que cambiarla pero no hay ningún repuesto válido: avisar y no tocar nada. */
    NO_SPARE,
    /** Nada que hacer. */
    OK,
    /** No lleva elytra puesta. */
    NOT_WEARING
}
