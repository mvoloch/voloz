import { useCallback, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Input } from '@/components/ui/input';
import type { GenerationSettings, GenerationSettingsUpdate } from '@/lib/api/types';
import { SettingRow } from './SettingRow';

interface FishAudioKeyRowProps {
  settings: GenerationSettings | undefined;
  update: (patch: GenerationSettingsUpdate) => void;
}

/**
 * Settings row for the Fish Audio cloud TTS key. Same contract as the custom
 * STT/LLM key fields: the key is write-only (the server only reports whether
 * one is stored), it persists on blur, and the draft is wiped after saving.
 */
export function FishAudioKeyRow({ settings, update }: FishAudioKeyRowProps) {
  const { t } = useTranslation();
  const apiKeyConfigured = Boolean(settings?.fish_audio_api_key_configured);
  // Write-only: blank on load even when a key is stored, wiped after save.
  const [apiKeyDraft, setApiKeyDraft] = useState('');

  const commitApiKey = useCallback(() => {
    const trimmed = apiKeyDraft.trim();
    if (!trimmed) return;
    update({ fish_audio_api_key: trimmed });
    setApiKeyDraft('');
  }, [apiKeyDraft, update]);
  const clearApiKey = useCallback(() => {
    setApiKeyDraft('');
    update({ fish_audio_api_key: null });
  }, [update]);

  return (
    <SettingRow
      title={t('settings.generation.fishAudio.title')}
      description={t('settings.generation.fishAudio.description')}
    >
      <div className="space-y-2 max-w-lg">
        <div className="flex gap-2">
          <Input
            id="fishAudioApiKey"
            type="password"
            placeholder={
              apiKeyConfigured
                ? t('settings.generation.fishAudio.apiKeyPlaceholderReplace')
                : t('settings.generation.fishAudio.apiKeyPlaceholder')
            }
            value={apiKeyDraft}
            onChange={(e) => setApiKeyDraft(e.target.value)}
            onBlur={commitApiKey}
            autoComplete="new-password"
            spellCheck={false}
            aria-label={t('settings.generation.fishAudio.title')}
            className="flex-1"
          />
          {apiKeyConfigured && (
            <button
              type="button"
              onClick={clearApiKey}
              className="text-xs text-muted-foreground hover:text-destructive px-2 py-1 border border-border rounded-md"
              aria-label={t('settings.generation.fishAudio.apiKeyClear')}
            >
              {t('settings.generation.fishAudio.apiKeyClear')}
            </button>
          )}
        </div>
        <p className="text-xs text-muted-foreground">
          {apiKeyConfigured ? (
            <>
              <span className="mr-2 text-[10px] uppercase tracking-wide text-emerald-600">
                {t('settings.generation.fishAudio.apiKeyConfigured')}
              </span>
              {t('settings.generation.fishAudio.statusReady')}
            </>
          ) : (
            <>
              {t('settings.generation.fishAudio.statusMissing')}{' '}
              {t('settings.generation.fishAudio.getKey')}
            </>
          )}
        </p>
      </div>
    </SettingRow>
  );
}
