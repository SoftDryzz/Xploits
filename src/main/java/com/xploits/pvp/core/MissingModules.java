package com.xploits.pvp.core;

import com.xploits.shared.core.i18n.Msg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The managed modules this Meteor build does not register, measured once per activation. Meteor
 * 1.21.11 has the {@code AntiAnchor} class but does not add it, so asking for it by name gives nothing.
 *
 * <p>Pure: the adapter passes how Meteor answers "is there a module with this name?", and each
 * measurement is one activation. It carries the three uses of that answer: the set the director skips
 * first ({@link CombatDirector}), the names the status lists, and the chat warning, said <b>once</b> and
 * only <b>with a world</b>. auto-pvp restored active at game start is turned on before there is a world,
 * and a line said then never reaches the player's chat.
 */
public final class MissingModules {
    private final Set<ManagedModule> modules;
    private final List<String> names;
    private boolean warned;

    private MissingModules(Set<ManagedModule> modules) {
        this.modules = Collections.unmodifiableSet(modules);
        List<String> list = new ArrayList<>();
        for (ManagedModule module : modules) list.add(module.name());
        this.names = List.copyOf(list);
    }

    /**
     * One activation's measurement.
     *
     * @param registered whether Meteor has a module with that name
     */
    public static MissingModules measure(Predicate<String> registered) {
        Set<ManagedModule> missing = new LinkedHashSet<>();
        for (ManagedModule module : ManagedModules.ALL) {
            if (!registered.test(module.name())) missing.add(module);
        }
        return new MissingModules(missing);
    }

    /** The missing modules, in catalog order: what the director skips before anything else. */
    public Set<ManagedModule> modules() {
        return modules;
    }

    /** Their names, in catalog order: what {@code .xploits pvp} lists. */
    public List<String> names() {
        return names;
    }

    /**
     * The chat warning, the first time it is asked for with a world and never again in this
     * activation; empty while there is no world, or when nothing is missing.
     */
    public Optional<Msg> warning(boolean inWorld) {
        if (warned || !inWorld || names.isEmpty()) return Optional.empty();
        warned = true;
        return Optional.of(PvpStatus.missingWarning(names));
    }
}
