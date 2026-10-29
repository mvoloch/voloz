import type { PlatformAutostart } from '@/platform/types';

export const webAutostart: PlatformAutostart = {
  async isEnabled(): Promise<boolean> {
    // The browser can't start anything at login
    return false;
  },

  async setEnabled(_enabled: boolean): Promise<void> {
    throw new Error('Starting at login is only available in the desktop app');
  },
};
