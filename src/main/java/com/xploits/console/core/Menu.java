package com.xploits.console.core;

/**
 * El menú de la ventana, como el de CustomCLI pero con números: la entrada de línea normal basta y
 * no hace falta poner la consola en modo tecla a tecla (spec consola §6).
 */
public final class Menu {
    public static final String LINEA = "[1] todo  [2] pvp  [3] travel  [4] sweep  [5] solo avisos  [6] pausar  [0] salir";

    private Menu() {
    }

    public sealed interface Orden {
    }

    public record CambiarFiltro(Filtro filtro) implements Orden {
    }

    public record AlternarPausa() implements Orden {
    }

    public record Salir() implements Orden {
    }

    public record Desconocida(String motivo) implements Orden {
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
            case "" -> new Desconocida("escribe el número de una opción del menú");
            default -> new Desconocida("«" + Texto.recortar(Texto.limpiar(limpia), 20) + "» no es una opción del menú");
        };
    }
}
