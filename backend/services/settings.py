"""
Server-side user settings — singleton rows persisted in SQLite so every
client window, API consumer, and headless flow reads the same preferences.

Two domains live here: capture/refine defaults and long-form generation
defaults. Each has a ``get_*`` that lazily creates the row with defaults and
an ``update_*`` that accepts a partial payload.
"""

import secrets
from datetime import datetime, timezone
from typing import Any, Optional
from urllib.parse import urlparse

from sqlalchemy.orm import Session

from ..database import CaptureSettings as DBCaptureSettings
from ..database import GenerationSettings as DBGenerationSettings
from ..utils.capture_chords import (
    default_command_chord,
    default_push_to_talk_chord,
    default_toggle_to_talk_chord,
)


SINGLETON_ID = 1

_KEY_FIELDS = (
    ("custom_stt_endpoint", "custom_stt_api_key"),
    ("custom_llm_endpoint", "custom_llm_api_key"),
)

# Key prefixes that name their provider for sure. A bare ``sk-`` is used by
# OpenAI and others, so it is not checked.
_KEY_PREFIX_HOSTS = (
    ("sk-ant-", "anthropic.com", "Anthropic"),
    ("gsk_", "groq.com", "Groq"),
    ("sk-or-", "openrouter.ai", "OpenRouter"),
)


class SettingsError(ValueError):
    """A settings write the server refuses; the message is shown to the user."""


def _host(endpoint: Optional[str]) -> str:
    return (urlparse(endpoint or "").hostname or "").lower()


def _check_key_fits_host(key: str, endpoint: Optional[str]) -> None:
    """Refuse a key whose prefix names another provider than the endpoint's.

    Pasting the Claude key into the OpenAI endpoint only showed up as a
    failed refine at the next dictation.
    """
    host = _host(endpoint)
    if not key or not host:
        return
    for prefix, domain, name in _KEY_PREFIX_HOSTS:
        if key.startswith(prefix) and host != domain and not host.endswith(f".{domain}"):
            raise SettingsError(f"This looks like a {name} key, but the endpoint is {host}.")


def _remember_key(saved: list, host: str, key: Optional[str]) -> list:
    """``saved`` plus ``key`` for ``host``, unless it is already there."""
    if not key or not host or any(entry.get("key") == key for entry in saved):
        return saved
    added = datetime.now(timezone.utc).isoformat(timespec="seconds")
    return [*saved, {"id": secrets.token_hex(6), "host": host, "key": key, "added_at": added}]


def _newest_key_for(saved: list, host: str) -> Optional[str]:
    matches = [entry["key"] for entry in saved if entry.get("host") == host and entry.get("key")]
    return matches[-1] if matches else None


def _find_saved(saved: list, key_id: str) -> dict:
    for entry in saved:
        if entry.get("id") == key_id:
            return entry
    raise SettingsError("That saved key no longer exists.")


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
            chord_command_keys=default_command_chord(),
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
    patch = dict(patch)
    saved = list(row.saved_api_keys or [])

    # Forgetting a stored key also unsets it where it is in use.
    forget_id = patch.pop("forget_api_key_id", None)
    if forget_id:
        forgotten = _find_saved(saved, forget_id)
        saved = [entry for entry in saved if entry is not forgotten]
        for _, key_field in _KEY_FIELDS:
            if key_field not in patch and getattr(row, key_field) == forgotten.get("key"):
                patch[key_field] = None

    # A key for a backup provider, entered next to it in the backup list.
    new_key = patch.pop("save_api_key", None)
    if new_key:
        host = _host(new_key["endpoint"])
        if not host:
            raise SettingsError("That endpoint has no host to save the key for.")
        _check_key_fits_host(new_key["key"], new_key["endpoint"])
        saved = _remember_key(saved, host, new_key["key"])

    for endpoint_field, key_field in _KEY_FIELDS:
        endpoint = patch.get(endpoint_field, getattr(row, endpoint_field))
        # A stored key picked from the list must belong to this endpoint's host.
        chosen_id = patch.pop(f"{key_field}_id", None)
        if chosen_id:
            chosen = _find_saved(saved, chosen_id)
            if chosen.get("host") != _host(endpoint):
                raise SettingsError(
                    f"That key was saved for {chosen.get('host')}, not {_host(endpoint) or 'this endpoint'}."
                )
            patch[key_field] = chosen["key"]
        # A key belongs to the host it was entered for. When an endpoint moves
        # to another host and the same request brings no key, use the newest
        # stored key for the new host, or none, rather than send the old key.
        new_endpoint = patch.get(endpoint_field)
        old_endpoint = getattr(row, endpoint_field)
        if new_endpoint and key_field not in patch and _host(new_endpoint) != _host(old_endpoint):
            patch[key_field] = _newest_key_for(saved, _host(new_endpoint))
        if patch.get(key_field):
            _check_key_fits_host(patch[key_field], endpoint)

    _apply_patch(row, patch)
    # Every key in use is kept, so moving an endpoint away and back (or
    # clearing the field) doesn't lose it.
    for endpoint_field, key_field in _KEY_FIELDS:
        saved = _remember_key(saved, _host(getattr(row, endpoint_field)), getattr(row, key_field))
    row.saved_api_keys = saved
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
    saved = row.saved_api_keys or []
    for kind, field, primary_endpoint, primary_key in (
        ("stt", "stt_fallbacks", row.custom_stt_endpoint, row.custom_stt_api_key),
        ("llm", "llm_fallbacks", row.custom_llm_endpoint, row.custom_llm_api_key),
    ):
        entries = []
        for entry in getattr(row, field) or []:
            host = _host(entry.get("endpoint"))
            # The primary's own key when the backup is on the same host,
            # else the newest stored key for that host.
            key = primary_key if host == _host(primary_endpoint) else _newest_key_for(saved, host)
            entries.append((entry.get("endpoint"), entry.get("model"), key))
        backends.set_fallback_config(kind, entries)


def bootstrap_llm_backend_config(db: Session) -> None:
    """Seed the backend LLM config from the persisted row on server startup."""
    row = _get_or_create_capture_row(db)
    # Keys entered before saved keys existed join the list on first start.
    saved = list(row.saved_api_keys or [])
    for endpoint_field, key_field in _KEY_FIELDS:
        saved = _remember_key(saved, _host(getattr(row, endpoint_field)), getattr(row, key_field))
    if saved != (row.saved_api_keys or []):
        row.saved_api_keys = saved
        db.commit()
        db.refresh(row)
    _sync_llm_backend_config(row)


def get_generation_settings(db: Session) -> DBGenerationSettings:
    """Return the generation settings row, creating it with defaults if missing."""
    return _get_or_create_generation_row(db)


def update_generation_settings(db: Session, patch: dict[str, Any]) -> DBGenerationSettings:
    """Apply a partial update to the generation-settings singleton and persist it.

    Unlike :func:`update_capture_settings`, no runtime state depends on
    these values — the fields flow directly into the next
    ``run_generation`` invocation via ``GenerationSettings`` — so the
    commit is enough.
    """
    row = _get_or_create_generation_row(db)
    _apply_patch(row, patch)
    db.commit()
    db.refresh(row)
    return row
