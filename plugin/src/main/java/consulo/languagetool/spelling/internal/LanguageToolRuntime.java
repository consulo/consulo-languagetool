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

import consulo.languagetool.spelling.LanguageToolLanguageProvider;
import consulo.logging.Logger;
import org.jspecify.annotations.Nullable;
import org.languagetool.JLanguageTool;
import org.languagetool.Language;
import org.languagetool.Tag;
import org.languagetool.rules.Categories;
import org.languagetool.rules.ITSIssueType;
import org.languagetool.rules.Rule;
import org.languagetool.rules.RuleMatch;
import org.languagetool.rules.spelling.SpellingCheckRule;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * All LanguageTool state built for one set of installed language plugins: languages, spellers, grammar tools and the word cache.
 * <p>
 * When the providers change, the client builds a new instance and {@link #dispose() disposes} this one.
 * <p>
 * {@link JLanguageTool} is not thread-safe, so all checks are serialised.
 *
 * @author VISTALL
 * @since 2026-11-02
 */
public class LanguageToolRuntime {
    private static final Logger LOG = Logger.getInstance(LanguageToolRuntime.class);

    private static final int MAX_SUGGESTIONS = 8;

    private static final int MAX_CACHE_SIZE = 50_000;

    private record CheckResult(boolean hasProblem, List<String> suggestions) {
        static final CheckResult OK = new CheckResult(false, List.of());
    }

    private record SpellerTool(JLanguageTool tool, SpellingCheckRule speller) {
    }

    /**
     * A grammar or style match in a checked text.
     */
    public record TextMatch(boolean style, int from, int to, String message, List<String> suggestions, String ruleId) {
    }

    private final List<LanguageToolLanguageProvider> myProviders;
    private final List<ClassLoader> myClassLoaders;

    /**
     * Installed languages by code, created on first use. Guarded by {@code this}.
     */
    private @Nullable Map<String, Language> myLanguages;

    /**
     * Key: "languages:word".
     */
    private final Map<String, CheckResult> myCache = new ConcurrentHashMap<>();

    /**
     * One speller per language code; {@code null} when the language has no speller. Guarded by {@code this}.
     */
    private final Map<String, @Nullable SpellerTool> myTools = new HashMap<>();

    /**
     * One grammar tool per language code (spelling and picky rules disabled); {@code null} when not available. Guarded by {@code this}.
     */
    private final Map<String, @Nullable JLanguageTool> myGrammarTools = new HashMap<>();

    private boolean myDisposed;

    public LanguageToolRuntime(List<LanguageToolLanguageProvider> providers) {
        myProviders = List.copyOf(providers);

        Set<ClassLoader> classLoaders = new LinkedHashSet<>();
        for (LanguageToolLanguageProvider provider : myProviders) {
            classLoaders.add(provider.getClass().getClassLoader());
        }
        myClassLoaders = List.copyOf(classLoaders);
    }

    /**
     * @return classloaders of the language plugins, where LanguageTool finds language classes, rules and dictionaries
     */
    public List<ClassLoader> getClassLoaders() {
        return myClassLoaders;
    }

    public boolean hasSameProviders(List<LanguageToolLanguageProvider> providers) {
        if (providers.size() != myProviders.size()) {
            return false;
        }
        for (int i = 0; i < providers.size(); i++) {
            if (providers.get(i) != myProviders.get(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return languages created by this runtime so far, without creating them
     */
    public synchronized List<Language> getLoadedLanguages() {
        Map<String, Language> languages = myLanguages;
        return languages == null ? List.of() : List.copyOf(languages.values());
    }

    public synchronized List<Language> getLanguages() {
        return List.copyOf(getLanguageMap().values());
    }

    /**
     * @param languages codes of the checked languages, empty for all installed languages
     */
    public boolean hasProblem(String word, Set<String> languages) {
        return getOrCheck(word, languages).hasProblem();
    }

    /**
     * @param languages codes of the checked languages, empty for all installed languages
     */
    public List<String> getSuggestions(String word, Set<String> languages) {
        return getOrCheck(word, languages).suggestions();
    }

    public void clearCache() {
        myCache.clear();
    }

    /**
     * Releases all LanguageTool objects. Waits for a running check; later calls find no languages.
     */
    public synchronized void dispose() {
        myDisposed = true;
        myCache.clear();
        myTools.clear();
        myGrammarTools.clear();
        myLanguages = Map.of();
    }

    private CheckResult getOrCheck(String word, Set<String> languages) {
        String key = String.join(",", languages) + ":" + word;
        CheckResult result = myCache.get(key);
        if (result != null) {
            return result;
        }
        return performCheck(key, word, languages);
    }

    private synchronized CheckResult performCheck(String key, String word, Set<String> languages) {
        CheckResult result = check(word, languages);
        if (!myDisposed) {
            if (myCache.size() >= MAX_CACHE_SIZE) {
                myCache.clear();
            }
            myCache.put(key, result);
        }
        return result;
    }

    /**
     * Each language either knows the word (present), reports it (absent), or ignores it as foreign (alien, e.g. another script).
     * The word is correct when any language knows it, a typo when a language reports it, and skipped when all ignore it.
     */
    private CheckResult check(String word, Set<String> languages) {
        Set<String> suggestions = new LinkedHashSet<>();
        boolean absent = false;
        for (String code : getCheckedCodes(languages)) {
            SpellerTool spellerTool = getOrCreateTool(code);
            if (spellerTool == null) {
                continue;
            }

            try {
                if (!spellerTool.speller().isMisspelled(word)) {
                    return CheckResult.OK;
                }

                // no match for a misspelled word: the rule ignores it as foreign
                RuleMatch[] matches = spellerTool.speller().match(spellerTool.tool().getRawAnalyzedSentence(word));
                if (matches.length == 0) {
                    continue;
                }

                absent = true;
                for (RuleMatch match : matches) {
                    for (String replacement : match.getSuggestedReplacements()) {
                        if (suggestions.size() >= MAX_SUGGESTIONS) {
                            break;
                        }
                        suggestions.add(replacement);
                    }
                }
            }
            catch (IOException e) {
                LOG.warn("LanguageTool check failed for '" + word + "' (" + code + "): " + e.getMessage());
            }
        }
        return absent ? new CheckResult(true, List.copyOf(suggestions)) : CheckResult.OK;
    }

    /**
     * Checks grammar and style of a natural language text. The language is the checked one whose speller knows most words of the text.
     *
     * @param languages codes of the checked languages, empty for all installed languages
     */
    public synchronized List<TextMatch> checkText(String text, Set<String> languages) {
        String code = detectLanguage(text, getCheckedCodes(languages));
        JLanguageTool tool = code == null ? null : getOrCreateGrammarTool(code);
        if (tool == null) {
            return List.of();
        }

        try {
            List<TextMatch> result = new ArrayList<>();
            for (RuleMatch match : tool.check(text)) {
                Rule rule = match.getRule();
                String message = match.getShortMessage();
                if (message == null || message.isEmpty()) {
                    message = match.getMessage();
                }
                List<String> suggestions = match.getSuggestedReplacements();
                result.add(new TextMatch(
                    isStyleLike(rule),
                    match.getFromPos(),
                    match.getToPos(),
                    message,
                    List.copyOf(suggestions.size() > MAX_SUGGESTIONS ? suggestions.subList(0, MAX_SUGGESTIONS) : suggestions),
                    rule.getId()
                ));
            }
            return result;
        }
        catch (IOException e) {
            LOG.warn("LanguageTool text check failed: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Selected languages which are installed; all installed languages when nothing is selected or no selected language is installed.
     */
    private Collection<String> getCheckedCodes(Set<String> languages) {
        Map<String, Language> installed = getLanguageMap();
        List<String> codes = new ArrayList<>();
        for (String code : languages) {
            if (installed.containsKey(code)) {
                codes.add(code);
            }
        }
        return codes.isEmpty() ? installed.keySet() : codes;
    }

    private static boolean isStyleLike(Rule rule) {
        return rule.getLocQualityIssueType() == ITSIssueType.Style
            || rule.getCategory().getId().equals(Categories.STYLE.getId())
            || rule.getCategory().getId().equals(Categories.TYPOGRAPHY.getId());
    }

    private @Nullable String detectLanguage(String text, Collection<String> codes) {
        if (codes.size() == 1) {
            return codes.iterator().next();
        }
        String best = null;
        int bestKnown = -1;
        String[] words = text.split("[^\\p{L}']+");
        for (String code : codes) {
            SpellerTool spellerTool = getOrCreateTool(code);
            if (spellerTool == null) {
                continue;
            }
            int known = 0;
            for (String word : words) {
                try {
                    if (!word.isEmpty() && !spellerTool.speller().isMisspelled(word)) {
                        known++;
                    }
                }
                catch (IOException ignored) {
                }
            }
            if (known > bestKnown) {
                best = code;
                bestKnown = known;
            }
        }
        return best;
    }

    private @Nullable JLanguageTool getOrCreateGrammarTool(String code) {
        if (myGrammarTools.containsKey(code)) {
            return myGrammarTools.get(code);
        }

        JLanguageTool tool = null;
        Language language = getLanguageMap().get(code);
        if (language != null) {
            try {
                tool = new JLanguageTool(language);
                // spelling is checked per word by the speller; picky rules are too noisy for code
                for (Rule rule : tool.getAllSpellingCheckRules()) {
                    tool.disableRule(rule.getId());
                }
                for (Rule rule : tool.getAllActiveRules()) {
                    if (rule.hasTag(Tag.picky)) {
                        tool.disableRule(rule.getId());
                    }
                }
                LOG.info("LanguageTool grammar ready for: " + code);
            }
            catch (Exception e) {
                LOG.warn("Failed to init LanguageTool grammar for '" + code + "': " + e.getMessage());
                tool = null;
            }
        }
        if (!myDisposed) {
            myGrammarTools.put(code, tool);
        }
        return tool;
    }

    private @Nullable SpellerTool getOrCreateTool(String code) {
        if (myTools.containsKey(code)) {
            return myTools.get(code);
        }

        SpellerTool spellerTool = null;
        Language language = getLanguageMap().get(code);
        if (language != null) {
            try {
                JLanguageTool tool = new JLanguageTool(language);
                List<SpellingCheckRule> spellers = tool.getAllSpellingCheckRules();
                if (spellers.isEmpty()) {
                    LOG.warn("LanguageTool has no speller for '" + code + "'");
                }
                else {
                    spellerTool = new SpellerTool(tool, spellers.get(0));
                    LOG.info("LanguageTool speller ready for: " + code + " (" + language.getName() + ")");
                }
            }
            catch (Exception e) {
                LOG.warn("Failed to init LanguageTool for '" + code + "': " + e.getMessage());
            }
        }
        if (!myDisposed) {
            myTools.put(code, spellerTool);
        }
        return spellerTool;
    }

    private Map<String, Language> getLanguageMap() {
        Map<String, Language> languages = myLanguages;
        if (languages != null) {
            return languages;
        }

        Map<String, Language> result = new LinkedHashMap<>();
        for (LanguageToolLanguageProvider provider : myProviders) {
            try {
                for (Language language : provider.getLanguages()) {
                    result.putIfAbsent(language.getShortCodeWithCountryAndVariant(), language);
                }
            }
            catch (Exception e) {
                LOG.warn("Failed to load LanguageTool languages of " + provider.getClass().getName() + ": " + e.getMessage());
            }
        }
        languages = Collections.unmodifiableMap(result);
        myLanguages = languages;
        return languages;
    }
}
