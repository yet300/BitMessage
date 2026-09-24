# Phase 1–5 remediation

Baseline: `f51f50c07f336dab3c05e1b52fc6ad5e18782573`. The original findings remain in `PHASE_1_5_AUDIT.md`. This change does not begin Phase 6.

## F1 — pending link writes

- **Finding:** F1, a failed or missing write completion occupied a live link indefinitely.
- **Original failure mechanism:** `RelayEncoded` inserted a link ID in `pendingLinkWrites`; `EffectFailed` left it there, and no deadline existed for an absent callback. Later relay selection skipped that link.
- **Chosen design:** `PendingLinkWrite` owns link, timer ID and monotonic expiry under the write correlation. Relay encoding schedules one deadline before each write. A matching completion or executor failure removes the entry and cancels the timer; the matching timeout removes it. Link close cancels its write timers. Runtime stop clears state and its timer driver calls `cancelAll`. Unique issued correlations and generation checks reject stale results. A timeout releases local occupancy; it does not retry delivery.
- **Files changed:** `MeshState.kt`, `MeshLimits.kt`, `MeshEngine.kt`, `RelayPolicy.kt`, `AdmissionReducer.kt`, `PendingLinkWriteTest.kt`, and affected existing mesh tests.
- **New invariant:** At most one pending write per live link; every pending write has a finite 15-second default deadline and a matching terminal path. The maximum relay-encode transition emits two effects per target, and the effect-capacity validation covers that count.
- **Regression tests:** `PendingLinkWriteTest` reproduces the former `EffectFailed` leak, drives an actual throwing executor through `executeEffect`, omits completion until virtual expiry, and proves the same live link accepts a later relay. The first failure test was run red at the audited baseline behavior.
- **Adversarial tests:** Completion before timeout, duplicate completion, late completion, stale timeout, old-generation completion/timeout, link close, and runtime stop are covered. The reducer settles once; later events leave replacement state unchanged.
- **Verification:** `:engine:mesh:meshEngineCheck :engine:mesh:allTests` passed on Android host and iOS Simulator ARM64, followed by the forced full gate below.
- **Remaining risk:** Physical BLE callback timing is not exercised before Phase 6. Deadline expiry intentionally provides no delivery guarantee.
- **Commit SHA:** `7348532ac54513d8cce9751e797e7fcc104c3b88`; additional regression commits `95ef4002b63032d3939b46b4566040d7e476429a` and `9b8da9504cd78f9446b513573f54b53e5949bb01`.
- **Status:** FIXED.

## F2 — retained relay packets

- **Finding:** F2, pending relay entropy retained full packets beyond the pending-admission byte budget.
- **Original failure mechanism:** After admission removed `PendingAdmission`, `PendingRelayEntropy` retained the full `DecodedPacket` until entropy completion or dedup expiry. `ScheduledRelay` also retained a packet. Neither was charged to an aggregate byte policy. At the old defaults, 10,000 distinct 128-KiB entries had a theoretical raw-wire footprint near 1.25 GiB.
- **Chosen design:** A BitMessage-local 8-MiB `maxAggregateRetainedPacketBytes` covers pending admission plus all full-packet relay state. The existing 4-MiB pending admission budget is reserved, while a 4-MiB `maxAggregateRelayRetainedBytes` gates entry into relay entropy. `MeshState.aggregateRelayRetainedBytes` derives the raw-wire total from pending entropy, scheduled relay and pending encode state; `aggregateRetainedPacketBytes` adds pending admission bytes. Both are validated on state construction, so transfers cannot drift a mutable counter. The encode stage now retains its packet explicitly. The runtime actor also tracks all registered `EncodeRelay` packet bytes until effect settlement and admits no transition over its separate 4-MiB `maxAggregateQueuedRelayPacketBytes` cap. A full relay budget skips new relay entropy while local publication and dedup admission continue. Entropy effects/results carry only correlation, packet ID, source, TTL and two random bytes; they no longer carry a full packet.
- **Files changed:** `MeshLimits.kt`, `MeshState.kt`, `AdmissionReducer.kt`, `RelayPolicy.kt`, `MeshEffect.kt`, `MeshEvent.kt`, `MeshRuntime.kt`, simulator executor, and affected tests.
- **New invariant:** All four full-packet state stages together retain no more than the configured 8 MiB of raw packet bytes; the three post-admission stages retain no more than 4 MiB. Registered relay encode effects are independently limited to 4 MiB across the actor deque, channel and worker until settlement, including after a state entry expires. Other mailboxes retain their existing finite count and per-packet caps.
- **Regression tests:** A default-limit flood of 130 distinct 32-KiB packets checks the old excessive-retention mechanism without using the new limit API. Temporarily removing the relay admission byte guards made this test fail at its byte-bound assertion; restoring the committed guards made it pass. Tiny-limit tests hold entropy results and verify local publication continues when relay scheduling is skipped.
- **Adversarial tests:** Many small unique identities, delayed entropy, transfer through scheduled relay and encode, result release, expiry and budget reuse are covered by `RelayPolicyTest`. `MeshRuntimeAcknowledgementTest` blocks one encode executor and proves a second packet-bearing transition waits for byte credit, then proceeds after settlement.
- **Verification:** Mesh and simulator Android-host/iOS-Simulator suites passed, followed by the forced full gate below.
- **Remaining risk:** State and queued-effect limits cover raw packet representations. Packet-bearing ingress mailboxes retain their existing finite count and per-packet caps, but this work does not provide a measured whole-runtime heap peak or one shared quota across every mailbox.
- **Commit SHA:** `f3b82c7b29f8198d4e0e99cb11d31272c25b7b5b`; hardening `58cc84ff2e4aa3e7aa8db31356eb80480a1205b5`; default-flood test `82ad104c19e0ccbc631a99d90ab28b4a9cd21f8f`; combined-state cap `9c446903ecd9aa26f087f2c919a959310bd7ab11`; runtime ledger cap `d55f41d1285bac83b6e2835fe860028ebcc2d8c7`.
- **Status:** FIXED.

