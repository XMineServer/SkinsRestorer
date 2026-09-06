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

import ch.jalu.configme.configurationdata.ConfigurationData;
import ch.jalu.configme.exception.ConfigMeException;
import ch.jalu.configme.resource.PropertyReader;
import ch.jalu.configme.resource.YamlFileResource;
import ch.jalu.configme.utils.Utils;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

// XMine start - подстановка переменных окружения в config.yml
/**
 * A {@link YamlFileResource} that expands {@code !ENV ${VAR}} scalars from the process environment
 * on read, and that never overwrites a config file that already has content.
 *
 * <p>Written for XMine: the proxy image carries the MySQL credentials in the environment, and they
 * must not end up in a file. See {@link EnvYamlFileReader} for how reading works.
 *
 * <h2>Why writing is blocked</h2>
 *
 * <p>ConfigMe rewrites the whole config whenever the migration service reports a migration, and
 * SkinsRestorer's {@link ConfigMigratorService} reports one as soon as
 * {@code configurationData.areAllValuesValidInResource()} is false — which happens the moment a new
 * plugin version adds a key the existing file does not have. The rewrite is done from the values
 * held in memory, and those are the <em>resolved</em> ones: the {@code !ENV} tags disappear and the
 * real database password is written to disk in plain text. Verified against ConfigMe
 * {@code beefdbdf7e} + SnakeYAML 1.33: a single missing key is enough.
 *
 * <p>For XMine that write is pointless as well as harmful — plugin configs are laid out from the
 * image on every start and {@code plugins/} is not a volume, so anything the plugin writes lands in
 * the container's writable layer and is gone at the next start.
 *
 * <p>So an existing file is never overwritten; a missing key just falls back to its default in
 * memory, and the suppressed write is logged. An <em>empty</em> file is still written to, which is
 * what generates the default config.yml on a fresh install.
 */
public class EnvYamlFileResource extends YamlFileResource {

    private final Consumer<String> warningLogger;

    /**
     * Constructor.
     *
     * @param path the config file to read
     * @param warningLogger where to report a suppressed write
     */
    public EnvYamlFileResource(@NotNull Path path, @NotNull Consumer<String> warningLogger) {
        super(path);
        this.warningLogger = warningLogger;

        // SettingsManagerBuilder#withYamlFile does this for us; withResource does not, and the
        // reader would fail with NoSuchFileException on a first run.
        Utils.createFileIfNotExists(path);
    }

    @Override
    public @NotNull PropertyReader createReader() {
        // EnvYamlFileReader is hardcoded to UTF-8 (see there for why it cannot read a field).
        // The default options are UTF-8 too, so this only guards against a future change here.
        if (!StandardCharsets.UTF_8.equals(getOptions().getCharset())) {
            throw new ConfigMeException("EnvYamlFileResource only supports UTF-8, got " + getOptions().getCharset());
        }

        return new EnvYamlFileReader(getPath(), getOptions().splitDotPaths());
    }

    @Override
    public void exportProperties(@NotNull ConfigurationData configurationData) {
        if (hasContent()) {
            warningLogger.accept("config.yml would have been rewritten (a key is missing or has an "
                    + "unexpected type), but this build never overwrites an existing config: the rewrite would "
                    + "resolve every ${...} and put the values, secrets included, into the file. The missing "
                    + "keys fall back to their defaults for this run. Update config.yml in the image if you "
                    + "want the new keys.");
            return;
        }

        // Empty file: this is the first run and there is nothing to lose. Writing here is what
        // produces the default config.yml.
        super.exportProperties(configurationData);
    }

    private boolean hasContent() {
        try {
            return Files.exists(getPath()) && Files.size(getPath()) > 0L;
        } catch (IOException e) {
            // Cannot tell - assume there is something worth keeping.
            return true;
        }
    }
}
// XMine end - подстановка переменных окружения в config.yml
