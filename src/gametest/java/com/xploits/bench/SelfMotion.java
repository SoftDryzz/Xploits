package com.xploits.bench;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

/**
 * Drives OUR OWN player's movement (crystal-aura++ R3-14, the fight situations where we move too): real client
 * movement, the same way a {@link Script} moves the sparring, only on the client thread and relative to
 * {@code origin} (our own position captured once at T0, never printed). Position and ground state only: yaw
 * and pitch are left to the aura under test, exactly as every other fight situation leaves the player's look
 * to it. Deterministic: the same tick after T0 always gives the same offset from {@code origin}.
 */
interface SelfMotion {
    /** One bench tick after T0: {@code k} is 1 on the first one, like {@link Script.Tick#sinceT0()}. Client thread. */
    void tick(MinecraftClient client, Vec3d origin, int k);
}