## F3 — scheduled relay causality

- **Finding:** F3, an unrelated event at the relay timer deadline could prune the relay first.
- **Original failure mechanism:** `ScheduledRelay.expiresAt` was the jitter deadline, and `prepareForCapacity` removed a scheduled relay at that timestamp. The later matching timer then found no relay.
- **Chosen design:** A scheduled relay has separate `dueAt` and `validUntil`. Jitter determines `dueAt`; the admitted packet's dedup expiry determines semantic validity. Generic capacity pruning uses `validUntil`. The matching timer executes at or after `dueAt` while still valid and consumes the relay once.
- **Files changed:** `MeshState.kt`, `RelayPolicy.kt`, `RelayPolicyTest.kt`.
- **New invariant:** An unrelated event at `dueAt` cannot consume a valid relay. An early timer cannot execute it, and a timer at or after semantic expiry cannot encode it.
- **Regression tests:** `unrelatedEventAtRelayDeadlineDoesNotConsumeItsTimer` was run red before the fix and green after it; zero jitter is used explicitly.
- **Adversarial tests:** The test repeats the timer to rule out double encode; a late timer before dedup expiry encodes once, and one at expiry does not. Existing duplicate, wrong-correlation and stale-generation tests remain in the mesh suite.
- **Verification:** Mesh Android-host/iOS-Simulator suites passed, followed by the forced full gate below.
- **Remaining risk:** The production coroutine timer callback reports its scheduled deadline as `observedAt`, so reducer-level tests establish late-timestamp semantics while physical scheduling latency is not independently measured here.
- **Commit SHA:** `87c72f7b1bc4d0049dcfdde92bab4cf0db01c5ae`.
- **Status:** FIXED.

## F4 — simulator entropy history

- **Finding:** F4, protocol entropy diagnostics grew for the lifetime of a simulated node.
- **Original failure mechanism:** `NodeProtocolEntropy` appended every generated byte string to an unlimited list, and every node snapshot copied that list.
- **Chosen design:** `SimulationLimits` caps the per-node transcript at 1,024 records and 256 KiB by default; tests can set tiny values. Generation always advances the same RNG and returns the same bytes, even after retention fills. New diagnostic records are dropped deterministically with a saturating dropped count exposed in `SimulatedNodeSnapshot`.
- **Files changed:** `SimulationTypes.kt`, `ProtocolPlans.kt`, `SimulatedNode.kt`, `SimulatedNetwork.kt`, `SimulationEntropyTest.kt`.
- **New invariant:** Per-node diagnostic history and snapshot copy cost remain bounded for the node lifetime; saturation never produces a normal mesh effect failure or changes protocol entropy.
- **Regression tests:** The 1,100-request lifetime test failed against the original unlimited transcript and passed after the cap. A two-record/four-byte test crosses capacity over repeated calls, compares every output to a same-seed replay, and checks ten observable drops.
- **Adversarial tests:** Repeated generation after saturation and deterministic transcript prefix/replay are covered. A two-node simulation crosses a tiny transcript cap over four quiescence calls; its node snapshots expose two drops and replay identically with the same seed.
- **Verification:** `:transport:simulation:simulationCheck :transport:simulation:allTests` passed on Android host and iOS Simulator ARM64, followed by the forced full gate below.
- **Remaining risk:** A direct entropy caller can still request a large single result; simulation protocol requests are fixed at two bytes. The history itself remains bounded by both configured dimensions.
- **Commit SHA:** `756c13ddde0930db31fd6c6cad22d3a203b373d4`; snapshot regression `b0a7f6bc3a4c354598685495d930b1612a115db7`.
- **Status:** FIXED.

