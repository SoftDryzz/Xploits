package com.xploits.console;

import com.xploits.XploitsAddon;
import com.xploits.console.core.ConsoleText;
import com.xploits.console.core.Level;
import com.xploits.console.core.WindowLifecycle;
import com.xploits.console.core.WindowStart;
import com.xploits.shared.Texts;
import com.xploits.shared.XploitsModule;
import com.xploits.shared.core.i18n.Msg;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Opens and closes the console window (console spec §8).
 *
 * <p>With {@code runInMainMenu} it stays out of the turning on and off that Meteor does on entering and
 * leaving each world: if left on, it opens when the game starts and survives disconnects and deaths.
 * It does not launch in {@code onActivate}, which at startup runs before anything exists, but on the
 * first tick.
 *
 * <p>Without a world, chat is lost silently (spec §3.2). That is why the notices go through the
 * {@link NoticeDispatcher}: a toast right away, and to chat as soon as there is a world, whether the
 * module is on or not.
 */
public class ConsoleModule extends XploitsModule {
    private static final int TICKS_BETWEEN_CHECKS = 5;

    private final WindowLifecycle lifecycle = new WindowLifecycle(WindowLauncher::newId);
    private final NoticeDispatcher dispatcher = new NoticeDispatcher();
    private ConsoleSink sink;
    private FileChannel lockChannel;
    private FileLock lock;
    private int ticks;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> hideCoordinates = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-coordinates")
        .description(Texts.startupText(ConsoleText.SETTING_HIDE_COORDINATES))
        .defaultValue(true)
        .onChanged(ConsoleOutput::hideCoordinates)
        .build());

    public ConsoleModule() {
        super(XploitsAddon.CATEGORY, "console", Texts.startupText(ConsoleText.MODULE_DESC));
        runInMainMenu = true;
        ConsoleOutput.installShutdownHook();
        MeteorClient.EVENT_BUS.subscribe(dispatcher);
    }

    @Override
    public void onActivate() {
        Path folder = WindowLauncher.folder();
        if (!acquireLock(folder)) {
            announce(Level.ERROR, Msg.of(ConsoleText.IN_USE, "folder", folder.toString()));
            toggle();
            return;
        }
        ConsoleSink fresh = new ConsoleSink(folder);
        try {
            fresh.start();
        } catch (IOException e) {
            announce(Level.ERROR, Msg.of(ConsoleText.CANNOT_PREPARE_LOG, "folder", folder.toString(), "error", String.valueOf(e.getMessage())));
            releaseLock();
            toggle();
            return;
        }
        sink = fresh;
        ConsoleOutput.connect(sink);
        sink.gameEvent("start");
        ticks = 0;
        execute(lifecycle.turnOn());
    }

    @Override
    public void onDeactivate() {
        execute(lifecycle.turnOff(System.currentTimeMillis()));
        ConsoleOutput.disconnect();
        if (sink != null) {
            sink.close();
            sink = null;
        }
        releaseLock();
    }

    /** Orbit does not catch: an exception from here would reach the game's tick. It is reported and the console turns off. */
    @EventHandler
    private void onTick(TickEvent.Post event) {
        try {
            if (++ticks % TICKS_BETWEEN_CHECKS != 0) return;
            Path folder = WindowLauncher.folder();
            execute(lifecycle.tick(new WindowLifecycle.Observation(System.currentTimeMillis(),
                WindowLauncher.readPid(folder).orElse(null), WindowLauncher::isAlive, WindowLauncher.readExit(folder).orElse(null))));
            // It may have turned off because of what just happened: check again.
            if (sink != null) ConsoleOutput.snapshot(SnapshotCollector.capture());
        } catch (RuntimeException e) {
            XploitsAddon.LOG.error("The console has failed", e);
            announce(Level.ERROR, Msg.of(ConsoleText.FAILED, "error", e.getClass().getSimpleName() + ": " + e.getMessage()));
            if (isActive()) toggle();
        }
    }

    private void execute(List<WindowLifecycle.Action> actions) {
        for (WindowLifecycle.Action action : actions) {
            switch (action) {
                case WindowLifecycle.Launch l -> launch(l.launchId());
                case WindowLifecycle.WriteClose w -> {
                    if (sink != null) sink.sendClose(w.launchId(), "console off");
                }
                case WindowLifecycle.WatchClose w -> WindowLauncher.watchClose(WindowLauncher.folder(), w);
                case WindowLifecycle.Notify n -> announce(n.level(), n.text());
                case WindowLifecycle.DisableModule d -> {
                    if (isActive()) toggle();
                }
            }
        }
    }

    /** An unexpected failure while preparing or launching is settled as a rejection: the lifecycle cannot stay in LAUNCHING. */
    private void launch(String launchId) {
        Path folder = WindowLauncher.folder();
        WindowLauncher.deleteLeftovers(folder);
        WindowStart.Result result;
        try {
            result = WindowLauncher.prepare(folder, launchId);
        } catch (RuntimeException e) {
            execute(lifecycle.rejected(unexpectedFailure(e)));
            return;
        }
        switch (result) {
            case WindowStart.Rejection r -> execute(lifecycle.rejected(r.reason()));
            case WindowStart.Command c -> {
                try {
                    WindowLauncher.launch(c.argv());
                } catch (IOException e) {
                    execute(lifecycle.rejected(Msg.of(ConsoleText.WINDOWS_REFUSED, "error", String.valueOf(e.getMessage()))));
                    return;
                } catch (RuntimeException e) {
                    execute(lifecycle.rejected(unexpectedFailure(e)));
                    return;
                }
                execute(lifecycle.launched(c.description(), System.currentTimeMillis()));
            }
        }
    }

    private static Msg unexpectedFailure(RuntimeException e) {
        XploitsAddon.LOG.error("Unexpected failure while preparing the console window", e);
        return Msg.of(ConsoleText.UNEXPECTED_FAILURE, "error", e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    private boolean acquireLock(Path folder) {
        try {
            Files.createDirectories(folder);
            lockChannel = FileChannel.open(folder.resolve("console.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = lockChannel.tryLock();
        } catch (IOException | OverlappingFileLockException e) {
            lock = null;
        }
        if (lock == null) releaseLock();
        return lock != null;
    }

    private void releaseLock() {
        try {
            if (lock != null) lock.release();
            if (lockChannel != null) lockChannel.close();
        } catch (IOException ignored) {
            // When the game closes the system releases it anyway.
        }
        lock = null;
        lockChannel = null;
    }

    private void announce(Level level, Msg msg) {
        String text = Texts.render(msg);
        switch (level) {
            case INFO -> XploitsAddon.LOG.info("[console] {}", text);
            case WARNING -> XploitsAddon.LOG.warn("[console] {}", text);
            case ERROR -> XploitsAddon.LOG.error("[console] {}", text);
        }
        dispatcher.enqueue(level, msg);
    }

    private record Notice(Level level, Msg text) {
    }

    /**
     * Hands out the console's notices and {@link ConsoleOutput}'s alerts. It is always subscribed, apart
     * from the module, because the most important notice ("the window has not started") arrives just as
     * the module turns off, and often in the menu, where there is no chat.
     */
    private final class NoticeDispatcher {
        private final List<Notice> pendingToasts = new ArrayList<>();
        private final List<Notice> pendingChat = new ArrayList<>();

        void enqueue(Level level, Msg text) {
            if (level != Level.INFO) pendingToasts.add(new Notice(level, text));
            pendingChat.add(new Notice(level, text));
        }

        /**
         * Orbit does not catch: if dispatching fails, it is logged and what is pending is dropped, so the
         * same failure does not repeat on every tick. No notice is sent from here: it could fail the same way.
         */
        @EventHandler
        private void onTick(TickEvent.Post event) {
            try {
                Msg alert;
                while ((alert = ConsoleOutput.pendingAlert()) != null) announce(Level.ERROR, alert);
                for (Notice n : pendingToasts) {
                    mc.getToastManager().add(new MeteorToast.Builder("Xploits").text(Texts.render(n.text())).icon(Items.COMMAND_BLOCK).build());
                }
                pendingToasts.clear();
                if (mc.world == null || pendingChat.isEmpty()) return;
                List<Notice> copy = new ArrayList<>(pendingChat);
                pendingChat.clear();
                for (Notice n : copy) {
                    switch (n.level()) {
                        case INFO -> info(n.text());
                        case WARNING -> warning(n.text());
                        case ERROR -> error(n.text());
                    }
                }
            } catch (RuntimeException e) {
                XploitsAddon.LOG.error("The console could not dispatch its notices; the pending ones are dropped", e);
                pendingToasts.clear();
                pendingChat.clear();
            }
        }
    }
}
