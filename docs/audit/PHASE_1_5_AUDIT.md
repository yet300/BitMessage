# BitMessage Phases 1–5 architecture and correctness audit

## A. Executive summary

- **Audit HEAD:** `f51f50c07f336dab3c05e1b52fc6ad5e18782573`, branch `main`.
- **Overall status:** `NOT_READY_FOR_PHASE_6`.
- **Findings:** P0 0, P1 4, P2 2, P3 1. Confidence: high for the source-proven P1 mechanisms; moderate for the aggregate memory estimate and compatibility provenance assessment.
- **Scope:** source, tests, Git history, fixture manifest, build configuration, and forced Android-host/iOS-Simulator execution at this HEAD. No production source was changed. The pinned upstream reproduction harness was inspected but not executed in this audit, so this report does not independently re-attest every upstream literal.

The architecture has a real pure reducer and serialized runtime, and the simulator uses those production paths. The blocking defects concern failure settlement, retained packet memory, relay timer causality, and an unbounded simulator entropy transcript. Green test counts do not exercise those failure sequences.

### Git phase boundaries

| Phase | Boundary commit | Historical meaning |
|---|---|---|
| 1 | `4c7576a` | Phase 1 compatibility baseline test/corpus |
| 2 | `9b04247` | Phase 2 roadmap marked complete after foundation/model/runtime commits |
| 3 | `6989e09` | Evidence-first wire codec added; subsequent Phase 4 commits expanded protocol evidence |
| 4 | `43b10ac` | Phase 4 acceptance evidence remediation, as the Phase 5 handoff also states |
| 5 | `f51f50c` | Phase 5 handoff and current HEAD |

These are reachable Git commits, not inferred dates. `docs/PHASE_5_HANDOFF.md` names a former feature branch, while this audit checkout is `main`; the base SHA it names matches history. `AGENTS.md` omits the now registered `:feature:root` module.

## B. Architecture map

Actual project dependencies from `build.gradle.kts` and `settings.gradle.kts`:

```text
androidApp -> sharedUI -> sharedLogic -> core:common
                                  \------> feature:root -> core:common
iosApp (Xcode) -------------------> sharedLogic
core:model -> core:foundation
core:testing -> core:foundation
protocol:bitchat -> core:foundation, core:model
transport:api -> core:foundation, core:model
engine:mesh -> core:foundation, core:model, protocol:bitchat, transport:api
transport:simulation -> core:foundation, core:model, protocol:bitchat,
                        transport:api, engine:mesh
```

`core:testing` is a test-scope dependency of protocol, mesh, and simulation; no production module imports it. No application dependency points inward to simulation. `transport:api` has no BitChat dependency or BitChat type in its link contracts.

Production ingress is `LinkEvent.PayloadReceived` → `MeshRuntime.adaptLinkIngressLocked` → `MeshProtocolAdapter`/`BitchatCodec` structural decode → `MeshEvent.PacketDecoded` → actor → `MeshEngine.reduce` → ordered `MeshEffect` ledger → runtime timer handling or `MeshEffectExecutor` → `LinkCommand`/correlated result. The simulator's `SimulatedNode` constructs the same `MeshEngine` and `MeshRuntime`; `SimulationEffectExecutor` uses production `FragmentPayloadCodec` and `RelayEncoding`, while `SimulatedNetwork` supplies directed links and virtual timer callbacks.

## C. Phase-by-phase verdict

