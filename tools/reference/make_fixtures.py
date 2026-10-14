"""Generate parity fixtures for the Kotlin detector from the upstream Python pipeline.

The Kotlin port (core/src/main/kotlin/dev/aichecker/core) must reproduce
lofcz/ai-music-detector's src/python/inference.py, which uses torchaudio for
resampling and the STFT and scipy for the lower hull. This script runs exactly
that code path on deterministic synthetic signals and writes the intermediate
values the Kotlin tests compare against.

    pip install numpy scipy torch torchaudio
    python tools/reference/make_fixtures.py core/src/test/resources/fixtures

Inputs are written as 16-bit PCM so both sides start from bit-identical samples.
"""

import json
import struct
import sys
from pathlib import Path

import numpy as np
import torch
import torchaudio
from scipy.ndimage import minimum_filter1d

SR = 16000
N_FFT = 8192
FREQ_MIN, FREQ_MAX = 1000, 8000
HULL_AREA, MAX_DB, MIN_DB = 10, 5, -45

MODEL = Path(__file__).resolve().parents[2] / "core/src/main/resources/models/fakeprint_lr_v1.bin"


def load_model():
    raw = MODEL.read_bytes()
    assert raw[:4] == b"FPLR"
    _, n = struct.unpack("<ii", raw[4:12])
    (bias,) = struct.unpack("<f", raw[12:16])
    w = np.frombuffer(raw[16:16 + 4 * n], dtype="<f4")
    return w.astype(np.float64), float(bias)


def fakeprint(audio_16k: torch.Tensor) -> np.ndarray:
    """Verbatim port of AIDetector.compute_fakeprint (mono input)."""
    stft = torchaudio.transforms.Spectrogram(n_fft=N_FFT, power=2, normalized=False)
    spec = stft(audio_16k)
    spec_db = 10 * torch.log10(torch.clamp(spec, min=1e-10, max=1e6))
    mean_spectrum = spec_db.mean(dim=(0, 2)).numpy()
    freq_bins = np.linspace(0, SR / 2, num=(N_FFT // 2) + 1)
    mask = (freq_bins >= FREQ_MIN) & (freq_bins <= FREQ_MAX)
    freq_spectrum = mean_spectrum[mask]
    hull = minimum_filter1d(freq_spectrum, size=HULL_AREA, mode="nearest")
    hull = np.clip(hull, MIN_DB, None)
    residue = np.clip(freq_spectrum - hull, 0, None)
    residue = np.clip(residue, 0, MAX_DB)
    return (residue / (np.max(residue) + 1e-6)).astype(np.float32)


def synth(seconds: float, sr: int, comb_hz: float | None, seed: int) -> np.ndarray:
    """Music-ish test signal: harmonic notes + noise, optionally with a vocoder-like spectral comb."""
    rng = np.random.default_rng(seed)
    t = np.arange(int(seconds * sr)) / sr
    x = 0.02 * rng.standard_normal(t.size)
    for k, f0 in enumerate([110.0, 164.8, 220.0, 329.6]):
        env = 0.5 + 0.5 * np.sin(2 * np.pi * (0.25 + 0.1 * k) * t)
        for h in range(1, 12):
            if f0 * h < sr / 2:
                x += env * (0.15 / h) * np.sin(2 * np.pi * f0 * h * t + k)
    if comb_hz:
        for f in np.arange(comb_hz, min(sr / 2, 8000), comb_hz):
            x += 0.004 * np.sin(2 * np.pi * f * t)
    x /= np.max(np.abs(x)) * 1.05
    return np.round(x * 32767).astype("<i2")


def main(out_dir: str):
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    w, b = load_model()

    # 1) Resampler parity at common source rates.
    res = {}
    for sr in (44100, 48000, 22050):
        pcm = synth(0.4, sr, None, seed=sr)
        (out / f"resample_{sr}.pcm").write_bytes(pcm.tobytes())
        y = torchaudio.functional.resample(torch.from_numpy(pcm / 32768.0).float()[None], sr, SR)[0].numpy()
        res[str(sr)] = {"length": int(y.size), "samples": y.astype(float).tolist()}
    (out / "resample.json").write_text(json.dumps(res))

    # 2) Minimum filter semantics.
    v = np.random.default_rng(7).standard_normal(40).astype(np.float32)
    (out / "minfilter.json").write_text(json.dumps({
        "input": v.astype(float).tolist(),
        "output": minimum_filter1d(v, size=HULL_AREA, mode="nearest").astype(float).tolist(),
    }))

    # 3) End-to-end: 22.05 kHz input -> resample -> fakeprint -> probability.
    e2e = {}
    for name, comb in (("plain", None), ("comb", 375.0)):
        pcm = synth(12.0, 22050, comb, seed=1 if comb is None else 2)
        (out / f"e2e_{name}.pcm").write_bytes(pcm.tobytes())
        audio = torchaudio.functional.resample(torch.from_numpy(pcm / 32768.0).float()[None], 22050, SR)
        fp = fakeprint(audio)
        z = float(fp.astype(np.float64) @ w + b)
        p = 1 / (1 + np.exp(-z))
        e2e[name] = {"sampleRate": 22050, "fakeprint": fp.astype(float).tolist(), "logit": z, "probability": float(p)}
        print(f"{name}: logit={z:.4f} p={p:.6f}")
    (out / "e2e.json").write_text(json.dumps(e2e))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "core/src/test/resources/fixtures")
