package com.xploits.i18n;

import com.xploits.console.core.CoordinateSentinel;
import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.MessageKey;
import com.xploits.shared.core.i18n.Msg;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Both catalogs against every key enum in the sources (spec §6, "Tests on the catalogs"). */
class CatalogParityTest {
    private static final Path SOURCES = Path.of("src", "main", "java");
    private static final Pattern KEY_ENUM = Pattern.compile("\\benum\\s+(\\w+)\\s+implements\\s+MessageKey\\b");
    private static final List<String> PROBLEMS = new ArrayList<>();
    private static final Catalog ES = Catalog.load(Language.ES, PROBLEMS::add);
    private static final Catalog EN = Catalog.load(Language.EN, PROBLEMS::add);

    static List<MessageKey> allKeys() throws IOException, ClassNotFoundException {
        List<MessageKey> keys = new ArrayList<>();
        try (Stream<Path> s = Files.walk(SOURCES)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = KEY_ENUM.matcher(Files.readString(p, StandardCharsets.UTF_8));
                if (!m.find()) continue;
                String pkg = SOURCES.relativize(p.getParent()).toString().replace('\\', '.').replace('/', '.');
                Class<?> type = Class.forName(pkg + "." + m.group(1));
                for (Object k : type.getEnumConstants()) keys.add((MessageKey) k);
            }
        }
        return keys;
    }

    private static Set<String> ids() throws Exception {
        Set<String> ids = new TreeSet<>();
        for (MessageKey k : allKeys()) ids.add(k.id());
        return ids;
    }

    @Test
    void bothCatalogsHoldExactlyTheKeysInTheCode() throws Exception {
        assertEquals(ids(), new TreeSet<>(ES.ids()), "es.lang");
        assertEquals(ids(), new TreeSet<>(EN.ids()), "en.lang");
    }

    @Test
    void placeholdersMatchAcrossLanguages() throws Exception {
        List<String> different = new ArrayList<>();
        for (String id : ids()) {
            if (ES.raw(id) == null || EN.raw(id) == null) continue;
            if (!Catalog.placeholders(ES.raw(id)).equals(Catalog.placeholders(EN.raw(id)))) different.add(id);
        }
        assertEquals(List.of(), different);
    }

    @Test
    void everyMessageRendersAndCoordinatesAreHeldInBothLanguagesOrNeither() throws Exception {
        List<String> mismatched = new ArrayList<>();
        for (MessageKey k : allKeys()) {
            String template = ES.raw(k.id());
            if (template == null) continue;
            List<Object> args = new ArrayList<>();
            for (String name : Catalog.placeholders(template)) {
                args.add(name);
                args.add(1234);
            }
            Msg msg = Msg.of(k, args.toArray());
            if (CoordinateSentinel.isSuspect(ES.render(msg)) != CoordinateSentinel.isSuspect(EN.render(msg))) mismatched.add(k.id());
        }
        assertEquals(List.of(), mismatched, "a message held back in one language and not the other");
        assertEquals(List.of(), PROBLEMS);
    }
}
