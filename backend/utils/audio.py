"""
Audio processing utilities.
"""

from pathlib import Path
from typing import Tuple, Optional

import numpy as np
import soundfile as sf

# librosa (with numba and scipy) is imported only where it is needed: the
# lite edition ships without it, and the dictation path never needs it.

# librosa.load's default resampler; load_audio uses the same so its output
# matches what librosa returned before.
RESAMPLE_QUALITY = "soxr_hq"


class UnsupportedAudioError(ValueError):
    """The audio is in a format this server can't decode.

    soundfile (libsndfile) reads WAV, FLAC, OGG and MP3. Other formats
    (m4a, webm…) need librosa's audioread/ffmpeg fallback, which the lite
    edition doesn't ship; the lite app converts imported files to WAV first.
    """


def normalize_audio(
    audio: np.ndarray,
    target_db: float = -20.0,
    peak_limit: float = 0.85,
) -> np.ndarray:
    """
    Normalize audio to target loudness with peak limiting.
    
    Args:
        audio: Input audio array
        target_db: Target RMS level in dB
        peak_limit: Peak limit (0.0-1.0)
        
    Returns:
        Normalized audio array
    """
    # Convert to float32
    audio = audio.astype(np.float32)
    
    # Calculate current RMS
    rms = np.sqrt(np.mean(audio**2))
    
    # Calculate target RMS
    target_rms = 10**(target_db / 20)
    
    # Apply gain
    if rms > 0:
        gain = target_rms / rms
        audio = audio * gain
    
    # Peak limiting
    audio = np.clip(audio, -peak_limit, peak_limit)
    
    return audio


def load_audio(
    path: str,
    sample_rate: int = 24000,
    mono: bool = True,
) -> Tuple[np.ndarray, int]:
    """
    Load audio file as float32, resampled to ``sample_rate``.

    Takes the steps ``librosa.load`` takes for a file soundfile can read
    (decode, average the channels, resample with soxr at ``soxr_hq``,
    fix the length), so the samples are the same, without importing
    librosa. Other formats go through ``librosa.load`` and its audioread
    fallback when librosa is installed (studio).

    Args:
        path: Path to audio file
        sample_rate: Target sample rate (``None`` keeps the file's)
        mono: Convert to mono

    Returns:
        Tuple of (audio_array, sample_rate)

    Raises:
        UnsupportedAudioError: soundfile can't read the file and librosa
            isn't installed (lite edition).
    """
    try:
        with sf.SoundFile(path) as f:
            native_sr = f.samplerate
            audio = f.read(dtype="float32", always_2d=False).T
    except sf.SoundFileRuntimeError as exc:
        try:
            import librosa
        except ImportError:
            suffix = Path(str(path)).suffix.lower()
            kind = f"{suffix} " if suffix else ""
            raise UnsupportedAudioError(
                f"Unsupported or unreadable {kind}audio: this server reads WAV, FLAC, OGG and MP3"
            ) from exc
        return librosa.load(path, sr=sample_rate, mono=mono)

    if mono and audio.ndim > 1:
        audio = np.mean(audio, axis=tuple(range(audio.ndim - 1)))
    if sample_rate is None:
        return audio, native_sr
    return resample_audio(audio, native_sr, sample_rate), sample_rate


def resample_audio(audio: np.ndarray, orig_sr: int, target_sr: int) -> np.ndarray:
    """Resample along the last axis like ``librosa.resample`` (soxr_hq, fixed length)."""
    if orig_sr == target_sr:
        return audio
    import soxr

    n_samples = int(np.ceil(audio.shape[-1] * float(target_sr) / orig_sr))
    resampled = np.apply_along_axis(
        soxr.resample,
        axis=-1,
        arr=audio,
        in_rate=orig_sr,
        out_rate=target_sr,
        quality=RESAMPLE_QUALITY,
    )
    # soxr may return a sample more or less than the exact ratio; librosa
    # pads with zeros or trims to ceil(n * ratio).
    current = resampled.shape[-1]
    if current > n_samples:
        resampled = resampled[..., :n_samples]
    elif current < n_samples:
        pad = [(0, 0)] * (resampled.ndim - 1) + [(0, n_samples - current)]
        resampled = np.pad(resampled, pad, mode="constant")
    return np.asarray(resampled, dtype=audio.dtype)


def audio_duration(path: str) -> float:
    """Length of an audio file in seconds, read from its header when possible.

    Falls back to decoding with :func:`load_audio` (which raises
    :class:`UnsupportedAudioError` for a format the lite edition can't read).
    """
    try:
        info = sf.info(str(path))
    except sf.SoundFileRuntimeError:
        audio, sr = load_audio(path)
        return len(audio) / sr
    return info.frames / info.samplerate