| Phase | Claimed and verified | Unverified or violated | Verdict |
|---|---|---|---|
| 1 compatibility | 52 literal, content-hash-locked fixtures; duplicate IDs rejected; 15 blocked entries remain separate; six corpus tests actually ran. Pinned Apple/Android export and reciprocal-acceptance harness sources exist. | This audit did not rerun either pinned upstream. Forty-six manifest `sourceTest` values are generic “harness or independent derivation” descriptions, not exact upstream test identifiers. Metadata assertion alone cannot prove reciprocal execution. | Credible offline corpus, with provenance re-attestation needed for the strongest claims. |
| 2 foundation | `Bytes` copies on ingress/egress, uses unsigned indexing and content equality; `Transition` snapshots lists; `SnapshotMap/List` copy outer collections. Wall and monotonic clock types are separate; generation and correlation are typed. | Element immutability still depends on model types. Generation/correlation overflow intentionally throws at exhaustion. | Suitable deterministic primitives for current scope. |
| 3 codec | Binary codec is in `commonMain` with no time, random, transport, or platform call; literal v1/v2 fixture tests and production coverage run. Six Phase 4 packet identity/signing/fragment literals are checked; the 256-byte transcript and `7 -> 6` case are fixture-backed. | Five layout-conflict cases and 15 blocked cases remain unresolved. Raw signed/compressed handling intentionally refuses unsupported emission. The production coverage meta-test counts one test method, not 29 independently scheduled test cases. | Evidence-backed supported slice, not full BitChat parity. |
| 4 mesh | Structural decode occurs before reducer packet state; signed packet verification precedes dedup; maps/queues have explicit owners and many capacity checks; stale generation checks are widespread. | Findings F1–F3 violate failure/liveness and aggregate resource guarantees. External effect completion is explicitly later than `submitAndAwait`. | Must repair before physical callback concurrency. |
| 5 simulation | Real codec, engine, runtime, relay/fragment paths; one ordered `(deadline, sequence)` queue; current-time quiescence does not advance future timers; directed epochs reject stale deliveries. | Finding F4 is unbounded. Sixty-four seeds each generate only 8–16 actions from a narrow five-way pattern and check mostly bounds; they are regression scenarios, not broad fuzzing. Virtual effect completion and injectable timers can differ from BLE callback timing. | Useful scenario harness, but incomplete resource and failure-model coverage. |

The exact admission order is structural decode → pending admission/timeout → digest → signature verification when required → dedup → binding/route allocation → publication/fragment/relay consequence. Unsigned public messages and fragments are intentionally admitted without a signature; the simulator tests invalid *signed* messages against dedup poisoning. `PeerId`/wire peer ID remains ephemeral and is not durable identity.

## D. Findings

### F1 — failed or missing write completion permanently occupies a link

- **ID:** F1. **Severity:** P1. **Phase:** 4. **Subsystem:** effect failure and link write lifecycle.
- **Files:** `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/RelayPolicy.kt:241`, `MeshEngine.kt:24`, `runtime/MeshEffectExecutor.kt:13`.
- **Invariant:** every pending link write must settle or time out so subsequent relay writes can use that link.
- **Observed behavior:** relay encoding inserts `pendingLinkWrites[correlation] = link`; later relays skip that link while it appears in the map. A thrown executor exception becomes `EffectFailed`, but `MeshEngine.ignoredResult` leaves the state untouched. Only `LinkCompleted`, link close, or runtime stop clears the entry. There is no write timeout.
- **Why it matters / concrete scenario:** a BLE adapter throws once while initiating a write, or accepts it but loses its completion callback. The link stays open and ready, but every subsequent relay to it is skipped indefinitely. A runtime acknowledgement may already have succeeded because it is only an admission acknowledgement.
- **Evidence:** `RelayPolicy.kt:241-253,273-300`; `MeshEngine.kt:24-43`; `MeshEffectExecutor.kt:13-29`; `AdmissionReducer.kt:757-771`. The current failure test (`MeshRuntimeTest.kt:315-327`) checks conversion to `EffectFailed` but not cleanup of a real pending write.
- **Recommended remediation:** settle the specific pending operation on typed failure and add a correlated write deadline (or require a completion contract with enforced timeout). Preserve generation and link checks.
- **Required regression test:** initiate a relay write, throw from executor and separately omit completion; assert pending entry clears and a later relay can use the same still-open link. Duplicate/stale completion must remain ignored.

### F2 — pending relay entropy retains large packets without a byte budget

