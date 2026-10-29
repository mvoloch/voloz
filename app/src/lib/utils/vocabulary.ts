import type { ReplacementRule, Snippet } from '@/lib/api/types';

/** Trim and collapse inner whitespace, as the server does. */
export function cleanTerm(value: string): string {
  return value.trim().replace(/\s+/g, ' ');
}

/** Split a typed or pasted entry into terms: commas and new lines separate them. */
export function splitTerms(value: string): string[] {
  return value.split(/[,\n]/).map(cleanTerm).filter(Boolean);
}

/** Append the terms not already listed (case-insensitive); existing spellings win. */
export function mergeVocabulary(existing: string[], added: string[]): string[] {
  const seen = new Set(existing.map((term) => term.toLocaleLowerCase()));
  const merged = [...existing];
  for (const raw of added) {
    const term = cleanTerm(raw);
    const key = term.toLocaleLowerCase();
    if (!term || seen.has(key)) continue;
    seen.add(key);
    merged.push(term);
  }
  return merged;
}

/** Add rules; a new rule for the same ``from`` (case-insensitive) replaces the old one. */
export function mergeReplacementRules(
  existing: ReplacementRule[],
  added: ReplacementRule[],
): ReplacementRule[] {
  const addedKeys = new Set(added.map((rule) => cleanTerm(rule.from).toLocaleLowerCase()));
  return [
    ...existing.filter((rule) => !addedKeys.has(cleanTerm(rule.from).toLocaleLowerCase())),
    ...added.map((rule) => ({ from: cleanTerm(rule.from), to: cleanTerm(rule.to) })),
  ];
}

/** Trigger key as the server compares it: case, accents and punctuation ignored. */
export function snippetKey(trigger: string): string {
  const folded = trigger.normalize('NFKD').replace(/\p{M}/gu, '').toLocaleLowerCase();
  return (folded.match(/[\p{L}\p{N}_]+/gu) ?? []).join(' ');
}

/** Add a shortcut; one with the same trigger key replaces the old one. */
export function mergeSnippets(existing: Snippet[], added: Snippet): Snippet[] {
  const key = snippetKey(added.trigger);
  return [
    ...existing.filter((snippet) => snippetKey(snippet.trigger) !== key),
    { trigger: cleanTerm(added.trigger), text: added.text.replace(/\r\n?/g, '\n').trim() },
  ];
}
