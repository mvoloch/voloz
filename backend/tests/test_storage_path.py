"""
``config.to_storage_path`` must store a path inside the configured data dir
relative to that dir, even when the data dir itself sits under a folder
named ``data`` (``/mnt/data/...``, ``/data/home/...``). Before the fix the
first "data" component won, the stored path was not under the data dir,
and ``resolve_storage_path`` pointed at a file that doesn't exist (no audio
playback, and the audio-retention cleanup refused to touch it).
"""

from backend import config


def test_a_data_dir_under_a_folder_named_data_round_trips(tmp_path, monkeypatch):
    data_dir = tmp_path / "data" / "dev" / "app-data"
    data_dir.mkdir(parents=True)
    monkeypatch.setattr(config, "_data_dir", data_dir.resolve())
    audio = data_dir / "captures" / "x.wav"
    audio.parent.mkdir()
    audio.write_bytes(b"RIFF")

    stored = config.to_storage_path(audio)
    assert stored == "captures/x.wav"
    assert config.resolve_storage_path(stored) == audio.resolve()


def test_legacy_and_outside_paths_are_unchanged(tmp_path, monkeypatch):
    data_dir = tmp_path / "data"
    data_dir.mkdir()
    monkeypatch.setattr(config, "_data_dir", data_dir.resolve())
    # A path in another install's "data" dir is still stored relative to it.
    other = tmp_path / "old" / "data" / "profiles" / "a.wav"
    assert config.to_storage_path(other) == "profiles/a.wav"
    # A path outside any data dir stays absolute.
    outside = tmp_path / "elsewhere" / "b.wav"
    assert config.to_storage_path(outside) == str(outside.resolve())
