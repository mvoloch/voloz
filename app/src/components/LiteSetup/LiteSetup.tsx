import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ChevronDown, ExternalLink, KeyRound, Loader2, Sparkles } from 'lucide-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { VolozWordmark } from '@/components/VolozWordmark';
import { apiClient } from '@/lib/api/client';
import { IS_LITE } from '@/lib/edition';
import { useCaptureSettings } from '@/lib/hooks/useSettings';
import { cn } from '@/lib/utils/cn';

const GROQ_KEYS_URL = 'https://console.groq.com/keys';
const ANTHROPIC_KEYS_URL = 'https://console.anthropic.com/settings/keys';

/**
 * Whether the lite first-run screen should replace the app: lite only, and
 * only while neither a transcription nor a refinement endpoint is set.
 * "Set up manually" hides it for this session; with nothing configured it
 * comes back on the next launch.
 */
export function useLiteSetupGate() {
  const { settings, isLoading } = useCaptureSettings();
  const [skipped, setSkipped] = useState(false);
  const unconfigured =
    settings != null && !settings.custom_stt_endpoint && !settings.custom_llm_endpoint;
  return {
    show: IS_LITE && !skipped && unconfigured,
    // Lite waits for the settings before drawing the app, so a fresh install
    // doesn't flash the empty home screen before the setup screen.
    pending: IS_LITE && isLoading,
    skip: () => setSkipped(true),
  };
}

interface SetupError {
  status?: number;
  detail?: { field?: 'groq' | 'anthropic' };
  message: string;
}

/**
 * First-run setup of the lite edition. Default: one free Groq key covers
 * transcription and refinement. Recommended: an Anthropic key as well, so
 * refinement runs on Claude Haiku. The server checks each key with one call
 * before storing anything (``POST /settings/captures/quick-setup``).
 */
