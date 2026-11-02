package consulo.languagetool.spelling;

import org.junit.Test;
import org.languagetool.JLanguageTool;
import org.languagetool.broker.DefaultResourceDataBroker;
import org.languagetool.rules.spelling.SpellingCheckRule;

import java.util.List;

import static org.junit.Assert.*;

public class LanguageToolIntegrationTest {
    private PluginAwareResourceDataBroker broker() {
        return new PluginAwareResourceDataBroker(
            List.of(getClass().getClassLoader()), new DefaultResourceDataBroker());
    }

    @Test
    public void findsBundledDictionaryInResourceDirectory() throws Exception {
        var broker = broker();
        assertTrue(broker.resourceExists("/en/english.dict"));
        try (var stream = broker.getFromResourceDirAsStream("/en/english.dict")) {
            assertNotEquals(-1, stream.read());
        }
    }

    @Test
    public void reportsMissingResourcesWithoutThrowing() {
        var broker = broker();
        assertFalse(broker.resourceExists("/en/does-not-exist.dict"));
        assertFalse(broker.ruleFileExists("/en/does-not-exist.xml"));
    }

    @Test
    public void reusesRegisteredLanguageInstances() {
        var provider = new EnglishLanguageToolProvider();
        assertSame(provider.getLanguages().get(0), provider.getLanguages().get(0));
        assertSame(provider.getLanguages().get(0), new EnglishLanguageToolProvider().getLanguages().get(0));
    }

    @Test
    public void checksEnglishUsingThePluginResourceBroker() throws Exception {
        var previous = JLanguageTool.getDataBroker();
        JLanguageTool.setDataBroker(broker());
        try {
            var tool = new JLanguageTool(new EnglishLanguageToolProvider().getLanguages().get(0));
            assertTrue(tool.check("sentense").stream().anyMatch(match -> match.getRule() instanceof SpellingCheckRule));
            assertFalse(tool.check("sentence").stream().anyMatch(match -> match.getRule() instanceof SpellingCheckRule));
        } finally {
            JLanguageTool.setDataBroker(previous);
        }
    }
}
