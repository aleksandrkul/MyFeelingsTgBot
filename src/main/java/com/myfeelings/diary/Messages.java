package com.myfeelings.diary;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;

/**
 * Every string the owner sees, in the language they picked.
 *
 * <p>Both files are loaded at startup, so a missing key fails on the first run rather than months
 * later in the middle of a reply.
 */
public class Messages {

    private static final Map<Lang, Properties> TABLES = load();

    private Lang lang;

    public Messages(Lang lang) {
        this.lang = lang;
    }

    public Lang lang() {
        return lang;
    }

    public java.util.Locale locale() {
        return lang.locale();
    }

    public void use(Lang lang) {
        this.lang = lang;
    }

    /** The text for {@code key}, with {@code args} filled into its {@code %s} and {@code %d} slots. */
    public String get(String key, Object... args) {
        return get(lang, key, args);
    }

    public static String get(Lang lang, String key, Object... args) {
        String template = TABLES.get(lang).getProperty(key);
        if (template == null) {
            throw new IllegalStateException("No message '" + key + "' for " + lang.code());
        }
        return args.length == 0 ? template : template.formatted(args);
    }

    /**
     * A count with its noun in the right form: "3 дня", "5 дней", "1 день".
     *
     * <p>Russian needs three forms and the choice is arithmetic, so it belongs in code rather than in
     * the properties files.
     */
    public String plural(String noun, long count) {
        return count + " " + get("plural." + noun + "." + form(count));
    }

    private String form(long count) {
        if (lang != Lang.RU) {
            return count == 1 ? "one" : "many";
        }
        long lastTwo = Math.abs(count) % 100;
        long last = lastTwo % 10;
        if (lastTwo >= 11 && lastTwo <= 14) {
            return "many";
        }
        if (last == 1) {
            return "one";
        }
        return last >= 2 && last <= 4 ? "few" : "many";
    }

    /** Both languages at once, for the very first message, before a language is chosen. */
    public static String bilingual(String key, Object... args) {
        return get(Lang.RU, key, args) + "\n" + get(Lang.EN, key, args);
    }

    private static Map<Lang, Properties> load() {
        Map<Lang, Properties> tables = new EnumMap<>(Lang.class);
        for (Lang lang : Lang.values()) {
            String resource = "/messages_" + lang.code() + ".properties";
            Properties properties = new Properties();
            try (InputStream in = Messages.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException(resource + " is missing from the build");
                }
                properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read " + resource, e);
            }
            tables.put(lang, properties);
        }
        verifySameKeys(tables);
        return tables;
    }

    /** A key present in one language and missing in the other would break only for one owner. */
    private static void verifySameKeys(Map<Lang, Properties> tables) {
        var reference = tables.get(Lang.EN).stringPropertyNames();
        for (Lang lang : Lang.values()) {
            var keys = tables.get(lang).stringPropertyNames();
            if (!keys.equals(reference)) {
                var missing = new java.util.TreeSet<>(reference);
                missing.removeAll(keys);
                var extra = new java.util.TreeSet<>(keys);
                extra.removeAll(reference);
                throw new IllegalStateException(
                        "messages_" + lang.code() + ".properties differs from messages_en: "
                                + "missing " + missing + ", unexpected " + extra);
            }
        }
    }
}
