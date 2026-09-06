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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.env.EnvScalarConstructor;
import org.yaml.snakeyaml.error.MissingEnvironmentVariableException;

// XMine start - подстановка переменных окружения в config.yml
/**
 * {@link EnvScalarConstructor} that refuses to silently produce an empty value.
 *
 * <p>Stock SnakeYAML resolves a bare {@code !ENV ${VAR}} to an <em>empty string</em> when the
 * variable is unset. For a database password that is the worst possible outcome: the plugin starts,
 * fails to connect somewhere in the middle of start-up, and the reason has to be dug out of the
 * plugin log instead of being stated at the point where the value was missing.
 *
 * <p>So the bare form is made mandatory here: unset or empty is an error. The forms that carry an
 * explicit intent are left exactly as SnakeYAML defines them:
 *
 * <ul>
 *   <li>{@code ${VAR}} — required; unset or empty is an error (this class);
 *   <li>{@code ${VAR:-default}} — default when unset or empty;
 *   <li>{@code ${VAR-default}} — default when unset;
 *   <li>{@code ${VAR?message}}, {@code ${VAR:?message}} — required, with your own message.
 * </ul>
 *
 * <p>This matches what the proxy image's entrypoint did with its own text substitution: an unset
 * variable stopped the start-up loudly rather than writing an empty string into a config.
 */
public class StrictEnvScalarConstructor extends EnvScalarConstructor {

    @Override
    public @NotNull String apply(@NotNull String name, @Nullable String separator,
                                 @NotNull String value, @Nullable String environment) {
        // separator == null is the bare ${VAR} form: no default, no explicit "?" message.
        // Every other form already says what should happen, and is left to the parent.
        if (separator == null && (environment == null || environment.isEmpty())) {
            throw new MissingEnvironmentVariableException(
                    "Environment variable " + name + " is not set (or is empty), and config.yml uses it as ${"
                            + name + "}. Set the variable, or write ${" + name
                            + ":-some-default} if an absent value is acceptable.");
        }

        return super.apply(name, separator, value, environment);
    }
}
// XMine end - подстановка переменных окружения в config.yml
