package com.xploits.sweep.core;

import com.xploits.shared.core.i18n.Msg;

import java.util.BitSet;

/**
 * Lleva la cuenta de cuántos chunks del área han llegado de verdad, para que el barrido pueda decir
 * al terminar <b>cuánto miró</b> en vez de solo que terminó (spec Nether Sweep §9).
 *
 * <p><b>El fallo que esta clase cierra.</b> El módulo leía la cobertura una sola vez, al lanzar, y
 * no la volvía a mirar: recorría los vértices y, al llegar al último, anunciaba «el barrido ha
 * terminado». Entre esas dos cosas no había ninguna comprobación de que los chunks del rectángulo
 * hubieran llegado. Una hora de vuelo con el servidor entregando con retraso, o el jugador
 * adelantándose a la entrega, y una fracción de cada banda no llega nunca: el jugador lee «Barrido
 * terminado», tacha la zona y no vuelve. Es exactamente la única mentira que el módulo no puede
 * contar -una cobertura incompleta marcada como completa- y encima no se descubre nunca.
 *
 * <p><b>Y el dato no hay que suponerlo.</b> Llega un {@code ChunkDataEvent} por cada chunk que el
 * servidor manda, al mismo bus al que el módulo ya está suscrito para medir la anchura de pasada.
 * Bastaba con mirar cuáles de esos chunks caen dentro del rectángulo. Esta clase es esa cuenta, en
 * el núcleo y con tests; el adaptador solo le pasa las coordenadas que van llegando.
 *
 * <p><b>Por qué también se cuentan los que ya estaban vistos antes de despegar.</b>
 * {@link SweepPlanner} se salta las bandas ya cubiertas enteras, así que esos chunks no van a llegar
 * durante el vuelo y no son terreno sin mirar: contarlos como huecos haría que todo barrido sobre un
 * área medio conocida pareciera fallido. Se marcan al construir, y así cada chunk del rectángulo
 * cuenta una sola vez, venga de la cobertura previa o del vuelo de hoy.
 *
 * <p><b>Por qué un {@link BitSet} y no un conjunto de {@link ChunkPos}.</b> El área puede llegar a
 * {@link SweepArea#MAXIMO_DE_CHUNKS}, cuatro millones de chunks: un {@code HashSet} de objetos ahí
 * son cientos de megas y un montón de basura que recoger en mitad del vuelo, mientras que cuatro
 * millones de bits son medio mega y una operación de coste fijo por chunk recibido. La cuenta del
 * índice es la misma que hace el recorrido del rectángulo, así que no hay nada que se pueda
 * desalinear.
 *
 * <p>Esta clase no toca Minecraft ni Meteor: recibe coordenadas de chunk ya leídas.
 */
public final class SweepTally {
    private final SweepArea area;
    private final BitSet cubiertos;
    private final int chunksDelArea;
    private final int yaVistos;

    private SweepTally(SweepArea area, BitSet cubiertos, int chunksDelArea, int yaVistos) {
        this.area = area;
        this.cubiertos = cubiertos;
        this.chunksDelArea = chunksDelArea;
        this.yaVistos = yaVistos;
    }

    /**
     * Arranca la cuenta sobre un área, marcando de entrada los chunks que la cobertura previa ya
     * daba por vistos.
     *
     * <p>Recorre el rectángulo entero una vez, igual que {@link Coverage#seenIn(SweepArea)} -de
     * hecho {@link #alreadySeen()} da ese mismo número, así que quien construya esto no necesita
     * llamar además a aquél y pagar el recorrido dos veces-.
     *
     * @throws NullPointerException si {@code area} o {@code seen} son nulos
     * @throws ArithmeticException  si el área tiene más chunks de los que caben en un {@code int};
     *                              el adaptador la rechaza mucho antes con
     *                              {@link SweepArea#oversizeRejection()}
     */
    public static SweepTally of(SweepArea area, Coverage seen) {
        if (area == null) throw new NullPointerException("hace falta un área para contar dentro de ella");
        if (seen == null) {
            throw new NullPointerException("hace falta la cobertura previa: sin ella no se sabe qué"
                + " chunks del área no hacía falta volver a ver"); // i18n: allowed (exception message, continuation line)
        }

        int chunksDelArea = area.chunkCount();
        BitSet cubiertos = new BitSet(chunksDelArea);
        int yaVistos = 0;
        for (int x = area.minChunkX(); x <= area.maxChunkX(); x++) {
            for (int z = area.minChunkZ(); z <= area.maxChunkZ(); z++) {
                if (seen.seen(new ChunkPos(x, z))) {
                    cubiertos.set(indice(area, x, z));
                    yaVistos++;
                }
            }
        }
        return new SweepTally(area, cubiertos, chunksDelArea, yaVistos);
    }