- **ID:** F2. **Severity:** P1. **Phase:** 4. **Subsystem:** aggregate attacker-influenced memory.
- **Files:** `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/AdmissionReducer.kt:561`, `MeshState.kt:155`, `MeshLimits.kt:8`.
- **Invariant:** full packet copies retained after admission need an aggregate byte bound independent of packet-ID count.
- **Observed behavior:** `PendingRelayEntropy` stores the full `DecodedPacket`; insertion checks neither map count nor aggregate retained bytes. Its effective entry count is at most `maxAdmittedPacketIds` (10,000), and each ingress packet may be 128 KiB. The 4 MiB `maxAggregatePendingBytes` applies only to `pendingAdmissions`, which is removed at admission. A delayed or absent entropy result can leave these entries for the five-minute dedup lifetime.
- **Why it matters / concrete scenario:** many distinct unsigned public packets reach an open link while entropy results are delayed. The map can retain roughly 1.25 GiB of raw wire representation before object overhead, despite the nominal 4 MiB pending byte limit. This is finite, but not a safe mobile aggregate bound.
- **Evidence:** `AdmissionReducer.kt:45-55,313-329,561-584`; `MeshState.kt:155-162,189,245-265`; `MeshLimits.kt:11-18`. Estimate is 10,000 × 128 KiB; actual live heap can be higher from decoded fields/copies.
- **Recommended remediation:** cap aggregate retained bytes across pending relay stages or retain only a bounded compact representation; define timeout/cancellation of delayed entropy requests.
- **Required regression test:** hold entropy responses, admit distinct maximum-size unsigned packets across many links, assert a small configured aggregate budget rejects later retention while earlier state stays valid.

### F3 — another event at a relay deadline can erase the relay before its timer

- **ID:** F3. **Severity:** P1. **Phase:** 4. **Subsystem:** timer causality.
- **Files:** `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/MeshState.kt:245`, `RelayPolicy.kt:125`, `AdmissionReducer.kt:24`.
- **Invariant:** an eligible scheduled relay remains executable until its matching timer is processed or an explicit cancellation decision occurs.
- **Observed behavior:** a relay's `expiresAt` equals its scheduled timer deadline. `prepareForCapacity(t)` drops scheduled relays when `expiresAt <= t`, without producing a cancellation/relay effect. Normal link and packet events call this pruning. `reduceRelayTimerOrNull` can execute the relay only if it is still present.
- **Why it matters / concrete scenario:** relay timer and a link observation are both due at `t`. If the observation reaches the actor first, pruning removes the relay; the later timer is ignored. The same ordered inputs remain deterministic, but callback order changes whether the packet is relayed, and a busy physical adapter makes the loss plausible. Zero-delay jitter makes the equality case immediate.
- **Evidence:** `MeshState.kt:245-265`; `RelayPolicy.kt:125-146,155-177`; `AdmissionReducer.kt:24-30,195-204`. No test found that submits a non-timer event at exactly the relay deadline before its timer.
- **Recommended remediation:** keep due relays until timer/cancellation resolution; distinguish expiry of stale bookkeeping from a due action.
- **Required regression test:** schedule a relay, submit another event at the deadline first, then the matching timer; assert relay effect occurs once. Repeat with zero delay and duplicate cancellation.

### F4 — simulator protocol entropy history is unbounded

- **ID:** F4. **Severity:** P1. **Phase:** 5. **Subsystem:** simulation memory and snapshot ownership.
- **Files:** `transport/simulation/src/commonMain/kotlin/com/yet/bitmessage/transport/simulation/ProtocolPlans.kt:20`, `SimulatedNode.kt:104`, `SimulatedNetwork.kt:196`.
- **Invariant:** all retained simulation histories must have a configured lifetime/aggregate cap.
- **Observed behavior:** `NodeProtocolEntropy.generate` appends every generated `ProtocolEntropyRecord`, including its bytes, to an unlimited `mutableListOf`; every node snapshot copies the entire list. `SimulationLimits` has no entropy-record or entropy-byte limit. `maxProcessedEvents` is a per-call budget in `runCurrentUntilQuiescent`, not a lifetime bound across repeated calls.
- **Why it matters / concrete scenario:** a long replay or repeated current-time runs with unique relay requests grows the transcript and snapshot copy cost without bound. This can make the simulator fail before its named trace/delivery limits, weakening resource conclusions.
- **Evidence:** `ProtocolPlans.kt:20-35`; `SimulatedNode.kt:104-111`; `SimulationTypes.kt:30-50`; `SimulatedNetwork.kt:196-238`.
- **Recommended remediation:** bounded/redacted entropy transcript with explicit overflow or drop semantics, or disable retention unless a test requests it.
- **Required regression test:** exceed a deliberately small entropy-history cap over multiple quiescence calls and assert a deterministic, observable result.

