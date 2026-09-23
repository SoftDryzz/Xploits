package com.xploits.shared.core.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One language's texts, from {@code /xploits/lang/<code>.lang}: {@code key=value} lines, {@code #}
 * comments, value taken verbatim after the first {@code =} (leading spaces kept), escapes {@code \n}
 * {@code \t} {@code \\}. Placeholders are {@code {name}} or {@code {name,decimals}}; {@code {{} and
 * {@code }}} are literal braces. Not {@code MessageFormat}: it eats apostrophes.
 *
 * <p>Rendering never throws at the player: a missing key or argument shows as {@code ‹id›} and is
 * reported once through {@code problems}.
 */
public final class Catalog {
    private static final Pattern KEY = Pattern.compile("[a-z0-9]+(\\.[a-z0-9-]+)+");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9]*)(?:,([0-9]))?}");

    private final Language language;
    private final Map<String, String> entries;
    private final Consumer<String> problems;
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    private Catalog(Language language, Map<String, String> entries, Consumer<String> problems) {
        this.language = language;
        this.entries = Collections.unmodifiableMap(entries);
        this.problems = problems;
    }

    public static Catalog load(Language language, Consumer<String> problems) {
        String resource = "/xploits/lang/" + language.code() + ".lang";
        try (InputStream in = Catalog.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing resource " + resource + " in the jar");
            return parse(language, new String(in.readAllBytes(), StandardCharsets.UTF_8), problems);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Catalog parse(Language language, String text, Consumer<String> problems) {
        Map<String, String> entries = new LinkedHashMap<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].endsWith("\r") ? lines[i].substring(0, lines[i].length() - 1) : lines[i];
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException(language.code() + ".lang line " + (i + 1) + ": expected key=value");
            String key = line.substring(0, eq).strip();
            if (!KEY.matcher(key).matches()) throw new IllegalArgumentException(language.code() + ".lang line " + (i + 1) + ": bad key " + key);
            String value = unescape(line.substring(eq + 1), language, i + 1);
            checkBraces(value, language, i + 1);
            if (entries.put(key, value) != null) throw new IllegalArgumentException(language.code() + ".lang line " + (i + 1) + ": duplicate key " + key);
        }
        return new Catalog(language, entries, problems);
    }

    public Language language() {
        return language;
    }

    public Set<String> ids() {
        return entries.keySet();
    }

    /** The template for {@code id}, or {@code null}. */
    public String raw(String id) {
        return entries.get(id);
    }

    public static Set<String> placeholders(String value) {
        Set<String> names = new LinkedHashSet<>();
        String v = value.replace("{{", "").replace("}}", "");
        Matcher m = PLACEHOLDER.matcher(v);
        while (m.find()) names.add(m.group(1));
        return names;
    }

    public String render(MessageKey key, Object... namesAndValues) {
        return render(Msg.of(key, namesAndValues));
    }

    public String render(Msg msg) {
        String id = msg.key().id();
        String template = entries.get(id);
        if (template == null) {
            report("missing key " + id + " in " + language.code() + ".lang");
            return "‹" + id + "›";
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{' && template.startsWith("{{", i)) {
                out.append('{');
                i += 2;
            } else if (c == '}' && template.startsWith("}}", i)) {
                out.append('}');
                i += 2;
            } else if (c == '{') {
                Matcher m = PLACEHOLDER.matcher(template).region(i, template.length());
                m.lookingAt(); // parse() guaranteed a valid placeholder here
                String name = m.group(1);
                Object value = msg.args().get(name);
                if (value == null) {
                    report("argument " + name + " missing for " + id);
                    out.append('‹').append(name).append('›');
                } else {
                    out.append(format(value, m.group(2)));
                }
                i = m.end();
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private String format(Object value, String decimals) {
        if (value instanceof Msg m) return render(m);
        if (value instanceof MessageKey k) return render(Msg.of(k));
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            String s = decimals != null
                ? String.format(Locale.ROOT, "%." + decimals + "f", d)
                : BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
            return language == Language.ES ? s.replace('.', ',') : s;
        }
        return String.valueOf(value);
    }

    private void report(String problem) {
        if (reported.add(problem)) problems.accept(problem);
    }

    private static String unescape(String raw, Language language, int line) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (i + 1 == raw.length()) throw new IllegalArgumentException(language.code() + ".lang line " + line + ": dangling backslash");
            char n = raw.charAt(++i);
            switch (n) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case '\\' -> out.append('\\');
                default -> throw new IllegalArgumentException(language.code() + ".lang line " + line + ": unknown escape \\" + n);
            }
        }
        return out.toString();
    }

    private static void checkBraces(String value, Language language, int line) {
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if ((c == '{' && value.startsWith("{{", i)) || (c == '}' && value.startsWith("}}", i))) {
                i += 2;
            } else if (c == '{') {
                Matcher m = PLACEHOLDER.matcher(value).region(i, value.length());
                if (!m.lookingAt()) throw new IllegalArgumentException(language.code() + ".lang line " + line + ": bad placeholder at column " + (i + 1));
                i = m.end();
            } else if (c == '}') {
                throw new IllegalArgumentException(language.code() + ".lang line " + line + ": stray } at column " + (i + 1));
            } else {
                i++;
            }
        }
    }
}
