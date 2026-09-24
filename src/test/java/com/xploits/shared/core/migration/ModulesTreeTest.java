package com.xploits.shared.core.migration;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModulesTreeTest {
    private static final SettingsRenames.Table T = SettingsRenames.V0_4_0;
    private static final Object OPAQUE = new Object();

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Object> setting(String name, Object value) {
        return map("name", name, "value", value);
    }

    @SafeVarargs
    private static Map<String, Object> group(String name, Map<String, Object>... settings) {
        return map("name", name, "sectionExpanded", OPAQUE, "settings", new ArrayList<>(List.of(settings)));
    }

    @SafeVarargs
    private static Map<String, Object> module(String name, Map<String, Object>... groups) {
        return map("name", name, "keybind", OPAQUE, "active", OPAQUE,
            "settings", map("groups", new ArrayList<>(List.of(groups))));
    }

    @SafeVarargs
    private static Map<String, Object> root(Map<String, Object>... modules) {
        return map("modules", new ArrayList<>(List.of(modules)));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> modules(Object tree) {
        return (List<Map<String, Object>>) ((Map<String, Object>) tree).get("modules");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> groups(Map<String, Object> module) {
        return (List<Map<String, Object>>) ((Map<String, Object>) module.get("settings")).get("groups");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> settings(Map<String, Object> group) {
        return (List<Map<String, Object>>) group.get("settings");
    }

    @Test
    void renamesTheConsoleModuleAndKeepsEverythingElse() {
        ModulesTree.Result r = ModulesTree.migrate(root(module("consola")), T);
        assertTrue(r.changed());
        Map<String, Object> m = modules(r.tree()).get(0);
        assertEquals("console", m.get("name"));
        assertSame(OPAQUE, m.get("keybind"));
        assertSame(OPAQUE, m.get("active"));
    }

    @Test
    void renamesGroupsSettingsAndValues() {
        Object tree = root(module("auto-travel",
            group("Patrón", setting("pattern", "ESPIRAL"), setting("quiebro-leg", OPAQUE)),
            group("General", setting("destination-mode", "RELATIVO"))));
        Map<String, Object> m = modules(ModulesTree.migrate(tree, T).tree()).get(0);
        Map<String, Object> pattern = groups(m).get(0);
        assertEquals("Pattern", pattern.get("name"));
        assertEquals("SPIRAL", settings(pattern).get(0).get("value"));
        assertEquals("swerve-leg", settings(pattern).get(1).get("name"));
        assertEquals("RELATIVE", settings(groups(m).get(1)).get(0).get("value"));
    }

    @Test
    void groupRenamedValueAlreadyEnglish() {
        Object tree = root(module("auto-travel", group("Patrón", setting("pattern", "ZIGZAG"))));
        ModulesTree.Result r = ModulesTree.migrate(tree, T);
        assertTrue(r.changed());
        Map<String, Object> g = groups(modules(r.tree()).get(0)).get(0);
        assertEquals("Pattern", g.get("name"));
        assertEquals("ZIGZAG", settings(g).get(0).get("value"));
    }

    @Test
    void sweepGroupsAreRenamed() {
        Object tree = root(module("nether-sweep", group("Pasada"), group("Cohetes"), group("Vuelo"), group("Avisos")));
        List<Map<String, Object>> gs = groups(modules(ModulesTree.migrate(tree, T).tree()).get(0));
        assertEquals(List.of("Lane", "Fireworks", "Flight", "Notify"), gs.stream().map(g -> g.get("name")).toList());
    }

    @Test
    void valuesMatchIgnoringCase() {
        Object tree = root(module("auto-travel", group("Patrón", setting("pattern", "recto"))));
        Map<String, Object> g = groups(modules(ModulesTree.migrate(tree, T).tree()).get(0)).get(0);
        assertEquals("STRAIGHT", settings(g).get(0).get("value"));
    }

    @Test
    void aGroupOfAnotherModuleIsLeftAlone() {
        Object tree = root(module("kill-aura", group("Vuelo", setting("pattern", "RECTO"))));
        ModulesTree.Result r = ModulesTree.migrate(tree, T);
        assertFalse(r.changed());
        assertSame(tree, r.tree());
    }

    @Test
    void newNameWinsWhenBothExist() {
        Map<String, Object> fresh = module("console");
        Object tree = root(module("consola"), fresh);
        List<Map<String, Object>> ms = modules(ModulesTree.migrate(tree, T).tree());
        assertEquals(1, ms.size());
        assertEquals(fresh, ms.get(0));
    }

    @Test
    void migratingTwiceChangesNothingTheSecondTime() {
        Object once = ModulesTree.migrate(root(module("consola"),
            module("auto-travel", group("Patrón", setting("pattern", "SENUELO")))), T).tree();
        ModulesTree.Result twice = ModulesTree.migrate(once, T);
        assertFalse(twice.changed());
        assertSame(once, twice.tree());
    }

    @Test
    void theInputIsNeverMutated() {
        Map<String, Object> m = module("consola");
        Object tree = root(m);
        ModulesTree.migrate(tree, T);
        assertEquals("consola", m.get("name"));
    }

    @Test
    void aTreeWithoutModulesIsUntouched() {
        Object tree = map("other", "x");
        ModulesTree.Result r = ModulesTree.migrate(tree, T);
        assertFalse(r.changed());
        assertSame(tree, r.tree());
    }

    @Test
    void hudStringsAreRenamedOnlyOnExactMatch() {
        Object tree = map("elements", List.of(map("hidden", List.of("consola", "consola-extra", "kill-aura"))));
        ModulesTree.Result r = ModulesTree.renameStrings(tree, T.modules());
        assertTrue(r.changed());
        assertEquals(map("elements", List.of(map("hidden", List.of("console", "consola-extra", "kill-aura")))), r.tree());
        assertFalse(ModulesTree.renameStrings(r.tree(), T.modules()).changed());
    }

    /**
     * Module names are also saved as plain strings elsewhere, inside another module's own setting list
     * (CrystalAura's {@code pause-modules}, Surround's {@code modules}). {@link ModulesTree#migrate} alone
     * does not reach into an arbitrary setting's value list, so {@code SettingsMigration} runs
     * {@link ModulesTree#renameStrings} on the result too; these two tests pin that combination down.
     */
    @Test
    void aModuleNameInsideAnotherModulesSettingListIsRenamedWhenBothStepsRun() {
        Object tree = root(module("kill-aura", group("General", setting("pause-modules", List.of("consola", "kill-aura")))));
        ModulesTree.Result structured = ModulesTree.migrate(tree, T);
        ModulesTree.Result strings = ModulesTree.renameStrings(structured.tree(), T.modules());
        assertTrue(strings.changed());
        Map<String, Object> g = groups(modules(strings.tree()).get(0)).get(0);
        assertEquals(List.of("console", "kill-aura"), settings(g).get(0).get("value"));
    }

    @Test
    void anUnrelatedStringInASettingListStaysUntouched() {
        Object tree = root(module("kill-aura", group("General", setting("pause-modules", List.of("surround", "kill-aura")))));
        ModulesTree.Result structured = ModulesTree.migrate(tree, T);
        ModulesTree.Result strings = ModulesTree.renameStrings(structured.tree(), T.modules());
        assertFalse(strings.changed());
        Map<String, Object> g = groups(modules(strings.tree()).get(0)).get(0);
        assertEquals(List.of("surround", "kill-aura"), settings(g).get(0).get("value"));
    }
}
