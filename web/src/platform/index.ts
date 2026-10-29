import type { Platform } from '@/platform/types';
import { webFilesystem } from './filesystem';
import { webUpdater } from './updater';
import { webAudio } from './audio';
import { webLifecycle } from './lifecycle';
import { webAutostart } from './autostart';
import { webMetadata } from './metadata';

export const webPlatform: Platform = {
  filesystem: webFilesystem,
  updater: webUpdater,
  audio: webAudio,
  lifecycle: webLifecycle,
  autostart: webAutostart,
  metadata: webMetadata,
};
