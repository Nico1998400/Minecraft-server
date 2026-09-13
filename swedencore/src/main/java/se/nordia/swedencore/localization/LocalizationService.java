package se.nordia.swedencore.localization;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;

/**
 * Loads message templates (MiniMessage syntax) per locale.
 *
 * <p>Bundled files live at {@code lang/<tag>.properties} (UTF-8). Server owners may override individual keys by
 * placing a file with the same name in the plugin's {@code lang/} data directory.
 *
 * <p>Lookup order: requested locale → default locale ({@code sv_SE}) → the key itself (and a warning, once).
 */
public final class LocalizationService {

    private final Map<SupportedLocale, Map<String, String>> bundles = new EnumMap<>(SupportedLocale.class);
    private final Set<String> warnedMissing = Collections.synchronizedSet(new TreeSet<>());
    private final Logger logger;

    public LocalizationService(Logger logger) {
        this.logger = logger;
    }

    public static LocalizationService loadBundled(Logger logger, ClassLoader classLoader, Path overrideDir) {
        LocalizationService service = new LocalizationService(logger);
        service.reload(classLoader, overrideDir);
        return service;
    }

    public synchronized void reload(ClassLoader classLoader, Path overrideDir) {
        for (SupportedLocale locale : SupportedLocale.values()) {
            Properties props = new Properties();
            String resource = "lang/" + locale.tag() + ".properties";
            try (InputStream in = classLoader.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException("Missing bundled language file " + resource);
                }
                try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    props.load(reader);
                }
                if (overrideDir != null) {
                    Path override = overrideDir.resolve(locale.tag() + ".properties");
                    if (Files.isRegularFile(override)) {
                        try (Reader reader = Files.newBufferedReader(override, StandardCharsets.UTF_8)) {
                            props.load(reader);
                        }
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load " + resource, e);
            }
            Map<String, String> map = new java.util.HashMap<>();
            for (String key : props.stringPropertyNames()) {
                map.put(key, props.getProperty(key));
            }
            bundles.put(locale, Map.copyOf(map));
        }
        warnedMissing.clear();
    }

    public String template(SupportedLocale locale, String key) {
        SupportedLocale effective = locale == null ? SupportedLocale.DEFAULT : locale;
        String value = bundles.get(effective).get(key);
        if (value == null && effective != SupportedLocale.DEFAULT) {
            value = bundles.get(SupportedLocale.DEFAULT).get(key);
        }
        if (value == null) {
            if (warnedMissing.add(key)) {
                logger.warning("Missing localization key: " + key);
            }
            return key;
        }
        return value;
    }

    public boolean has(SupportedLocale locale, String key) {
        return bundles.get(locale).containsKey(key);
    }

    public Set<String> keys(SupportedLocale locale) {
        return bundles.get(locale).keySet();
    }
}
