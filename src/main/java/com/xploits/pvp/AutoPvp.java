package com.xploits.pvp;

import com.xploits.XploitsAddon;
import com.xploits.autotpy.AutoTpy;
import com.xploits.kitrequester.KitRequester;
import com.xploits.pvp.core.ActionWatch;
import com.xploits.pvp.core.AllyPolicy;
import com.xploits.pvp.core.CombatDirector;
import com.xploits.pvp.core.CombatPosture;
import com.xploits.pvp.core.CombatSnapshot;
import com.xploits.pvp.core.CombatState;
import com.xploits.pvp.core.DefensivePolicy;
import com.xploits.pvp.core.FriendLedger;
import com.xploits.pvp.core.ManagedModule;
import com.xploits.pvp.core.ManagedModules;
import com.xploits.pvp.core.ModuleLedger;
import com.xploits.pvp.core.Plan;
import com.xploits.pvp.core.PvpText;
import com.xploits.pvp.core.Resource;
import com.xploits.pvp.core.Skipped;
import com.xploits.shared.Texts;
import com.xploits.shared.XploitsModule;
import com.xploits.shared.core.i18n.Msg;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friend;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.entity.fakeplayer.FakePlayerEntity;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Manages Meteor's combat modules (spec §1, redesigned in
 * {@code 2026-09-22-autopvp-decide-bien}). It performs no combat action: it only enables and
 * disables, and it only disables what it enabled itself (spec §7).
 *
 * <p>It is the <b>adapter</b>: it measures the world and carries out what the core decides, which is
 * pure and tested. Nothing is decided here. What it measures is the enemy's phase, your half of the
 * snapshot -the one behind the defensive axis of redesign §5- and the other Meteor settings a core
 * decision depends on, today only the {@code anti-suicide} of {@code crystal-aura}.
 */
public class AutoPvp extends XploitsModule {
    private static final int FIRST_SLOT = 0;
    /** Game ticks per second: the only conversion needed to report times. */
    private static final int TICKS_PER_SECOND = 20;
    /**
     * Last hotbar slot (spec §6): what {@code InvUtils.findInHotbar}/{@code
     * testInHotbar} see, and also the only range that counts for {@code PICKAXE} — {@code AutoCity}
     * looks for the pickaxe with {@code InvUtils.find} over the whole inventory, but rejects the result
     * if {@code !isHotbar()} and turns itself off with an error (spec §6). Counting the backpack for the
     * pickaxe overestimated exactly the same silent failure this range fixes for the other
     * five.
     */
    private static final int HOTBAR_LAST_SLOT = 8;
    /** Last slot of the whole inventory: how far the single scan of {@link #inventory()} goes. */
    private static final int INVENTORY_LAST_SLOT = 35;

    /** Cap on the names remembered so as not to repeat the "not attacking" warning; when full it is emptied. */
    private static final int MAX_ANNOUNCED_ALLIES = 64;

    /**
     * Blast resistance from which a block protects from a crystal (redesign §4.1).
     * It is the same threshold Meteor's {@code PlayerUtils.isInHole(boolean)} uses to decide whether a
     * neighbour protects you, so "burrowed" and "in a hole" are measured by the same yardstick.
     */
    private static final float PROTECTIVE_BLAST_RESISTANCE = 600f;

    /** Combat modules auto-pvp never touches, whether you have them on or not (spec §7). */
    private static final List<String> ALWAYS_YOURS = List.of("auto-totem", "auto-armor", "offhand", "auto-weapon");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> targetRange = sgGeneral.add(new IntSetting.Builder()
        .name("target-range")
        .description(Texts.startupText(PvpText.SETTING_TARGET_RANGE))
        .defaultValue(16)
        .range(4, 64)
        .sliderRange(4, 64)
        .build()
    );

    private final Setting<Integer> approachDistance = sgGeneral.add(new IntSetting.Builder()
        .name("approach-distance")
        .description(Texts.startupText(PvpText.SETTING_APPROACH_DISTANCE))
        .defaultValue(6)
        .range(2, 6)
        .sliderRange(2, 6)
        .build()
    );

    /**
     * The threshold of the defensive axis (redesign §5). The core leaves it open and as a setting; its
     * constant {@link com.xploits.pvp.core.DefensivePolicy#THREAT_MARGIN} is only the default
     * value, and it is the one set here.
     */
    private final Setting<Double> threatMargin = sgGeneral.add(new DoubleSetting.Builder()
        .name("threat-margin")
        .description(Texts.startupText(PvpText.SETTING_THREAT_MARGIN))
        .defaultValue(DefensivePolicy.THREAT_MARGIN)
        .range(0, 40)
        .sliderRange(0, 20)
        .build()
    );

