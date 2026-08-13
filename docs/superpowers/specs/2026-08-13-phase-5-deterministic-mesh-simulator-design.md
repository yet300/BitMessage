# Phase 5 Deterministic Multi-Node Mesh Simulator Design

Status: architecture approved with review adjustments; written specification awaiting review

## Goal

Build the smallest deterministic Kotlin Multiplatform multi-node simulator that exercises the real Phase 3 BitChat protocol boundary and real Phase 4 `MeshEngine`/`MeshRuntime` orchestration under directed network links, virtual time, explicit faults, bounded hostile traffic, and exact replay.

Phase 5 proves that the current production protocol and mesh components interact correctly under controlled multi-node scenarios. It does not promote simulator behavior to cross-client compatibility truth and does not replace future physical-device interoperability testing.

## Scope

Phase 5 includes:

- a dedicated `:transport:simulation` Kotlin Multiplatform module;
- a real `MeshEngine` and `MeshRuntime` per simulated node;
- one authoritative global scheduled virtual-event queue ordered by deadline and insertion sequence;
- explicit current-instant runtime acknowledgement and quiescence;
- directed simulated links with latency, MTU, readiness, disconnect, reconnect, and typed write results;
- an explicit bounded `FaultPlan` for loss, duplication, reordering, partitions, readiness, backpressure, and failures;
- independent simulation-plan randomness and per-node protocol entropy;
- real SHA-256 packet-ID digest semantics in simulation by default;
- deterministic verification outcomes without implementing production cryptography or Noise;
- bounded, redacted traces and exact replay diagnostics;
- direct, relay, duplicate, TTL, partition, reconnect, backpressure, fragment, hostile-admission, relay-loop, and generated adversarial scenarios;
- only the narrow Phase 4 runtime refinements required for virtual timers and deterministic acknowledgement;
- documentation and build-gate updates required by the new module.

Phase 5 does not include:

- Bluetooth, BlueFalcon, CoreBluetooth, Android Bluetooth, BLE scanning, GATT, MTU negotiation, or physical radio behavior;
- Noise, Ed25519, durable identity, key storage, sessions, ratchets, or a production crypto provider;
- persistence, process restoration, outboxes, delivery acknowledgements, retries, read receipts, conversations, or durable message state;
- sync, GCS, courier, store-and-forward, media transfer, or a `DeliveryEngine`;
- a generic simulator framework, plugin fault architecture, virtual operating system, or reflection-driven scenario registry;
- a property-test shrinker;
- any Phase 6 implementation.

## Design principles

1. The simulator supplies observations and executes effects; it does not reproduce mesh policy.
2. `MeshState` remains the only authority for admission, authenticated deduplication, fragments, routes, and relays.
3. Structural decode remains outside the reducer through the production `MeshProtocolAdapter` and `BitChatCodec` boundary.
4. Virtual time advances only through explicit simulator operations.
5. The simulator has one authoritative scheduled virtual-event queue, while the existing bounded runtime mailbox and effect queues remain intact.
6. A runtime acknowledgement proves transition commitment and effect submission, not asynchronous completion.
7. Exact replay data is preferred to speculative shrinking machinery.
8. Compatibility literals come only from the Phase 1 canonical corpus.

## Design 1: module and node boundary

### Dependency graph

Create and register one module:

```text
:transport:simulation
  commonMain -> :core:foundation
  commonMain -> :core:model
  commonMain -> :transport:api
  commonMain -> :protocol:bitchat
  commonMain -> :engine:mesh
  tests only -> :core:testing
```

No application, shared-logic, protocol, transport-contract, or mesh-production module depends on `:transport:simulation`. Production modules continue to have no dependency on `:core:testing`.

The simulator is production-source test infrastructure: common code makes it reusable from Android host and iOS simulator tests, but it is not reachable from an application runtime graph.

### Core components

```text
SimulatedNetwork
  owns topology, links, virtual time, scheduled events, faults, and trace
  |
  +-- SimulatedNode A
  |     owns real MeshEngine + real MeshRuntime + simulation effect executor
  |
  +-- SimulatedNode B
  |     owns real MeshEngine + real MeshRuntime + simulation effect executor
  |
  +-- SimulatedNode C
        owns real MeshEngine + real MeshRuntime + simulation effect executor
```

