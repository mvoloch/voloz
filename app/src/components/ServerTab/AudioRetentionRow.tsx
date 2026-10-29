import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import type { AudioRetention, CaptureSettingsUpdate } from '@/lib/api/types';
import { SettingRow } from './SettingRow';

// Shortest-lived last: moving right in this list deletes audio.
const POLICIES: AudioRetention[] = ['always', '30d', '7d', 'never'];

/**
 * "Keep dictation audio" setting. Transcripts always stay; the server
 * deletes audio past the chosen window at startup, after each capture and
 * as soon as the setting changes — so a shorter window asks first.
 */
export function AudioRetentionRow({
  value,
  update,
}: {
  value: AudioRetention;
  update: (patch: CaptureSettingsUpdate) => void;
}) {
  const { t } = useTranslation();
  const [pending, setPending] = useState<AudioRetention | null>(null);

  const choose = (next: AudioRetention) => {
    if (next === value) return;
    if (POLICIES.indexOf(next) > POLICIES.indexOf(value)) {
      setPending(next);
    } else {
      update({ audio_retention: next });
    }
  };

  return (
    <>
      <SettingRow
        title={t('settings.captures.storage.audioRetention.title')}
        description={t('settings.captures.storage.audioRetention.description')}
        action={
          <Select value={value} onValueChange={(v) => choose(v as AudioRetention)}>
            <SelectTrigger className="w-[180px]">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {POLICIES.map((policy) => (
                <SelectItem key={policy} value={policy}>
                  {t(`settings.captures.storage.audioRetention.options.${policy}`)}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        }
      />
      <AlertDialog open={pending !== null} onOpenChange={(open) => !open && setPending(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {t('settings.captures.storage.audioRetention.confirmTitle')}
            </AlertDialogTitle>
            <AlertDialogDescription>
              {pending === 'never'
                ? t('settings.captures.storage.audioRetention.confirmNever')
                : t('settings.captures.storage.audioRetention.confirmOlder', {
                    period: pending
                      ? t(`settings.captures.storage.audioRetention.options.${pending}`)
                      : '',
                  })}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t('common.cancel')}</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                if (pending) update({ audio_retention: pending });
                setPending(null);
              }}
            >
              {t('settings.captures.storage.audioRetention.confirm')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}
