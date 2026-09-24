# BitMessage Phase 5 handoff

Project: BitMessage, a Kotlin Multiplatform Android/iOS messenger foundation at `/Users/yet/development/Multiplatform/BitMessage`.

Status (2026-09-24): Phase 5 deterministic multi-node mesh simulation is implemented and verified on branch `codex/phase-5-deterministic-mesh-simulator`, based on Phase 4 commit `43b10ac7cb351f65905bf99586d9b96ffadb1a2d`. Phase 6 has not started. This handoff is operational context for a new agent/orchestrator, not authority to begin Phase 6 without a new request.

## What was done

- Added `:transport:simulation`, depending inward on foundation/model, the production BitChat codec, transport API, and real Phase 4 mesh engine/runtime. Application modules do not depend on simulation; `:core:testing` is test-only.
- Refined the Phase 4 runtime with correlated submit acknowledgements, causal effect fencing, injectable timers, and bounded lifecycle/settlement behavior. An acknowledgement ends when a reducer transition commits and its effects are registered/submitted; asynchronous completions remain explicit later events.
- Built a single bounded scheduled virtual-event queue ordered by `(deadline, insertionSequence)`. Per-node mesh actor/effect mailboxes remain separate and bounded. `runCurrentUntilQuiescent` does not advance future expiry timers; `advanceTo`/`advanceBy` move time deliberately; bounded `runUntil` stops at a scenario predicate and drains that instant.
- Implemented directed links, MTU/readiness/latency, explicit loss/duplication/delay/corruption/completion faults, partitions/reconnect, epoch invalidation, bounded trace/delivery/publication histories, and redacted projections.
- Each simulated node uses real codec adaptation, `MeshEngine`, `MeshRuntime`, and production relay/fragment codecs. Normal packet identity uses test-only SHA-256 of canonical Phase 4 input, while explicit `DigestPlan` overrides cover exceptional cases. Simulation-plan RNG is independent of per-node protocol entropy.
- Added direct and canonical three-node relay, duplicate/pre-jitter relay cancellation, TTL, partition, reconnect/stale generation, backpressure, invalid-auth poisoning, fragment reorder/conflict/quota, relay-loop convergence, exact replay, and 64 fixed-seed adversarial scenarios. Scenario actions and faults are compiled before execution and actions use the global queue. No shrinker or second compatibility-literal corpus was added.
- Preserved the Phase 4 distinction: concrete `7 -> 6` is canonical dual-upstream evidence; a general max TTL of 7 and hostile `255 -> 6` are local resource policy. `compatibility/` is unchanged from the Phase 4 base.

## Verification baseline

The complete Phase 1–5 regression gate passed with `--rerun-tasks` on Android host and iOS Simulator ARM64. Fresh XML counts: foundation 14/14, model 3/3, testing 32/26, protocol 42/36, transport API 5/5, mesh 101/101, simulation 76/72 (Android/iOS respectively); 530 total test executions. The Phase 1 `compatibilityCheck` executed 32 Android-host tests (six corpus tests); the Phase 1–4 production compatibility, Phase 4 mesh, and Phase 5 simulation gates each executed one coverage test. Two separate forced re-runs of the simulation `allTests` suite passed, and a further Android/iOS suite run passed after the pre-jitter cancellation test was added. Android debug assembly and iOS Simulator shared-framework link passed. Gradle emits existing deprecation/plugin-descriptor and unrelated application warning messages; none failed the gates.

Exact final gate command:

```bash
rtk ./gradlew :core:testing:compatibilityCheck :core:foundation:allTests :core:model:allTests :core:testing:allTests :protocol:bitchat:productionCompatibilityCheck :protocol:bitchat:allTests :transport:api:allTests :engine:mesh:meshEngineCheck :engine:mesh:allTests :transport:simulation:simulationCheck :transport:simulation:allTests --rerun-tasks --console=plain
```

Build command:

```bash
rtk ./gradlew :androidApp:assembleDebug :sharedLogic:linkDebugFrameworkIosSimulatorArm64 --rerun-tasks --console=plain
```

Compatibility integrity check:

```bash
rtk git diff --exit-code 43b10ac7cb351f65905bf99586d9b96ffadb1a2d -- compatibility
```

## Boundaries and next work

This phase is simulation/test infrastructure only. It does not provide Bluetooth transport, production crypto/Noise, persistence, Delivery/Sync/media engines, or a complete messenger. Phase 6 is the next roadmap phase but is unstarted and needs separate authorization. Before any later work, read `AGENTS.md`, the current architecture docs, the relevant repository skills, and the scoped phase request; inspect actual source rather than treating this handoff as a specification. Keep all commands prefixed with `rtk` under the repository agent instructions.
