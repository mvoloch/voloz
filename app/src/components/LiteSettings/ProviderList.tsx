import { ChevronDown, ChevronUp, X } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiKeyPicker } from '@/components/ServerTab/ApiKeyPicker';
import { SettingSection } from '@/components/ServerTab/SettingRow';
import { Button } from '@/components/ui/button';
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
import { cn } from '@/lib/utils/cn';

const MAX_PROVIDERS = 5;
const OTHER = '__other__';
const ICON_BUTTON =
  'grid h-7 w-7 place-items-center rounded-md border border-border text-muted-foreground transition-colors hover:text-foreground disabled:opacity-30 disabled:hover:text-muted-foreground';

interface ProviderListProps {
  kind: 'stt' | 'llm';
  settings: CaptureSettings | undefined;
  update: (patch: CaptureSettingsUpdate) => void;
}

/**
 * The providers of one step (transcription or refinement), in the order
 * they are tried: the first is the main endpoint, the rest its backups
 * (see ``services/providers.py``). Reordering writes both at once, so
 * "make this one the main provider" is just moving it to the top.
 */
export function ProviderList({ kind, settings, update }: ProviderListProps) {
  const { t } = useTranslation();
  const [editing, setEditing] = useState<number | null>(null);
  const [adding, setAdding] = useState(false);

  const endpointField = kind === 'stt' ? 'custom_stt_endpoint' : 'custom_llm_endpoint';
  const modelField = kind === 'stt' ? 'custom_stt_model' : 'custom_llm_model';
  const fallbacksField = kind === 'stt' ? 'stt_fallbacks' : 'llm_fallbacks';
  const primaryEndpoint = settings?.[endpointField] ?? '';
  const primaryModel = settings?.[modelField] ?? '';
  const primary: ProviderEntry | null =
    primaryEndpoint && primaryModel ? { endpoint: primaryEndpoint, model: primaryModel } : null;
  const backups = settings?.[fallbacksField] ?? [];
  const providers = primary ? [primary, ...backups] : backups;
  const savedHosts = new Set((settings?.saved_api_keys ?? []).map((key) => key.host));
  const primaryConfigured =
    kind === 'stt'
      ? settings?.custom_stt_api_key_configured
      : settings?.custom_llm_api_key_configured;

  const write = (next: ProviderEntry[]) => {
    const [first, ...rest] = next;
    update({
      [endpointField]: first?.endpoint ?? null,
      [modelField]: first?.model ?? null,
      [fallbacksField]: rest,
    });
  };
  const move = (from: number, to: number) => {
    const next = [...providers];
    const [entry] = next.splice(from, 1);
    next.splice(to, 0, entry);
    setEditing(null);
    write(next);
  };
  const hasKey = (entry: ProviderEntry, index: number) =>
    index === 0 && primary ? Boolean(primaryConfigured) : savedHosts.has(hostOf(entry.endpoint));
  const presets = BACKUP_PRESETS[kind].filter(
    (preset) => !providers.some((p) => sameProvider(p, preset)),
  );

  return (
    <SettingSection title={t('lite.groups.providers')}>
      {providers.length === 0 && (
        <p className="px-4 py-3.5 text-sm text-muted-foreground">{t('lite.providers.empty')}</p>
      )}
      {providers.map((entry, index) => (
        <div key={`${entry.endpoint} ${entry.model}`} className="px-4 py-3">
          <div className="flex items-center gap-3">
            <span
              className={cn(
                'grid h-6 w-6 shrink-0 place-items-center rounded-full border font-mono text-[11px]',
                index === 0
                  ? 'border-foreground bg-foreground text-background'
                  : 'border-border text-muted-foreground',
              )}
              title={index === 0 ? t('lite.providers.main') : undefined}
            >
              {index + 1}
            </span>
            <div className="min-w-0 flex-1">
              <div className="text-sm font-semibold">{providerName(hostOf(entry.endpoint))}</div>
              <div className="truncate font-mono text-xs text-muted-foreground">{entry.model}</div>
            </div>
            <span
              className={cn(
                'whitespace-nowrap rounded-full border px-2.5 py-0.5 text-xs font-medium',
                hasKey(entry, index)
                  ? 'border-emerald-600/30 text-emerald-700 dark:text-emerald-400'
                  : 'border-signal/40 bg-signal/10 text-signal',
              )}
            >
              {hasKey(entry, index) ? t('lite.providers.keyOk') : t('lite.providers.keyMissing')}
            </span>
            <div className="flex gap-1">
              <button
                type="button"
                className={ICON_BUTTON}
                disabled={index === 0}
                onClick={() => move(index, index - 1)}
                aria-label={t('lite.providers.up')}
              >
                <ChevronUp className="h-3.5 w-3.5" />
              </button>
              <button
                type="button"
                className={ICON_BUTTON}
                disabled={index === providers.length - 1}
                onClick={() => move(index, index + 1)}
                aria-label={t('lite.providers.down')}
              >
                <ChevronDown className="h-3.5 w-3.5" />
              </button>
              <button
                type="button"
                className={ICON_BUTTON}
                onClick={() => {
                  setEditing(null);
                  write(providers.filter((_, i) => i !== index));
                }}
                aria-label={t('lite.providers.remove')}
              >
                <X className="h-3.5 w-3.5" />
              </button>
            </div>
            <Button
              variant="outline"
              size="sm"
              onClick={() => setEditing(editing === index ? null : index)}
            >
              {editing === index ? t('lite.providers.done') : t('lite.providers.edit')}
            </Button>
          </div>
          {editing === index && (
            <ProviderEditor
              kind={kind}
              entry={entry}
              isMain={index === 0 && Boolean(primary)}
              settings={settings}
              update={update}
              onChange={(changed) => write(providers.map((p, i) => (i === index ? changed : p)))}
            />
          )}
        </div>
      ))}
      {providers.length < MAX_PROVIDERS && (
        <div className="px-4 py-3">
          {adding ? (
            <CustomProviderForm
              onCancel={() => setAdding(false)}
              onAdd={(entry) => {
                setAdding(false);
                write([...providers, entry]);
              }}
            />
          ) : (
            <Select
              value=""
              onValueChange={(value) => {
                if (value === OTHER) {
                  setAdding(true);
                  return;
                }
                const preset = presets[Number(value)];
                if (preset) write([...providers, preset]);
              }}
            >
              <SelectTrigger className="w-[280px]" aria-label={t('lite.providers.add')}>
                <SelectValue placeholder={`+ ${t('lite.providers.add')}`} />
              </SelectTrigger>
              <SelectContent>
                {presets.map((preset, index) => (
                  <SelectItem key={`${preset.endpoint} ${preset.model}`} value={String(index)}>
                    {providerName(hostOf(preset.endpoint))} · {preset.model}
                  </SelectItem>
                ))}
                <SelectItem value={OTHER}>{t('lite.providers.other')}</SelectItem>
              </SelectContent>
            </Select>
          )}
        </div>
      )}
    </SettingSection>
  );
}

