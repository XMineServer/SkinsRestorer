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
import ch.jalu.configme.exception.ConfigMeException;
import ch.jalu.configme.migration.PlainMigrationService;
import net.skinsrestorer.shared.config.DatabaseConfig;
import net.skinsrestorer.shared.config.EnvYamlFileResource;
import net.skinsrestorer.shared.config.StrictEnvScalarConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// XMine start - подстановка переменных окружения в config.yml
/**
 * Guards the two things the XMine fork adds to config loading: {@code !ENV} expansion, and the
 * refusal to overwrite an existing config file.
 *
 * <p>The environment variables these tests read are set by the {@code test} task, see
 * {@code test/build.gradle.kts}.
 */
class EnvConfigTest {

    private static final String HOST = System.getenv("SR_TEST_HOST");
    private static final String PASSWORD = System.getenv("SR_TEST_PASSWORD");

    /** Every key DatabaseConfig knows about, so nothing is missing from the resource. */
    private static final String COMPLETE_CONFIG =
            "database:\n"
                    + "    type: MYSQL\n"
                    + "    host: !ENV ${SR_TEST_HOST}\n"
                    + "    port: 3306\n"
                    + "    database: !ENV ${SR_TEST_DATABASE}\n"
                    + "    username: !ENV ${SR_TEST_USERNAME}\n"
                    + "    password: !ENV ${SR_TEST_PASSWORD}\n"
                    + "    maxPoolSize: 10\n"
                    + "    tablePrefix: 'sr_'\n"
                    + "    connectionOptions: 'sslMode=trust&serverTimezone=UTC'\n";

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
     * password on disk in plain text and drop the {@code !ENV} tags with it.
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
     * An {@code !ENV} scalar is always a String, and ConfigMe accepts a String only for string and
     * enum properties. Feeding an int property from the environment therefore silently loses the
     * value — the test states that limitation rather than pretending it does not exist.
     */
    @Test
    void envOnANumericPropertyIsIgnoredButStillDoesNotRewrite(@TempDir Path dir) throws IOException {
        String numericFromEnv = COMPLETE_CONFIG.replace("    port: 3306\n", "    port: !ENV ${SR_TEST_PORT}\n");
        Path config = write(dir, numericFromEnv);
        List<String> warnings = new ArrayList<>();

        SettingsManager settings = load(config, warnings);

        assertEquals(3306, settings.getProperty(DatabaseConfig.DATABASE_PORT),
                "a String where an Integer is expected falls back to the default");
        assertEquals(numericFromEnv, read(config), "and still no rewrite");
        assertEquals(1, warnings.size());
    }

    @Test
    void unsetVariableFailsAndNamesIt(@TempDir Path dir) throws IOException {
        Path config = write(dir, COMPLETE_CONFIG.replace("${SR_TEST_HOST}", "${SR_TEST_DEFINITELY_UNSET}"));

        ConfigMeException thrown = assertThrows(ConfigMeException.class,
                () -> load(config, new ArrayList<>()));

        assertTrue(thrown.getMessage().contains("SR_TEST_DEFINITELY_UNSET"),
                "the message must name the variable, got: " + thrown.getMessage());
    }

    @Test
    void writesTheDefaultTemplateIntoAnEmptyFile(@TempDir Path dir) throws IOException {
        Path config = write(dir, "");
        List<String> warnings = new ArrayList<>();

        load(config, warnings);

        assertTrue(read(config).contains("database"), "a fresh install must still get a config");
        assertTrue(warnings.isEmpty());
    }

    @Test
    void strictConstructorOnlyTightensTheBareForm() {
        StrictEnvScalarConstructor constructor = new StrictEnvScalarConstructor();

        // Bare ${VAR}: unset or empty is an error, a value passes through.
        assertThrows(RuntimeException.class, () -> constructor.apply("VAR", null, "", null));
        assertThrows(RuntimeException.class, () -> constructor.apply("VAR", null, "", ""));
        assertEquals("value", constructor.apply("VAR", null, "", "value"));

        // The explicit forms keep SnakeYAML's own semantics.
        assertEquals("fallback", constructor.apply("VAR", ":-", "fallback", null));
        assertEquals("fallback", constructor.apply("VAR", ":-", "fallback", ""));
        assertEquals("value", constructor.apply("VAR", ":-", "fallback", "value"));
        assertEquals("fallback", constructor.apply("VAR", "-", "fallback", null));
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
