"""
Server-side user settings — singleton rows persisted in SQLite so every
client window, API consumer, and headless flow reads the same preferences.

Two domains live here: capture/refine defaults and long-form generation
defaults. Each has a ``get_*`` that lazily creates the row with defaults and
an ``update_*`` that accepts a partial payload.
"""

from typing import Any
from urllib.parse import urlparse

from sqlalchemy.orm import Session

from ..database import CaptureSettings as DBCaptureSettings
from ..database import GenerationSettings as DBGenerationSettings
from ..utils.capture_chords import (
    default_push_to_talk_chord,
    default_toggle_to_talk_chord,
)


SINGLETON_ID = 1


def _get_or_create_capture_row(db: Session) -> DBCaptureSettings:
    """Fetch the singleton capture-settings row, lazily creating it on first read.

    Every setter path is a partial update against this row, so callers
    can assume the returned object exists and holds the schema defaults
    for any field the user hasn't customised yet.
    """
    row = db.query(DBCaptureSettings).filter(DBCaptureSettings.id == SINGLETON_ID).first()
    if row is None:
        row = DBCaptureSettings(
            id=SINGLETON_ID,
            chord_push_to_talk_keys=default_push_to_talk_chord(),
            chord_toggle_to_talk_keys=default_toggle_to_talk_chord(),
        )
        db.add(row)
        db.commit()
        db.refresh(row)
    return row


def _get_or_create_generation_row(db: Session) -> DBGenerationSettings:
    """Fetch the singleton generation-settings row, lazily creating it on first read.

    Symmetric counterpart to :func:`_get_or_create_capture_row` for the
    long-form generation defaults (chunk sizer, crossfade, normalize).
    """
    row = db.query(DBGenerationSettings).filter(DBGenerationSettings.id == SINGLETON_ID).first()
    if row is None:
        row = DBGenerationSettings(id=SINGLETON_ID)
        db.add(row)
        db.commit()
        db.refresh(row)
    return row


def _apply_patch(row: Any, patch: dict[str, Any]) -> None:
    """Apply a partial update to a settings row.

    Values explicitly set to ``None`` are honored only for columns where the
    schema allows it — clearing ``default_playback_voice_id`` works, but a
    ``None`` for a non-nullable field is dropped rather than crashing the
    request. Unknown keys are ignored.
    """
    columns = type(row).__table__.columns
    for key, value in patch.items():
        col = columns.get(key)
        if col is None:
            continue
        if value is None and not col.nullable:
            continue
        setattr(row, key, value)


def get_capture_settings(db: Session) -> DBCaptureSettings:
    """Return the capture settings row, creating it with defaults if missing."""
    return _get_or_create_capture_row(db)


def update_capture_settings(db: Session, patch: dict[str, Any]) -> DBCaptureSettings:
    """Apply a partial update to the capture-settings singleton and persist it.

    After the commit, propagates the (possibly changed) custom-LLM
    endpoint into the backend module state via
    :func:`_sync_llm_backend_config` so subsequent refinement /
    personality calls route to the new URL without needing a restart.
    """
    row = _get_or_create_capture_row(db)
    # A key belongs to the host it was entered for. When an endpoint moves to
    # another host and the same request brings no key, drop the old key
    # instead of sending it to the new host.
    for endpoint_field, key_field in (
        ("custom_stt_endpoint", "custom_stt_api_key"),
        ("custom_llm_endpoint", "custom_llm_api_key"),
    ):
        new_endpoint = patch.get(endpoint_field)
        old_endpoint = getattr(row, endpoint_field)
        if (
            new_endpoint
            and key_field not in patch
            and urlparse(new_endpoint).hostname != urlparse(old_endpoint or "").hostname
        ):
            patch = {**patch, key_field: None}
    _apply_patch(row, patch)
    db.commit()
    db.refresh(row)
    _sync_llm_backend_config(row)
    return row


def _sync_llm_backend_config(row: DBCaptureSettings) -> None:
    """Push the persisted custom-LLM and custom-STT endpoints into the backend module state.

    Called after every capture-settings write and once on startup from
    ``bootstrap_llm_backend_config``. Keeps the runtime dispatch in
    ``backends.get_llm_backend()`` in sync with what the DB row holds so
    the user's next refinement / personality call routes to the new URL
    without a restart.
    """
    # Imported inline to avoid a circular import at module load — the
    # backends package pulls in HF patches that in turn import services.
    from .. import backends

    backends.set_llm_config(
        endpoint=row.custom_llm_endpoint,
        model=row.custom_llm_model,
        api_key=row.custom_llm_api_key,
    )
    backends.set_stt_config(
        endpoint=row.custom_stt_endpoint,
        model=row.custom_stt_model,
        api_key=row.custom_stt_api_key,
    )


def bootstrap_llm_backend_config(db: Session) -> None:
    """Seed the backend LLM config from the persisted row on server startup."""
    row = _get_or_create_capture_row(db)
    _sync_llm_backend_config(row)


def _sync_tts_backend_config(row: DBGenerationSettings) -> None:
    """Push the persisted Fish Audio key into the backend module state.

    Twin of :func:`_sync_llm_backend_config` for the cloud TTS engine:
    called after every generation-settings write and once on startup.
    """
    from .. import backends

    backends.set_fish_audio_config(api_key=row.fish_audio_api_key)


def bootstrap_tts_backend_config(db: Session) -> None:
    """Seed the cloud TTS key from the persisted row on server startup."""
    row = _get_or_create_generation_row(db)
    _sync_tts_backend_config(row)


def get_generation_settings(db: Session) -> DBGenerationSettings:
    """Return the generation settings row, creating it with defaults if missing."""
    return _get_or_create_generation_row(db)


def update_generation_settings(db: Session, patch: dict[str, Any]) -> DBGenerationSettings:
    """Apply a partial update to the generation-settings singleton and persist it.

    The chunking/normalize fields flow directly into the next
    ``run_generation`` invocation; the Fish Audio key is pushed into the
    backend module state via :func:`_sync_tts_backend_config` so the next
    cloud generation uses it without a restart. A blank key clears it.
    """
    row = _get_or_create_generation_row(db)
    if "fish_audio_api_key" in patch:
        patch = {**patch, "fish_audio_api_key": (patch["fish_audio_api_key"] or "").strip() or None}
    _apply_patch(row, patch)
    db.commit()
    db.refresh(row)
    _sync_tts_backend_config(row)
    return row
