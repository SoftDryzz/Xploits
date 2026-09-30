package com.xploits.bench;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.entity.decoration.EndCrystalEntity;

import java.util.Set;
import java.util.function.Supplier;

/**
 * 0.8.0: which end crystals the bench itself spawned, so a hit from one of them is never counted as ours
 * ({@link com.xploits.bench.core.OwnHits#ownIds}). Every crystal in a bench world is spawned either by our player's
 * placement or by the bench: a sparring's script ({@code CrystalAttack}, {@code SurroundMiner}, {@code Attacker},
 * {@code Menacer}, {@code LabCrystalAttack}, {@code LabCityAttack}, all from their {@code tick}, which only
 * {@code Sparring.step} calls) or a bench command ({@code Arena.explodeCrystal}'s {@code /summon}). {@link Bench} runs
 * both inside {@link #during}, on the server thread, and a server entity-load hook records every end crystal added
 * while one runs. The hook is generic, so a new script is covered without touching this class.
 *
 * <p>Nothing of ours can be recorded: the server makes our crystals while handling our placement packet, a task of its
 * own on the server thread, which never runs inside another task such as the bench's {@code step} or command. Entity
 * ids are shared by the integrated server and the client, so the ids recorded here are the ones the recorder and
 * crystal-aura++ see.
 */
final class ScriptCrystals {
    /** Where crystals are recorded while {@link #during} runs; null otherwise. Server thread only. */
    private static Set<Integer> sink;
    private static boolean registered;

    private ScriptCrystals() {
    }

    /** Registers the server hook, once per game (the bench plays every scenario in one client). */
    static synchronized void register() {
        if (registered) return;
        registered = true;
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            Set<Integer> into = sink;
            if (into != null && entity instanceof EndCrystalEntity) into.add(entity.getId());
        });
    }

    /** Runs {@code action} on the server thread, recording into {@code into} every end crystal it adds to a world. */
    static <T> T during(Set<Integer> into, Supplier<T> action) {
        Set<Integer> previous = sink;
        sink = into;
        try {
            return action.get();
        } finally {
            sink = previous;
        }
    }
}
