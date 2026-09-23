package com.xploits.shared;

import com.xploits.console.Salida;
import com.xploits.console.core.Formato;
import com.xploits.console.core.Nivel;
import com.xploits.shared.core.PositionedMsg;
import com.xploits.shared.core.TextoConPosicion;
import com.xploits.shared.core.i18n.MessageKey;
import com.xploits.shared.core.i18n.Msg;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.text.Text;

/**
 * La base de los módulos de Xploits: todo lo que dicen por el chat va también a la consola
 * (spec consola §4). Primero se registra y después se llama a Meteor, que se comporta exactamente
 * como antes, incluido lanzar si el formato está roto.
 *
 * <p>Lo que lleva coordenadas no pasa por aquí: va por {@link #infoPrivado} y compañía, con su
 * versión sin posición para la consola (spec consola §7).
 */
public abstract class XploitsModule extends Module {
    protected XploitsModule(Category categoria, String nombre, String descripcion) {
        super(categoria, nombre, descripcion);
    }

    @Override
    public void info(Text message) {
        Salida.mensaje(Nivel.INFO, name, message.getString());
        super.info(message);
    }

    @Override
    public void info(String message, Object... args) {
        anotar(Nivel.INFO, message, args);
        super.info(message, args);
    }

    @Override
    public void warning(String message, Object... args) {
        anotar(Nivel.AVISO, message, args);
        super.warning(message, args);
    }

    @Override
    public void error(String message, Object... args) {
        anotar(Nivel.ERROR, message, args);
        super.error(message, args);
    }

    public void infoPrivado(TextoConPosicion texto) {
        Salida.mensaje(Nivel.INFO, name, texto.registro());
        super.info("%s", texto.chat());
    }

    public void warningPrivado(TextoConPosicion texto) {
        Salida.mensaje(Nivel.AVISO, name, texto.registro());
        super.warning("%s", texto.chat());
    }

    public void errorPrivado(TextoConPosicion texto) {
        Salida.mensaje(Nivel.ERROR, name, texto.registro());
        super.error("%s", texto.chat());
    }

    /** Solo a la consola, sin chat: para lo que ya se dijo por otro camino. */
    public void registrar(Nivel nivel, String texto) {
        Salida.mensaje(nivel, name, texto);
    }

    public void info(Msg msg) {
        String text = Texts.render(msg);
        Salida.mensaje(Nivel.INFO, name, text);
        super.info("%s", text);
    }

    public void warning(Msg msg) {
        String text = Texts.render(msg);
        Salida.mensaje(Nivel.AVISO, name, text);
        super.warning("%s", text);
    }

    public void error(Msg msg) {
        String text = Texts.render(msg);
        Salida.mensaje(Nivel.ERROR, name, text);
        super.error("%s", text);
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

    public void infoPrivado(PositionedMsg msg) {
        Salida.mensaje(Nivel.INFO, name, Texts.render(msg.log()));
        super.info("%s", Texts.render(msg.chat()));
    }

    public void warningPrivado(PositionedMsg msg) {
        Salida.mensaje(Nivel.AVISO, name, Texts.render(msg.log()));
        super.warning("%s", Texts.render(msg.chat()));
    }

    public void errorPrivado(PositionedMsg msg) {
        Salida.mensaje(Nivel.ERROR, name, Texts.render(msg.log()));
        super.error("%s", Texts.render(msg.chat()));
    }

    public void registrar(Nivel nivel, Msg msg) {
        Salida.mensaje(nivel, name, Texts.render(msg));
    }

    /** Qué hace ahora, en 30 caracteres como mucho; vacío si nada. Solo desde el hilo del juego. */
    public String ahora() {
        return "";
    }

    private void anotar(Nivel nivel, String plantilla, Object[] args) {
        Formato.Resultado r = Formato.aplicar(Texts.catalog(Texts.current()), plantilla, args);
        Salida.mensaje(r.roto() ? Nivel.ERROR : nivel, name, r.texto());
    }
}
