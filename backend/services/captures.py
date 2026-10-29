"""
Captures service — persists raw audio alongside its STT transcript and,
optionally, an LLM-refined version.

A capture is a single voice input event (dictation, long-form recording, or
uploaded file). Storage mirrors the generations flow: audio lives under
``data/captures/<id>.wav`` and rows live in the ``captures`` table.
"""

import contextlib
import json
import logging
import uuid
from pathlib import Path
from typing import Optional

import soundfile as sf
from sqlalchemy.orm import Session

from .. import config
from ..database import Capture as DBCapture
from ..models import CaptureResponse, CaptureTranscriptUpdateResponse, RefinementFlagsModel
from ..utils.audio import load_audio
from .app_context import style_for_app
from .refinement import RefinementFlags, refine_transcript
from .snippets import (
    SNIPPET_LABEL,
    expand_snippets,
    match_whole_snippet,
    protect_snippets,
    restore_snippets,
)
from .transcribe import get_whisper_model, resolve_stt_model, transcribe_with_vocabulary
from .vocabulary import apply_replacements, normalize_replacements, suggest_replacements
from .voice_commands import run_command

logger = logging.getLogger(__name__)


# "command": a spoken instruction applied to the text selected in the target
# app (see ``services/voice_commands.py``); its result is the refined text.
VALID_SOURCES = {"dictation", "recording", "file", "command"}
# The window title only matters for telling web apps apart in a browser;
# a long one is cut rather than stored whole.
MAX_TARGET_TITLE_CHARS = 200
# Suffixes whisper's miniaudio loader can read directly. Anything outside
# this set has to go through librosa for decode + a soundfile transcode
# before whisper sees it.
WHISPER_NATIVE_FORMATS = (".wav", ".mp3", ".flac", ".ogg")


def _to_response(row: DBCapture) -> CaptureResponse:
    flags_model: Optional[RefinementFlagsModel] = None
    if row.refinement_flags:
        try:
            flags_model = RefinementFlagsModel(**json.loads(row.refinement_flags))
        except (ValueError, TypeError):
            flags_model = None

    audio = config.resolve_storage_path(row.audio_path)
    return CaptureResponse(
        id=row.id,
        audio_path=row.audio_path,
        audio_available=audio is not None and audio.exists(),
        source=row.source,
        language=row.language,
        duration_ms=row.duration_ms,
        transcript_raw=row.transcript_raw or "",
        transcript_refined=row.transcript_refined,
        stt_model=row.stt_model,
        llm_model=row.llm_model,
        refinement_flags=flags_model,
        target_app=row.target_app,
        target_title=row.target_title,
        created_at=row.created_at,
    )


def _clean_target(value: Optional[str], limit: int) -> Optional[str]:
    value = (value or "").strip()
    return value[:limit] or None


def app_style(row: DBCapture, adapt_to_app: bool) -> Optional[str]:
    """The target app's writing style for ``row``; ``None`` when off or unknown."""
    if not adapt_to_app:
        return None
    return style_for_app(row.target_app, row.target_title)


def _snippet_text(
    transcript: str, snippets: Optional[list[dict]], expand_inline: bool
) -> Optional[str]:
    """Text the user's shortcuts turn ``transcript`` into, or ``None``.

    A transcript that is only a trigger always becomes its text. Triggers
    inside a longer transcript are expanded only when ``expand_inline`` —
    i.e. when no refinement follows; otherwise :func:`refine_capture`
    passes them through the LLM as placeholders, so the model never
    rewrites them.
    """
    whole = match_whole_snippet(transcript, snippets)
    if whole is not None:
        return whole
    if expand_inline:
        expanded = expand_snippets(transcript, snippets)
        if expanded != transcript:
            return expanded
    return None


def unrefined_text(transcript: str, snippets: Optional[list[dict]]) -> str:
    """``transcript`` as delivered without refinement: shortcuts expanded.

    What the app pastes when the refinement that follows a capture fails,
    and what :func:`refine_capture` stores when the LLM loses a shortcut.
    """
    return expand_snippets(transcript, snippets)


