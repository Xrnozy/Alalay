# 01 — Product Requirements Document (PRD)

| | |
|---|---|
| **Product** | Alalay (working name; formerly BlindPilot) |
| **Event** | AppBuilders PH Hackathon 2026 — theme: "Local AI" |
| **Status** | Draft v1.0 — for the coding agent and team lead |
| **Upstream docs** | None (this is document 1 of 9) |
| **Downstream docs** | 02-MVP-Scope, 03-User-Flow, 04-Architecture, 06-TRD, 07-Agent-Specs, 09-Acceptance-Tests |

---

## 1. Vision

An offline-first, voice-controlled accessibility agent for Android. The user speaks a request in Taglish or English — e.g. *"Open Messenger and read Mama's latest message"* — an on-device small language model turns it into a validated action, an Android `AccessibilityService` executes it step by step, and the result is spoken back. **No internet connection or cloud AI service is required at any point.**

Core promise: **Understands what you want. Works with no internet. Never acts without your say-so.**

## 2. Problem

- TalkBack works, but multi-step tasks (open app → find thread → read latest → reply) require many swipe gestures and deep familiarity. Blind and low-vision users describe this as slow and exhausting.
- Existing AI phone agents (including our reference repo, `private-agent`) send the screen contents to a cloud API. For Filipino users this means private chats, banking screens, and OTPs leave the device — unacceptable for the target population, and useless on poor connectivity.
- The Philippines has poor/expensive connectivity for a large share of users; an assistant that requires internet fails exactly when needed.

## 3. Target User and Persona

**Primary:** Blind and low-vision Android users in the Philippines.

**Persona — "Mang Domeng," 58, Quezon City:**
- Android phone (mid-range), TalkBack installed but uses it reluctantly.
- Primary apps: Messenger/Facebook, SMS, calls. Speaks Taglish.
- Wants: "Just read me the message and let me answer it with my voice."
- Fears: the app sending money, posting something, or "doing things I didn't ask."
- Connectivity: prepaid data, often offline.

**Secondary:** Sighted users on unreliable connections who want hands-free operation (driving-adjacent, cooking), and users who refuse cloud AI on privacy grounds.

## 4. User Stories

| ID | Story | Maps to |
|---|---|---|
| US-01 | As a blind user, I want to press a button and speak, so I don't have to navigate menus to start a command. | FR-01 |
| US-02 | As a user, I want the phone to understand "i-open mo ang Messenger" as well as "open Messenger," so I can speak naturally in Taglish. | FR-01, FR-02 |
| US-03 | As a user, I want the app to read the latest message from a contact aloud, so I can keep up with family without navigating. | FR-04, FR-06 |
| US-04 | As a user, I want to hear exactly who a reply goes to and what it says before it is sent, so nothing embarrassing or dangerous is ever sent. | FR-07 |
| US-05 | As a user, if I say "Mama" and there are two Mamas, I want to be asked which one, not have the app guess. | FR-08 |
| US-06 | As a user, I want to say "stop" at any moment and have the app stop immediately. | FR-09 |
| US-07 | As a user, I want to teach the app that "Mama" means "Remedios Domingo" once, and have it remember — but never have it remember my message contents. | FR-10 |
| US-08 | As a user, when the app can't do something, I want it to say so honestly instead of pretending. | FR-13 |
| US-09 | As a demo judge, I want a diagnostics screen proving the model and inference are local, so I can verify the Local AI claim. | FR-11 |

## 5. Functional Requirements

Priorities: **P0** = must ship in MVP (demo fails without it), **P1** = ship if phase-4 time allows, **P2** = stretch only.

