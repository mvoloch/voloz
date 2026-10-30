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
import type { CaptureSettings, CaptureSettingsUpdate, ProviderEntry } from '@/lib/api/types';
import { BACKUP_PRESETS, hostOf, providerName, sameProvider } from '@/lib/providers';
import { SettingRow } from './SettingRow';

const MAX_BACKUPS = 4;
const BUTTON =
  'text-xs text-muted-foreground hover:text-foreground px-2 py-1 border border-border rounded-md disabled:opacity-40';

interface BackupProvidersRowProps {
  kind: 'stt' | 'llm';
  settings: CaptureSettings | undefined;
  update: (patch: CaptureSettingsUpdate) => void;
}

/**
 * Backup providers for transcription or refinement, tried in order when the
 * main endpoint fails (rate limit, server error, timeout, bad key). Each
 * uses the key stored for its host; one without a key asks for it here.
 */
export function BackupProvidersRow({ kind, settings, update }: BackupProvidersRowProps) {
  const { t } = useTranslation();
  const field = kind === 'stt' ? 'stt_fallbacks' : 'llm_fallbacks';
  const backups = settings?.[field] ?? [];
  const primary: ProviderEntry | null =
    kind === 'stt'
      ? settings?.custom_stt_endpoint && settings.custom_stt_model
        ? { endpoint: settings.custom_stt_endpoint, model: settings.custom_stt_model }
        : null
      : settings?.custom_llm_endpoint && settings.custom_llm_model
        ? { endpoint: settings.custom_llm_endpoint, model: settings.custom_llm_model }
        : null;
  const savedHosts = new Set((settings?.saved_api_keys ?? []).map((key) => key.host));

  // Backups only apply behind a remote main endpoint.
  if (!primary) return null;

  const write = (next: ProviderEntry[]) => update({ [field]: next });
  const move = (from: number, to: number) => {
    const next = [...backups];
    const [entry] = next.splice(from, 1);
    next.splice(to, 0, entry);
    write(next);
  };
  const options = BACKUP_PRESETS[kind].filter(
    (preset) =>
      !sameProvider(preset, primary) && !backups.some((entry) => sameProvider(entry, preset)),
  );
  const describe = (entry: ProviderEntry) =>
    `${providerName(hostOf(entry.endpoint))} · ${entry.model}`;

  return (
    <SettingRow
      title={t('settings.captures.backups.title')}
      description={t(`settings.captures.backups.description.${kind}`)}
    >
      <div className="space-y-2 max-w-lg">
        {backups.length === 0 ? (
          <p className="text-xs text-muted-foreground">{t('settings.captures.backups.empty')}</p>
        ) : (
          <ol className="space-y-2">
            {backups.map((entry, index) => (
              <li key={`${entry.endpoint} ${entry.model}`} className="space-y-1">
                <div className="flex items-center gap-2">
                  <span className="text-sm flex-1 min-w-0 truncate">
                    {index + 1}. {describe(entry)}
                  </span>
                  <button
                    type="button"
                    className={BUTTON}
                    disabled={index === 0}
                    onClick={() => move(index, index - 1)}
                    aria-label={t('settings.captures.backups.moveUp')}
                  >
                    ↑
                  </button>
                  <button
                    type="button"
                    className={BUTTON}
                    disabled={index === backups.length - 1}
                    onClick={() => move(index, index + 1)}
                    aria-label={t('settings.captures.backups.moveDown')}
                  >
                    ↓
                  </button>
                  <button
                    type="button"
                    className={BUTTON}
                    onClick={() => write(backups.filter((_, i) => i !== index))}
                  >
                    {t('settings.captures.backups.remove')}
                  </button>
                </div>
                {!savedHosts.has(hostOf(entry.endpoint)) && (
                  <BackupKeyInput entry={entry} update={update} />
                )}
              </li>
            ))}
          </ol>
        )}
        {options.length > 0 && backups.length < MAX_BACKUPS && (
          <Select
            value=""
            onValueChange={(value) => {
              const preset = options[Number(value)];
              if (preset) write([...backups, preset]);
            }}
          >
            <SelectTrigger className="w-[300px]" aria-label={t('settings.captures.backups.add')}>
              <SelectValue placeholder={t('settings.captures.backups.add')} />
            </SelectTrigger>
            <SelectContent>
              {options.map((preset, index) => (
                <SelectItem key={`${preset.endpoint} ${preset.model}`} value={String(index)}>
                  {describe(preset)}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        )}
      </div>
    </SettingRow>
  );
}

/** Write-only key field for a backup whose host has no stored key yet. */
function BackupKeyInput({
  entry,
  update,
}: {
  entry: ProviderEntry;
  update: (patch: CaptureSettingsUpdate) => void;
}) {
  const { t } = useTranslation();
  const [draft, setDraft] = useState('');
  const provider = providerName(hostOf(entry.endpoint));
  return (
    <div className="space-y-1">
      <p className="text-xs text-amber-600">
        {t('settings.captures.backups.keyMissing', { provider })}
      </p>
      <Input
        type="password"
        placeholder={t('settings.captures.backups.pasteKey', { provider })}
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        onBlur={() => {
          const trimmed = draft.trim();
          if (!trimmed) return;
          update({ save_api_key: { endpoint: entry.endpoint, key: trimmed } });
          setDraft('');
        }}
        autoComplete="new-password"
        spellCheck={false}
        aria-label={t('settings.captures.backups.pasteKey', { provider })}
      />
    </div>
  );
}
