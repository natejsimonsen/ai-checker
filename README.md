# AI Song Checker

Android app that estimates how likely a song is to be AI-generated, in the spirit of
SubmitHub's AI Song Checker. Paste or share a Spotify link (or pick an audio file)
and get an AI-likelihood score, a verdict, and a section-by-section timeline.

**Everything runs on the phone.** There's no backend, no API key and no account. Audio never leaves the device.

## How it works

```
Spotify link ──► open.spotify.com/embed/track/{id} ──► metadata + 30 s preview MP3
                                                          │
Audio file ───────────────────────────────────────────────┤
                                                          ▼
                    MediaCodec decode ─► mono ─► resample to 16 kHz (torchaudio-exact sinc)
                                                          ▼
                    STFT (8192) ─► mean dB spectrum 1–8 kHz ─► minus lower hull = "fakeprint"
                                                          ▼
                    logistic regression ─► AI probability (whole clip + 10 s windows)
```

* **Detector.** Generators such as Suno and Udio produce audio through neural-vocoder
  upsampling layers, which leave a regular comb of narrow spectral peaks. The
  *fakeprint* isolates those peaks (Afchar et al., *A Fourier Explanation of AI-music
  Artifacts*, ISMIR 2025). Weights come from
  [lofcz/ai-music-detector](https://github.com/lofcz/ai-music-detector) (MIT). Its
  self-reported test set has 99.9% accuracy and a 0.3% false-positive rate on Suno ≤ v5 / Udio ≤ v1.5.
  The upstream ONNX graph is exactly `sigmoid(w·x + b)`, so the app ships the
  3,585 weights as a 14 KB file and runs them in plain Kotlin. No ML runtime is needed.
* **Verdict.** Comes from the whole-clip score: **Likely AI** at 85% or above (SubmitHub's blocking line),
  **Possibly AI / hybrid** from 50%, **Inconclusive** from 15%, and **Likely human** at 15% or below. The app also
  scores each 10-second window for a timeline, but the model was trained on whole tracks, so
  those windows are noisy (on AI songs they swing between 0 and 100%). They're shown, but they don't
  drive the verdict.
* **Spotify audio.** Spotify's Web API no longer returns previews for new apps
  (since Nov 2024), so the app reads the public embed page, the same one any website
  embedding a Spotify player loads. That page format is undocumented and may change; the parser
  is defensive and CI checks it against live Spotify on every push.

### Measured so far (CI, every push)

| Input | Score |
|---|---|
| 4 human hits via live Spotify previews (Rick Astley, The Killers, The Weeknd, Ed Sheeran) | 0.0% each, *Likely human* |
| 4 full Suno songs + 2 full Udio songs (SONICS dataset) | 99.9–100% |
| 1 short (33 s) Udio song | 80.6% |
| Same AI songs as 30 s / 96 kbps preview-style re-encodes: Suno | 99.6–100% |
| Same, Udio | 94%, 16%, 0.5% (the last clip is only 13 s) |

SONICS was part of the model's training data, so these numbers check the pipeline, not
the model's accuracy on new songs. The takeaway: Suno is caught reliably even from a preview, while
Udio's fingerprint fades in short, low-bitrate clips.

### Known limits

* A preview is only 30 s. Picking the full audio file gives a steadier result, especially for Udio.
* The model only knows the generators it was trained on. Newer ones, heavy mastering,
  re-encoding or re-recorded vocals can hide the fingerprint. A low score is not proof that a human made the track.
* There's no temporal/structural model yet (SubmitHub runs a second one; see *Next steps*).

## Project layout

| Path | What |
|---|---|
| `core/` | Pure Kotlin/JVM: DSP, model, Spotify parsing, desktop CLI. Shared by the app and CI. |
| `app/` | Jetpack Compose app (Material 3, dynamic color, edge-to-edge), API 37 target, min API 29. |
| `tools/reference/` | Python scripts that export the weights and generate parity fixtures from the upstream torchaudio pipeline. |
| `.github/workflows/build.yml` | Unit tests, debug + release APK, emulator test of the MediaCodec path, live Spotify + known-AI smoke tests. |

Toolchain: AGP 9.4 (built-in Kotlin), Kotlin 2.4, Gradle 9.8, Compose BOM 2026.09, compileSdk/targetSdk 37.

## Running it

**Install on a phone (no tools needed).** Open the latest *build* run under
GitHub → Actions, download the `ai-song-checker-apk` artifact, unzip it, and install
`app-release.apk` (or the debug one) on your phone. Allow "install unknown apps" when asked.
Then share any song from Spotify → **AI Song Checker**.

**Build locally.** Android Studio (latest) or the command line with the Android SDK (API 37) installed:

```sh
./gradlew :app:installDebug        # with a phone/emulator connected
./gradlew :core:test               # detector + parser tests (no Android SDK needed: gradle -p core test)
./gradlew :core:run --args="https://open.spotify.com/track/4PTG3Z6ehGkBFwjybzWkR8"   # CLI, needs ffmpeg
```

**Check specific songs in CI.** Actions → *build* → *Run workflow*, then paste links into `links`.

## Next steps

1. **Second model (temporal).** Add [SONICS / SpecTTTra](https://github.com/awsaf49/sonics)
   (ICLR 2025) exported to ONNX or LiteRT, and blend it with the spectral score weighted by clip
   length, the way SubmitHub combines its two models.
2. **Calibration set.** A labelled set of human and Suno/Udio/ElevenLabs tracks run through the
   preview path, to tune the 85% / 15% thresholds for MP3-preview audio specifically.
3. **Optional hosted mode.** A small backend could cache results per track ID and run heavier
   models. The app would keep working offline with the on-device model.
