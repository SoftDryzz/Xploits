package com.xploits.shared;

import com.xploits.XploitsAddon;
import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.LanguageChoice;
import com.xploits.shared.core.i18n.MessageKey;
import com.xploits.shared.core.i18n.Msg;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.MinecraftClient;

import java.util.EnumMap;
import java.util.Map;

/**
 * Game side of the language: what the player chose and what it resolves to right now. Resolved on
 * every render, so Auto follows a Minecraft language change at once (spec §5).
 */
public final class Texts {
    private static final Map<Language, Catalog> CATALOGS = new EnumMap<>(Language.class);
    private static volatile LanguageChoice choice = LanguageChoice.AUTO;
    private static volatile Language startup = Language.EN;

    private Texts() {
    }

    /** Called once from {@code Languages.start}, itself called from {@code XploitsAddon.onInitialize}
     * before any module is built. */
    static void init(LanguageChoice fromFile) {
        choice = fromFile;
        startup = current();
    }

    static void choose(LanguageChoice c) {
        choice = c;
    }

    public static LanguageChoice choice() {
        return choice;
    }

    public static Language current() {
        return choice.resolve(minecraftLanguage());
    }

    /** The language descriptions were built in; they stay in it until a restart. */
    public static Language startup() {
        return startup;
    }

    public static String minecraftLanguage() {
        MinecraftClient mc = MeteorClient.mc;
        return mc == null || mc.options == null ? "" : mc.options.language;
    }

    public static synchronized Catalog catalog(Language language) {
        return CATALOGS.computeIfAbsent(language, l -> Catalog.load(l, p -> XploitsAddon.LOG.warn("Xploits texts: {}", p)));
    }

    public static String render(Msg msg) {
        return catalog(current()).render(msg);
    }

    public static String render(MessageKey key, Object... namesAndValues) {
        return render(Msg.of(key, namesAndValues));
    }

    /** For module and setting descriptions, which Meteor keeps from construction on. */
    public static String startupText(MessageKey key) {
        return catalog(startup).render(Msg.of(key));
    }
}
