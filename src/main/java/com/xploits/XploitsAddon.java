package com.xploits;

import com.mojang.logging.LogUtils;
import com.xploits.autotpy.AutoTpy;
import com.xploits.commands.XploitsCommand;
import com.xploits.elytra.ElytraReplace;
import com.xploits.kitrequester.KitRequester;
import com.xploits.pvp.AutoPvp;
import com.xploits.stash.StashKeeper;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class XploitsAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Xploits");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Xploits");
        Modules.get().add(new KitRequester());
        Modules.get().add(new AutoTpy());
        Modules.get().add(new StashKeeper());
        Modules.get().add(new ElytraReplace());
        Modules.get().add(new AutoPvp());
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
