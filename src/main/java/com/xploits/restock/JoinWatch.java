package com.xploits.restock;

import com.xploits.XploitsAddon;
import com.xploits.shared.XploitsModule;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;

/**
 * Tells a module turned on by the player from one Meteor turns back on during a world join (spec §3 "leaving the
 * server": the module does nothing until enabled again): {@code Modules.onGameJoined} calls {@code onActivate()} of
 * every module still marked active (VERIFIED in Meteor's sources for the halted plan), and this listener, at
 * {@code HIGHEST}, runs before it. At the first tick of a world, whichever module is on, it gives back the Baritone
 * values and the printer's print mode an interrupted restock session left behind. Subscribed once at start-up.
 */
public final class JoinWatch {
    private static final JoinWatch INSTANCE = new JoinWatch();
    private static boolean started;

    private volatile boolean joining;
    private boolean repairDue;

    private JoinWatch() {
    }

    public static synchronized void start() {
        if (started) return;
        started = true;
        MeteorClient.EVENT_BUS.subscribe(INSTANCE);
    }

    /** From a world join until its first tick: an {@code onActivate()} now is Meteor's, not the player's. */
    public static boolean joining() {
        return INSTANCE.joining;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onJoin(GameJoinedEvent event) {
        joining = true;
        repairDue = true;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onTick(TickEvent.Pre event) {
        joining = false;
        if (repairDue && MeteorClient.mc.player != null) {
            repairDue = false;
            XploitsModule say = Modules.get().get("restock") instanceof XploitsModule m ? m : null;
            // Each repair on its own: an exception must not reach the game tick nor skip the other repair.
            try {
                BaritoneRepair.run(say);
            } catch (RuntimeException e) {
                XploitsAddon.LOG.error("restock: the Baritone repair at join failed");
            }
            try {
                PrinterMarker.repairAtJoin(say, LitematicaPrinterSwitch.find());
            } catch (RuntimeException e) {
                XploitsAddon.LOG.error("restock: the printer repair at join failed");
            }
        }
    }
}