def save_audio(
    audio: np.ndarray,
    path: str,
    sample_rate: int = 24000,
) -> None:
    """
    Save audio file with atomic write and error handling.

    Writes to a temporary file first, then atomically renames to the
    target path.  This prevents corrupted/partial WAV files if the
    process is interrupted mid-write.

    Args:
        audio: Audio array
        path: Output path
        sample_rate: Sample rate

    Raises:
        OSError: If file cannot be written
    """
    from pathlib import Path
    import os

    temp_path = f"{path}.tmp"
    try:
        # Ensure parent directory exists
        Path(path).parent.mkdir(parents=True, exist_ok=True)

        # Write to temporary file first (explicit format since .tmp
        # extension is not recognised by soundfile)
        sf.write(temp_path, audio, sample_rate, format='WAV')

        # Atomic rename to final path
        os.replace(temp_path, path)

    except Exception as e:
        # Clean up temp file on failure
        try:
            if Path(temp_path).exists():
                Path(temp_path).unlink()
        except Exception:
            pass  # Best effort cleanup

        raise OSError(f"Failed to save audio to {path}: {e}") from e


def has_speech(
    audio: np.ndarray,
    sample_rate: int,
    frame_ms: int = 20,
    silence_threshold_db: float = -40.0,
    min_speech_ms: int = 150,
) -> bool:
    """Whether ``audio`` holds at least ``min_speech_ms`` of non-silent frames.

    A frame is non-silent when its RMS reaches ``silence_threshold_db``
    (dBFS), the test :func:`has_tts_runaway` uses. Counting frames rather
    than taking one peak keeps a click or a bump on the mic from passing
    for speech; a single short word is still several times longer.
    """
    frame_len = int(sample_rate * frame_ms / 1000)
    if frame_len == 0 or len(audio) < frame_len:
        return False
    n_frames = len(audio) // frame_len
    frames = np.asarray(audio[: n_frames * frame_len], dtype=np.float32).reshape(n_frames, frame_len)
    rms = np.sqrt(np.mean(frames**2, axis=1))
    loud = int(np.count_nonzero(rms >= 10 ** (silence_threshold_db / 20)))
    return loud * frame_ms >= min_speech_ms


def has_tts_runaway(
    audio: np.ndarray,
    sample_rate: int = 24000,
    frame_ms: int = 20,
    silence_threshold_db: float = -40.0,
    max_internal_silence_ms: int = 2000,
) -> bool:
    """Detect speech followed by a long silence and then more output.

    This shape is a reliable signal that a TTS model missed EOS and resumed
    with hallucinated speech or codec noise. Leading and trailing silence do
    not count because they are not bounded by non-silent audio.
    """
    frame_len = int(sample_rate * frame_ms / 1000)
    if frame_len == 0 or len(audio) < frame_len:
        return False

    n_frames = len(audio) // frame_len
    threshold_linear = 10 ** (silence_threshold_db / 20)
    max_silence_frames = int(max_internal_silence_ms / frame_ms)
    seen_speech = False
    consecutive_silence = 0

    for i in range(n_frames):
        frame = audio[i * frame_len : (i + 1) * frame_len]
        is_speech = np.sqrt(np.mean(frame**2)) >= threshold_linear
        if is_speech:
            if seen_speech and consecutive_silence >= max_silence_frames:
                return True
            seen_speech = True
            consecutive_silence = 0
        elif seen_speech:
            consecutive_silence += 1

    return False


def trim_tts_output(
    audio: np.ndarray,
    sample_rate: int = 24000,
    frame_ms: int = 20,
    silence_threshold_db: float = -40.0,
    min_silence_ms: int = 200,
    max_internal_silence_ms: int | None = 1000,
    fade_ms: int = 30,
) -> np.ndarray:
    """
    Trim trailing silence and post-silence hallucination from TTS output.

    Chatterbox sometimes produces ``[speech][silence][hallucinated noise]``.
    This detects internal silence gaps longer than *max_internal_silence_ms*
    and cuts the audio at that boundary, then trims trailing silence and
    applies a short cosine fade-out.

    Args:
        audio: Input audio array (mono float32)
        sample_rate: Sample rate in Hz
        frame_ms: Frame size for RMS energy calculation
        silence_threshold_db: dB threshold below which a frame is silence
        min_silence_ms: Minimum trailing silence to keep
        max_internal_silence_ms: Cut after any silence gap longer than this.
            ``None`` disables the internal cut and only trims the edges.
        fade_ms: Cosine fade-out duration in ms

    Returns:
        Trimmed audio array
    """
    frame_len = int(sample_rate * frame_ms / 1000)
    if frame_len == 0 or len(audio) < frame_len:
        return audio

    n_frames = len(audio) // frame_len
    threshold_linear = 10 ** (silence_threshold_db / 20)

    # Compute per-frame RMS
    rms = np.array(
        [
            np.sqrt(np.mean(audio[i * frame_len : (i + 1) * frame_len] ** 2))
            for i in range(n_frames)
        ]
    )
    is_speech = rms >= threshold_linear

    # Find first speech frame
    first_speech = 0
    for i, s in enumerate(is_speech):
        if s:
            first_speech = max(0, i - 1)  # keep 1 frame padding
            break

    # Walk forward from first speech; cut at long internal silence gaps
    cut_frame = n_frames
    if max_internal_silence_ms is not None:
        max_silence_frames = int(max_internal_silence_ms / frame_ms)
        consecutive_silence = 0
        for i in range(first_speech, n_frames):
            if is_speech[i]:
                consecutive_silence = 0
            else:
                consecutive_silence += 1
                if consecutive_silence >= max_silence_frames:
                    cut_frame = i - consecutive_silence + 1
                    break

    # Trim trailing silence from the cut point
    min_silence_frames = int(min_silence_ms / frame_ms)
    end_frame = cut_frame
    while end_frame > first_speech and not is_speech[end_frame - 1]:
        end_frame -= 1
    # Keep a short tail
    end_frame = min(end_frame + min_silence_frames, cut_frame)

    # Convert frames back to samples
    start_sample = first_speech * frame_len
    end_sample = min(end_frame * frame_len, len(audio))

    trimmed = audio[start_sample:end_sample].copy()

    # Cosine fade-out
    fade_samples = int(sample_rate * fade_ms / 1000)
    if fade_samples > 0 and len(trimmed) > fade_samples:
        fade = np.cos(np.linspace(0, np.pi / 2, fade_samples)) ** 2
        trimmed[-fade_samples:] *= fade

    return trimmed


