"""
Tests for capture audio retention: the cutoff per policy, the cleanup over
a temporary data dir with fake capture rows, and the setting through the
routes (a "never" dictation keeps its transcript but not its audio).

STT calls go to the in-process ``FakeServer`` from
``test_cloud_dictation.py`` (no network, no keys).
"""

import sqlite3
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest

from backend.backends import reset_backends
from backend.services.audio_retention import purge_expired_audio, retention_cutoff
from backend.tests.test_cloud_dictation import FakeServer, _wav_bytes
from backend.tests.test_vocabulary import api  # noqa: F401 — fixture

NOW = datetime(2026, 10, 29, 12, 0, 0)


@pytest.fixture(autouse=True)
def _clean_backends():
    reset_backends()
    yield
    reset_backends()


# ── Cutoff ────────────────────────────────────────────────────────────────


def test_cutoff_per_policy():
    assert retention_cutoff("always", NOW) is None
    assert retention_cutoff(None, NOW) is None
    # A value this version does not know must never delete anything.
    assert retention_cutoff("90d", NOW) is None
    assert retention_cutoff("never", NOW) == NOW
    assert retention_cutoff("7d", NOW) == NOW - timedelta(days=7)
    assert retention_cutoff("30d", NOW) == NOW - timedelta(days=30)
    # An aware "now" becomes naive UTC, the form SQLite stores.
    aware = datetime(2026, 10, 29, 9, 0, 0, tzinfo=UTC)
    assert retention_cutoff("never", aware) == datetime(2026, 10, 29, 9, 0, 0)


# ── Cleanup over fake rows ────────────────────────────────────────────────


@pytest.fixture
def store(tmp_path, monkeypatch):
    """A SQLite session and a data dir with a ``captures/`` folder."""
    from sqlalchemy import create_engine
    from sqlalchemy.orm import sessionmaker

    from backend import config
    from backend.database.models import Base

    data = tmp_path / "data"
    data.mkdir()
    monkeypatch.setattr(config, "_data_dir", data)
    engine = create_engine(f"sqlite:///{tmp_path / 'r.db'}")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    yield db, config.get_captures_dir(), tmp_path
    db.close()


def _row(db, captures_dir: Path, name: str, age_days: float, *, write=True, path=None):
    from backend import config
    from backend.database import Capture as DBCapture

    audio = path or captures_dir / f"{name}.wav"
    if write:
        audio.write_bytes(b"RIFF")
    row = DBCapture(
        id=name,
        audio_path=config.to_storage_path(audio),
        source="dictation",
        transcript_raw=f"text of {name}",
        created_at=NOW - timedelta(days=age_days),
    )
    db.add(row)
    db.commit()
    return audio


def _audio_paths(db) -> dict[str, str]:
    from backend.database import Capture as DBCapture

    db.expire_all()
    return {r.id: r.audio_path for r in db.query(DBCapture).all()}


def test_seven_days_deletes_only_older_audio_and_keeps_the_text(store):
    db, captures_dir, _ = store
    old = _row(db, captures_dir, "old", 10)
    recent = _row(db, captures_dir, "recent", 1)
    _row(db, captures_dir, "missing", 20, write=False)

    assert purge_expired_audio(db, "7d", now=NOW) == 2

    assert not old.exists()
    assert recent.exists()
    paths = _audio_paths(db)
    assert paths["old"] == "" and paths["missing"] == ""
    assert paths["recent"] != ""
    from backend.database import Capture as DBCapture

    assert db.query(DBCapture).filter_by(id="old").one().transcript_raw == "text of old"
    # A second run has nothing left to do.
    assert purge_expired_audio(db, "7d", now=NOW) == 0


def test_always_keeps_everything_and_never_deletes_everything(store):
    db, captures_dir, _ = store
    old = _row(db, captures_dir, "old", 400)
    new = _row(db, captures_dir, "new", 0)

    assert purge_expired_audio(db, "always", now=NOW) == 0
    assert old.exists() and new.exists()

    assert purge_expired_audio(db, "never", now=NOW) == 2
    assert not old.exists() and not new.exists()
    assert set(_audio_paths(db).values()) == {""}


def test_a_file_outside_the_captures_dir_is_never_deleted(store):
    db, captures_dir, tmp_path = store
    outside = tmp_path / "elsewhere.wav"
    _row(db, captures_dir, "outside", 40, path=outside)

    assert purge_expired_audio(db, "30d", now=NOW) == 0
    assert outside.exists()
    assert _audio_paths(db)["outside"] != ""


