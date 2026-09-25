package com.xploits.pvp.hud;

import com.xploits.pvp.AutoPvp;
import com.xploits.pvp.hud.core.PanelInput;
import com.xploits.pvp.hud.core.PvpScriptValues;
import com.xploits.shared.Texts;
import com.xploits.shared.XploitsStarscript;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.meteordev.starscript.value.Value;
import org.meteordev.starscript.value.ValueMap;

/**
 * Registers {@code {xploits.pvp.*}} (design spec §3 "Starscript extra", "Precise rules" -&gt;
 * Starscript): nested maps {@code xploits} -&gt; {@code pvp} -&gt; suppliers {@code state}, {@code
 * posture}, {@code profile}, {@code target}, {@code distance}, backed by {@link PvpScriptValues}. Each
 * supplier is lazy, so it re-reads {@link AutoPvp} and the active language on every Starscript run —
 * exactly the same facts the panel shows, never a position.
 *
 * <p>The {@code pvp} map is added to the shared {@code xploits} root through {@link XploitsStarscript}
 * rather than calling {@code MeteorStarscript.ss.set("xploits", ...)} directly, which would replace the
 * whole namespace and erase any other subsystem's {@code xploits.*} values (see that class's javadoc).
 */
public final class PvpStarscript {
    private PvpStarscript() {
    }

    /** Called once from {@code XploitsAddon.onInitialize}, after the modules are added. */
    public static void register() {
        ValueMap pvp = new ValueMap()
            .set("state", () -> Value.string(values().state()))
            .set("posture", () -> Value.string(values().posture()))
            .set("profile", () -> Value.string(values().profile()))
            .set("target", () -> Value.string(values().target()))
            .set("distance", () -> Value.string(values().distance()));
        XploitsStarscript.addChild("pvp", pvp);
    }

    /** {@code in} is {@code null} for "no world" (Precise rules -&gt; Starscript), matching {@link PvpScriptValues#of}. */
    private static PvpScriptValues values() {
        AutoPvp autoPvp = Modules.get().get(AutoPvp.class);
        String activeProfile = autoPvp == null ? "" : autoPvp.activeProfile().name();
        PanelInput in = (autoPvp == null || MeteorClient.mc.world == null) ? null : autoPvp.panelInput(false);
        return PvpScriptValues.of(in, activeProfile, Texts.catalog(Texts.current()));
    }
}
