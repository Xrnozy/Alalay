# Alalay — Spec Package (00 — Index)

**Alalay** is an offline-first, voice-controlled accessibility agent for Android, built for AppBuilders PH Hackathon 2026 ("Local AI"). A blind or low-vision user speaks in Taglish or English ("Basahin mo yung huling message ni Mama"); an on-device small language model plans one step at a time; an Android `AccessibilityService` executes it; results are spoken back — with a hard, code-enforced confirmation before anything is sent. Everything runs on one phone, in airplane mode, with zero data leaving the device. Core promise: *understands what you want, works with no internet, never acts without your say-so.*

## Files (read in order)

| File | Contents |
|---|---|
| `00-README.md` | This index, ID conventions, Decisions to Confirm, First-2-Hours checklist |
| `01-PRD.md` | Vision, problem, persona, user stories US-xx, requirements FR-xx/NFR-xx, metrics, risks RISK-xx |
| `02-MVP-Scope.md` | In/stretch/out scope, supported commands CMD-01..05, definition of done, cut-line |
| `03-User-Flow.md` | Spoken-first flows + Mermaid diagrams incl. confirmation, ambiguity, cancel, failure, setup, demo |
| `04-Architecture.md` | Component diagram, module interfaces, threading, agent + confirmation state machines, memory schema |
| `05-Architecture-Essentials.md` | The 9 non-negotiable design decisions (D-1..D-9) |
| `06-TRD.md` | **Reference Repo Analysis (§0)**, stack/versions, dependencies + licenses, models, device reqs, permissions, build, test strategy |
| `07-Agent-Specs.md` | System prompt, tool schema, `alalay.action.v1` contract, validation, confirmation/ambiguity/recovery rules, TPL-xx templates, 20 examples |
| `08-Development-Plan.md` | 24-hour plan: phases P0–P4, tasks T-01..T-26, risk-first spikes, freeze at H19 |
| `09-Acceptance-Tests.md` | AT-01..AT-13 with preconditions/steps/pass criteria + traceability matrix |

## ID conventions

`FR-`/`NFR-`/`US-`/`RISK-` (01), `CMD-` (02), `D-` design decisions (05), `TPL-` spoken templates (07), `T-` tasks (08), `AT-` tests (09), `DEC-`/`ASM-` decisions & assumptions (below). Cross-doc links use these IDs; keep them stable — never renumber.

## Key terms (use consistently)

`VoiceInput` `Router` `Orchestrator` `SafetyGate` `AgentLoop` `ScreenInspector` `Executor` `Speaker` `MemoryStore` `DiagnosticsBus` — module names per 04-§3. Action contract: `alalay.action.v1`. Model tool set (exactly 8): `open_app, click, read_text, scroll, go_back, draft_reply, ask_user, finish`. Internal-only: `confirm_send`, `emergency_stop`, `memory_write`. Target app for demo: "Alalay Demo Messenger" (package `com.alalay.demomessenger`) unless DEC-02 resolves to real Messenger.

---

## Decisions to Confirm (consolidated — freeze in first 2 hours)

| ID | Decision | Default / assumption | Owner task |
|---|---|---|---|
| DEC-01 | Target device: model, RAM, Android version | ASSUMPTION: ≥8 GB RAM, Android 13+; pick the best phone present | T-05 after T-02 |
| DEC-02 | Demo messaging target: real Messenger vs. Alalay Demo Messenger | ASSUMPTION: demo messenger; decide by T-01 spike result | T-01, T-05 |
| DEC-03 | Final product name + package id | ASSUMPTION: "Alalay", `com.alalay.app`, service label "Alalay Screen Control" | T-05 |
| DEC-04 | Official command list = CMD-01..05 and Taglish phrasing | Per 02-§2 | T-05 |
| DEC-05 | Native Kotlin (not Flutter) confirmed | ASSUMPTION: yes, per 06-TRD §0.5 | T-05 |
| DEC-06 | Primary model identity | **RESOLVED:** MiniCPM5-1B (openbmb, Apache-2.0) via llama.cpp GGUF Q4_K_M. Eval 2026-10 vs Gemma/LiteRT-LM path — see 06-TRD §2. Fallback: Qwen3 1.7B → Gemma 3 1B | T-02 |
| DEC-07 | STT model identity | **RESOLVED:** whisper `small` multilingual via whisper.cpp (MIT). CrisperWhisper 2.0 Small rejected — EN/DE only (no Tagalog) + non-commercial-research weights license; see 06-TRD §2. SpeechRecognizer `preferOffline` remains the runtime fallback | T-03 |
| DEC-08 | Offline TTS voice present on target device | VERIFY in airplane mode | T-04 |
| DEC-09 | private-agent license — no LICENSE file exists | Treat as reference-only, attribute, rewrite patterns; ask author for terms | team lead |
| DEC-10 | PTT trigger mechanism: on-screen button vs. volume-key long-press | ASSUMPTION: on-screen hold button (simplest, eyes-free friendly) | T-07 |
| DEC-11 | Model delivery to the device | **RESOLVED:** weights bundled inside the app (`assets/models/`, noCompress) → extracted to `files/models/` + verified on first launch. No download step, no pre-event staging. Play-Store path: install-time asset pack (06-TRD §6) | pre-event |

## First 2 Hours Checklist (Phase P0 — risk-first, per 08-§2)

- [ ] **H0:00** Assign spikes; confirm device RAM (DEC-01) + bundled-model APK builds and installs (DEC-11)
- [ ] **H0:15** T-01: accessibility service dump on Messenger AND a stub list app — can we read elements and click?
- [ ] **H0:45** T-02: load MiniCPM5-1B on the target phone; run 20 prompts; record latency + % valid JSON
- [ ] **H1:15** T-03: whisper-small transcribes the 10 scripted utterances in airplane mode — note WER on CMD phrases
- [ ] **H1:30** T-04: TTS speaks with airplane mode ON
- [ ] **H1:45** T-05: freeze DEC-01..10; write them into this README; adjust scope per spike results (fallbacks per 06-TRD §2 / cut-line 02-§7)
- [ ] **H2:00** Go/no-go gate: if LLM spike failed → invoke T-23 rules-only planner; if Messenger failed → commit to demo messenger; then start P1

---
*Consistency rule: if any document changes an ID, scope item, tool name, or safety rule, the change must be mirrored in all documents referencing it before it's considered made.*
