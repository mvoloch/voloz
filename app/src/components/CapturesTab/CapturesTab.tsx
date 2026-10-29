import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { listen, type UnlistenFn } from '@tauri-apps/api/event';
import { save } from '@tauri-apps/plugin-dialog';
import { writeFile, writeTextFile } from '@tauri-apps/plugin-fs';
import {
  Captions,
  Check,
  ChevronDown,
  CircleDot,
  Copy,
  Download,
  FileAudio,
  FileText,
  Loader2,
  Mic,
  Settings2,
  Sparkles,
  Square,
  Trash2,
  Upload,
  Volume2,
  Wand2,
  X,
} from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import { AudioBars } from '@/components/AudioBars';
import { CapturePill } from '@/components/CapturePill/CapturePill';
import { CaptureInlinePlayer } from '@/components/CapturesTab/CaptureInlinePlayer';
import { DictationReadinessChecklist } from '@/components/CapturesTab/DictationReadinessChecklist';
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
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Textarea } from '@/components/ui/textarea';
import { ListPaneSearch } from '@/components/ListPane';
import { useToast } from '@/components/ui/use-toast';
import { apiClient } from '@/lib/api/client';
import type {
  CaptureListResponse,
  CaptureResponse,
  CaptureSource,
  ReplacementRule,
  VoiceProfileResponse,
} from '@/lib/api/types';
import type { LanguageCode } from '@/lib/constants/languages';
import { BOTTOM_SAFE_AREA_PADDING } from '@/lib/constants/ui';
import { IS_LITE } from '@/lib/edition';
import { useCaptureRecordingSession } from '@/lib/hooks/useCaptureRecordingSession';
import { useDictationReadiness } from '@/lib/hooks/useDictationReadiness';
import { useCaptureSettings } from '@/lib/hooks/useSettings';
import { cn } from '@/lib/utils/cn';
import { formatAbsoluteDate, parseServerDate } from '@/lib/utils/format';
import { displayLabelForKey, modifierSideHint } from '@/lib/utils/keyCodes';
import { mergeReplacementRules, mergeVocabulary } from '@/lib/utils/vocabulary';
import { useGenerationStore } from '@/stores/generationStore';
import { usePlayerStore } from '@/stores/playerStore';

const CAPTURE_AUDIO_MIME = 'audio/*,.wav,.mp3,.m4a,.flac,.ogg,.webm';

function formatDuration(ms?: number | null): string {
  if (!ms || ms < 0) return '0:00';
  const total = Math.round(ms / 1000);
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
}

function ChordKeys({ keys, size = 'sm' }: { keys: string[]; size?: 'sm' | 'lg' }) {
  if (keys.length === 0) return null;
  return (
    <div className={cn('flex items-center', size === 'lg' ? 'gap-1.5' : 'gap-1')}>
      {keys.map((k) => {
        const side = modifierSideHint(k);
        return (
          <span
            key={k}
            className={cn(
              'relative inline-flex items-center justify-center rounded-md border border-border bg-muted/60 font-mono font-medium shadow-sm text-foreground',
              size === 'lg'
                ? 'h-9 min-w-[2.25rem] px-2.5 text-sm border-b-2 tracking-normal'
                : 'h-6 min-w-[1.5rem] px-1.5 text-[11px]',
            )}
          >
            {displayLabelForKey(k)}
            {side ? (
              <span className="absolute -top-1 -right-1 h-3 min-w-[0.75rem] px-0.5 rounded-sm bg-accent text-[7px] font-bold leading-none flex items-center justify-center text-accent-foreground">
                {side}
              </span>
            ) : null}
          </span>
        );
      })}
    </div>
  );
}

function SourceBadge({ source }: { source: CaptureSource }) {
  const { t } = useTranslation();
  const Icon =
    source === 'dictation'
      ? Mic
      : source === 'recording'
        ? CircleDot
        : source === 'command'
          ? Wand2
          : FileAudio;
  const label = t(`captures.source.${source}`);
  return (
    <Badge
      variant="secondary"
      className="h-5 px-1.5 text-[10px] gap-1 font-medium bg-muted/60 text-muted-foreground"
    >
      <Icon className="h-2.5 w-2.5" />
      {label}
    </Badge>
  );
}

/** "outlook.exe" → "outlook"; a macOS bundle id stays as it is. */
function appLabel(app: string): string {
  return app.replace(/\.exe$/i, '');
}

interface DayGroup {
  key: string;
  label: string;
  items: CaptureResponse[];
}

function dayLabel(date: Date, language: string, t: TFunction): string {
  const today = new Date();
  const yesterday = new Date(today);
  yesterday.setDate(today.getDate() - 1);
  if (date.toDateString() === today.toDateString()) return t('home.today');
  if (date.toDateString() === yesterday.toDateString()) return t('home.yesterday');
  return date.toLocaleDateString(language, { weekday: 'long', day: 'numeric', month: 'long' });
}

/** Group captures (already newest first) by local calendar day. */
function groupByDay(captures: CaptureResponse[], language: string, t: TFunction): DayGroup[] {
  const groups = new Map<string, DayGroup>();
  for (const capture of captures) {
    const date = parseServerDate(capture.created_at);
    const key = date.toDateString();
    let group = groups.get(key);
    if (!group) {
      group = { key, label: dayLabel(date, language, t), items: [] };
      groups.set(key, group);
    }
    group.items.push(capture);
  }
  return [...groups.values()];
}

