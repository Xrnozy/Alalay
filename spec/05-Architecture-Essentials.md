# 05 — Architecture Essentials (One Page)

The non-negotiable design decisions. If a schedule trade-off threatens one of these, the answer is no. Full rationale in 04-Architecture; enforcement in 07-Agent-Specs and the SafetyGate code.

---

## D-1. The model proposes; code disposes.
The LLM can only emit a `ActionV1` JSON naming one of 8 tools. `SafetyGate` — plain, testable Kotlin — decides whether it runs. No model output is ever executed directly, including during retries.
*Why:* a small local model will occasionally hallucinate actions; safety must not depend on model behavior.

## D-2. One step at a time, max 5.
The model plans exactly one action per call; the loop re-dumps the screen after every action and re-plans. Cap: **5 steps**, then `finish` or honest failure.
*Why:* small models lose coherence on multi-step plans; fresh screen state after each action makes the task reactive instead of speculative. 5 steps covers all five supported commands with margin.

## D-3. Deterministic router before the model.
"Stop", "yes", "go back", "open X", ordinal answers — matched by code in <300 ms, never sent to the LLM.
*Why:* safety-critical controls (especially stop/confirm) must not wait on or be reinterpreted by a stochastic model. Also makes the demo feel instant on common commands.

## D-4. No send primitive exists in the tool set.
`draft_reply` types into the compose field. Sending is a **code path** (`Executor.confirmSend()`) reachable only from the confirmation state machine after a spoken yes. The model literally cannot emit a "send" action.
*Why:* "never acts without your say-so" must be structurally guaranteed, not prompt-engineered.

## D-5. Verify on screen; never claim success blindly.
Every action is followed by a fresh dump and a check (app foregrounded? field contains draft? send button consumed?). Failure → one hinted retry → honest spoken failure.
*Why:* the product promise is honesty; "Done" means "verified on screen," not "the tool returned true."

## D-6. Everything offline, verifiably.
STT (whisper.cpp small multilingual), planner (MiniCPM5-1B via llama.cpp / LiteRT-LM alternate), TTS (system offline voice) all on-device with weights bundled in the APK; the demo runs in airplane mode and the diagnostics screen proves it.
*Why:* it is 25% of the judging weight **and** the privacy promise — both collapse if a packet leaves.

## D-7. Privacy by construction, not policy.
Audio buffers discarded after transcription; screen dumps live in memory only; logs record element counts and action types — never message text or contact names; memory stores aliases and preferences only, written only by code after user confirmation.
*Why:* our target users' screens contain chats, banking, OTPs — logging them would betray the premise.

## D-8. Honest failure over guessing.
Ambiguity → numbered question. Two failures → stop and say exactly what failed. Blocked domains → spoken refusal naming the limit.
*Why:* trust is the product. A blind user cannot see a wrong guess — so we never guess.

## D-9. Port, don't fork, the reference repo.
Reuse PrivateAgent's Kotlin `AccessibilityService` and its loop/compression **patterns**; rewrite the Dart layer and cloud brain in Kotlin. Respect the missing license — attribute, don't copy wholesale (06-TRD §0.7).
*Why:* saves 4–6 hours of proven accessibility code; avoids shipping a Flutter engine we don't need.

---
*Related: the agent loop and confirmation FSMs are diagrammed in 04-Architecture §5–6; enforcement rules in 07-Agent-Specs §5–8.*
