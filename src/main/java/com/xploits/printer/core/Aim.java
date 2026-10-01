package com.xploits.printer.core;

/**
 * Where to click on a face and the rotation that looks there (printer spec §5.2–5.3). The yaw is Meteor's continuous one:
 * the player's own yaw plus the wrapped difference, so a wound-up camera never sees a full-turn snap.
 */
public final class Aim {
    private Aim() {
    }

    /** A rotation as the movement packet carries it: two floats. Equality is the packet's own, float for float. */
    public record Rotation(float yaw, float pitch) {
    }

    /** {@code MathHelper.wrapDegrees} on doubles: the result is in [-180, 180). */
    public static double wrapDegrees(double degrees) {
        double d = degrees % 360.0;
        if (d >= 180.0) d -= 360.0;
        if (d < -180.0) d += 360.0;
        return d;
    }

    /** Whether the eye is strictly on the outer side of {@code block}'s {@code side} face (§5.2: the normal points to the eye). */
    public static boolean facesEye(Pos block, Face side, Point eye) {
        return switch (side) {
            case DOWN -> eye.y() < block.y();
            case UP -> eye.y() > block.y() + 1;
            case NORTH -> eye.z() < block.z();
            case SOUTH -> eye.z() > block.z() + 1;
            case WEST -> eye.x() < block.x();
            case EAST -> eye.x() > block.x() + 1;
        };
    }

    /** The point of the face nearest the eye, kept {@code margin} away from every edge of the face. */
    public static Point hitPoint(Pos block, Face side, Point eye, double margin) {
        double x = clamp(eye.x(), block.x() + margin, block.x() + 1 - margin);
        double y = clamp(eye.y(), block.y() + margin, block.y() + 1 - margin);
        double z = clamp(eye.z(), block.z() + margin, block.z() + 1 - margin);
        return switch (side) {
            case DOWN -> new Point(x, block.y(), z);
            case UP -> new Point(x, block.y() + 1, z);
            case NORTH -> new Point(x, y, block.z());
            case SOUTH -> new Point(x, y, block.z() + 1);
            case WEST -> new Point(block.x(), y, z);
            case EAST -> new Point(block.x() + 1, y, z);
        };
    }

    /** The rotation from the eye to the hit point, its yaw continuous with {@code currentYaw}. */
    public static Rotation rotation(Point eye, Point hit, float currentYaw) {
        double dx = hit.x() - eye.x();
        double dy = hit.y() - eye.y();
        double dz = hit.z() - eye.z();
        double yaw = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
        double pitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        float continuous = (float) (currentYaw + wrapDegrees(yaw - currentYaw));
        return new Rotation(continuous + 0.0f, (float) pitch + 0.0f);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
