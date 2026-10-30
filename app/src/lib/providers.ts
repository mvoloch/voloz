import type { ProviderEntry } from '@/lib/api/types';

/** Host of an endpoint URL, lowercased; empty when it doesn't parse. */
export function hostOf(endpoint: string | null | undefined): string {
  try {
    return new URL(endpoint ?? '').hostname.toLowerCase();
  } catch {
    return '';
  }
}

const PROVIDER_NAMES: Array<[string, string]> = [
  ['groq.com', 'Groq'],
  ['openai.com', 'OpenAI'],
  ['anthropic.com', 'Anthropic'],
  ['openrouter.ai', 'OpenRouter'],
];

/** "Groq", "OpenAI"… for known hosts, the host itself otherwise. */
export function providerName(host: string): string {
  const known = PROVIDER_NAMES.find(([domain]) => host === domain || host.endsWith(`.${domain}`));
  return known ? known[1] : host;
}

/** Same endpoint (ignoring a trailing slash) and model. */
export function sameProvider(a: ProviderEntry, b: ProviderEntry): boolean {
  const trim = (url: string) => url.trim().replace(/\/+$/, '');
  return trim(a.endpoint) === trim(b.endpoint) && a.model === b.model;
}

/** Backups offered in the settings, fastest first (measured 2026-10-30). */
export const BACKUP_PRESETS: Record<'stt' | 'llm', ProviderEntry[]> = {
  stt: [
    { endpoint: 'https://api.groq.com/openai/v1', model: 'whisper-large-v3' },
    { endpoint: 'https://api.openai.com/v1', model: 'gpt-transcribe' },
  ],
  llm: [
    { endpoint: 'https://api.groq.com/openai/v1', model: 'qwen/qwen3.8-27b' },
    { endpoint: 'https://api.openai.com/v1', model: 'gpt-6-luna' },
    { endpoint: 'https://api.anthropic.com/v1', model: 'claude-haiku-5-5' },
  ],
};
