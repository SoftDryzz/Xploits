package com.xploits.pvp.hud;

import com.xploits.XploitsAddon;
import com.xploits.pvp.AutoPvp;
import com.xploits.pvp.hud.core.HudText;
import com.xploits.pvp.hud.core.PanelLine;
import com.xploits.pvp.hud.core.PanelModel;
import com.xploits.pvp.hud.core.Tone;
import com.xploits.shared.Texts;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

import java.util.List;

/**
 * The {@code xploits-pvp} HUD element (design spec §3 "HUD panel"): draws {@link PanelModel#lines} in
 * the player's language, one line per {@link PanelLine}, coloured by {@link Tone}. It is the adapter
 * only — every fact and every word comes from the pure core ({@code PanelModel}, {@code AutoPvp
 * .panelInput}); this class just measures text and draws it.
 *
 * <p>In the editor with nothing to show (no world to read {@link AutoPvp} from, {@code
 * !Utils.canUpdate()}), it draws {@link PanelModel#sample()} instead, so the panel can be sized and
 * placed before auto-pvp has ever run. Any exception while drawing is caught once per session — logged,
 * never shown to the player — after which the element draws nothing until the next world join
 * ("Precise rules" -&gt; HUD failure flag).
 */
public class AutoPvpHud extends HudElement {
    public static final HudGroup GROUP = new HudGroup("Xploits");
    public static final HudElementInfo<AutoPvpHud> INFO =
        new HudElementInfo<>(GROUP, "xploits-pvp", Texts.startupText(HudText.ELEMENT_DESCRIPTION), AutoPvpHud::new);

    /** Vertical space between two lines of the panel, on top of the text's own height. */
    private static final double LINE_GAP = 2;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description(Texts.startupText(HudText.SETTING_SCALE))
        .defaultValue(1)
        .min(0.5)
        .sliderRange(0.5, 3)
        .build()
    );

    private final Setting<Boolean> shadow = sgGeneral.add(new BoolSetting.Builder()
        .name("shadow")
        .description(Texts.startupText(HudText.SETTING_SHADOW))
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> background = sgGeneral.add(new ColorSetting.Builder()
        .name("background")
        .description(Texts.startupText(HudText.SETTING_BACKGROUND))
        .defaultValue(new SettingColor(0, 0, 0, 100))
        .build()
    );

    private final Setting<Boolean> showFight = sgGeneral.add(new BoolSetting.Builder()
        .name("show-fight")
        .description(Texts.startupText(HudText.SETTING_SHOW_FIGHT))
        .defaultValue(true)
        .build()
    );

    /** Set on the first render exception this session; cleared on {@link #onGameJoined}. */
    private boolean failed;

    public AutoPvpHud() {
        super(INFO);
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    /** "HUD failure flag resets on world join" (Precise rules). */
    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        failed = false;
    }

    @Override
    public void render(HudRenderer renderer) {
        if (failed) return;
        try {
            draw(renderer);
        } catch (RuntimeException e) {
            failed = true;
            XploitsAddon.LOG.error("xploits-pvp: the HUD panel failed to draw, it stays blank for the rest of this session", e);
        }
    }

    private void draw(HudRenderer renderer) {
        List<PanelLine> lines = linesToDraw();

        double lineScale = scale.get();
        boolean lineShadow = shadow.get();
        double lineHeight = renderer.textHeight(lineShadow, lineScale);

        double width = 0;
        for (PanelLine line : lines) {
            width = Math.max(width, renderer.textWidth(Texts.render(line.text()), lineShadow, lineScale));
        }
        double height = lines.isEmpty() ? 0 : lines.size() * lineHeight + (lines.size() - 1) * LINE_GAP;
        setSize(width, height);

        renderer.quad(x, y, width, height, background.get());

        double lineY = y;
        for (PanelLine line : lines) {
            renderer.text(Texts.render(line.text()), x, lineY, color(line.tone()), lineShadow, lineScale);
            lineY += lineHeight + LINE_GAP;
        }
    }

    /** The real panel, or {@link PanelModel#sample()} when there is no world to read {@link AutoPvp} from. */
    private List<PanelLine> linesToDraw() {
        if (!Utils.canUpdate()) return PanelModel.sample();
        AutoPvp autoPvp = Modules.get().get(AutoPvp.class);
        if (autoPvp == null) return PanelModel.sample();
        return PanelModel.lines(autoPvp.panelInput(showFight.get()));
    }

    /** {@link Tone}'s fixed {@code 0xRRGGBB} as the renderer's own colour type, opaque. */
    private static Color color(Tone tone) {
        int rgb = tone.rgb();
        return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }
}