    /**
     * CAREFUL: this setting writes to Meteor configuration that does not belong to the addon. Its
     * description says so in so many words because the player has to learn it from the ClickGUI,
     * without reading any README (spec §14.3).
     */
    private final Setting<Boolean> syncFriends = sgGeneral.add(new BoolSetting.Builder()
        .name("sync-friends")
        .description(Texts.startupText(PvpText.SETTING_SYNC_FRIENDS))
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("notify")
        .description(Texts.startupText(PvpText.SETTING_NOTIFY))
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifySound = sgGeneral.add(new BoolSetting.Builder()
        .name("notify-sound")
        .description(Texts.startupText(PvpText.SETTING_NOTIFY_SOUND))
        .defaultValue(true)
        .build()
    );

    private final CombatDirector director = new CombatDirector();
    private final ModuleLedger ledger = new ModuleLedger();

    /**
     * The "I have it on and it does nothing" watch ({@link ActionWatch}). It lives here and not in
     * the director because it needs a piece of data the director does not see: which modules are
     * <b>really</b> on right now. The adapter measures -what the plan asks for, what is on and what
     * is left in the hotbar- and the core decides when that has stopped being a combat gap.
     */
    private final ActionWatch actionWatch = new ActionWatch();
    private final FriendLedger friendLedger = new FriendLedger();

    /**
     * What was last synced with the friends list, or {@code null} if it has not been
     * reconciled yet in this activation. While it does not change, the friends list is not read and
     * not a single byte is written to disk (spec §14.1).
     */
    private Set<String> lastSynced;

    private Plan lastPlan;
    private CombatSnapshot lastSnapshot;
    private String lastTargetName;
    private Double lastTargetDistance;
    private CombatState lastReported = CombatState.NO_COMBAT;
    private CombatPosture lastPosture = CombatPosture.CALM;
    /**
     * Whether your Y changed on the <b>previous</b> tick (redesign §5, critical C2). It is kept because
     * {@code Surround} checks {@code prevY != getY()} in {@code TickEvent.Pre} and this module measures
     * in {@code TickEvent.Post}: what the module will punish at the start of the next tick is the
     * movement seen here at the end of this one. Putting the two ticks together, the posture stops asking
     * for {@code surround} in either of them and the self-shutdown never gets to fire
     * while the director wants it on.
     */
    private boolean yChangedLastTick;
    private SkippedAlly skippedAlly;
    /** Names of ours already announced in this activation: each one is said only once. */
    private final Set<String> announcedAllies = new LinkedHashSet<>();
    /**
     * The warnings and omissions already said on the previous tick. It is what turns "this
     * happens twenty times per second" into one chat line: only what appears is said, and only
     * when it appears. Both sources are protected upstream -the core's resource hysteresis
     * for the omissions, the crystal count for the aura warning-, so an entry
     * that comes and goes does not bounce.
     */
    private Set<Object> announcedNotes = Set.of();

    public AutoPvp() {
        super(XploitsAddon.CATEGORY, "auto-pvp", Texts.startupText(PvpText.MODULE_DESC));
    }

    @Override
    public void onActivate() {
        director.reset();
        ledger.reset();
        actionWatch.reset();
        lastPlan = null;
        lastSnapshot = null;
        lastTargetName = null;
        lastTargetDistance = null;
        lastReported = CombatState.NO_COMBAT;
        lastPosture = CombatPosture.CALM;
        yChangedLastTick = false;
        skippedAlly = null;
        announcedAllies.clear();
        announcedNotes = Set.of();
        // The friends list is not touched here: the first reconciliation is the first tick's, which
        // already requires being in the world. Turning the module on from the ClickGUI in the main menu
        // writes nothing to the player's disk.
        friendLedger.reset();
        lastSynced = null;
        warnAlreadyActiveManagedModules();
    }

    @Override
    public void onDeactivate() {
        releaseAll();
        // What we put in the friends list leaves with us, and only what we put there.
        applyFriendChanges(friendLedger.release(meteorFriendNames()));
        friendLedger.reset();
        lastSynced = null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || !mc.player.isAlive()) {
            releaseAll();
            return;
        }

        // The two source lists, trimmed once per tick: they are used by the target filter
        // -once per player in sight- and by the sync with the Meteor friends.
        Set<String> couriers = AllyPolicy.names(kitRequesterCouriers());
        Set<String> tpyUsers = AllyPolicy.names(autoTpyUsers());
        syncMeteorFriends(couriers, tpyUsers);

        PlayerEntity target = findTarget(couriers, tpyUsers);
        lastTargetName = target == null ? null : nameOf(target);

        CombatSnapshot snapshot = snapshot(target, couriers, tpyUsers);
        lastSnapshot = snapshot;
        lastTargetDistance = snapshot.hasTarget() ? snapshot.targetDistance() : null;
        Plan plan = director.tick(snapshot, approachDistance.get(), threatMargin.get());
        lastPlan = plan;

        apply(plan);
        reportPhaseChange(plan);
        reportPostureChange(plan);
        reportNotes(plan);
        reportSkippedAlly();
        lastReported = plan.state();
        lastPosture = plan.posture();
    }

    /** One of ours who was in reach and was not attacked: who, why and at what distance. */
    private record SkippedAlly(String name, AllyPolicy.Allegiance allegiance, double distance) {}

    /**
     * The target, with ours discarded <b>inside</b> the selection predicate (spec §13).
     *
     * <p>It is the same predicate as {@code TargetUtils.getPlayerTarget(range, priority)} —checked
     * against the meteor-client 1.21.11 sources—, with two changes: {@code Friends.shouldAttack}
     * moves inside {@link AllyPolicy} (FRIEND is exactly its negation, so Meteor's filter
     * still applies the same way) and the kit-requester couriers and the auto-tpy users list are
     * added.
     *
     * <p>CRITICAL: the exclusion has to go in the predicate, not afterwards. Selecting the closest one
     * and discarding it if it turns out to be ours would return {@code null} with a courier next to you,
     * even with a real enemy ten blocks behind; inside the predicate the courier does not even
     * get into the list that is sorted, and the enemy is still the target.
     *
     * <p>This decides whom <b>this</b> module picks, and only that. The five it enables pick their
     * own target and do not know about these lists: making sure they do not cross a courier either is
     * the job of {@link #syncMeteorFriends} (spec §14).
     */
    private PlayerEntity findTarget(Set<String> couriers, Set<String> tpyUsers) {
        double range = targetRange.get();
        skippedAlly = null;

        Entity found = TargetUtils.get(entity -> {
            if (!(entity instanceof PlayerEntity player) || entity == mc.player) return false;
            if (player.isDead() || player.getHealth() <= 0) return false;
            if (!PlayerUtils.isWithin(entity, range)) return false;

            AllyPolicy.Allegiance allegiance =
                AllyPolicy.of(nameOf(player), Friends.get().isFriend(player), couriers, tpyUsers);
            if (allegiance.isOurs()) {
                noteSkippedAlly(player, allegiance);
                return false;
            }

            if (entity instanceof FakePlayerEntity fakePlayer) return !fakePlayer.noHit;
            return EntityUtils.getGameMode(player) == GameMode.SURVIVAL;
        }, SortPriority.LowestDistance);

        return found instanceof PlayerEntity player ? player : null;
    }

