package com.xploits.shared;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@code toTree}/{@code toNbt} are package-private, so this lives next to {@link SettingsMigration} rather
 * than under {@code core/migration} with the rest of the migration tests, and — unlike those — is allowed
 * to touch real {@code net.minecraft.nbt} types directly to prove the conversion round-trips real NBT.
 */
class SettingsMigrationNbtTest {
    @Test
    void aCompoundWithAStringListAndAnIntRoundTripsThroughTheTree() {
        NbtCompound root = new NbtCompound();
        root.put("name", NbtString.of("consola"));
        NbtList items = new NbtList();
        items.add(NbtString.of("a"));
        items.add(NbtString.of("b"));
        root.put("items", items);
        NbtInt count = NbtInt.of(3);
        root.put("count", count);

        Object tree = SettingsMigration.toTree(root);
        NbtCompound back = (NbtCompound) SettingsMigration.toNbt(tree);

        assertEquals("consola", ((NbtString) back.get("name")).value());
        List<String> backItems = ((NbtList) back.get("items")).stream().map(x -> ((NbtString) x).value()).toList();
        assertEquals(List.of("a", "b"), backItems);
        assertSame(count, back.get("count"), "an opaque element (anything but a compound, list or string) passes through unchanged");
    }
}