/** "openai/whisper-large-v3-turbo" -> "whisper-large-v3-turbo". */
function shortModelName(model: string): string {
  return model.split('/').pop() || model;
}

function formatTime(date: string, language: string): string {
  return parseServerDate(date).toLocaleTimeString(language, { hour: '2-digit', minute: '2-digit' });
}

/** Totals over the loaded captures: words of the best transcript and minutes of audio. */
function summarize(captures: CaptureResponse[]): { words: number; minutes: number } {
  let words = 0;
  let ms = 0;
  for (const capture of captures) {
    const text = (capture.transcript_refined || capture.transcript_raw || '').trim();
    if (text) words += text.split(/\s+/).length;
    ms += capture.duration_ms ?? 0;
  }
  return { words, minutes: Math.round(ms / 60000) };
}

function Stat({ value, label }: { value: string; label: string }) {
  return (
    <div>
      <div className="text-2xl font-semibold tabular-nums tracking-tight">{value}</div>
      <div className="text-xs text-muted-foreground">{label}</div>
    </div>
  );
}

interface CaptureRowProps {
  capture: CaptureResponse;
  text: string;
  time: string;
  isLatest: boolean;
  isOpen: boolean;
  onOpen: () => void;
  onCopy: () => void;
}

function CaptureRow({ capture, text, time, isLatest, isOpen, onOpen, onCopy }: CaptureRowProps) {
  const { t } = useTranslation();
  const sourceLabel = t(`captures.source.${capture.source}`);
  return (
    <div
      className={cn(
        'group flex items-start gap-4 -mx-3 px-3 py-3.5 rounded-lg transition-colors',
        isOpen ? 'bg-muted/60' : 'hover:bg-muted/30',
      )}
    >
      <span className="w-12 shrink-0 pt-0.5 text-xs tabular-nums text-muted-foreground/70">{time}</span>
      <button type="button" onClick={onOpen} className="flex-1 min-w-0 text-left rounded-sm">
        <p className="text-[15px] leading-relaxed text-foreground/90 line-clamp-3">
          {text}
          {/* The newest dictation ends in the brand caret */}
          {isLatest && (
            <span
              aria-hidden
              className="voloz-caret inline-block w-[2px] h-[1.05em] ml-1 align-[-0.15em] bg-accent"
            />
          )}
        </p>
        <p className="mt-1 text-xs text-muted-foreground/70">
          {sourceLabel} · {formatDuration(capture.duration_ms)}
          {capture.transcript_refined ? ` · ${t('captures.transcript.refined')}` : ''}
        </p>
      </button>
      <Button
        variant="ghost"
        size="icon"
        onClick={onCopy}
        className="h-8 w-8 shrink-0 text-muted-foreground opacity-0 group-hover:opacity-100 focus-visible:opacity-100"
        aria-label={t('captures.actions.copy')}
        title={t('captures.actions.copy')}
      >
        <Copy className="h-4 w-4" />
      </Button>
    </div>
  );
}

type PlaybackState = 'idle' | 'generating' | 'playing';

