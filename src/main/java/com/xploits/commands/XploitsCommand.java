package com.xploits.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.xploits.kitrequester.KitRequester;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;

public class XploitsCommand extends Command {
    public XploitsCommand() {
        super("xploits", "Estado y recarga de KitRequester.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("status").executes(context -> {
            info("%s", module().status());
            return SINGLE_SUCCESS;
        }));
        builder.then(literal("reload").executes(context -> {
            info("%s", module().reload());
            return SINGLE_SUCCESS;
        }));
    }

    private static KitRequester module() {
        return Modules.get().get(KitRequester.class);
    }
}
