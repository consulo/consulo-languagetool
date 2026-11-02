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

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

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

    /**
     * Codes of the selected languages, also of languages which are not installed now, so the selection survives reinstalling a plugin.
     *
     * @return codes of the selected languages, empty for all installed languages
     */
    public Set<String> getLanguages() {
        return myLanguages;
    }

    public void setLanguages(Set<String> languages) {
        myLanguages = Collections.unmodifiableSet(new TreeSet<>(languages));
    }

    @Override
    public LanguageToolSettingsState getState() {
        LanguageToolSettingsState state = new LanguageToolSettingsState();
        Set<String> languages = myLanguages;
        state.languages = languages.isEmpty() ? null : new TreeSet<>(languages);
        return state;
    }

    @Override
    public void loadState(LanguageToolSettingsState state) {
        Set<String> languages = state.languages;
        setLanguages(languages == null ? Set.of() : languages);
    }
}
