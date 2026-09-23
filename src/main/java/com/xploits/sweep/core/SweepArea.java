package com.xploits.sweep.core;

import com.xploits.shared.core.i18n.Msg;

/**
 * El rectángulo a barrer, en coordenadas de chunk del Nether, con las esquinas ya normalizadas
 * (min ≤ max en cada eje). El jugador lo piensa en chunks del Nether porque ahí es donde vuela;
 * {@link #overworldEquivalent()} lo traduce a bloques del Overworld (×8 sobre bloques del Nether,
 * y cada chunk son 16 bloques) para que sepa qué extensión real está cubriendo.
 */
public record SweepArea(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
    private static final int BLOQUES_POR_CHUNK = 16;
    private static final int RATIO_NETHER_OVERWORLD = 8;

    /**
     * Rechaza, no reordena. Los nombres de los componentes prometen cuál es el borde menor y cuál
     * el mayor de cada eje; si se aceptara un min &gt; max en silencio, {@code chunkCount()} y
     * {@code overworldEquivalent()} podían contradecirse en la misma llamada (una anunciaba área
     * negativa, la otra un recuento positivo). Sigue el precedente de {@code Destination.java}: el
     * constructor canónico valida y rechaza, no repara; quien no conozca el orden de las esquinas
     * usa {@link #ofChunks} para que se normalicen antes de llegar aquí.
     */
    public SweepArea {
        if (minChunkX > maxChunkX || minChunkZ > maxChunkZ) {
            throw new IllegalArgumentException(
                "minChunkX/minChunkZ deben ser <= que maxChunkX/maxChunkZ respectivamente; "
                    + "usa ofChunks si no conoces de antemano el orden de las esquinas");
        }
    }

    /**
     * Construye el área a partir de dos esquinas cualesquiera, en cualquier orden: normaliza cada
     * eje por separado con min/max, porque dar la esquina «mayor» primero es el error de dedo más
     * fácil de cometer al teclear coordenadas.
     */
    public static SweepArea ofChunks(int chunkX1, int chunkZ1, int chunkX2, int chunkZ2) {
        return new SweepArea(
            Math.min(chunkX1, chunkX2), Math.min(chunkZ1, chunkZ2),
            Math.max(chunkX1, chunkX2), Math.max(chunkZ1, chunkZ2));
    }

    /** Anchura en chunks, contando ambos bordes: un área de un solo chunk mide 1, no 0. */
    public int widthInChunks() {
        return maxChunkX - minChunkX + 1;
    }

    /** Altura en chunks, contando ambos bordes. */
    public int heightInChunks() {
        return maxChunkZ - minChunkZ + 1;
    }

    /**
     * Número total de chunks del rectángulo. Se multiplica en {@code long} para no desbordar antes
     * de comprobar el rango: con anchura y altura como {@code int}, hacer la cuenta directamente en
     * {@code int} podía envolverse a un número negativo con pinta de válido para áreas grandes pero
     * tecleables (muy por debajo del límite real del mundo). Si ni así cabe en un {@code int} se
     * falla alto en vez de devolver ese número envuelto.
     */
    public int chunkCount() {
        long total = (long) widthInChunks() * heightInChunks();
        if (total > Integer.MAX_VALUE) {
            throw new ArithmeticException(
                "el área tiene " + total + " chunks, demasiados para contarlos en un entero"); // i18n: allowed (exception message, continuation line)
        }
        return (int) total;
    }

    /**
     * Cuántos chunks puede tener como mucho un área para que se pueda barrer, y de dónde sale el
     * número.
     *
     * <p><b>Hace falta un tope porque dos recorridos del módulo cuestan el rectángulo entero</b>, no
     * lo que el jugador haya visto: {@code Coverage.seenIn} visita cada chunk del área para contar
     * los que ya están vistos, y {@code SweepPlanner} hace lo mismo en {@code bandaVistaEntera} para
     * decidir si una banda se salta. Los dos corren en el hilo principal desde un comando, así que
     * un área tecleada de 20.000 x 20.000 chunks -que los deslizadores de los ajustes permiten,
     * porque {@code sliderRange} solo dibuja el widget y no acota el valor- son 400 millones de
     * consultas por recorrido y el cliente parece colgado. El único freno que había más allá era el
     * {@code ArithmeticException} de {@link #chunkCount()}, que salta cerca de
     * {@code Integer.MAX_VALUE}, es decir <b>después</b> de que los recorridos ya lo hubieran
     * intentado.
     *
     * <p><b>De dónde sale el 4.000.000</b>, que es 2.000 x 2.000 chunks:
     *
     * <ul>
     *   <li><b>Por arriba, lo que tarda en volarse.</b> Con una anchura de pasada medida típica de
     *       unos 25 chunks, barrer un área de N chunks son del orden de {@code N * 16 / 25} bloques
     *       de vuelo; 4.000.000 de chunks son unos 2,5 millones de bloques, o sea <b>más de veinte
     *       horas</b> frente a los ~116.000 bloques y la hora larga del barrido de spec §6. Nadie
     *       planea eso: a partir de ahí es un error de tecleo, no un barrido.</li>
     *   <li><b>Por abajo, que no estorbe.</b> La caja entera que el jugador ha cruzado en meses en
     *       este servidor son 384 x 580 = 222.720 chunks (spec §1), así que el tope deja casi veinte
     *       veces de margen sobre todo lo que ha tocado nunca.</li>
     *   <li><b>Y lo que cuesta mirarlo.</b> Cuatro millones por recorrido, dos recorridos, son unos
     *       ocho millones de consultas a un {@code HashSet}: décimas de segundo en el peor caso
     *       admitido, en vez de las decenas de segundos del límite del deslizador.</li>
     * </ul>
     *
     * <p>Además, por debajo de este tope {@link #chunkCount()} no puede desbordar, así que todo lo
     * que se anuncia del área se puede contar en un {@code int} sin más comprobaciones.
     */
    public static final int MAXIMO_DE_CHUNKS = 4_000_000;

    /**
     * El motivo por el que este área es demasiado grande para barrerla, o {@code null} si se puede.
     *
     * <p><b>Se rechaza, no se recorta.</b> Recortar el rectángulo a un tamaño manejable dejaría
     * fuera terreno que el jugador pidió barrer y le devolvería un barrido «terminado» que nunca
     * miró esa parte: la única mentira que este módulo no puede contar (spec §9), entrando por una
     * puerta nueva. Un rechazo se ve; un recorte silencioso, no.
     *
     * <p>El motivo sigue el estilo del resto: dice el tamaño que tiene, el tope, y <b>a cuánto bajar
     * el lado largo</b> manteniendo el corto, que es lo accionable. Nombra los cuatro ajustes de las
     * esquinas tal y como aparecen en la interfaz.
     *
     * <p>La cuenta se hace en {@code long} y no llamando a {@link #chunkCount()} a propósito: este
     * método tiene que poder contestar precisamente sobre las áreas que hacen que aquél lance.
     */
    public Msg oversizeRejection() {
        long total = (long) widthInChunks() * heightInChunks();
        if (total <= MAXIMO_DE_CHUNKS) {
            return null;
        }

        int corto = Math.min(widthInChunks(), heightInChunks());
        long largoMaximo = MAXIMO_DE_CHUNKS / corto;
        // Si el largo que cabría es menor que el propio lado corto, ni un cuadrado de ese lado entra:
        // decirle "baja el largo a ese número" sería mandarle a un rectángulo que sigue sin caber.
        Msg queBajar = largoMaximo < corto
            ? Msg.of(SweepText.OVERSIZE_NOTHING_FITS, "short", corto)
            : Msg.of(SweepText.OVERSIZE_KEEP_SHORT, "short", corto, "max", largoMaximo);

        return Msg.of(SweepText.OVERSIZE, "width", widthInChunks(), "height", heightInChunks(), "total", total,
            "max", MAXIMO_DE_CHUNKS, "fix", queBajar);
    }

    /** Tamaño del rectángulo en bloques del Overworld, para que el jugador vea qué área real cubre. */
    public Msg overworldEquivalent() {
        long anchoBloques = (long) widthInChunks() * BLOQUES_POR_CHUNK * RATIO_NETHER_OVERWORLD;
        long altoBloques = (long) heightInChunks() * BLOQUES_POR_CHUNK * RATIO_NETHER_OVERWORLD;
        return Msg.of(SweepText.OVERWORLD_EQUIVALENT, "width", anchoBloques, "height", altoBloques);
    }
}