### F5 — production coverage classification trusts fixture ID before blocked state

- **ID:** F5. **Severity:** P2. **Phase:** 1/3. **Subsystem:** compatibility accounting.
- **Files:** `protocol/bitchat/src/androidHostTest/kotlin/com/yet/bitmessage/protocol/bitchat/ProductionFixtureCoverage.kt:42`.
- **Invariant:** a blocked/unresolved fixture cannot be counted as executed production support.
- **Observed behavior:** `classify` checks the `EXECUTED_FIXTURES` ID set before `BLOCKED_BY_PROTOCOL_DECISION`. Current manifest entries do not trip this, but a future status change to an existing ID can still classify it `EXECUTED` if its outcome remains `ACCEPT`/`REJECT`.
- **Why it matters / concrete scenario:** evidence for an existing supported fixture is revoked and its decision state is changed to blocked; the report can continue to count it as production coverage.
- **Evidence:** `ProductionFixtureCoverage.kt:42-48`; `ProductionFixtureCoverageTest.kt:14-27` asserts totals only; current corpus has 15 blocked entries.
- **Recommended remediation:** classify blocked state first and assert no executed entry has unresolved decision state.
- **Required regression test:** clone an executed fixture with blocked decision state and assert coverage reports blocked, never executed.

### F6 — fixture provenance is weaker than the reciprocal-acceptance test name

- **ID:** F6. **Severity:** P2. **Phase:** 1. **Subsystem:** provenance verification.
- **Files:** `compatibility/BitchatBaseline2026_08/fixtures.json`, `core/testing/src/androidHostTest/kotlin/com/yet/bitmessage/testing/compatibility/CompatibilityCorpusTest.kt:29`.
- **Invariant:** claims of dual upstream acceptance must be traceable to exact executable evidence.
- **Observed behavior:** 46/52 fixture `sourceTest` fields use the generic phrase “Phase 1 pinned harness or independent derivation” rather than a concrete test and output artifact. `dualUpstreamCorpusHasIndependentOriginsAndReciprocalAcceptance` checks only `acceptedBy` metadata counts. The harness sources do call pinned production code, but ordinary CI does not rerun them, and this audit did not rerun them.
- **Why it matters / concrete scenario:** a literal or claimed reciprocal acceptance can drift in the manifest while metadata assertions continue to pass if hashes are updated together.
- **Evidence:** manifest inspection and `CompatibilityCorpusTest.kt:29-38`; `tools/upstream-compat/README.md` explicitly excludes reproduction from ordinary CI.
- **Recommended remediation:** attach exact harness test/output identifiers or reproducible output hashes per canonical fixture; periodically rerun the pinned harness in a controlled gate.
- **Required regression test:** metadata alone is insufficient; a fixture-to-harness-output comparison should fail on altered literal or acceptance evidence.

### F7 — agent module map omits an actual module

- **ID:** F7. **Severity:** P3. **Phase:** cross-phase documentation.
- **Files:** `AGENTS.md`, `settings.gradle.kts:34`, `sharedLogic/build.gradle.kts:18`.
- **Invariant:** repository architecture map matches registered project boundaries.
- **Observed behavior:** `:feature:root` exists and is exported by `:sharedLogic`, but is absent from the agent guide's map and dependency diagram.
- **Why it matters / concrete scenario:** future dependency reviews can miss a feature module. No Phase 1–5 protocol dependency inversion was observed.
- **Evidence:** settings and Gradle source.
- **Recommended remediation:** update the guide when the audit findings are remediated.
- **Required regression test:** none; documentation review is enough.

## E. State ownership and coroutine topology