export function CapturesTab() {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const { toast } = useToast();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const uploadInputRef = useRef<HTMLInputElement>(null);

  const snippetOf = (capture: CaptureResponse): string => {
    const source = capture.transcript_refined || capture.transcript_raw || '';
    return source.trim() || t('captures.snippetEmpty');
  };

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [showRefined, setShowRefined] = useState(true);
  const [launchedPlayAsId, setLaunchedPlayAsId] = useState<string | null>(null);
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
  const [detailOpen, setDetailOpen] = useState(false);

  const audioUrl = usePlayerStore((s) => s.audioUrl);
  const playerAudioId = usePlayerStore((s) => s.audioId);
  const playerIsPlaying = usePlayerStore((s) => s.isPlaying);
  const isPlayerVisible = !!audioUrl;

  const setIsPlaying = usePlayerStore((s) => s.setIsPlaying);

  const addPendingGeneration = useGenerationStore((s) => s.addPendingGeneration);
  const pendingGenerationIds = useGenerationStore((s) => s.pendingGenerationIds);

  const { settings: captureSettings, update: updateCaptureSettings } = useCaptureSettings();
  const sttModel = captureSettings?.stt_model ?? 'turbo';
  const llmModel = captureSettings?.llm_model ?? '0.6B';
  const hotkeyEnabled = captureSettings?.hotkey_enabled ?? false;
  const pushToTalkKeys = captureSettings?.chord_push_to_talk_keys ?? [];
  const toggleToTalkKeys = captureSettings?.chord_toggle_to_talk_keys ?? [];
  // A custom endpoint replaces the on-device model only when both URL and model are set
  // (same rule as backend/routes/captures.py). Lite has no on-device model to fall back to.
  const sttLabel =
    captureSettings?.custom_stt_endpoint && captureSettings.custom_stt_model
      ? shortModelName(captureSettings.custom_stt_model)
      : IS_LITE
        ? t('captures.readiness.stt.labelLite')
        : `Whisper ${sttModel.charAt(0).toUpperCase()}${sttModel.slice(1)}`;
  const llmLabel =
    captureSettings?.custom_llm_endpoint && captureSettings.custom_llm_model
      ? shortModelName(captureSettings.custom_llm_model)
      : IS_LITE
        ? t('captures.readiness.llm.labelLite')
        : `Qwen3 ${llmModel}`;
  const autoRefine = captureSettings?.auto_refine ?? true;
  const readiness = useDictationReadiness();

  const session = useCaptureRecordingSession({
    onCaptureCreated: (capture) => setSelectedId(capture.id),
  });

  const { data: capturesData, isLoading: capturesLoading } = useQuery({
    queryKey: ['captures'],
    queryFn: () => apiClient.listCaptures(200, 0),
  });

  const { data: profiles } = useQuery({
    queryKey: ['profiles'],
    queryFn: () => apiClient.listProfiles(),
    // Voice profiles (for "Play as") don't exist in the lite edition
    enabled: !IS_LITE,
  });

  const captures = capturesData?.items ?? [];
  const stats = useMemo(() => summarize(captures), [captures]);

  // Keep a selection. If the current selection disappears (e.g. deletion),
  // fall through to the first capture, then to null.
  useEffect(() => {
    if (!captures.length) {
      if (selectedId !== null) setSelectedId(null);
      return;
    }
    if (!selectedId || !captures.find((c) => c.id === selectedId)) {
      setSelectedId(captures[0].id);
    }
  }, [captures, selectedId]);

  // Live sync from sibling Tauri webviews (the floating dictate window).
  // ``capture:created`` carries the full row so we can seed the cache before
  // the refetch lands and focus the new capture in one shot — without the
  // seed, the selection-guard effect would snap back to ``captures[0]`` in
  // the race window between ``setSelectedId(new)`` and the refetched list
  // actually containing the new row.
  useEffect(() => {
    const unlistens: Promise<UnlistenFn>[] = [];
    unlistens.push(
      listen<{ capture: CaptureResponse }>('capture:created', (event) => {
        const capture = event.payload?.capture;
        if (capture) {
          queryClient.setQueryData<CaptureListResponse>(['captures'], (prev) => {
            if (!prev) return prev;
            if (prev.items.some((c) => c.id === capture.id)) return prev;
            return { ...prev, items: [capture, ...prev.items], total: prev.total + 1 };
          });
          setSelectedId(capture.id);
        }
        queryClient.invalidateQueries({ queryKey: ['captures'] });
      }),
    );
    unlistens.push(
      listen('capture:updated', () => {
        queryClient.invalidateQueries({ queryKey: ['captures'] });
      }),
    );
    return () => {
      for (const p of unlistens) p.then((fn) => fn()).catch(() => {});
    };
  }, [queryClient]);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return captures;
    return captures.filter((c) => {
      const raw = (c.transcript_raw || '').toLowerCase();
      const refined = (c.transcript_refined || '').toLowerCase();
      return raw.includes(q) || refined.includes(q);
    });
  }, [search, captures]);

  const selected = captures.find((c) => c.id === selectedId) ?? null;
  // Source of truth is capture_settings.default_playback_voice_id, shared
  // with Settings → Captures and the MCP global default. Stale ids (e.g.
  // referenced profile was deleted) fall through to the first profile.
  const storedVoiceId = captureSettings?.default_playback_voice_id ?? null;
  const playAsVoice =
    (storedVoiceId && profiles?.find((p) => p.id === storedVoiceId)) ||
    profiles?.[0] ||
    null;
  const playAsVoiceId = playAsVoice?.id ?? null;

  // Transcript text shown in the details panel, and the version it is.
  const shownTranscript =
    (showRefined && selected?.transcript_refined
      ? selected.transcript_refined
      : selected?.transcript_raw) ?? '';
  const shownBase: 'raw' | 'refined' =
    showRefined && selected?.transcript_refined ? 'refined' : 'raw';
  // Hand edits of the shown transcript, tied to the capture and view they
  // were typed in (same key as the Textarea): switching either drops them.
  const draftKey = `${selected?.id}-${showRefined}`;
  const [draftState, setDraftState] = useState<{ key: string; text: string } | null>(null);
  const transcriptDraft = draftState?.key === draftKey ? draftState.text : null;
  const setTranscriptDraft = (text: string | null) =>
    setDraftState(text === null ? null : { key: draftKey, text });
  const transcriptDirty = transcriptDraft !== null && transcriptDraft !== shownTranscript;
  // Replacement rules offered after a corrected transcript is saved; shown
  // only on that capture.
  const [learnOffer, setLearnOffer] = useState<{
    captureId: string;
    rules: ReplacementRule[];
  } | null>(null);

  const saveTranscriptMutation = useMutation({
    mutationFn: async (vars: { captureId: string; text: string; base: 'raw' | 'refined' }) =>
      apiClient.updateCaptureTranscript(vars.captureId, { text: vars.text, base: vars.base }),
    onSuccess: ({ suggestions, ...capture }) => {
      queryClient.setQueryData<CaptureListResponse>(['captures'], (prev) =>
        prev
          ? { ...prev, items: prev.items.map((c) => (c.id === capture.id ? capture : c)) }
          : prev,
      );
      queryClient.invalidateQueries({ queryKey: ['captures'] });
      // The edit is now the refined text.
      setShowRefined(true);
      setTranscriptDraft(null);
      setLearnOffer(suggestions.length ? { captureId: capture.id, rules: suggestions } : null);
      toast({ title: t('captures.toast.transcriptSaved') });
    },
    onError: (err: Error) => {
      toast({
        title: t('captures.toast.transcriptSaveFailed'),
        description: err.message,
        variant: 'destructive',
      });
    },
  });

  const saveTranscript = () => {
    if (!selected || transcriptDraft === null || !transcriptDirty) return;
    saveTranscriptMutation.mutate({
      captureId: selected.id,
      text: transcriptDraft,
      base: shownBase,
    });
  };

  const dropLearnRule = (rule: ReplacementRule) => {
    setLearnOffer((offer) => {
      if (!offer) return null;
      const rules = offer.rules.filter((r) => r !== rule);
      return rules.length ? { ...offer, rules } : null;
    });
  };

  const learnRule = (rule: ReplacementRule) => {
    updateCaptureSettings({
      replacements: mergeReplacementRules(captureSettings?.replacements ?? [], [rule]),
      vocabulary: mergeVocabulary(captureSettings?.vocabulary ?? [], [rule.to]),
    });
    dropLearnRule(rule);
    toast({ title: t('captures.toast.learned', { from: rule.from, to: rule.to }) });
  };

  const deleteMutation = useMutation({
    mutationFn: async (captureId: string) => apiClient.deleteCapture(captureId),
    onSuccess: () => {
      setDeleteDialogOpen(false);
      setDetailOpen(false);
      queryClient.invalidateQueries({ queryKey: ['captures'] });
    },
    onError: (err: Error) => {
      toast({ title: t('captures.toast.deleteFailed'), description: err.message, variant: 'destructive' });
    },
  });

  const playAsMutation = useMutation({
    mutationFn: async ({ capture, voice }: { capture: CaptureResponse; voice: VoiceProfileResponse }) => {
      const text = capture.transcript_refined || capture.transcript_raw;
      if (!text.trim()) throw new Error(t('captures.noTranscriptError'));
      const language = (capture.language || voice.language) as LanguageCode;
      // Preset profiles (Kokoro etc.) reject the qwen default — honor the
      // profile's stored engine preference. Cloned profiles without an
      // override fall through to whatever the backend picks.
      const engine = voice.default_engine as
        | 'qwen' | 'qwen_custom_voice' | 'qwen_voice_design' | 'luxtts' | 'chatterbox'
        | 'chatterbox_turbo' | 'tada' | 'kokoro' | 'omnivoice'
        | undefined;
      return apiClient.generateSpeech({
        profile_id: voice.id,
        text,
        language,
        engine,
      });
    },
    onSuccess: (result) => {
      // /generate is queue-based — it returns a generating row with an empty
      // audio_path. Hand the id to the global SSE handler which polls
      // /generation/{id}/status and triggers autoplay on completion.
      setLaunchedPlayAsId(result.id);
      addPendingGeneration(result.id);
    },
    onError: (err: Error) => {
      toast({ title: t('captures.toast.playAsFailed'), description: err.message, variant: 'destructive' });
    },
  });

  const playbackState: PlaybackState = playAsMutation.isPending
    ? 'generating'
    : launchedPlayAsId && pendingGenerationIds.has(launchedPlayAsId)
      ? 'generating'
      : launchedPlayAsId && playerAudioId === launchedPlayAsId && playerIsPlaying
        ? 'playing'
        : 'idle';

  const handleUploadClick = () => uploadInputRef.current?.click();

  const handleUploadFile = (e: React.ChangeEvent<HTMLInputElement>, source: CaptureSource) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    session.uploadFile(file, source);
  };

  const handleCopy = async () => {
    if (!selected) return;
    const text = showRefined
      ? selected.transcript_refined || selected.transcript_raw
      : selected.transcript_raw;
    try {
      await navigator.clipboard.writeText(text || '');
      toast({ title: t('captures.toast.transcriptCopied') });
    } catch {
      toast({ title: t('captures.toast.copyFailed'), variant: 'destructive' });
    }
  };

  const exportToastSuccess = (path: string) => {
    const name = path.split(/[\\/]/).pop() ?? path;
    toast({ title: t('captures.toast.exportSuccess', { path: name }) });
  };

  const exportToastError = (err: unknown) => {
    toast({
      title: t('captures.toast.exportFailed'),
      description: err instanceof Error ? err.message : String(err),
      variant: 'destructive',
    });
  };

  const handleExportAudio = async () => {
    if (!selected) return;
    try {
      const dest = await save({
        defaultPath: `capture_${selected.id.slice(0, 8)}.wav`,
        filters: [{ name: 'Audio', extensions: ['wav'] }],
      });
      if (!dest) return;
      const res = await fetch(apiClient.getCaptureAudioUrl(selected.id));
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const buf = new Uint8Array(await res.arrayBuffer());
      await writeFile(dest, buf);
      exportToastSuccess(dest);
    } catch (err) {
      exportToastError(err);
    }
  };

  const handleExportTranscript = async () => {
    if (!selected) return;
    const text = (selected.transcript_refined || selected.transcript_raw || '').trim();
    if (!text) {
      toast({ title: t('captures.toast.exportEmpty'), variant: 'destructive' });
      return;
    }
    try {
      const dest = await save({
        defaultPath: `capture_${selected.id.slice(0, 8)}.txt`,
        filters: [{ name: 'Text', extensions: ['txt'] }],
      });
      if (!dest) return;
      await writeTextFile(dest, text);
      exportToastSuccess(dest);
    } catch (err) {
      exportToastError(err);
    }
  };

  const buildCaptureMarkdown = (capture: CaptureResponse): string => {
    const lines: string[] = [];
    lines.push(`# Capture ${capture.id}`, '');
    lines.push(`- **Source:** ${capture.source}`);
    lines.push(`- **Created:** ${capture.created_at}`);
    if (capture.duration_ms != null) lines.push(`- **Duration:** ${formatDuration(capture.duration_ms)}`);
    if (capture.language) lines.push(`- **Language:** ${capture.language}`);
    if (capture.stt_model) lines.push(`- **STT model:** ${capture.stt_model}`);
    if (capture.llm_model) lines.push(`- **LLM model:** ${capture.llm_model}`);
    if (capture.target_app) lines.push(`- **App:** ${capture.target_app}`);
    lines.push('');
    if (capture.transcript_refined?.trim()) {
      lines.push('## Refined transcript', '', capture.transcript_refined.trim(), '');
    }
    if (capture.transcript_raw?.trim()) {
      lines.push('## Raw transcript', '', capture.transcript_raw.trim(), '');
    }
    return lines.join('\n');
  };

  const handleExportMarkdown = async () => {
    if (!selected) return;
    const hasContent = (selected.transcript_refined || selected.transcript_raw || '').trim();
    if (!hasContent) {
      toast({ title: t('captures.toast.exportEmpty'), variant: 'destructive' });
      return;
    }
    try {
      const dest = await save({
        defaultPath: `capture_${selected.id.slice(0, 8)}.md`,
        filters: [{ name: 'Markdown', extensions: ['md'] }],
      });
      if (!dest) return;
      await writeTextFile(dest, buildCaptureMarkdown(selected));
      exportToastSuccess(dest);
    } catch (err) {
      exportToastError(err);
    }
  };

  const handlePlayAs = (voice?: VoiceProfileResponse) => {
    if (!selected) return;
    // Stop the current playback when the button is in its 'playing' state
    // and the user clicked the main button without picking a new voice.
    if (!voice && playbackState === 'playing') {
      setIsPlaying(false);
      return;
    }
    const target = voice ?? playAsVoice;
    if (!target) {
      toast({
        title: t('captures.toast.noVoice'),
        description: t('captures.toast.noVoiceDescription'),
        variant: 'destructive',
      });
      return;
    }
    if (voice && voice.id !== playAsVoiceId) {
      updateCaptureSettings({ default_playback_voice_id: voice.id });
    }
    playAsMutation.mutate({ capture: selected, voice: target });
  };

  const hasHotkey = hotkeyEnabled && (pushToTalkKeys.length > 0 || toggleToTalkKeys.length > 0);
  const days = groupByDay(filtered, i18n.language, t);
  const latestId = captures[0]?.id;

  const openDetail = (captureId: string) => {
    setSelectedId(captureId);
    setDetailOpen(true);
  };

  const copyCapture = async (capture: CaptureResponse) => {
    try {
      await navigator.clipboard.writeText(capture.transcript_refined || capture.transcript_raw || '');
      toast({ title: t('captures.toast.transcriptCopied') });
    } catch {
      toast({ title: t('captures.toast.copyFailed'), variant: 'destructive' });
    }
  };

  return (
    <div className="h-full flex overflow-hidden -mx-8">
      <input
        ref={uploadInputRef}
        type="file"
        accept={CAPTURE_AUDIO_MIME}
        onChange={(e) => handleUploadFile(e, 'file')}
        className="hidden"
      />
      <input
        ref={fileInputRef}
        type="file"
        accept={CAPTURE_AUDIO_MIME}
        onChange={(e) => handleUploadFile(e, 'file')}
        className="hidden"
      />

      {/* Home: shortcut, numbers and the dictation history */}
      <div className={cn('flex-1 min-w-0 overflow-y-auto', isPlayerVisible && BOTTOM_SAFE_AREA_PADDING)}>
        <div className="max-w-[760px] mx-auto px-8 pt-10 pb-16">
          <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
            <span
              className={cn(
                'w-1.5 h-1.5 rounded-full',
                readiness.canRecord ? 'bg-accent' : 'bg-muted-foreground/50',
              )}
            />
            <span>{autoRefine ? `${sttLabel} · ${llmLabel}` : sttLabel}</span>
          </div>

          {hasHotkey ? (
            <div className="mt-4 space-y-2">
              {pushToTalkKeys.length ? (
                <h1 className="flex flex-wrap items-center gap-x-3 gap-y-2 text-3xl font-semibold tracking-tight">
                  <span>{t('home.hero.hold')}</span>
                  <ChordKeys keys={pushToTalkKeys} size="lg" />
                  <span>{t('home.hero.speak')}</span>
                </h1>
              ) : null}
              <p className="text-[15px] text-muted-foreground">{t('home.hero.holdHint')}</p>
              {toggleToTalkKeys.length ? (
                <p className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
                  <span>{t('home.hero.toggleBefore')}</span>
                  <ChordKeys keys={toggleToTalkKeys} />
                  <span>{t('home.hero.toggleAfter')}</span>
                </p>
              ) : null}
            </div>
          ) : (
            <div className="mt-4 space-y-2">
              <h1 className="text-3xl font-semibold tracking-tight">{t('home.hero.noShortcut')}</h1>
              <p className="text-[15px] text-muted-foreground">{t('home.hero.noShortcutHint')}</p>
            </div>
          )}

          <div className="flex flex-wrap items-center gap-2 mt-6">
            {session.pillState !== 'hidden' ? (
              <CapturePill
                state={session.pillState}
                elapsedMs={session.pillElapsedMs}
                errorMessage={session.errorMessage}
                onDismiss={session.dismissError}
                onStop={session.isRecording ? session.stopRecording : undefined}
              />
            ) : (
              <>
                {/* Hide Dictate when recording readiness fails so the user can't kick off
                    a capture that has nowhere to land. */}
                {readiness.canRecord && (
                  <Button
                    onClick={session.toggleRecording}
                    disabled={session.isUploading}
                    className="bg-accent text-accent-foreground hover:bg-accent/90"
                  >
                    <Mic className="h-4 w-4 mr-2" />
                    {t('home.actions.dictate')}
                  </Button>
                )}
                {readiness.canRecord && (
                  <Button variant="outline" onClick={handleUploadClick} disabled={session.isUploading}>
                    {session.isUploading ? (
                      <Loader2 className="h-4 w-4 mr-2 animate-spin" />
                    ) : (
                      <Upload className="h-4 w-4 mr-2" />
                    )}
                    {session.isUploading ? t('captures.actions.importing') : t('home.actions.import')}
                  </Button>
                )}
                <Button variant="outline" asChild>
                  <Link to="/settings/captures">
                    <Settings2 className="mr-2 h-4 w-4" />
                    {t('home.actions.configure')}
                  </Link>
                </Button>
              </>
            )}
            {/* Stop stays reachable while recording, even if readiness changed mid-capture */}
            {session.isRecording && (
              <Button
                onClick={session.toggleRecording}
                className="bg-accent text-accent-foreground hover:bg-accent/90"
              >
                <Square className="h-4 w-4 mr-2 fill-current" />
                {t('captures.actions.stop')}
              </Button>
            )}
          </div>

          {hotkeyEnabled && !readiness.isLoading && !readiness.canRecord && (
            <div className="mt-8">
              <DictationReadinessChecklist readiness={readiness} />
            </div>
          )}

          {captures.length > 0 && (
            <div className="flex flex-wrap gap-x-10 gap-y-3 mt-8">
              <Stat value={stats.words.toLocaleString(i18n.language)} label={t('home.stats.words')} />
              <Stat value={captures.length.toLocaleString(i18n.language)} label={t('home.stats.captures')} />
              <Stat value={stats.minutes.toLocaleString(i18n.language)} label={t('home.stats.minutes')} />
            </div>
          )}

          {captures.length > 0 && (
            <div className="mt-8 -mx-4">
              <ListPaneSearch
                value={search}
                onChange={setSearch}
                placeholder={t('captures.searchPlaceholder')}
              />
            </div>
          )}

          <div className="mt-2">
            {capturesLoading ? (
              <div className="py-12 flex items-center justify-center text-muted-foreground">
                <Loader2 className="h-4 w-4 animate-spin" />
              </div>
            ) : filtered.length === 0 ? (
              <div className="py-12 text-center text-sm text-muted-foreground">
                {search ? (
                  <p>{t('home.empty.noMatches', { query: search })}</p>
                ) : (
                  <div className="space-y-2">
                    <Captions className="h-8 w-8 mx-auto opacity-40" />
                    <p>{t('home.empty.none')}</p>
                    {/* Without a shortcut the hero above already shows this hint. */}
                    {hasHotkey ? <p className="text-xs">{t('captures.empty.pressShortcut')}</p> : null}
                  </div>
                )}
              </div>
            ) : (
              days.map((day) => (
                <section key={day.key}>
                  <h2 className="mt-8 mb-1 text-[11px] font-medium uppercase tracking-wider text-muted-foreground/70">
                    {day.label}
                  </h2>
                  {day.items.map((capture) => (
                    <CaptureRow
                      key={capture.id}
                      capture={capture}
                      text={snippetOf(capture)}
                      time={formatTime(capture.created_at, i18n.language)}
                      isLatest={capture.id === latestId}
                      isOpen={detailOpen && capture.id === selectedId}
                      onOpen={() => openDetail(capture.id)}
                      onCopy={() => copyCapture(capture)}
                    />
                  ))}
                </section>
              ))
            )}
          </div>
        </div>
      </div>

      {/* Details of the selected dictation */}
      {detailOpen && selected && (
        <aside
          className={cn(
            'w-[440px] shrink-0 border-l border-border overflow-y-auto px-6 pt-4 pb-8',
            isPlayerVisible && BOTTOM_SAFE_AREA_PADDING,
          )}
          aria-label={t('home.details')}
        >
          <div className="flex justify-end -mr-2">
            <Button
              variant="ghost"
              size="icon"
              onClick={() => setDetailOpen(false)}
              aria-label={t('common.close')}
              title={t('common.close')}
            >
              <X className="h-4 w-4" />
            </Button>
          </div>
            {/* Meta row */}
            <div className="flex items-center gap-3 mb-4 text-xs text-muted-foreground">
              <span>{formatAbsoluteDate(selected.created_at)}</span>
              {selected.language && (
                <>
                  <span className="text-muted-foreground/40">·</span>
                  <span>{selected.language.toUpperCase()}</span>
                </>
              )}
              <span className="text-muted-foreground/40">·</span>
              <SourceBadge source={selected.source} />
              {selected.target_app && (
                <>
                  <span className="text-muted-foreground/40">·</span>
                  <span title={selected.target_title ?? undefined}>
                    {t('captures.targetApp', { app: appLabel(selected.target_app) })}
                  </span>
                </>
              )}
            </div>

            {/* Audio player card */}
            <div className="rounded-xl border border-border bg-muted/20 p-4 mb-6">
              <CaptureInlinePlayer
                audioUrl={apiClient.getCaptureAudioUrl(selected.id)}
                fallbackDurationMs={selected.duration_ms}
              />
            </div>

            {/* Transcript header */}
            <div className="flex items-center gap-3 mb-3">
              <div className="inline-flex rounded-md bg-muted/40 p-0.5 border border-border">
                <button
                  type="button"
                  onClick={() => setShowRefined(true)}
                  disabled={!selected.transcript_refined}
                  className={cn(
                    'px-3 py-1 text-xs font-medium rounded transition-colors',
                    showRefined && selected.transcript_refined
                      ? 'bg-background shadow-sm text-foreground'
                      : 'text-muted-foreground hover:text-foreground disabled:opacity-40',
                  )}
                >
                  <Sparkles className="h-3 w-3 inline-block mr-1 -translate-y-px" />
                  {t('captures.transcript.refined')}
                </button>
                <button
                  type="button"
                  onClick={() => setShowRefined(false)}
                  className={cn(
                    'px-3 py-1 text-xs font-medium rounded transition-colors',
                    !showRefined || !selected.transcript_refined
                      ? 'bg-background shadow-sm text-foreground'
                      : 'text-muted-foreground hover:text-foreground',
                  )}
                >
                  <Captions className="h-3 w-3 inline-block mr-1 -translate-y-px" />
                  {t('captures.transcript.raw')}
                </button>
              </div>
              <div className="flex-1" />
              <span className="text-xs text-muted-foreground">
                {showRefined && selected.transcript_refined && selected.llm_model === 'manual'
                  ? t('captures.transcript.editedHint')
                  : showRefined && selected.transcript_refined
                  ? t('captures.transcript.refinedHint', {
                      // Local refinement stores only the size ("0.6B").
                      model: selected.llm_model
                        ? /^\d+(\.\d+)?B$/i.test(selected.llm_model)
                          ? `Qwen3 ${selected.llm_model}`
                          : selected.llm_model
                        : llmLabel,
                    })
                  : selected.stt_model
                    ? t('captures.transcript.rawHint', {
                        // The string already says "Whisper": show "large-v3-turbo",
                        // not "Whisper openai/whisper-large-v3-turbo".
                        model: selected.stt_model.replace(/^.*\//, '').replace(/^whisper-/i, ''),
                      })
                    : null}
              </span>
            </div>

            {/* Transcript body */}
            <div className="rounded-xl border border-border bg-muted/10">
              <Textarea
                key={`${selected.id}-${showRefined}`}
                // Controlled, so a refine that lands while the panel is open
                // shows up; typing starts a draft that Save stores as the
                // refined text.
                value={transcriptDraft ?? shownTranscript}
                onChange={(e) => setTranscriptDraft(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
                    e.preventDefault();
                    saveTranscript();
                  } else if (e.key === 'Escape' && transcriptDirty) {
                    e.preventDefault();
                    setTranscriptDraft(null);
                  }
                }}
                aria-label={t('captures.transcript.editLabel')}
                className="text-[15px] leading-relaxed min-h-[260px] border-0 bg-transparent resize-none focus-visible:ring-0 focus-visible:ring-offset-0 p-6"
              />
              {transcriptDirty && (
                <div className="flex items-center gap-2 px-4 pb-3">
                  <span className="text-xs text-muted-foreground mr-auto">
                    {t('captures.transcript.unsaved')}
                  </span>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => setTranscriptDraft(null)}
                    disabled={saveTranscriptMutation.isPending}
                  >
                    {t('captures.actions.discardEdit')}
                  </Button>
                  <Button
                    size="sm"
                    onClick={saveTranscript}
                    disabled={saveTranscriptMutation.isPending}
                  >
                    {saveTranscriptMutation.isPending ? (
                      <Loader2 className="h-3.5 w-3.5 mr-1.5 animate-spin" />
                    ) : (
                      <Check className="h-3.5 w-3.5 mr-1.5" />
                    )}
                    {t('captures.actions.saveEdit')}
                  </Button>
                </div>
              )}
            </div>

            {/* Corrections the user can turn into replacement rules */}
            {learnOffer?.captureId === selected.id && (
              <div className="mt-3 rounded-xl border border-accent/40 bg-accent/5 p-3 space-y-2">
                <p className="text-xs text-muted-foreground">{t('captures.learn.description')}</p>
                {learnOffer.rules.map((rule) => (
                  <div key={`${rule.from}→${rule.to}`} className="flex items-center gap-2">
                    <span className="flex-1 min-w-0 truncate text-sm">
                      {t('captures.learn.rule', { from: rule.from, to: rule.to })}
                    </span>
                    <Button variant="outline" size="sm" onClick={() => learnRule(rule)}>
                      {t('captures.learn.confirm')}
                    </Button>
                    <Button
                      variant="ghost"
                      size="icon"
                      className="h-8 w-8"
                      onClick={() => dropLearnRule(rule)}
                      aria-label={t('captures.learn.dismiss')}
                      title={t('captures.learn.dismiss')}
                    >
                      <X className="h-3.5 w-3.5" />
                    </Button>
                  </div>
                ))}
              </div>
            )}

            {/* Bottom actions */}
            <div className="flex items-center gap-2 mt-4 flex-wrap">
              {/* "Play as" reads the transcript in a cloned voice; lite has no voices */}
              {!IS_LITE && (
                <div className="inline-flex">
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => handlePlayAs()}
                    disabled={!playAsVoice || playAsMutation.isPending}
                    className={cn(
                      'gap-2 rounded-r-none border-r-0 pr-3 pl-2 transition-colors',
                      playbackState !== 'idle' &&
                        'border-accent/50 text-foreground bg-accent/10 hover:bg-accent/15 hover:text-foreground hover:border-accent/50',
                    )}
                  >
                    {playbackState === 'generating' ? (
                      <>
                        <AudioBars mode="generating" className="h-3.5" />
                        {t('captures.actions.playAsGenerating')}
                      </>
                    ) : playbackState === 'playing' ? (
                      <>
                        <Square className="h-3 w-3 fill-current" />
                        {playAsVoice
                          ? t('captures.actions.playAsStop', { name: playAsVoice.name })
                          : t('captures.actions.playAsStopFallback')}
                      </>
                    ) : (
                      <>
                        <Volume2 className="h-3.5 w-3.5" />
                        {playAsVoice
                          ? t('captures.actions.playAs', { name: playAsVoice.name })
                          : t('captures.actions.playAsFallback')}
                      </>
                    )}
                  </Button>
                  <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                      <Button
                        variant="outline"
                        size="sm"
                        className={cn(
                          'rounded-l-none px-2 transition-colors',
                          playbackState !== 'idle' &&
                            'border-accent/50 bg-accent/10 hover:bg-accent/15 hover:text-foreground hover:border-accent/50',
                        )}
                        disabled={!profiles || !profiles.length}
                      >
                        <ChevronDown className="h-3.5 w-3.5 opacity-70" />
                      </Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="start" className="w-64">
                      <DropdownMenuLabel className="text-[11px] font-medium text-muted-foreground uppercase tracking-wide">
                        {t('captures.actions.playAsDropdownLabel')}
                      </DropdownMenuLabel>
                      <DropdownMenuSeparator />
                      {profiles?.map((v) => (
                        <DropdownMenuItem
                          key={v.id}
                          onClick={() => handlePlayAs(v)}
                          className="py-2"
                        >
                          <div className="flex-1 min-w-0">
                            <div className="text-sm font-medium truncate">{v.name}</div>
                            <div className="text-[11px] text-muted-foreground truncate">
                              {v.description || v.language.toUpperCase()}
                            </div>
                          </div>
                          {v.id === playAsVoiceId && (
                            <Check className="h-3.5 w-3.5 text-accent shrink-0" />
                          )}
                        </DropdownMenuItem>
                      ))}
                    </DropdownMenuContent>
                  </DropdownMenu>
                </div>
              )}
              <Button variant="outline" size="sm" onClick={handleCopy}>
                <Copy className="h-3.5 w-3.5 mr-1.5" />
                {t('captures.actions.copy')}
              </Button>
              <Button
                variant="outline"
                size="sm"
                onClick={() => session.refine(selected.id)}
                disabled={session.isRefining}
              >
                {session.isRefining ? (
                  <Loader2 className="h-3.5 w-3.5 mr-1.5 animate-spin" />
                ) : (
                  <Sparkles className="h-3.5 w-3.5 mr-1.5" />
                )}
                {selected.transcript_refined
                  ? t('captures.actions.reRefine')
                  : t('captures.actions.refine')}
              </Button>
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button variant="outline" size="sm">
                    <Download className="h-3.5 w-3.5 mr-1.5" />
                    {t('captures.actions.export')}
                    <ChevronDown className="h-3.5 w-3.5 ml-1 opacity-70" />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="start" className="w-56">
                  <DropdownMenuLabel className="text-[11px] font-medium text-muted-foreground uppercase tracking-wide">
                    {t('captures.actions.exportDropdownLabel')}
                  </DropdownMenuLabel>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem onClick={handleExportAudio}>
                    <FileAudio className="h-3.5 w-3.5 mr-2 text-muted-foreground" />
                    {t('captures.actions.exportAudio')}
                  </DropdownMenuItem>
                  <DropdownMenuItem onClick={handleExportTranscript}>
                    <Captions className="h-3.5 w-3.5 mr-2 text-muted-foreground" />
                    {t('captures.actions.exportTranscript')}
                  </DropdownMenuItem>
                  <DropdownMenuItem onClick={handleExportMarkdown}>
                    <FileText className="h-3.5 w-3.5 mr-2 text-muted-foreground" />
                    {t('captures.actions.exportMarkdown')}
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
              <div className="flex-1" />
              <Button
                variant="ghost"
                size="sm"
                onClick={() => setDeleteDialogOpen(true)}
                disabled={deleteMutation.isPending}
                className="text-muted-foreground "
              >
                {deleteMutation.isPending ? (
                  <Loader2 className="h-3.5 w-3.5 mr-1.5 animate-spin" />
                ) : (
                  <Trash2 className="h-3.5 w-3.5 mr-1.5" />
                )}
                {t('captures.actions.delete')}
              </Button>
            </div>
        </aside>
      )}

      <AlertDialog open={deleteDialogOpen} onOpenChange={setDeleteDialogOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('captures.deleteDialog.title')}</AlertDialogTitle>
            <AlertDialogDescription>{t('captures.deleteDialog.description')}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t('common.cancel')}</AlertDialogCancel>
            <AlertDialogAction asChild>
              <Button
                onClick={() => selected && deleteMutation.mutate(selected.id)}
                disabled={deleteMutation.isPending}
                className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
              >
                {deleteMutation.isPending ? t('captures.deleteDialog.deleting') : t('common.delete')}
              </Button>
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