    /** Keeps the closest of ours discarded in this tick. */
    private void noteSkippedAlly(PlayerEntity player, AllyPolicy.Allegiance allegiance) {
        double distance = mc.player.distanceTo(player);
        if (skippedAlly == null || distance < skippedAlly.distance()) {
            skippedAlly = new SkippedAlly(nameOf(player), allegiance, distance);
        }
    }

    /** Make it visible, but without flooding the chat: each name is said only once per activation. */
    private void reportSkippedAlly() {
        if (skippedAlly == null || !notify.get()) return;
        // Cap: with the list full it starts from zero and warns again, better than growing without
        // end in a long session. The clearing goes BEFORE the add: clearing after recording the name
        // wiped it at the very moment of announcing it, and the ally who hit the cap was announced
        // twice, the second time as soon as they were in reach again.
        if (announcedAllies.size() >= MAX_ANNOUNCED_ALLIES) announcedAllies.clear();
        if (!announcedAllies.add(skippedAlly.name())) return;
        info(PvpText.NOT_ATTACKING, "name", skippedAlly.name(), "reason", skippedAlly.allegiance().reason());
    }

    private static String nameOf(PlayerEntity player) {
        return player.getGameProfile().name();
    }

    /**
     * The kit-requester couriers and the auto-tpy users list, <b>whether those modules are on
     * or off</b>. It is on purpose different from {@code AutoTpy.kitRequesterCouriers()}, which does
     * require {@code isActive()}: there the question is one of dispatch —who answers this TPA—, and if
     * kit-requester is off nobody else is going to answer it; here the question is one of identity —whose
     * side you are on—, and the courier who came with an earlier order is still next to you after
     * kit-requester turns off. Making it depend on the module would silently bring back the attack, which is
     * exactly the failure this fixes.
     */
    private static Set<String> kitRequesterCouriers() {
        KitRequester module = Modules.get().get(KitRequester.class);
        return module == null ? Set.of() : module.knownCouriers();
    }

    private static Set<String> autoTpyUsers() {
        AutoTpy module = Modules.get().get(AutoTpy.class);
        return module == null ? Set.of() : module.users();
    }

    /** Whether kit-requester accepts unknown couriers, which is what makes its list unreliable (spec §14.2). */
    private static boolean kitRequesterTrustsUnknownCouriers() {
        KitRequester module = Modules.get().get(KitRequester.class);
        return module != null && module.trustsUnknownCouriers();
    }

    /**
     * Keeps ours in the Meteor friends list (spec §14). It is what keeps the
     * five managed modules —which pick their own target and only respect that list— from
     * attacking a courier or someone in the users list too.
     *
     * <p><b>It reconciles on change, not per tick.</b> {@code Friends.add} and {@code Friends.remove}
     * save {@code friends.nbt} on every call (checked in the sources: both call
     * {@code save()}), so reconciling blindly twenty times per second would mean writing to the
     * player's disk twenty times per second. While the set to sync is
     * the same as last time nothing is done: the friends list is neither walked nor written.
     * It changes when the player edits {@code known-couriers} or {@code users}, when kit-requester
     * learns a courier, when {@code trust-unknown-couriers} or {@code sync-friends} is touched, and on
     * the first tick of every activation.
     */
    private void syncMeteorFriends(Set<String> couriers, Set<String> tpyUsers) {
        Set<String> wanted = syncFriends.get()
            ? FriendLedger.syncable(couriers, kitRequesterTrustsUnknownCouriers(), tpyUsers)
            : Set.of();
        if (wanted.equals(lastSynced)) return;
        lastSynced = wanted;
        applyFriendChanges(friendLedger.reconcile(wanted, meteorFriendNames()));
    }

