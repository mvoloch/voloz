import { ArrowRight, Plus, X } from 'lucide-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import type { CaptureSettings, CaptureSettingsUpdate, ReplacementRule } from '@/lib/api/types';
import {
  cleanTerm,
  mergeReplacementRules,
  mergeVocabulary,
  splitTerms,
} from '@/lib/utils/vocabulary';
import { SettingRow, SettingSection } from './SettingRow';

interface VocabularySectionProps {
  settings: CaptureSettings | undefined;
  update: (patch: CaptureSettingsUpdate) => void;
}

/**
 * Settings section for the custom vocabulary: spellings of names and
 * jargon (Whisper prompt + refinement) and replacement rules applied to the
 * raw transcript. Each add or remove writes the whole list.
 */
export function VocabularySection({ settings, update }: VocabularySectionProps) {
  const { t } = useTranslation();
  const vocabulary = settings?.vocabulary ?? [];
  const replacements = settings?.replacements ?? [];

  const [termDraft, setTermDraft] = useState('');
  const [fromDraft, setFromDraft] = useState('');
  const [toDraft, setToDraft] = useState('');

  const addTerms = () => {
    const next = mergeVocabulary(vocabulary, splitTerms(termDraft));
    setTermDraft('');
    if (next.length !== vocabulary.length) update({ vocabulary: next });
  };
  const removeTerm = (term: string) => {
    update({ vocabulary: vocabulary.filter((v) => v !== term) });
  };

  const fromValue = cleanTerm(fromDraft);
  const toValue = cleanTerm(toDraft);
  const canAddRule = Boolean(fromValue && toValue && fromValue !== toValue);
  const addRule = () => {
    if (!canAddRule) return;
    update({
      replacements: mergeReplacementRules(replacements, [{ from: fromValue, to: toValue }]),
    });
    setFromDraft('');
    setToDraft('');
  };
  const removeRule = (rule: ReplacementRule) => {
    update({ replacements: replacements.filter((r) => r !== rule) });
  };

  return (
    <SettingSection
      title={t('settings.captures.vocabulary.title')}
      description={t('settings.captures.vocabulary.description')}
    >
      <SettingRow
        title={t('settings.captures.vocabulary.terms.title')}
        description={t('settings.captures.vocabulary.terms.description')}
      >
        <div className="space-y-2 max-w-lg">
          {vocabulary.length > 0 ? (
            <div className="flex flex-wrap gap-1.5">
              {vocabulary.map((term) => (
                <span
                  key={term}
                  className="inline-flex items-center gap-1 h-7 pl-2.5 pr-1 rounded-md border border-border bg-muted/40 text-sm"
                >
                  {term}
                  <button
                    type="button"
                    onClick={() => removeTerm(term)}
                    className="p-0.5 rounded text-muted-foreground hover:text-destructive"
                    aria-label={t('settings.captures.vocabulary.terms.remove', { term })}
                    title={t('settings.captures.vocabulary.terms.remove', { term })}
                  >
                    <X className="h-3 w-3" />
                  </button>
                </span>
              ))}
            </div>
          ) : (
            <p className="text-xs text-muted-foreground">
              {t('settings.captures.vocabulary.terms.empty')}
            </p>
          )}
          <div className="flex gap-2">
            <Input
              value={termDraft}
              onChange={(e) => setTermDraft(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault();
                  addTerms();
                }
              }}
              placeholder={t('settings.captures.vocabulary.terms.placeholder')}
              aria-label={t('settings.captures.vocabulary.terms.title')}
              autoComplete="off"
              spellCheck={false}
              className="flex-1"
            />
            <Button
              variant="outline"
              size="sm"
              onClick={addTerms}
              disabled={!splitTerms(termDraft).length}
            >
              <Plus className="h-3.5 w-3.5 mr-1" />
              {t('settings.captures.vocabulary.terms.add')}
            </Button>
          </div>
        </div>
      </SettingRow>

      <SettingRow
        title={t('settings.captures.vocabulary.replacements.title')}
        description={t('settings.captures.vocabulary.replacements.description')}
      >
        <div className="space-y-2 max-w-lg">
          {replacements.length > 0 ? (
            <ul className="space-y-1">
              {replacements.map((rule) => (
                <li
                  key={rule.from}
                  className="flex items-center gap-2 h-8 pl-3 pr-1 rounded-md border border-border bg-muted/20 text-sm"
                >
                  <span className="truncate text-muted-foreground">{rule.from}</span>
                  <ArrowRight className="h-3.5 w-3.5 shrink-0 text-muted-foreground/60" />
                  <span className="truncate font-medium">{rule.to}</span>
                  <div className="flex-1" />
                  <button
                    type="button"
                    onClick={() => removeRule(rule)}
                    className="p-1 rounded text-muted-foreground hover:text-destructive"
                    aria-label={t('settings.captures.vocabulary.replacements.remove', {
                      from: rule.from,
                      to: rule.to,
                    })}
                    title={t('settings.captures.vocabulary.replacements.remove', {
                      from: rule.from,
                      to: rule.to,
                    })}
                  >
                    <X className="h-3.5 w-3.5" />
                  </button>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-xs text-muted-foreground">
              {t('settings.captures.vocabulary.replacements.empty')}
            </p>
          )}
          <div className="flex items-center gap-2">
            <Input
              value={fromDraft}
              onChange={(e) => setFromDraft(e.target.value)}
              placeholder={t('settings.captures.vocabulary.replacements.fromPlaceholder')}
              aria-label={t('settings.captures.vocabulary.replacements.fromPlaceholder')}
              autoComplete="off"
              spellCheck={false}
              className="flex-1"
            />
            <ArrowRight className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
            <Input
              value={toDraft}
              onChange={(e) => setToDraft(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault();
                  addRule();
                }
              }}
              placeholder={t('settings.captures.vocabulary.replacements.toPlaceholder')}
              aria-label={t('settings.captures.vocabulary.replacements.toPlaceholder')}
              autoComplete="off"
              spellCheck={false}
              className="flex-1"
            />
            <Button variant="outline" size="sm" onClick={addRule} disabled={!canAddRule}>
              <Plus className="h-3.5 w-3.5 mr-1" />
              {t('settings.captures.vocabulary.replacements.add')}
            </Button>
          </div>
        </div>
      </SettingRow>
    </SettingSection>
  );
}
