package com.xploits.shared.core.migration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies {@link SettingsRenames.Table} to a plain tree of Meteor's {@code modules.nbt}: compounds are
 * {@code Map<String,Object>}, lists {@code List<Object>}, strings {@code String}; anything else is opaque
 * and kept as is. Inputs are never mutated; an unchanged tree is returned as the same object.
 *
 * <p>When an old and a new name end up side by side in one list (a file saved by 0.4.0 and then by an
 * older version), the entry that already had the new name wins and the renamed one is dropped.
 */
public final class ModulesTree {
    private ModulesTree() {
    }

    public record Result(Object tree, boolean changed) {
    }

    private interface Inner {
        Map<String, Object> apply(String newName, Map<String, Object> entry);
    }

    private static final class Changes {
        boolean any;
    }

    public static Result migrate(Object root, SettingsRenames.Table table) {
        if (!(root instanceof Map<?, ?> rootMap) || !(rootMap.get("modules") instanceof List<?> modules)) {
            return new Result(root, false);
        }
        Changes changes = new Changes();
        List<Object> renamed = renameEntries(modules, table.modules(), changes,
            (module, entry) -> migrateModule(module, entry, table, changes));
        if (!changes.any) return new Result(root, false);
        Map<String, Object> copy = copyOf(rootMap);
        copy.put("modules", renamed);
        return new Result(copy, true);
    }

    public static Result renameStrings(Object root, Map<String, String> exact) {
        Changes changes = new Changes();
        Object out = renameStrings(root, exact, changes);
        return changes.any ? new Result(out, true) : new Result(root, false);
    }

    private static Object renameStrings(Object node, Map<String, String> exact, Changes changes) {
        if (node instanceof String s && exact.containsKey(s)) {
            changes.any = true;
            return exact.get(s);
        }
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put((String) k, renameStrings(v, exact, changes)));
            return out;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object v : list) out.add(renameStrings(v, exact, changes));
            return out;
        }
        return node;
    }

    private static Map<String, Object> migrateModule(String module, Map<String, Object> entry,
                                                     SettingsRenames.Table table, Changes changes) {
        if (!(entry.get("settings") instanceof Map<?, ?> settings) || !(settings.get("groups") instanceof List<?> groups)) {
            return entry;
        }
        List<Object> renamed = renameEntries(groups, table.groups().getOrDefault(module, Map.of()), changes,
            (group, g) -> migrateGroup(module, group, g, table, changes));
        Map<String, Object> settingsCopy = copyOf(settings);
        settingsCopy.put("groups", renamed);
        Map<String, Object> copy = new LinkedHashMap<>(entry);
        copy.put("settings", settingsCopy);
        return copy;
    }

    private static Map<String, Object> migrateGroup(String module, String group, Map<String, Object> entry,
                                                    SettingsRenames.Table table, Changes changes) {
        if (!(entry.get("settings") instanceof List<?> settings)) return entry;
        List<Object> renamed = renameEntries(settings, table.settings().getOrDefault(module + "/" + group, Map.of()), changes,
            (setting, s) -> migrateValue(table.values().get(module + "/" + group + "/" + setting), s, changes));
        Map<String, Object> copy = new LinkedHashMap<>(entry);
        copy.put("settings", renamed);
        return copy;
    }

    private static Map<String, Object> migrateValue(Map<String, String> values, Map<String, Object> entry, Changes changes) {
        if (values == null || !(entry.get("value") instanceof String value)) return entry;
        for (Map.Entry<String, String> v : values.entrySet()) {
            if (v.getKey().equalsIgnoreCase(value)) {
                Map<String, Object> copy = new LinkedHashMap<>(entry);
                copy.put("value", v.getValue());
                changes.any = true;
                return copy;
            }
        }
        return entry;
    }

    /** Renames the {@code name} of each compound in a list, drops renamed duplicates, recurses with {@code inner}. */
    private static List<Object> renameEntries(List<?> list, Map<String, String> renames, Changes changes, Inner inner) {
        Set<String> alreadyNew = new HashSet<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m && m.get("name") instanceof String n && !renames.containsKey(n)) alreadyNew.add(n);
        }
        List<Object> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m) || !(m.get("name") instanceof String name)) {
                out.add(o);
                continue;
            }
            Map<String, Object> entry = copyOf(m);
            String newName = renames.getOrDefault(name, name);
            if (!newName.equals(name)) {
                changes.any = true;
                if (alreadyNew.contains(newName)) continue;
                entry.put("name", newName);
            }
            out.add(inner.apply(newName, entry));
        }
        return out;
    }

    private static Map<String, Object> copyOf(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put((String) k, v));
        return out;
    }
}
