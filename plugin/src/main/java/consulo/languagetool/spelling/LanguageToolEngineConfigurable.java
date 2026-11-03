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

import consulo.application.Application;
import consulo.configurable.UnnamedConfigurable;
import consulo.disposer.Disposable;
import consulo.disposer.Disposer;
import consulo.languagetool.spelling.internal.LanguageToolRuntime;
import consulo.languagetool.spelling.localize.LanguageToolSpellingLocalize;
import consulo.localize.LocalizeValue;
import consulo.ui.CheckBoxTree;
import consulo.ui.CheckBoxTreePolicy;
import consulo.ui.Component;
import consulo.ui.Label;
import consulo.ui.Length;
import consulo.ui.MultiSelectComboBox;
import consulo.ui.TextAttribute;
import consulo.ui.TreeModel;
import consulo.ui.TreeNode;
import consulo.ui.UIAccess;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.layout.DockLayout;
import consulo.ui.layout.LabeledLayout;
import consulo.ui.layout.ScrollableLayout;
import org.jspecify.annotations.Nullable;
import org.languagetool.Language;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Options of the LanguageTool engine: the checked languages, from the installed language plugins (no selection means all languages),
 * and the grammar and style rules of each language.
 *
 * @author VISTALL
 * @since 2026-11-01
 */
class LanguageToolEngineConfigurable implements UnnamedConfigurable {
    private record LanguageNode(String code, String name) {
    }

    private record CategoryNode(String code, String name) {
    }

    private record RuleNode(String code, LanguageToolRuntime.RuleInfo rule) {
        String key() {
            return code + ":" + rule.id();
        }
    }

    private static final int RULES_HEIGHT = 320;

    private final LanguageToolClient myClient;
    private final LanguageToolSettings mySettings;

    private @Nullable MultiSelectComboBox<String> myLanguagesBox;

    /**
     * Rules of each installed language, grouped by category; {@code null} while loading.
     */
    private @Nullable Map<LanguageNode, Map<CategoryNode, List<RuleNode>>> myRules;
    private @Nullable CheckBoxTree<Object> myRulesTree;

    LanguageToolEngineConfigurable(LanguageToolClient client, LanguageToolSettings settings) {
        myClient = client;
        mySettings = settings;
    }