`SimulatedNode` owns:

- a stable simulation-only node ID;
- one real `MeshEngine`;
- one real `MeshRuntime`;
- one production `MeshProtocolAdapter` using `BitChatCodec`;
- one simulation effect executor;
- an independent protocol entropy source;
- an explicit verification plan;
- real default digest computation plus optional digest overrides;
- bounded publication records and safe immutable state projections.

`SimulatedNode` does not contain another admission cache, dedup cache, fragment assembler, relay selector, route graph, or TTL policy. Assertions read immutable `MeshState` projections after an acknowledged stable boundary; they never mutate or inspect actor-owned state concurrently.

`SimulatedNetwork` owns:

- nodes indexed by stable node ID;
- directed links indexed by stable link ID;
- the authoritative virtual clock;
- the authoritative scheduled virtual-event queue;
- the explicit `FaultPlan` and its cursor;
- bounded redacted trace records;
- processed-event accounting and run limits.

Map iteration does not define behavior. Whenever a scenario action affects multiple nodes or links, the simulator expands it into individually sequenced events in stable node/link-ID order.

### Ingress boundary

Network delivery follows the accepted Phase 4 boundary:

```text
scheduled network delivery
  -> LinkEvent.PayloadReceived(Bytes)
  -> MeshProtocolAdapter
  -> BitChatCodec.decode(...)
  -> MeshEvent.PacketDecoded(decoded packet + raw/signing evidence)
  -> MeshRuntime
  -> MeshEngine.reduce(...)
```

Structural decode failure stops at the adapter. It creates a bounded simulator diagnostic but no `MeshEvent.PacketDecoded`, pending admission, or `MeshState` mutation.

## Design 2: virtual time, scheduled events, acknowledgement, and quiescence

### One authoritative scheduled virtual-event queue

The simulator has one global queue for future virtual events. Its strict ordering key is:

```text
(deadline, insertionSequence)
```

`deadline` is a finite monotonic virtual instant. `insertionSequence` is a checked monotonically increasing value allocated when an event is committed to the queue. Sequence exhaustion is a typed failure; it never wraps or silently reorders work.

Scheduled event families are deliberately small:

- payload delivery;
- write completion;
- link open, close, and readiness observation;
- partition and reconnect action;
- runtime timer expiry;
- explicit scenario action.

This is one global virtual-time queue, not one total queue in the process. Every `MeshRuntime` keeps its existing bounded external-event mailbox, effect queue, result path, and trace buffer. Phase 5 coordinates those actor queues through acknowledgement and quiescence; it does not replace them.

### Virtual-time API

The simulator exposes operations equivalent to:

```kotlin
suspend fun runCurrentUntilQuiescent(): QuiescenceResult
suspend fun advanceTo(target: VirtualInstant): QuiescenceResult
suspend fun advanceBy(duration: Duration): QuiescenceResult
suspend fun runUntil(
    predicate: (SimulationSnapshot) -> Boolean,
    maxProcessedEvents: Int,
    maxVirtualDuration: Duration,
): RunUntilResult
```

There is no API whose default meaning is "advance until every future timer has fired". In particular, Phase 5 will not define protocol convergence as an empty global queue because healthy runtime state intentionally schedules future dedup, fragment, topology, and route maintenance.

### Runtime transition acknowledgement

Add the smallest explicit acknowledged-submission boundary to `MeshRuntime` while retaining `trySubmit` for existing non-blocking callers.

For one accepted event, acknowledgement means:

```text
event accepted by the running generation
  -> reducer transition committed by the single writer
  -> resulting effects admitted to/registered with the ordered effect path
  -> acknowledgement completed
```

The acknowledgement does not mean that asynchronous effects completed. A timer firing, link write completion, delayed digest override, delayed verification override, or other asynchronous provider result remains an explicit future simulator event. Stale and duplicate completions continue through the real Phase 4 correlated-event checks.

Rejection caused by lifecycle state or mailbox pressure is returned as a typed acknowledged outcome rather than being confused with a committed transition.

