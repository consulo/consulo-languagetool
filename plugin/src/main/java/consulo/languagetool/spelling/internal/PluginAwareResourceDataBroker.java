/*
 * Copyright 2013-2026 consulo.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package consulo.languagetool.spelling.internal;

import org.jspecify.annotations.Nullable;
import org.languagetool.broker.ResourceDataBroker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.function.Supplier;

/**
 * A {@link ResourceDataBroker} which searches resources in the classloaders of the installed language plugins before falling back
 * to the default broker (the classpath of languagetool-core).
 * <p>
 * The classloaders are requested on every lookup, so the broker never holds a classloader of an unloaded plugin.
 *
 * @author VISTALL
 * @since 2026-03-28
 */
public class PluginAwareResourceDataBroker implements ResourceDataBroker {
    private final Supplier<List<ClassLoader>> myClassLoaders;
    private final ResourceDataBroker myDefault;

    public PluginAwareResourceDataBroker(Supplier<List<ClassLoader>> classLoaders, ResourceDataBroker defaultBroker) {
        myClassLoaders = classLoaders;
        myDefault = defaultBroker;
    }

    @Override
    public @Nullable InputStream getAsStream(String path) {
        String classLoaderPath = stripLeadingSlash(path);
        for (ClassLoader classLoader : myClassLoaders.get()) {
            InputStream stream = classLoader.getResourceAsStream(classLoaderPath);
            if (stream != null) {
                return stream;
            }
        }
        return myDefault.getAsStream(path);
    }

    @Override
    public @Nullable URL getAsURL(String path) {
        String classLoaderPath = stripLeadingSlash(path);
        for (ClassLoader classLoader : myClassLoaders.get()) {
            URL url = classLoader.getResource(classLoaderPath);
            if (url != null) {
                return url;
            }
        }
        return myDefault.getAsURL(path);
    }

    /**
     * @return all matching URLs of every language plugin and of languagetool-core, grammar rules may be split across several jars
     */
    @Override
    public List<URL> getAsURLs(String path) {
        String classLoaderPath = stripLeadingSlash(path);
        List<URL> urls = new ArrayList<>();
        for (ClassLoader classLoader : myClassLoaders.get()) {
            try {
                Enumeration<URL> resources = classLoader.getResources(classLoaderPath);
                while (resources.hasMoreElements()) {
                    urls.add(resources.nextElement());
                }
            }
            catch (IOException ignored) {
            }
        }
        urls.addAll(myDefault.getAsURLs(path));
        return urls;
    }

    @Override
    public InputStream getFromResourceDirAsStream(String path) {
        return Objects.requireNonNull(getAsStream(joinPaths(RESOURCE_DIR, path)), "Missing resource: " + path);
    }

    @Override
    public URL getFromResourceDirAsUrl(String path) {
        return Objects.requireNonNull(getAsURL(joinPaths(RESOURCE_DIR, path)), "Missing resource: " + path);
    }

    @Override
    public List<URL> getFromResourceDirAsUrls(String path) {
        return getAsURLs(joinPaths(RESOURCE_DIR, path));
    }

    @Override
    public List<String> getFromResourceDirAsLines(String path) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(getFromResourceDirAsStream(path), StandardCharsets.UTF_8))) {
            return reader.lines().toList();
        }
        catch (IOException e) {
            return List.of();
        }
    }

    @Override
    public boolean resourceExists(String path) {
        return getAsURL(joinPaths(RESOURCE_DIR, path)) != null;
    }

    @Override
    public InputStream getFromRulesDirAsStream(String path) {
        return Objects.requireNonNull(getAsStream(joinPaths(RULES_DIR, path)), "Missing resource: " + path);
    }

    @Override
    public URL getFromRulesDirAsUrl(String path) {
        return Objects.requireNonNull(getAsURL(joinPaths(RULES_DIR, path)), "Missing resource: " + path);
    }

    @Override
    public boolean ruleFileExists(String path) {
        return getAsURL(joinPaths(RULES_DIR, path)) != null;
    }

    @Override
    public String getResourceDir() {
        return RESOURCE_DIR;
    }

    @Override
    public String getRulesDir() {
        return RULES_DIR;
    }

    @Override
    public ResourceBundle getResourceBundle(String baseName, Locale locale) {
        for (ClassLoader classLoader : myClassLoaders.get()) {
            try {
                return ResourceBundle.getBundle(baseName, locale, classLoader);
            }
            catch (MissingResourceException ignored) {
            }
        }
        return myDefault.getResourceBundle(baseName, locale);
    }

    private static String stripLeadingSlash(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private static String joinPaths(String base, String segment) {
        return segment.startsWith("/") ? base + segment : base + "/" + segment;
    }
}
