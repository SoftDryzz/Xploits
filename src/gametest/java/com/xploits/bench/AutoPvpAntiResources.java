package com.xploits.bench;

import com.xploits.pvp.core.CombatPosture;
import com.xploits.pvp.core.CombatState;
import com.xploits.pvp.core.ManagedModule;
import com.xploits.pvp.core.ManagedModules;
import com.xploits.pvp.core.MissingModules;
import com.xploits.pvp.core.Plan;
import com.xploits.pvp.core.PvpText;
import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.MessageKey;
import com.xploits.shared.core.i18n.Msg;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.item.Items;

import java.util.List;
import java.util.Optional;

/**
 * CHECK {@code autopvp-anti-resources}: with a crystal standing next to the player, auto-pvp's posture is
 * THREATENED, and the anti modules come up on what they really need. anti-bed comes up with no string
 * (it breaks a bed on your head without any), hole-filler and anti-anvil come up on the obsidian, and
 * anti-anchor never comes up: with no slabs it is short, and Meteor 1.21.11 does not even register it,
 * which the plan and the status must say (the status's whole "missing in Meteor" line, on every tick
 * with a plan). No tick is OUT_OF_RESOURCES, the standing crystal is never broken (one spawned) and
 * crystal-aura is never on.
 *
 * <p>Standard loadout (no string, no slabs) plus 64 cobwebs in hotbar 3, so auto-web has something to
 * place and the offensive half is never empty-handed. Profile {@code balanced} with
 * {@code use-crystal-aura} off, so nothing of ours breaks the standing crystal. Recorder off, a Menacer
 * sparring. Auto-pvp runs 12 s; the plan is checked from T0+25 (after the crystal) to T0+240.
 */
final class AutoPvpAntiResources implements Scenario {
    private static final int FIRST_CHECKED = Menacer.FIRST_CRYSTAL + 5;
    private static final int LAST_CHECKED = 240;
    private static final int MIN_THREATENED = 100;
    private static final int WEB_SLOT = 3;
    private static final int WEBS = 64;

    private AutoPvpScene scene;
    private Menacer menacer;

    @Override
    public String name() {
        return "autopvp-anti-resources";
    }

    @Override
    public Kind kind() {
        return Kind.CHECK;
    }

    @Override
    public int seconds() {
        return 12;
    }

    @Override
    public void arrange(Bench bench) {
        scene = AutoPvpScene.arrange(bench, "balanced");
        bench.setting(scene.autoPvp, "Modules", "use-" + ManagedModules.CRYSTAL_AURA.name(), false);
        bench.arena().loadout(false);
        bench.arena().give(WEB_SLOT, Items.COBWEB, WEBS);
        menacer = new Menacer();
        bench.spawn(menacer);
    }

    /**
     * One tick as the check reads it: the plan (null before auto-pvp's first), why anti-anchor was
     * skipped (null when it was not), the status in English, and which of the Meteor modules are on.
     */
    private record Look(Plan plan, MessageKey antiAnchorSkip, String status, boolean antiBedOn,
                        boolean holeFillerOn, boolean antiAnvilOn, boolean antiAnchorOn, boolean auraOn) {
    }

