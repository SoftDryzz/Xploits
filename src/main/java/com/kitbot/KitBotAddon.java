package com.kitbot;

import com.kitbot.commands.KitBotCommand;
import com.kitbot.modules.KitRequester;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class KitBotAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("KitBot");

    @Override
    public void onInitialize() {
        LOG.info("Initializing KitBot");
        Modules.get().add(new KitRequester());
        Commands.add(new KitBotCommand());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.kitbot";
    }
}
