package com.xploits.pvp.recorder.core;

/**
 * Decides on which tick your death is reported, so that each death is reported exactly once.
 *
 * <p>Two signs report a death: the server's death message, sent only to the player who died, and your
 * health reaching 0 (alive on one tick, dead on the next, same player entity). They come in either order
 * and often on different ticks, and on a server with immediate respawn the health sign may never be seen.
 * The client builds a new player entity when you respawn; a death belongs to one entity, and an entity
 * dies only once, so a second sign for the same entity is the same death.
 *
 * <p>A death message read on the tick the client has already respawned you belongs to the entity that
 * died, not to the new one: it is reported unless that entity's death already was.
 *
 * <p>Feed it once per tick with the player in the world, in tick order.
 */
public final class DeathSignal {
    private boolean wasAlive;
    /** Whether the current player entity's death has been reported. */
    private boolean reported;

    /**
     * @param respawned    the player entity is not the one of the previous tick (never on the first tick)
     * @param deathMessage a death message about you was read on this tick
     * @param alive        the current player entity is alive now
     * @return whether your death is reported on this tick
     */
    public boolean tick(boolean respawned, boolean deathMessage, boolean alive) {
        boolean died = false;
        if (respawned) {
            died = deathMessage && !reported;
            reported = false;
        } else if (!reported && (deathMessage || (wasAlive && !alive))) {
            died = true;
            reported = true;
        }
        wasAlive = alive;
        return died;
    }

    /** Forgets everything: the next tick is a first tick. */
    public void reset() {
        wasAlive = false;
        reported = false;
    }
}
