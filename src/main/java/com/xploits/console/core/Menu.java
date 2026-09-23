package com.xploits.console.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Msg;

/**
 * El menú de la ventana, como el de CustomCLI pero con números: la entrada de línea normal basta y
 * no hace falta poner la consola en modo tecla a tecla (spec consola §6).
 */
public final class Menu {
    private Menu() {
    }

    /** The menu line: the digits and their order are fixed, the words come from the catalog. */
    public static String linea(Catalog textos) {
        return textos.render(WindowText.MENU_LINE);
    }

    public sealed interface Orden {
    }

    public record CambiarFiltro(Filtro filtro) implements Orden {
    }

    public record AlternarPausa() implements Orden {
    }

    public record Salir() implements Orden {
    }

    public record Desconocida(Msg motivo) implements Orden {
    }

    public static Orden interpretar(String linea) {
        String limpia = linea.strip();
        return switch (limpia) {
            case "1" -> new CambiarFiltro(Filtro.TODO);
            case "2" -> new CambiarFiltro(Filtro.PVP);
            case "3" -> new CambiarFiltro(Filtro.TRAVEL);
            case "4" -> new CambiarFiltro(Filtro.SWEEP);
            case "5" -> new CambiarFiltro(Filtro.AVISOS);
            case "6" -> new AlternarPausa();
            case "0" -> new Salir();
            case "" -> new Desconocida(Msg.of(WindowText.MENU_EMPTY));
            default -> new Desconocida(Msg.of(WindowText.MENU_UNKNOWN, "input", Texto.recortar(Texto.limpiar(limpia), 20)));
        };
    }
}
