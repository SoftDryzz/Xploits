package com.xploits.pvp.crystal.core;

/**
 * The sample points vanilla's explosion exposure casts its rays from ({@code ExplosionImpl.calculateReceivedDamage},
 * 1.21.11, read from the yarn jar): a grid over the entity's box with a step of {@code 1 / (size * 2 + 1)} per
 * axis, the whole grid nudged on x and z (never y) by {@code (1 - floor(1 / step) * step) / 2} so it is centred,
 * the points interpolated as {@code min + t * (max - min)} for {@code t} from 0 to 1 inclusive. The exposure is
 * the fraction of those points whose ray to the explosion is not blocked. The order (x outermost, then y, then
 * z) and every operation are vanilla's, in {@code double}, so the points equal vanilla's bit for bit.
 *
 * <p>Pure geometry: the raycasting lives in the adapter. A box with a negative size has no points (vanilla
 * returns 0 exposure for it) and a non-finite one would give none either: never throws.
 */
public final class ExposureGrid {
    /** A box needing more than 20 samples per axis is no entity: no points, never a huge array. */
    private static final double MIN_STEP = 0.05;

    private ExposureGrid() {
    }

    /**
     * The sample points of the box {@code (minX, minY, minZ)} to {@code (maxX, maxY, maxZ)}, flat as
     * {@code x0, y0, z0, x1, y1, z1, ...} in vanilla's iteration order; empty when vanilla would return 0 or the box is not a sane one (a caller must then read the exposure as 1.0, never 0).
     */
    public static double[] samples(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double stepX = 1.0 / ((maxX - minX) * 2.0 + 1.0);
        double stepY = 1.0 / ((maxY - minY) * 2.0 + 1.0);
        double stepZ = 1.0 / ((maxZ - minZ) * 2.0 + 1.0);
        double offX = (1.0 - Math.floor(1.0 / stepX) * stepX) / 2.0;
        double offZ = (1.0 - Math.floor(1.0 / stepZ) * stepZ) / 2.0;
        if (!(stepX >= 0.0 && stepY >= 0.0 && stepZ >= 0.0) || stepX < MIN_STEP || stepY < MIN_STEP || stepZ < MIN_STEP) {
            return new double[0];
        }
        int count = 0;
        for (double x = 0.0; x <= 1.0; x += stepX) {
            for (double y = 0.0; y <= 1.0; y += stepY) {
                for (double z = 0.0; z <= 1.0; z += stepZ) count++;
            }
        }
        double[] out = new double[count * 3];
        int i = 0;
        for (double x = 0.0; x <= 1.0; x += stepX) {
            for (double y = 0.0; y <= 1.0; y += stepY) {
                for (double z = 0.0; z <= 1.0; z += stepZ) {
                    out[i++] = lerp(x, minX, maxX) + offX;
                    out[i++] = lerp(y, minY, maxY);
                    out[i++] = lerp(z, minZ, maxZ) + offZ;
                }
            }
        }
        return out;
    }

    private static double lerp(double delta, double start, double end) {
        return start + delta * (end - start);
    }
}