    /**
     * Anota un chunk que acaba de llegar del servidor. Los de fuera del rectángulo se ignoran -son
     * la mayoría durante la aproximación- y los repetidos no cuentan dos veces.
     *
     * @return si este chunk era del área y no estaba cubierto todavía
     */
    public boolean record(int chunkX, int chunkZ) {
        if (chunkX < area.minChunkX() || chunkX > area.maxChunkX()) return false;
        if (chunkZ < area.minChunkZ() || chunkZ > area.maxChunkZ()) return false;

        int indice = indice(area, chunkX, chunkZ);
        if (cubiertos.get(indice)) return false;
        cubiertos.set(indice);
        return true;
    }

    /** Cuántos chunks tiene el rectángulo. */
    public int areaChunks() {
        return chunksDelArea;
    }

    /** Cuántos chunks del área ya estaban vistos al planificar, según la cobertura previa. */
    public int alreadySeen() {
        return yaVistos;
    }

    /** Cuántos chunks del área han llegado durante el barrido y no estaban vistos antes. */
    public int arrived() {
        return covered() - yaVistos;
    }

    /** Cuántos chunks del área están cubiertos: los de antes más los que han llegado. */
    public int covered() {
        return cubiertos.cardinality();
    }

    /** Cuántos chunks del área no ha visto nadie: ni la cobertura previa ni este barrido. */
    public int missing() {
        return chunksDelArea - covered();
    }

    /** Qué fracción del área está cubierta, en {@code [0, 1]}. */
    public double coveredFraction() {
        return covered() / (double) chunksDelArea;
    }

    /**
     * Si la cobertura se queda por debajo del suelo que el jugador considera aceptable. Quien
     * pregunte esto debe avisar <b>fuerte</b>: un barrido que cubrió la mitad no puede parecerse a
     * uno que cubrió todo, porque los dos terminan y solo uno hay que repetirlo.
     *
     * @param floor fracción mínima aceptable, en {@code [0, 1]}
     */
    public boolean shortOfCoverage(double floor) {
        return coveredFraction() < floor;
    }

    /**
     * Lo que hay que decirle al jugador al terminar: cuántos de los N chunks del área llegaron, de
     * dónde salen y cuántos faltan.
     *
     * <p><b>El porcentaje se redondea hacia abajo</b>, nunca al más cercano: con 3.590 de 3.600 la
     * cuenta da 99,7 %, y un «100 %» sobre diez chunks sin ver sería la misma mentira que esta clase
     * existe para no contar, solo que redactada por el redondeo. Así, un 100 % solo aparece cuando
     * de verdad no falta ninguno.
     */
    public Msg summary() {
        int porcentaje = (int) Math.floor(coveredFraction() * 100);
        if (missing() == 0) {
            return Msg.of(SweepText.COVERAGE_COMPLETE, "covered", covered(), "total", chunksDelArea,
                "percent", porcentaje, "seen", yaVistos, "arrived", arrived());
        }
        return Msg.of(SweepText.COVERAGE_MISSING, "covered", covered(), "total", chunksDelArea,
            "percent", porcentaje, "seen", yaVistos, "arrived", arrived(), "missing", missing());
    }

    private static int indice(SweepArea area, int chunkX, int chunkZ) {
        return (chunkX - area.minChunkX()) * area.heightInChunks() + (chunkZ - area.minChunkZ());
    }
}