### Immediate-settlement protocol

Acknowledged transition commitment alone is not sufficient when an already-submitted effect can synchronously produce a result or register a same-time virtual event. The runtime therefore also exposes a narrow barrier equivalent to `awaitImmediateQuiescence()`.

For work accepted before the barrier, it completes only after:

- all committed transitions have submitted their ordered effects;
- each such effect provider has either returned an immediate result, registered an explicit scheduled completion, or produced a terminal typed outcome;
- every immediate result has re-entered the runtime and committed its resulting transition;
- effects recursively emitted by those immediate result transitions have reached the same boundary.

The barrier does not wait for a registered completion whose deadline is later than the current virtual instant. Providers must register a completion before reporting that the effect has settled. This makes it safe for the simulator to inspect the global queue and select the next event without `yield()`, coroutine-test scheduler polling, or arbitrary sleeps.

The implementation may use an internal monotonic work ledger or barrier watermark, but it must preserve single-writer actor ownership and bounded channels. It must not expose mutable `MeshState` or infer stability from unsynchronized `Channel.isEmpty` snapshots.

### Current-instant quiescence

`runCurrentUntilQuiescent()` never advances the virtual clock. At the current instant it repeatedly:

1. Removes the next scheduled event only when its deadline equals `now`.
2. Dispatches that single event through the appropriate network, adapter, or runtime boundary.
3. Waits for the affected transition acknowledgement.
4. Runs the immediate-settlement barrier for affected runtimes in stable node-ID order.
5. Allows effects to register additional same-time scheduled events.
6. Repeats while the global queue head is due at `now` or runtime immediate work remains.

It returns when all of the following are true:

- no scheduled simulation event is due at `now`;
- no accepted runtime mailbox work remains uncommitted;
- no committed effect remains unregistered with an effect provider;
- no immediate effect result remains to be reduced.

Future events remain queued. A dedup expiry five minutes in the future therefore does not alter the state returned after a delivery scenario converges.

Zero-delay cycles are protected by the same processed-event bound as all other work. They cannot make the quiescence loop unbounded.

### Explicit time advancement

`advanceTo(target)` rejects a target earlier than `now`. It advances to each scheduled deadline at or before `target`, invokes current-instant quiescence there, and finally sets `now` to `target` and drains current-time consequences. `advanceBy(duration)` validates a finite nonnegative duration and delegates to checked addition plus `advanceTo`.

Neither operation advances beyond its caller-supplied target.

### Predicate-bounded advancement

`runUntil` supports integration scenarios that need future relay jitter, latency, or delayed fragments:

1. Record the starting instant and processed-event count.
2. Run current-instant quiescence.
3. Evaluate the predicate only against a stable immutable snapshot.
4. If false, select the next scheduled deadline.
5. Fail with a typed unreachable outcome if no future event exists.
6. Fail before exceeding `maxProcessedEvents` or `maxVirtualDuration`.
7. Advance to the next deadline and repeat.
8. Return only after the predicate is true at a current-instant quiescent boundary.

For example:

```kotlin
network.runUntil(
    predicate = { it.node("C").publications.size == 1 },
    maxProcessedEvents = 10_000,
    maxVirtualDuration = 30.seconds,
)
```

This stops after C's publication and all consequences at that same instant have settled. It does not advance to five-minute dedup expiry merely because maintenance timers remain queued.

Relay-loop convergence is expressed with a scenario-specific predicate: no scheduled relays remain in node state, no simulated write or payload delivery relevant to the sent packet remains outstanding, and current-instant quiescence has been reached. Passive future maintenance timers do not prevent convergence.

### Bounds

All limits are immutable validated configuration. Initial defaults are:

| Resource | Default |
|---|---:|
| Nodes | 16 |
| Directed links | 64 |
| Scheduled virtual events | 4,096 |
| Fault actions | 2,048 |
| Trace records retained | 8,192 |
| Processed events per operation | 100,000 |
| Maximum `runUntil` virtual duration | 10 minutes |

Tests use smaller limits where practical. Exceeding a collection, event-count, duration, deadline, or sequence bound returns a deterministic typed failure with replay diagnostics.