| ID | Requirement | Priority | Verified by |
|---|---|---|---|
| FR-01 | Push-to-talk voice input: user presses and holds (on-screen button or volume-key long-press, VERIFY feasibility), speaks ≤10 s, releases; on-device STT transcribes. Works in airplane mode. | P0 | AT-02, AT-10 |
| FR-02 | Local LLM converts the utterance into one validated structured action per step (see 07-Agent-Specs for contract `alalay.action.v1`). Loop cap: **5 steps max** per command, then finish or honest failure. | P0 | AT-01, AT-11 |
| FR-03 | A deterministic code router intercepts control utterances **before** the LLM: "stop", "cancel", "confirm"/"yes", "go back", "open [app]". Router output never passes through the model. | P0 | AT-05, AT-06 |
| FR-04 | ScreenInspector reads the foreground app's accessibility UI tree and compacts it into a bounded text dump (element index, type, label, flags, center coords; ≤60 elements, ≤50 chars per label). | P0 | AT-03 |
| FR-05 | Executor performs the primitives behind the model tool set: open_app, click (by text/desc or coordinates), scroll up/down, go_back, and draft_reply (type into the compose field — never sends). Implemented via `AccessibilityService` (`ACTION_CLICK`, `dispatchGesture`, `ACTION_SET_TEXT`, `ACTION_SCROLL_*`, `GLOBAL_ACTION_*`). | P0 | AT-02, AT-03 |
| FR-06 | All feedback is spoken via Android `TextToSpeech` using an installed offline voice. Every state change is announced (listening, thinking, acting, done, failed). | P0 | AT-10, AT-12 |
| FR-07 | **Confirmation gate:** any action that sends content to another person requires a spoken confirmation of the exact recipient + exact text. Only code (never the model) may authorize sending. Timeout after 30 s = cancel. | P0 | AT-05 |
| FR-08 | **Ambiguity handling:** when a target resolves to ≥2 candidates (e.g. two "Mama" contacts/threads), the agent asks a numbered spoken question and waits for "the first one" / "ang una" / a name. | P0 | AT-04 |
| FR-09 | **Stop/cancel:** the word "stop"/"cancel" or a hardware-agnostic cancel control halts the loop within 1 s from any state, and speaks "Stopped." | P0 | AT-06 |
| FR-10 | **Memory:** store user-approved aliases and preferences only (name map, preferred language, last contact used). Never message contents. Model may never write memory; only code writes, and only after user confirms a teach flow. | P1 | AT-09 |
| FR-11 | **Diagnostics screen:** shows model identity + size, last inference latency, memory footprint, network-status indicator proving airplane mode, and pipeline status — no private content. | P0 | AT-10 |
| FR-12 | **Safety layer (SafetyGate):** deterministic allowlist of the 8 model tools; blocklist of forbidden domains — payments/transfers, authentication, passwords/OTPs/PINs, security or device-admin settings, account deletion, public posting. Blocked requests get a spoken refusal explaining the limit. | P0 | AT-11 |
| FR-13 | **Honest failure:** the agent never claims success without a verification step (re-dump screen and confirm expected state). On failure it says what it could not do, in plain Taglish. | P0 | AT-07 |
| FR-14 | **First-time setup flow:** guides the user through enabling the accessibility service (including Android's "restricted settings" path for side-loaded APKs), RECORD_AUDIO permission, model presence check, offline TTS voice check, and optional alias teaching. | P1 | AT-10 (partial), manual |

## 6. Non-Functional Requirements

| ID | Requirement | Measurable criterion | Verified by |
|---|---|---|---|
| NFR-01 | **Fully offline.** No packet leaves the device during normal operation. | Demo passes in airplane mode; network monitor shows zero egress during AT-10. `INTERNET` permission absent or provably unused in release build. | AT-08, AT-10 |
| NFR-02 | **Latency targets** (to be measured on target device; values are ceilings for "acceptable demo"): | Router decision ≤300 ms; STT turnaround ≤4 s for a ≤10 s utterance; LLM step decision ≤8 s; TTS first-audio ≤1 s; "open app" end-to-end ≤10 s. | AT-01, AT-02 |
| NFR-03 | **Privacy.** No audio, screen content, prompts, or contacts leave the device. Logs contain no message bodies, no contact names in cleartext payloads (log element counts and action types, not values). | Logcat audit during AT-10 finds zero message text. | AT-08 |
| NFR-04 | **Reliability** on supported commands only. | ≥80% success across the AT matrix runs on CMD-01..05 on the target device; **zero unconfirmed sends, ever** (0% tolerance). | AT-05, full suite |
| NFR-05 | **Eyes-free operability.** | Every state change in 03-User-Flow has a spoken equivalent; no step requires reading the screen. | Manual checklist in AT-10 |
| NFR-06 | **Resource budget.** | App + all models ≤4 GB installed; runs within target device RAM (8 GB recommended, see DEC-01). | Build/install check |
| NFR-07 | **Interruptibility.** | Cancel takes effect ≤1 s from any state including mid-inference and mid-TTS. | AT-06 |

## 7. Success Metrics (tied to judging criteria)

| Judging criterion | Weight | How Alalay scores | Measurable proxy |
|---|---|---|---|
| Problem & Usefulness | 25% | Real population, real pain (TalkBack fatigue + privacy + connectivity). | Judges agree persona is credible; live demo completes 2 of 3 scripted tasks. |
| Local AI Implementation | 25% | LLM + STT run on-device; diagnostics screen shows model name + latency + airplane-mode proof. | AT-10 passes; diagnostics shows ≥1 real inference timing. |
| Technical Execution | 20% | One-step-at-a-time planner, validator/safety gate, accessibility executor, verification loop. | AT suite ≥80% pass; zero safety violations. |
| Innovation | 15% | Code-authorizes-not-model safety; honest failure; alias memory without content logging. | Confirmation gate demonstrated live; blocked-request refusal demonstrated. |
| Product & Demo | 15% | 3-minute scripted demo + backup video; spoken UX coherent. | Demo script in 03-User-Flow §10 runs ≤3 min. |

## 8. Assumptions (ASSUMPTION — see Decisions to Confirm in 00-README)

- **ASM-01** Target device: mid-to-high-range Android phone, ≥8 GB RAM, Android 13+ (API 33). Final device/RAM/Android version is DEC-01.
- **ASM-02** Primary model: **Gemma 4 E2B via LiteRT-LM** per project direction — **VERIFY** model name/availability at event time; fallback chain defined in 06-TRD §4.
- **ASM-03** whisper.cpp `tiny` or `base` with Filipino/Tagalog accuracy adequate for short commands — **VERIFY**; fallback is Android `SpeechRecognizer` with an on-device recognition pack.
- **ASM-04** Messaging target for the demo is a **purpose-built demo messaging app** ("Alalay Demo Messenger") unless real Messenger proves accessible in hour 0–2 testing (DEC-02).
- **ASM-05** App id `com.alalay.app`, service label "Alalay Screen Control". Final name is DEC-03.

## 9. Risks (summary — full register in 06-TRD §10 and 08-Development-Plan)

| ID | Risk | Impact | Mitigation |
|---|---|---|---|
| RISK-01 | On-device LLM too slow/too big for target phone | Whole concept fails | Hour 0–2 model smoke test; fallback model list; cut to rule-based planner for 5 commands |
| RISK-02 | Messenger UI not reliably accessible | Demo feature lost | Purpose-built demo messaging app as planned fallback |
| RISK-03 | STT accuracy poor on Taglish in airplane mode | Commands misheard | Constrained grammar via router; repeat-back confirmation; fallback recognizer |
| RISK-04 | Reference repo license unclear (no LICENSE file) | Can't copy code | Treat as reference; rewrite; attribute; VERIFY (06-TRD §0.7) |
| RISK-05 | Accessibility service blocked by "restricted settings" on side-loaded APK | Can't control phone | Documented onboarding path (repo README already solves this) |
