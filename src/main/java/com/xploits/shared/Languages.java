package com.xploits.shared;

import com.xploits.XploitsAddon;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.LanguageChoice;
import com.xploits.shared.core.i18n.LanguageFile;
import com.xploits.shared.core.i18n.LanguageSync;
import com.xploits.shared.core.i18n.LanguageText;
import com.xploits.shared.core.i18n.Msg;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;

import java.io.IOException;

/** Keeps language.txt, the {@code language} setting and {@code .xploits language} in step (spec §5). */
public final class Languages {
    private static LanguageSync sync;
    private static String unreadable;
    private static final Startup STARTUP = new Startup();

    private Languages() {
    }

    /** First thing in {@code onInitialize}: descriptions are built right after, in this language. */
    public static void start() {
        LanguageFile.Read read;
        try {
            read = LanguageFile.parse(LanguageStore.read());
        } catch (IOException e) {
            read = new LanguageFile.Read(LanguageChoice.AUTO, e.getClass().getSimpleName());
        }
        sync = new LanguageSync(read.choice());
        unreadable = read.unreadable();
        if (unreadable != null) XploitsAddon.LOG.warn("Xploits: language.txt unreadable ({}), using Auto", unreadable);
        Texts.init(read.choice());
        MeteorClient.EVENT_BUS.subscribe(STARTUP);
    }

    static void settingChanged(LanguageChoice value) {
        if (sync == null) return;
        if (sync.settingChanged(value) == LanguageSync.Change.CHANGED) apply(value);
    }

    /** {@code .xploits language <code>}. */
    public static void choose(LanguageChoice value) {
        XploitsSettings module = module();
        if (sync.choose(value) == LanguageSync.Change.UNCHANGED) {
            if (module != null) module.info(LanguageText.ALREADY, "choice", value.toString());
            return;
        }
        if (module != null) module.language.set(value); // fires settingChanged → UNCHANGED
        apply(value);
    }

    /** {@code .xploits language}. */
    public static Msg describe() {
        LanguageChoice c = Texts.choice();
        if (c != LanguageChoice.AUTO) return Msg.of(LanguageText.CURRENT, "choice", c.toString());
        return Msg.of(LanguageText.CURRENT_AUTO, "language", name(Texts.current()), "minecraft", Texts.minecraftLanguage());
    }

    private static void apply(LanguageChoice value) {
        Language before = Texts.current();
        Texts.choose(value);
        Language after = Texts.current();
        XploitsSettings module = module();
        try {
            LanguageStore.write(value);
        } catch (IOException e) {
            if (module != null) module.error(LanguageText.FILE_UNWRITABLE, "reason", e.getClass().getSimpleName());
        }
        if (module == null) return;
        // Spec §5: mention the restart only when the resolved language actually changed.
        LanguageText key = before != after ? LanguageText.NOW_RESTART : LanguageText.NOW;
        module.info(key, "language", name(after));
    }

    private static LanguageText name(Language l) {
        return l == Language.ES ? LanguageText.NAME_ES : LanguageText.NAME_EN;
    }

    private static XploitsSettings module() {
        return Modules.get() == null ? null : Modules.get().get(XploitsSettings.class);
    }

    /** First tick: Meteor has loaded saved settings; the file wins. Then report a bad file once in a world. */
    private static final class Startup {
        private boolean forced;

        @EventHandler
        private void onTick(TickEvent.Post event) {
            if (!forced) {
                XploitsSettings module = module();
                if (module != null) module.language.set(sync.fileChoice());
                sync.finishLoading();
                forced = true;
            }
            if (unreadable != null) {
                if (MeteorClient.mc.world == null) return;
                XploitsSettings module = module();
                if (module != null) module.warning(LanguageText.FILE_UNREADABLE, "content", unreadable);
                unreadable = null;
            }
            MeteorClient.EVENT_BUS.unsubscribe(this);
        }
    }
}
