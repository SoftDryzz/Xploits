package com.xploits.bench;

import com.xploits.XploitsAddon;
import com.xploits.pvp.AutoPvp;
import com.xploits.pvp.recorder.FightRecorder;
import com.xploits.pvp.recorder.core.FightRecord;
import com.xploits.pvp.recorder.core.FightStore;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.ReturnValueConsumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.world.Difficulty;
import net.minecraft.world.rule.GameRules;
import org.apache.commons.lang3.function.FailableConsumer;
import org.apache.commons.lang3.function.FailableFunction;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The handle a scenario gets for one run (spec {@code 2026-09-25-ingame-bench}, §Code layout and §Run
 * timeline). It is used from the gametest thread only; everything that touches the client or the server
 * is sent to that thread.
 *
 * <p><b>No coordinates.</b> Commands name the real player and use relative offsets only; nothing here
 * reads, keeps or prints a position.
 */
public final class Bench {
    /** Selectors that would pick a player by position or at random: commands name the player instead. */
    private static final Pattern PLAYER_SELECTOR = Pattern.compile("@[aprs](?![a-z])");
    /** Where a command names the real player. */
    public static final String PLAYER = "{player}";

    private final ClientGameTestContext ctx;
    private final TestServerContext server;
    private final int budget;
    private final List<Runnable> everyTick = new ArrayList<>();
    private final List<Runnable> despawn = new ArrayList<>();
    private final List<Runnable> screenRestore = new ArrayList<>();

    private int ticksUsed;
    private int t0Tick = -1;
    private String player;
    /** The fight files there were at T0. */
    private Set<Path> fightsAtT0;

    Bench(ClientGameTestContext ctx, TestServerContext server, int budgetTicks) {
        this.ctx = ctx;
        this.server = server;
        this.budget = budgetTicks;
    }

    // --- Time ------------------------------------------------------------------------------------

    /**
     * Waits {@code n} ticks, one at a time; after each one the per-tick steps run (the sparring steps
     * there). One {@code waitTick()} is one server tick, so a step neither drifts nor runs twice.
     *
     * @throws BenchTimeout past the scenario's budget
     */
    public void ticks(int n) {
        for (int i = 0; i < n; i++) {
            if (ticksUsed >= budget) throw new BenchTimeout(budget);
            ctx.waitTick();
            ticksUsed++;
            for (Runnable step : everyTick) step.run();
        }
    }

    /** Ticks waited in this run so far. */
    public int ticksUsed() {
        return ticksUsed;
    }

    /** Ticks since T0; -1 before it. */
    public int sinceT0() {
        return t0Tick < 0 ? -1 : ticksUsed - t0Tick;
    }

    /** Runs {@code step} on the gametest thread after every tick of {@link #ticks}, in the order added. */
    public void everyTick(Runnable step) {
        everyTick.add(step);
    }

    // --- Threads ---------------------------------------------------------------------------------

    public <E extends Throwable> void onClient(FailableConsumer<MinecraftClient, E> action) throws E {
        ctx.runOnClient(action);
    }

    public <T, E extends Throwable> T fromClient(FailableFunction<MinecraftClient, T, E> function) throws E {
        return ctx.computeOnClient(function);
    }

    public <E extends Throwable> void onServer(FailableConsumer<MinecraftServer, E> action) throws E {
        server.runOnServer(action);
    }

    public <T, E extends Throwable> T fromServer(FailableFunction<MinecraftServer, T, E> function) throws E {
        return server.computeOnServer(function);
    }

    // --- Player and commands ---------------------------------------------------------------------

    /** The real player's name, from the server's player list (there is exactly one player). */
    public String player() {
        if (player == null) {
            player = fromServer(srv -> {
                List<ServerPlayerEntity> players = srv.getPlayerManager().getPlayerList();
                if (players.size() != 1) throw new BenchException("expected one player on the server, found " + players.size());
                return players.getFirst().getGameProfile().name();
            });
            if (player == null || player.isBlank()) throw new BenchException("the player has no name");
        }
        return player;
    }

    /**
     * Runs a server command as the server, with no feedback anywhere. {@value #PLAYER} is replaced by the real player's
     * name; the selectors {@code @a @p @r @s} are refused, so no command picks a player by position.
     * A rejected command (it did not parse, or it failed) is ERROR; its text and its error are never
     * printed, only its first word.
     */
    public void command(String command) {
        if (PLAYER_SELECTOR.matcher(command).find()) {
            throw new BenchException("a command selects a player instead of naming it: " + firstWord(command));
        }
        String line = command.replace(PLAYER, player());
        boolean ok = fromServer(srv -> {
            Capture capture = new Capture();
            // Not withSilent(): a silent source drops its errors too, and they are what is watched.
            ServerCommandSource source = srv.getCommandSource()
                .withOutput(capture)
                .withReturnValueConsumer(capture);
            srv.getCommandManager().parseAndExecute(source, line);
            return capture.ok();
        });
        if (!ok) throw new BenchException("a command was rejected: " + firstWord(command));
    }

