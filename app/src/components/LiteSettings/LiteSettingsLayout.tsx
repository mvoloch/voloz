import { Link, Outlet, useMatchRoute } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import { cn } from '@/lib/utils/cn';
import { version } from '../../../package.json';

const SECTIONS = [
  { to: '/settings', key: 'shortcuts', exact: true },
  { to: '/settings/transcription', key: 'transcription' },
  { to: '/settings/refinement', key: 'refinement' },
  { to: '/settings/vocabulary', key: 'vocabulary' },
  { to: '/settings/general', key: 'general' },
  { to: '/settings/about', key: 'about' },
] as const;

/** Lite settings: a short list of sections on the left, one page at a time. */
export function LiteSettingsLayout() {
  const { t } = useTranslation();
  const matchRoute = useMatchRoute();

  return (
    <div className="grid grid-cols-[13rem_1fr] h-full min-h-0">
      <nav
        className="flex flex-col gap-0.5 border-r border-border px-4 py-7"
        aria-label={t('lite.nav.settings')}
      >
        {SECTIONS.map((section) => {
          const active = matchRoute({ to: section.to, fuzzy: !('exact' in section) });
          return (
            <Link
              key={section.to}
              to={section.to}
              aria-current={active ? 'page' : undefined}
              className={cn(
                'rounded-lg px-3 py-2 text-sm font-medium transition-colors',
                active
                  ? 'bg-card text-foreground shadow-[0_0_0_1px_hsl(var(--border))]'
                  : 'text-muted-foreground hover:text-foreground',
              )}
            >
              {t(`lite.settings.nav.${section.key}`)}
            </Link>
          );
        })}
        <span className="mt-auto px-3 font-mono text-[11px] text-muted-foreground/70">
          v{version}
        </span>
      </nav>
      <div className="min-h-0 overflow-y-auto px-14 py-9">
        <div className="max-w-[44rem] pb-16">
          <Outlet />
        </div>
      </div>
    </div>
  );
}

/** Page title and one plain sentence on what the page is for. */
export function LitePageHeader({ title, lede }: { title: string; lede?: string }) {
  return (
    <header className="mb-2">
      <h1 className="font-display text-[1.75rem] font-[650] leading-tight tracking-[-0.03em]">
        {title}
      </h1>
      {lede && <p className="mt-1.5 max-w-xl text-sm text-muted-foreground">{lede}</p>}
    </header>
  );
}
