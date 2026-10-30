import { Link } from '@tanstack/react-router';
import { Keyboard } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { AccessibilityNotice } from '@/components/AccessibilityGate/AccessibilityGate';
import { ChordPicker } from '@/components/ChordPicker/ChordPicker';
import { InputMonitoringNotice } from '@/components/InputMonitoringGate/InputMonitoringGate';
import { AudioRetentionRow } from '@/components/ServerTab/AudioRetentionRow';
import { ChordPreview, HotkeyPillPreview, sameChord } from '@/components/ServerTab/CapturesPage';
import { LanguageSelect } from '@/components/ServerTab/LanguageSelect';
import { SettingRow, SettingSection } from '@/components/ServerTab/SettingRow';
import { SnippetsSection } from '@/components/ServerTab/SnippetsSection';
import { ThemeSelect } from '@/components/ServerTab/ThemeSelect';
import { VocabularySection } from '@/components/ServerTab/VocabularySection';
import { Button } from '@/components/ui/button';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Toggle } from '@/components/ui/toggle';
import { useToast } from '@/components/ui/use-toast';
import { useCaptureSettings } from '@/lib/hooks/useSettings';
import { defaultChordKeys } from '@/lib/utils/keyCodes';
import { usePlatform } from '@/platform/PlatformContext';
import { useServerStore } from '@/stores/serverStore';
import { LitePageHeader } from './LiteSettingsLayout';
import { ProviderList } from './ProviderList';

type ChordKind = 'push' | 'toggle' | 'command';

export function LiteShortcutsPage() {
  const { t } = useTranslation();
  const { toast } = useToast();
  const { settings, update } = useCaptureSettings();
  const [editing, setEditing] = useState<ChordKind | null>(null);
  const enabled = settings?.hotkey_enabled ?? false;
  const keys: Record<ChordKind, string[]> = {
    push: settings?.chord_push_to_talk_keys ?? defaultChordKeys('push'),
    toggle: settings?.chord_toggle_to_talk_keys ?? defaultChordKeys('toggle'),
    command: settings?.chord_command_keys ?? defaultChordKeys('command'),
  };
  const rows: Array<{ kind: ChordKind; i18n: string; picker: string }> = [
    { kind: 'push', i18n: 'pushToTalk', picker: 'ptt' },
    { kind: 'toggle', i18n: 'toggle', picker: 'toggle' },
    { kind: 'command', i18n: 'command', picker: 'command' },
  ];

  const save = (kind: ChordKind, chord: string[]) => {
    if (kind === 'command' && (sameChord(chord, keys.push) || sameChord(chord, keys.toggle))) {
      // One press would start both; the keyboard hook can't tell them apart.
      toast({
        title: t('settings.captures.dictation.chordPicker.conflict'),
        variant: 'destructive',
      });
      return;
    }
    const field = {
      push: 'chord_push_to_talk_keys',
      toggle: 'chord_toggle_to_talk_keys',
      command: 'chord_command_keys',
    } as const;
    update({ [field[kind]]: chord });
    setEditing(null);
  };

  return (
    <>
      <LitePageHeader title={t('lite.shortcuts.title')} lede={t('lite.shortcuts.lede')} />
      <SettingSection>
        <SettingRow
          title={t('settings.captures.dictation.globalShortcut.title')}
          description={t('settings.captures.dictation.globalShortcut.description')}
          htmlFor="hotkeyEnabled"
          action={
            <Toggle
              id="hotkeyEnabled"
              checked={enabled}
              onCheckedChange={(v) => update({ hotkey_enabled: v })}
            />
          }
        >
          <InputMonitoringNotice enabled={enabled} />
        </SettingRow>
        {rows.map((row) => (
          <SettingRow
            key={row.kind}
            title={t(`settings.captures.dictation.${row.i18n}.title`)}
            description={t(`settings.captures.dictation.${row.i18n}.description`)}
            action={
              <div className="flex items-center gap-2">
                <ChordPreview keys={keys[row.kind]} />
                <Button
                  variant="outline"
                  size="sm"
                  disabled={!enabled}
                  onClick={() => setEditing(row.kind)}
                >
                  <Keyboard className="h-3.5 w-3.5 mr-1.5" />
                  {t(`settings.captures.dictation.${row.i18n}.change`)}
                </Button>
              </div>
            }
          />
        ))}
      </SettingSection>

      <SettingSection title={t('settings.captures.dictation.preview.title')}>
        <SettingRow title={t('settings.captures.dictation.preview.description')}>
          <HotkeyPillPreview enabled={enabled} />
        </SettingRow>
      </SettingSection>

      {rows.map((row) => (
        <ChordPicker
          key={row.kind}
          open={editing === row.kind}
          title={t(`settings.captures.dictation.chordPicker.${row.picker}Title`)}
          description={t(`settings.captures.dictation.chordPicker.${row.picker}Description`)}
          initialKeys={keys[row.kind]}
          onCancel={() => setEditing(null)}
          onSave={(chord) => save(row.kind, chord)}
        />
      ))}
    </>
  );
}

const LANGUAGES = ['auto', 'pt', 'en', 'es', 'fr', 'de', 'ja', 'zh', 'hi'] as const;

