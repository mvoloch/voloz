import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Input } from '@/components/ui/input';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import type { SavedApiKey } from '@/lib/api/types';
import { hostOf } from '@/lib/providers';

const NEW_KEY = '__new__';

interface ApiKeyPickerProps {
  /** i18n prefix of the endpoint row, e.g. ``settings.captures.refinement.customEndpoint``. */
  i18nPrefix: string;
  inputId: string;
  /** The endpoint as typed: keys are offered for its host only. */
  endpoint: string;
  configured: boolean;
  selectedId: string | null;
  savedKeys: SavedApiKey[];
  onSave: (key: string) => void;
  onPick: (id: string) => void;
  onClear: () => void;
  onForget: (id: string) => void;
}

/**
 * The API key of an endpoint row. Keys stay write-only: a new one is typed,
 * sent once and wiped from state. Keys stored earlier for the endpoint's
 * host are offered by their masked hint (``gsk_ab…wxyz``), so switching
 * providers back and forth needs no pasting.
 */
export function ApiKeyPicker({
  i18nPrefix,
  inputId,
  endpoint,
  configured,
  selectedId,
  savedKeys,
  onSave,
  onPick,
  onClear,
  onForget,
}: ApiKeyPickerProps) {
  const { t, i18n } = useTranslation();
  const [draft, setDraft] = useState('');
  const [typing, setTyping] = useState(false);
  const [confirmForget, setConfirmForget] = useState(false);
  const forHost = savedKeys.filter((key) => key.host === hostOf(endpoint));
  const showInput = forHost.length === 0 || typing;

  const commit = () => {
    // Send the trimmed value — surrounding whitespace in a bearer token
    // makes the remote reject it with 401. Then wipe the draft so nothing
    // else in the tree can read it back.
    const trimmed = draft.trim();
    if (!trimmed) return;
    onSave(trimmed);
    setDraft('');
    setTyping(false);
  };

  const keyLabel = (key: SavedApiKey) => {
    const added = key.added_at ? new Date(key.added_at).toLocaleDateString(i18n.language) : null;
    return added
      ? `${key.hint} · ${t('settings.captures.apiKeys.added', { date: added })}`
      : key.hint;
  };

  return (
    <>
      <label htmlFor={inputId} className="block text-xs font-medium text-muted-foreground pt-1">
        {t(`${i18nPrefix}.apiKeyLabel`)}
        {configured ? (
          <span className="ml-2 text-[10px] uppercase tracking-wide text-emerald-600">
            {t(`${i18nPrefix}.apiKeyConfigured`)}
          </span>
        ) : null}
      </label>
      {forHost.length > 0 && (
        <div className="flex gap-2">
          <Select
            value={typing ? NEW_KEY : (selectedId ?? '')}
            onValueChange={(value) => {
              setConfirmForget(false);
              if (value === NEW_KEY) {
                setTyping(true);
              } else {
                setTyping(false);
                onPick(value);
              }
            }}
            disabled={!endpoint}
          >
            <SelectTrigger className="flex-1" aria-label={t('settings.captures.apiKeys.pick')}>
              <SelectValue placeholder={t('settings.captures.apiKeys.pick')} />
            </SelectTrigger>
            <SelectContent>
              {forHost.map((key) => (
                <SelectItem key={key.id} value={key.id}>
                  {keyLabel(key)}
                </SelectItem>
              ))}
              <SelectItem value={NEW_KEY}>{t('settings.captures.apiKeys.newKey')}</SelectItem>
            </SelectContent>
          </Select>
          {selectedId && !typing && (
            <button
              type="button"
              onClick={() => {
                if (!confirmForget) {
                  setConfirmForget(true);
                  return;
                }
                setConfirmForget(false);
                onForget(selectedId);
              }}
              onBlur={() => setConfirmForget(false)}
              className="text-xs text-muted-foreground hover:text-destructive px-2 py-1 border border-border rounded-md"
              title={t('settings.captures.apiKeys.forgetHint')}
            >
              {confirmForget
                ? t('settings.captures.apiKeys.forgetConfirm')
                : t('settings.captures.apiKeys.forget')}
            </button>
          )}
        </div>
      )}
      {showInput && (
        <div className="flex gap-2">
          <Input
            id={inputId}
            type="password"
            placeholder={
              configured
                ? t(`${i18nPrefix}.apiKeyPlaceholderReplace`)
                : t(`${i18nPrefix}.apiKeyPlaceholder`)
            }
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onBlur={commit}
            disabled={!endpoint}
            autoComplete="new-password"
            spellCheck={false}
            aria-label={t(`${i18nPrefix}.apiKeyLabel`)}
            className="flex-1"
          />
          {configured && forHost.length === 0 && (
            <button
              type="button"
              onClick={() => {
                setDraft('');
                onClear();
              }}
              className="text-xs text-muted-foreground hover:text-destructive px-2 py-1 border border-border rounded-md"
              aria-label={t(`${i18nPrefix}.apiKeyClear`)}
            >
              {t(`${i18nPrefix}.apiKeyClear`)}
            </button>
          )}
        </div>
      )}
    </>
  );
}
