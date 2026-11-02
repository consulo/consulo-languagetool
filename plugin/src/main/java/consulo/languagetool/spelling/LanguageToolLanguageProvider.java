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
import consulo.annotation.component.ExtensionAPI;
import consulo.languagetool.spelling.internal.LanguageToolLanguages;
import org.languagetool.Language;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Supplies LanguageTool {@link Language}s from a language plugin.
 * <p>
 * The plugin classloader of the implementation must see the LanguageTool language jar: LanguageTool loads language classes, rules and
 * dictionaries through it.
 *
 * @author VISTALL
 * @since 2026-03-28
 */
@ExtensionAPI(ComponentScope.APPLICATION)
public interface LanguageToolLanguageProvider {
    /**
     * Implementations must reuse LanguageTool's registered instances via {@link #getLanguage(Class)}.
     */
    List<Language> getLanguages();

    /**
     * Locales of {@link #getLanguages()}, known without loading LanguageTool. Used for spellchecker engine selection,
     * also while LanguageTool is not the active engine.
     */
    Set<Locale> getLocales();

    static Language getLanguage(Class<? extends Language> languageClass) {
        return LanguageToolLanguages.getOrAdd(languageClass);
    }
}
