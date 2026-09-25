package com.xploits.bench;

import com.xploits.pvp.hud.AutoPvpHud;
import com.xploits.pvp.hud.core.HudText;
import com.xploits.pvp.hud.core.PanelInput;
import com.xploits.pvp.hud.core.PanelModel;
import com.xploits.pvp.recorder.FightRecorder;
import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.XAnchor;
import meteordevelopment.meteorclient.systems.hud.YAnchor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.message.ChatVisibility;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CHECK {@code panel} (spec {@code 2026-09-25-ingame-bench}, §Scenarios): with auto-pvp on in a fight,
 * the {@code xploits-pvp} panel is on, its header names the {@code balanced} profile, and a screenshot of
 * it is saved as {@code build/bench/panel.png}.
 *
 * <p>Standard loadout, recorder on, auto-pvp, a Still sparring. The Meteor HUD is turned on with every
 * element removed but {@link AutoPvpHud#INFO}, and chat is hidden, so the picture shows the panel and no
 * position. The teardown puts the HUD's elements back, turns the HUD off and shows chat again.
 */
final class Panel implements Scenario {
    private static final int RUN_TICKS = 100;
    /** Where the panel is drawn: a few pixels in from the top left corner. */
    private static final int PANEL_INSET = 4;

    private AutoPvpScene scene;
    /** The HUD as it was before the scenario changed it; null until then. Client thread. */
    private NbtCompound savedHud;
    private ChatVisibility savedChat;

    @Override
    public String name() {
        return "panel";
    }

    @Override
    public Kind kind() {
        return Kind.CHECK;
    }

    @Override
    public int seconds() {
        return 6;
    }

    @Override
    public void arrange(Bench bench) {
        scene = AutoPvpScene.arrange(bench, "balanced");
        bench.meteor(FightRecorder.class);
        bench.arena().loadout(false);
        bench.spawn(new Still());

        savedHud = null;
        savedChat = null;
        bench.atScreenRestore(() -> bench.onClient(this::restore));
        bench.onClient(client -> {
            Hud hud = Hud.get();
            savedHud = hud.toTag();
            savedChat = client.options.getChatVisibility().getValue();
            for (HudElement element : elements(hud)) element.remove();
            hud.add(AutoPvpHud.INFO, PANEL_INSET, PANEL_INSET, XAnchor.Left, YAnchor.Top);
            hud.active = true;
            client.options.getChatVisibility().setValue(ChatVisibility.HIDDEN);
        });
        bench.ticks(1);
        requireScreen(bench);
    }

    @Override
    public Metrics act(Bench bench) {
        scene.start(bench, true);
        bench.ticks(RUN_TICKS);

        PanelInput input = bench.fromClient(client -> scene.autoPvp.panelInput(true));
        Bench.check(input.autoPvpOn(), "the panel says auto-pvp is off");
        Catalog english = Catalog.load(Language.EN, problem -> { });
        String header = PanelModel.lines(input).stream()
            .filter(line -> line.text().key() == HudText.HEADER)
            .map(line -> english.render(line.text()))
            .findFirst().orElse(null);
        Bench.check(header != null, "the panel has no header line");
        Bench.check(header.contains("balanced"), "the panel header does not name the balanced profile");

        requireScreen(bench);
        // Advancement and recipe toasts are not the HUD: out of the picture.
        bench.onClient(client -> client.getToastManager().clear());
        Path shot = bench.screenshot("panel");
        Bench.check(shot.getFileName().toString().equals("panel.png") && written(shot), "panel.png was not written");
        bench.finish();
        return Metrics.none();
    }

    /** The screen is as the picture needs it: the HUD on with the panel alone, chat hidden. */
    private static void requireScreen(Bench bench) {
        boolean ready = bench.fromClient(client -> {
            Hud hud = Hud.get();
            List<HudElement> elements = elements(hud);
            return hud.active && elements.size() == 1 && elements.getFirst().info == AutoPvpHud.INFO
                && client.options.getChatVisibility().getValue() == ChatVisibility.HIDDEN;
        });
        if (!ready) throw new BenchException("the HUD does not show the panel alone, or chat is not hidden");
    }

    /** Teardown step 5: the HUD's own elements back, the HUD off, chat as it was (visible). Client thread. */
    private void restore(MinecraftClient client) {
        Hud hud = Hud.get();
        for (HudElement element : elements(hud)) element.remove();
        if (savedHud != null) hud.fromTag(savedHud);
        hud.active = false;
        ChatVisibility chat = savedChat == null || savedChat == ChatVisibility.HIDDEN ? ChatVisibility.FULL : savedChat;
        client.options.getChatVisibility().setValue(chat);
        if (hud.active || client.options.getChatVisibility().getValue() == ChatVisibility.HIDDEN) {
            throw new BenchException("the HUD is still on or chat is still hidden");
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
