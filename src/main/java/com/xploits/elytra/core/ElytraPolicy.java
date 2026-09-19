package com.xploits.elytra.core;

import java.util.List;

/**
 * Decide cuándo cambiar la elytra puesta y por cuál (spec §4). Guarda dos cosas entre decisiones:
 * cuándo fue el último cambio, para no repetirlo mientras el slot de pechera se actualiza, y si ya
 * se avisó de que no hay repuesto, para no avisar veinte veces por segundo.
 */
public final class ElytraPolicy {
    public static final long SWAP_COOLDOWN_MS = 2_000;

    private static final long NEVER = Long.MIN_VALUE;

    private long lastSwapAt = NEVER;
    private Integer warnedAtPercent;

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
     * @param swapBelow   cambiar cuando la puesta baje de este porcentaje
     * @param minSpare    solo considerar repuestos con al menos este porcentaje
     * @param now         System.currentTimeMillis()
     */
    public Result decide(Integer wornPercent, List<ElytraCandidate> candidates,
                         int swapBelow, int minSpare, long now) {
        if (wornPercent == null) return new Result(Decision.NOT_WEARING, -1, 0);

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
     * true si toca avisar ahora de que no hay repuesto. Una sola vez por elytra puesta: como una
     * elytra solo se desgasta, que el porcentaje suba significa que el jugador se ha puesto otra.
     */
    public boolean shouldWarnNoSpare(int wornPercent) {
        if (warnedAtPercent != null && wornPercent <= warnedAtPercent) return false;
        warnedAtPercent = wornPercent;
        return true;
    }

    /** Rearma el aviso y olvida el último cambio. Se llama al encender el módulo y tras un cambio. */
    public void reset() {
        lastSwapAt = NEVER;
        warnedAtPercent = null;
    }
}
