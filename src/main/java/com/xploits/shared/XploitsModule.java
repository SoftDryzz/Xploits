package com.xploits.shared;

import com.xploits.console.ConsoleOutput;
import com.xploits.console.core.Level;
import com.xploits.console.core.SafeFormat;
import com.xploits.shared.core.PositionedMsg;
import com.xploits.shared.core.i18n.MessageKey;
import com.xploits.shared.core.i18n.Msg;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.text.Text;

/**
 * The base of Xploits' modules: everything they say in chat also goes to the console (console
 * spec §4). The line is logged first and Meteor is called afterwards, so Meteor behaves exactly as
 * before, including throwing when the format is broken.
 *
 * <p>Whatever carries coordinates does not come through here: it goes through {@link #infoPrivate}
 * and its siblings, with its position-free version for the console (console spec §7).
 */
public abstract class XploitsModule extends Module {
    protected XploitsModule(Category category, String name, String description) {
        super(category, name, description);
    }

    @Override
    public void info(Text message) {
        ConsoleOutput.message(Level.INFO, name, message.getString());
        super.info(message);
    }

    @Override
    public void info(String message, Object... args) {
        log(Level.INFO, message, args);
        super.info(message, args);
    }

    @Override
    public void warning(String message, Object... args) {
        log(Level.WARNING, message, args);
        super.warning(message, args);
    }

    @Override
    public void error(String message, Object... args) {
        log(Level.ERROR, message, args);
        super.error(message, args);
    }

    public void info(Msg msg) {
        String text = Texts.render(msg);
        ConsoleOutput.message(Level.INFO, name, text);
        super.info("%s", text); // i18n: allowed
    }

    public void warning(Msg msg) {
        String text = Texts.render(msg);
        ConsoleOutput.message(Level.WARNING, name, text);
        super.warning("%s", text); // i18n: allowed
    }

    public void error(Msg msg) {
        String text = Texts.render(msg);
        ConsoleOutput.message(Level.ERROR, name, text);
        super.error("%s", text); // i18n: allowed
    }

    public void info(MessageKey key, Object... namesAndValues) {
        info(Msg.of(key, namesAndValues));
    }

    public void warning(MessageKey key, Object... namesAndValues) {
        warning(Msg.of(key, namesAndValues));
    }

    public void error(MessageKey key, Object... namesAndValues) {
        error(Msg.of(key, namesAndValues));
    }

    public void infoPrivate(PositionedMsg msg) {
        ConsoleOutput.message(Level.INFO, name, Texts.render(ConsoleOutput.forConsole(msg)));
        super.info("%s", Texts.render(msg.chat())); // i18n: allowed
    }

    public void warningPrivate(PositionedMsg msg) {
        ConsoleOutput.message(Level.WARNING, name, Texts.render(ConsoleOutput.forConsole(msg)));
        super.warning("%s", Texts.render(msg.chat())); // i18n: allowed
    }

    public void errorPrivate(PositionedMsg msg) {
        ConsoleOutput.message(Level.ERROR, name, Texts.render(ConsoleOutput.forConsole(msg)));
        super.error("%s", Texts.render(msg.chat())); // i18n: allowed
    }

    /** To the console only, not to chat: for what was already said some other way. */
    public void logToConsole(Level level, Msg msg) {
        ConsoleOutput.message(level, name, Texts.render(msg));
    }

    /** What the module is doing right now, in 30 characters at most; empty if nothing. Game thread only. */
    public String activity() {
        return "";
    }

    private void log(Level level, String template, Object[] args) {
        SafeFormat.Result r = SafeFormat.apply(Texts.catalog(Texts.current()), template, args);
        ConsoleOutput.message(r.broken() ? Level.ERROR : level, name, r.text());
    }
}
