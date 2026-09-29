package com.xploits.bench;

import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Task A3: the four real crystal-PvP fights (fight mode: {@link Arena#fightLoadout()}, finite totems, gapples
 * after every pop, outcomes — task A1), each built fresh by its own factory method (a {@link
 * java.util.function.Supplier}{@code <Script>} for {@link Scenarios}' wiring, matching {@link Scenarios#FIGHTS}'
 * own {@code Above::new} style), so every MEASURE run gets fresh counters.
 */
final class Fights {
    private static final Logger LOG = LoggerFactory.getLogger("xploits-bench");
    /** The four cardinal neighbours of F (our own feet block) at floor level (y = -1, the same level the
     * arena's own floor sits at) — a FEET-mode candidate cell list shared by {@code exchange} and
     * {@code near-death}: "next to our feet", the plain crystal-PvP shape (task A2's own {@code Attacker}
     * precedent, {@code ATTACK_CELL} at y = -1 too). */
    private static final List<Vec3i> FEET_CELLS = List.of(
        new Vec3i(1, -1, 0), new Vec3i(-1, -1, 0), new Vec3i(0, -1, 1), new Vec3i(0, -1, -1));

    /** The four cardinal neighbours of F at the level of our own feet's block (y = 0) — a HEAD-mode candidate
     * cell list: face-placed on top of them, the crystal floats at head height. These are exactly the same
     * four cells {@code hole-standoff}'s own {@link HoleWalls} builds around F, so {@link CrystalAttack#build}
     * placing obsidian there again is idempotent, not a second, different wall. */
    private static final List<Vec3i> HEAD_CELLS = List.of(
        new Vec3i(1, 0, 0), new Vec3i(-1, 0, 0), new Vec3i(0, 0, 1), new Vec3i(0, 0, -1));

    private Fights() {
    }

    /**
     * {@code exchange}: open obsidian floor, the opponent strafing 3 blocks out ({@link ExchangeMover}) while
     * it attacks our feet (A2's {@link CrystalAttack}), autobreaks our crystals near it, spot-blocks the cell
     * that would hurt it most, and escapes once its totems run low.
     */
    static Script exchange() {
        ExchangeMover mover = new ExchangeMover();
        CrystalAttack attack = new CrystalAttack(CrystalAttack.Mode.FEET, mover.anchor(), FEET_CELLS);
        return new ComposedFight(mover, List.of(attack, new Autobreak(attack), new SpotBlock(), new Escape()));
    }

    /**
     * {@code hole-standoff}: both in 1x1 obsidian holes, walls on four sides, 4 blocks apart; the opponent
     * face-places at our head level ({@link CrystalAttack}, HEAD mode, whose own {@code build} completes our
     * hole's wall ring since {@link #HEAD_CELLS} are exactly those four wall positions), surrounds itself once
     * hurt, autobreaks our crystals near it, and webs/traps us on a cooldown.
     */
    static Script holeStandoff() {
        Vec3i sparringAnchor = new Vec3i(4, 0, 0);
        CrystalAttack attack = new CrystalAttack(CrystalAttack.Mode.HEAD, sparringAnchor, HEAD_CELLS);
        return new ComposedFight(attack,
            List.of(new HoleWalls(sparringAnchor), new SelfSurround(), new Autobreak(attack), new WebTrap()));
    }

    /**
     * {@code city}: we stand in a surrounded 1x1 obsidian hole ({@link HoleWalls} around F); the opponent
     * ({@link SurroundMiner}, 2 blocks out) mines our east wall and places a crystal into the gap, while
     * surrounding itself once hurt and filling any hole it finds near us. Our own side is not driven here —
     * {@link CityMeasure} runs auto-pvp instead, which is what is meant to patch the gap back with {@code
     * surround}.
     */
    static Script city() {
        Vec3i minerAnchor = new Vec3i(2, 0, 0);
        SurroundMiner miner = new SurroundMiner(minerAnchor, Direction.EAST);
        return new ComposedFight(miner, List.of(new HoleWalls(Vec3i.ZERO), new SelfSurround(), new HoleFill()));
    }

    /**
     * {@code near-death} (no totem, for B0's finishing blow — fix round 1, review-a3.md finding 1): open
     * floor, 3 blocks apart, a stationary, non-attacking opponent ({@link PassiveTarget}) on its own obsidian
     * pad, facing us. Passive on purpose: with an attacking opponent (this fight's original build), our aura's
     * own ordinary foreign-crystal defence — breaking the opponent's incoming attack crystal, which still
     * explodes — reached and killed the totem-less, 6-HP opponent standing 3 blocks away as a side effect,
     * with {@code placements_per_s == 0}: both auras "won" identically without ever choosing to place or break
     * a crystal against the opponent itself. A passive opponent takes that shortcut away, so the win has to
     * come from our aura's own placement/break decision, which is what B0's finishing blow needs this fight to
     * measure. {@link FightMeasure#startingLow(boolean) startingLow(false)} makes it a warm-up with both players
     * healthy (so our aura lands a first hit and crystal-aura++ can trust the target's health), then the
     * near-death moment (task B0b), which strips the opponent's totem.
     */
    static Script nearDeath() {
        return new PassiveTarget(new Vec3i(3, 0, 0));
    }

    /**
     * {@code near-death-totem} (kept as originally built — review-a3.md finding 1 found no problem with it):
     * the opponent keeps attacking our feet (A2's {@link CrystalAttack} alone — no autobreak, spot blocking or
     * escape) and, unlike {@code near-death}, keeps its totem ({@link FightMeasure#startingLow(boolean)
     * startingLow(true)}): real placement activity on both sides (8 pops dealt, nonzero placements/s), so this
     * variant needed no fix.
     */
    static Script nearDeathTotem() {
        Vec3i anchor = new Vec3i(3, 0, 0);
        return new CrystalAttack(CrystalAttack.Mode.FEET, anchor, FEET_CELLS);
    }

    /**
     * Task A3's own "Proof" requirement: logs every behaviour counter this run's script built — attacks,
     * autobreaks, blocks, surround placements, webs, traps, escapes, city breaches — one line per kind
     * present, walking a {@link ComposedFight}'s base and behaviours (never both: a behaviour composed into
     * one fight is never also that fight's base). Log only, never a metric; no coordinates.
     */
    static void logCounters(String scenario, int run, Script script) {
        if (script instanceof CrystalAttack attack) logCrystalAttack(scenario, run, attack);
        if (script instanceof SurroundMiner miner) logSurroundMiner(scenario, run, miner);
        if (script instanceof ComposedFight composed) {
            logCounters(scenario, run, composed.base());
            for (FightBehaviour behaviour : composed.behaviours()) logBehaviour(scenario, run, behaviour);
        }
    }

    private static void logBehaviour(String scenario, int run, FightBehaviour behaviour) {
        switch (behaviour) {
            case CrystalAttack attack -> logCrystalAttack(scenario, run, attack);
            case Autobreak autobreak -> LOG.info("[bench] {} run {}: autobreak broke {} crystal(s) (log only)",
                scenario, run, autobreak.broken());
            case SpotBlock spot -> LOG.info("[bench] {} run {}: spot blocking placed {} obsidian (log only)",
                scenario, run, spot.placed());
            case SelfSurround surround -> LOG.info("[bench] {} run {}: self-surround placed {} obsidian (log only)",
                scenario, run, surround.placed());
            case HoleFill fill -> LOG.info("[bench] {} run {}: hole fill filled {} hole(s) (log only)",
                scenario, run, fill.filled());
            case WebTrap trap -> LOG.info("[bench] {} run {}: web/trap placed {} web(s), {} trap block(s) (log only)",
                scenario, run, trap.webs(), trap.trapBlocks());
            case Escape escape -> LOG.info("[bench] {} run {}: escape triggered {} (log only)",
                scenario, run, escape.triggered());
            default -> { } // HoleWalls (build-only) and anything else with nothing to count
        }
    }

    private static void logCrystalAttack(String scenario, int run, CrystalAttack attack) {
        LOG.info("[bench] {} run {}: crystal-attack spawned {}, {} exploded, {} broken first, {} abandoned (log only)",
            scenario, run, attack.spawned(), attack.explosions(), attack.brokenFirst(), attack.abandoned());
    }

    private static void logSurroundMiner(String scenario, int run, SurroundMiner miner) {
        LOG.info("[bench] {} run {}: city breaches {}, refilled before the explosion {}, {} broken first, "
                + "{} exploded, {} abandoned (log only)",
            scenario, run, miner.breaches(), miner.refilledBeforeExplosion(), miner.brokenFirst(), miner.explosions(),
            miner.abandoned());
    }
}
