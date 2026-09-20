package com.xploits.travel.core;

/**
 * Los números que afinan cada patrón de vuelo. No todos los campos se usan en todos los patrones:
 * {@code amplitude}/{@code period} son de ZIGZAG, {@code legLength}/{@code lateralOffset} son de
 * QUIEBRO (con los mismos papeles que los dos anteriores), {@code spiralRadius}/{@code spiralTurns}
 * son de ESPIRAL, y {@code decoyAngleDegrees}/{@code decoyFraction} son de SENUELO.
 *
 * @param amplitude         cuánto se desvía el ZIGZAG a cada lado del rumbo, en bloques
 * @param period            cada cuántos bloques de avance el ZIGZAG cambia de lado
 * @param legLength         cada cuántos bloques de avance el QUIEBRO cambia de lado
 * @param lateralOffset     cuánto se desvía el QUIEBRO a cada lado del rumbo, en bloques
 * @param spiralRadius      el radio de la espiral final de ESPIRAL, en bloques
 * @param spiralTurns       cuántas vueltas da la espiral final al cerrarse
 * @param decoyAngleDegrees cuántos grados se aparta el señuelo del rumbo real
 * @param decoyFraction     qué fracción del tramo hacia el señuelo se recorre antes de corregir
 */
public record PatternParams(double amplitude, double period, double legLength, double lateralOffset,
                             double spiralRadius, double spiralTurns, double decoyAngleDegrees,
                             double decoyFraction) {
    /** Los valores de fábrica del módulo. */
    public static PatternParams defaults() {
        return new PatternParams(200, 2000, 5000, 800, 1500, 1.5, 30, 0.6);
    }
}