## Design 3: directed links and explicit faults

### Directed link model

A bidirectional connection is two independent directed links:

```text
A -> B
B -> A
```

Each directed link records:

- stable link ID;
- source and destination node IDs;
- source-side and destination-side `LinkId` context;
- open/closed lifecycle;
- write readiness;
- positive MTU;
- nonnegative base latency;
- checked link epoch used only by the simulator's link lifecycle.

The link epoch does not replace the real runtime `Generation`. Runtime stale-result scenarios carry the actual `Generation` and `CorrelationId` from Phase 4.

### Write and delivery flow

The simulation effect executor passes every `MeshEffect.WriteLink` to the network:

```text
MeshEffect.WriteLink
  -> validate directed link lifecycle/readiness/MTU/capacity
  -> apply next matching explicit fault decision
  -> schedule typed LinkResult completion
  -> when permitted, separately schedule peer payload delivery
```

The runtime receives the real `Written`, `Backpressured`, `PayloadTooLarge`, `Disconnected`, `Unsupported`, or `Failed` result. A written result means only that the simulated link completed the write; it does not create delivery, read, retry, or durable message semantics.

The network never silently buffers an unbounded write backlog. Queue capacity is checked before committing completions or deliveries.

### FaultPlan

`FaultPlan` is an immutable ordered list of bounded actions selected by stable scenario, write, delivery, node, and link ordinals. Supported actions are limited to Phase 5 needs:

- drop one selected delivery;
- duplicate one selected delivery by an explicit bounded copy count;
- add explicit delay, allowing deterministic reordering;
- return backpressure or a typed write failure;
- change readiness;
- disconnect or reconnect a directed link;
- activate or heal a partition;
- corrupt selected bytes for structural-rejection scenarios.

Fault matching and consumption are deterministic. An action records whether it is one-shot or remains active for an explicit bounded interval. Unmatched actions do not silently disappear; scenario completion can assert that expected actions were consumed.

Generated tests use a seed only to compile an explicit topology, scenario action list, and `FaultPlan` before execution. The executor reads that immutable plan and performs no random choice while the scenario runs.

Partitions affect both directions only when the scenario explicitly groups both directed links. Reconnect establishes new link lifecycle observations deterministically; it does not resurrect writes lost while partitioned and does not invent an outbox.

## Design 4: entropy, digest, verification, and effect execution

### Randomness domains

Simulation-plan randomness and protocol entropy are distinct dependencies:

```text
simulation seed
  -> explicit generated topology/actions/FaultPlan
  -> seed is not consulted during execution

node A protocol seed -> node A EntropySource -> relay jitter effects
node B protocol seed -> node B EntropySource -> relay jitter effects
node C protocol seed -> node C EntropySource -> relay jitter effects
```

There is no `Random.Default`, implicit shared RNG, global seed, or cross-test mutable source.

Independence tests prove both directions:

- changing the simulation seed or fault plan while holding node inputs constant does not change any node's protocol-entropy transcript;
- changing a node's protocol seed does not change the compiled topology, action list, fault plan, or fault decisions.

### Real default packet digest

The normal simulator path handles `MeshEffect.ComputePacketDigest` with the real Phase 4 identity semantics:

```text
PacketIdentityInput.bytes
  -> SHA-256
  -> PacketIdentity.fromSha256(...)
  -> first 16 digest bytes as PacketId
```

`:transport:simulation` provides a simulation/test-only common SHA-256 implementation or provider. It is verified against standard SHA-256 known-answer vectors and the canonical packet-ID fixture before being used by multi-node tests. It is not exported as a production cryptographic provider and does not create `crypto:api`.

`DigestPlan` remains available only as an explicit override for scenarios requiring forced failure, forced collision, delayed completion, duplicate completion, or stale correlation/generation. An arbitrary pseudo-hash is never the default packet identity behavior.

### Verification plan

`VerificationPlan` provides deterministic outcomes for `MeshEffect.VerifySignature`. Rules match explicit request evidence or stable request ordinal and return valid, invalid, failed, or delayed outcomes. The default is fail-closed unless a scenario explicitly declares valid evidence.