def test_a_file_that_cannot_be_deleted_keeps_its_row_for_the_next_run(store, monkeypatch):
    db, captures_dir, _ = store
    locked = _row(db, captures_dir, "locked", 40)
    _row(db, captures_dir, "free", 40)
    real_unlink = Path.unlink

    def unlink(self, missing_ok=False):
        if self.name == "locked.wav":
            raise PermissionError("in use")
        return real_unlink(self, missing_ok=missing_ok)

    monkeypatch.setattr(Path, "unlink", unlink)
    assert purge_expired_audio(db, "30d", now=NOW) == 1
    assert locked.exists()
    paths = _audio_paths(db)
    assert paths["locked"] != "" and paths["free"] == ""


# ── Setting and routes ────────────────────────────────────────────────────


def _dictate(api):
    res = api.post(
        "/captures",
        files={"file": ("clip.wav", _wav_bytes(), "audio/wav")},
        data={"source": "dictation", "language": "pt"},
    )
    assert res.status_code == 200, res.text
    return res.json()


def _configure(api, url, retention):
    res = api.put(
        "/settings/captures",
        json={
            "custom_stt_endpoint": url,
            "custom_stt_model": "whisper-large-v3-turbo",
            "audio_retention": retention,
        },
    )
    assert res.status_code == 200, res.text


def test_setting_defaults_to_always_and_rejects_unknown_values(api):
    assert api.get("/settings/captures").json()["audio_retention"] == "always"
    assert api.put("/settings/captures", json={"audio_retention": "90d"}).status_code == 422
    assert api.put("/settings/captures", json={"audio_retention": "30d"}).status_code == 200
    assert api.get("/settings/captures").json()["audio_retention"] == "30d"


def test_never_keeps_the_transcript_but_not_the_audio(api, tmp_path):
    with FakeServer(lambda *_: (200, {"text": "Bom dia."})) as stt:
        _configure(api, stt.base_url, "never")
        capture = _dictate(api)

    assert capture["transcript_raw"] == "Bom dia."
    assert capture["audio_available"] is False
    assert capture["audio_path"] == ""
    assert list((tmp_path / "captures").iterdir()) == []

    listed = api.get("/captures").json()["items"][0]
    assert listed["audio_available"] is False
    assert api.get(f"/captures/{capture['id']}/audio").status_code == 404
    assert api.post(f"/captures/{capture['id']}/retranscribe", json={}).status_code == 410
    assert api.delete(f"/captures/{capture['id']}").status_code == 200


def test_always_keeps_the_audio_and_a_shorter_setting_applies_at_once(api, tmp_path):
    with FakeServer(lambda *_: (200, {"text": "Bom dia."})) as stt:
        _configure(api, stt.base_url, "always")
        capture = _dictate(api)
    assert capture["audio_available"] is True
    assert api.get(f"/captures/{capture['id']}/audio").status_code == 200

    # Age the capture past 7 days, then tighten the setting.
    old = (datetime.now(UTC) - timedelta(days=8)).replace(tzinfo=None)
    with sqlite3.connect(tmp_path / "api.db") as conn:
        conn.execute("UPDATE captures SET created_at = ?", (old.isoformat(sep=" "),))
    assert api.put("/settings/captures", json={"audio_retention": "7d"}).status_code == 200

    after = api.get(f"/captures/{capture['id']}").json()
    assert after["audio_available"] is False
    assert after["transcript_raw"] == "Bom dia."
    assert list((tmp_path / "captures").iterdir()) == []


def test_migration_adds_the_retention_column_with_the_current_behaviour(tmp_path):
    from sqlalchemy import create_engine, text
    from sqlalchemy.orm import sessionmaker

    from backend.database.migrations import run_migrations
    from backend.database.models import Base
    from backend.services import settings as settings_service

    engine = create_engine(f"sqlite:///{tmp_path / 'old.db'}")
    Base.metadata.create_all(engine)
    with engine.connect() as conn:
        conn.execute(text("ALTER TABLE capture_settings DROP COLUMN audio_retention"))
        conn.execute(
            text(
                "INSERT INTO capture_settings (id, stt_model, language, auto_refine, llm_model, "
                "smart_cleanup, self_correction, preserve_technical, allow_auto_paste, hotkey_enabled, "
                "chord_push_to_talk_keys, chord_toggle_to_talk_keys, vocabulary, replacements, snippets) "
                "VALUES (1, 'turbo', 'pt', 1, '0.6B', 1, 1, 1, 1, 0, '[\"AltGr\"]', '[\"AltGr\",\"Space\"]', "
                "'[]', '[]', '[]')"
            )
        )
        conn.commit()

    run_migrations(engine)
    run_migrations(engine)  # idempotent

    db = sessionmaker(bind=engine)()
    try:
        assert settings_service.get_capture_settings(db).audio_retention == "always"
    finally:
        db.close()
