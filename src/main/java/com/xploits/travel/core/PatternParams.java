package com.xploits.travel.core;

/**
 * The numbers that tune each flight pattern. Not every field is used by every pattern: {@code
 * amplitude}/{@code period} belong to ZIGZAG, {@code legLength}/{@code lateralOffset} belong to
 * SWERVE (with the same roles as the previous two), {@code spiralRadius}/{@code spiralTurns} belong to
 * SPIRAL, and {@code decoyAngleDegrees}/{@code decoyFraction} belong to DECOY.
 *
 * @param amplitude         how far the ZIGZAG strays to each side of the heading, in blocks
 * @param period            every how many blocks of progress the ZIGZAG switches sides
 * @param legLength         every how many blocks of progress the SWERVE switches sides
 * @param lateralOffset     how far the SWERVE strays to each side of the heading, in blocks
 * @param spiralRadius      the radius of SPIRAL's final spiral, in blocks
 * @param spiralTurns       how many turns the final spiral makes as it closes
 * @param decoyAngleDegrees how many degrees the decoy strays from the real heading
 * @param decoyFraction     what fraction of the leg towards the decoy is flown before correcting
 */
public record PatternParams(double amplitude, double period, double legLength, double lateralOffset,
                             double spiralRadius, double spiralTurns, double decoyAngleDegrees,
                             double decoyFraction) {
    /** The module's out-of-the-box values. */
    public static PatternParams defaults() {
        return new PatternParams(200, 2000, 5000, 800, 1500, 1.5, 30, 0.6);
    }
}