This proves authentication-before-dedup behavior without implementing Ed25519, identity ownership, Noise, or key storage. The simulator does not claim that a deterministic valid outcome independently proves upstream cryptographic compatibility.

### Other effects

| Mesh effect | Simulation handling |
|---|---|
| `ReinjectPacket` | Existing runtime sends bytes through the production protocol adapter. |
| `DecodeFragmentPayload` | Production `FragmentPayloadCodec`. |
| `EncodeRelay` | Production `RelayEncoding`; simulator never decrements TTL itself. |
| `RequestEntropy` | Node-local deterministic protocol entropy source. |
| `Schedule` / `Cancel` | Runtime timer-driver seam registers/cancels entries in the global scheduled virtual-event queue. |
| `WriteLink` / `CloseLink` | Directed simulated link and explicit typed completion. |
| `PublishPublicPayload` | Bounded redacted publication record; raw private content is not traced. |

Immediate pure codec outcomes may return directly through the effect executor. Delayed or faulted outcomes are explicit scheduled events. Every result retains the real correlation and runtime generation.

### Narrow Phase 4 refinements

Phase 5 may change Phase 4 runtime code only to add:

1. an injected timer driver, with the current coroutine-delay behavior retained as the default outside simulation;
2. acknowledged event submission with the exact commitment contract above;
3. an immediate-quiescence barrier or equivalent internal work-ledger query;
4. a safe immutable snapshot boundary if the acknowledged state flow is insufficient.

These changes must preserve current lifecycle, mailbox bounds, ordered effect execution, stale-result handling, stop/restart semantics, and all Phase 4 tests. They must not move admission, deduplication, fragmentation, relay, or wire-codec policy into the simulator.

## Design 5: fixtures and acceptance scenarios

### Compatibility authority

The authority chain remains:

```text
pinned upstream evidence
  -> provenance
  -> cross-client validation where applicable
  -> BitchatBaseline2026_08 fixture
  -> canonical hash
  -> productionCompatibilityCheck
  -> production tests
```

No compatibility literal is copied into simulator source or a simulator-local resource corpus. Exact fixture-backed packet identity, 256-byte signing transcript, mutable-TTL signing behavior, canonical `7 -> 6`, and positive fragment evidence are loaded through `:core:testing` in Android host integration coverage. Common cross-platform network tests construct non-normative inputs through production protocol APIs.

Simulation success proves local component interaction only. `255 -> 6` remains a documented BitMessage-local safety policy, not dual-upstream compatibility truth.

### Tier 1: simulator substrate

Before mesh assertions, tests prove:

1. `A -> B` delivery occurs exactly at 100 ms virtual time.
2. Equal-deadline events execute by insertion sequence.
3. An explicit drop removes only the selected delivery.
4. One write can create exactly two explicitly planned deliveries.
5. Added delay can make a later write arrive first.
6. A directed partition prevents impossible delivery.
7. Reconnect creates a deterministic new usable lifecycle.
8. Readiness and backpressure produce real typed outcomes.
9. MTU and queue overflow fail without hidden buffering.
10. Current-instant quiescence leaves passive future maintenance queued.
11. `runUntil` stops after its predicate is true and current-time consequences settle.

### Tier 2: real protocol and mesh scenarios

#### Direct public delivery

Topology `A <-> B`. A valid baseline packet enters through A's simulated write and B's real link/adapter/runtime path. B decodes, admits, publishes exactly once, and records the real packet identity.

#### Three-node signed relay

Topology `A <-> B <-> C`, with no A-C link. A canonical relayable packet begins with TTL 3. B authenticates, admits, publishes, and uses production relay policy/encoding to send TTL 2. C authenticates, admits, and publishes. The test proves stable packet identity across TTL mutation and unchanged signature-compatible representation without simulator TTL logic.

#### Duplicate suppression

The same valid authenticated packet arrives twice on one link and, in a second case, through distinct links. The real admitted cache permits one publication, one relay decision, and one admitted-ID insertion per node.

#### Authentication poisoning

