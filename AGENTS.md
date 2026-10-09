# Alalay — agent notes

Offline-first, voice-controlled accessibility agent for blind/low-vision
Android users in the Philippines. Specs live in `spec/` — they are the
source of truth (00-README → 09-Acceptance-Tests).

## Layout

- `app/` — the agent (`com.alalay.app`)
  - `agent/` — pure-Kotlin core, JVM-testable: `ActionV1`/`ActionContract`
    (wire protocol `alalay.action.v1`), `Router`, `SafetyGate`,
    `ConfirmationGate`, `Orchestrator`/`RulesPlanner`, `AgentLoop`,
    `IntentParser`, `Templates` (TPL-01..18 EN/TL).
  - `device/` — `ScreenInspector` (accessibility dump + compactor) and
    `Executor` (action primitives; `confirmSend()` is code-only).
  - `accessibility/` — `AlalayAccessibilityService`.
  - `speech/` — `Speaker` (system TTS), `VoiceInput`
    (SpeechRecognizer offline + whisper seam).
  - `memory/` — Room store (`aliases`, `prefs`, `rolling_context`) +
    `AliasResolver` (pure).
  - `models/` — `ModelStore`: extracts `assets/models/` → `files/models/`
    on first launch (.staged marker, size + pinned SHA-256 verify).
  - `cpp/` — vendored `llama.cpp` v0.6.0 + `whisper.cpp` v1.9.5 (one
    shared ggml 0.26.0), JNI shims `jni_llama.cpp`/`jni_whisper.cpp` →
    `libalalay_llama`/`libalalay_whisper`, arm64-v8a only, built by
    externalNativeBuild (CMake 3.22.1, NDK installed).
  - Weights in `app/src/main/assets/models/` (gitignored, ~880 MB —
    `tools/fetch-models.sh` re-fetches; SHA-256 pinned in `AlalayAgent`):
    `MiniCPM5-1B-Q4_K_M.gguf` (planner), `ggml-small-q5_1.bin` (STT).
  - `ui/` — Compose: Home (hold-to-talk), Diagnostics, Setup.
  - `diagnostics/` — `DiagnosticsBus`.
- `demomessenger/` — `com.alalay.demomessenger`, a Views-based target
  app with seeded threads (2× Mama + 2× Rosa ambiguity pairs; Mama
  Remedios' latest message is the AT-03 fixture).

## Build & test

```sh
./gradlew :app:compileDebugKotlin :demomessenger:compileDebugKotlin
./gradlew :app:testDebugUnitTest     # 85 JVM tests (JUnit4 + coroutines-test)
./gradlew assembleDebug              # both APKs; manifest merge included
```

JVM tests substitute the `Interfaces.kt` seams (`ScreenSource`,
`ActionPerformer`, `SpokenOutput`, `VoiceCapture`, `MemoryAccess`,
`Planner`) with fakes in `app/src/test/java/com/alalay/app/Fakes.kt`.
`AgentLoop.submitTranscript` returns the task `Job` — `join()` it in
tests instead of polling.

## Toolchain deviations from spec (06-TRD says Kotlin 2.2.20)

AGP 9.0.1 forces these versions; do not "fix" them back:

- Kotlin 2.3.10 — `org.jetbrains.kotlin.android` is not applied at all;
  AGP 9 built-in Kotlin handles compilation (the plugin crashes against
  AGP 9's new extension types).
- KSP 2.3.3 — first KSP line with AGP 9 support; requires
  `android.disallowKotlinSourceSets=false` in `gradle.properties`.
- `androidx.core` capped at 1.15.0 — 1.19+ requires compileSdk 37 /
  AGP 9.1 which are not installed here.

## Non-negotiables (from spec)

- No `INTERNET` permission — verified absent from the packaged manifest
  (`tools:node="remove"` guards against transitive merges).
- The model can emit only 8 tools; `confirm_send`, `emergency_stop`,
  `memory_write` are rejected at parse. Send exists only as
  `Executor.confirmSend()` behind `ConfirmationGate` (voice yes/no/30 s
  timeout).
- No message bodies or contact names in logs or Room.
- `SafetyGate.checkTask` runs on the raw utterance AND inside
  `runOpenShortcut` — do not remove the shortcut's blocklist check.
- Router yes/no are exact-match in `COMMAND` mode (otherwise "send
  money to mama" is eaten by the confirm-word "send"). In
  `AWAITING_CONFIRMATION`, deny wins over confirm and "go back" denies.

## Known gaps

- Native runtimes are real now: `LlamaCppRuntime` (llama.cpp) feeds
  `LiteRtLmPlanner`, `WhisperEngine` (whisper.cpp) feeds
  `WhisperVoiceInput` (AudioRecord 16 kHz mono → full-utterance
  transcription, abort via ggml callback). Both degrade honestly:
  missing .so/model → RulesPlanner / SpeechRecognizer fallback.
- LLM correctness on-device is unverified: MiniCPM5-1B chat-template
  handling (JNI tries GGUF template, then ChatML fallback), JSON
  validity rate, latency, and whisper Taglish WER are unmeasured —
  T-02/T-03 hardware spikes still required.
- Device acceptance tests (AT-01..13 on hardware) are unrun — no adb
  in this environment.
