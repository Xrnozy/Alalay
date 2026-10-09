# 04 — Architecture

| | |
|---|---|
| **Upstream** | 01-PRD, 02-MVP-Scope, 03-User-Flow |
| **Downstream** | 05-Architecture-Essentials (non-negotiables), 06-TRD (stack/repo analysis), 07-Agent-Specs (contracts), 08-Development-Plan (tasks) |

Stack: **native Android, Kotlin** (see 06-TRD §0.5 — the reference repo's Flutter shell is not reused; its Kotlin accessibility layer is ported).

## 1. Component Diagram

```mermaid
flowchart TB
    subgraph UI["UI Layer (single Activity + Compose)"]
        PTT[Push-to-talk button]
        DIAG[Diagnostics screen]
        SETUP[Setup/onboarding]
    end

    subgraph PIPE["Agent Pipeline"]
        VIN[VoiceInput<br/>whisper.cpp STT]
        RTR[Router<br/>deterministic rules]
        ORC[Orchestrator<br/>local LLM client]
        VAL[SafetyGate<br/>validator + allowlist + confirm FSM]
        LOOP[AgentLoop<br/>state machine controller]
    end

    subgraph DEV["On-Device Primitives"]
        INSP[ScreenInspector<br/>UI-tree dump + compactor]
        EXEC[Executor<br/>AccessibilityService actions]
        SPK[Speaker<br/>Android TTS]
        MEM[MemoryStore<br/>SQLite/Room]
    end

    LLM[[Local LLM<br/>Gemma E2B / LiteRT-LM<br/>fallback: llama.cpp GGUF]]

    PTT --> VIN --> RTR
    RTR -->|control utterance| LOOP
    RTR -->|task utterance| ORC
    LOOP <--> ORC
    ORC --> LLM
    LOOP --> VAL
    VAL --> EXEC
    VAL --> MEM
    LOOP --> INSP
    EXEC --> INSP
    LOOP --> SPK
    VAL --> SPK
    DIAG --- ORC
    DIAG --- EXEC
```

**Hard boundary:** `Orchestrator` output never reaches `Executor`. Everything funnels through `SafetyGate`. The model proposes; code disposes.

## 2. Data Flow (one command cycle)

1. `VoiceInput` records audio (≤10 s, PCM 16 kHz mono) → whisper.cpp → transcript string. Audio buffer discarded after transcription (never written to disk).
2. `Router` regex/keyword-matches control utterances. Match → direct action (cancel/confirm/back/open_app/repeat/ordinal answer). No match → pass transcript to `AgentLoop`.
3. `AgentLoop` enters PLANNING: `ScreenInspector.dump()` produces a ≤60-element compact list; `Orchestrator.planStep()` sends system prompt + task + dump + last result to the LLM → returns one `ActionV1` JSON.
4. `SafetyGate.validate(action)`: schema check, tool allowlist, forbidden-domain screen check, target verification. Produces `Approved | NeedsConfirmation | Rejected | AskUser`.
5. Approved → `Executor` performs it → `ScreenInspector.dump()` again → verification result appended → next loop iteration (max 5).
6. NeedsConfirmation → Speaker reads recipient+text → router listens for yes/no → on yes, **code** (not model) runs `confirmSend()`.
7. `finish` or cap → Speaker speaks outcome. Diagnostics records latency.

## 3. Modules — Responsibilities and Interfaces

| Module | Responsibility | Key interface (Kotlin) | Reuse source (see 06-TRD §0.5) |
|---|---|---|---|
| `VoiceInput` | PTT capture, whisper.cpp inference, discard audio | `suspend fun transcribe(pcm: ShortArray): String` | New — repo's `speech_to_text` plugin is replaced |
| `Router` | Deterministic control phrases | `fun route(t: String): RouteResult` (Control/Task/Answer) | Inspired by repo's `_getNavigationShortcut`, rewritten |
| `Orchestrator` | LLM session, prompt assembly, JSON parse, 1 parse-retry | `suspend fun planStep(ctx: PlanContext): ActionV1` | Rewritten — repo's `AiService` is cloud HTTP |
| `SafetyGate` | Schema validation, allowlist, blocklist, confirm FSM, target verification | `fun validate(a: ActionV1, s: ScreenModel): Verdict` | New — no equivalent in repo |
| `AgentLoop` | Step loop ≤5, retry rules, cancel token, state machine | `suspend fun run(task: String, scope: CoroutineScope)` | Pattern ported from repo's `TaskExecutor` (Kotlin rewrite) |
| `ScreenInspector` | UI-tree traversal → `ScreenModel`; compactor | `fun dump(): ScreenModel` | Ported from repo's `AgentAccessibilityService.dumpScreen` + `getCompressedScreenDescription` |
| `Executor` | click/scroll/back/open/draft_reply; `confirmSend()` is code-only | `suspend fun perform(a: ActionV1): ExecResult` | Ported from repo's accessibility actions (`clickByText`, `dispatchGesture`, `ACTION_SET_TEXT`) |
| `Speaker` | Offline TTS queue, barge-in stop | `fun speak(tpl: Tpl, vararg args)`, `fun stop()` | Rebuilt on `TextToSpeech` directly (repo used `flutter_tts`) |
| `MemoryStore` | Aliases, prefs, rolling context | `fun resolveAlias(name): List<Contact>` etc. | New (repo's skill-memory is out of scope) |
| `Diagnostics` | Model name, last latency, net status, pipeline state | `object DiagnosticsBus : StateFlow` | New |

## 4. Threading and Responsiveness

- All agent work on `Dispatchers.Default`; `Executor` calls into the AccessibilityService on `Dispatchers.Main` only where required (gestures dispatch async via `dispatchGesture` callback).
- LLM and whisper run in a single dedicated `Dispatcher` limited to 1 worker (they're heavyweight; serialize them) — backed by a `Process`-safe JNI wrapper.
- **Cancel:** `kotlinx.coroutines.Job` per task; "stop" calls `job.cancel()` — checked between steps and inside LLM via periodic `ensureActive()`/abort hooks. Whisper/llama.cpp inference supports early abort via callback returning false.
- TTS is interruptible (`speaker.stop()` on cancel). Screen dump is the only blocking call and times out at 3 s.
- UI is never blocked: PTT button stays responsive during inference; a visible + spoken "Stop" affordance is always present.

## 5. Agent Loop State Machine

```mermaid
stateDiagram-v2
    [*] --> IDLE
    IDLE --> LISTENING : PTT pressed
    LISTENING --> ROUTING : audio released
    LISTENING --> IDLE : silence/empty transcript
    ROUTING --> CONTROL : control utterance
    ROUTING --> PLANNING : task utterance
    CONTROL --> IDLE : executed (stop/confirm/back/repeat)
    PLANNING --> VALIDATING : model returned JSON
    PLANNING --> PLANNING : parse retry (max 1)
    VALIDATING --> EXECUTING : approved
    VALIDATING --> CONFIRMING : needs confirmation
    VALIDATING --> ASKING : ambiguity
    VALIDATING --> FAILED : rejected/blocked
    EXECUTING --> VERIFYING : action done
    VERIFYING --> PLANNING : continue, step<5
    VERIFYING --> DONE : finish or verified goal
    VERIFYING --> PLANNING : retry once on fail
    CONFIRMING --> SENDING : user said yes
    CONFIRMING --> IDLE : no / timeout (draft cleared)
    SENDING --> DONE : verified sent
    ASKING --> PLANNING : resolved
    ASKING --> FAILED : unresolved x2
    FAILED --> IDLE : speak honest failure
    DONE --> IDLE : speak result
    IDLE --> CANCELLED : "stop" heard
    LISTENING --> CANCELLED
    PLANNING --> CANCELLED
    VALIDATING --> CANCELLED
    EXECUTING --> CANCELLED
    CONFIRMING --> CANCELLED
    VERIFYING --> CANCELLED
    CANCELLED --> IDLE : cleanup + "Stopped."
```

## 6. Confirmation Gate State Machine (FR-07)

```mermaid
stateDiagram-v2
    [*] --> NONE
    NONE --> PENDING : draft_reply verified
    PENDING --> CONFIRMED : router hears yes/oo/sige
    PENDING --> DENIED : no/hindi
    PENDING --> EXPIRED : 30 s silence
    PENDING --> DENIED : "stop" heard
    CONFIRMED --> SEND_EXEC : code path confirmSend()
    SEND_EXEC --> SENT : screen verify passes
    SEND_EXEC --> FAIL : verify fails
    DENIED --> NONE : clear draft
    EXPIRED --> NONE : clear draft
    SENT --> NONE
    FAIL --> NONE
```

Rules: (a) `SEND_EXEC` is reachable **only** from `CONFIRMED`; the model's tool set contains no send primitive. (b) Draft field is cleared on any non-SENT exit. (c) Pending state expires in 30 s.

## 7. Memory Schema (SQLite via Room — `alalay.db`)

```sql
CREATE TABLE aliases (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  alias TEXT NOT NULL,          -- "Mama" — what the user says
  target_name TEXT NOT NULL,    -- "Remedios Domingo" — canonical contact/thread name
  target_kind TEXT NOT NULL,    -- 'contact' | 'thread' | 'app'
  created_at INTEGER NOT NULL
);

CREATE TABLE prefs (
  key TEXT PRIMARY KEY,         -- 'speak_language' | 'speech_rate'
  value TEXT NOT NULL
);

CREATE TABLE rolling_context (
  key TEXT PRIMARY KEY,         -- 'last_contact' | 'last_task_kind'
  value TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);
```

Hard rules: no message bodies ever stored; writes require explicit spoken confirmation (teach flow in 03 §1.7 and post-task "want me to remember?"); model has **no memory-write tool** (07-Agent-Specs §9).

## 8. Reference Repo Fit (summary — full analysis in 06-TRD §0)

| Repo module | Alalay destination | Verdict |
|---|---|---|
| `AgentAccessibilityService.kt` | `Executor` + `ScreenInspector` | **Port** (Kotlin→Kotlin, adapt; license VERIFY) |
| `TaskExecutor` loop + guards | `AgentLoop` | **Port pattern**, rewrite in Kotlin |
| `getCompressedScreenDescription` | `ScreenInspector` compactor | **Port pattern** |
| `AiService` (cloud) | `Orchestrator` | **Rewrite** — on-device inference |
| `VoiceService` | `VoiceInput` | **Rewrite** — whisper.cpp |
| Telegram/Shizuku/SMS/call/system-control services | — | **Drop** (network/scope/safety) |
| Skill memory | — | Drop (stretch idea) |
