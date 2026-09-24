package com.xploits.shared;

import com.xploits.console.ConsoleOutput;
import com.xploits.console.core.Level;
import com.xploits.console.core.SafeFormat;
import com.xploits.shared.core.PositionedMsg;
import com.xploits.shared.core.i18n.Msg;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.text.Text;

/**
 * The base of Xploits' commands, for the same reason as {@link XploitsModule}. What a command repeats
 * on behalf of a module goes through {@link #reply}, with that module as its source, so the console's
 * filter finds it together with the rest of that module's lines.
 */
public abstract class XploitsCommandBase extends Command {
    protected XploitsCommandBase(String name, String description, String... aliases) {
        super(name, description, aliases);
    }

    @Override
    public void info(Text message) {
        ConsoleOutput.message(Level.INFO, getName(), message.getString());
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
        ConsoleOutput.message(Level.INFO, getName(), text);
        super.info("%s", text); // i18n: allowed
    }

    public void warning(Msg msg) {
        String text = Texts.render(msg);
        ConsoleOutput.message(Level.WARNING, getName(), text);
        super.warning("%s", text); // i18n: allowed
    }

    public void error(Msg msg) {
        String text = Texts.render(msg);
        ConsoleOutput.message(Level.ERROR, getName(), text);
        super.error("%s", text); // i18n: allowed
    }

    protected void reply(Level level, String source, PositionedMsg msg) {
        ConsoleOutput.message(level, source, Texts.render(ConsoleOutput.forConsole(msg)));
        String chat = Texts.render(msg.chat());
        switch (level) {
            case INFO -> super.info("%s", chat); // i18n: allowed
            case WARNING -> super.warning("%s", chat); // i18n: allowed
            case ERROR -> super.error("%s", chat); // i18n: allowed
        }
    }

    private void log(Level level, String template, Object[] args) {
        SafeFormat.Result r = SafeFormat.apply(Texts.catalog(Texts.current()), template, args);
        ConsoleOutput.message(r.broken() ? Level.ERROR : level, getName(), r.text());
    }
}