    /** The names in the friends list right now, as Meteor stores them. */
    private static Set<String> meteorFriendNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Friend friend : Friends.get()) names.add(friend.getName());
        return names;
    }

    /**
     * Carries out what {@link FriendLedger} decided. An addition Meteor rejects stops counting as
     * ours on the spot; and before removing anything it checks that the friend
     * {@code Friends.get(String)} returns —which compares with {@code equalsIgnoreCase}— is exactly the
     * name we put there. Otherwise it would be enough for the player to have a
     * "stormaegis44" of their own for us to remove their entry when releasing ours.
     */
    private void applyFriendChanges(FriendLedger.Result result) {
        if (result.isEmpty()) return;

        Friends friends = Friends.get();
        List<String> added = new ArrayList<>();
        for (String name : result.toAdd()) {
            if (friends.add(new Friend(name))) added.add(name);
            else friendLedger.disown(name);
        }

        List<String> removed = new ArrayList<>();
        for (String name : result.toRemove()) {
            Friend friend = friends.get(name);
            if (friend != null && name.equals(friend.getName()) && friends.remove(friend)) removed.add(name);
        }

        if (!notify.get()) return;
        if (!added.isEmpty()) {
            info(PvpText.FRIENDS_ADDED, "names", String.join(", ", added));
        }
        if (!removed.isEmpty()) {
            info(PvpText.FRIENDS_REMOVED, "names", String.join(", ", removed));
        }
    }

    /** Reports the phase change: OUT_OF_RESOURCES always and loud (spec §4.1, §6); the rest, if notify allows it. */
    private void reportPhaseChange(Plan plan) {
        if (plan.state() == lastReported) return;

        if (plan.state() == CombatState.OUT_OF_RESOURCES) {
            warnOutOfResources(plan);
        } else if (notify.get()) {
            info(phase(plan));
        }
    }

    /**
     * Reports the other axis (redesign §3). It goes on its own line, and not attached to the phase,
     * because they are orthogonal: the posture changes without the phase moving -you get crystalled
     * while still in SURFACE- and the other way round. It is only said when it changes, which with the
     * §5 threshold is a couple of lines per fight, not twenty per second.
     */
    private void reportPostureChange(Plan plan) {
        if (plan.posture() == lastPosture || !notify.get()) return;

        if (plan.posture() == CombatPosture.THREATENED) {
            info(PvpText.THREATENED, "damage", lastSnapshot.incomingDamage(),
                "health", lastSnapshot.selfTotalHealth());
        } else {
            info(PvpText.CALM);
        }
    }

    /**
     * Says once what the plan is not going to enable and why, and the warnings that are not
     * omissions (redesign §7: the aura without crystals is enabled anyway, but the player has to
     * know). Without this memory it would be twenty lines per second; with it, one per new thing.
     *
     * <p>The loud {@code OUT_OF_RESOURCES} warning is left out: it has its own path and sounds
     * even with {@code notify} off.
     */
    private void reportNotes(Plan plan) {
        // The key of an omission is the module name, NOT its reason: the reason carries
        // how much you have left ("you have 2, it needs 8"), and that drops with every block you spend, so
        // comparing whole reasons would be one line per tick again. What the player needs
        // to know is that auto-trap is not going to come up, not the exact number of this tick -which does
        // show, and up to date, in .xploits pvp-.
        Map<Object, Msg> notes = new LinkedHashMap<>();
        for (Msg warning : plan.warnings()) notes.put(warning, warning);
        for (Skipped skipped : plan.skipped()) {
            notes.put(skipped.module().name(),
                Msg.of(PvpText.NOT_ENABLING, "module", skipped.module().name(), "reason", skipped.reason()));
        }

        if (notify.get() && plan.state() != CombatState.OUT_OF_RESOURCES) {
            for (Map.Entry<Object, Msg> note : notes.entrySet()) {
                if (!announcedNotes.contains(note.getKey())) warning(note.getValue());
            }
        }
        announcedNotes = Set.copyOf(notes.keySet());
    }

    /** The phase and, if there is one, the target: what is announced on a change and what the console shows. */
    private Msg phase(Plan plan) {
        return Msg.of(PvpText.PHASE, "state", PvpText.of(plan.state()), "target", targetSuffix());
    }

    private Msg targetSuffix() {
        return lastTargetName == null ? Msg.of(PvpText.NOTHING) : Msg.of(PvpText.TARGET_SUFFIX, "name", lastTargetName);
    }

    private CombatSnapshot snapshot(PlayerEntity target, Set<String> couriers, Set<String> tpyUsers) {
        Inventory inventory = inventory();
        // Your half of the snapshot is always read, target or not: the defensive axis (§5) is
        // derived from you and does not depend on the director having picked someone. They are the same two
        // pieces of data AutoTotem, Offhand and AutoLog use to decide the same thing, and the client knows them
        // on every server: getTotalHealth() is health + absorption and possibleHealthReductions()
        // is the damage ALREADY aimed at you (placed crystals, players with a sword at <=5, beds in the
        // Nether and falling). isInHole(false) is the hole without doubles, the only place for the surround.
        double totalHealth = PlayerUtils.getTotalHealth();
        double incomingDamage = PlayerUtils.possibleHealthReductions();
        boolean inHole = PlayerUtils.isInHole(false);
        boolean onGround = mc.player.isOnGround();
        boolean antiSuicide = crystalAuraAntiSuicide();
        int hostiles = hostilesInCrystalRange(couriers, tpyUsers);
        // The same comparison Surround makes in its toggle-on-y-change -field_6036 is lastY in
        // yarn 1.21.11+build.3, checked in the mappings, not assumed-, plus the previous tick's:
        // the module evaluates it in TickEvent.Pre and here it is measured in Post, so the movement that
        // will make it turn off at the start of the coming tick is the one seen at the end of this one, and with the
        // two ticks together the posture asks for it in neither.
        boolean movedNow = mc.player.lastY != mc.player.getY();
        boolean yChanged = movedNow || yChangedLastTick;
        yChangedLastTick = movedNow;

        if (target == null) {
            return new CombatSnapshot(false, 0, 0, 0, false, false,
                mc.player.isGliding(), inventory.totems(), inventory.resources(),
                null, hostiles, totalHealth, incomingDamage, inHole, onGround, yChanged, antiSuicide);
        }

        // SURROUNDED requires auto-city's real reach to the block, not to the target (spec §4.2.1,
        // corrected): the block is a horizontal neighbour of the target and can be on the side opposite
        // to where you are. It is measured exactly as Meteor's AutoCity.java does
        // (PlayerUtils.squaredDistanceTo against the BlockPos, to the block's minimum corner, not to the
        // center) so that the comparison in the core is the same one auto-city will apply afterwards.
        BlockPos cityBlock = EntityUtils.getCityBlock(target);
        double cityBlockDistance = cityBlock != null ? Math.sqrt(PlayerUtils.squaredDistanceTo(cityBlock)) : 0;
        return new CombatSnapshot(true, mc.player.distanceTo(target),
            surroundSides(target), cityBlockDistance, protectedFromCrystals(target), target.isGliding(),
            mc.player.isGliding(), inventory.totems(), inventory.resources(),
            nameOf(target), hostiles, totalHealth, incomingDamage, inHole, onGround, yChanged, antiSuicide);
    }

    /**
     * How many of the target's four horizontal neighbours, at the height of its feet, are of the kind
     * {@code EntityUtils.getCityBlock()} considers mineable (minor M1).
     *
     * <p>The director used {@code getCityBlock(target) != null} as "has a surround", and it is not:
     * checked in the {@code meteor-client:1.21.11-SNAPSHOT} sources, that method walks the
     * four horizontal directions and returns <b>the closest</b> one from this list, or
     * {@code null}; it never counts how many there are. An enemy standing by the obsidian wall of
     * any base -or by the obsidian your own {@code auto-trap} has just placed- gave a
     * block, classified as {@code SURROUNDED} and the director started mining the wall.
     *
     * <p>The list is the same as Meteor's, and it does not include bedrock (spec §2): obsidian, netherite
     * block, crying obsidian, respawn anchor and ancient debris. How many sides
     * are needed is decided by the core ({@code CombatDirector.SURROUND_MIN_SIDES}), which is where it
     * can be tested; here they are only counted.
     */
    private int surroundSides(PlayerEntity target) {
        BlockPos feet = target.getBlockPos();
        int sides = 0;
        for (Direction direction : Direction.values()) {
            if (direction.getAxis().isVertical()) continue;
            if (isCityBlock(mc.world.getBlockState(feet.offset(direction)).getBlock())) sides++;
        }
        return sides;
    }

    /** The five blocks {@code EntityUtils.getCityBlock()} accepts, checked in its sources. */
    private static boolean isCityBlock(Block block) {
        return block == Blocks.OBSIDIAN || block == Blocks.CRYING_OBSIDIAN
            || block == Blocks.NETHERITE_BLOCK || block == Blocks.RESPAWN_ANCHOR
            || block == Blocks.ANCIENT_DEBRIS;
    }

    /**
     * Does what is at its feet protect it from a crystal? (redesign §4.1). It is the question that decides
     * {@code BURROWED}, and it is not the one asked before.
     *
     * <p>Before, {@code blocksMovement()} was asked, which is "is there something solid there?". In the
     * 1.21.11 bytecode that is {@code !COBWEB && !BAMBOO_SAPLING && isSolid()}, and {@code isSolid()}
     * only requires a half-side of 0.7291666666666666: a bottom slab gives 0.833 and passed. That is,
     * standing on a slab, a stair, a chest or a trapdoor was classified as
     * BURROWED, the director turned off the aura and stood there placing anvils against someone who was
     * protected from nothing.
     *
     * <p>The right question has two halves and both are needed:
     * <ul>
     *   <li><b>Blast resistance &ge; 600</b> ({@code Block#getBlastResistance()}). It is the
     *       threshold Meteor's {@code PlayerUtils.isInHole(boolean)} already uses to decide whether a
     *       block protects you, so the classification and what Meteor considers a hole say
     *       the same thing. Obsidian, crying obsidian, the netherite block, ancient
     *       debris and bedrock meet it; stone, dirt and cobweb do not.</li>
     *   <li><b>Full cube</b> ({@code AbstractBlockState#isFullCube}, which is
     *       {@code Block.isShapeFullCube(getCollisionShape(...))}). Without this, blocks with 1200 of
     *       resistance and a partial shape -an enchanting table, a half-used anchor- would take
     *       whoever stands on them as burrowed, which is the same slab failure through the other
     *       door.</li>
     * </ul>
     *
     * <p>Both names are checked with {@code javap} on this project's remapped Minecraft jar
     * (yarn 1.21.11+build.3), not assumed.
     */
    private boolean protectedFromCrystals(PlayerEntity player) {
        BlockPos pos = player.getBlockPos();
        BlockState state = mc.world.getBlockState(pos);
        return state.getBlock().getBlastResistance() >= PROTECTIVE_BLAST_RESISTANCE
            && state.isFullCube(mc.world, pos);
    }

    /**
     * How many hostiles are in crystal range, <b>protected or not</b> (redesign §4.4, corrected by
     * critical C1). It is what prevents the obvious bait: one burrows, the other crystals you, and the
     * director turns off your aura against the second because the phase is about a single player.
     *
     * <p>The filter is the same as {@link #findTarget}'s —ours out, survival,
     * alive—, with a single difference: the range is the crystal one, not the module's
     * {@code target-range}.
     *
     * <p><b>The protected one is no longer discarded</b>, and that discard was the critical. It asked "can I
     * crystal someone?" to decide "do I need the aura?", which are two different questions: the
     * aura places and breaks, and breaking spends none of your crystals (§2). The other one being burrowed or
     * surrounded saves them from your crystals; it does not save you from theirs. With the discard in place,
     * an enemy who burrows at 3.5 and keeps crystalling you from inside the burrow made the
     * count zero and the ledger turned off your autobreak against the only one who could kill you.
     *
     * <p>With it goes the use of {@code EntityUtils.getCityBlock()} for this, which also
     * made the failure worse: that method does not check whether someone has a surround, but whether there is
     * a mineable block next to them, so an enemy by an obsidian wall -or by the obsidian your
     * own {@code auto-trap} had just placed- counted as "protected" and disappeared from the
     * count.
     */
    private int hostilesInCrystalRange(Set<String> couriers, Set<String> tpyUsers) {
        int hostiles = 0;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || player.isDead() || player.getHealth() <= 0) continue;
            if (!PlayerUtils.isWithin(player, CombatDirector.CRYSTAL_RANGE)) continue;
            if (AllyPolicy.of(nameOf(player), Friends.get().isFriend(player), couriers, tpyUsers).isOurs()) continue;
            if (player instanceof FakePlayerEntity fakePlayer) {
                if (fakePlayer.noHit) continue;
            } else if (EntityUtils.getGameMode(player) != GameMode.SURVIVAL) {
                continue;
            }
            hostiles++;
        }
        return hostiles;
    }

    /**
     * Whether the {@code anti-suicide} of {@code crystal-aura} is on (redesign §7, through the door
     * of §10). It is the only thing that decides whether the totem floor stays in place.
     *
     * <p>It is read through Meteor's public settings API —{@code Module.settings} is
     * {@code public final} and {@code Settings#get(String, Class)} returns the already typed
     * {@code Setting<Boolean>}, comparing the name case-insensitively—, not by reflection nor by a mixin:
     * the {@code antiSuicide} field of {@code CrystalAura} is private, but the setting is not.
     *
     * <p>If the module is not loaded or the setting does not show up, the answer is <b>off</b>, which is the
     * prudent value: without being able to prove the protection exists, the totem floor stays.
     */
    private static boolean crystalAuraAntiSuicide() {
        Module crystalAura = byName(ManagedModules.CRYSTAL_AURA.name());
        if (crystalAura == null) return false;
        Setting<Boolean> antiSuicide = crystalAura.settings.get("anti-suicide", Boolean.class);
        return antiSuicide != null && antiSuicide.get();
    }

    /** Loaded players and how many of them are on your side, with the same definition as the target. Works with auto-pvp off. */
    public record Neighbourhood(int loaded, int friendly) {
    }

    public Optional<Neighbourhood> neighbourhood() {
        if (mc.world == null || mc.player == null) return Optional.empty();
        Set<String> couriers = AllyPolicy.names(kitRequesterCouriers());
        Set<String> tpyUsers = AllyPolicy.names(autoTpyUsers());
        int loaded = 0;
        int friendly = 0;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            loaded++;
            if (AllyPolicy.of(nameOf(player), Friends.get().isFriend(player), couriers, tpyUsers).isOurs()) friendly++;
        }
        return Optional.of(new Neighbourhood(loaded, friendly));
    }

    /** The hotbar ammunition, which is what the modules it manages use. The pickaxe is not ammunition. */
    public Optional<Map<Resource, Integer>> hotbarResources() {
        if (mc.player == null) return Optional.empty();
        Map<Resource, Integer> resources = new EnumMap<>(Resource.class);
        resources.putAll(inventory().resources());
        resources.remove(Resource.PICKAXE);
        return Optional.of(resources);
    }

    @Override
    public String activity() {
        if (lastPlan == null) return Texts.render(PvpText.READING);
        return Texts.render(phase(lastPlan));
    }

    /** What is read from the inventory for the snapshot: a single scan of the 36 slots for everything. */
    private record Inventory(Map<Resource, Integer> resources, int totems) {}

    private Inventory inventory() {
        Map<Resource, Integer> counts = new EnumMap<>(Resource.class);
        int totems = mc.player.getOffHandStack().getItem() == Items.TOTEM_OF_UNDYING ? 1 : 0;
        for (int slot = FIRST_SLOT; slot <= INVENTORY_LAST_SLOT; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty()) continue;
            if (stack.getItem() == Items.TOTEM_OF_UNDYING) { totems++; continue; }
            Resource resource = resourceOf(stack);
            if (resource == null) continue;
            // CRITICAL (spec §6): no resource counts outside the hotbar. The five modules that
            // search with InvUtils.findInHotbar/testInHotbar already require it because they look no further;
            // auto-city looks for the pickaxe with InvUtils.find over the whole inventory, but rejects the
            // result if it is not in the hotbar (FindItemResult.isHotbar()) and turns itself off with an
            // error. Counting the backpack for the pickaxe would say "taken" of a module that turns itself off
            // in its own onActivate/onTick.
            if (slot > HOTBAR_LAST_SLOT) continue;
            counts.merge(resource, stack.getCount(), Integer::sum);
        }
        return new Inventory(counts, totems);
    }

    private static Resource resourceOf(ItemStack stack) {
        if (stack.getItem() == Items.END_CRYSTAL) return Resource.CRYSTALS;
        if (stack.getItem() == Items.OBSIDIAN) return Resource.OBSIDIAN;
        if (stack.getItem() == Items.COBWEB) return Resource.WEBS;
        if (stack.getItem() == Items.ANVIL) return Resource.ANVILS;
        if (stack.getItem() == Items.NETHERITE_PICKAXE || stack.getItem() == Items.DIAMOND_PICKAXE) return Resource.PICKAXE;
        return null;
    }

    /**
     * Enables what the plan asks for and disables what it took and no longer asks for; never what is not its own (spec
     * §7). The ownership decision belongs to {@link ModuleLedger}, pure logic with its own tests
     * (spec §12): here the real names are only gathered and what it decides is carried out.
     */
    private void apply(Plan plan) {
        Set<String> wanted = new LinkedHashSet<>();
        for (ManagedModule module : plan.enable()) wanted.add(module.name());

        Set<String> active = new LinkedHashSet<>();
        for (ManagedModule module : ManagedModules.ALL) {
            Module m = byName(module.name());
            if (m != null && m.isActive()) active.add(module.name());
        }

        // The watch measures BEFORE carrying out anything: `active` is what is really on at the
        // start of this tick, which is what has to be matched against what the plan wants. A module
        // just enabled does not count until the next tick, and that is right: it has not yet
        // had a chance to spend.
        reportIdle(actionWatch.update(lastSnapshot, wanted, active));

        ModuleLedger.Result result = ledger.apply(director.state(), plan.posture(), wanted, active);

        for (String name : result.toDisable()) {
            Module module = byName(name);
            if (module != null) module.disable();
        }
        for (String name : result.toEnable()) {
            Module module = byName(name);
            if (module != null) module.enable();
        }
        if (notify.get()) {
            for (String name : result.newlyReleased()) {
                info(PvpText.RELEASED, "module", name);
            }
        }
    }

    /**
     * Says what the watch has just concluded ({@link ActionWatch}). <b>It is said even with
     * {@code notify} off</b>, for the same reason as {@code OUT_OF_RESOURCES}: both are
     * failures that cannot be seen any other way -the module announces the phase, enables the
     * aura and nothing happens-, and keeping quiet about them is exactly what the module's principle forbids. The
     * normal warnings -phase changes, posture changes, omissions- do respect the setting: those are
     * plainly visible through their effects.
     *
     * <p>It turns nothing off. The warning is the end of the road: whoever decides whether {@code min-damage} is
     * badly set is the player, and meanwhile the module stays ready for the tick in which there is
     * a valid position.
     */
    private void reportIdle(List<ActionWatch.Idle> newlyIdle) {
        for (ActionWatch.Idle idle : newlyIdle) warning(ActionWatch.reason(idle));
    }

    /** I1: if something it manages was already on when auto-pvp was turned on, it is the player's and that has to be said. */
    private void warnAlreadyActiveManagedModules() {
        for (ManagedModule managed : ManagedModules.ALL) {
            Module module = byName(managed.name());
            if (module == null || !module.isActive()) continue;

            if (managed.equals(ManagedModules.CRYSTAL_AURA)) {
                warning(PvpText.CRYSTAL_AURA_ALREADY_ON);
            } else {
                warning(PvpText.ALREADY_ON, "module", managed.name());
            }
        }
    }

    private void releaseAll() {
        for (String name : ledger.owned()) {
            Module module = byName(name);
            if (module != null && module.isActive()) module.disable();
        }
        ledger.reset();
        director.reset();
        actionWatch.reset();
        lastPlan = null;
        lastSnapshot = null;
        lastReported = CombatState.NO_COMBAT;
        lastPosture = CombatPosture.CALM;
        yChangedLastTick = false;
        skippedAlly = null;
        announcedAllies.clear();
        announcedNotes = Set.of();
    }

    private static Module byName(String name) {
        return Modules.get().get(name);
    }

    /** I5: OUT_OF_RESOURCES is the only loud warning (spec §4.1, §6): chat in warning() and a toast with sound. */
    private void warnOutOfResources(Plan plan) {
        Msg message = outOfResourcesMessage(plan);
        warning(message);

        MeteorToast.Builder toast = new MeteorToast.Builder("Xploits").text(Texts.render(message)).icon(Items.BARRIER);
        // MeteorToast.update() calls play(customSound) without checking for null and vanilla dereferences it:
        // NPE on the render thread. Never pass null; it is silenced with zero volume, just like ElytraReplace.
        if (!notifySound.get()) {
            toast.sound(PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), 1.2f, 0f));
        }
        mc.getToastManager().add(toast.build());
    }

    private static Msg outOfResourcesMessage(Plan plan) {
        Object reasons = null;
        for (Skipped skipped : plan.skipped()) {
            Msg item = Msg.of(PvpText.OUT_OF_RESOURCES_ITEM, "module", skipped.module().name(), "reason", skipped.reason());
            reasons = reasons == null ? item : Msg.of(PvpText.JOIN_COMMA, "first", reasons, "rest", item);
        }
        if (reasons == null) return Msg.of(PvpText.OUT_OF_RESOURCES_NONE);
        return Msg.of(PvpText.OUT_OF_RESOURCES, "reasons", reasons);
    }

    /** Appends {@code line} after {@code head}; with a null {@code head}, the line alone. */
    private static Msg append(Msg head, Msg line) {
        return head == null ? line : Msg.of(PvpText.CONCAT, "first", head, "second", line);
    }

    public Msg status() {
        if (!isActive()) return Msg.of(PvpText.STATUS_OFF);
        if (lastPlan == null) return Msg.of(PvpText.STATUS_UNREAD);

        Set<String> owned = ledger.owned();

        // The two axes, on the first line and in this order (redesign §3): the enemy sets the phase
        // and the posture is you, and both are true at once. Below, the detail of each
        // one, so that it can be seen at a glance why what is on is on.
        Msg target = Msg.of(PvpText.NOTHING);
        if (lastTargetName != null) {
            target = Msg.of(PvpText.STATUS_TARGET, "name", lastTargetName, "distance", lastTargetDistance == null
                ? Msg.of(PvpText.NOTHING)
                : Msg.of(PvpText.STATUS_TARGET_DISTANCE, "distance", lastTargetDistance));
        }
        Msg self = Msg.of(PvpText.NOTHING);
        if (lastSnapshot != null) {
            int hostiles = lastSnapshot.hostilesInCrystalRange();
            self = Msg.of(PvpText.STATUS_SELF, "health", lastSnapshot.selfTotalHealth(),
                "damage", lastSnapshot.incomingDamage(), "margin", threatMargin.get(),
                "hole", lastSnapshot.selfInHole() ? PvpText.STATUS_IN_HOLE : PvpText.NOTHING,
                "gliding", lastSnapshot.selfGliding() ? PvpText.STATUS_GLIDING : PvpText.NOTHING,
                "crystals", hostiles > 0
                    ? Msg.of(PvpText.STATUS_CRYSTALS, "count", hostiles,
                        "hostiles", hostiles == 1 ? PvpText.HOSTILE : PvpText.HOSTILES)
                    : Msg.of(PvpText.NOTHING));
        }
        Msg ally = skippedAlly == null
            ? Msg.of(PvpText.NOTHING)
            : Msg.of(PvpText.STATUS_ALLY, "name", skippedAlly.name(), "reason", skippedAlly.allegiance().reason(),
                "distance", skippedAlly.distance());
        Msg skippedLines = null;
        for (Skipped skipped : lastPlan.skipped()) {
            skippedLines = append(skippedLines,
                Msg.of(PvpText.STATUS_SKIPPED, "module", skipped.module().name(), "reason", skipped.reason()));
        }
        Msg warningLines = null;
        for (Msg warning : lastPlan.warnings()) {
            warningLines = append(warningLines, Msg.of(PvpText.STATUS_WARNING, "warning", warning));
        }
        List<String> yours = yourActiveModules(owned);
        return Msg.of(PvpText.STATUS, "state", PvpText.of(lastPlan.state()),
            "seconds", director.ticksInState() / TICKS_PER_SECOND, "posture", PvpText.of(lastPlan.posture()),
            "target", target, "self", self, "ally", ally,
            "friends", syncedFriendsLine(),
            "owned", owned.isEmpty() ? PvpText.NONE : String.join(", ", owned),
            "idle", idleLine(),
            "skipped", skippedLines == null ? Msg.of(PvpText.NOTHING) : skippedLines,
            "warnings", warningLines == null ? Msg.of(PvpText.NOTHING) : warningLines,
            "yours", yours.isEmpty() ? PvpText.NONE : String.join(", ", yours));
    }

    /**
     * Which modules have been on for a while without spending anything ({@link ActionWatch}), and for how long. It is the
     * other half of the warning: the warning is said once, but the situation lasts -a badly set
     * {@code min-damage} does not heal by itself- and it has to be possible to check it while it lasts.
     *
     * <p>The line always shows, even when there are none, because saying "none" is also
     * information: it means what is on is spending.
     *
     * <p>Those that share a stack show together and marked as a joint verdict, which is the only thing
     * the measurement supports: the inventory says how much obsidian is left, not who placed it.
     */
    private Msg idleLine() {
        List<ActionWatch.Idle> idle = actionWatch.idle();
        if (idle.isEmpty()) return Msg.of(PvpText.IDLE_NONE);

        Object parts = null;
        for (ActionWatch.Idle verdict : idle) {
            List<String> names = new ArrayList<>();
            for (ManagedModule module : verdict.modules()) names.add(module.name());
            // The "+" is not decorative: it says those names go together because they share a stack and the
            // verdict cannot be split between them.
            Msg joint = verdict.joint()
                ? Msg.of(PvpText.IDLE_JOINT, "count", names.size())
                : Msg.of(PvpText.NOTHING);
            Object joined = names.getFirst();
            for (String name : names.subList(1, names.size())) {
                joined = Msg.of(PvpText.JOIN_PLUS, "first", joined, "rest", name);
            }
            Msg part = Msg.of(PvpText.IDLE_PART, "modules", joined,
                "seconds", verdict.ticks() / TICKS_PER_SECOND, "joint", joint);
            parts = parts == null ? part : Msg.of(PvpText.JOIN_COMMA, "first", parts, "rest", part);
        }
        return Msg.of(PvpText.IDLE_LINE, "parts", parts);
    }

    /**
     * What it is touching right now in the Meteor friends list, and if it touches nothing, why: the
     * player has to be able to see in one line that their global configuration is being intervened —or that
     * it is not, and then the other five modules can attack ours (spec §14.3).
     */
    private Msg syncedFriendsLine() {
        if (!syncFriends.get()) return Msg.of(PvpText.FRIENDS_SYNC_OFF);
        Set<String> synced = friendLedger.added();
        if (synced.isEmpty()) {
            return Msg.of(kitRequesterTrustsUnknownCouriers() ? PvpText.FRIENDS_NONE_TRUSTING : PvpText.NONE);
        }
        return Msg.of(PvpText.FRIENDS_SYNCED, "names", String.join(", ", synced));
    }

    /** I6: which combat modules you have active that the director does not control, not a fixed list. */
    private List<String> yourActiveModules(Set<String> owned) {
        List<String> result = new ArrayList<>();
        for (ManagedModule managed : ManagedModules.ALL) {
            if (owned.contains(managed.name())) continue;
            Module module = byName(managed.name());
            if (module != null && module.isActive()) result.add(managed.name());
        }
        for (String name : ALWAYS_YOURS) {
            Module module = byName(name);
            if (module != null && module.isActive()) result.add(name);
        }
        return result;
    }
}