/** Endpoint, model and key of one provider, saved as each field loses focus. */
function ProviderEditor({
  kind,
  entry,
  isMain,
  settings,
  update,
  onChange,
}: {
  kind: 'stt' | 'llm';
  entry: ProviderEntry;
  isMain: boolean;
  settings: CaptureSettings | undefined;
  update: (patch: CaptureSettingsUpdate) => void;
  onChange: (entry: ProviderEntry) => void;
}) {
  const { t } = useTranslation();
  const [endpoint, setEndpoint] = useState(entry.endpoint);
  const [model, setModel] = useState(entry.model);
  const [newKey, setNewKey] = useState('');
  useEffect(() => setEndpoint(entry.endpoint), [entry.endpoint]);
  useEffect(() => setModel(entry.model), [entry.model]);

  const commit = () => {
    const next = { endpoint: endpoint.trim(), model: model.trim() };
    if (next.endpoint && next.model && !sameProvider(next, entry)) onChange(next);
  };
  const host = hostOf(entry.endpoint);
  const hasSavedKey = (settings?.saved_api_keys ?? []).some((key) => key.host === host);
  const prefix =
    kind === 'stt'
      ? 'settings.captures.transcription.customEndpoint'
      : 'settings.captures.refinement.customEndpoint';

  return (
    <div className="mt-3 ml-9 grid gap-2">
      <label className="text-xs font-medium text-muted-foreground" htmlFor={`${kind}-endpoint`}>
        {t('lite.providers.endpoint')}
      </label>
      <Input
        id={`${kind}-endpoint`}
        type="url"
        value={endpoint}
        onChange={(e) => setEndpoint(e.target.value)}
        onBlur={commit}
        spellCheck={false}
      />
      <label className="text-xs font-medium text-muted-foreground pt-1" htmlFor={`${kind}-model`}>
        {t('lite.providers.model')}
      </label>
      <Input
        id={`${kind}-model`}
        value={model}
        onChange={(e) => setModel(e.target.value)}
        onBlur={commit}
        spellCheck={false}
      />
      {isMain ? (
        <ApiKeyPicker
          i18nPrefix={prefix}
          inputId={`${kind}-api-key`}
          endpoint={entry.endpoint}
          configured={Boolean(
            kind === 'stt'
              ? settings?.custom_stt_api_key_configured
              : settings?.custom_llm_api_key_configured,
          )}
          selectedId={
            (kind === 'stt' ? settings?.custom_stt_api_key_id : settings?.custom_llm_api_key_id) ??
            null
          }
          savedKeys={settings?.saved_api_keys ?? []}
          onSave={(key) =>
            update({ [kind === 'stt' ? 'custom_stt_api_key' : 'custom_llm_api_key']: key })
          }
          onPick={(id) =>
            update({ [kind === 'stt' ? 'custom_stt_api_key_id' : 'custom_llm_api_key_id']: id })
          }
          onClear={() =>
            update({ [kind === 'stt' ? 'custom_stt_api_key' : 'custom_llm_api_key']: null })
          }
          onForget={(id) => update({ forget_api_key_id: id })}
        />
      ) : (
        <>
          <label
            className="text-xs font-medium text-muted-foreground pt-1"
            htmlFor={`${kind}-backup-key`}
          >
            {t(`${prefix}.apiKeyLabel`)}
          </label>
          <Input
            id={`${kind}-backup-key`}
            type="password"
            placeholder={
              hasSavedKey
                ? t('lite.providers.backupKeySaved', { provider: providerName(host) })
                : t('settings.captures.backups.pasteKey', { provider: providerName(host) })
            }
            value={newKey}
            onChange={(e) => setNewKey(e.target.value)}
            onBlur={() => {
              const trimmed = newKey.trim();
              if (!trimmed) return;
              update({ save_api_key: { endpoint: entry.endpoint, key: trimmed } });
              setNewKey('');
            }}
            autoComplete="new-password"
            spellCheck={false}
          />
        </>
      )}
    </div>
  );
}

