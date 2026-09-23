package com.xploits.travel.core;

/**
 * Los ocho ejes de autopista del plano XZ: los cuatro cardinales y las cuatro diagonales.
 *
 * <p>Las diagonales no son un añadido cosmético: en un servidor anarchy son la mitad de las
 * autopistas que existen, y sin ellas el modo autopista solo sirve para la mitad de los viajes.
 *
 * <p><b>Cada eje lleva su vector unitario</b>, y de ahí sale la decisión que gobierna todo lo demás:
 * {@code highway-distance} son <b>bloques recorridos</b>, no bloques por coordenada. Una distancia
 * de N por {@link #X_PLUS_Z_PLUS} avanza {@code N/√2} en X y otro tanto en Z, y el vuelo mide N,
 * exactamente igual que una distancia de N por {@link #X_PLUS}. El ajuste significa lo mismo en los
 * ocho ejes, que es lo único que permite compararlos sin hacer cuentas: "20 000 bloques" es siempre
 * el mismo trozo de fuegos artificiales, apunte a donde apunte.
 *
 * <p>Por eso el par de signos que identifica al eje se normaliza en el constructor en vez de
 * escribirse ya normalizado: así el enum se lee como lo que es -{@code (1, 1)} es la diagonal de
 * X+ y Z+- y la normalización, que es la regla de verdad, aparece una sola vez y no ocho.
 *
 * <p><b>Los nombres son el identificador que Meteor guarda en disco.</b> {@code EnumSetting.save}
 * escribe {@code get().toString()} y {@code load} lo busca entre los valores comparando otra vez
 * {@code toString()}; si no lo encuentra, {@code parse} no asigna nada y el ajuste se queda en su
 * valor de fábrica. Así que rebautizar los cuatro cardinales -o darles un {@code toString()} más
 * bonito- le cambiaría el eje en silencio a quien tuviera uno guardado. Las cuatro diagonales se
 * nombran siguiendo el mismo esquema, que además es el que se lee de un vistazo en el desplegable
 * de la ClickGUI: el desplegable pinta {@code toString()} tal cual, sin retocarlo.
 */
public enum Axis {
    X_PLUS(1, 0),
    X_MINUS(-1, 0),
    Z_PLUS(0, 1),
    Z_MINUS(0, -1),
    X_PLUS_Z_PLUS(1, 1),
    X_PLUS_Z_MINUS(1, -1),
    X_MINUS_Z_PLUS(-1, 1),
    X_MINUS_Z_MINUS(-1, -1);

    private final double unitX;
    private final double unitZ;

    /**
     * @param signX hacia dónde va el eje en X: 1, 0 o -1
     * @param signZ hacia dónde va el eje en Z: 1, 0 o -1
     */
    Axis(int signX, int signZ) {
        double length = Math.hypot(signX, signZ);
        this.unitX = signX / length;
        this.unitZ = signZ / length;
    }

    /**
     * La componente X del vector unitario del eje. En los cardinales vale 1, 0 o -1 exactos
     * -{@code hypot(1, 0)} es 1.0 sin error de redondeo-, así que los cuatro ejes de siempre
     * resuelven al mismo punto que resolvían antes de existir las diagonales.
     */
    public double unitX() {
        return unitX;
    }

    /** La componente Z del vector unitario del eje. */
    public double unitZ() {
        return unitZ;
    }

    /** Si el eje es una de las cuatro diagonales, donde la distancia se reparte entre X y Z. */
    public boolean isDiagonal() {
        return unitX != 0 && unitZ != 0;
    }
}