def preprocess_reference_audio(
    audio: np.ndarray,
    sample_rate: int,
    peak_target: float = 0.95,
    trim_top_db: float = 40.0,
    edge_padding_ms: int = 100,
) -> np.ndarray:
    """
    Clean up a reference-audio sample before validation/storage.

    Removes DC offset, trims leading/trailing silence, and caps the peak so a
    slightly-hot recording doesn't get rejected downstream as "clipping". The
    goal is to accept reasonable real-world recordings — not to repair badly
    distorted ones. True clipping artifacts inside the waveform can't be
    recovered by peak scaling and will still sound bad.

    Args:
        audio: Mono audio array.
        sample_rate: Sample rate of ``audio`` in Hz.
        peak_target: Peak amplitude cap in [0, 1]. Applied only if the input
            peak exceeds this value.
        trim_top_db: Silence threshold for edge trimming, in dB below peak.
            40 dB sits below normal speech dynamic range (≈30 dB) so soft
            trailing syllables are preserved, while still catching obvious
            leading/trailing silence. Lower values are more aggressive;
            librosa's own default is 60.
        edge_padding_ms: Milliseconds of padding to add back at each edge
            *only if* trimming shortened the waveform, so TTS engines have a
            brief silence to anchor on without ever making the output longer
            than the input.

    Returns:
        Preprocessed audio array (float32).
    """
    audio = audio.astype(np.float32, copy=False)

    if audio.size == 0:
        return audio

    audio = audio - float(np.mean(audio))

    import librosa

    trimmed, _ = librosa.effects.trim(audio, top_db=trim_top_db)
    if 0 < trimmed.size < audio.size:
        pad_each = int(sample_rate * edge_padding_ms / 1000)
        # Never pad past the original length — for near-max-duration uploads
        # an unconditional pad would push them over the 30 s ceiling and
        # trigger a spurious "too long" rejection.
        headroom = (audio.size - trimmed.size) // 2
        pad = min(pad_each, max(headroom, 0))
        if pad > 0:
            trimmed = np.pad(trimmed, (pad, pad), mode="constant")
        audio = trimmed

    peak = float(np.abs(audio).max())
    if peak > peak_target and peak > 0:
        audio = audio * (peak_target / peak)

    return audio


def validate_reference_audio(
    audio_path: str,
    min_duration: float = 2.0,
    max_duration: float = 30.0,
    min_rms: float = 0.01,
) -> Tuple[bool, Optional[str]]:
    """
    Validate reference audio for voice cloning.

    Args:
        audio_path: Path to audio file
        min_duration: Minimum duration in seconds
        max_duration: Maximum duration in seconds
        min_rms: Minimum RMS level

    Returns:
        Tuple of (is_valid, error_message)
    """
    result = validate_and_load_reference_audio(
        audio_path, min_duration, max_duration, min_rms
    )
    return (result[0], result[1])


def validate_and_load_reference_audio(
    audio_path: str,
    min_duration: float = 2.0,
    max_duration: float = 30.0,
    min_rms: float = 0.01,
) -> Tuple[bool, Optional[str], Optional[np.ndarray], Optional[int]]:
    """
    Validate and load reference audio in a single pass.

    Applies :func:`preprocess_reference_audio` before checks so that
    slightly-hot recordings aren't rejected as clipping. Duration and RMS
    checks run on the preprocessed waveform.

    Returns:
        Tuple of (is_valid, error_message, audio_array, sample_rate)
    """
    try:
        audio, sr = load_audio(audio_path)
        audio = preprocess_reference_audio(audio, sr)
        duration = len(audio) / sr

        if duration < min_duration:
            return False, f"Audio too short (minimum {min_duration} seconds)", None, None
        if duration > max_duration:
            return False, f"Audio too long (maximum {max_duration} seconds)", None, None

        rms = np.sqrt(np.mean(audio**2))
        if rms < min_rms:
            return False, "Audio is too quiet or silent", None, None

        return True, None, audio, sr
    except Exception as e:
        return False, f"Error validating audio: {str(e)}", None, None
