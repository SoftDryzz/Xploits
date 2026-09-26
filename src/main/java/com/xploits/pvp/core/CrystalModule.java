package com.xploits.pvp.core;

import com.xploits.pvp.profile.core.ProfileText;
import com.xploits.shared.core.PositionedMsg;
import com.xploits.shared.core.i18n.MessageKey;
import com.xploits.shared.core.i18n.Msg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which crystal aura auto-pvp drives (crystal-aura++ spec §3.1, P5): Meteor's {@code crystal-aura}, the
 * default and 0.6.2's behaviour, or Xploits' {@code crystal-aura++}.
 *
 * <p>Everything below auto-pvp -the director, the ledger, the watch, the profiles- keys on the logical
 * catalog name {@link #LOGICAL}; only the adapter turns it into the real module, through
 * {@link #resolve}. That keeps {@code profiles.json}, {@link ManagedModules} and {@link ModuleLedger}
 * unchanged.
 *
 * <p>{@link #toString()} is what Meteor saves for an enum setting and what a player types, so it is
 * fixed here and never derived from the constant's name.
 */
public enum CrystalModule {
    METEOR("meteor", "crystal-aura", PvpText.SUSPECTS_CRYSTAL_AURA),
    XPLOITS("xploits++", "crystal-aura++", PvpText.SUSPECTS_CRYSTAL_AURA_PP);

    /** The catalog's name for "the crystal aura", whichever of the two it is. */
    public static final String LOGICAL = "crystal-aura";

    /**
     * The messages whose text names the aura through a {@code {module}} placeholder and that are built
     * where the selection is not known (the director, the profile book): {@link #name(Msg)} fills it in.
     */
    private static final Set<MessageKey> NAMING = Set.of(
        PvpText.TOTEM_FLOOR, PvpText.AURA_NO_CRYSTALS, ProfileText.PROFILE_NO_AUTOBREAK);

    private final String saved;
    private final String moduleName;
    private final PvpText suspects;

    CrystalModule(String saved, String moduleName, PvpText suspects) {
        this.saved = saved;
        this.moduleName = moduleName;
        this.suspects = suspects;
    }

    /** The Meteor module name of this aura. */
    public String moduleName() {
        return moduleName;
    }

    /** The aura that is not this one. */
    public CrystalModule other() {
        return this == METEOR ? XPLOITS : METEOR;
    }

    /** The real module behind a managed module's catalog name: only {@link #LOGICAL} changes. */
    public String resolve(String managedName) {
        return LOGICAL.equals(managedName) ? moduleName : managedName;
    }

    /** Where to look when this aura is on and places nothing (the "not spending" warning). */
    public PvpText suspects() {
        return suspects;
    }

    /**
     * The message with this aura's name filled into every part of it -nested ones included- whose text
     * names the aura and was built without it. A name already given is kept.
     */
    public Msg name(Msg msg) {
        boolean changed = false;
        Map<String, Object> args = new LinkedHashMap<>();
        for (Map.Entry<String, Object> arg : msg.args().entrySet()) {
            Object value = arg.getValue() instanceof Msg nested ? name(nested) : arg.getValue();
            changed |= value != arg.getValue();
            args.put(arg.getKey(), value);
        }
        if (NAMING.contains(msg.key()) && !args.containsKey("module")) {
            args.put("module", moduleName);
            changed = true;
        }
        return changed ? new Msg(msg.key(), args) : msg;
    }

    /** {@link #name(Msg)} on both halves. */
    public PositionedMsg name(PositionedMsg msg) {
        return new PositionedMsg(name(msg.chat()), name(msg.log()));
    }

    /** The plan with its warnings and skip reasons named; the modules it enables keep their catalog names. */
    public Plan name(Plan plan) {
        List<Skipped> skipped = new ArrayList<>();
        for (Skipped s : plan.skipped()) skipped.add(new Skipped(s.module(), name(s.reason())));
        List<Msg> warnings = new ArrayList<>();
        for (Msg warning : plan.warnings()) warnings.add(name(warning));
        return new Plan(plan.state(), plan.posture(), plan.enable(), skipped, warnings);
    }

    @Override
    public String toString() {
        return saved;
    }
}
