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

import consulo.configurable.UnnamedConfigurable;
import consulo.disposer.Disposable;
import consulo.languagetool.spelling.localize.LanguageToolSpellingLocalize;
import consulo.localize.LocalizeValue;
import consulo.ui.Component;
import consulo.ui.MultiSelectComboBox;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.layout.LabeledLayout;
import consulo.ui.layout.VerticalLayout;
import org.jspecify.annotations.Nullable;
import org.languagetool.Language;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Options of the LanguageTool engine: the checked languages, from the installed language plugins. No selection means all languages.
 *
 * @author VISTALL
 * @since 2026-11-01
 */
class LanguageToolEngineConfigurable implements UnnamedConfigurable {
    private final LanguageToolClient myClient;
    private final LanguageToolSettings mySettings;

    private @Nullable MultiSelectComboBox<String> myLanguagesBox;

    LanguageToolEngineConfigurable(LanguageToolClient client, LanguageToolSettings settings) {
        myClient = client;
        mySettings = settings;
    }

    @RequiredUIAccess
    @Override
    public Component createUIComponent(Disposable parentDisposable) {
        List<String> codes = new ArrayList<>();
        Map<String, LocalizeValue> names = new HashMap<>();
        for (Language language : myClient.getAvailableLanguages()) {
            String code = language.getShortCodeWithCountryAndVariant();
            codes.add(code);
            names.put(code, LocalizeValue.of(language.getName() + " (" + code + ")"));
        }

        for (String selected : mySettings.getLanguages()) {
            if (!codes.contains(selected)) {
                codes.add(selected);
                names.put(selected, LanguageToolSpellingLocalize.settingsLanguageNotInstalled(selected));
            }
        }

        MultiSelectComboBox<String> languagesBox = MultiSelectComboBox.create(codes);
        languagesBox.setTextRenderer(code -> code == null ? LocalizeValue.empty() : names.getOrDefault(code, LocalizeValue.of(code)));
        languagesBox.setSummaryRenderer(selected -> {
            if (selected.isEmpty()) {
                return LanguageToolSpellingLocalize.settingsLanguagesAll();
            }
            List<LocalizeValue> selectedNames = new ArrayList<>();
            for (String code : selected) {
                selectedNames.add(names.getOrDefault(code, LocalizeValue.of(code)));
            }
            return LocalizeValue.join(", ", selectedNames.toArray(LocalizeValue[]::new));
        });
        languagesBox.setPlaceholder(LanguageToolSpellingLocalize.settingsLanguagesAll());
        myLanguagesBox = languagesBox;

        VerticalLayout layout = VerticalLayout.create();
        layout.add(LabeledLayout.create(LanguageToolSpellingLocalize.settingsLanguagesLabel(), languagesBox));
        return layout;
    }

    private Set<String> getSelectedLanguages() {
        MultiSelectComboBox<String> languagesBox = myLanguagesBox;
        return languagesBox == null ? Set.of() : new TreeSet<>(languagesBox.getValue());
    }

    @RequiredUIAccess
    @Override
    public boolean isModified() {
        return myLanguagesBox != null && !getSelectedLanguages().equals(mySettings.getLanguages());
    }

    @RequiredUIAccess
    @Override
    public void apply() {
        if (isModified()) {
            mySettings.setLanguages(getSelectedLanguages());
            myClient.clearCache();
        }
    }

    @RequiredUIAccess
    @Override
    public void reset() {
        MultiSelectComboBox<String> languagesBox = myLanguagesBox;
        if (languagesBox != null) {
            languagesBox.setValue(new ArrayList<>(mySettings.getLanguages()));
        }
    }

    @RequiredUIAccess
    @Override
    public void disposeUIResources() {
        myLanguagesBox = null;
    }
}