| State | Owner / mutation | Bound and lifecycle |
|---|---|---|
| `Bytes`, `Transition`, `SnapshotMap/List` | Value constructors copy outer inputs; no writer after creation | Input size is caller/module controlled; elements must themselves be immutable |
| `MeshState` maps and counters | Sole `MeshRuntime` actor calls pure `MeshEngine.reduce`; direct reducer tests own their local state | Mostly `MeshLimits`; F2 is a materially excessive aggregate bound; F1 has no completion expiry |
| Runtime lifecycle flags, current run, `StateFlow` | `MeshRuntime` under `lifecycleMutex`; actor is normal state writer, stop fallback writes only after actor termination | One active `RunContext`; stop/close cancel supervisor and timers |
| External/effect/result/control/trace channels and actor deques | One run context; actor owns deques, worker owns effect execution | Explicit channel capacities and causal credit; traces drop with a counter |
| Timer jobs | `CoroutineMeshTimerDriver` mutex-protected map, children of run supervisor | `cancelAll` on teardown; timer count follows scheduled effects, but no standalone timer quota |
| Simulator queue, directed links, fault cursors, delivery/completion histories | `SimulatedNetwork` mutex/virtual-time owner | Node/link/event/history limits; lifetime on `close` |
| Protocol entropy RNG and transcript | `SimulatedNode` effect executor | RNG node-local; transcript **UNBOUNDED** (F4) |

Long-lived jobs are `MeshRuntime`'s actor and effect worker, created at `start` under a run-specific `SupervisorJob(parentScope.Job)`; the default timer driver creates child timer jobs. Stop/close cancel and join that supervisor. The simulator injects a network timer driver instead, with registrations owned by the virtual queue. There is no constructor-launched protocol coroutine. An actor/effect-worker crash cancels the run supervisor; external submissions then return closed. The simulator's mutable network structures are test infrastructure, not a second production mesh owner.

## F. Bounds table

| Resource | Owner | Limit / expiry | Aggregate bound / tested? |
|---|---|---|---|
| Incoming wire packet | runtime adapter | 128 KiB default before decode | One submission plus mailbox; size checked / yes |
| Pending admissions | mesh state | 256, 8/link, 4 MiB; 15 s | 4 MiB / yes |
| Dedup IDs | mesh state | 10,000; 5 min | Count bounded / yes |
| Peer bindings/routes | mesh state | 256/512, per-link/source quotas, 3 min | Count bounded / yes |
| Fragment streams | mesh state | 64, 4/source, 256 fragments/stream, 128 KiB/stream, 4 MiB total, 30 s | 4 MiB / yes; reassembled bytes reenter structural decode |
| Scheduled relay packets | mesh state | 512, 8/source, dedup/relay deadline | Up to ~64 MiB raw packet bytes / count tests; F3 causality gap |
| Pending relay entropy packets | mesh state | Effectively 10,000 admitted IDs, 5 min | **~1.25 GiB default raw bytes; no aggregate byte quota** / no saturation test |
| Pending link writes | mesh state | At most one/link, 32 links | Count bounded, **no timeout** / failure path untested |
| Runtime channels/deques | run context | Mailboxes 256, effect 256, trace 256; causal credit | Bounded / concurrency tests |
| Simulator nodes/directions/events | network | 16/64/4,096; per-call 100,000 processed | Bounded current queue / tests |
| Simulator delivery/completion/publication/trace | network/node | 8,192/8,192/1,024 per node/8,192 | Fail or drop observably / tests |
| Simulator entropy transcript | node | **UNBOUNDED** | **UNBOUNDED** / no limit test |

## G. Async causality table

| Request → result | Correlation / generation | Timeout, duplicate, stale behavior |
|---|---|---|
| Digest → `PacketDigestComputed` | Pending admission ID and generation | 15 s admission timeout; duplicate stage ignored; old generation ignored; generic executor failure leaves entry until timeout |
| Signature verify → `SignatureVerified` | Same pending ID and generation | Same admission timeout; invalid signature rejected before dedup |
| Fragment decode → `FragmentPayloadDecoded` | Pending fragment decode ID and generation | Pruned at dedup expiry; duplicate/stale ignored |
| Entropy → `EntropyProvided` | Pending entropy ID, packet/source/details, generation | Pruned at dedup expiry; duplicate/stale ignored; F2 memory exposure until then |
| Relay encode → `RelayEncoded` | Pending encode ID, packet/targets, generation | Pruned at dedup expiry; duplicate/stale ignored |
| Timer schedule → `TimerElapsed` | Timer ID, correlation, generation | Matching timer checks; cancel on close/stop; F3 deadline ordering |
| Write/close → `LinkCompleted` | Result ID/link/generation | Matching pending write removed; duplicate/stale ignored; **write has no deadline or generic failure settlement (F1)** |
| Publication → executor `null` | Correlation/generation on effect only | No completion claim; publication side effect may occur after submit acknowledgement |

