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

import consulo.annotation.component.ExtensionImpl;
import consulo.application.Application;
import consulo.component.extension.ExtensionPointCacheKey;
import consulo.configurable.UnnamedConfigurable;
import consulo.document.util.TextRange;
import consulo.language.spellchecker.editor.SpellcheckerEngine;
import consulo.languagetool.spelling.internal.LanguageToolRuntime;
import consulo.language.spellchecker.editor.SpellcheckerFeature;
import consulo.language.spellchecker.editor.SpellcheckerTextProblem;
import consulo.language.spellcheker.text.TextContent;
import consulo.languagetool.spelling.localize.LanguageToolSpellingLocalize;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * LanguageTool-based {@link SpellcheckerEngine}. The language is chosen in the engine options of
 * <em>Settings | Editor | Spellchecking</em>; languages come from the installed {@link LanguageToolLanguageProvider}s.
 *
 * @author VISTALL
 * @since 2026-03-28
 */
@ExtensionImpl
public class LanguageToolSpellcheckerEngine implements SpellcheckerEngine {
    public static final String ID = "language-tool";

    /**
     * Locales of all installed language plugins, rebuilt when language plugins are installed or removed.
     */
    private static final ExtensionPointCacheKey<LanguageToolLanguageProvider, Set<Locale>> LOCALES =
        ExtensionPointCacheKey.create("LanguageToolLocales", walker -> {
            Set<Locale> locales = new HashSet<>();
            walker.walk(provider -> locales.addAll(provider.getLocales()));
            return Set.copyOf(locales);
        });

    private final Application myApplication;
    private final LanguageToolClient myClient;
    private final LanguageToolSettings mySettings;

    @Inject
    public LanguageToolSpellcheckerEngine(Application application, LanguageToolClient client, LanguageToolSettings settings) {
        myApplication = application;
        myClient = client;
        mySettings = settings;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public LocalizeValue getDisplayName() {
        return LanguageToolSpellingLocalize.engineDisplayName();
    }

    @Override
    public Set<SpellcheckerFeature> getFeatures() {
        return EnumSet.of(SpellcheckerFeature.SPELLING, SpellcheckerFeature.GRAMMAR, SpellcheckerFeature.STYLE);
    }

    @Override
    public List<SpellcheckerTextProblem> checkText(Project project, TextContent text) {
        List<SpellcheckerTextProblem> problems = new ArrayList<>();
        for (LanguageToolRuntime.TextMatch match : myClient.checkText(text.toString(), mySettings.getLanguages())) {
            if (match.from() < 0 || match.to() > text.length() || match.from() >= match.to()) {
                continue;
            }
            problems.add(new SpellcheckerTextProblem(
                match.style() ? SpellcheckerFeature.STYLE : SpellcheckerFeature.GRAMMAR,
                new TextRange(match.from(), match.to()),
                LocalizeValue.of(match.message()),
                match.suggestions(),
                match.language() + ":" + match.ruleId()
            ));
        }
        return problems;
    }

    @Override
    public Set<Locale> getLocales() {
        return myApplication.getExtensionPoint(LanguageToolLanguageProvider.class).getOrBuildCache(LOCALES);
    }

    @Override
    public boolean hasProblem(Project project, String word) {
        return myClient.hasProblem(word, mySettings.getLanguages());
    }

    @Override
    public List<String> getSuggestions(Project project, String word) {
        return myClient.getSuggestions(word, mySettings.getLanguages());
    }

    @Override
    public LocalizeValue getRuleDisplayName(String ruleId) {
        int separator = ruleId.indexOf(':');
        if (separator < 0) {
            return LocalizeValue.empty();
        }
        String description = myClient.getRuleDescription(ruleId.substring(0, separator), ruleId.substring(separator + 1));
        return description == null ? LocalizeValue.empty() : LocalizeValue.of(description);
    }

    @Override
    public void disableRule(String ruleId) {
        mySettings.disableRule(ruleId);
    }

    @Override
    public long getModificationCount() {
        return mySettings.getModificationCount();
    }

    @Override
    public UnnamedConfigurable createConfigurable() {
        return new LanguageToolEngineConfigurable(myClient, mySettings);
    }
}
