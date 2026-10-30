import { Link, useMatchRoute } from '@tanstack/react-router';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { VolozWordmark } from '@/components/VolozWordmark';
import { useDictationReadiness } from '@/lib/hooks/useDictationReadiness';
import { useCaptureSettings } from '@/lib/hooks/useSettings';
import { hostOf, providerName } from '@/lib/providers';
import { cn } from '@/lib/utils/cn';
import { usePlatform } from '@/platform/PlatformContext';
import type { UpdateStatus } from '@/platform/types';

/** "qwen/qwen3.8-27b" → "qwen3.8-27b": the vendor prefix is noise here. */
const shortModel = (model: string) => model.split('/').pop() ?? model;

/** What dictation runs on right now, e.g. "Groq · whisper-large-v3 → qwen3.8-27b". */
function ActiveModels() {
  const { t } = useTranslation();
  const { settings } = useCaptureSettings();
  const readiness = useDictationReadiness();
  const stt = settings?.custom_stt_model;
  const llm = settings?.auto_refine ? settings?.custom_llm_model : null;
  const sttHost = hostOf(settings?.custom_stt_endpoint);
  const llmHost = hostOf(settings?.custom_llm_endpoint);
  if (!settings || readiness.isLoading) return null;

  if (!readiness.canRecord || !stt) {
    return (
      <Link
        to="/settings/transcription"
        className="flex items-center gap-2 text-xs font-medium text-signal"
      >
        <span className="h-1.5 w-1.5 rounded-full bg-signal" />
        {t('lite.status.notReady')}
      </Link>
    );
  }
  const sttLabel = `${providerName(sttHost)} · ${shortModel(stt)}`;
  const llmLabel = llm
    ? llmHost === sttHost
      ? shortModel(llm)
      : `${providerName(llmHost)} · ${shortModel(llm)}`
    : null;
  return (
    <span className="flex min-w-0 items-center gap-2 font-mono text-xs text-muted-foreground">
      <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-emerald-500" />
      <span className="truncate">{llmLabel ? `${sttLabel} → ${llmLabel}` : sttLabel}</span>
    </span>
  );
}

function NavLink({ to, label, exact }: { to: '/' | '/settings'; label: string; exact?: boolean }) {
  const matchRoute = useMatchRoute();
  const active = matchRoute({ to, fuzzy: !exact });
  return (
    <Link
      to={to}
      aria-current={active ? 'page' : undefined}
      className={cn(
        'rounded-full px-3.5 py-1.5 text-sm font-medium transition-colors',
        active
          ? 'bg-card text-foreground shadow-[0_0_0_1px_hsl(var(--border))]'
          : 'text-muted-foreground hover:text-foreground',
      )}
    >
      {label}
    </Link>
  );
}

/** Lite has two places to go: a top bar instead of the Studio sidebar. */
export function LiteTopBar() {
  const { t } = useTranslation();
  const platform = usePlatform();
  const [updateStatus, setUpdateStatus] = useState<UpdateStatus>(platform.updater.getStatus());
  useEffect(() => platform.updater.subscribe(setUpdateStatus), [platform.updater]);

  return (
    <header className="flex h-14 shrink-0 items-center gap-6 border-b border-border px-7">
      <VolozWordmark className="h-5 w-auto" />
      <nav className="flex gap-1">
        <NavLink to="/" label={t('lite.nav.home')} exact />
        <NavLink to="/settings" label={t('lite.nav.settings')} />
      </nav>
      <div className="ml-auto flex min-w-0 items-center gap-3">
        <ActiveModels />
        {updateStatus.available && (
          <Link
            to="/settings/about"
            className="rounded-full bg-foreground px-2.5 py-0.5 text-[11px] font-semibold text-background"
          >
            {t('nav.updateBadge')}
          </Link>
        )}
      </div>
    </header>
  );
}
