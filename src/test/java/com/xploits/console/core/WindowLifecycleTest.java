package com.xploits.console.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.Msg;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WindowLifecycleTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });
    private static final Predicate<WindowLifecycle.Pid> ALIVE = p -> true;
    private static final Predicate<WindowLifecycle.Pid> DEAD = p -> false;
    private static final WindowLifecycle.Pid PID = new WindowLifecycle.Pid(99, 1, "l1");

    /** The actions with each notice rendered in Spanish: the text moved to the catalog verbatim. */
    private static List<Object> es(List<WindowLifecycle.Action> actions) {
        return actions.stream().map(a -> a instanceof WindowLifecycle.Notify n ? (Object) (n.level() + " " + ES.render(n.text())) : a).toList();
    }

    private static WindowLifecycle lifecycle() {
        Iterator<String> ids = List.of("l1", "l2", "l3").iterator();
        return new WindowLifecycle(ids::next);
    }

    private static WindowLifecycle.Observation obs(long now, WindowLifecycle.Pid readPid, Predicate<WindowLifecycle.Pid> alive,
                                                   WindowLifecycle.Exit exit) {
        return new WindowLifecycle.Observation(now, readPid, alive, exit);
    }

    /** A lifecycle with window l1 open, pid 99. */
    private static WindowLifecycle aliveLifecycle() {
        WindowLifecycle c = lifecycle();
        c.turnOn();
        c.tick(obs(0, null, ALIVE, null));
        c.launched("command", 0);
        c.tick(obs(500, PID, ALIVE, null));
        return c;
    }

    @Test
    void turningOnLaunchesOnTheFirstTick() {
        WindowLifecycle c = lifecycle();
        assertEquals(List.of(), c.turnOn());
        assertEquals(List.of(new WindowLifecycle.Launch("l1")), c.tick(obs(0, null, ALIVE, null)));
        assertEquals(List.of(), c.tick(obs(50, null, ALIVE, null)));
    }

    @Test
    void withItsPidAliveItIsOpenAndTurningOffSendsItTheClose() {
        assertEquals(List.of(new WindowLifecycle.WriteClose("l1"), new WindowLifecycle.WatchClose("l1", 99L, 3000)),
            aliveLifecycle().turnOff(1000));
    }

    @Test
    void noPidInTenSecondsIsReportedWithTheExactCommand() {
        WindowLifecycle c = lifecycle();
        c.turnOn();
        c.tick(obs(0, null, ALIVE, null));
        c.launched("cmd /c something", 0);
        assertEquals(List.of(), c.tick(obs(9_999, null, ALIVE, null)));
        // The F goes even without a window: if it starts late, it finds it and closes instead of being left orphaned.
        assertEquals(List.of("ERROR " + "La consola no ha arrancado en 10 s. Se intentó: cmd /c something",
            new WindowLifecycle.WriteClose("l1"), new WindowLifecycle.DisableModule()), es(c.tick(obs(10_000, null, ALIVE, null))));
        assertFalse(c.active());
    }

    @Test
    void anotherLaunchCloseDoesNotCloseThisOneNorDoesAForeignPidCount() {
        WindowLifecycle c = lifecycle();
        c.turnOn();
        c.tick(obs(0, null, ALIVE, null));
        c.launched("command", 0);
        WindowLifecycle.Pid foreign = new WindowLifecycle.Pid(77, 1, "other");
        assertEquals(List.of(), c.tick(obs(500, foreign, ALIVE, null)));
        assertEquals(3, c.tick(obs(10_000, foreign, ALIVE, null)).size());

        WindowLifecycle open = aliveLifecycle();
        assertEquals(List.of("INFO " + "La ventana de la consola se cerró (con la X o desde fuera).",
            new WindowLifecycle.DisableModule()),
            es(open.tick(obs(600, PID, DEAD, new WindowLifecycle.Exit(WindowLifecycle.Exit.USER, "other", "")))));
    }

    @Test
    void theThreeWaysOfClosingAreReportedDifferently() {
        assertEquals(List.of("INFO " + "Consola cerrada desde su menú.", new WindowLifecycle.DisableModule()),
            es(aliveLifecycle().tick(obs(600, PID, DEAD, new WindowLifecycle.Exit(WindowLifecycle.Exit.USER, "l1", "")))));
        assertEquals(List.of("ERROR " + "La consola se cerró por un error: NullPointerException: x",
                new WindowLifecycle.DisableModule()),
            es(aliveLifecycle().tick(obs(600, PID, DEAD,
                new WindowLifecycle.Exit(WindowLifecycle.Exit.ERROR, "l1", "NullPointerException: x")))));
        assertEquals(List.of("INFO " + "La ventana de la consola se cerró (con la X o desde fuera).",
            new WindowLifecycle.DisableModule()), es(aliveLifecycle().tick(obs(600, PID, DEAD, null))));
    }

    @Test
    void aRejectionIsReportedAndTurnsTheModuleOff() {
        WindowLifecycle c = lifecycle();
        c.turnOn();
        c.tick(obs(0, null, ALIVE, null));
        assertEquals(List.of("ERROR " + "No se puede abrir la consola: no encuentro el jar del addon en disco, y la ventana se ejecuta desde él.",
            new WindowLifecycle.DisableModule()), es(c.rejected(Msg.of(ConsoleText.NO_JAR))));
        assertFalse(c.active());
    }

    @Test
    void onOffOnWhileClosingLaunchesOnlyOnce() {
        WindowLifecycle c = aliveLifecycle();
        c.turnOff(1000);
        c.turnOn();
        List<WindowLifecycle.Action> all = new ArrayList<>();
        all.addAll(c.tick(obs(1500, PID, ALIVE, null)));
        all.addAll(c.tick(obs(2000, PID, DEAD, null)));
        all.addAll(c.tick(obs(2050, null, ALIVE, null)));
        all.addAll(c.tick(obs(2100, null, ALIVE, null)));
        assertEquals(List.of(new WindowLifecycle.Launch("l2")), all);
    }

    @Test
    void offAndNotTurnedBackOnLaunchesNothing() {
        WindowLifecycle c = aliveLifecycle();
        c.turnOff(1000);
        assertEquals(List.of(), c.tick(obs(5000, PID, DEAD, null)));
        assertFalse(c.active());
    }

    @Test
    void turningOffBeforeTheFirstTickDoesNothing() {
        WindowLifecycle c = lifecycle();
        c.turnOn();
        assertEquals(List.of(), c.turnOff(0));
        assertEquals(List.of(), c.tick(obs(0, null, ALIVE, null)));
        assertFalse(c.active());
    }

    @Test
    void reportingALaunchWhileNotLaunchingIsAnAdapterBug() {
        assertThrows(IllegalStateException.class, () -> lifecycle().launched("x", 0));
        assertThrows(IllegalStateException.class, () -> lifecycle().rejected(Msg.of(ConsoleText.NO_JAR)));
    }

    @Test
    void pidAndExitAreWrittenAndRead() {
        assertEquals(Optional.of(new WindowLifecycle.Pid(123, 456, "l1")),
            WindowLifecycle.Pid.read(new WindowLifecycle.Pid(123, 456, "l1").write()));
        assertEquals(Optional.empty(), WindowLifecycle.Pid.read("garbage"));
        assertEquals(Optional.of(new WindowLifecycle.Exit(WindowLifecycle.Exit.ERROR, "l1", "a\tb")),
            WindowLifecycle.Exit.read(WindowLifecycle.Exit.error("l1", "a\tb")));
        assertEquals(Optional.of(new WindowLifecycle.Exit(WindowLifecycle.Exit.USER, "l1", "")),
            WindowLifecycle.Exit.read(WindowLifecycle.Exit.user("l1")));
        assertEquals(Optional.empty(), WindowLifecycle.Exit.read(""));
    }
}
