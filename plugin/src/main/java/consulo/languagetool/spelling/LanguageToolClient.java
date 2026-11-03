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
package consulo.languagetool.spelling;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.application.Application;
import consulo.application.plugin.PluginActionListener;
import consulo.container.plugin.PluginId;
import consulo.disposer.Disposable;
import consulo.languagetool.spelling.internal.LanguageToolLanguages;
import consulo.languagetool.spelling.internal.LanguageToolRuntime;
import consulo.languagetool.spelling.internal.PluginAwareResourceDataBroker;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;
import org.languagetool.JLanguageTool;
import org.languagetool.Language;
import org.languagetool.broker.ClassBroker;
import org.languagetool.broker.DefaultClassBroker;
import org.languagetool.broker.DefaultResourceDataBroker;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * In-process LanguageTool speller and grammar checker.
 * <p>
 * All LanguageTool state lives in a {@link LanguageToolRuntime}, built for the installed {@link LanguageToolLanguageProvider}s.
 * When plugins change, the providers are visited again: if they differ, a new runtime is built, the previous one is disposed and
 * languages which are no longer provided are unregistered from LanguageTool.
 * <p>
 * LanguageTool finds language classes, rules and dictionaries through global brokers. They are installed once and ask the current
 * runtime for the classloaders of the language plugins on every lookup, so no classloader of a removed plugin is kept.
 *
 * @author VISTALL
 * @since 2026-03-28
 */
@ServiceAPI(ComponentScope.APPLICATION)
@ServiceImpl
@Singleton
public class LanguageToolClient implements Disposable {
    private final Application myApplication;
    private final LanguageToolSettings mySettings;

    private volatile @Nullable LanguageToolRuntime myRuntime;

    private boolean myBrokersInstalled;

    @Inject
    public LanguageToolClient(Application application, LanguageToolSettings settings) {
        myApplication = application;
        mySettings = settings;

        application.getMessageBus().connect(this).subscribe(PluginActionListener.class, new PluginActionListener() {
            @Override
            public void pluginsInstalled(PluginId[] pluginIds) {
                refresh();
            }

            @Override
            public void pluginsUninstalled(PluginId[] pluginIds) {
                refresh();
            }
        });
    }

    /**
     * @param languages codes of the checked languages, empty for all installed languages
     */
    public boolean hasProblem(String word, Set<String> languages) {
        return getRuntime().hasProblem(word, languages);
    }

    /**
     * @param languages codes of the checked languages, empty for all installed languages
     */
    public List<String> getSuggestions(String word, Set<String> languages) {
        return getRuntime().getSuggestions(word, languages);
    }

    /**
     * @param languages codes of the checked languages, empty for all installed languages
     */
    public List<LanguageToolRuntime.TextMatch> checkText(String text, Set<String> languages) {
        return getRuntime().checkText(text, languages);
    }

    /**
     * @return languages of all installed {@link LanguageToolLanguageProvider}s
     */
    public List<Language> getAvailableLanguages() {
        return getRuntime().getLanguages();
    }

    /**
     * @return grammar and style rules of the language; loads the language when needed
     */
    public List<LanguageToolRuntime.RuleInfo> getRules(String languageCode) {
        return getRuntime().getRules(languageCode);
    }

    public @Nullable String getRuleDescription(String languageCode, String ruleId) {
        return getRuntime().getRuleDescription(languageCode, ruleId);
    }

    public void clearCache() {
        LanguageToolRuntime runtime = myRuntime;
        if (runtime != null) {
            runtime.clearCache();
        }
    }

    private LanguageToolRuntime getRuntime() {
        LanguageToolRuntime runtime = myRuntime;
        if (runtime != null) {
            return runtime;
        }
        synchronized (this) {
            runtime = myRuntime;
            if (runtime == null) {
                installBrokers();
                runtime = new LanguageToolRuntime(getProviders(), mySettings);
                myRuntime = runtime;
            }
            return runtime;
        }
    }

    /**
     * Visits the providers again; when they differ from the providers of the current runtime, replaces the runtime. Nothing is loaded
     * when LanguageTool was not used yet.
     */
    private void refresh() {
        LanguageToolRuntime previous;
        LanguageToolRuntime runtime;
        synchronized (this) {
            previous = myRuntime;
            List<LanguageToolLanguageProvider> providers = getProviders();
            if (previous == null || previous.hasSameProviders(providers)) {
                return;
            }
            runtime = new LanguageToolRuntime(providers, mySettings);
            myRuntime = runtime;
        }

        List<Language> removed = new ArrayList<>(previous.getLoadedLanguages());
        List<Language> current = runtime.getLanguages();
        removed.removeIf(language -> current.stream().anyMatch(it -> it == language));
        previous.dispose();
        LanguageToolLanguages.unregister(removed);
    }

    private List<LanguageToolLanguageProvider> getProviders() {
        List<LanguageToolLanguageProvider> providers = new ArrayList<>();
        myApplication.getExtensionPoint(LanguageToolLanguageProvider.class).forEach(providers::add);
        return providers;
    }

    /**
     * Must be called before LanguageTool creates any language, since languages read the brokers on creation.
     */
    private void installBrokers() {
        if (myBrokersInstalled) {
            return;
        }

        JLanguageTool.setDataBroker(new PluginAwareResourceDataBroker(this::getClassLoaders, new DefaultResourceDataBroker()));
        ClassBroker defaultClassBroker = new DefaultClassBroker();
        JLanguageTool.setClassBrokerBroker(qualifiedName -> {
            for (ClassLoader classLoader : getClassLoaders()) {
                try {
                    return Class.forName(qualifiedName, false, classLoader);
                }
                catch (ClassNotFoundException ignored) {
                }
            }
            return defaultClassBroker.forName(qualifiedName);
        });
        myBrokersInstalled = true;
    }

    private List<ClassLoader> getClassLoaders() {
        LanguageToolRuntime runtime = myRuntime;
        return runtime == null ? List.of() : runtime.getClassLoaders();
    }

    @Override
    public void dispose() {
        LanguageToolRuntime runtime;
        synchronized (this) {
            runtime = myRuntime;
            myRuntime = null;
            if (myBrokersInstalled) {
                JLanguageTool.setDataBroker(new DefaultResourceDataBroker());
                JLanguageTool.setClassBrokerBroker(new DefaultClassBroker());
                myBrokersInstalled = false;
            }
        }
        if (runtime != null) {
            runtime.dispose();
        }
    }
}
