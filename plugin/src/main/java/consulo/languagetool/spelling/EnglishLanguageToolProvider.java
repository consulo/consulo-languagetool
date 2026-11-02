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
import org.languagetool.Language;
import org.languagetool.language.en.AmericanEnglish;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Provides American English (en-US) as the bundled LanguageTool language.
 *
 * @author VISTALL
 * @since 2026-03-28
 */
@ExtensionImpl
public class EnglishLanguageToolProvider implements LanguageToolLanguageProvider {

    @Override
    public List<Language> getLanguages() {
        return List.of(LanguageToolLanguageProvider.getLanguage(AmericanEnglish.class));
    }

    @Override
    public Set<Locale> getLocales() {
        return Set.of(Locale.US);
    }
}
