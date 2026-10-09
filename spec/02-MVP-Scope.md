# 02 — MVP Scope

| | |
|---|---|
| **Upstream** | 01-PRD (FR/NFR IDs used here) |
| **Downstream** | 03-User-Flow, 07-Agent-Specs, 08-Development-Plan, 09-Acceptance-Tests |

This document freezes exactly what gets built. Anything not listed as in-scope is out — put new ideas in §5 and nowhere else.

---

## 1. In-Scope (MVP)

All items are committed to the MVP. Priority P0 unless marked P1 — P1 items are cut candidates per §7 but are built if the schedule holds.

| # | Feature | Requirement IDs |
|---|---|---|
| 1 | Push-to-talk voice input, on-device STT, ≤10 s utterances | FR-01 |
| 2 | Deterministic router for control utterances (stop, cancel, confirm, go back, open app) | FR-03 |
| 3 | On-device LLM planner, **one action per call**, 5-step cap, strict JSON contract `alalay.action.v1` | FR-02 |
| 4 | ScreenInspector: accessibility tree dump → compact element list (≤60 elements) | FR-04 |
| 5 | Executor: open_app, click, scroll, go_back, draft text into field | FR-05 |
| 6 | SafetyGate: tool allowlist, forbidden-domain blocklist, target verification, confirmation state machine | FR-12, FR-07 |
| 7 | Spoken confirmation before any send (exact recipient + exact text) | FR-07 |
| 8 | Ambiguity resolution via numbered spoken question | FR-08 |
| 9 | Stop/cancel ≤1 s from any state | FR-09 |
| 10 | Offline TTS for all feedback | FR-06 |
| 11 | Diagnostics screen: model identity, last inference ms, network status, pipeline state | FR-11 |
| 12 | Memory: alias table + preferences + last-contact context (user-approved writes only) — **P1** | FR-10 |
| 13 | First-time setup flow (accessibility enable incl. restricted-settings path, mic permission, model + TTS presence check) — **P1** | FR-14 |
| 14 | Model weights bundled inside the app — MiniCPM5-1B planner (GGUF) + whisper-small STT; works offline immediately after install, no download step | NFR-01, DEC-06/07/11 |

## 2. Officially Supported Commands

These are the **only** five commands we commit to. All tests and the demo use only these. Others may work by accident; we do not claim them.

| ID | Command | Example utterances | Tools exercised | Confirmation? |
|---|---|---|---|---|
| CMD-01 | Open an app | "Open Messenger" / "Buksan mo ang Messenger" | router `open_app` or `open_app` tool | No |
| CMD-02 | Read latest message from a contact | "Read Mama's latest message" / "Basahin mo yung huling message ni Mama" | open_app → click → read_text | No |
| CMD-03 | Send a message to a contact | "Reply to Mama: I'm on my way" / "Sabihin kay Mama, papunta na ako" | open_app → click → draft_reply → **confirm** → send (code path) | **Yes — mandatory** |
| CMD-04 | Describe the current screen | "What's on my screen?" / "Anong nasa screen?" | read_text | No |
| CMD-05 | Scroll to read more | "Scroll down" / "Baba pa" | scroll | No |

**Control utterances** (handled by the router in code, never the model — FR-03):
`stop`, `cancel`, `confirm` / `yes` / `oo` / `sige`, `no` / `hindi` / `cancel`, `go back`, `repeat` (re-speak last message), `the first one` / `ang una` / numbered answers to ambiguity questions.

## 3. Stretch (only after all MVP acceptance tests pass — see 08-Development-Plan §phase order)

| # | Stretch item | Rule |
|---|---|---|
| S-01 | Recovery from wrong starting screen (model presses home/back to re-orient) | Only after AT-01..AT-10 green |
| S-02 | Undo of navigation actions (press back along recorded path) | Requires step journal; never applies to sends |
| S-03 | Real Messenger support | Only if hour-0 spike (T-01) shows reliable accessibility dump |
| S-04 | Vision fallback for unlabeled buttons (screenshot → on-device vision model) | Probably out of time budget; document, don't build |
| S-05 | Tagalog-language offline TTS voice | Only if a fil-PH voice is confirmed installed (VERIFY) |

## 4. Out-of-Scope (hard list — do not build)

- Controlling arbitrary apps beyond the demo set; "it happens to work" is fine, but no code or claims
- Payments, banking actions, purchases, money transfers (blocked — FR-12)
- Authentication, OTP/password/PIN entry or display (blocked — FR-12)
- Security settings, device admin, accessibility settings of other apps (blocked — FR-12)
- Undoing or deleting sent messages
- Account creation/deletion, public posting to feeds
- Model training, fine-tuning, or quantization work at the event (use pre-built quantized models)
- Any cloud or networked feature: no remote control, no Telegram, no analytics, no crash reporting
- Phone calls, SMS via system APIs (sending happens only inside the demo messaging flow, and only via accessibility taps after confirmation)
- Multi-user support, sync, backup

## 5. Stretch / Future Parking Lot (post-hackathon ideas — do NOT implement)

- Continuous "OK Alalay" wake word (push-to-talk is the MVP trigger)
- Task macros / learned skills (the reference repo's skill-memory idea)
- Richer memory (routines, favorite contacts' apps)
- Bisaya/Cebuano support
- Screenshot+vision planning when UI tree is insufficient
- Play Store release

## 6. Definition of Done (MVP)

An item is done only when **all** boxes are ticked:

- [ ] All five CMD-01..05 pass their acceptance tests on the target device in airplane mode (AT-01..AT-10 per 09-Acceptance-Tests)
- [ ] Zero unconfirmed sends in any test run (NFR-04)
- [ ] Blocked-domain refusal works for payments/auth/security/OTP prompts (AT-11)
- [ ] Stop works ≤1 s mid-inference, mid-action, mid-speech (AT-06)
- [ ] Failure path speaks an honest "I couldn't do X because Y" (AT-07)
- [ ] Diagnostics screen shows model name, last inference latency, airplane-mode indicator (FR-11)
- [ ] No message content or contact text in logs (AT-08 logcat audit)
- [ ] Setup flow gets a fresh install to a working state without a developer (FR-14)
- [ ] 3-minute demo script runs end-to-end; backup video recorded (03-User-Flow §10)
- [ ] App id, service label, and package name match DEC-03 freeze

## 7. Cut-Line (what gets dropped first, in order, if time runs short)

Cut from the bottom up. Never cut a P0 safety item (FR-07, FR-09, FR-12, FR-13).

| Order | Item | Reason it's safest to cut |
|---|---|---|
| 1 | S-03/S-04/S-05 stretch | Already gated behind MVP completion |
| 2 | FR-10 memory (alias teaching UI) | Aliases can be seeded via a fixed config file for the demo |
| 3 | CMD-05 scroll | Least demo-critical; scrolling still works inside CMD-02 flow |
| 4 | CMD-04 read-screen | Subsumed by CMD-02's read |
| 5 | FR-14 polished onboarding | Fallback: written setup steps on screen |
| **Never** | Confirmation gate, stop, safety gate, honest failure, diagnostics, CMD-01..03 | Core promise and judging criteria |
