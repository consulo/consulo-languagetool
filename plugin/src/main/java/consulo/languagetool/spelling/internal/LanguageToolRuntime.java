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

import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.ProgressManager;
import consulo.component.ProcessCanceledException;
import consulo.languagetool.spelling.LanguageToolLanguageProvider;
import consulo.languagetool.spelling.LanguageToolSettings;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * All LanguageTool state built for one set of installed language plugins: languages, spellers, grammar tools and the word cache.
 * <p>
 * When the providers change, the client builds a new instance and {@link #dispose() disposes} this one.
 * <p>
 * {@link JLanguageTool} is not thread-safe, so all checks are serialised. Checks run inside read actions, so a waiting check and a
 * running grammar check stop when their progress is canceled, otherwise a pending write action waits for every queued check.
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
    public record TextMatch(String language, boolean style, int from, int to, String message, List<String> suggestions, String ruleId) {
    }

    /**
     * A grammar or style rule of a language, for the rule options.
     */
    public record RuleInfo(String id, String description, String category, boolean enabledByDefault) {
    }

    private final List<LanguageToolLanguageProvider> myProviders;
    private final LanguageToolSettings mySettings;
    private final List<ClassLoader> myClassLoaders;

    private final ReentrantLock myLock = new ReentrantLock();

    /**
     * Installed languages by code, created on first use. Guarded by {@link #myLock}.
     */
    private @Nullable Map<String, Language> myLanguages;

    /**
     * Key: "languages:word".
     */
    private final Map<String, CheckResult> myCache = new ConcurrentHashMap<>();

    /**
     * One speller per language code; {@code null} when the language has no speller. Guarded by {@link #myLock}.
     */
    private final Map<String, @Nullable SpellerTool> myTools = new HashMap<>();

    /**
     * One grammar tool per language code (spelling and picky rules disabled); {@code null} when not available. Guarded by {@link #myLock}.
     */
    private final Map<String, @Nullable JLanguageTool> myGrammarTools = new HashMap<>();

    /**
     * Rule descriptions by language code and rule id, of the grammar tools created so far.
     */
    private final Map<String, Map<String, String>> myRuleDescriptions = new ConcurrentHashMap<>();

    /**
     * Settings modification count the grammar tools were built for. Guarded by {@link #myLock}.
     */
    private long myRulesModificationCount = -1;

    private boolean myDisposed;

    public LanguageToolRuntime(List<LanguageToolLanguageProvider> providers, LanguageToolSettings settings) {
        myProviders = List.copyOf(providers);
        mySettings = settings;

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
    public List<Language> getLoadedLanguages() {
        myLock.lock();
        try {
            Map<String, Language> languages = myLanguages;
            return languages == null ? List.of() : List.copyOf(languages.values());
        }
        finally {
            myLock.unlock();
        }
    }

    public List<Language> getLanguages() {
        myLock.lock();
        try {
            return List.copyOf(getLanguageMap().values());
        }
        finally {
            myLock.unlock();
        }
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
    public void dispose() {
        myLock.lock();
        try {
            myDisposed = true;
            myCache.clear();
            myTools.clear();
            myGrammarTools.clear();
            myRuleDescriptions.clear();
            myLanguages = Map.of();
        }
        finally {
            myLock.unlock();
        }
    }

    private CheckResult getOrCheck(String word, Set<String> languages) {
        String key = String.join(",", languages) + ":" + word;
        CheckResult result = myCache.get(key);
        if (result != null) {
            return result;
        }
        return performCheck(key, word, languages);
    }

    private CheckResult performCheck(String key, String word, Set<String> languages) {
        lockCancelable();
        try {
            CheckResult result = myCache.get(key);
            if (result != null) {
                return result;
            }
            result = check(word, languages);
            if (!myDisposed) {
                if (myCache.size() >= MAX_CACHE_SIZE) {
                    myCache.clear();
                }
                myCache.put(key, result);
            }
            return result;
        }
        finally {
            myLock.unlock();
        }
    }

    /**
     * Waits for the lock while the current progress is not canceled.
     *
     * @throws ProcessCanceledException when the progress is canceled before the lock is taken
     */
    private void lockCancelable() {
        while (true) {
            ProgressManager.checkCanceled();
            try {
                if (myLock.tryLock(10, TimeUnit.MILLISECONDS)) {
                    return;
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProcessCanceledException(e);
            }
        }
    }

    private static boolean isCanceled() {
        ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
        return indicator != null && indicator.isCanceled();
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
    public List<TextMatch> checkText(String text, Set<String> languages) {
        lockCancelable();
        try {
            return doCheckText(text, languages);
        }
        finally {
            myLock.unlock();
        }
    }

    private List<TextMatch> doCheckText(String text, Set<String> languages) {
        long modificationCount = mySettings.getModificationCount();
        if (modificationCount != myRulesModificationCount) {
            // enabled and disabled rules are applied when a grammar tool is created
            myGrammarTools.clear();
            myRulesModificationCount = modificationCount;
        }

        String code = detectLanguage(text, getCheckedCodes(languages));
        if (code == null) {
            return List.of();
        }
        JLanguageTool tool = getOrCreateGrammarTool(code);
        if (tool == null) {
            return List.of();
        }

        try {
            List<RuleMatch> matches = tool.check(text);
            ProgressManager.checkCanceled();

            List<TextMatch> result = new ArrayList<>();
            for (RuleMatch match : matches) {
                Rule rule = match.getRule();
                String message = match.getShortMessage();
                if (message == null || message.isEmpty()) {
                    message = match.getMessage();
                }
                List<String> suggestions = match.getSuggestedReplacements();
                result.add(new TextMatch(
                    code,
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

    /**
     * @return grammar and style rules of the language, without spelling rules; loads the language when needed
     */
    public List<RuleInfo> getRules(String code) {
        myLock.lock();
        try {
            JLanguageTool tool = getOrCreateGrammarTool(code);
            if (tool == null) {
                return List.of();
            }
            Map<String, RuleInfo> rules = new LinkedHashMap<>();
            for (Rule rule : tool.getAllRules()) {
                if (rule instanceof SpellingCheckRule || rules.containsKey(rule.getId())) {
                    continue;
                }
                boolean enabledByDefault = !rule.isDefaultOff() && !rule.getCategory().isDefaultOff() && !rule.hasTag(Tag.picky);
                rules.put(rule.getId(), new RuleInfo(rule.getId(), rule.getDescription(), rule.getCategory().getName(), enabledByDefault));
            }
            return List.copyOf(rules.values());
        }
        finally {
            myLock.unlock();
        }
    }

    /**
     * @return the description of a rule of an already loaded language
     */
    public @Nullable String getRuleDescription(String code, String ruleId) {
        Map<String, String> descriptions = myRuleDescriptions.get(code);
        return descriptions == null ? null : descriptions.get(ruleId);
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
                tool.setCheckCancelledCallback(LanguageToolRuntime::isCanceled);
                // spelling is checked per word by the speller; picky rules are too noisy for code
                for (Rule rule : tool.getAllSpellingCheckRules()) {
                    tool.disableRule(rule.getId());
                }
                for (Rule rule : tool.getAllActiveRules()) {
                    if (rule.hasTag(Tag.picky)) {
                        tool.disableRule(rule.getId());
                    }
                }
                String prefix = code + ":";
                for (String rule : mySettings.getEnabledRules()) {
                    if (rule.startsWith(prefix)) {
                        tool.enableRule(rule.substring(prefix.length()));
                    }
                }
                for (String rule : mySettings.getDisabledRules()) {
                    if (rule.startsWith(prefix)) {
                        tool.disableRule(rule.substring(prefix.length()));
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
            if (tool != null && !myRuleDescriptions.containsKey(code)) {
                Map<String, String> descriptions = new HashMap<>();
                for (Rule rule : tool.getAllRules()) {
                    String description = rule.getDescription();
                    if (description != null) {
                        descriptions.putIfAbsent(rule.getId(), description);
                    }
                }
                myRuleDescriptions.put(code, Map.copyOf(descriptions));
            }
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
