/*
 * SkinsRestorer
 * Copyright (C) 2026  SkinsRestorer Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package net.skinsrestorer.config;

import ch.jalu.configme.SettingsManager;
import ch.jalu.configme.SettingsManagerBuilder;
import ch.jalu.configme.migration.PlainMigrationService;
import net.skinsrestorer.shared.config.DatabaseConfig;
import net.skinsrestorer.shared.config.EnvYamlFileResource;
import net.skinsrestorer.shared.config.EnvironmentSubstitutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// XMine start - подстановка переменных окружения в config.yml
/**
 * Guards the two things the XMine fork adds to config loading: environment variable expansion, and
 * the refusal to overwrite an existing config file.
 *
 * <p>The expansion rules are the XMine Paper fork's, ported in {@link EnvironmentSubstitutor}: no
 * tag, nested fallbacks, and a type that follows the quoting.
 *
 * <p>The environment variables these tests read are set by the {@code test} task, see
 * {@code test/build.gradle.kts}.
 */
class EnvConfigTest {

    private static final String HOST = System.getenv("SR_TEST_HOST");
    private static final String PASSWORD = System.getenv("SR_TEST_PASSWORD");

    /** Every key DatabaseConfig knows about, so nothing is missing from the resource. */
    private static final String COMPLETE_CONFIG = """
            database:
                type: MYSQL
                host: ${SR_TEST_HOST}
                port: 3306
                database: ${SR_TEST_DATABASE}
                username: ${SR_TEST_USERNAME}
                password: ${SR_TEST_PASSWORD}
                maxPoolSize: 10
                tablePrefix: 'sr_'
                connectionOptions: 'sslMode=trust&serverTimezone=UTC'
            """;

    @Test
    void expandsEnvVariablesAndLeavesTheFileAlone(@TempDir Path dir) throws IOException {
        Path config = write(dir, COMPLETE_CONFIG);
        List<String> warnings = new ArrayList<>();

        SettingsManager settings = load(config, warnings);

        assertEquals(HOST, settings.getProperty(DatabaseConfig.DATABASE_HOST));
        assertEquals(PASSWORD, settings.getProperty(DatabaseConfig.DATABASE_PASSWORD));
        assertEquals(COMPLETE_CONFIG, read(config), "an intact config must not be touched");
        assertTrue(warnings.isEmpty(), "nothing to warn about: " + warnings);
    }

    /**
     * The case this fork exists to prevent. A key missing from the file — which is what a plugin
     * upgrade looks like — makes ConfigMe report a migration, and stock ConfigMe answers that by
     * rewriting the whole file from the resolved in-memory values. That would put the database
     * password on disk in plain text and lose every {@code ${...}} reference with it.
     */
    @Test
    void doesNotRewriteTheConfigWhenAKeyIsMissing(@TempDir Path dir) throws IOException {
        String withoutOneKey = COMPLETE_CONFIG.replace("    maxPoolSize: 10\n", "");
        Path config = write(dir, withoutOneKey);
        List<String> warnings = new ArrayList<>();

        SettingsManager settings = load(config, warnings);

        assertEquals(withoutOneKey, read(config), "the config must not be rewritten");
        assertFalse(read(config).contains(PASSWORD), "the resolved password must not reach the disk");
        assertEquals(1, warnings.size(), "the suppressed write must be reported");
        // The missing key falls back to its default, and everything else still reads fine.
        assertEquals(10, settings.getProperty(DatabaseConfig.DATABASE_MAX_POOL_SIZE));
        assertEquals(PASSWORD, settings.getProperty(DatabaseConfig.DATABASE_PASSWORD));
    }

    /**
     * The reason this fork stopped using SnakeYAML's {@code !ENV}: that tag always produced a
     * String, ConfigMe took a String only for string and enum properties, and an int fed from the
     * environment was silently lost — which is what forced {@code database.port} to be a literal,
     * and a literal port is what made the node image fit one contour only.
     *
     * <p>Substitution now runs on the node tree, so an unquoted reference is retyped and arrives
     * as the number it expands to.
     */
    @Test
    void unquotedNumericReferenceArrivesAsANumber(@TempDir Path dir) throws IOException {
        String numericFromEnv = COMPLETE_CONFIG.replace("    port: 3306\n", "    port: ${SR_TEST_PORT}\n");
        Path config = write(dir, numericFromEnv);
        List<String> warnings = new ArrayList<>();

        SettingsManager settings = load(config, warnings);

        assertEquals(3307, settings.getProperty(DatabaseConfig.DATABASE_PORT),
                "an unquoted reference must reach an int property");
        assertEquals(numericFromEnv, read(config), "and still no rewrite");
        assertTrue(warnings.isEmpty(), "nothing to warn about: " + warnings);
    }

