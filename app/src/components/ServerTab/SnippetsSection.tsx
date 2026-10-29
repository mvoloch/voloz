import { Pencil, Plus, X } from 'lucide-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import type { CaptureSettings, CaptureSettingsUpdate, Snippet } from '@/lib/api/types';
import { cleanTerm, mergeSnippets, snippetKey } from '@/lib/utils/vocabulary';
import { SettingSection } from './SettingRow';

// Same caps as the server (``services/snippets.py``), so a typed shortcut
// never comes back as a 422.
const MAX_TRIGGER_CHARS = 100;
const MAX_TEXT_CHARS = 4000;

interface SnippetsSectionProps {
  settings: CaptureSettings | undefined;
  update: (patch: CaptureSettingsUpdate) => void;
}

/**
 * Settings section for text shortcuts: say a trigger phrase ("my address")
 * and the saved text is pasted instead. Each add, edit or remove writes the
 * whole list.
 */
export function SnippetsSection({ settings, update }: SnippetsSectionProps) {
  const { t } = useTranslation();
  const snippets = settings?.snippets ?? [];

  const [triggerDraft, setTriggerDraft] = useState('');
  const [textDraft, setTextDraft] = useState('');
  // Key of the shortcut loaded into the form, so saving under a new trigger
  // replaces it instead of adding a second one.
  const [editingKey, setEditingKey] = useState<string | null>(null);

  const trigger = cleanTerm(triggerDraft);
  const canSave = Boolean(snippetKey(trigger) && textDraft.trim());
  const replacesOther =
    !editingKey &&
    Boolean(trigger) &&
    snippets.some((s) => snippetKey(s.trigger) === snippetKey(trigger));

  const resetForm = () => {
    setTriggerDraft('');
    setTextDraft('');
    setEditingKey(null);
  };

  const save = () => {
    if (!canSave) return;
    const kept = editingKey
      ? snippets.filter((s) => snippetKey(s.trigger) !== editingKey)
      : snippets;
    update({ snippets: mergeSnippets(kept, { trigger, text: textDraft }) });
    resetForm();
  };
  const edit = (snippet: Snippet) => {
    setTriggerDraft(snippet.trigger);
    setTextDraft(snippet.text);
    setEditingKey(snippetKey(snippet.trigger));
  };
  const remove = (snippet: Snippet) => {
    update({ snippets: snippets.filter((s) => s !== snippet) });
    if (editingKey === snippetKey(snippet.trigger)) resetForm();
  };

  return (
    <SettingSection
      title={t('settings.captures.snippets.title')}
      description={t('settings.captures.snippets.description')}
    >
      <div className="py-3 space-y-3 max-w-lg">
        {snippets.length > 0 ? (
          <ul className="space-y-1.5">
            {snippets.map((snippet) => (
              <li
                key={snippet.trigger}
                className="flex items-start gap-2 pl-3 pr-1 py-2 rounded-md border border-border bg-muted/20 text-sm"
              >
                <div className="min-w-0 flex-1">
                  <div className="font-medium truncate">“{snippet.trigger}”</div>
                  <div className="text-xs text-muted-foreground whitespace-pre-line line-clamp-3 break-words mt-0.5">
                    {snippet.text}
                  </div>
                </div>
                <button
                  type="button"
                  onClick={() => edit(snippet)}
                  className="p-1 rounded text-muted-foreground hover:text-foreground"
                  aria-label={t('settings.captures.snippets.edit', { trigger: snippet.trigger })}
                  title={t('settings.captures.snippets.edit', { trigger: snippet.trigger })}
                >
                  <Pencil className="h-3.5 w-3.5" />
                </button>
                <button
                  type="button"
                  onClick={() => remove(snippet)}
                  className="p-1 rounded text-muted-foreground hover:text-destructive"
                  aria-label={t('settings.captures.snippets.remove', { trigger: snippet.trigger })}
                  title={t('settings.captures.snippets.remove', { trigger: snippet.trigger })}
                >
                  <X className="h-3.5 w-3.5" />
                </button>
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-xs text-muted-foreground">{t('settings.captures.snippets.empty')}</p>
        )}

        <div className="space-y-2">
          <Input
            value={triggerDraft}
            onChange={(e) => setTriggerDraft(e.target.value)}
            maxLength={MAX_TRIGGER_CHARS}
            placeholder={t('settings.captures.snippets.triggerPlaceholder')}
            aria-label={t('settings.captures.snippets.triggerLabel')}
            autoComplete="off"
            spellCheck={false}
          />
          <Textarea
            value={textDraft}
            onChange={(e) => setTextDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
                e.preventDefault();
                save();
              }
            }}
            maxLength={MAX_TEXT_CHARS}
            placeholder={t('settings.captures.snippets.textPlaceholder')}
            aria-label={t('settings.captures.snippets.textLabel')}
            className="min-h-[88px]"
          />
          <div className="flex items-center gap-2">
            <span className="text-xs text-muted-foreground mr-auto">
              {replacesOther ? t('settings.captures.snippets.replaces') : null}
            </span>
            {editingKey ? (
              <Button variant="ghost" size="sm" onClick={resetForm}>
                {t('common.cancel')}
              </Button>
            ) : null}
            <Button variant="outline" size="sm" onClick={save} disabled={!canSave}>
              {editingKey ? null : <Plus className="h-3.5 w-3.5 mr-1" />}
              {editingKey
                ? t('settings.captures.snippets.save')
                : t('settings.captures.snippets.add')}
            </Button>
          </div>
        </div>
      </div>
    </SettingSection>
  );
}