export function LiteTranscriptionPage() {
  const { t } = useTranslation();
  const { settings, update } = useCaptureSettings();
  return (
    <>
      <LitePageHeader title={t('lite.transcription.title')} lede={t('lite.transcription.lede')} />
      <ProviderList kind="stt" settings={settings} update={update} />
      <SettingSection title={t('lite.groups.speech')}>
        <SettingRow
          title={t('settings.captures.transcription.language.title')}
          description={t('settings.captures.transcription.language.description')}
          action={
            <Select
              value={settings?.language ?? 'auto'}
              onValueChange={(v) => update({ language: v })}
            >
              <SelectTrigger className="w-[200px]">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {LANGUAGES.map((code) => (
                  <SelectItem key={code} value={code}>
                    {t(`settings.captures.transcription.language.${code}`)}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          }
        />
        <AudioRetentionRow value={settings?.audio_retention ?? 'always'} update={update} />
      </SettingSection>
    </>
  );
}

const CLEANUP = [
  ['smartCleanup', 'smart_cleanup'],
  ['selfCorrection', 'self_correction'],
  ['adaptToApp', 'adapt_to_app'],
  ['preserveTechnical', 'preserve_technical'],
] as const;

export function LiteRefinementPage() {
  const { t } = useTranslation();
  const { settings, update } = useCaptureSettings();
  const autoRefine = settings?.auto_refine ?? true;
  return (
    <>
      <LitePageHeader title={t('lite.refinement.title')} lede={t('lite.refinement.lede')} />
      <SettingSection>
        <SettingRow
          title={t('settings.captures.refinement.auto.title')}
          description={t('lite.refinement.autoDescription')}
          htmlFor="autoRefine"
          action={
            <Toggle
              id="autoRefine"
              checked={autoRefine}
              onCheckedChange={(v) => update({ auto_refine: v })}
            />
          }
        />
      </SettingSection>
      <ProviderList kind="llm" settings={settings} update={update} />
      <SettingSection title={t('lite.groups.cleanup')}>
        {CLEANUP.map(([key, field]) => (
          <SettingRow
            key={field}
            title={t(`settings.captures.refinement.${key}.title`)}
            description={t(`settings.captures.refinement.${key}.description`)}
            htmlFor={field}
            action={
              <Toggle
                id={field}
                checked={settings?.[field] ?? field !== 'preserve_technical'}
                disabled={!autoRefine}
                onCheckedChange={(v) => update({ [field]: v })}
              />
            }
          />
        ))}
      </SettingSection>
    </>
  );
}

export function LiteVocabularyPage() {
  const { t } = useTranslation();
  const { settings, update } = useCaptureSettings();
  return (
    <>
      <LitePageHeader title={t('lite.vocabulary.title')} />
      <VocabularySection settings={settings} update={update} />
      <SnippetsSection settings={settings} update={update} />
    </>
  );
}

/** Start at login. Reads and writes the OS entry itself, not a stored copy. */
function AutostartToggle() {
  const { t } = useTranslation();
  const platform = usePlatform();
  const { toast } = useToast();
  // null until the OS entry has been read; the switch stays disabled.
  const [enabled, setEnabled] = useState<boolean | null>(null);
  useEffect(() => {
    platform.autostart
      .isEnabled()
      .then(setEnabled)
      .catch((error) => console.error('Failed to read the autostart entry:', error));
  }, [platform]);
  return (
    <Toggle
      id="autostart"
      checked={enabled ?? false}
      disabled={enabled === null}
      onCheckedChange={(checked: boolean) => {
        setEnabled(checked);
        platform.autostart.setEnabled(checked).catch((error) => {
          setEnabled(!checked);
          toast({
            title: t('settings.saveFailed'),
            description: error instanceof Error ? error.message : String(error),
            variant: 'destructive',
          });
        });
      }}
    />
  );
}

export function LiteGeneralPage() {
  const { t } = useTranslation();
  const platform = usePlatform();
  const serverUrl = useServerStore((s) => s.serverUrl);
  const { settings, update } = useCaptureSettings();
  const [capturesPath, setCapturesPath] = useState<string | null>(null);

  useEffect(() => {
    fetch(`${serverUrl}/health/filesystem`)
      .then((res) => res.json())
      .then((data) => {
        const dir = data.directories?.find((d: { path: string }) => d.path.includes('captures'));
        if (dir?.path) setCapturesPath(dir.path);
      })
      .catch(() => {});
  }, [serverUrl]);

  const openFolder = useCallback(() => {
    if (capturesPath) platform.filesystem.openPath(capturesPath).catch(console.error);
  }, [platform, capturesPath]);

  return (
    <>
      <LitePageHeader title={t('lite.general.title')} lede={t('lite.general.lede')} />
      <SettingSection>
        {platform.metadata.isTauri && (
          <SettingRow
            title={t('settings.general.autostart.title')}
            description={t('settings.general.autostart.description')}
            htmlFor="autostart"
            action={<AutostartToggle />}
          />
        )}
        <SettingRow
          title={t('settings.captures.dictation.autoPaste.title')}
          description={t('settings.captures.dictation.autoPaste.description')}
          htmlFor="autoPaste"
          action={
            <Toggle
              id="autoPaste"
              checked={settings?.allow_auto_paste ?? true}
              onCheckedChange={(v) => update({ allow_auto_paste: v })}
            />
          }
        >
          <AccessibilityNotice />
        </SettingRow>
        <SettingRow title={t('settings.language.label')} action={<LanguageSelect />} />
        <SettingRow title={t('settings.theme.label')} action={<ThemeSelect />} />
      </SettingSection>

      <SettingSection title={t('lite.groups.dictations')}>
        <SettingRow
          title={t('lite.general.folder')}
          description={capturesPath ?? undefined}
          action={
            <Button variant="outline" size="sm" disabled={!capturesPath} onClick={openFolder}>
              {t('lite.general.open')}
            </Button>
          }
        />
        {platform.metadata.isTauri && (
          <SettingRow
            title={t('lite.general.log')}
            description={t('lite.general.logHint')}
            action={
              <Button variant="outline" size="sm" asChild>
                <Link to="/settings/logs">{t('lite.general.logOpen')}</Link>
              </Button>
            }
          />
        )}
      </SettingSection>
    </>
  );
}
