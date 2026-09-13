package se.nordia.swedencore;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforces the layering rule from CLAUDE.md: domain packages are pure Java and never depend on Bukkit/Paper/Adventure.
 * This keeps economic logic testable against real PostgreSQL without a server, and prevents main-thread coupling.
 */
class ArchitectureTest {

    private static final Path ROOT = Path.of("src/main/java/se/nordia/swedencore");
    private static final Pattern FORBIDDEN = Pattern.compile("^import\\s+(static\\s+)?(org\\.bukkit|io\\.papermc|net\\.kyori|com\\.destroystokyo)\\.",
            Pattern.MULTILINE);
    private static final Pattern DOUBLE_MONEY = Pattern.compile("\\b(double|float)\\s+\\w*(balance|amount|price|salary|reward|money)\\w*\\b",
            Pattern.CASE_INSENSITIVE);

    @Test
    void domainPackagesDoNotImportPaper() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Path relative = ROOT.relativize(file);
                boolean adapter = relative.startsWith("paper") || relative.toString().equals("SwedenCorePlugin.java");
                if (!adapter && FORBIDDEN.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) {
                    violations.add(relative.toString());
                }
            }
        }
        assertThat(violations).as("domain files importing Bukkit/Paper/Adventure").isEmpty();
    }

    @Test
    void moneyIsNeverFloatingPoint() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (DOUBLE_MONEY.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) {
                    violations.add(ROOT.relativize(file).toString());
                }
            }
        }
        assertThat(violations).as("floating point money variables").isEmpty();
    }
}
