package com.xploits.travel.core;

/**
 * A Meteor module the trip borrows while flying and gives back on landing (spec §6.3), in pure
 * logic: it notes down how it was before take-off and decides what has to be done to it at each
 * moment. It knows nothing about Meteor or Minecraft -the adapter passes it a boolean and carries out
 * whatever it returns-, so it is tested without starting the game.
 *
 * <p><b>Why this is not a declared setting.</b> The first version gave both modules back to a value
 * declared in two {@code *-resting} settings, with the argument of spec §6.1: <i>Baritone's settings
 * can be written but not read, so what to go back to must be declared</i>. That argument is true for
 * Baritone and <b>false for these two</b>, which are Meteor modules: {@code Module.isActive()} reads
 * perfectly well. Declaring the resting state meant that a player who never enabled {@code
 * elytra-fly} -the norm on an anarchy server, where odd flight gives you away- would land with it on
 * and read "Environment restored". Here the real state is noted down and given back.
 *
 * <p><b>A manual move during the flight overrides what was noted down</b>, same as auto-pvp's {@code
 * ModuleLedger}: if on release the module is no longer in the state the preparation imposed on it,
 * someone moved it after us, and their decision is more recent than our note. In that case it is not
 * touched.
 *
 * <p><b>And there is one moment when no module can be touched:</b> the teardown on leaving the world.
 * {@code Modules.onGameLeft} unsubscribes and deactivates the active modules <b>without</b> setting
 * {@code active = false}, so that they come back by themselves on the next join; turning one on there
 * leaves it subscribed, and {@code Modules.onGameJoined} subscribes it again on coming back -orbit's
 * {@code EventBus.insert()} does not deduplicate-, so its handlers would run twice per event for the
 * rest of the session, and not even turning it off fixes it because {@code unsubscribe} uses {@code
 * List.remove}, which removes a single copy. That is what the {@code canToggle} of {@link
 * #release(boolean, boolean)} is for: with {@code false} the decision is not lost, it stays
 * <b>pending</b> and the adapter applies it when it can.
 */
public final class BorrowedModule {
    /** What has to be done to the module right now. */
    public enum Action {
        TURN_ON,
        TURN_OFF,
        NONE;

        static Action towards(boolean wanted) {
            return wanted ? TURN_ON : TURN_OFF;
        }
    }

    /** The module's name, as the player sees it in the ClickGUI. Only for the warnings. */
    private final String name;

    /** The state the preparation imposes on it while flying (spec §6.2). */
    private final boolean inFlight;

    /** How it was right before take-off, or {@code null} if it is not borrowed right now. */
    private Boolean atTakeoff;

    /** What was left to do because the module could not be touched when it was due. */
    private Action pending = Action.NONE;

    public BorrowedModule(String name, boolean inFlight) {
        this.name = name;
        this.inFlight = inFlight;
    }

    public String name() {
        return name;
    }

    /** The state this module has to be in while the trip lasts. */
    public boolean inFlight() {
        return inFlight;
    }

    /**
     * Takes the module at take-off: notes down how it is and answers what has to be done to leave it
     * in its flight state.
     *
     * <p>Taking it forgets anything pending: a pending action is the give-back of a previous trip,
     * and if another trip starts that give-back no longer makes sense -what has to be given back is
     * what gets noted down now-. The adapter applies pending actions before taking anything, so that
     * "what gets noted down now" really is the player's resting state and not the one the previous
     * trip left.
     *
     * @param active whether the module is on right now
     */
    public Action take(boolean active) {
        atTakeoff = active;
        pending = Action.NONE;
        return active == inFlight ? Action.NONE : Action.towards(inFlight);
    }

    /**
     * Releases the module when the trip ends and answers what has to be done to it.
     *
     * @param active    whether the module is on right now
     * @param canToggle whether a Meteor module can be turned on or off right now without breaking it.
     *                  With {@code false} -the teardown on leaving the world- no action is returned:
     *                  the one that was due stays pending and is read with {@link #pending()}
     * @return what has to be done to it now, which is {@code NONE} if it was not borrowed, if the
     *         player moved it by hand during the flight, if it is already where it was, or if it
     *         cannot be touched
     */
    public Action release(boolean active, boolean canToggle) {
        // Without a note there is nothing to give back, and an earlier pending action is not touched:
        // the second exit path to arrive cannot erase what the first one left noted down.
        if (atTakeoff == null) return Action.NONE;

        boolean wanted = atTakeoff;
        atTakeoff = null;
        pending = Action.NONE;

        // It is no longer as the preparation left it: someone moved it after us, and that is more
        // recent than our note. It is not touched.
        if (active != inFlight) return Action.NONE;
        if (wanted == active) return Action.NONE;

        Action action = Action.towards(wanted);
        if (canToggle) return action;

        pending = action;
        return Action.NONE;
    }

    /** What was left to do, or {@code NONE} if nothing is pending. */
    public Action pending() {
        return pending;
    }

    public boolean hasPending() {
        return pending != Action.NONE;
    }

    /** Returns what is pending and forgets it, so that it is not applied twice. */
    public Action claimPending() {
        Action action = pending;
        pending = Action.NONE;
        return action;
    }

    /** Forgets the note and anything pending. For when the module is not even registered. */
    public void forget() {
        atTakeoff = null;
        pending = Action.NONE;
    }
}
