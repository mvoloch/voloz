import { Link, useMatchRoute } from '@tanstack/react-router';
import {
  AudioLines,
  Box,
  House,
  type LucideIcon,
  Mic,
  Settings,
  Volume2,
  Wand2,
} from 'lucide-react';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { VolozWordmark } from '@/components/VolozWordmark';
import { cn } from '@/lib/utils/cn';
import { usePlatform } from '@/platform/PlatformContext';
import type { UpdateStatus } from '@/platform/types';
import { usePlayerStore } from '@/stores/playerStore';
import { version } from '../../package.json';

interface SidebarProps {
  isMacOS?: boolean;
}

interface NavTab {
  id: string;
  path: '/' | '/generate' | '/stories' | '/voices' | '/effects' | '/models' | '/settings';
  icon: LucideIcon;
  labelKey: string;
}

/** Dictation first; the voice studio is grouped below it. */
const homeTab: NavTab = { id: 'home', path: '/', icon: House, labelKey: 'nav.home' };

const studioTabs: NavTab[] = [
  { id: 'generate', path: '/generate', icon: Volume2, labelKey: 'nav.generate' },
  { id: 'stories', path: '/stories', icon: AudioLines, labelKey: 'nav.stories' },
  { id: 'voices', path: '/voices', icon: Mic, labelKey: 'nav.voices' },
  { id: 'effects', path: '/effects', icon: Wand2, labelKey: 'nav.effects' },
  { id: 'models', path: '/models', icon: Box, labelKey: 'nav.models' },
];

const settingsTab: NavTab = {
  id: 'settings',
  path: '/settings',
  icon: Settings,
  labelKey: 'nav.settings',
};

function NavItem({ tab }: { tab: NavTab }) {
  const { t } = useTranslation();
  const matchRoute = useMatchRoute();
  const Icon = tab.icon;
  const isActive =
    tab.path === '/' ? matchRoute({ to: '/', fuzzy: false }) : matchRoute({ to: tab.path, fuzzy: true });

  return (
    <Link
      to={tab.path}
      className={cn(
        'relative flex items-center gap-3 h-9 px-3 rounded-lg text-sm transition-colors',
        isActive
          ? 'bg-muted/60 text-foreground font-medium'
          : 'text-muted-foreground hover:text-foreground hover:bg-muted/30',
      )}
      aria-current={isActive ? 'page' : undefined}
    >
      {/* Active marker: a caret-like bar on the sidebar edge */}
      {isActive && (
        <span className="absolute -left-3 top-2 bottom-2 w-[3px] rounded-r-sm bg-accent" />
      )}
      <Icon className="h-[18px] w-[18px] shrink-0" />
      <span className="truncate">{t(tab.labelKey)}</span>
    </Link>
  );
}

export function Sidebar({ isMacOS }: SidebarProps) {
  const { t } = useTranslation();
  const isPlayerOpen = !!usePlayerStore((s) => s.audioUrl);
  const platform = usePlatform();

  const [updateStatus, setUpdateStatus] = useState<UpdateStatus>(platform.updater.getStatus());
  useEffect(() => platform.updater.subscribe(setUpdateStatus), [platform.updater]);

  return (
    <div
      className={cn(
        'fixed left-0 top-0 h-full w-[14.5rem] bg-sidebar border-r border-border flex flex-col px-3 py-6',
        isMacOS && 'pt-14',
      )}
    >
      <div className="px-3 mb-7">
        <VolozWordmark className="h-6 w-auto" />
      </div>

      <nav className="flex flex-col gap-1">
        <NavItem tab={homeTab} />
      </nav>

      <div className="px-3 mt-6 mb-2 text-[11px] font-medium uppercase tracking-wider text-muted-foreground/60">
        {t('nav.studio')}
      </div>
      <nav className="flex flex-col gap-1">
        {studioTabs.map((tab) => (
          <NavItem key={tab.id} tab={tab} />
        ))}
      </nav>

      <div
        className="mt-auto flex flex-col gap-2 transition-all duration-300"
        style={{ paddingBottom: isPlayerOpen ? '7rem' : undefined }}
      >
        <nav className="flex flex-col">
          <NavItem tab={settingsTab} />
        </nav>
        <div className="flex items-center gap-2 px-3">
          <span className="text-[11px] text-muted-foreground/50">v{version}</span>
          {updateStatus.available && (
            <Link
              to="/settings"
              className="text-[9px] font-semibold tracking-wide uppercase px-2 py-0.5 rounded-full bg-accent/15 text-accent hover:bg-accent/25 transition-colors"
            >
              {t('nav.updateBadge')}
            </Link>
          )}
        </div>
      </div>
    </div>
  );
}
