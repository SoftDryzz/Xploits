package com.xploits;

import com.mojang.logging.LogUtils;
import com.xploits.autotpy.AutoTpy;
import com.xploits.commands.XploitsCommand;
import com.xploits.console.ConsoleModule;
import com.xploits.elytra.ElytraReplace;
import com.xploits.kitrequester.KitRequester;
import com.xploits.pvp.AutoPvp;
import com.xploits.pvp.crystal.CrystalAuraPlusPlus;
import com.xploits.pvp.shell.SurroundPlusPlus;
import com.xploits.pvp.hud.AutoPvpHud;
import com.xploits.pvp.hud.PvpStarscript;
import com.xploits.pvp.recorder.FightRecorder;
import com.xploits.restock.JoinWatch;
import com.xploits.restock.Marks;
import com.xploits.restock.PacketWatch;
import com.xploits.restock.Restock;
import com.xploits.stash.StashKeeper;
import com.xploits.shared.Languages;
import com.xploits.shared.SettingsMigration;
import com.xploits.shared.XploitsSettings;
import com.xploits.sweep.NetherSweep;
import com.xploits.travel.AutoTravel;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class XploitsAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Xploits");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Xploits");
        SettingsMigration.run();
        Languages.start();
        // restock's always-on listeners (restock spec §3, §4): the packet watch must know the rotation the server holds
        // and the input it last heard before restock is ever turned on; the join watch gives back what an interrupted
        // session left (Baritone's values, litematica-printer's print mode) whichever module is on; the mark key works
        // with restock off. Meteor registers the addon's lambda factory before this runs.
        PacketWatch.start();
        JoinWatch.start();
        Marks.start();
        Modules.get().add(new XploitsSettings());
        Modules.get().add(new KitRequester());
        Modules.get().add(new AutoTpy());
        Modules.get().add(new StashKeeper());
        Modules.get().add(new ElytraReplace());
        Modules.get().add(new AutoPvp());
        Modules.get().add(new CrystalAuraPlusPlus());
        Modules.get().add(new SurroundPlusPlus());
        Modules.get().add(new FightRecorder());
        Modules.get().add(new AutoTravel());
        Modules.get().add(new NetherSweep());
        Modules.get().add(new Restock());
        Modules.get().add(new ConsoleModule());
        Hud.get().register(AutoPvpHud.INFO);
        PvpStarscript.register();
        Commands.add(new XploitsCommand());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.xploits";
    }
}