    private static String firstWord(String command) {
        String trimmed = command.strip();
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }

    /**
     * Keeps a command's errors and result, and drops its feedback: feedback can carry a position (the
     * teleport one does), and it goes nowhere.
     */
    private static final class Capture implements CommandOutput, ReturnValueConsumer {
        private boolean error;
        private boolean anyResult;
        private boolean anySuccess;

        @Override
        public void sendMessage(Text message) {
            // Only errors reach here: shouldReceiveFeedback() is false. The text itself is not kept.
            error = true;
        }

        @Override
        public boolean shouldReceiveFeedback() {
            return false;
        }

        @Override
        public boolean shouldTrackOutput() {
            return true;
        }

        @Override
        public boolean shouldBroadcastConsoleToOps() {
            return false;
        }

        @Override
        public void onResult(boolean successful, int returnValue) {
            anyResult = true;
            anySuccess |= successful;
        }

        boolean ok() {
            return !error && (!anyResult || anySuccess);
        }
    }

    /**
     * The common first steps of every run (§Run timeline, Arrange): difficulty normal, no natural
     * regeneration, the player in survival and centred on its block.
     */
    void prepare() {
        // The command fails when the difficulty is already the one asked for (a new world's default).
        if (fromServer(srv -> srv.getSaveProperties().getDifficulty()) != Difficulty.NORMAL) command("difficulty normal");
        command("gamerule natural_health_regeneration false");
        command("gamemode survival " + PLAYER);
        command("execute at " + PLAYER + " align xz run tp " + PLAYER + " ~0.5 ~ ~0.5");
        boolean set = fromServer(srv -> srv.getSaveProperties().getDifficulty() == Difficulty.NORMAL
            && !srv.getOverworld().getGameRules().getValue(GameRules.NATURAL_HEALTH_REGENERATION));
        if (!set) throw new BenchException("the difficulty or the regeneration rule did not change");
    }

    // --- Meteor and Xploits modules --------------------------------------------------------------

    /** The module of that class with every setting back to its default. */
    public <T extends Module> T meteor(Class<T> type) {
        return fromClient(client -> {
            T module = Modules.get().get(type);
            if (module == null) throw new BenchException("no module " + type.getSimpleName());
            module.settings.reset();
            return module;
        });
    }

    /**
     * Sets one setting, found by its group and name ({@code settings.get(name)} would return the first
     * match of a repeated name). The value must be of the setting's type (an enum constant for an enum
     * setting); a value out of range is ERROR.
     */
    public void setting(Module module, String group, String name, Object value) {
        onClient(client -> {
            SettingGroup settings = module.settings.getGroup(group);
            if (settings == null) throw new BenchException(module.name + " has no setting group " + group);
            Setting<?> setting = settings.get(name);
            if (setting == null) throw new BenchException(module.name + " has no setting " + group + "/" + name);
            if (!sameType(setting.getDefaultValue(), value)) {
                throw new BenchException(module.name + " " + name + " takes another type of value");
            }
            @SuppressWarnings("unchecked")
            Setting<Object> typed = (Setting<Object>) setting;
            if (!typed.set(value)) throw new BenchException(module.name + " " + name + " refused the value");
        });
    }

    private static boolean sameType(Object current, Object value) {
        if (current == null || value == null) return false;
        if (current instanceof Enum<?> a && value instanceof Enum<?> b) return a.getDeclaringClass() == b.getDeclaringClass();
        if (current instanceof Collection<?> && value instanceof Collection<?>) return true;
        if (current instanceof Map<?, ?> && value instanceof Map<?, ?>) return true;
        return current.getClass().isInstance(value);
    }

    public AutoPvp autoPvp() {
        return fromClient(client -> {
            AutoPvp module = Modules.get().get(AutoPvp.class);
            if (module == null) throw new BenchException("auto-pvp is not loaded");
            return module;
        });
    }

    /** Turns fight-recorder on or off on the client thread; off saves the fight in progress, ABORTED. */
    public void recorder(boolean on) {
        onClient(client -> {
            FightRecorder recorder = recorderModule();
            if (on) recorder.enable();
            else recorder.disable();
        });
    }