`submitAndAwait` acknowledges state commit, trace publication attempt, registered-effect count, and complete effect-list admission to the bounded actor ledger. It does **not** attest that an effect ran, that bytes were written, or that a peer received them. No production caller currently treats it as delivery; Phase 6 must keep this distinction.

## H. Compatibility evidence table

| Behavior | Apple evidence | Android evidence | BitMessage check | Classification / confidence |
|---|---|---|---|---|
| v1/v2 outer public packet, recipient, route | Pinned export and reciprocal acceptance harness; literal IDs in corpus | Corresponding pinned export/acceptance harness | Android-host literal decode/re-encode, common codec tests | UPSTREAM FACT for supported cases; moderate until upstream harness rerun |
| Legacy/extended announce TLVs and unknown preservation | Pinned announcement harness | Pinned announcement harness | Announcement fixture tests | UPSTREAM FACT for literal slice; moderate |
| Packet identity SHA-256/truncation | Phase 4 Apple harness + literal/hash | Phase 4 Android harness + literal/hash | Production fixture and mesh tests | UPSTREAM FACT for selected vector; moderate |
| 256-byte signing transcript and signature-preserving `7 -> 6` relay | Phase 4 Apple production-helper harness | Phase 4 Android production-helper harness | Two signed relay fixtures and production check | UPSTREAM FACT for selected vectors; moderate |
| Fragment 13-byte metadata, ascending reassembly | Phase 4 Apple harness | Phase 4 Android harness | Two fragment literals, codec/mesh tests | UPSTREAM FACT for selected positive case; moderate |
| General received TTL cap 7 and `255 -> 6` | No general upstream proof | No general upstream proof | Relay policy tests | BITMESSAGE LOCAL POLICY; high |
| Compressed/padded signed relay; exact cross-client fallback fanout | Unmerged/disputed evidence | Unmerged/disputed evidence | Emission blocked or no parity test | BLOCKED / UNRESOLVED; high |

The fixture bytes are literal JSON artifacts, not generated by the current BitMessage codec. Content hashes protect accidental drift, not independent provenance by themselves. No current production test was found that turns a blocked `DECODE_ONLY` fixture into shipping emission.

## I. Verification evidence

Commands run from repository root (both exit 0):

```text
rtk ./gradlew :core:testing:compatibilityCheck :core:foundation:allTests :core:model:allTests :core:testing:allTests :protocol:bitchat:productionCompatibilityCheck :protocol:bitchat:allTests :transport:api:allTests :engine:mesh:meshEngineCheck :engine:mesh:allTests :transport:simulation:simulationCheck :transport:simulation:allTests --rerun-tasks --console=plain
rtk ./gradlew :androidApp:assembleDebug :sharedLogic:linkDebugFrameworkIosSimulatorArm64 --rerun-tasks --console=plain
```

Fresh XML counts (`tests`, Android host / iOS Simulator ARM64): foundation 14/14, model 3/3, testing 32/26, protocol 42/36, transport API 5/5, mesh 101/101, simulation 76/72. Total **530 executions**, zero skipped and zero failed in these suites. Gate output: compatibility 32 Android-host tests (six corpus), mesh one coverage anchor, simulation one coverage anchor. The production compatibility anchor XML is one test and reports 29 executed fixture outcomes; those outcomes are assertions inside that test, not 29 JUnit methods. Android debug APK assembly and iOS shared-framework link succeeded. Resource processing had some `NO-SOURCE` tasks; none of the seven relevant `allTests` suites was zero-source. A full Xcode iOS app build and pinned upstream harness reproduction were not run.

