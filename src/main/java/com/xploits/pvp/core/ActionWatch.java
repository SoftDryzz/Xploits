package com.xploits.pvp.core;

import com.xploits.shared.core.i18n.Msg;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "I have it on and it is not doing anything", measured instead of assumed.
 *
 * <h2>The problem</h2>
 * The director enables and disables modules, but <b>does not control their settings</b>, and those
 * settings decide whether the module does anything. Out of the box {@code CrystalAura} ships with
 * {@code min-damage} at 6 -the minimum damage the crystal must deal to the target for it to be
 * placed- and {@code support} at {@code Disabled}; on a server where everyone wears netherite with
 * Protection IV that threshold can reject almost every position. Then {@code auto-pvp} announces
 * {@code SURFACE · enemy}, enables the aura and <b>nothing happens</b>, and from outside it is
 * indistinguishable from it working. That is what the module's principle forbids: <i>a failure must
 * not look like a normal result</i> (redesign §10).
 *
 * <h2>Why the settings are not read</h2>
 * Reading {@code min-damage} and comparing it with something would mean guessing the list of causes
 * -and one would always be missing: the {@code walls-range}, the anvil's {@code height}, the occupied
 * position, the server rejecting the swap-. Here the <b>effect</b> is measured, which is a single one
 * and visible: if the director wants the module, has enabled it, there is a target in range and there
 * is material -and the material <b>does not go down</b> for {@link #IDLE_TICKS} ticks- that module is
 * not acting, whatever the reason. It is the same reasoning with which {@code nether-sweep} measures
 * the server's lane width and the real firework spending instead of assuming a number.
 *
 * <h2>The verdict belongs to the stack, not to the module</h2>
 * What is measured is <b>one inventory stack</b>, and a stack can have several consumers:
 * {@code auto-trap}, {@code surround} and {@code hole-filler} draw from the same obsidian (I3). The
 * inventory says how much obsidian is left, <b>not who placed it</b>, so with this data nothing can be
 * claimed about any single one of the three: the only honest sentence is "none of these has placed
 * anything". That is why the count goes <b>by resource</b> and the warning names every module that
 * was in a position to spend it, saying in so many words that the verdict is joint.
 * When the resource has a single consumer -the four usual cases- the group has one member and the
 * warning belongs to that module.
 *
 * <p><b>And that has a price that must be said, not hidden:</b> as long as one of the group really
 * spends, the stack moves and <b>nothing is said</b> about the rest. In a trap war, with the rival
 * breaking the trap and {@code auto-trap} rebuilding it again and again, a truly broken
 * {@code hole-filler} may not warn during the whole fight. That is not a delay, it is silence, and
 * with this data it cannot be fixed: it would be fixed by seeing who places, which is exactly what the
 * inventory does not tell. What can be done -and is what is done- is not to claim a per-module
 * certainty that does not exist.
 *
 * <h2>Whom it watches, and whom it does not</h2>
 * The watch covers <b>six of the ten</b> managed modules: {@code crystal-aura},
 * {@code auto-trap}, {@code auto-web}, {@code auto-anvil} and the two obsidian ones,
 * {@code surround} and {@code hole-filler}. The four left out are left out for a written reason,
 * not a pretended one:
 *
 * <ul>
 *   <li><b>{@code auto-city} uses a pickaxe, and the pickaxe is not consumed</b> ({@link Resource#PICKAXE}).
 *       Mining takes durability from it, it does not take it out of the inventory, and the snapshot
 *       count is of items. "The resource does not go down" means nothing there, so watching it would
 *       mean warning of a failure every time {@code auto-city} works well.</li>
 *   <li><b>The three {@code anti-} modules are {@linkplain ManagedModule#reactive() reactive}</b>: they do
 *       place a block -obsidian, string, a slab- but only when their threat shows up (redesign §5), an
 *       anvil over your head, a bed being put on you, an anchor. With none, a stack that does not move
 *       is them waiting, and "on, with material to spare and not spending" would be said of them in
 *       every fight they work well.</li>
 * </ul>
 *
 * <p>The exclusion is not a hand-written list that goes stale: it comes from what each module
 * declares in {@link ManagedModules} ({@link #watches}), so a new module gets in or stays out on its
 * own.
 *
 * <h2>What it does and what it does not</h2>
 * <b>It neither cuts nor turns off anything.</b> A module that does not act can be perfectly correct
 * -there may be no valid position right now, the {@code surround} may already be complete, the cell
 * that {@code auto-web} webs may already have a web-, and turning it off would be worse than warning:
 * the module would stop being ready for the tick in which there is a position. The only thing it does
 * is say so <b>once</b>, naming the modules and pointing at the suspects <b>without claiming which one
 * it is</b>, and rearm when the situation changes.
 */
public final class ActionWatch {
    /** Game ticks per second, to state the margin in seconds in the warning. */
    private static final int TICKS_PER_SECOND = 20;

    /**
     * Consecutive ticks with someone in a position to spend the stack and without the stack moving
     * before warning. Sixty, three seconds.
     *
     * <p>The number comes from the two ways of getting it wrong, and they are very asymmetric.
     *
     * <p><b>From below</b>, the slowest legitimate cadence of the six watched modules rules, and it is
     * measured, not assumed: {@code AutoAnvil} ships with {@code delay} at {@code defaultValue(10)}
     * -checked in the {@code meteor-client:1.21.11-SNAPSHOT} sources: its {@code onTick} places only
     * when {@code timer >= delay.get()} and resets the counter-, so an {@code auto-anvil} that works
     * perfectly spends one anvil every <b>eleven</b> ticks. A margin of twenty ticks would leave it
     * less than two cadences of slack: a single covered gap over the target's head would already
     * cross it, and the warning would fire constantly with the module working. With sixty, five
     * consecutive failed placements fit before anything is said. For {@code crystal-aura} the same
     * margin is 6 to 15 whole crystal cycles (§9 measures half a second in 2-5 cycles, that is 4-10
     * ticks per cycle): three seconds with a hostile closer than 4.5, the aura on and crystals in hand
     * without placing <b>a single one</b> is not a combat gap, it is a wall.
     *
     * <p><b>From above there is no hurry</b>, and that is the asymmetry: the cause this looks for -a
     * threshold that rejects every position, a short range, a block list that does not include what
     * you carry- <b>is permanent</b>. It does not heal by itself, so waiting too long never loses the
     * warning; it only delays it by three seconds. Getting it wrong the other way does cost: a false
     * chat line in every fight teaches the player to ignore the warning, and then the watch is of no
     * use at all.
     */
    public static final int IDLE_TICKS = 60;

    /**
     * Whether a managed module can be watched this way. Two questions: whether its resource <b>is
     * spent when used</b> -{@link Resource#PICKAXE} is not: mining spends durability, not items- and
     * whether it is used <b>whenever there is a target</b>: a {@link ManagedModule#reactive() reactive}
     * one waits for its threat, and not spending until then is its normal state.
     */
    public static boolean watches(ManagedModule module) {
        return module.needs() != Resource.PICKAXE && !module.reactive();
    }

    /**
     * The watched modules, derived from the catalog by {@link #watches} and in the same order: six of
     * the ten, the ones that spend whenever there is a target. {@code anti-anvil} draws from the same
     * obsidian and is not among them -it is reactive-; when it does place, the stack moves and the
     * obsidian count starts again, which can only delay a warning, never invent one.
     */
    public static final List<ManagedModule> WATCHED =
        ManagedModules.ALL.stream().filter(ActionWatch::watches).toList();

    /** The stacks to look at, without repeats and in the order they appear in {@link #WATCHED}. */
    public static final List<Resource> WATCHED_RESOURCES =
        List.copyOf(new LinkedHashSet<>(WATCHED.stream().map(ManagedModule::needs).toList()));

    /**
     * A verdict: the stack that does not move, the modules that were in a position to spend it and
     * how many ticks they have been like that.
     *
     * <p>With more than one module the verdict is <b>joint and cannot be split</b> ({@link
     * #joint()}): the only thing measured is that the stack does not go down, and the inventory does
     * not say who places.
     *
     * @param resource the stack that has not moved
     * @param modules  those that were wanted, were on and had material to spare, in catalog
     *                 order
     * @param ticks    consecutive ticks it has been like that, the margin already reached
     */
    public record Idle(Resource resource, List<ManagedModule> modules, int ticks) {
        public Idle {
            modules = List.copyOf(modules);
        }

        /** Whether the verdict covers several modules at once and therefore cannot be split. */
        public boolean joint() {
            return modules.size() > 1;
        }
    }

    /** Consecutive ticks each stack has been still with someone in a position to spend it. */
    private final Map<Resource, Integer> idleTicks = new EnumMap<>(Resource.class);

    /** Who was in a position to spend each stack on the previous tick. */
    private final Map<Resource, List<ManagedModule>> lastEligible = new EnumMap<>(Resource.class);

    /** How much of each watched stack there was on the previous tick. */
    private final Map<Resource, Integer> lastAmount = new EnumMap<>(Resource.class);

    /** Which stacks have already been warned about, to say it once and not in a loop. */
    private final Set<Resource> warned = new LinkedHashSet<>();

    /**
     * One tick of watching. Returns the verdicts to <b>warn about now</b>: the stacks that have just
     * reached the margin and that have not been warned about yet.
     *
     * <p>The four conditions that put a module "in a position to spend" are: the plan
     * <b>wants</b> it, it is <b>really on</b>, there is a <b>target</b> and there is <b>enough
     * resource</b> (its {@link ManagedModule#minimum()}). A stack's count runs while there is
     * at least one module in those conditions.
     *
     * <p>When there stops being one, the count is not paused: it is <b>reset</b>, and the warning is
     * rearmed too. It is the conservative choice and the one that is needed -the claim about to be
     * made is "three seconds in a row without spending while able to spend", and one tick in which it
     * could not spend breaks it entirely-.
     *
     * <p><b>A change in who is in a position to spend also resets.</b> If {@code auto-trap} joins
     * the obsidian on tick 50, the verdict about the three cannot rest on the fifty ticks in which it
     * was not there: that run referred to another group. It starts from zero.
     *
     * <p>The target is also required of the two defensive modules, even though {@code surround} and
     * {@code hole-filler} do not need one to place: with nobody in front, not spending is
     * normal, and counting those ticks would only produce warnings about nothing.
     *
     * <p><b>Any movement of the stack resets, not only a drop.</b> If it drops, someone in the
     * group is acting and there is nothing to say. If it rises -you pick up obsidian, you take webs out
     * of the backpack- the run stops comparing the same thing, and a rise can also hide spending (you
     * spend one and pick up two). It cannot be claimed that nothing is spent while the stack moves, so
     * it is not claimed.
     *
     * @param snapshot the situation of this tick, for the target and for the inventory counts
     * @param wanted   the module names the plan of this tick wants on
     * @param active   the module names that are really on right now
     */
    public List<Idle> update(CombatSnapshot snapshot, Set<String> wanted, Set<String> active) {
        List<Idle> newlyIdle = new ArrayList<>();

        for (Resource resource : WATCHED_RESOURCES) {
            int amount = snapshot.amountOf(resource);
            Integer before = lastAmount.put(resource, amount);
            boolean moved = before != null && before != amount;

            List<ManagedModule> eligible = eligibleFor(resource, snapshot, wanted, active);
            List<ManagedModule> previous = lastEligible.put(resource, eligible);

            if (eligible.isEmpty() || moved) {
                idleTicks.remove(resource);
                warned.remove(resource);
                continue;
            }
            if (!eligible.equals(previous)) {
                idleTicks.remove(resource);
                warned.remove(resource);
            }

            int ticks = idleTicks.merge(resource, 1, Integer::sum);
            if (ticks >= IDLE_TICKS && warned.add(resource)) {
                newlyIdle.add(new Idle(resource, eligible, ticks));
            }
        }
        return List.copyOf(newlyIdle);
    }

    /** The watched modules of that stack that were in a position to spend it this tick. */
    private static List<ManagedModule> eligibleFor(Resource resource, CombatSnapshot snapshot,
                                                   Set<String> wanted, Set<String> active) {
        if (!snapshot.hasTarget()) return List.of();

        List<ManagedModule> eligible = new ArrayList<>();
        for (ManagedModule module : WATCHED) {
            if (module.needs() != resource) continue;
            if (!wanted.contains(module.name()) || !active.contains(module.name())) continue;
            if (snapshot.amountOf(resource) < module.minimum()) continue;
            eligible.add(module);
        }
        return List.copyOf(eligible);
    }

    /** Ticks that stack has been still with someone in a position to spend it; zero if that is not the case. */
    public int idleTicksOf(Resource resource) {
        return idleTicks.getOrDefault(resource, 0);
    }

    /**
     * The verdicts that have already reached the whole margin and still stand, in stack order.
     * It is what {@code .xploits pvp} shows: the warning is said once, but the situation lasts, and
     * it has to be possible to check it while it lasts.
     */
    public List<Idle> idle() {
        List<Idle> result = new ArrayList<>();
        for (Resource resource : WATCHED_RESOURCES) {
            int ticks = idleTicksOf(resource);
            if (ticks >= IDLE_TICKS) result.add(new Idle(resource, lastEligible.get(resource), ticks));
        }
        return List.copyOf(result);
    }

    /** Forgets the counts, the stacks and what was already warned about. Called when the module is turned on or off. */
    public void reset() {
        idleTicks.clear();
        lastEligible.clear();
        lastAmount.clear();
        warned.clear();
    }

    /**
     * The warning for a verdict: it names the modules, says what has been measured and <b>points at
     * the suspects without claiming which one it is</b>. Every setting and default value named is
     * checked in the {@code meteor-client:1.21.11-SNAPSHOT} sources and, where they are block names,
     * against the yarn 1.21.11+build.3 mappings; none is assumed.
     *
     * <p>With a single module the <b>innocent cause comes first</b> ({@link #innocent}) and then the
     * suspects. With several -which can only happen with the obsidian- it also says, in so many
     * words, that the verdict is joint and why it cannot be split.
     */
    public static Msg reason(Idle idle) {
        PvpText material = material(idle.resource());
        if (!idle.joint()) {
            ManagedModule module = idle.modules().getFirst();
            return Msg.of(PvpText.IDLE_ALONE, "module", module.name(), "seconds", seconds(idle),
                "material", material, "innocent", innocent(module), "suspects", suspects(module));
        }

        List<String> names = new ArrayList<>();
        Object tails = null;
        for (ManagedModule module : idle.modules()) {
            names.add(module.name());
            Msg tail = Msg.of(PvpText.SUSPECTS_OF, "module", module.name(), "suspects", suspects(module));
            tails = tails == null ? tail : Msg.of(PvpText.JOIN_SEMICOLON, "first", tails, "rest", tail);
        }
        return Msg.of(PvpText.IDLE_TOGETHER, "modules", join(names), "seconds", seconds(idle),
            "material", material, "count", idle.modules().size(), "suspects", tails);
    }

    /** The seconds the verdict has lasted, as they read in the warning. */
    private static int seconds(Idle idle) {
        return idle.ticks() / TICKS_PER_SECOND;
    }

    /** "a, b and c", listed the way the player's language does it. */
    private static Object join(List<String> names) {
        if (names.size() == 1) return names.getFirst();
        Object head = names.getFirst();
        for (String name : names.subList(1, names.size() - 1)) {
            head = Msg.of(PvpText.JOIN_COMMA, "first", head, "rest", name);
        }
        return Msg.of(PvpText.JOIN_AND, "first", head, "second", names.getLast());
    }

    /**
     * The reason why it would <b>not</b> be a failure, which goes before the suspects because in
     * several cases it is by far the most likely one.
     *
     * <p>The three that are not "there is no position" are checked in the sources: {@code Surround}
     * sets {@code complete = true} and stops placing when the surround is finished, and with
     * {@code toggle-on-complete} at {@code false} by default <b>it stays on forever</b>;
     * {@code HoleFiller} with {@code smart} on only fills holes near a target, and there may be
     * none; and {@code AutoWeb} only places where {@code isReplaceable()}, and a web is not, so as
     * soon as the intended cell has a web it stops placing there -against someone cornered who still
     * counts as "moving away", the intended cell does not change and it does not spend a single one
     * again-.
     */
    private static PvpText innocent(ManagedModule module) {
        return switch (module.name()) {
            case "surround" -> PvpText.INNOCENT_SURROUND;
            case "hole-filler" -> PvpText.INNOCENT_HOLE_FILLER;
            case "auto-web" -> PvpText.INNOCENT_AUTO_WEB;
            default -> PvpText.INNOCENT_DEFAULT;
        };
    }

    /** Which settings to look at, without claiming the culprit is among them. */
    private static PvpText suspects(ManagedModule module) {
        return switch (module.name()) {
            case "crystal-aura" -> PvpText.SUSPECTS_CRYSTAL_AURA;
            case "auto-trap" -> PvpText.SUSPECTS_AUTO_TRAP;
            case "auto-web" -> PvpText.SUSPECTS_AUTO_WEB;
            case "auto-anvil" -> PvpText.SUSPECTS_AUTO_ANVIL;
            case "surround" -> PvpText.SUSPECTS_SURROUND;
            case "hole-filler" -> PvpText.SUSPECTS_HOLE_FILLER;
            default -> PvpText.SUSPECTS_DEFAULT;
        };
    }

    /** What the material in that stack is called, for the warning. */
    private static PvpText material(Resource resource) {
        return PvpText.of(resource);
    }
}