    /** The other half of that rule: quoting asks for a string, and a string it stays. */
    @Test
    void quotedNumericReferenceStaysAString(@TempDir Path dir) throws IOException {
        String quoted = COMPLETE_CONFIG.replace("    port: 3306\n", "    port: \"${SR_TEST_PORT}\"\n");
        Path config = write(dir, quoted);
        List<String> warnings = new ArrayList<>();

        SettingsManager settings = load(config, warnings);

        assertEquals(3306, settings.getProperty(DatabaseConfig.DATABASE_PORT),
                "a String where an Integer is expected falls back to the default");
        assertEquals(quoted, read(config), "and still no rewrite");
        assertEquals(1, warnings.size(), "the suppressed write must be reported");
    }

    /**
     * An unset variable is left in the config verbatim rather than throwing or expanding to an
     * empty string. The plugin then fails to connect with a literal {@code ${...}} where the host
     * should be, which names its own cause — an empty string would not.
     */
    @Test
    void unsetVariableIsLeftAsWritten(@TempDir Path dir) throws IOException {
        Path config = write(dir, COMPLETE_CONFIG.replace("${SR_TEST_HOST}", "${SR_TEST_DEFINITELY_UNSET}"));

        SettingsManager settings = load(config, new ArrayList<>());

        assertEquals("${SR_TEST_DEFINITELY_UNSET}", settings.getProperty(DatabaseConfig.DATABASE_HOST));
    }

    /** A fallback chain: the plugin's own variable first, the shared one behind it. */
    @Test
    void nestedFallbackResolvesToTheInnerVariable(@TempDir Path dir) throws IOException {
        Path config = write(dir, COMPLETE_CONFIG.replace(
                "${SR_TEST_HOST}", "${SR_TEST_UNSET_HOST:-${SR_TEST_HOST:-localhost}}"));

        SettingsManager settings = load(config, new ArrayList<>());

        assertEquals(HOST, settings.getProperty(DatabaseConfig.DATABASE_HOST));
    }

    @Test
    void writesTheDefaultTemplateIntoAnEmptyFile(@TempDir Path dir) throws IOException {
        Path config = write(dir, "");
        List<String> warnings = new ArrayList<>();

        load(config, warnings);

        assertTrue(read(config).contains("database"), "a fresh install must still get a config");
        assertTrue(warnings.isEmpty());
    }

    /**
     * The substitution rules themselves, stated once. They are the XMine Paper fork's, and a
     * divergence here would mean two dialects on one server — which is the thing this port exists
     * to remove.
     */
    @Test
    void substitutionRulesMatchTheCoreDialect() {
        UnaryOperator<String> env = name -> switch (name) {
            case "SET" -> "value";
            case "EMPTY" -> "";
            case "INDIRECT" -> "${SET}";
            default -> null;
        };

        // Expansion, and the two ways of asking for a default.
        assertEquals("value", EnvironmentSubstitutor.substitute("${SET}", env));
        assertEquals("value", EnvironmentSubstitutor.substitute("${UNSET:-${SET}}", env));
        assertEquals("fallback", EnvironmentSubstitutor.substitute("${UNSET:-fallback}", env));
        assertEquals("fallback", EnvironmentSubstitutor.substitute("${EMPTY:-fallback}", env),
                "an empty value counts as unset, as in the shell");
        assertEquals("", EnvironmentSubstitutor.substitute("${EMPTY}", env));

        // Nothing to expand: the caller keeps the original scalar, tag and all.
        assertNull(EnvironmentSubstitutor.substitute("plain text", env));

        // Everything ambiguous is left exactly as written.
        assertNull(EnvironmentSubstitutor.substitute("${UNSET}", env), "an unset variable stays put");
        assertNull(EnvironmentSubstitutor.substitute("${lower_case}", env), "not an env var name");
        assertNull(EnvironmentSubstitutor.substitute("${UNTERMINATED", env));
        assertEquals("${SET}", EnvironmentSubstitutor.substitute("$${SET}", env), "$$ escapes");
        assertEquals("${SET}", EnvironmentSubstitutor.substitute("${INDIRECT}", env),
                "a substituted value is never rescanned");

        // Surrounding text survives, and a value can carry several references.
        assertEquals("jdbc:mysql://value:3306/", EnvironmentSubstitutor.substitute(
                "jdbc:mysql://${SET}:${UNSET:-3306}/", env));
    }

    private static SettingsManager load(Path config, List<String> warnings) {
        return SettingsManagerBuilder
                .withResource(new EnvYamlFileResource(config, warnings::add))
                .configurationData(DatabaseConfig.class)
                // SkinsRestorer's own ConfigMigratorService reduces to exactly this condition plus
                // a set of pre-15.x key renames, which are irrelevant here.
                .migrationService(new PlainMigrationService())
                .create();
    }

    private static Path write(Path dir, String content) throws IOException {
        Path config = dir.resolve("config.yml");
        Files.write(config, content.getBytes(StandardCharsets.UTF_8));
        return config;
    }

    private static String read(Path config) throws IOException {
        return new String(Files.readAllBytes(config), StandardCharsets.UTF_8);
    }
}
// XMine end - подстановка переменных окружения в config.yml
