# 08 — Development Plan (24 Hours)

| | |
|---|---|
| **Upstream** | 01–07 (all scope, architecture, agent contracts) |
| **Downstream** | 09-Acceptance-Tests (exit criteria per phase) |

Assumes event start = **H0** (clock time TBD), one Android device available at H0, models pre-downloaded to a laptop/phone storage **before** H0 (no venue Wi-Fi dependency). Team: 1–3 people; tasks are sized for parallel work where marked.

## 1. Phase Map

| Phase | Hours | Goal | Exit criteria |
|---|---|---|---|
| **P0 — Define & risk-test** | H0–H2 | Kill the three biggest unknowns before building | T-01..T-04 done; DEC-01..04 frozen; go/no-go per spike |
| **P1 — Foundation** | H2–H8 | Skeleton app: PTT→STT→(echo)→TTS loop + accessibility service + demo messenger | T-05..T-10 done; CMD-01 works end-to-end without LLM |
| **P2 — Agent integration** | H8–H14 | LLM planner + SafetyGate + Executor loop live | CMD-02, CMD-04, CMD-05 pass on device |
| **P3 — Reliability** | H14–H19 | Confirmation, ambiguity, cancel, memory, diagnostics, failure polish | All P0 ATs green; CMD-03 incl. confirmation works |
| **P4 — Ship / freeze** | H19–H24 | Demo rehearsal, backup video, docs, buffer | Feature freeze at **H19**; demo rehearsed ×3; video recorded by H22 |

**Feature freeze: H19.** After H19 only bug fixes for the AT matrix and demo script — no new features, no stretch items.

## 2. Risk-First Order (explicit)

Do these **before** any other build task. If a spike fails, re-scope immediately rather than debugging deep.

| Order | Spike (task) | Question | If it fails |
|---|---|---|---|
| 1 | T-01 accessibility dump on target app (Messenger *and* demo messenger stub) | Can we read + click the target screens? | Default to demo messenger (DEC-02 → fallback) |
| 2 | T-02 LLM on-device smoke: load model, 20 test prompts, measure latency + JSON validity | Does a ≤8 s, schema-valid plan step exist on this phone? | Drop down fallback chain (06-TRD §2); worst case T-23 rules-only planner |
| 3 | T-03 whisper STT on Taglish commands in airplane mode | Are CMD phrases recognized ≥80%? | constrain vocabulary; SpeechRecognizer offline fallback; type-to-command backup for demo |
| 4 | T-04 offline TTS voice check in airplane mode | Does a voice speak offline? | pre-install voice data during setup; else on-screen captions (demo-only degrade) |

## 3. Task Breakdown

Estimates in hours. `||` = parallelizable. Deps = must finish first.