Test categories: common module tests are unit behavior; Android-host fixture suites check literal compatibility; `MeshPropertyTest` and 64 generated simulator seeds are bounded adversarial sequences; runtime acknowledgement tests exercise concurrency; simulation tests exercise directed-link scenarios; four special Gradle checks are nonzero coverage/meta gates. The generated seeds vary topology, small packet mixes, and a few fault patterns, but do not prove the missing F1–F4 sequences. No ignored/skipped tests appeared in the fresh XML.

## J. Missing tests, ranked

1. F1 exception, lost callback, duplicate/stale completion, and recovery on the same open link.
2. F2 delayed entropy responses under maximum-size unique packet flood, with aggregate heap/byte invariant.
3. F3 equal-deadline non-timer event before relay timer, including zero jitter.
4. F4 transcript saturation across multiple simulator run calls.
5. Fresh pinned upstream export/acceptance comparison keyed to each canonical fixture, including hashes and exact harness IDs.
6. BLE-adapter contract tests for concurrent callback ordering and honest completion semantics, before any physical integration is judged complete.

## K. Phase 6 prerequisites and direct answers

**MUST FIX BEFORE PHASE 6:** F1–F3; F4 before treating simulator resource tests as authoritative. **SHOULD FIX BEFORE PHASE 6:** F5–F6 and rerun pinned upstream reproduction. **SAFE TO DEFER:** F7 and unsupported protocol profiles already marked blocked.

1. Malformed remote bytes cannot mutate authoritative `MeshState` through the `LinkEvent` ingress path; structural decode precedes reduction. The public typed `MeshEvent` API assumes its caller is trusted.
2. Invalid signed packets do not poison dedup: signature verification precedes `admit`. Unsigned message/fragment policy is intentionally different.
3. Old-generation effect results are rejected by reducer generation checks; no demonstrated cross-generation mutation was found.
4. Yes, a caller could misread an acknowledgement; the implementation/documentation explicitly limit it to commit and effect admission, and no present production caller makes a delivery claim.
5. Fragment streams have per-stream and 4 MiB global byte caps; the separate pending relay entropy stage has the aggregate hole F2.
6. Link/state counts are bounded, but a missing write completion can strand one link indefinitely (F1).
7. Current-time quiescence checks `queue.peek()?.deadline == now`; it does not advance future expiry timers.
8. Equal-time virtual events use insertion sequence, with checked sequence exhaustion; ordering is deterministic for the same action/effect sequence.
9. Yes: simulated nodes instantiate the production codec adapter, `MeshEngine`, `MeshRuntime`, relay encoding, and fragment codec.
10. Fixture expected bytes are literals, not BitMessage-generated expectations; some tests that decode and re-encode rely on raw representation retention, so they prove a narrower property than independent emission.
11. The general TTL cap 7 and hostile `255 -> 6` are labeled local policy in source/docs; no contrary representation was found.
12. `Bytes`, `Transition`, and snapshot containers copy their immediate collections; no alias mutation was demonstrated in the inspected model paths.
13. The pure reducer has no hidden clock, random, delay, or platform call. Runtime timers and simulator RNG are outside it by design.
14. No production module depends on `core:testing` or `transport:simulation`.
15. `transport:api` contracts and dependencies are protocol-neutral.
16. Stop/restart cancels run jobs/timers; the uncompleted write path is a live-run liveness defect, not an orphan coroutine. No stop/restart orphan was demonstrated.
17. Trace overflow increments a drop counter after transition commit; no protocol-state dependency on trace pressure was found.
18. Bounded causal credit and actor settlement paths address queue deadlock in tested sequences; no permanent queue deadlock was demonstrated. F1 can strand later writes without queue saturation.
19. No: F4 is unbounded, and F2's finite aggregate permits roughly 1.25 GiB of raw retained packets at defaults.
20. Yes: concurrent BLE callbacks can expose F1 missing completions and F3 deadline ordering; the simulator's default immediate executor does not naturally produce those physical timing failures.

## Final decision

F1 can permanently disable relay writes on a live link after one failed/lost completion. F2 permits a mobile-hostile aggregate of retained untrusted packet bytes. F3 can erase a due relay based on equal-deadline event order. F4 invalidates the simulator's blanket bounded-history claim. These concrete gaps should be repaired and regression-tested before physical BLE callbacks become an input to the runtime.

NOT_READY_FOR_PHASE_6