    @RequiredUIAccess
    @Override
    public Component createUIComponent(Disposable parentDisposable) {
        List<Language> languages = myClient.getAvailableLanguages();
        List<String> codes = new ArrayList<>();
        Map<String, LocalizeValue> names = new HashMap<>();
        for (Language language : languages) {
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

        DockLayout rulesPanel = DockLayout.create();
        // the settings page grows with its content: a fixed height keeps the rule tree scrollable
        rulesPanel.setHeight(Length.ofPixel(RULES_HEIGHT));
        rulesPanel.center(Label.create(LanguageToolSpellingLocalize.settingsRulesLoading()));

        // loading the rules creates LanguageTool for every language: never on the UI thread
        UIAccess uiAccess = UIAccess.current();
        Application.get().executeOnPooledThread(() -> {
            Map<LanguageNode, Map<CategoryNode, List<RuleNode>>> rules = loadRules(languages);
            uiAccess.give(() -> {
                if (myLanguagesBox != languagesBox) {
                    // disposed meanwhile
                    return;
                }
                myRules = rules;
                CheckBoxTree<Object> tree = CheckBoxTree.create(createModel(rules));
                Disposer.register(parentDisposable, tree.destroyHook());
                tree.setCheckPolicy(CheckBoxTreePolicy.FULL_SYNC);
                myRulesTree = tree;
                resetRules();
                rulesPanel.center(ScrollableLayout.create(tree));
            });
        });

        DockLayout layout = DockLayout.create();
        layout.top(LabeledLayout.create(LanguageToolSpellingLocalize.settingsLanguagesLabel(), languagesBox));
        layout.center(LabeledLayout.create(LanguageToolSpellingLocalize.settingsRulesLabel(), rulesPanel));
        return layout;
    }

    private Map<LanguageNode, Map<CategoryNode, List<RuleNode>>> loadRules(List<Language> languages) {
        Map<LanguageNode, Map<CategoryNode, List<RuleNode>>> result = new LinkedHashMap<>();
        for (Language language : languages) {
            String code = language.getShortCodeWithCountryAndVariant();
            Map<CategoryNode, List<RuleNode>> categories = new LinkedHashMap<>();
            for (LanguageToolRuntime.RuleInfo rule : myClient.getRules(code)) {
                categories.computeIfAbsent(new CategoryNode(code, rule.category()), it -> new ArrayList<>()).add(new RuleNode(code, rule));
            }
            if (!categories.isEmpty()) {
                result.put(new LanguageNode(code, language.getName() + " (" + code + ")"), categories);
            }
        }
        return result;
    }

    private static TreeModel<Object> createModel(Map<LanguageNode, Map<CategoryNode, List<RuleNode>>> rules) {
        Map<CategoryNode, List<RuleNode>> byCategory = new HashMap<>();
        rules.values().forEach(byCategory::putAll);
        return (nodeFactory, parentValue) -> {
            if (parentValue == null) {
                for (LanguageNode language : rules.keySet()) {
                    TreeNode<Object> node = nodeFactory.apply(language);
                    node.setLeaf(false);
                    node.setRenderer((value, presentation) -> presentation.append(language.name()));
                }
            }
            else if (parentValue instanceof LanguageNode language) {
                for (CategoryNode category : rules.getOrDefault(language, Map.of()).keySet()) {
                    TreeNode<Object> node = nodeFactory.apply(category);
                    node.setLeaf(false);
                    node.setRenderer((value, presentation) -> presentation.append(category.name()));
                }
            }
            else if (parentValue instanceof CategoryNode category) {
                for (RuleNode rule : byCategory.getOrDefault(category, List.of())) {
                    TreeNode<Object> node = nodeFactory.apply(rule);
                    node.setLeaf(true);
                    node.setRenderer((value, presentation) -> {
                        presentation.append(rule.rule().description());
                        presentation.append("   " + rule.rule().id(), TextAttribute.GRAYED);
                    });
                }
            }
        };
    }

    private Set<String> getSelectedLanguages() {
        MultiSelectComboBox<String> languagesBox = myLanguagesBox;
        return languagesBox == null ? Set.of() : new TreeSet<>(languagesBox.getValue());
    }

    /**
     * @return {disabled, enabled} rules shown by the tree, plus the rules of the settings the tree does not show (e.g. of languages
     * which are not installed now)
     */
    private List<Set<String>> getTreeRules() {
        Set<String> disabled = new TreeSet<>(mySettings.getDisabledRules());
        Set<String> enabled = new TreeSet<>(mySettings.getEnabledRules());
        CheckBoxTree<Object> tree = myRulesTree;
        Map<LanguageNode, Map<CategoryNode, List<RuleNode>>> rules = myRules;
        if (tree != null && rules != null) {
            for (Map<CategoryNode, List<RuleNode>> categories : rules.values()) {
                for (List<RuleNode> categoryRules : categories.values()) {
                    for (RuleNode rule : categoryRules) {
                        boolean checked = tree.isChecked(rule);
                        boolean byDefault = rule.rule().enabledByDefault();
                        if (checked == byDefault) {
                            disabled.remove(rule.key());
                            enabled.remove(rule.key());
                        }
                        else if (checked) {
                            enabled.add(rule.key());
                            disabled.remove(rule.key());
                        }
                        else {
                            disabled.add(rule.key());
                            enabled.remove(rule.key());
                        }
                    }
                }
            }
        }
        return List.of(disabled, enabled);
    }

    @RequiredUIAccess
    @Override
    public boolean isModified() {
        if (myLanguagesBox == null) {
            return false;
        }
        List<Set<String>> rules = getTreeRules();
        return !getSelectedLanguages().equals(mySettings.getLanguages())
            || !rules.get(0).equals(mySettings.getDisabledRules())
            || !rules.get(1).equals(mySettings.getEnabledRules());
    }

    @RequiredUIAccess
    @Override
    public void apply() {
        if (!isModified()) {
            return;
        }
        if (!getSelectedLanguages().equals(mySettings.getLanguages())) {
            mySettings.setLanguages(getSelectedLanguages());
            myClient.clearCache();
        }
        List<Set<String>> rules = getTreeRules();
        mySettings.setRules(rules.get(0), rules.get(1));
    }

    @RequiredUIAccess
    @Override
    public void reset() {
        MultiSelectComboBox<String> languagesBox = myLanguagesBox;
        if (languagesBox != null) {
            languagesBox.setValue(new ArrayList<>(mySettings.getLanguages()));
        }
        resetRules();
    }

    @RequiredUIAccess
    private void resetRules() {
        CheckBoxTree<Object> tree = myRulesTree;
        Map<LanguageNode, Map<CategoryNode, List<RuleNode>>> rules = myRules;
        if (tree == null || rules == null) {
            return;
        }
        Set<String> disabled = mySettings.getDisabledRules();
        Set<String> enabled = mySettings.getEnabledRules();
        // groups first: a group spreads its state to the loaded nodes below it, the rules then set their own state.
        // A collapsed group shows its own state, so it is checked only when all its rules are
        for (Map.Entry<LanguageNode, Map<CategoryNode, List<RuleNode>>> language : rules.entrySet()) {
            boolean languageChecked = true;
            for (List<RuleNode> categoryRules : language.getValue().values()) {
                for (RuleNode rule : categoryRules) {
                    languageChecked &= isEnabled(rule, disabled, enabled);
                }
            }
            tree.setChecked(language.getKey(), languageChecked);
        }
        for (Map<CategoryNode, List<RuleNode>> categories : rules.values()) {
            for (Map.Entry<CategoryNode, List<RuleNode>> category : categories.entrySet()) {
                boolean categoryChecked = true;
                for (RuleNode rule : category.getValue()) {
                    categoryChecked &= isEnabled(rule, disabled, enabled);
                }
                tree.setChecked(category.getKey(), categoryChecked);
            }
        }
        for (Map<CategoryNode, List<RuleNode>> categories : rules.values()) {
            for (List<RuleNode> categoryRules : categories.values()) {
                for (RuleNode rule : categoryRules) {
                    tree.setChecked(rule, isEnabled(rule, disabled, enabled));
                }
            }
        }
    }

    private static boolean isEnabled(RuleNode rule, Set<String> disabled, Set<String> enabled) {
        if (disabled.contains(rule.key())) {
            return false;
        }
        return rule.rule().enabledByDefault() || enabled.contains(rule.key());
    }

    @RequiredUIAccess
    @Override
    public void disposeUIResources() {
        myLanguagesBox = null;
        myRulesTree = null;
        myRules = null;
    }
}
