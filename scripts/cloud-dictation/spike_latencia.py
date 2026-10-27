"""Spike: mede a latência real do ditado em nuvem (transcrição + polimento).

Grava do microfone (ou lê um .wav), transcreve via OpenRouter e pole com o
Claude Haiku, cronometrando cada etapa. Não altera nada no Voicebox.

Chaves lidas de variáveis de ambiente (nunca do código):
    OPENROUTER_API_KEY   transcrição
    ANTHROPIC_API_KEY    polimento (chave do Console ligada ao Claude Max)

Uso:
    python spike_latencia.py                 # grava 15 s do microfone
    python spike_latencia.py --seconds 8
    python spike_latencia.py --wav fala.wav
"""

import argparse
import base64
import io
import os
import sys
import time
import wave

import anthropic
import httpx

SAMPLE_RATE = 16_000
STT_URL = "https://openrouter.ai/api/v1/audio/transcriptions"
STT_MODEL = "openai/whisper-large-v3-turbo"
POLISH_MODEL = "claude-haiku-5-5"

POLISH_SYSTEM = (
    "Você recebe a transcrição crua de um ditado em português. Devolva o mesmo "
    "texto pronto para colar: corrija pontuação e maiúsculas, remova hesitações "
    "('é...', 'tipo', 'né', repetições) e autocorreções faladas, mantendo as "
    "palavras e o tom de quem falou. Não acrescente nada, não responda ao "
    "conteúdo, não explique. Devolva apenas o texto final."
)


def record(seconds: float) -> bytes:
    import sounddevice as sd  # importado só quando grava

    print(f"Gravando {seconds:.0f} s — fale agora…", flush=True)
    frames = sd.rec(int(seconds * SAMPLE_RATE), samplerate=SAMPLE_RATE, channels=1, dtype="int16")
    sd.wait()
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(frames.tobytes())
    return buf.getvalue()


def wav_seconds(data: bytes) -> float:
    with wave.open(io.BytesIO(data)) as w:
        return w.getnframes() / w.getframerate()


def transcribe(client: httpx.Client, audio: bytes, provider: dict | None) -> tuple[str, float, dict]:
    body = {
        "model": STT_MODEL,
        "input_audio": {"data": base64.b64encode(audio).decode(), "format": "wav"},
        "language": "pt",
    }
    if provider:
        body["provider"] = provider
    t0 = time.perf_counter()
    r = client.post(STT_URL, json=body)
    elapsed = time.perf_counter() - t0
    if r.status_code != 200:
        raise RuntimeError(f"OpenRouter HTTP {r.status_code}: {r.text[:300]}")
    data = r.json()
    return data.get("text", "").strip(), elapsed, data.get("usage", {})


def polish(client: anthropic.Anthropic, text: str, thinking_off: bool) -> tuple[str, float]:
    kwargs = {}
    if thinking_off:
        kwargs["thinking"] = {"type": "disabled"}  # aceito em effort low/medium/high
    t0 = time.perf_counter()
    resp = client.messages.create(
        model=POLISH_MODEL,
        max_tokens=2048,
        system=POLISH_SYSTEM,
        output_config={"effort": "low"},
        messages=[{"role": "user", "content": text}],
        **kwargs,
    )
    elapsed = time.perf_counter() - t0
    if resp.stop_reason == "refusal":
        raise RuntimeError("Haiku recusou o pedido")
    out = "".join(b.text for b in resp.content if b.type == "text").strip()
    return out, elapsed


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=float, default=15)
    ap.add_argument("--wav", help="usa este arquivo em vez de gravar")
    args = ap.parse_args()

    missing = [k for k in ("OPENROUTER_API_KEY", "ANTHROPIC_API_KEY") if not os.environ.get(k)]
    if missing:
        print("Faltam variáveis de ambiente:", ", ".join(missing))
        return 2

    audio = open(args.wav, "rb").read() if args.wav else record(args.seconds)
    dur = wav_seconds(audio)
    print(f"Áudio: {dur:.1f} s, {len(audio) / 1024:.0f} KB (WAV 16 kHz mono)\n")

    http = httpx.Client(
        timeout=60,
        headers={"Authorization": f"Bearer {os.environ['OPENROUTER_API_KEY']}"},
    )
    claude = anthropic.Anthropic()

    rows = []
    transcript = ""
    # A primeira chamada paga o handshake TLS; as rotas seguintes reaproveitam a conexão.
    for label, provider in (
        ("STT  OpenRouter (rota padrão)", None),
        ("STT  OpenRouter fixado no Groq", {"order": ["groq"], "allow_fallbacks": False}),
    ):
        try:
            text, t, usage = transcribe(http, audio, provider)
            rows.append((label, t, f"US$ {usage.get('cost', '?')}"))
            transcript = transcript or text
        except Exception as e:  # noqa: BLE001 — spike: reportar e seguir
            rows.append((label, float("nan"), f"ERRO: {e}"))

    if not transcript:
        for r in rows:
            print(f"{r[0]:<36} {r[2]}")
        return 1

    polished = {}
    for label, off in (("POL  Haiku effort=low, thinking off", True), ("POL  Haiku effort=low, adaptive", False)):
        try:
            out, t = polish(claude, transcript, off)
            rows.append((label, t, ""))
            polished[label] = out
        except Exception as e:  # noqa: BLE001
            rows.append((label, float("nan"), f"ERRO: {e}"))

    print(f"{'etapa':<36} {'segundos':>8}")
    for label, t, note in rows:
        print(f"{label:<36} {t:>8.2f}  {note}")
    stt_best = min((t for l, t, _ in rows if l.startswith("STT") and t == t), default=float("nan"))
    pol_best = min((t for l, t, _ in rows if l.startswith("POL") and t == t), default=float("nan"))
    print(f"\nMelhor caminho (STT + polimento): {stt_best + pol_best:.2f} s para {dur:.0f} s de fala")

    print("\n--- transcrição crua ---\n" + transcript)
    for label, out in polished.items():
        print(f"\n--- {label.strip()} ---\n{out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