function CustomProviderForm({
  onAdd,
  onCancel,
}: {
  onAdd: (entry: ProviderEntry) => void;
  onCancel: () => void;
}) {
  const { t } = useTranslation();
  const [endpoint, setEndpoint] = useState('');
  const [model, setModel] = useState('');
  const ready = /^https?:\/\/.+/i.test(endpoint.trim()) && model.trim().length > 0;
  return (
    <div className="grid gap-2">
      <Input
        type="url"
        placeholder={t('lite.providers.endpointPlaceholder')}
        value={endpoint}
        onChange={(e) => setEndpoint(e.target.value)}
        spellCheck={false}
        aria-label={t('lite.providers.endpoint')}
      />
      <Input
        placeholder={t('lite.providers.model')}
        value={model}
        onChange={(e) => setModel(e.target.value)}
        spellCheck={false}
        aria-label={t('lite.providers.model')}
      />
      <div className="flex gap-2">
        <Button
          size="sm"
          disabled={!ready}
          onClick={() => onAdd({ endpoint: endpoint.trim(), model: model.trim() })}
        >
          {t('lite.providers.addCustom')}
        </Button>
        <Button size="sm" variant="ghost" onClick={onCancel}>
          {t('common.cancel')}
        </Button>
      </div>
    </div>
  );
}
