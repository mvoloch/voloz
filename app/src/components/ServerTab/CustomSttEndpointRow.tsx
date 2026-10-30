import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Input } from '@/components/ui/input';
import type { CaptureSettings, CaptureSettingsUpdate } from '@/lib/api/types';
import { IS_LITE } from '@/lib/edition';
import { ApiKeyPicker } from './ApiKeyPicker';
import { SettingRow } from './SettingRow';

interface CustomSttEndpointRowProps {
  settings: CaptureSettings | undefined;
  update: (patch: CaptureSettingsUpdate) => void;
}

/**
 * Settings row for the optional OpenAI-compatible transcription endpoint —
 * the STT twin of the custom LLM endpoint row on the refinement section.
 * Same contract: drafts persist on blur, and the API key is write-only.
 * In the lite edition there is no built-in Whisper, so this endpoint is required.
 */
export function CustomSttEndpointRow({ settings, update }: CustomSttEndpointRowProps) {
  const { t } = useTranslation();
  const endpointSaved = settings?.custom_stt_endpoint ?? '';
  const modelSaved = settings?.custom_stt_model ?? '';
  const apiKeyConfigured = Boolean(settings?.custom_stt_api_key_configured);
  const active = Boolean(endpointSaved && modelSaved);

  const [endpointDraft, setEndpointDraft] = useState(endpointSaved);
  const [modelDraft, setModelDraft] = useState(modelSaved);

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

  return (
    <SettingRow
      title={t(
        IS_LITE
          ? 'settings.captures.transcription.customEndpoint.titleLite'
          : 'settings.captures.transcription.customEndpoint.title',
      )}
      description={t(
        IS_LITE
          ? 'settings.captures.transcription.customEndpoint.descriptionLite'
          : 'settings.captures.transcription.customEndpoint.description',
      )}
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
        <ApiKeyPicker
          i18nPrefix="settings.captures.transcription.customEndpoint"
          inputId="customSttApiKey"
          endpoint={endpointDraft}
          configured={apiKeyConfigured}
          selectedId={settings?.custom_stt_api_key_id ?? null}
          savedKeys={settings?.saved_api_keys ?? []}
          onSave={(key) => update({ custom_stt_api_key: key })}
          onPick={(id) => update({ custom_stt_api_key_id: id })}
          onClear={() => update({ custom_stt_api_key: null })}
          onForget={(id) => update({ forget_api_key_id: id })}
        />
        <p className="text-xs text-muted-foreground">
          {active
            ? t('settings.captures.transcription.customEndpoint.statusRemote', {
                endpoint: endpointSaved,
              })
            : t(
                IS_LITE
                  ? 'settings.captures.transcription.customEndpoint.statusUnset'
                  : 'settings.captures.transcription.customEndpoint.statusBuiltin',
              )}
        </p>
      </div>
    </SettingRow>
  );
}
