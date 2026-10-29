import { disable, enable, isEnabled } from '@tauri-apps/plugin-autostart';
import type { PlatformAutostart } from '@/platform/types';

export const tauriAutostart: PlatformAutostart = {
  isEnabled,
  async setEnabled(enabled: boolean): Promise<void> {
    await (enabled ? enable() : disable());
  },
};