## Smaller audit findings

- **F5 — FIXED:** Coverage classification now checks unresolved decision state before executed IDs; the regression test changes an executed fixture to blocked and ran red before the fix. Known layout-conflict classification retains its established precedence. Commit `d4d862ca3bfdb025aa1ecaec859b1af2386b7282`.
- **F6 — NOT_FIXED:** Fixture provenance was not invented. The pinned upstream clones were absent and `ANDROID_HOME` was unset in this environment. The external Apple/Android reciprocal harness was not rerun. Its exact execution evidence and fixture-to-output linkage remain unverified in this remediation.
- **F7 — FIXED:** `AGENTS.md` now includes registered `:feature:root` and the actual `:sharedLogic` dependency. Commit `d4d862ca3bfdb025aa1ecaec859b1af2386b7282`.

## Forced verification

Run from the repository root with `--rerun-tasks --console=plain`:

```text
rtk ./gradlew :core:testing:compatibilityCheck :core:foundation:allTests :core:model:allTests :core:testing:allTests :protocol:bitchat:productionCompatibilityCheck :protocol:bitchat:allTests :transport:api:allTests :engine:mesh:meshEngineCheck :engine:mesh:allTests :transport:simulation:simulationCheck :transport:simulation:allTests --rerun-tasks --console=plain
rtk ./gradlew :androidApp:assembleDebug :sharedLogic:linkDebugFrameworkIosSimulatorArm64 --rerun-tasks --console=plain
```

Both forced commands exited successfully after the final code change. Fresh JUnit XML counts (`tests / failed / skipped`):

| Module | Android host | iOS Simulator ARM64 |
|---|---:|---:|
| `:core:foundation` | 14 / 0 / 0 | 14 / 0 / 0 |
| `:core:model` | 3 / 0 / 0 | 3 / 0 / 0 |
| `:core:testing` | 32 / 0 / 0 | 26 / 0 / 0 |
| `:protocol:bitchat` | 43 / 0 / 0 | 36 / 0 / 0 |
| `:transport:api` | 5 / 0 / 0 | 5 / 0 / 0 |
| `:engine:mesh` | 113 / 0 / 0 | 113 / 0 / 0 |
| `:transport:simulation` | 79 / 0 / 0 | 75 / 0 / 0 |
| **Total** | **289 / 0 / 0** | **272 / 0 / 0** |

All seven behavior modules executed tests on both configured test platforms. `NO-SOURCE` messages in the Gradle logs concerned resource or Java tasks; no listed behavior test task was `NO-SOURCE`. The compatibility, production compatibility, mesh, and simulation coverage gates each executed their required anchor tests. `androidApp-debug.apk` was assembled and `SharedLogic.framework` linked for iOS Simulator ARM64. The commands do not build or run the native Xcode app. No production dependency on test or simulator infrastructure was added; no codec compatibility bytes were changed.

## Final adversarial answers

1. **Failed `WriteLink` blocks forever?** No. Its matching `EffectFailed` settles it and cancels the deadline.
2. **Missing completion blocks forever?** No. The monotonic write deadline settles it.
3. **Late/duplicate completion clears a newer write?** No. Settlement removes only its unique correlation in the current generation.
4. **Every full relay packet covered by an aggregate byte policy?** Yes. Mesh-state entries have the 8-MiB combined and 4-MiB relay caps; registered encode effects have a 4-MiB actor-owned cap until settlement. Ingress mailboxes remain separately bounded by count and per-packet size.
5. **Delayed entropy exceeds the configured state budget?** No. Entropy state admission checks the aggregate before retaining the packet; entropy effects/results carry no packet.
6. **Budget released deterministically?** Yes, on state transfer, result, cancellation, expiry, and stop, because the aggregate is derived from state maps.
7. **Unrelated event at `dueAt` erases a valid relay?** No. Generic pruning uses `validUntil`.
8. **Slightly late timer executes a valid relay?** Yes, for a timer event observed before `validUntil`.
9. **Relay executes twice?** No. Matching timer processing removes the scheduled entry before producing encode work.
10. **Simulator entropy history grows without bound?** No. Record and byte caps apply for the node lifetime.
11. **Transcript saturation changes entropy output?** No. RNG generation occurs before optional diagnostic retention.
12. **Generation/correlation guarantees changed?** No; write settlement and relay timers check both as before, and write deadlines use the write correlation.
13. **Compatibility bytes changed?** No codec or fixture bytes were changed; production compatibility tests passed.
14. **Production dependency on simulation/testing?** No; Gradle production dependencies are unchanged.
15. **Real tests on both platforms?** Yes: 289 Android-host and 272 iOS-Simulator-ARM64 executed, zero failed or skipped. Pinned external Apple/Android upstream harnesses were not run (F6).
