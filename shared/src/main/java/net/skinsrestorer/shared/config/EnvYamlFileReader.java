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
package net.skinsrestorer.shared.config;

import ch.jalu.configme.exception.ConfigMeException;
import ch.jalu.configme.resource.YamlFileReader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.env.EnvScalarConstructor;
import org.yaml.snakeyaml.error.MissingEnvironmentVariableException;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

// XMine start - подстановка переменных окружения в config.yml
/**
 * A {@link YamlFileReader} that resolves environment variables while parsing.
 *
 * <p>The only difference from the parent is the SnakeYAML constructor: {@link EnvScalarConstructor}
 * instead of the default one. It acts on scalars tagged {@code !ENV} and on nothing else, so an
 * untagged {@code ${...}} stays a literal string and existing configs keep parsing byte for byte
 * the same way:
 *
 * <pre>{@code
 * database:
 *   password: !ENV ${SR_MYSQL_PASSWORD}          # required: unset or empty stops the start-up
 *   database: !ENV ${SR_MYSQL_DATABASE:-skins}   # default if unset or empty
 * }</pre>
 *
 * <p>The constructor is {@link StrictEnvScalarConstructor}, not the stock one: a bare
 * {@code ${VAR}} that stock SnakeYAML would resolve to an empty string is an error here.
 *
 * <p>Note the types. An {@code !ENV} scalar always yields a <em>String</em>, and ConfigMe accepts a
 * String only for string and enum properties — an integer or boolean property fed from {@code !ENV}
 * counts as invalid in the resource, which makes ConfigMe rewrite the whole file. Keep numbers and
 * booleans (for example {@code database.port}) as literals.
 *
 * <p>Resolution happens on read only. {@link ch.jalu.configme.resource.YamlFileResource} writing is
 * inherited untouched, but be aware that if SkinsRestorer ever exports the config (a migration),
 * the resolved values — the real password — would be written to disk.
 */
public class EnvYamlFileReader extends YamlFileReader {

    /**
     * Constructor.
     *
     * @param path the file to load
     * @param splitDotPaths whether dots in yaml paths should be split into nested paths
     */
    public EnvYamlFileReader(@NotNull Path path, boolean splitDotPaths) {
        super(path, StandardCharsets.UTF_8, splitDotPaths);
    }

    @Override
    protected @Nullable Map<String, Object> loadFile(boolean splitDotPaths) {
        // The charset is deliberately not a constructor argument kept in a field: loadFile() runs
        // from the parent constructor, before any field of this class is assigned, so a field would
        // still be null here. getPath() works because the parent assigns path before calling us.
        Path path = getPath();

        try (InputStream is = Files.newInputStream(path);
             Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            Map<Object, Object> rootMap = new Yaml(new StrictEnvScalarConstructor()).load(reader);
            return normalizeMap(rootMap, splitDotPaths);
        } catch (MissingEnvironmentVariableException e) {
            // Wrapped by hand so the reason - which variable - survives into the top-level message.
            // ConfigMe's generic YAMLException branch would only say "YAML error while loading".
            throw new ConfigMeException("Cannot read '" + path + "': " + e.getMessage(), e);
        } catch (IOException e) {
            throw new ConfigMeException("Could not read file '" + path + "'", e);
        } catch (ClassCastException e) {
            throw new ConfigMeException("Top-level is not a map in '" + path + "'", e);
        } catch (YAMLException e) {
            throw new ConfigMeException("YAML error while trying to load file '" + path + "'", e);
        }
    }
}
// XMine end - подстановка переменных окружения в config.yml
