package com.xploits.elytra.core;

import java.util.List;

/**
 * Decide cuándo cambiar la elytra puesta y por cuál (spec §4). Guarda tres cosas entre
 * decisiones: cuándo fue el último cambio, para no repetirlo mientras el slot de pechera se
 * actualiza; a qué porcentaje se avisó por última vez de que no hay repuesto, para no avisar
 * veinte veces por segundo; y cuándo fue ese último aviso, para rearmarlo por tiempo si la elytra
 * puesta cambia sin que la pechera llegue a quedar vacía.
 */
public final class ElytraPolicy {
    /** Ventana tras un SWAP durante la que no se repite el cambio, en milisegundos. */
    public static final long SWAP_COOLDOWN_MS = 2_000;

    /** Si han pasado tantos milisegundos desde el último aviso de "sin repuesto", se rearma. */
    public static final long WARNING_REARM_WINDOW_MS = 5 * 60 * 1_000;

    private static final long NEVER = Long.MIN_VALUE;

    private long lastSwapAt = NEVER;
    private Integer warnedAtPercent;
    private long lastWarnedAt = NEVER;

    /**
     * @param slot slot del repuesto elegido, o -1 si la decisión no es SWAP
     */
    public record Result(Decision decision, int slot, int wornPercent) {}

    /** Durabilidad restante en tanto por ciento. Un ítem sin durabilidad cuenta como intacto. */
    public static int percentOf(int damage, int maxDamage) {
        if (maxDamage <= 0) return 100;
        long remaining = (long) maxDamage - damage;
        int percent = (int) (remaining * 100 / maxDamage);
        return Math.max(0, Math.min(100, percent));
    }

    /**
     * @param wornPercent durabilidad de la elytra puesta, o null si no lleva ninguna
     * @param candidates  elytras sueltas del inventario
     * @param swapBelow   cambiar cuando la durabilidad de la puesta sea igual o menor que este porcentaje
     * @param minSpare    solo considerar repuestos con al menos este porcentaje
     * @param now         System.currentTimeMillis()
     */
    public Result decide(Integer wornPercent, List<ElytraCandidate> candidates,
                         int swapBelow, int minSpare, long now) {
        if (wornPercent == null) {
            // La pechera vacía es la señal más fiable de que ha cambiado la elytra: cubre morir
            // y la mayoría de los cambios a mano. Rearma aquí, no en shouldWarnNoSpare(), que no
            // repite esta regla: el rearme por subida o por pechera vacía vive en un solo sitio.
            warnedAtPercent = null;
            return new Result(Decision.NOT_WEARING, -1, 0);
        }

        // Ver subir el porcentaje de la puesta significa que es otra elytra: rearma el aviso de
        // "no hay repuesto". Esto se comprueba en cada decide(), no solo cuando se avisa, porque
        // decide() se llama en cada tick y es lo único que ve el cambio de elytra a tiempo (spec
        // §4.5). Es el único sitio donde se aplica esta regla: shouldWarnNoSpare() no la repite.
        if (warnedAtPercent != null && wornPercent > warnedAtPercent) {
            warnedAtPercent = null;
        }

        // Tras un cambio el slot de pechera tarda algún tick en reflejarlo (spec §4.4).
        // El centinela se comprueba aparte a propósito: "now - Long.MIN_VALUE" desborda a un número
        // negativo, que pasaría la comparación y dejaría el módulo sin cambiar la elytra nunca.
        if (lastSwapAt != NEVER && now >= lastSwapAt && now - lastSwapAt < SWAP_COOLDOWN_MS) {
            return new Result(Decision.OK, -1, wornPercent);
        }
        if (wornPercent > swapBelow) return new Result(Decision.OK, -1, wornPercent);

        ElytraCandidate best = null;
        for (ElytraCandidate candidate : candidates) {
            // Estrictamente mejor que la puesta: sin esto, un mínimo bajo encadena cambios (spec §4.3).
            if (candidate.percent() < minSpare || candidate.percent() <= wornPercent) continue;
            if (best == null
                || candidate.percent() < best.percent()
                || (candidate.percent() == best.percent() && candidate.slot() < best.slot())) {
                best = candidate;
            }
        }

        if (best == null) return new Result(Decision.NO_SPARE, -1, wornPercent);

        lastSwapAt = now;
        return new Result(Decision.SWAP, best.slot(), wornPercent);
    }

    /**
     * true si toca avisar ahora de que no hay repuesto. El rearme por subida de porcentaje o por
     * pechera vacía ya lo resuelve {@link #decide(Integer, List, int, int, long)} limpiando
     * {@code warnedAtPercent} antes de llegar aquí; este método solo añade el rearme por tiempo,
     * igual que {@code AutoTpyPolicy.shouldReportIgnored}, para el caso de cambiar de elytra sin
     * que la pechera llegue a quedar vacía.
     *
     * @param wornPercent durabilidad de la elytra puesta
     * @param now         System.currentTimeMillis()
     */
    public boolean shouldWarnNoSpare(int wornPercent, long now) {
        if (warnedAtPercent != null && lastWarnedAt != NEVER && now >= lastWarnedAt
            && now - lastWarnedAt < WARNING_REARM_WINDOW_MS) {
            return false;
        }
        warnedAtPercent = wornPercent;
        lastWarnedAt = now;
        return true;
    }

    /**
     * Rearma el aviso y olvida el último cambio. Se llama <strong>solo</strong> al encender el
     * módulo. No debe llamarse después de un SWAP: eso borraría el {@code lastSwapAt} que
     * {@code decide()} acaba de fijar y anularía la ventana anti-repetición de
     * {@link #SWAP_COOLDOWN_MS}, haciendo que el módulo repita el mismo cambio en cada tick.
     */
    public void reset() {
        lastSwapAt = NEVER;
        warnedAtPercent = null;
        lastWarnedAt = NEVER;
    }
}
