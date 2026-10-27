import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Input } from '@/components/ui/input';
import type { CaptureSettings, CaptureSettingsUpdate } from '@/lib/api/types';
import { SettingRow } from './SettingRow';

interface CustomSttEndpointRowProps {
  settings: CaptureSettings | undefined;
  update: (patch: CaptureSettingsUpdate) => void;
}

/**
 * Settings row for the optional OpenAI-compatible transcription endpoint —
 * the STT twin of the custom LLM endpoint row on the refinement section.
 * Same contract: drafts persist on blur, and the API key is write-only.
 */
export function CustomSttEndpointRow({ settings, update }: CustomSttEndpointRowProps) {
  const { t } = useTranslation();
  const endpointSaved = settings?.custom_stt_endpoint ?? '';
  const modelSaved = settings?.custom_stt_model ?? '';
  const apiKeyConfigured = Boolean(settings?.custom_stt_api_key_configured);
  const active = Boolean(endpointSaved && modelSaved);

  const [endpointDraft, setEndpointDraft] = useState(endpointSaved);
  const [modelDraft, setModelDraft] = useState(modelSaved);
  // Write-only: blank on load even when a key is stored, wiped after save.
  const [apiKeyDraft, setApiKeyDraft] = useState('');

  useEffect(() => {
    setEndpointDraft(endpointSaved);
  }, [endpointSaved]);
  useEffect(() => {
    setModelDraft(modelSaved);
  }, [modelSaved]);

  const commitEndpoint = useCallback(() => {
    const next = endpointDraft.trim();
    if (next !== endpointSaved) {
      update({ custom_stt_endpoint: next || null });
    }
  }, [endpointDraft, endpointSaved, update]);
  const commitModel = useCallback(() => {
    const next = modelDraft.trim();
    if (next !== modelSaved) {
      update({ custom_stt_model: next || null });
    }
  }, [modelDraft, modelSaved, update]);
  const commitApiKey = useCallback(() => {
    const trimmed = apiKeyDraft.trim();
    if (!trimmed) return;
    update({ custom_stt_api_key: trimmed });
    setApiKeyDraft('');
  }, [apiKeyDraft, update]);
  const clearApiKey = useCallback(() => {
    setApiKeyDraft('');
    update({ custom_stt_api_key: null });
  }, [update]);

  return (
    <SettingRow
      title={t('settings.captures.transcription.customEndpoint.title')}
      description={t('settings.captures.transcription.customEndpoint.description')}
    >
      <div className="space-y-2 max-w-lg">
        <label htmlFor="customSttEndpoint" className="block text-xs font-medium text-muted-foreground">
          {t('settings.captures.transcription.customEndpoint.endpointLabel')}
        </label>
        <Input
          id="customSttEndpoint"
          type="url"
          placeholder={t('settings.captures.transcription.customEndpoint.endpointPlaceholder')}
          value={endpointDraft}
          onChange={(e) => setEndpointDraft(e.target.value)}
          onBlur={commitEndpoint}
          autoComplete="off"
          spellCheck={false}
          aria-label={t('settings.captures.transcription.customEndpoint.endpointLabel')}
        />
        <label htmlFor="customSttModel" className="block text-xs font-medium text-muted-foreground pt-1">
          {t('settings.captures.transcription.customEndpoint.modelLabel')}
        </label>
        <Input
          id="customSttModel"
          type="text"
          placeholder={t('settings.captures.transcription.customEndpoint.modelPlaceholder')}
          value={modelDraft}
          onChange={(e) => setModelDraft(e.target.value)}
          onBlur={commitModel}
          disabled={!endpointDraft}
          autoComplete="off"
          spellCheck={false}
          aria-label={t('settings.captures.transcription.customEndpoint.modelLabel')}
        />
        <label htmlFor="customSttApiKey" className="block text-xs font-medium text-muted-foreground pt-1">
          {t('settings.captures.transcription.customEndpoint.apiKeyLabel')}
          {apiKeyConfigured ? (
            <span className="ml-2 text-[10px] uppercase tracking-wide text-emerald-600">
              {t('settings.captures.transcription.customEndpoint.apiKeyConfigured')}
            </span>
          ) : null}
        </label>
        <div className="flex gap-2">
          <Input
            id="customSttApiKey"
            type="password"
            placeholder={
              apiKeyConfigured
                ? t('settings.captures.transcription.customEndpoint.apiKeyPlaceholderReplace')
                : t('settings.captures.transcription.customEndpoint.apiKeyPlaceholder')
            }
            value={apiKeyDraft}
            onChange={(e) => setApiKeyDraft(e.target.value)}
            onBlur={commitApiKey}
            disabled={!endpointDraft}
            autoComplete="new-password"
            spellCheck={false}
            aria-label={t('settings.captures.transcription.customEndpoint.apiKeyLabel')}
            className="flex-1"
          />
          {apiKeyConfigured && (
            <button
              type="button"
              onClick={clearApiKey}
              className="text-xs text-muted-foreground hover:text-destructive px-2 py-1 border border-border rounded-md"
              aria-label={t('settings.captures.transcription.customEndpoint.apiKeyClear')}
            >
              {t('settings.captures.transcription.customEndpoint.apiKeyClear')}
            </button>
          )}
        </div>
        <p className="text-xs text-muted-foreground">
          {active
            ? t('settings.captures.transcription.customEndpoint.statusRemote', {
                endpoint: endpointSaved,
              })
            : t('settings.captures.transcription.customEndpoint.statusBuiltin')}
        </p>
      </div>
    </SettingRow>
  );
}
