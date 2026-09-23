package com.xploits.shared;

import com.xploits.console.Salida;
import com.xploits.console.core.Formato;
import com.xploits.console.core.Nivel;
import com.xploits.shared.core.TextoConPosicion;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.text.Text;

/**
 * La base de los comandos de Xploits, por la misma razón que {@link XploitsModule}. Lo que el
 * comando repite de un módulo va por {@link #responder}, con la fuente del módulo: así el filtro
 * de la consola lo encuentra con lo demás de ese módulo.
 */
public abstract class ComandoBase extends Command {
    protected ComandoBase(String nombre, String descripcion, String... alias) {
        super(nombre, descripcion, alias);
    }

    @Override
    public void info(Text message) {
        Salida.mensaje(Nivel.INFO, getName(), message.getString());
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

    protected void responder(Nivel nivel, String fuente, TextoConPosicion texto) {
        Salida.mensaje(nivel, fuente, texto.registro());
        switch (nivel) {
            case INFO -> super.info("%s", texto.chat());
            case AVISO -> super.warning("%s", texto.chat());
            case ERROR -> super.error("%s", texto.chat());
        }
    }

    private void anotar(Nivel nivel, String plantilla, Object[] args) {
        Formato.Resultado r = Formato.aplicar(plantilla, args);
        Salida.mensaje(r.roto() ? Nivel.ERROR : nivel, getName(), r.texto());
    }
}
