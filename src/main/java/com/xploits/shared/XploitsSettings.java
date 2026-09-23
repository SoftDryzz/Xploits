package com.xploits.shared;

import com.xploits.XploitsAddon;
import com.xploits.shared.core.i18n.LanguageChoice;
import com.xploits.shared.core.i18n.LanguageText;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.orbit.EventHandler;

/** The {@code xploits} module: holds settings only. Turned on, it says so and turns itself off. */
public class XploitsSettings extends XploitsModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<LanguageChoice> language = sgGeneral.add(new EnumSetting.Builder<LanguageChoice>()
        .name("language")
        .description(Texts.startupText(LanguageText.SETTING_LANGUAGE))
        .defaultValue(LanguageChoice.AUTO)
        .onChanged(Languages::settingChanged)
        .build());

    public XploitsSettings() {
        super(XploitsAddon.CATEGORY, "xploits", Texts.startupText(LanguageText.MODULE_DESC));
        runInMainMenu = true;
    }

    @Override
    public void onActivate() {
        info(LanguageText.ONLY_SETTINGS);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (isActive()) toggle();
    }
}
