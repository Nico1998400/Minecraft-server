package se.nordia.swedencore.localization;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import se.nordia.swedencore.economy.AccountOwner;
import se.nordia.swedencore.economy.TransactionType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class LocalizationBundleTest {

    /** Standard MiniMessage tag names that are styling, not placeholders. */
    private static final Set<String> STYLE_TAGS = Set.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray", "grey",
            "dark_gray", "dark_grey", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
            "bold", "b", "italic", "i", "em", "underlined", "u", "strikethrough", "st", "obfuscated", "obf",
            "reset", "newline", "br", "gradient", "rainbow", "hover", "click", "key", "lang", "insertion", "font",
            "transition", "color", "colour", "c", "shadow", "pride");
    private static final Pattern TAG = Pattern.compile("<([a-z_]+)(?::[^>]*)?>");
    private static final Path MAIN_SOURCES = Path.of("src/main/java");

    private static LocalizationService service;

    @BeforeAll
    static void load() {
        service = LocalizationService.loadBundled(Logger.getLogger("test"), LocalizationBundleTest.class.getClassLoader(), null);
    }

    @Test
    void allLocalesHaveIdenticalKeys() {
        Set<String> reference = service.keys(SupportedLocale.DEFAULT);
        for (SupportedLocale locale : SupportedLocale.values()) {
            assertThat(service.keys(locale)).as("keys of " + locale).containsExactlyInAnyOrderElementsOf(reference);
        }
    }

    @Test
    void placeholdersMatchAcrossLocales() {
        for (String key : service.keys(SupportedLocale.DEFAULT)) {
            Set<String> expected = placeholders(service.template(SupportedLocale.DEFAULT, key));
            for (SupportedLocale locale : SupportedLocale.values()) {
                assertThat(placeholders(service.template(locale, key))).as(locale + " " + key).isEqualTo(expected);
            }
        }
    }

    @Test
    void everyTransactionTypeAndOwnerTypeIsTranslated() {
        for (TransactionType type : TransactionType.values()) {
            assertThat(service.has(SupportedLocale.DEFAULT, "transaction.type." + type.name())).as(type.name()).isTrue();
        }
        for (AccountOwner.OwnerType type : AccountOwner.OwnerType.values()) {
            assertThat(service.has(SupportedLocale.DEFAULT, "economy.counterparty." + type.name())).as(type.name()).isTrue();
        }
    }

    @Test
    void everyDomainErrorCodeUsedInCodeHasAMessage() throws IOException {
        Pattern errorCode = Pattern.compile("(?:new DomainException|DomainException\\.of)\\(\\s*\"([a-z0-9_.]+)\"");
        Set<String> missing = new TreeSet<>();
        for (String source : mainSources()) {
            Matcher m = errorCode.matcher(source);
            while (m.find()) {
                if (!service.has(SupportedLocale.DEFAULT, "error." + m.group(1))) {
                    missing.add("error." + m.group(1));
                }
            }
        }
        assertThat(missing).as("error keys referenced in code but missing in sv_SE").isEmpty();
    }

    @Test
    void everyMessageKeyUsedInPaperLayerExists() throws IOException {
        Pattern sendCall = Pattern.compile("\\.(?:send|render)\\([^;\"]*?\"([a-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)\"");
        Set<String> missing = new TreeSet<>();
        for (String source : mainSources()) {
            Matcher m = sendCall.matcher(source);
            while (m.find()) {
                String key = m.group(1);
                if (!key.endsWith(".") && !service.has(SupportedLocale.DEFAULT, key)) {
                    missing.add(key);
                }
            }
        }
        assertThat(missing).as("message keys referenced in code but missing in sv_SE").isEmpty();
    }

    @Test
    void fallsBackToDefaultThenKey() {
        assertThat(service.template(SupportedLocale.EN_US, "does.not.exist")).isEqualTo("does.not.exist");
        assertThat(service.template(null, "prefix")).isEqualTo(service.template(SupportedLocale.SV_SE, "prefix"));
    }

    private static Set<String> placeholders(String template) {
        Set<String> names = new HashSet<>();
        Matcher m = TAG.matcher(template);
        while (m.find()) {
            if (!STYLE_TAGS.contains(m.group(1))) {
                names.add(m.group(1));
            }
        }
        return names;
    }

    private static Set<String> mainSources() throws IOException {
        Set<String> sources = new HashSet<>();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                sources.add(Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return sources;
    }
}