With deliberately small `MeshLimits`, many unique structurally valid packets receive invalid verification outcomes before a legitimate packet arrives. Pending state remains bounded, invalid candidates never enter admitted dedup or relay, and the legitimate packet is admitted and published.

#### TTL boundaries

Network scenarios exercise TTL 0, 1, canonical 7, and hostile 255. TTL 0 and 1 do not relay. Canonical 7 relays as 6 under dual-upstream evidence. Hostile 255 relays as 6 only under the named local cap policy. TTL never underflows.

#### Partition and reconnect

In `A <-> B <-> C`, partitioning B-C prevents delivery and does not deadlock or grow state beyond limits. Healing does not replay lost traffic. A separate generation scenario disconnects generation 1, starts generation 2, then delivers a late generation-1 completion; the real runtime ignores it without mutating generation-2 state.

#### Backpressure

A directed link becomes non-writable or returns `Backpressured`. The real runtime receives the typed result. Neither simulator nor mesh invents an unbounded retry queue.

#### Positive fragments

Canonical or production-encoded fragments arrive out of order with one identical duplicate and one delayed fragment. Real fragment handling assembles in index order, removes the stream, reinjects bytes through `MeshProtocolAdapter`, performs structural decode and full authentication/admission, and publishes exactly once.

#### Fragment attacks

One scenario supplies different bytes for the same stream/index and proves the real Phase 4 conflict policy removes or rejects the hostile stream. Another exceeds global, per-source, and aggregate-byte limits. Assertions use `MeshState` projections to prove boundedness and continued runtime usability.

#### Relay loop

Topology `A <-> B`, `B <-> C`, `C <-> A`. One relayable packet circulates through real links and relay policy. A scenario-specific `runUntil` predicate waits until scheduled relays and packet-relevant network work are resolved, then current-instant quiescence is drained. Each node publishes at most once, dedup stops circulation, TTL does not underflow, and passive expiry timers may remain queued.

#### Deterministic replay

Running the same topology, scenario actions, `FaultPlan`, simulation seed, and per-node protocol seeds twice produces equal:

- final immutable `MeshState` projections;
- publications;
- network deliveries;
- redacted trace;
- processed-event count;
- virtual completion time;
- remaining scheduled-event metadata.

### Generated adversarial coverage

The bounded generated suite uses 64 fixed seeds. Each seed produces at most 128 actions, an explicit topology, an explicit `FaultPlan`, and explicit per-node protocol seeds before execution. Invariants are checked after every current-instant quiescent boundary where applicable:

- pending admissions, admitted dedup, fragment streams/bytes, scheduled relays, and simulator queues stay within configured bounds;
- TTL never underflows;
- invalid authentication never creates an admitted entry;
- a packet is not published twice per node;
- stale-generation results cannot mutate current state;
- closed links cannot complete new writes successfully;
- identical deterministic input replays identically.

There is no Phase 5 shrinker. On failure the test retains and reports exact replay data:

```text
scenario name
seed
explicit generated topology
explicit scenario actions
explicit FaultPlan
per-node protocol seeds
virtual time
processed-event count
last redacted trace records
relevant bounded-state sizes
```

Shrinking is deferred until actual failures show that exact replay data is insufficient.

## Trace and diagnostics

Trace records are structured values rendered deterministically. They contain only virtual time, sequence, node/link IDs, event category, redacted packet ID, TTL where relevant, sizes, correlation/generation, typed outcome, and bounded state counts.

They never contain private keys, shared secrets, unrestricted raw payloads, or plaintext private messages. Retention uses a bounded ring with an explicit dropped-record count. Failure diagnostics include the last 64 records by default even when the configured trace retention is larger.

Semantic assertions are primary. At most a small supplementary golden set covers direct delivery, three-node relay, and partition/reconnect; harmless internal trace additions must not force a large snapshot rewrite.

## Historical salvage

The historical repository remains read-only and non-authoritative. Phase 5 may classify only concrete scenario ideas:

- duplicate relay regression as `SALVAGE_TEST`;
- reconnect/late-result regression as `SALVAGE_TEST`;
- fragment reorder/conflict regression as `SALVAGE_TEST`;
- historical managers and runtimes as `REFERENCE_ONLY`.