    private static FightRecorder recorderModule() {
        FightRecorder recorder = Modules.get().get(FightRecorder.class);
        if (recorder == null) throw new BenchException("fight-recorder is not loaded");
        return recorder;
    }

    // --- T0, close ---------------------------------------------------------------------------------

    /**
     * T0 (§Run timeline 3), in one client call: the fight store is snapshotted, then the recorder is
     * turned on if {@code recorder}, then {@code modules} in order.
     */
    @SafeVarargs
    public final void start(boolean recorder, Class<? extends Module>... modules) {
        onClient(client -> {
            fightsAtT0 = new HashSet<>(listFights());
            if (recorder) recorderModule().enable();
            for (Class<? extends Module> type : modules) {
                Module module = Modules.get().get(type);
                if (module == null) throw new BenchException("no module " + type.getSimpleName());
                module.enable();
            }
        });
        t0Tick = ticksUsed;
    }

    /** The close (§Run timeline 5): recorder off, one tick, then the fights recorded since T0. */
    public List<FightRecord> finish() {
        recorder(false);
        ticks(1);
        return newFights();
    }

    /** The fights saved since T0, oldest first. */
    public List<FightRecord> newFights() {
        if (fightsAtT0 == null) throw new BenchException("new fights were read before T0");
        return fromClient(client -> {
            FightStore store = recorderModule().store();
            List<Path> added = new ArrayList<>(listFights());
            added.removeAll(fightsAtT0);
            List<FightRecord> records = new ArrayList<>();
            // list() is newest first.
            for (int i = added.size() - 1; i >= 0; i--) {
                try {
                    records.add(store.load(added.get(i)));
                } catch (IOException e) {
                    throw new BenchException("a saved fight could not be read");
                }
            }
            return records;
        });
    }

    private static List<Path> listFights() {
        try {
            return recorderModule().store().list();
        } catch (IOException e) {
            throw new BenchException("the fight folder could not be listed");
        }
    }

    // --- Checks ----------------------------------------------------------------------------------

    /** A CHECK's condition: false fails the run with {@code message}, which the bench wrote. */
    public static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    // --- Teardown --------------------------------------------------------------------------------

    /** Runs at teardown step 4 (despawn the sparring). */
    public void atDespawn(Runnable step) {
        despawn.add(step);
    }

    /** Runs at teardown step 5 (the HUD elements the bench added, the HUD, chat). */
    public void atScreenRestore(Runnable step) {
        screenRestore.add(step);
    }

    /**
     * Undoes what a run may have left behind, even after a failure (§Code layout, Teardown); every step
     * is tried whatever the others did. Returns the steps that failed, in words the bench wrote; last,
     * it checks that no combat or Xploits module is still on.
     */
    List<String> teardown() {
        List<String> failed = new ArrayList<>();
        step(failed, "recorder off", () -> recorder(false));
        step(failed, "modules off", () -> failed.addAll(fromClient(client -> {
            // One by one: a module that throws while turning off does not keep the others on.
            List<String> refused = new ArrayList<>();
            for (Module module : groupModules()) {
                try {
                    module.disable();
                } catch (RuntimeException | AssertionError e) {
                    refused.add("module off: " + module.name + " (" + e.getClass().getSimpleName() + ")");
                }
            }
            return refused;
        })));
        step(failed, "profile balanced", () -> {
            boolean ok = fromClient(client -> {
                AutoPvp autoPvp = Modules.get().get(AutoPvp.class);
                return autoPvp != null && autoPvp.useProfile("balanced").ok();
            });
            if (!ok) throw new BenchException("the balanced profile was not applied");
        });
        for (Runnable r : despawn) step(failed, "despawn", r);
        for (Runnable r : screenRestore) step(failed, "screen restore", r);
        step(failed, "modules left on", () -> {
            List<String> on = fromClient(client -> {
                List<String> names = new ArrayList<>();
                for (Module module : groupModules()) {
                    if (module.isActive()) names.add(module.name);
                }
                return names;
            });
            if (!on.isEmpty()) throw new BenchException(String.join(", ", on).toLowerCase(Locale.ROOT) + " still on");
        });
        return failed;
    }

    /** Copies of the combat and Xploits module groups. Client thread. */
    private static List<Module> groupModules() {
        List<Module> modules = new ArrayList<>(Modules.get().getGroup(Categories.Combat));
        modules.addAll(Modules.get().getGroup(XploitsAddon.CATEGORY));
        return modules;
    }

    private static void step(List<String> failed, String name, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException | AssertionError e) {
            failed.add(name + " (" + BenchTest.describe(e) + ")");
        }
    }
}
