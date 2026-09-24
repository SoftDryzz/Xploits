package com.xploits;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Published documentation: links resolve, and the English docs are English (docs-in-English design §5). */
class DocsTest {
    /** Docs already translated. Each task appends; the last covers every published doc except README.es.md. */
    private static final List<String> ENGLISH_DOCS = List.of("CHANGELOG.md", "docs/VERSIONING.md",
        "docs/architecture.md", "docs/conventions.md", "docs/known-issues.md", "docs/security.md",
        "docs/own-client/README.md", "docs/own-client/meteor-anatomy.md", "docs/own-client/roadmap.md",
        "docs/own-client/integrating-baritone.md", "README.md");

    private static final Set<String> SPANISH = Set.of("el", "la", "los", "las", "que", "para", "con", "una", "del",
        "por", "pero", "cuando", "como", "este", "esta", "esto", "sin", "sobre", "porque", "donde", "también");
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s]+)\\)");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`]*`");
    private static final Pattern WORD = Pattern.compile("\\p{L}+");

    static List<Path> publishedDocs() throws IOException {
        List<Path> docs = new ArrayList<>();
        try (Stream<Path> root = Files.list(Path.of(""))) {
            root.filter(p -> p.getFileName().toString().matches("(README.*|CHANGELOG)\\.md")).forEach(docs::add);
        }
        try (Stream<Path> tree = Files.walk(Path.of("docs"))) {
            tree.filter(p -> p.toString().endsWith(".md"))
                .filter(p -> !p.startsWith(Path.of("docs", "superpowers")))
                .forEach(docs::add);
        }
        return docs;
    }

    /** Lines outside fenced code blocks, with inline code removed. */
    static List<String> prose(Path doc) throws IOException {
        List<String> out = new ArrayList<>();
        boolean fenced = false;
        for (String line : Files.readAllLines(doc, StandardCharsets.UTF_8)) {
            if (line.strip().startsWith("```")) {
                fenced = !fenced;
                out.add("");
                continue;
            }
            out.add(fenced ? "" : INLINE_CODE.matcher(line).replaceAll(" "));
        }
        return out;
    }

    /** GitHub's heading slugs, with -1, -2… for repeats. */
    static Set<String> anchors(Path doc) throws IOException {
        Set<String> slugs = new LinkedHashSet<>();
        Map<String, Integer> seen = new HashMap<>();
        boolean fenced = false;
        for (String line : Files.readAllLines(doc, StandardCharsets.UTF_8)) {
            if (line.strip().startsWith("```")) fenced = !fenced;
            if (fenced) continue;
            Matcher h = HEADING.matcher(line);
            if (!h.matches()) continue;
            String text = h.group(1).replaceAll("\\[([^]]*)]\\([^)]*\\)", "$1").replace("`", "");
            String slug = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} _-]", "").replace(' ', '-');
            int n = seen.merge(slug, 1, Integer::sum);
            slugs.add(n == 1 ? slug : slug + "-" + (n - 1));
        }
        return slugs;
    }

    @Test
    void linksResolve() throws IOException {
        List<String> broken = new ArrayList<>();
        for (Path doc : publishedDocs()) {
            List<String> lines = prose(doc);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = LINK.matcher(lines.get(i));
                while (m.find()) {
                    String target = m.group(1);
                    if (target.matches("^[a-z]+:.*")) continue;
                    String file = target.contains("#") ? target.substring(0, target.indexOf('#')) : target;
                    String anchor = target.contains("#") ? target.substring(target.indexOf('#') + 1) : null;
                    Path resolved = file.isEmpty() ? doc : doc.toAbsolutePath().getParent().resolve(file).normalize();
                    if (!Files.exists(resolved)) {
                        broken.add(doc + ":" + (i + 1) + " -> " + target);
                    } else if (anchor != null && resolved.toString().endsWith(".md") && !anchors(resolved).contains(anchor)) {
                        broken.add(doc + ":" + (i + 1) + " -> " + target + " (no such heading)");
                    }
                }
            }
        }
        assertEquals(List.of(), broken);
    }

    @Test
    void englishDocsHaveNoSpanishProse() throws IOException {
        List<String> found = new ArrayList<>();
        for (String name : ENGLISH_DOCS) {
            Path doc = Path.of(name);
            List<String> raw = Files.readAllLines(doc, StandardCharsets.UTF_8);
            List<String> lines = prose(doc);
            for (int i = 0; i < lines.size(); i++) {
                if (raw.get(i).contains("<!-- es-quote -->")) continue;
                Set<String> hits = new LinkedHashSet<>();
                Matcher w = WORD.matcher(lines.get(i).toLowerCase(Locale.ROOT));
                while (w.find()) if (SPANISH.contains(w.group())) hits.add(w.group());
                if (hits.size() >= 2) found.add(name + ":" + (i + 1) + " " + hits);
            }
        }
        assertEquals(List.of(), found);
    }

    @Test
    void bothReadmesHaveTheSameSections() throws IOException {
        long en = Files.readAllLines(Path.of("README.md"), StandardCharsets.UTF_8).stream().filter(l -> l.startsWith("## ")).count();
        long es = Files.readAllLines(Path.of("README.es.md"), StandardCharsets.UTF_8).stream().filter(l -> l.startsWith("## ")).count();
        assertEquals(es, en, "README.md and README.es.md must mirror each other section by section");
    }
}