async def create_capture(
    *,
    audio_bytes: bytes,
    filename: str,
    source: str,
    language: Optional[str],
    stt_model: Optional[str],
    db: Session,
    vocabulary: Optional[list[str]] = None,
    replacements: Optional[list[dict]] = None,
    target_app: Optional[str] = None,
    target_title: Optional[str] = None,
    snippets: Optional[list[dict]] = None,
    refine_follows: bool = False,
) -> CaptureResponse:
    """Persist raw audio, run STT, store the row.

    ``vocabulary`` biases a remote STT endpoint; ``replacements`` are
    applied to its output before it is stored as ``transcript_raw``.
    ``target_app`` / ``target_title`` name the app the text is for; they
    pick its writing style when the capture is refined.
    When ``snippets`` turn the transcript into a shortcut's text, that text
    is stored as the refined transcript with ``llm_model`` set to
    ``SNIPPET_LABEL``, and the caller must not refine it.
    ``refine_follows`` says whether an automatic refinement comes next
    (see :func:`_snippet_text`).
    """
    if source not in VALID_SOURCES:
        raise ValueError(f"Invalid source '{source}'. Must be one of {sorted(VALID_SOURCES)}")

    capture_id = str(uuid.uuid4())
    suffix = Path(filename).suffix.lower() or ".wav"
    if suffix not in (".wav", ".mp3", ".m4a", ".flac", ".ogg", ".webm"):
        suffix = ".wav"

    raw_path = config.get_captures_dir() / f"{capture_id}{suffix}"
    written_files: list[Path] = []

    try:
        raw_path.write_bytes(audio_bytes)
        written_files.append(raw_path)

        # Decode once with librosa — its audioread fallback handles webm/opus
        # via ffmpeg, which miniaudio (used inside mlx-audio's whisper) can't.
        # The decoded array gives us an accurate duration and becomes the
        # canonical WAV we hand to whisper.
        try:
            audio, sr = load_audio(str(raw_path))
            duration_ms = int((len(audio) / sr) * 1000) if sr else None
        except Exception as decode_err:
            logger.warning(
                "Could not decode capture %s (%s): %r", capture_id, suffix, decode_err
            )
            audio, sr = None, None
            duration_ms = None

        if audio is None or sr is None:
            # Decode failed. Only pass the file straight to whisper if the
            # source is a format its miniaudio loader can still read — webm,
            # m4a, etc. would just 500 later. Surface a clean error instead.
            if suffix not in WHISPER_NATIVE_FORMATS:
                raise ValueError(
                    f"Could not decode {suffix} audio — the recording may be empty or corrupt"
                )
            audio_path = raw_path
        elif suffix == ".wav":
            audio_path = raw_path
        else:
            # Transcode to WAV so downstream loaders (miniaudio, soundfile) work
            # regardless of what format the client shipped.
            audio_path = config.get_captures_dir() / f"{capture_id}.wav"
            sf.write(str(audio_path), audio, sr, format="WAV")
            written_files.append(audio_path)
            with contextlib.suppress(OSError):
                raw_path.unlink()
                written_files.remove(raw_path)

        whisper = get_whisper_model()
        resolved_stt = resolve_stt_model(whisper, stt_model)
        transcript = await transcribe_with_vocabulary(
            whisper, str(audio_path), language, resolved_stt, vocabulary
        )
        transcript = apply_replacements(transcript, replacements)
        shortcut = _snippet_text(transcript, snippets, expand_inline=not refine_follows)

        row = DBCapture(
            id=capture_id,
            audio_path=config.to_storage_path(audio_path),
            source=source,
            language=language,
            duration_ms=duration_ms,
            transcript_raw=transcript,
            transcript_refined=shortcut,
            stt_model=resolved_stt,
            target_app=_clean_target(target_app, 255),
            target_title=_clean_target(target_title, MAX_TARGET_TITLE_CHARS),
            llm_model=SNIPPET_LABEL if shortcut is not None else None,
        )
        db.add(row)
        db.commit()
        db.refresh(row)
    except Exception:
        # Anything between the first write and the commit means the audio on
        # disk has no row pointing at it — clean up so data/captures doesn't
        # accumulate orphan blobs across failed transcribes.
        for path in written_files:
            try:
                path.unlink()
            except OSError:
                pass
        raise

    return _to_response(row)


def list_captures(db: Session, limit: int = 50, offset: int = 0) -> tuple[list[CaptureResponse], int]:
    total = db.query(DBCapture).count()
    rows = (
        db.query(DBCapture)
        .order_by(DBCapture.created_at.desc())
        .limit(limit)
        .offset(offset)
        .all()
    )
    return [_to_response(r) for r in rows], total


def get_capture(capture_id: str, db: Session) -> Optional[CaptureResponse]:
    row = db.query(DBCapture).filter(DBCapture.id == capture_id).first()
    return _to_response(row) if row else None


def delete_capture(capture_id: str, db: Session) -> bool:
    row = db.query(DBCapture).filter(DBCapture.id == capture_id).first()
    if not row:
        return False

    resolved = config.resolve_storage_path(row.audio_path)
    if resolved and resolved.exists():
        try:
            resolved.unlink()
        except OSError:
            logger.exception("Failed to remove capture audio %s", resolved)

    db.delete(row)
    db.commit()
    return True