| ID | Task | Est | Phase | Deps |
|---|---|---|---|---|
| T-01 | Spike: dump UI tree of Messenger + click a thread; same on a throwaway list app | 1.0 | P0 | device in hand |
| T-02 | Spike: LiteRT-LM (or llama.cpp) hello-world inference on target phone; 20-prompt JSON validity + latency bench | 1.0 || T-01 | P0 | model file present |
| T-03 | Spike: whisper.cpp tiny/base transcribe 10 recorded Taglish utterances, airplane mode | 0.75 || | P0 | model file present |
| T-04 | Spike: TTS offline voice check; airplane-mode TTS speak | 0.25 || | P0 | — |
| T-05 | Freeze decisions DEC-01..04; record in 00-README | 0.5 | P0 | T-01..04 |
| T-06 | Alalay Demo Messenger: thread list → thread view → compose field (seeded: 2× "Mama"-matchable contacts for ambiguity demo, Rosa×2) | 3.0 || from H2 | P1 | — |
| T-07 | App skeleton: single Activity, PTT button, service wiring, package `com.alalay.app` | 1.5 | P1 | — |
| T-08 | Port/adapt accessibility service (ScreenInspector dump + Executor primitives) from reference repo — clean-room rewrite per 06-TRD §0.7; own-package filtering; 300 ms notification timeout | 3.0 | P1 | T-07, T-01 |
| T-09 | VoiceInput: whisper.cpp JNI + PTT capture + discard-after-transcribe | 2.0 || | P1 | T-03, T-07 |
| T-10 | Speaker: TextToSpeech wrapper, barge-in stop, TPL template renderer | 1.0 || | P1 | T-04, T-07 |
| T-11 | Router: control phrase table + open-app matcher + ordinal parser (EN/Taglish) | 1.5 || | P1 | — |
| T-12 | Orchestrator: LLM session, prompt assembly per 07-§4, constrained JSON or parse+retry | 2.5 | P2 | T-02, T-08 |
| T-13 | ActionV1 contract + kotlinx.serialization schema validator | 1.0 || | P2 | — |
| T-14 | SafetyGate: allowlist, domain blocklist, target verification, verdicts | 2.0 | P2 | T-13 |
| T-15 | AgentLoop: state machine per 04-§5, 5-step cap, retry/hint rules, cancel job | 2.0 | P2 | T-12, T-14 |
| T-16 | Confirmation gate FSM + Executor.confirmSend code path + draft clear | 1.5 | P3 | T-15 |
| T-17 | Ambiguity handling (ask_user flow + ordinal routing) | 1.0 || | P3 | T-11, T-15 |
| T-18 | Cancel plumbing: coroutine Job cancel ≤1 s, whisper/llama abort hooks, TTS stop | 1.0 || | P3 | T-15 |
| T-19 | MemoryStore (Room): aliases/prefs/rolling_context + alias teach flow w/ spoken confirm | 1.5 || | P3 | T-07 |
| T-20 | Diagnostics screen: model id, last inference ms, network badge, pipeline state | 1.0 || | P3 | T-12 |
| T-21 | Setup/onboarding flow FR-14 incl. restricted-settings guidance | 1.5 || | P3 | T-07 |
| T-22 | Adversarial + unit tests: validator, gate, router, FSMs, injection fixtures | 1.5 || | P3 | T-13, T-14 |
| T-23 | Contingency (only if T-02 fails): rules-only planner covering CMD-01..05 | 2.0 | P2 | decision at T-02 |
| T-24 | AT matrix runs on device; fix-forward | 2.0 | P3→P4 | all P0 tasks |
| T-25 | Demo rehearsal ×3, backup video in airplane mode | 1.5 | P4 | T-24 |
| T-26 | Slide/pitch polish + README | 1.0 || | P4 | — |

Sum ≈ 33 h of tasks — parallelize `||` items across teammates; a solo dev should pre-cut FR-10/FR-14 polish per 02-§7 cut-line.

## 4. Dependency Graph (critical path)

```
T-01 ─┐
      ├─> T-08 ─> T-12 ─> T-15 ─> T-16 ─> T-24 ─> T-25
T-02 ─┘        (T-13,14)        (T-17,18,19,20)
T-07 ── T-09 ── (VoiceInput feeds loop from P2)
T-06 (demo messenger) ── needed by T-24
```

Critical path ≈ T-01→T-08→T-12→T-15→T-16→T-24→T-25 ≈ 11.5 h — fits inside H0–H22 with buffer if P0 spikes stay green.

## 5. Checkpoints

| Hour | Check | Action if red |
|---|---|---|
| H2 | Spikes green? | Trigger fallbacks (T-23, demo messenger, captions) |
| H8 | CMD-01 + echo loop works? | Re-plan P2 scope; possibly drop CMD-04/05 |
| H14 | CMD-02 green on device? | Enter reliability early; cut memory to config-file aliases |
| H19 | **FREEZE** — all P0 ATs green? | Any red P0 → fix only that; demo script re-scoped to what's green |
| H22 | Demo rehearsed + video done | Remaining time = sleep + buffer |
