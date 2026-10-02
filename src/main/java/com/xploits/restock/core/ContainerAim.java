package com.xploits.restock.core;

import com.xploits.printer.core.Aim;
import com.xploits.printer.core.Face;
import com.xploits.printer.core.PlacePlanner;
import com.xploits.printer.core.Point;
import com.xploits.printer.core.Pos;

import java.util.Optional;

/**
 * Where to look before clicking a container (restock spec §3 "Take": look at the container, reach ≤ 4.5), by the
 * printer's aim rules: a face that looks at the eye, the hit point inside it a margin away from its edges, within reach
 * of the eye, and a vanilla raycast with exactly that rotation that returns the container and that face. The nearest
 * such face wins; ties by face order. The yaw stays continuous with the player's own ({@link Aim#rotation}).
 */
public final class ContainerAim {
    /** The face to click, the hit point and the rotation that looks at it. */
    public record Aiming(Face side, Point hit, Aim.Rotation rotation) {
    }

    private ContainerAim() {
    }

    public static Optional<Aiming> choose(Pos container, Point eye, float yaw, double reach, double margin,
                                          PlacePlanner.RayOracle oracle) {
        Aiming best = null;
        double bestD = Double.POSITIVE_INFINITY;
        for (Face f : Face.values()) {
            if (!Aim.facesEye(container, f, eye)) continue;
            Point hit = Aim.hitPoint(container, f, eye, margin);
            double d = hit.distance(eye);
            if (d > reach) continue;
            Aim.Rotation r = Aim.rotation(eye, hit, yaw);
            if (!oracle.sees(container, f, r)) continue;
            if (d < bestD) {
                bestD = d;
                best = new Aiming(f, hit, r);
            }
        }
        return Optional.ofNullable(best);
    }
}
