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

import org.languagetool.Language;
import org.languagetool.Languages;

import java.util.Collection;
import java.util.List;

/**
 * Access to the global language registry of LanguageTool ({@link Languages}).
 * <p>
 * Languages of removed plugins are unregistered here, so the registry neither returns them nor keeps their classloaders.
 *
 * @author VISTALL
 * @since 2026-11-02
 */
public final class LanguageToolLanguages {
    private LanguageToolLanguages() {
    }

    /**
     * @return the registered instance of the language class; an instance of a same named class from another classloader is replaced
     */
    public static Language getOrAdd(Class<? extends Language> languageClass) {
        synchronized (Languages.class) {
            Language language = Languages.getOrAddLanguageByClassName(languageClass.getName());
            if (language.getClass() == languageClass) {
                return language;
            }

            unregister(List.of(language));
            return Languages.getOrAddLanguageByClassName(languageClass.getName());
        }
    }

    public static void unregister(Collection<Language> languages) {
        synchronized (Languages.class) {
            for (Language language : languages) {
                Languages.removeLanguage(language);
            }
        }
    }
}