    @Override
    public Metrics act(Bench bench) {
        Catalog english = Catalog.load(Language.EN, problem -> { });
        boolean anchorMissing = bench.fromClient(client -> Modules.get().get(ManagedModules.ANTI_ANCHOR.name()) == null);
        MessageKey anchorReason = anchorMissing ? PvpText.MODULE_MISSING_SKIP : PvpText.SHORTAGE;
        // The whole line the status must carry, as the player reads it, not just the module's name.
        List<String> missingNames = bench.fromClient(client ->
            MissingModules.measure(name -> Modules.get().get(name) != null).names());
        String missingLine = english.render(Msg.of(PvpText.STATUS_MISSING, "modules", String.join(", ", missingNames)));

        scene.start(bench, false);
        int threatened = 0;
        int outOfResources = 0;
        int firstOutOfResources = -1;
        int anchorEnabled = 0;
        int anchorOn = 0;
        int noAntiBed = 0;
        int noHoleFiller = 0;
        int noAntiAnvil = 0;
        int anchorWrongReason = 0;
        int statusWithoutAnchor = 0;
        int auraOn = 0;
        boolean antiBedOn = false;
        boolean holeFillerOn = false;
        boolean antiAnvilOn = false;
        while (bench.sinceT0() < LAST_CHECKED) {
            bench.ticks(1);
            int t = bench.sinceT0();
            Look look = look(bench, english);
            Plan plan = look.plan();
            if (plan != null && plan.state() == CombatState.OUT_OF_RESOURCES) {
                outOfResources++;
                if (firstOutOfResources < 0) firstOutOfResources = t;
            }
            if (look.antiAnchorOn()) anchorOn++;
            if (look.auraOn()) auraOn++;
            if (t < FIRST_CHECKED) continue;

            antiBedOn |= look.antiBedOn();
            holeFillerOn |= look.holeFillerOn();
            antiAnvilOn |= look.antiAnvilOn();
            if (plan == null) continue;
            if (anchorMissing && !look.status().contains(missingLine)) statusWithoutAnchor++;
            if (plan.enable().contains(ManagedModules.ANTI_ANCHOR)) anchorEnabled++;
            if (plan.posture() != CombatPosture.THREATENED) continue;

            threatened++;
            if (!plan.enable().contains(ManagedModules.ANTI_BED)) noAntiBed++;
            if (!plan.enable().contains(ManagedModules.HOLE_FILLER)) noHoleFiller++;
            if (!plan.enable().contains(ManagedModules.ANTI_ANVIL)) noAntiAnvil++;
            if (look.antiAnchorSkip() != anchorReason) anchorWrongReason++;
        }

        int crystals = bench.fromServer(srv -> menacer.spawned());
        // The threat is one standing crystal: if anything broke it, the posture checks below would be
        // about another fight. Only crystal-aura could, and it is kept off by the profile.
        Bench.check(crystals == 1, "the Menacer spawned " + crystals + " crystals: the standing one was broken");
        Bench.check(auraOn == 0, "crystal-aura was on for " + auraOn + " ticks");
        Bench.check(threatened >= MIN_THREATENED, "auto-pvp was threatened on " + threatened + " ticks, fewer than "
            + MIN_THREATENED + " (crystals spawned: " + crystals + ")");
        Bench.check(outOfResources == 0, "auto-pvp reported OUT_OF_RESOURCES on " + outOfResources
            + " ticks (first at tick " + firstOutOfResources + ")");
        Bench.check(noAntiBed == 0 && noHoleFiller == 0 && noAntiAnvil == 0,
            "a threatened plan left out anti-bed on " + noAntiBed + " ticks, hole-filler on " + noHoleFiller
                + " ticks and anti-anvil on " + noAntiAnvil + " ticks");
        Bench.check(anchorEnabled == 0, "the plan enabled anti-anchor on " + anchorEnabled + " ticks");
        Bench.check(anchorWrongReason == 0, "a threatened plan did not skip anti-anchor as "
            + (anchorMissing ? "missing in Meteor" : "short of slabs") + " on " + anchorWrongReason + " ticks");
        Bench.check(statusWithoutAnchor == 0, "the status lacked the line '" + missingLine.strip() + "' on "
            + statusWithoutAnchor + " ticks");
        Bench.check(anchorOn == 0, "the anti-anchor module was on for " + anchorOn + " ticks");
        Bench.check(antiBedOn, "the anti-bed module was never on");
        Bench.check(holeFillerOn, "the hole-filler module was never on");
        Bench.check(antiAnvilOn, "the anti-anvil module was never on");
        bench.finish();
        return Metrics.none();
    }

    private Look look(Bench bench, Catalog english) {
        return bench.fromClient(client -> {
            Optional<Plan> plan = scene.autoPvp.currentPlan();
            MessageKey anchorSkip = plan.map(Plan::skipped).orElse(List.of()).stream()
                .filter(s -> s.module().equals(ManagedModules.ANTI_ANCHOR))
                .map(s -> s.reason().key())
                .findFirst().orElse(null);
            return new Look(plan.orElse(null), anchorSkip, english.render(scene.autoPvp.status()),
                on(ManagedModules.ANTI_BED), on(ManagedModules.HOLE_FILLER), on(ManagedModules.ANTI_ANVIL),
                on(ManagedModules.ANTI_ANCHOR), on(ManagedModules.CRYSTAL_AURA));
        });
    }

    /** Whether the Meteor module of that name is on; one Meteor does not register is off. Client thread. */
    private static boolean on(ManagedModule managed) {
        Module module = Modules.get().get(managed.name());
        return module != null && module.isActive();
    }
}
