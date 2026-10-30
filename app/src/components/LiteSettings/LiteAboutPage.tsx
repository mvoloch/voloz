import { Link } from '@tanstack/react-router';
import { ArrowUpRight } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { UpdatesSection } from '@/components/ServerTab/GeneralPage';
import { SettingRow, SettingSection } from '@/components/ServerTab/SettingRow';
import { Button } from '@/components/ui/button';
import { VolozWordmark } from '@/components/VolozWordmark';
import { usePlatform } from '@/platform/PlatformContext';
import { LitePageHeader } from './LiteSettingsLayout';

const REPO_URL = 'https://github.com/mvoloch/voloz';

function ExternalLink({ href, children }: { href: string; children: string }) {
  return (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      className="inline-flex items-center gap-0.5 font-medium text-foreground underline decoration-border underline-offset-4 hover:decoration-foreground"
    >
      {children}
      <ArrowUpRight className="h-3 w-3 text-muted-foreground" />
    </a>
  );
}

/** Version, credits (Voloz is a fork of Voicebox: keep them), updates and the changelog. */
export function LiteAboutPage() {
  const { t } = useTranslation();
  const platform = usePlatform();
  const [version, setVersion] = useState('');
  useEffect(() => {
    platform.metadata
      .getVersion()
      .then(setVersion)
      .catch(() => setVersion(''));
  }, [platform]);

  return (
    <>
      <LitePageHeader title={t('lite.settings.nav.about')} />
      <div className="mt-6 flex items-center gap-4">
        <VolozWordmark className="h-8 w-auto" />
        <span className="font-mono text-xs text-muted-foreground">
          {version ? `v${version}` : ''}
        </span>
      </div>
      <p className="mt-4 max-w-xl text-sm text-muted-foreground">{t('lite.about.tagline')}</p>
      <div className="mt-4 space-y-1 text-sm text-muted-foreground">
        <p>
          {t('settings.about.forkBy')} <ExternalLink href={REPO_URL}>Marcus Voloch</ExternalLink>
        </p>
        <p>
          {t('settings.about.basedOn')}{' '}
          <ExternalLink href="https://github.com/jamiepine/voicebox">Voicebox</ExternalLink>{' '}
          {t('settings.about.by')}{' '}
          <ExternalLink href="https://github.com/jamiepine">Jamie Pine</ExternalLink>
        </p>
      </div>

      <SettingSection>
        <SettingRow
          title={t('lite.about.changelog')}
          description={t('lite.about.changelogHint')}
          action={
            <Button variant="outline" size="sm" asChild>
              <Link to="/settings/changelog">{t('lite.about.view')}</Link>
            </Button>
          }
        />
        <SettingRow
          title="GitHub"
          description={REPO_URL.replace('https://', '')}
          action={
            <Button variant="outline" size="sm" asChild>
              <a href={REPO_URL} target="_blank" rel="noopener noreferrer">
                {t('lite.about.view')}
              </a>
            </Button>
          }
        />
      </SettingSection>

      {platform.metadata.isTauri && <UpdatesSection />}
    </>
  );
}
