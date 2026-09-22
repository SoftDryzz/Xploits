package com.xploits.pvp.core;

/** Lo que un módulo dirigido necesita llevar encima para servir de algo (spec §6). */
public enum Resource {
    CRYSTALS, OBSIDIAN, WEBS, ANVILS, PICKAXE,
    /**
     * Nada. Los tres {@code anti-} de la postura defensiva (rediseño §5) no colocan ni gastan: solo
     * escuchan y reaccionan, así que no hay nada que contar en el inventario y el filtro de recursos
     * no puede quitarlos nunca. Se declara como recurso, con mínimo cero, para que el catálogo no
     * tenga que admitir un {@code needs()} nulo.
     */
    NONE
}
