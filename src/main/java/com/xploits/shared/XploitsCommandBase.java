package com.xploits.shared;

import com.xploits.console.Salida;
import com.xploits.console.core.Formato;
import com.xploits.console.core.Nivel;
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
        Salida.mensaje(Nivel.INFO, getName(), message.getString());
        super.info(message);
    }

    @Override
    public void info(String message, Object... args) {
        log(Nivel.INFO, message, args);
        super.info(message, args);
    }

    @Override
    public void warning(String message, Object... args) {
        log(Nivel.AVISO, message, args);
        super.warning(message, args);
    }

    @Override
    public void error(String message, Object... args) {
        log(Nivel.ERROR, message, args);
        super.error(message, args);
    }

    public void info(Msg msg) {
        String text = Texts.render(msg);
        Salida.mensaje(Nivel.INFO, getName(), text);
        super.info("%s", text); // i18n: allowed
    }

    public void warning(Msg msg) {
        String text = Texts.render(msg);
        Salida.mensaje(Nivel.AVISO, getName(), text);
        super.warning("%s", text); // i18n: allowed
    }

    public void error(Msg msg) {
        String text = Texts.render(msg);
        Salida.mensaje(Nivel.ERROR, getName(), text);
        super.error("%s", text); // i18n: allowed
    }

    protected void reply(Nivel level, String source, PositionedMsg msg) {
        Salida.mensaje(level, source, Texts.render(Salida.paraConsola(msg)));
        String chat = Texts.render(msg.chat());
        switch (level) {
            case INFO -> super.info("%s", chat); // i18n: allowed
            case AVISO -> super.warning("%s", chat); // i18n: allowed
            case ERROR -> super.error("%s", chat); // i18n: allowed
        }
    }

    private void log(Nivel level, String template, Object[] args) {
        Formato.Resultado r = Formato.aplicar(Texts.catalog(Texts.current()), template, args);
        Salida.mensaje(r.roto() ? Nivel.ERROR : level, getName(), r.texto());
    }
}
