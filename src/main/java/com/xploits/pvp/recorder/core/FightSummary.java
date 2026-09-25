package com.xploits.pvp.recorder.core;

import com.xploits.pvp.core.PvpText;
import com.xploits.pvp.recorder.core.FightAnalysis.Cause;
import com.xploits.pvp.recorder.core.FightRecord.ModuleChange;
import com.xploits.pvp.recorder.core.FightRecord.Opponent;
import com.xploits.pvp.recorder.core.FightRecord.PhaseChange;
import com.xploits.pvp.recorder.core.FightRecord.ProfileChange;
import com.xploits.pvp.recorder.core.FightRecord.SelfTotals;
import com.xploits.shared.core.i18n.Msg;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Turns a finished {@link FightRecord} into chat lines. Pure text: no console, no command wiring, no
 * knowledge of how the lines reach the player. {@link #review} is the full breakdown for
 * {@code .xploits pvp review}, {@link #listLine} one row of {@code .xploits pvp fights}, and
 * {@link #deathNotice} the single line shown on death.
 *
 * <p>Every line is one {@link Msg}, short enough for chat, with "s" for seconds and "·" between parts;
 * none of them ever carries a position (design's no-coordinates rule).
 */
public final class FightSummary {
    /** How many of the most recent phase changes are shown in a review; older ones are summed up in one line. */
    public static final int PHASES_SHOWN = 8;

    private FightSummary() {
    }

    /**
     * The full review, numbered as the player asked for it: header, your totals, the damage split, your
     * offense, the modules you started with, the auto-pvp phases, and (only for a {@link FightOutcome#LOST}
     * fight) the probable causes, strongest first.
     */
    public static List<Msg> review(FightRecord f, int number) {
        List<Msg> lines = new ArrayList<>();
        lines.add(Msg.of(RecorderText.SUMMARY_HEADER, "number", number, "outcome", f.outcome().label(),
            "seconds", f.durationSeconds(), "opponents", opponents(f), "mode", f.mode().label()));

        SelfTotals self = f.self();
        lines.add(Msg.of(RecorderText.SUMMARY_SELF, "damageTaken", self.damageTaken(), "pops", self.pops(),
            "totemsStart", self.totemsStart(), "totemsEnd", self.totemsEnd()));

        addDamageSplit(f, lines);
        addOffense(f, lines);

        lines.add(Msg.of(RecorderText.SUMMARY_MODULES, "modules", joinOrNothing(f.modulesAtStart())));

        addProfile(f, lines);
        addChanges(f, lines);
        addPhases(f, lines);
        addCauses(f, lines);
        return List.copyOf(lines);
    }

    /** One row of the fight list: the same header as {@link #review}, plus how long ago it happened. */
    public static Msg listLine(int number, FightRecord f, Object ago) {
        return Msg.of(RecorderText.LIST_LINE, "number", number, "outcome", f.outcome().label(),
            "seconds", f.durationSeconds(), "opponents", opponents(f), "mode", f.mode().label(), "ago", ago);
    }

    /** The single line shown on death: how long, against whom, and the main probable cause. */
    public static Msg deathNotice(FightRecord f) {
        return Msg.of(RecorderText.DEATH_NOTICE, "seconds", f.durationSeconds(), "opponents", opponents(f),
            "cause", FightAnalysis.mainCause(f));
    }

    /** The opponents as "Foo, Bar", or {@link RecorderText#NOBODY} when the fight had none. */
    public static Msg opponents(FightRecord f) {
        Object head = null;
        for (Opponent o : f.opponents()) {
            head = head == null ? o.name() : Msg.of(RecorderText.JOIN_COMMA, "first", head, "rest", o.name());
        }
        if (head == null) return Msg.of(RecorderText.NOBODY);
        if (head instanceof Msg m) return m;
        return Msg.of(RecorderText.CONCAT, "first", head, "second", RecorderText.NOTHING);
    }

    private static void addDamageSplit(FightRecord f, List<Msg> lines) {
        Map<DamageKind, Double> byKind = FightAnalysis.damageByKind(f);
        double unseen = byKind.getOrDefault(DamageKind.UNSEEN, 0.0);
        List<Map.Entry<DamageKind, Double>> known = new ArrayList<>();
        for (Map.Entry<DamageKind, Double> e : byKind.entrySet()) {
            if (e.getKey() != DamageKind.UNSEEN) known.add(e);
        }
        if (known.isEmpty() && unseen <= 0) return;

        known.sort((a, b) -> {
            int cmp = Double.compare(b.getValue(), a.getValue());
            return cmp != 0 ? cmp : a.getKey().compareTo(b.getKey());
        });
        lines.add(Msg.of(RecorderText.SUMMARY_DAMAGE));
        for (Map.Entry<DamageKind, Double> e : known) {
            lines.add(Msg.of(RecorderText.SUMMARY_DAMAGE_PART, "kind", e.getKey().label(),
                "percent", percent(FightAnalysis.share(f, e.getKey()))));
        }
        if (unseen > 0) lines.add(Msg.of(RecorderText.SUMMARY_UNSEEN, "amount", unseen));
    }

    private static void addOffense(FightRecord f, List<Msg> lines) {
        SelfTotals self = f.self();
        int hits = 0;
        for (Opponent o : f.opponents()) hits += o.hitsByYou();
        lines.add(Msg.of(RecorderText.SUMMARY_OFFENSE, "attacks", self.attacks(), "placed", self.crystalsPlaced(),
            "broken", self.crystalsBroken(), "hits", hits));
    }

    /** The profile active when the fight opened; nothing for a fight recorded before profiles existed. */
    private static void addProfile(FightRecord f, List<Msg> lines) {
        if (f.profile() == null) return;
        lines.add(Msg.of(RecorderText.SUMMARY_PROFILE, "name", f.profile()));
    }

    /**
     * Module toggles and profile switches, merged into one list and shown in the order they happened (not
     * two separate sections): a drop right after a switch then reads next to it, with no inference rule
     * needed. Ties at the same second keep module changes before profile changes, the order they are read
     * from the record. Only the last {@link #PHASES_SHOWN} merged entries are shown, the same trim
     * {@link #addPhases} does: unbounded, this could flood the chat (each list is capped at
     * {@code FightTracker.MAX_CHANGES} on its own, but the merge of both is not).
     */
    private static void addChanges(FightRecord f, List<Msg> lines) {
        List<ModuleChange> moduleChanges = f.moduleChanges();
        List<ProfileChange> profileChanges = f.profileChanges();
        if (moduleChanges.isEmpty() && profileChanges.isEmpty()) return;

        record Entry(int second, int order, Msg line) {
        }
        List<Entry> entries = new ArrayList<>();
        int order = 0;
        for (ModuleChange c : moduleChanges) {
            entries.add(new Entry(c.second(), order++, Msg.of(RecorderText.SUMMARY_CHANGE_MODULE, "second", c.second(),
                "module", c.module(), "state", c.on() ? RecorderText.ON : RecorderText.OFF)));
        }
        for (ProfileChange c : profileChanges) {
            entries.add(new Entry(c.second(), order++, Msg.of(RecorderText.SUMMARY_CHANGE_PROFILE, "second", c.second(),
                "name", c.name())));
        }
        entries.sort(Comparator.comparingInt(Entry::second).thenComparingInt(Entry::order));

        lines.add(Msg.of(RecorderText.SUMMARY_CHANGES));
        int earlier = Math.max(0, entries.size() - PHASES_SHOWN);
        if (earlier > 0) lines.add(Msg.of(RecorderText.SUMMARY_CHANGES_EARLIER, "count", earlier));
        for (Entry e : entries.subList(earlier, entries.size())) lines.add(e.line());
    }

    private static void addPhases(FightRecord f, List<Msg> lines) {
        List<PhaseChange> phases = f.phases();
        if (phases.isEmpty()) return;
        lines.add(Msg.of(RecorderText.SUMMARY_PHASES));
        int earlier = Math.max(0, phases.size() - PHASES_SHOWN);
        if (earlier > 0) lines.add(Msg.of(RecorderText.SUMMARY_PHASES_EARLIER, "count", earlier));
        for (PhaseChange c : phases.subList(earlier, phases.size())) {
            lines.add(Msg.of(RecorderText.SUMMARY_PHASE_PART, "second", c.second(), "state", PvpText.of(c.state()),
                "posture", PvpText.of(c.posture()), "target", c.target() == null ? RecorderText.NOBODY : c.target()));
        }
    }

    private static void addCauses(FightRecord f, List<Msg> lines) {
        if (f.outcome() != FightOutcome.LOST) return;
        List<Cause> causes = FightAnalysis.causes(f);
        lines.add(Msg.of(RecorderText.SUMMARY_CAUSES));
        if (causes.isEmpty()) {
            lines.add(Msg.of(RecorderText.SUMMARY_NO_CAUSES));
            return;
        }
        int rank = 1;
        for (Cause c : causes) {
            lines.add(Msg.of(RecorderText.SUMMARY_CAUSE_LINE, "rank", rank++, "cause", c.evidence()));
        }
    }

    /** {@code names} joined with commas, or {@link RecorderText#NOTHING} when there are none. */
    private static Object joinOrNothing(List<String> names) {
        Object head = null;
        for (String name : names) head = head == null ? name : Msg.of(RecorderText.JOIN_COMMA, "first", head, "rest", name);
        return head == null ? RecorderText.NOTHING : head;
    }

    private static int percent(double share) {
        return (int) Math.round(share * 100);
    }
}