No historical runtime, transport manager, or compatibility literal is ported. Current canonical evidence and current production behavior remain authoritative.

## Documentation changes

Implementation updates only materially affected documents:

- `settings.gradle.kts` and the new module build file;
- `AGENTS.md` for the module map, dependency direction, and verification command;
- `docs/architecture/BITMESSAGE_ARCHITECTURE.md` for the simulation boundary;
- `docs/architecture/STATE_MACHINE_DESIGN.md` for runtime acknowledgement and virtual timer coordination;
- `docs/IMPLEMENTATION_PLAN.md` for Phase 5 status and gates.

Compatibility documentation changes only if implementation uncovers a factual evidence issue. Completed research is not rewritten for style.

## Verification strategy

Development follows test-driven slices: write one failing deterministic test, prove the intended failure, add the minimum implementation, rerun the narrow suite, and commit a coherent slice.

The final gate includes:

```bash
./gradlew :transport:simulation:allTests
./gradlew :transport:simulation:simulationCheck

./gradlew \
  :core:testing:compatibilityCheck \
  :core:foundation:allTests \
  :core:model:allTests \
  :core:testing:allTests \
  :protocol:bitchat:productionCompatibilityCheck \
  :protocol:bitchat:allTests \
  :transport:api:allTests \
  :engine:mesh:meshEngineCheck \
  :engine:mesh:allTests \
  :transport:simulation:simulationCheck \
  :transport:simulation:allTests

./gradlew \
  :androidApp:assembleDebug \
  :sharedLogic:linkDebugFrameworkIosSimulatorArm64
```

`simulationCheck` must fail if no selected Phase 5 behavioral test executed. Exact executed counts are derived from fresh JUnit XML per module and target rather than inferred from Gradle task status.

Final source checks include `git diff --check`, an explicit working-tree status, dependency inspection proving no application leak, and searches proving simulator sources do not use wall-clock sleeps, `delay`, or `Random.Default` for scenario execution.

## Definition of done

Phase 5 is complete only when:

1. Every node uses the real `MeshEngine` and `MeshRuntime`.
2. No mesh admission, dedup, relay, fragment, or TTL policy is duplicated.
3. Structural decode remains outside the reducer.
4. No simulator dependency leaks into application production graphs.
5. Scheduled virtual-event order is deterministic.
6. Runtime actor queues remain bounded and coordinated through explicit acknowledgement.
7. Current-instant quiescence does not advance passive future timers.
8. Predicate-bounded advancement has event-count and duration protection.
9. Simulation RNG and per-node protocol entropy are independent.
10. Default packet identity uses real SHA-256 semantics.
11. Simulator collections and traces are bounded.
12. Direct, relay, duplicate, TTL, partition, reconnect, backpressure, fragment, hostile-auth, relay-loop, and replay scenarios pass.
13. Generated adversarial scenarios retain exact replay data and require no shrinker.
14. Phase 1 compatibility, Phase 2 tests, Phase 3 production compatibility, and Phase 4 mesh gates remain green.
15. Android debug assembly and iOS simulator framework linking remain green.
16. No selected Phase 5 behavioral suite is `NO-SOURCE`.
17. Documentation describes the actual implemented boundaries.
18. Phase 6 remains unstarted.

## Implementation order

The implementation plan will decompose Phase 5 into independently testable slices:

1. register the module and lock dependency direction;
2. add and verify the real simulation SHA-256 provider;
3. add the bounded scheduled virtual-event queue and current-instant clock operations;
4. add precise runtime transition acknowledgement and immediate-settlement barriers;
5. route runtime timers through an injected driver while retaining the production default;
6. add nodes, directed links, typed writes, and explicit faults;
7. add entropy, verification, digest overrides, traces, and replay snapshots;
8. prove substrate behavior before mesh integration;
9. add direct and canonical relay scenarios;
10. add duplicate, TTL, partition, reconnect, and backpressure scenarios;
11. add fragment, poisoning, relay-loop, replay, and generated adversarial scenarios;
12. update documentation and run the complete Phase 1-5 gate.

No step begins Phase 6.
