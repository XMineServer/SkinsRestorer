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
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

// XMine start - подстановка переменных среды
/**
 * A {@link YamlFileReader} that expands environment variable references while parsing.
 *
 * <p>The rules are {@link EnvironmentSubstitutor}'s, which are the XMine Paper fork's: every plugin
 * config on a game node behaves the same way, whether the plugin reads it through Bukkit or, like
 * this one, through ConfigMe.
 *
 * <pre>{@code
 * database:
 *   host: ${DATABASE_HOST}                                  # left as written if unset
 *   port: ${SKINSRESTORER_DATABASE_PORT:-${DATABASE_PORT}}  # nested fallback
 *   database: ${SKINSRESTORER_DATABASE_DATABASE:-skins}     # literal fallback
 * }</pre>
 *
 * <p>No tag is needed. An earlier version of this class used SnakeYAML's own
 * {@code EnvScalarConstructor}, which acts only on scalars tagged {@code !ENV}; that meant a second
 * substitution dialect on the same server - different syntax, no nesting, and every value arriving
 * as a String.
 *
 * <h2>Quoting decides the type</h2>
 * Substitution runs on the parsed node tree, not on the file's text, so an unquoted
 * {@code port: ${DATABASE_PORT}} arrives as a real {@link Integer} and ConfigMe's integer property
 * takes it. A quoted {@code port: "${DATABASE_PORT}"} stays a String, and ConfigMe then rejects it
 * for a numeric property, falls back to the default and counts the resource as incomplete - so
 * leave references to numeric and boolean settings unquoted. Only values that survive a round trip
 * unchanged are retyped: {@code 5432} is, {@code 0755} is not.
 *
 * <p>Keys are never substituted: a key is a path segment, and rewriting one would move the value
 * somewhere ConfigMe does not look.
 *
 * <p>Resolution happens on read only. Writing is inherited from ConfigMe untouched - which matters
 * because this fork also removes the config write-back: were it still there, the expanded values,
 * the real password among them, would be written to disk on the first version bump.
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
            // Parsing is split into compose and construct on purpose: substitution has to run on the
            // node tree, where a scalar still knows whether it was quoted - and that is what decides
            // the type an expanded value arrives as.
            Node document = new Yaml(new LoaderOptions()).compose(reader);
            @SuppressWarnings("unchecked")
            Map<Object, Object> rootMap = document == null
                    ? null
                    : (Map<Object, Object>) new SubstitutingConstructor().construct(document);
            return normalizeMap(rootMap, splitDotPaths);
        } catch (IOException e) {
            throw new ConfigMeException("Could not read file '" + path + "'", e);
        } catch (ClassCastException e) {
            throw new ConfigMeException("Top-level is not a map in '" + path + "'", e);
        } catch (YAMLException e) {
            throw new ConfigMeException("YAML error while trying to load file '" + path + "'", e);
        }
    }

    /**
     * Rewrites the composed node tree before construction, expanding every scalar value.
     */
    private static final class SubstitutingConstructor extends SafeConstructor {

        /**
         * A decimal integer whose {@code toString} is byte-for-byte what was read. Anything else -
         * {@code 0755}, {@code 1_000}, {@code 0x1F}, {@code -0} - stays a string: YAML 1.1 would
         * turn {@code 0755} into 493, and a value written back would no longer be the one supplied.
         */
        private static final Pattern CANONICAL_INT = Pattern.compile("0|-?[1-9][0-9]*");
        private static final Pattern CANONICAL_FLOAT = Pattern.compile("-?(?:0|[1-9][0-9]*)\\.[0-9]+");

        private SubstitutingConstructor() {
            super(new LoaderOptions());
        }

        /**
         * Expands the tree and builds the document from it.
         *
         * <p>{@code constructDocument} is final in SnakeYAML, so the expansion cannot be hooked into
         * construction - it happens here, before it.
         */
        private @Nullable Object construct(@NotNull Node document) {
            return constructDocument(substitute(document));
        }

        private static @NotNull Node substitute(@NotNull Node node) {
            if (node instanceof MappingNode mapping) {
                // NodeTuple is immutable, so a mapping is rebuilt rather than patched in place.
                List<NodeTuple> tuples = new ArrayList<>(mapping.getValue().size());
                for (NodeTuple tuple : mapping.getValue()) {
                    // Keys are never substituted: a key is a path segment, and rewriting one would
                    // move the value somewhere ConfigMe does not look for it.
                    tuples.add(new NodeTuple(tuple.getKeyNode(), substitute(tuple.getValueNode())));
                }
                mapping.setValue(tuples);
                return mapping;
            }

            if (node instanceof SequenceNode sequence) {
                // SequenceNode has no setter for its value: the list itself is patched.
                List<Node> values = sequence.getValue();
                for (int index = 0; index < values.size(); index++) {
                    values.set(index, substitute(values.get(index)));
                }
                return sequence;
            }

            if (!(node instanceof ScalarNode scalar)) {
                return node;
            }

            String substituted = EnvironmentSubstitutor.substitute(scalar.getValue(), System::getenv);
            if (substituted == null) {
                return node;
            }

            Tag tag = scalar.isPlain() && Tag.STR.equals(scalar.getTag())
                    ? implicitTag(substituted)
                    : scalar.getTag();
            return new ScalarNode(tag, substituted, scalar.getStartMark(), scalar.getEndMark(), scalar.getScalarStyle());
        }

        /**
         * Resolves the implicit YAML tag of an expanded scalar, but only where the value survives a
         * round trip unchanged. Everything else stays a string.
         */
        private static @NotNull Tag implicitTag(@NotNull String value) {
            if (value.isEmpty()) {
                return Tag.STR; // an empty expansion is an empty string, not null
            }
            if (CANONICAL_INT.matcher(value).matches()) {
                return Tag.INT;
            }
            if (value.equals("true") || value.equals("false")) {
                return Tag.BOOL; // not "yes"/"on": those would come back as "true" on the next save
            }
            if (CANONICAL_FLOAT.matcher(value).matches()) {
                return Tag.FLOAT;
            }
            return Tag.STR;
        }
    }
}
// XMine end - подстановка переменных среды
