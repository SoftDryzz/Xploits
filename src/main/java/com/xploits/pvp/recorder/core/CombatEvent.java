package com.xploits.pvp.recorder.core;

import java.util.Objects;

/**
 * Something that happened in the fight during one tick, already resolved by the adapter from packets
 * (ids turned into names, allies filtered). Names are real usernames, never sentinel strings: "self" is
 * a valid username, so who hit you is carried by {@link AttackerKind}, not by a magic name.
 */
public sealed interface CombatEvent {

    /** Who caused damage to you. */
    enum AttackerKind { PLAYER, SELF, NONE }

    /**
     * You received a damage packet. The packet has no amount: the {@code DamageLedger} takes it from your
     * health drop. {@code attacker} is the player's name when {@code by == PLAYER} and null otherwise;
     * {@code attackerOurs} marks an ally's hit, which is recorded but never starts a fight.
     */
    record SelfDamaged(DamageKind kind, AttackerKind by, String attacker, boolean attackerOurs) implements CombatEvent {
        public SelfDamaged {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(by, "by");
            if (kind == DamageKind.UNSEEN) throw new IllegalArgumentException("UNSEEN is the ledger's, never a packet's");
            if ((by == AttackerKind.PLAYER) != (attacker != null)) {
                throw new IllegalArgumentException("an attacker name goes with PLAYER and only with it: " + by + "/" + attacker);
            }
            if (attackerOurs && by != AttackerKind.PLAYER) throw new IllegalArgumentException("only a player can be one of ours");
        }
    }

    /** A hostile player received damage; {@code byYou} when you caused it. */
    record OpponentDamaged(String name, boolean byYou) implements CombatEvent {
        public OpponentDamaged {
            Objects.requireNonNull(name, "name");
        }
    }

    /** A totem popped: yours when {@code name} is null, else that player's. */
    record Popped(String name) implements CombatEvent {
    }

    /** Another player died. */
    record PlayerDied(String name) implements CombatEvent {
        public PlayerDied {
            Objects.requireNonNull(name, "name");
        }
    }

    /** You died. */
    record SelfDied() implements CombatEvent {
    }

    /** You placed an end crystal. */
    record CrystalPlaced() implements CombatEvent {
    }

    /** You attacked (broke) an end crystal. */
    record CrystalBroken() implements CombatEvent {
    }

    /** An end crystal appeared close to you, anyone's. */
    record CrystalSpawnedNear() implements CombatEvent {
    }

    /** You attacked a hostile player. */
    record Attacked(String name) implements CombatEvent {
        public Attacked {
            Objects.requireNonNull(name, "name");
        }
    }
}
