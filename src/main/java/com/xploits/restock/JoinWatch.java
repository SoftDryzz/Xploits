package com.xploits.restock;

import com.xploits.XploitsAddon;
import com.xploits.restock.core.RestockText;
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
            repair("Baritone", () -> BaritoneRepair.run(say), say, RestockText.REPAIR_ERROR);
            repair("printer", () -> PrinterMarker.repairAtJoin(say, LitematicaPrinterSwitch.find()), say,
                RestockText.PRINTER_NOT_REPAIRED);
        }
    }

    /**
     * One repair. A failure (an exception, or a {@code LinkageError} from a mod that changed) is logged by its class only,
     * as its message could carry a position, and the player is told in a fixed text what to check (deferred L44).
     */
    private static void repair(String what, Runnable repair, XploitsModule say, RestockText told) {
        try {
            repair.run();
        } catch (RuntimeException | LinkageError e) {
            XploitsAddon.LOG.error("restock: the {} repair at join failed ({})", what, e.getClass().getName());
            if (say == null) return;
            try {
                say.warning(told);
            } catch (RuntimeException | LinkageError again) {
                XploitsAddon.LOG.error("restock: the {} repair's warning failed ({})", what, again.getClass().getName());
            }
        }
    }
}
