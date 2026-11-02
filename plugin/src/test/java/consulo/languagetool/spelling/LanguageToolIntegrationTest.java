package consulo.languagetool.spelling;

import consulo.languagetool.spelling.internal.LanguageToolLanguages;
import consulo.languagetool.spelling.internal.PluginAwareResourceDataBroker;
import org.junit.Test;
import org.languagetool.JLanguageTool;
import org.languagetool.Language;
import org.languagetool.Languages;
import org.languagetool.broker.DefaultResourceDataBroker;
import org.languagetool.broker.ResourceDataBroker;
import org.languagetool.language.en.AmericanEnglish;
import org.languagetool.rules.spelling.SpellingCheckRule;

import java.io.InputStream;
import java.util.List;

import static org.junit.Assert.*;

public class LanguageToolIntegrationTest {
    private PluginAwareResourceDataBroker broker() {
        return new PluginAwareResourceDataBroker(() -> List.of(getClass().getClassLoader()), new DefaultResourceDataBroker());
    }

    @Test
    public void findsBundledDictionaryInResourceDirectory() throws Exception {
        PluginAwareResourceDataBroker broker = broker();
        assertTrue(broker.resourceExists("/en/english.dict"));
        try (InputStream stream = broker.getFromResourceDirAsStream("/en/english.dict")) {
            assertNotEquals(-1, stream.read());
        }
    }

    @Test
    public void reportsMissingResourcesWithoutThrowing() {
        PluginAwareResourceDataBroker broker = broker();
        assertFalse(broker.resourceExists("/en/does-not-exist.dict"));
        assertFalse(broker.ruleFileExists("/en/does-not-exist.xml"));
    }

    @Test
    public void reusesRegisteredLanguageInstances() {
        EnglishLanguageToolProvider provider = new EnglishLanguageToolProvider();
        assertSame(provider.getLanguages().get(0), provider.getLanguages().get(0));
        assertSame(provider.getLanguages().get(0), new EnglishLanguageToolProvider().getLanguages().get(0));
    }

    @Test
    public void unregistersOnlyDynamicLanguages() {
        Language language = LanguageToolLanguages.getOrAdd(AmericanEnglish.class);
        boolean dynamic = !Languages.get().contains(language) || Languages.removeLanguage(language);
        if (dynamic) {
            assertFalse(Languages.get().stream().anyMatch(it -> it == language));
        }
        else {
            // found on the classpath on startup: never removed
            assertTrue(Languages.get().stream().anyMatch(it -> it == language));
        }
        assertSame(LanguageToolLanguages.getOrAdd(AmericanEnglish.class), LanguageToolLanguages.getOrAdd(AmericanEnglish.class));
    }

    @Test
    public void checksEnglishUsingThePluginResourceBroker() throws Exception {
        ResourceDataBroker previous = JLanguageTool.getDataBroker();
        JLanguageTool.setDataBroker(broker());
        try {
            JLanguageTool tool = new JLanguageTool(new EnglishLanguageToolProvider().getLanguages().get(0));
            assertTrue(tool.check("sentense").stream().anyMatch(match -> match.getRule() instanceof SpellingCheckRule));
            assertFalse(tool.check("sentence").stream().anyMatch(match -> match.getRule() instanceof SpellingCheckRule));
        }
        finally {
            JLanguageTool.setDataBroker(previous);
        }
    }
}
