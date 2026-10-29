"""
Audio retention — how long a capture keeps its audio file.

Every capture stores its recording under ``data/captures/``. The
``audio_retention`` capture setting limits that: ``always`` (keep, the
default), ``7d`` / ``30d`` (delete audio older than that), or ``never``
(delete it as soon as the transcript is stored — refinement only needs
the text). Transcripts and the rest of the row stay; a capture whose
audio is gone has an empty ``audio_path``.

:func:`purge_expired_audio` runs at server startup, after each new
capture and when the setting changes. It only reads rows that still
point at audio, so the scan stays cheap.
"""

import logging
from datetime import UTC, datetime, timedelta
from typing import Optional

from sqlalchemy.orm import Session

from .. import config
from ..database import Capture as DBCapture

logger = logging.getLogger(__name__)

AUDIO_RETENTION_POLICIES = ("always", "7d", "30d", "never")
DEFAULT_AUDIO_RETENTION = "always"

_RETENTION_DAYS = {"7d": 7, "30d": 30}


def retention_cutoff(policy: Optional[str], now: Optional[datetime] = None) -> Optional[datetime]:
    """Captures created at or before this instant lose their audio.

    ``None`` means keep everything (``always``, or a value this version
    does not know — an unknown policy must never delete). The result is a
    naive UTC datetime, the form SQLite hands back for ``created_at``.
    """
    if policy == "never":
        days = 0
    elif policy in _RETENTION_DAYS:
        days = _RETENTION_DAYS[policy]
    else:
        return None
    now = now or datetime.now(UTC)
    if now.tzinfo is not None:
        now = now.astimezone(UTC).replace(tzinfo=None)
    return now - timedelta(days=days)


def purge_expired_audio(db: Session, policy: Optional[str], now: Optional[datetime] = None) -> int:
    """Delete the audio of captures past the retention window.

    Only files inside the captures directory are deleted; a row pointing
    anywhere else is left alone. A file that is already missing still
    clears its row. A file that cannot be deleted (locked, permissions)
    keeps its row so the next run retries.

    Returns:
        How many captures lost their audio.
    """
    cutoff = retention_cutoff(policy, now)
    if cutoff is None:
        return 0

    captures_dir = config.get_captures_dir().resolve()
    rows = (
        db.query(DBCapture)
        .filter(DBCapture.audio_path != "", DBCapture.created_at <= cutoff)
        .all()
    )
    purged = 0
    for row in rows:
        path = config.resolve_storage_path(row.audio_path)
        if path is not None:
            path = path.resolve()
            if not path.is_relative_to(captures_dir):
                logger.warning(
                    "Audio retention: capture %s points outside %s (%s); not deleting",
                    row.id,
                    captures_dir,
                    path,
                )
                continue
            try:
                path.unlink(missing_ok=True)
            except OSError as exc:
                logger.warning("Audio retention: could not delete %s: %s", path, exc)
                continue
        row.audio_path = ""
        purged += 1

    if purged:
        db.commit()
        logger.info("Audio retention (%s): deleted the audio of %d capture(s)", policy, purged)
    return purged
