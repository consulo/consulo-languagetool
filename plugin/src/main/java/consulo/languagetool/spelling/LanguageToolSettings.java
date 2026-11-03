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
import consulo.component.persist.PersistentStateComponent;
import consulo.component.persist.State;
import consulo.component.persist.Storage;
import jakarta.inject.Singleton;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Application-level settings of the LanguageTool spellchecker engine.
 *
 * @author VISTALL
 * @since 2026-03-28
 */
@Singleton
@State(name = "LanguageToolSettings", storages = @Storage("languagetool"))
@ServiceAPI(ComponentScope.APPLICATION)
@ServiceImpl
public class LanguageToolSettings implements PersistentStateComponent<LanguageToolSettingsState> {
    private volatile Set<String> myLanguages = Set.of();
    private volatile Set<String> myDisabledRules = Set.of();
    private volatile Set<String> myEnabledRules = Set.of();

    private final AtomicLong myModificationCount = new AtomicLong();

    /**
     * Codes of the selected languages, also of languages which are not installed now, so the selection survives reinstalling a plugin.
     *
     * @return codes of the selected languages, empty for all installed languages
     */
    public Set<String> getLanguages() {
        return myLanguages;
    }

    public void setLanguages(Set<String> languages) {
        myLanguages = copy(languages);
        myModificationCount.incrementAndGet();
    }

    /**
     * @return rules turned off by the user, as {@code languageCode:ruleId}
     */
    public Set<String> getDisabledRules() {
        return myDisabledRules;
    }

    /**
     * @return rules which are off by default and turned on by the user, as {@code languageCode:ruleId}
     */
    public Set<String> getEnabledRules() {
        return myEnabledRules;
    }

    public void setRules(Set<String> disabledRules, Set<String> enabledRules) {
        myDisabledRules = copy(disabledRules);
        myEnabledRules = copy(enabledRules);
        myModificationCount.incrementAndGet();
    }

    public void disableRule(String rule) {
        Set<String> disabled = new TreeSet<>(myDisabledRules);
        disabled.add(rule);
        Set<String> enabled = new TreeSet<>(myEnabledRules);
        enabled.remove(rule);
        setRules(disabled, enabled);
    }

    /**
     * @return a counter which changes with every change of the options
     */
    public long getModificationCount() {
        return myModificationCount.get();
    }

    @Override
    public LanguageToolSettingsState getState() {
        LanguageToolSettingsState state = new LanguageToolSettingsState();
        state.languages = toState(myLanguages);
        state.disabledRules = toState(myDisabledRules);
        state.enabledRules = toState(myEnabledRules);
        return state;
    }

    @Override
    public void loadState(LanguageToolSettingsState state) {
        myLanguages = copy(state.languages);
        myDisabledRules = copy(state.disabledRules);
        myEnabledRules = copy(state.enabledRules);
        myModificationCount.incrementAndGet();
    }

    private static Set<String> copy(@Nullable Set<String> values) {
        return values == null || values.isEmpty() ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(values));
    }

    private static @Nullable Set<String> toState(Set<String> values) {
        return values.isEmpty() ? null : new TreeSet<>(values);
    }
}