async def refine_capture(
    capture_id: str,
    flags: RefinementFlags,
    model_size: Optional[str],
    db: Session,
    vocabulary: Optional[list[str]] = None,
    adapt_to_app: bool = False,
    snippets: Optional[list[dict]] = None,
) -> Optional[CaptureResponse]:
    """Refine a capture's raw transcript and store the result.

    A transcript that is only a shortcut trigger gets the shortcut's text
    without calling the LLM. Otherwise each trigger goes to the LLM as a
    placeholder it must keep, which becomes the shortcut's text after
    refinement, so the saved text is pasted verbatim. When a placeholder
    is lost or repeated, the unrefined text with shortcuts expanded is
    stored instead, labelled like a shortcut.
    """
    row = db.query(DBCapture).filter(DBCapture.id == capture_id).first()
    if not row:
        return None

    raw = row.transcript_raw or ""
    whole = match_whole_snippet(raw, snippets)
    if whole is not None:
        row.transcript_refined = whole
        row.llm_model = SNIPPET_LABEL
        row.refinement_flags = None
        db.commit()
        db.refresh(row)
        return _to_response(row)

    protected, tokens = protect_snippets(raw, snippets)
    refined, llm_size = await refine_transcript(
        protected,
        flags,
        model_size=model_size,
        vocabulary=vocabulary,
        app_style=app_style(row, adapt_to_app),
        placeholders=tokens,
    )

    restored = restore_snippets(refined, tokens)
    if restored is None:
        # Guessing where a lost shortcut belonged could paste it in the
        # wrong place; the unrefined text holds every one where it was said.
        logger.warning(
            "Refinement of capture %s lost or repeated a shortcut placeholder; "
            "storing the unrefined text",
            capture_id,
        )
        row.transcript_refined = unrefined_text(raw, snippets)
        row.llm_model = SNIPPET_LABEL
        row.refinement_flags = None
    else:
        row.transcript_refined = restored
        row.llm_model = llm_size
        row.refinement_flags = json.dumps(flags.to_dict())
    db.commit()
    db.refresh(row)
    return _to_response(row)


async def run_capture_command(
    capture_id: str,
    selection: str,
    db: Session,
    instruction: Optional[str] = None,
    model_size: Optional[str] = None,
    adapt_to_app: bool = False,
) -> Optional[CaptureResponse]:
    """Apply a command capture's instruction to ``selection``; store the result.

    The instruction is the capture's transcript unless ``instruction`` is
    given. The result becomes the refined text, so history shows the
    instruction and what it produced side by side.

    Raises:
        ValueError: The instruction is empty.
    """
    row = db.query(DBCapture).filter(DBCapture.id == capture_id).first()
    if not row:
        return None

    if instruction is None:
        instruction = row.transcript_raw or ""
    text, llm_size = await run_command(
        instruction,
        selection,
        app_style=app_style(row, adapt_to_app),
        model_size=model_size,
    )

    row.transcript_refined = text
    row.llm_model = llm_size
    row.refinement_flags = None
    db.commit()
    db.refresh(row)
    return _to_response(row)


# ``llm_model`` label of a transcript the user corrected by hand, so the UI
# says "edited" instead of naming a model that did not write it.
MANUAL_EDIT_LABEL = "manual"


def update_capture_transcript(
    capture_id: str,
    text: str,
    base: str,
    db: Session,
    replacements: Optional[list[dict]] = None,
) -> Optional[CaptureTranscriptUpdateResponse]:
    """Store a hand-corrected transcript and suggest rules learned from it.

    The edit becomes the refined text (the raw STT output is kept for
    re-refining). Suggestions compare the edit with the version the user
    edited (``base``: "raw" or "refined") and leave out rules that are
    already saved.
    """
    row = db.query(DBCapture).filter(DBCapture.id == capture_id).first()
    if not row:
        return None

    if base == "refined" and row.transcript_refined is not None:
        before = row.transcript_refined
    else:
        before = row.transcript_raw or ""

    saved = {(r["from"].casefold(), r["to"]) for r in normalize_replacements(replacements)}
    suggestions = [
        s for s in suggest_replacements(before, text) if (s["from"].casefold(), s["to"]) not in saved
    ]

    row.transcript_refined = text
    row.llm_model = MANUAL_EDIT_LABEL
    db.commit()
    db.refresh(row)
    return CaptureTranscriptUpdateResponse(
        **_to_response(row).model_dump(), suggestions=suggestions
    )


async def retranscribe_capture(
    capture_id: str,
    stt_model: Optional[str],
    language: Optional[str],
    db: Session,
    vocabulary: Optional[list[str]] = None,
    replacements: Optional[list[dict]] = None,
    snippets: Optional[list[dict]] = None,
) -> Optional[CaptureResponse]:
    row = db.query(DBCapture).filter(DBCapture.id == capture_id).first()
    if not row:
        return None

    resolved = config.resolve_storage_path(row.audio_path)
    if not resolved or not resolved.exists():
        raise FileNotFoundError(f"Audio for capture {capture_id} is missing")

    whisper = get_whisper_model()
    resolved_stt = resolve_stt_model(whisper, stt_model)
    transcript = await transcribe_with_vocabulary(
        whisper, str(resolved), language, resolved_stt, vocabulary
    )
    transcript = apply_replacements(transcript, replacements)

    row.transcript_raw = transcript
    row.stt_model = resolved_stt
    if language:
        row.language = language
    # Refined text is stale after a fresh STT pass — force a re-refine,
    # unless the new transcript is just a shortcut trigger.
    shortcut = _snippet_text(transcript, snippets, expand_inline=False)
    row.transcript_refined = shortcut
    row.llm_model = SNIPPET_LABEL if shortcut is not None else None
    row.refinement_flags = None
    db.commit()
    db.refresh(row)
    return _to_response(row)