export function LiteSetupScreen({ onManual }: { onManual: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [groqKey, setGroqKey] = useState('');
  const [anthropicKey, setAnthropicKey] = useState('');
  const [showClaude, setShowClaude] = useState(false);

  const setup = useMutation({
    mutationFn: () => apiClient.quickSetup(groqKey.trim(), anthropicKey.trim() || null),
    onSuccess: (settings) => {
      setGroqKey('');
      setAnthropicKey('');
      // The settings now name both endpoints, which closes this screen.
      queryClient.setQueryData(['settings', 'captures'], settings);
      queryClient.invalidateQueries({ queryKey: ['capture-readiness'] });
    },
  });

  const error = setup.error as SetupError | null;
  const errorField = error?.detail?.field ?? 'groq';
  const errorText = !error
    ? null
    : error.status === 400
      ? t(`setup.quick.errors.invalidKey.${errorField}`)
      : error.status === 503
        ? t('setup.quick.errors.network')
        : t('setup.quick.errors.other', { detail: error.message });

  const submit = () => {
    if (groqKey.trim() && !setup.isPending) setup.mutate();
  };

  const errorLine = (field: 'groq' | 'anthropic') =>
    errorText && errorField === field ? (
      <p id={`${field}-key-error`} role="alert" className="text-sm text-destructive">
        {errorText}
      </p>
    ) : null;

  return (
    <div className="flex flex-1 min-h-0 items-start justify-center overflow-y-auto px-6">
      <form
        className="w-full max-w-md py-12 space-y-8"
        onSubmit={(e) => {
          e.preventDefault();
          submit();
        }}
      >
        <div className="space-y-4 text-center">
          <VolozWordmark className="h-9 w-auto mx-auto" />
          <h1 className="text-2xl font-semibold tracking-tight">{t('setup.quick.title')}</h1>
          <p className="text-sm text-muted-foreground leading-relaxed">{t('setup.quick.intro')}</p>
        </div>

        <ol className="space-y-6">
          <li className="flex gap-4">
            <StepNumber n={1} />
            <div className="flex-1 space-y-3">
              <p className="text-sm">{t('setup.quick.groqStep')}</p>
              <Button variant="outline" asChild>
                <a href={GROQ_KEYS_URL} target="_blank" rel="noopener noreferrer">
                  <ExternalLink className="h-4 w-4 mr-2" />
                  {t('setup.quick.groqCreate')}
                </a>
              </Button>
            </div>
          </li>
          <li className="flex gap-4">
            <StepNumber n={2} />
            <div className="flex-1 space-y-3">
              <label htmlFor="groq-key" className="block text-sm">
                {t('setup.quick.groqPaste')}
              </label>
              <KeyInput
                id="groq-key"
                value={groqKey}
                onChange={setGroqKey}
                placeholder="gsk…"
                error={Boolean(errorText) && errorField === 'groq'}
              />
              {errorLine('groq')}
            </div>
          </li>
          <li className="flex gap-4">
            <StepNumber n={3} muted />
            <div className="flex-1 space-y-3">
              <button
                type="button"
                onClick={() => setShowClaude((v) => !v)}
                aria-expanded={showClaude}
                className="flex w-full items-center gap-2 text-left text-sm"
              >
                <Sparkles className="h-3.5 w-3.5 text-accent shrink-0" />
                <span className="flex-1">
                  <span className="font-medium">{t('setup.quick.claudeTitle')}</span>{' '}
                  <span className="text-muted-foreground">{t('setup.quick.optional')}</span>
                </span>
                <ChevronDown
                  className={cn(
                    'h-4 w-4 text-muted-foreground transition-transform',
                    showClaude && 'rotate-180',
                  )}
                />
              </button>
              {showClaude ? (
                <div className="space-y-3">
                  <p className="text-sm text-muted-foreground leading-relaxed">
                    {t('setup.quick.claudeWhy')}
                  </p>
                  <Button variant="outline" size="sm" asChild>
                    <a href={ANTHROPIC_KEYS_URL} target="_blank" rel="noopener noreferrer">
                      <ExternalLink className="h-3.5 w-3.5 mr-2" />
                      {t('setup.quick.claudeCreate')}
                    </a>
                  </Button>
                  <KeyInput
                    id="anthropic-key"
                    value={anthropicKey}
                    onChange={setAnthropicKey}
                    placeholder="sk-ant…"
                    ariaLabel={t('setup.quick.claudePaste')}
                    error={Boolean(errorText) && errorField === 'anthropic'}
                  />
                  {errorLine('anthropic')}
                </div>
              ) : null}
            </div>
          </li>
        </ol>

        <div className="space-y-3">
          <Button type="submit" className="w-full" disabled={!groqKey.trim() || setup.isPending}>
            {setup.isPending ? (
              <>
                <Loader2 className="h-4 w-4 mr-2 animate-spin" />
                {t('setup.quick.testing')}
              </>
            ) : (
              t('setup.quick.submit')
            )}
          </Button>
          <p className="text-xs text-muted-foreground leading-relaxed text-center">
            {anthropicKey.trim() ? t('setup.quick.noteClaude') : t('setup.quick.noteGroq')}
          </p>
        </div>

        <div className="text-center">
          <button
            type="button"
            onClick={onManual}
            className="text-sm text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
          >
            {t('setup.quick.manual')}
          </button>
        </div>
      </form>
    </div>
  );
}

function KeyInput({
  id,
  value,
  onChange,
  placeholder,
  ariaLabel,
  error,
}: {
  id: string;
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
  ariaLabel?: string;
  error: boolean;
}) {
  return (
    <div className="relative">
      <KeyRound className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
      <Input
        id={id}
        type="password"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        aria-label={ariaLabel}
        autoComplete="off"
        spellCheck={false}
        className="pl-9 font-mono"
        aria-invalid={error}
        aria-describedby={error ? `${id}-error` : undefined}
      />
    </div>
  );
}

function StepNumber({ n, muted = false }: { n: number; muted?: boolean }) {
  return (
    <span
      className={cn(
        'flex h-7 w-7 shrink-0 items-center justify-center rounded-full border text-xs font-medium tabular-nums',
        muted ? 'border-dashed border-border text-muted-foreground' : 'border-border bg-muted/40',
      )}
    >
      {n}
    </span>
  );
}
