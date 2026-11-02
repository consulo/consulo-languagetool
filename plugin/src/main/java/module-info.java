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

import org.jspecify.annotations.NullMarked;

/**
 * @author VISTALL
 * @since 2026-03-28
 */
@NullMarked
module consulo.language.tool.spelling {
    requires consulo.annotation;
    requires consulo.application.api;
    requires consulo.component.api;
    requires consulo.configurable.api;
    requires consulo.disposer.api;
    requires consulo.language.api;
    requires consulo.language.spellchecker.api;
    requires consulo.language.spellchecker.editor.api;
    requires consulo.localize.api;
    requires consulo.logging.api;
    requires consulo.project.api;
    requires consulo.ui.api;
    requires consulo.util.lang;
    requires jakarta.inject;

    // Explicit modules from the Consulo LanguageTool fork.
    // requires transitive so language-plugin modules can use Language in their API
    // without re-declaring it.
    requires transitive org.languagetool.core;
    requires org.languagetool.language.en;    // bundled en-US

    exports consulo.languagetool.spelling;
    exports consulo.languagetool.spelling.localize;

    opens consulo.languagetool.spelling to consulo.util.xml.serializer, consulo.component.impl;
}
