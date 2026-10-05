package com.xploits.bench;

import com.xploits.console.ConsoleModule;
import com.xploits.kitrequester.KitRequester;
import com.xploits.pvp.core.CrystalModule;
import com.xploits.pvp.crystal.CrystalAuraPlusPlus;
import com.xploits.pvp.crystal.core.CrystalSetting;
import com.xploits.pvp.hud.AutoPvpHud;
import com.xploits.pvp.recorder.FightRecorder;
import com.xploits.restock.Restock;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.tabs.Tabs;
import meteordevelopment.meteorclient.gui.tabs.builtin.ModulesTab;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.XAnchor;
import meteordevelopment.meteorclient.systems.hud.YAnchor;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.message.ChatVisibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The README's screenshots. Not a bench scenario: {@link Scenarios} does not list it, and only
 * {@code ./gradlew runClientGameTest -Pshots} runs it, alone, with no report ({@code tools/shots.ps1} does that and
 * copies the pictures to {@code docs/images}).
 *
 * <p>In a bench world, in a {@value #WIDTH}x{@value #HEIGHT} window with the GUI at scale {@value #GUI_SCALE}, it
 * saves in {@code build/shots}: {@code clickgui.png} (Meteor's ClickGUI), {@code crystal-aura-pp.png}
 * (crystal-aura++'s settings), {@code restock.png} (restock's settings) and
 * {@code fight-1.png} to {@code fight-}{@value #FIGHT_SHOTS}{@code .png} (auto-pvp
 * driving crystal-aura++ against the sparring, with the auto-pvp panel as the only HUD element and chat hidden).
 * During the fight it turns the console on, writes {@code console.ready} and waits, at most
 * {@value #CONSOLE_WAIT_TICKS} ticks, for {@code console.done}: {@code tools/shots.ps1} captures the console
 * window in between. Without that file it goes on without the console picture.
 *
 * <p>No picture carries a position: the HUD shows the panel alone, chat is hidden, the camera turns with
 * {@code /rotate} (no position), and the only name on screen is the sparring's. The teardown puts the HUD, chat,
 * the GUI scale and the settings the scene changed back.
 */
final class Shots implements Scenario {
    /** The system property {@code build.gradle.kts} sets with {@code -Pshots}: the folder the pictures go to. */
    static final String FOLDER_PROPERTY = "xploits.shots";
    static final int WIDTH = 1280;
    static final int HEIGHT = 720;
    static final int GUI_SCALE = 2;
    static final int FIGHT_SHOTS = 6;
    /** Ticks between two fight pictures, from the first one on. */
    private static final int BETWEEN_FIGHT_SHOTS = 30;
    /** Ticks auto-pvp gets to engage and crystal-aura++ to start placing before the first fight picture. */
    private static final int FIGHT_WARMUP_TICKS = 60;
    /** Ticks the console gets to open and log the fight before {@code console.ready}. */
    private static final int CONSOLE_OPEN_TICKS = 300;
    static final int CONSOLE_WAIT_TICKS = 600;
    /** crystal-aura++'s {@code min-damage} in the scene; see {@link #arrange}. */
    private static final double SCENE_MIN_DAMAGE = 3.0;
    /** Ticks a screen gets to lay itself out before its picture. */
    private static final int SCREEN_TICKS = 5;
    private static final int PANEL_INSET = 4;
    /** Towards +x, where {@link Still} stands. */
    private static final int FACE_YAW = -90;
    /** Degrees below the horizon: the sparring's body and its feet both in the picture. */
    private static final int FACE_PITCH = 12;
    private static final Logger LOG = LoggerFactory.getLogger("xploits-bench");

    private AutoPvpScene scene;
    /** The HUD, chat and GUI scale as they were before this run changed them; null until then. Client thread. */
    private NbtCompound savedHud;
    private ChatVisibility savedChat;
    private Integer savedGuiScale;

    /** The window size the pictures are taken at; before the world, from the gametest thread. */
    static void frame(ClientGameTestContext ctx) {
        ctx.getInput().resizeWindow(WIDTH, HEIGHT);
    }

    @Override
    public String name() {
        return "shots";
    }

    @Override
    public Kind kind() {
        return Kind.CHECK;
    }

    @Override
    public int seconds() {
        return (FIGHT_WARMUP_TICKS + FIGHT_SHOTS * BETWEEN_FIGHT_SHOTS + CONSOLE_OPEN_TICKS + CONSOLE_WAIT_TICKS) / 20 + 10;
    }

    /** Health comes back, so crystal-aura++ keeps fighting instead of holding at its reserve for the pictures. */
    @Override
    public boolean naturalRegeneration() {
        return true;
    }

    @Override
    public void arrange(Bench bench) {
        scene = AutoPvpScene.arrange(bench, "balanced", CrystalModule.XPLOITS);
        // The sparring stands still with endless totems: with Meteor's default min-damage (6), crystal-aura++ placed
        // nothing more after its first two pops against the sparring's armour and absorption, and the pictures showed
        // an idle fight. A lower one keeps it going.
        CrystalAuraPlusPlus plusPlus = bench.fromClient(client -> Modules.get().get(CrystalAuraPlusPlus.class));
        bench.setting(plusPlus, "General", "min-damage", SCENE_MIN_DAMAGE);
        bench.atScreenRestore(() -> bench.onClient(client -> plusPlus.settings.reset()));
        // No hole near the sparring for hole-filler to fill: it would only show up as idle, in red.
        bench.setting(scene.autoPvp, "Modules", "use-hole-filler", false);
        bench.meteor(FightRecorder.class);
        bench.meteor(ConsoleModule.class);
        // kit-requester's couriers are player names, and auto-pvp announces them as it adds them to Meteor's friends:
        // none in the pictures. Back to their default at the teardown.
        KitRequester kits = bench.meteor(KitRequester.class);
        bench.setting(kits, "General", "known-couriers", List.of());
        bench.atScreenRestore(() -> bench.onClient(client -> kits.settings.reset()));
        bench.arena().loadout(false);
        bench.spawn(new Still());

        savedHud = null;
        savedChat = null;
        savedGuiScale = null;
        bench.atScreenRestore(() -> bench.onClient(this::restore));
        bench.onClient(client -> {
            savedGuiScale = client.options.getGuiScale().getValue();
            client.options.getGuiScale().setValue(GUI_SCALE);
            client.onResolutionChanged();
            Hud hud = Hud.get();
            savedHud = hud.toTag();
            savedChat = client.options.getChatVisibility().getValue();
            for (HudElement element : elements(hud)) element.remove();
            hud.add(AutoPvpHud.INFO, PANEL_INSET, PANEL_INSET, XAnchor.Left, YAnchor.Top);
            hud.active = true;
            client.options.getChatVisibility().setValue(ChatVisibility.HIDDEN);
            client.getToastManager().clear();
        });
        // Face the sparring, which stands along +x (Still), looking a little down so its feet, where the crystals go,
        // are in the picture: a rotation only, no position. Not "tp ... facing entity": the sparring is not in the
        // server's player list, so a command cannot name it.
        bench.command("rotate " + Bench.PLAYER + " " + FACE_YAW + " " + FACE_PITCH);
        bench.ticks(1);
    }

    @Override
    public Metrics act(Bench bench) {
        Path out = folder();
        requireScreen(bench);

        // The screens first, with the HUD and hands hidden (F1): nothing but the GUI in them.
        bench.onClient(client -> client.options.hudHidden = true);
        screen(bench, "clickgui", client -> Tabs.get(ModulesTab.class).openScreen(GuiThemes.get()));
        plusPlusSettings(bench);
        screen(bench, "restock", client -> client.setScreen(GuiThemes.get().moduleScreen(Modules.get().get(Restock.class))));
        bench.onClient(client -> client.options.hudHidden = false);

        scene.start(bench, true);
        bench.onClient(client -> Modules.get().get(ConsoleModule.class).enable());
        bench.ticks(FIGHT_WARMUP_TICKS);
        for (int i = 1; i <= FIGHT_SHOTS; i++) {
            requireScreen(bench);
            bench.onClient(client -> client.getToastManager().clear());
            shot(bench, "fight-" + i);
            bench.ticks(BETWEEN_FIGHT_SHOTS);
        }

        bench.ticks(CONSOLE_OPEN_TICKS);
        Bench.check(bench.fromClient(client -> Modules.get().get(ConsoleModule.class).isActive()), "the console turned itself off");
        write(out.resolve("console.ready"));
        int waited = 0;
        while (waited < CONSOLE_WAIT_TICKS && !Files.exists(out.resolve("console.done"))) {
            bench.ticks(1);
            waited++;
        }
        if (Files.exists(out.resolve("console.done"))) LOG.info("[bench] shots: the console was captured");
        else LOG.warn("[bench] shots: nobody captured the console in {} ticks; no console picture", CONSOLE_WAIT_TICKS);
        bench.finish();
        return Metrics.none();
    }

    /**
     * crystal-aura++'s settings with only its own section open, {@code Safety} ({@code risk}, {@code self-budget},
     * {@code finishing-blow}); Meteor's sections folded. Which sections are open is saved with the module, so it is
     * put back right after the picture.
     */
    private static void plusPlusSettings(Bench bench) {
        CrystalAuraPlusPlus module = bench.fromClient(client -> Modules.get().get(CrystalAuraPlusPlus.class));
        Map<SettingGroup, Boolean> saved = bench.fromClient(client -> {
            Map<SettingGroup, Boolean> was = new LinkedHashMap<>();
            for (SettingGroup group : module.settings) {
                was.put(group, group.sectionExpanded);
                group.sectionExpanded = group.name.equals(CrystalSetting.Group.SAFETY.title());
            }
            return was;
        });
        try {
            screen(bench, "crystal-aura-pp", client -> client.setScreen(GuiThemes.get().moduleScreen(module)));
        } finally {
            bench.onClient(client -> saved.forEach((group, expanded) -> group.sectionExpanded = expanded));
        }
    }

    /** Opens a screen, lets it lay out, saves its picture and closes it. */
    private static void screen(Bench bench, String name, Consumer<MinecraftClient> open) {
        bench.onClient(open::accept);
        bench.ticks(SCREEN_TICKS);
        Bench.check(bench.fromClient(client -> client.currentScreen != null), name + ": the screen did not open");
        shot(bench, name);
        bench.onClient(client -> client.setScreen(null));
        bench.ticks(1);
    }

    private static void shot(Bench bench, String name) {
        Path file = bench.screenshot(name);
        Bench.check(written(file), name + ".png was not written");
    }

    /** The screen is as the fight pictures need it: the HUD on with the panel alone, chat hidden. */
    private static void requireScreen(Bench bench) {
        boolean ready = bench.fromClient(client -> {
            Hud hud = Hud.get();
            List<HudElement> elements = elements(hud);
            return hud.active && elements.size() == 1 && elements.getFirst().info == AutoPvpHud.INFO
                && client.options.getChatVisibility().getValue() == ChatVisibility.HIDDEN;
        });
        if (!ready) throw new BenchException("the HUD does not show the panel alone, or chat is not hidden");
    }

    /** Teardown step 5: the HUD's own elements back, the HUD off, chat and the GUI scale as they were. Client thread. */
    private void restore(MinecraftClient client) {
        client.options.hudHidden = false;
        Hud hud = Hud.get();
        for (HudElement element : elements(hud)) element.remove();
        if (savedHud != null) hud.fromTag(savedHud);
        hud.active = false;
        ChatVisibility chat = savedChat == null || savedChat == ChatVisibility.HIDDEN ? ChatVisibility.FULL : savedChat;
        client.options.getChatVisibility().setValue(chat);
        if (savedGuiScale != null) {
            client.options.getGuiScale().setValue(savedGuiScale);
            client.onResolutionChanged();
        }
        if (hud.active || client.options.getChatVisibility().getValue() == ChatVisibility.HIDDEN) {
            throw new BenchException("the HUD is still on or chat is still hidden");
        }
    }

    /** The folder {@code -Pshots} asked for. */
    static Path folder() {
        String folder = System.getProperty(FOLDER_PROPERTY);
        if (folder == null || folder.isBlank()) throw new BenchException(FOLDER_PROPERTY + " is not set: run it with tools/shots.ps1");
        return Path.of(folder);
    }

    private static void write(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "");
        } catch (IOException e) {
            throw new BenchException("could not write " + file.getFileName());
        }
    }

    private static List<HudElement> elements(Hud hud) {
        List<HudElement> elements = new ArrayList<>();
        for (HudElement element : hud) elements.add(element);
        return elements;
    }

    private static boolean written(Path file) {
        try {
            return Files.isRegularFile(file) && Files.size(file) > 0;
        } catch (IOException e) {
            return false;
        }
    }
}
