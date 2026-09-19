package com.xploits.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.xploits.kitrequester.KitRequester;
import com.xploits.stash.StashKeeper;
import com.xploits.stash.core.StashIndex;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class XploitsCommand extends Command {
    private static final int MAX_HITS = 10;

    public XploitsCommand() {
        super("xploits", "Estado del addon, búsqueda en el stash y recarga.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("status").executes(context -> {
            info("%s", kitRequester().status());
            return SINGLE_SUCCESS;
        }));
        builder.then(literal("reload").executes(context -> {
            info("%s", kitRequester().reload());
            return SINGLE_SUCCESS;
        }));
        builder.then(literal("stash").executes(context -> {
            info("%s", keeper().status());
            return SINGLE_SUCCESS;
        }));
        builder.then(literal("find").then(argument("item", StringArgumentType.greedyString()).executes(context -> {
            find(StringArgumentType.getString(context, "item"));
            return SINGLE_SUCCESS;
        })));
    }

    private void find(String query) {
        Set<String> ids = resolve(query);
        if (ids.isEmpty()) {
            warning("No conozco ningún ítem que se parezca a \"%s\".", query);
            return;
        }

        List<StashIndex.Hit> hits = keeper().index().find(ids);
        if (hits.isEmpty()) {
            warning("No he visto \"%s\" en ningún contenedor. Recuerda que un cofre solo entra en el índice cuando lo abres.", query);
            return;
        }

        info("%d sitios con \"%s\":", hits.size(), query);
        for (StashIndex.Hit hit : hits.subList(0, Math.min(MAX_HITS, hits.size()))) {
            String where = hit.insideShulker() == null ? "" : " · en shulker \"" + hit.insideShulker() + "\"";
            info("  %s x%d · %s%s · visto %s",
                shortId(hit.itemId()), hit.count(), hit.key().id(), where, ago(hit.seenAt()));
        }
        if (hits.size() > MAX_HITS) info("  ...y %d más.", hits.size() - MAX_HITS);
    }

    /** Casa la consulta contra el id del ítem y contra su nombre traducido, para que "obsidiana" funcione. */
    private static Set<String> resolve(String query) {
        String needle = query.toLowerCase().strip();
        Set<String> ids = new LinkedHashSet<>();
        for (Item item : Registries.ITEM) {
            String id = Registries.ITEM.getId(item).toString();
            String name = item.getName().getString().toLowerCase();
            if (id.toLowerCase().contains(needle) || name.contains(needle)) ids.add(id);
        }
        return ids;
    }

    private static String shortId(String itemId) {
        return itemId.startsWith("minecraft:") ? itemId.substring("minecraft:".length()) : itemId;
    }

    private static String ago(long seenAt) {
        Duration d = Duration.ofMillis(Math.max(0, System.currentTimeMillis() - seenAt));
        if (d.toMinutes() < 1) return "hace un momento";
        if (d.toHours() < 1) return "hace " + d.toMinutes() + " min";
        if (d.toDays() < 1) return "hace " + d.toHours() + " h";
        return "hace " + d.toDays() + " días";
    }

    private static KitRequester kitRequester() {
        return Modules.get().get(KitRequester.class);
    }

    private static StashKeeper keeper() {
        return Modules.get().get(StashKeeper.class);
    }
}
