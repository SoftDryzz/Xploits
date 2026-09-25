package com.xploits.pvp.recorder.core;

import com.xploits.pvp.core.CombatPosture;
import com.xploits.pvp.core.CombatState;
import com.xploits.pvp.recorder.core.CombatEvent.AttackerKind;
import com.xploits.pvp.recorder.core.CombatEvent.SelfDamaged;
import com.xploits.pvp.recorder.core.TickInput.AutoPvpView;
import com.xploits.pvp.recorder.core.TickInput.Hostile;
import com.xploits.pvp.recorder.core.TickInput.SelfState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The tracker's input, one tick after another. The state set here (health, inventory, hostiles, modules,
 * auto-pvp) carries over from tick to tick until changed; the events belong to the next tick only. Tick
 * {@code t} happens at {@link #MILLIS_AT_ZERO} + 50 t ms.
 */
final class Ticks {
    static final long MILLIS_AT_ZERO = 1_727_190_000_000L;
    static final long FIRST_TICK = 1_000;

    private long tick = FIRST_TICK;
    private boolean alive = true;
    private double health = 20;
    private double incoming;
    private int totems = 8;
    private boolean offhandTotem = true;
    private int crystals = 64;
    private int obsidian = 64;
    private int gapples = 32;
    private int armor = 4;
    private boolean inHole;
    private boolean gliding;
    private final Map<String, Double> hostiles = new LinkedHashMap<>();
    private final Set<String> modules = new TreeSet<>();
    private boolean autoPvpOn;
    private AutoPvpView autoPvp;
    private String profile;

    /** The tick the next {@link #next} builds. */
    long tick() {
        return tick;
    }

    /** Lets ticks go by without handing them to anyone (the adapter skips idle ticks with nobody near). */
    Ticks skip(long ticks) {
        tick += ticks;
        return this;
    }

    static long millis(long tick) {
        return MILLIS_AT_ZERO + tick * 50;
    }

    Ticks health(double value) {
        health = value;
        return this;
    }

    Ticks incoming(double value) {
        incoming = value;
        return this;
    }

    Ticks totems(int value) {
        totems = value;
        return this;
    }

    Ticks offhandTotem(boolean value) {
        offhandTotem = value;
        return this;
    }

    Ticks crystals(int value) {
        crystals = value;
        return this;
    }

    Ticks armor(int value) {
        armor = value;
        return this;
    }

    Ticks inHole(boolean value) {
        inHole = value;
        return this;
    }

    Ticks gliding(boolean value) {
        gliding = value;
        return this;
    }

    Ticks alive(boolean value) {
        alive = value;
        return this;
    }

    /** A hostile at this distance, replacing the one with the same name. */
    Ticks hostile(String name, double distance) {
        hostiles.put(name, distance);
        return this;
    }

    Ticks noHostile(String name) {
        hostiles.remove(name);
        return this;
    }

    Ticks modules(String... names) {
        modules.clear();
        modules.addAll(List.of(names));
        return this;
    }

    /** auto-pvp on and in this phase. */
    Ticks autoPvp(CombatState state, CombatPosture posture, String target) {
        autoPvpOn = true;
        autoPvp = new AutoPvpView(state, posture, target);
        return this;
    }

    /** auto-pvp on but with no plan (the view is null). */
    Ticks autoPvpWithoutPlan() {
        autoPvpOn = true;
        autoPvp = null;
        return this;
    }

    Ticks autoPvpOff() {
        autoPvpOn = false;
        autoPvp = null;
        return this;
    }

    /** The active style profile from here on, or null (the default) when it is not known. */
    Ticks profile(String name) {
        profile = name;
        return this;
    }

    /** This tick, with these events; then moves on to the next. */
    TickInput next(CombatEvent... events) {
        List<Hostile> list = new ArrayList<>();
        hostiles.forEach((name, distance) -> list.add(new Hostile(name, distance)));
        SelfState self = new SelfState(health, incoming, totems, offhandTotem, crystals, obsidian, gapples, armor, inHole, gliding);
        TickInput in = new TickInput(tick, millis(tick), alive, self, list, modules, autoPvpOn, autoPvp, List.of(events), profile);
        tick++;
        return in;
    }

    static SelfDamaged crystalBy(String name) {
        return new SelfDamaged(DamageKind.CRYSTAL, AttackerKind.PLAYER, name, false);
    }

    static SelfDamaged meleeBy(String name) {
        return new SelfDamaged(DamageKind.MELEE, AttackerKind.PLAYER, name, false);
    }

    static SelfDamaged allyCrystal(String name) {
        return new SelfDamaged(DamageKind.CRYSTAL, AttackerKind.PLAYER, name, true);
    }

    static SelfDamaged unattributed(DamageKind kind) {
        return new SelfDamaged(kind, AttackerKind.NONE, null, false);
    }

    static SelfDamaged ownCrystal() {
        return new SelfDamaged(DamageKind.CRYSTAL, AttackerKind.SELF, null, false);
    }

    static CombatEvent.Popped ownPop() {
        return new CombatEvent.Popped(null);
    }

    static CombatEvent.Popped popOf(String name) {
        return new CombatEvent.Popped(name);
    }

    static CombatEvent.PlayerDied death(String name) {
        return new CombatEvent.PlayerDied(name);
    }

    static CombatEvent.SelfDied selfDied() {
        return new CombatEvent.SelfDied();
    }

    static CombatEvent.Attacked attack(String name) {
        return new CombatEvent.Attacked(name);
    }

    static CombatEvent.OpponentDamaged damaged(String name, boolean byYou) {
        return new CombatEvent.OpponentDamaged(name, byYou);
    }

    static CombatEvent.CrystalPlaced placed() {
        return new CombatEvent.CrystalPlaced();
    }

    static CombatEvent.CrystalBroken broken() {
        return new CombatEvent.CrystalBroken();
    }

    static CombatEvent.CrystalSpawnedNear spawnedNear() {
        return new CombatEvent.CrystalSpawnedNear();
    }
}
