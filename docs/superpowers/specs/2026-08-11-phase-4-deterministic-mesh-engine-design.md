# Phase 4 Deterministic MeshEngine Design

Status: approved in conversation; proposed for written-spec review

## Goal

Implement the smallest deterministic Kotlin Multiplatform mesh engine that can admit, deduplicate, dispatch, reassemble, and relay BitChat packets without embedding transport, platform, cryptographic, persistence, or future product behavior.

The engine is a pure state transition system surrounded by a lifecycle-aware runtime. Every nondeterministic operation is represented as an explicit effect and returns through a correlated event. Authenticated admission precedes authoritative deduplication, dispatch, and relay.

Phase 4 is complete only when each implemented compatibility-sensitive behavior is supported by pinned current-client evidence. Existing Phase 1 fixtures, expected results, provenance, and hashes remain unchanged.

## Scope and evidence boundary

Phase 4 includes:

- transport-neutral link events, commands, and results;
- a pure `MeshEngine` reducer and immutable `MeshState`;
- a serialized runtime with explicit start, stop, restart, and close semantics;
- bounded pending admission and authenticated deduplication;
- packet identity calculation through a protocol-owned canonical input;
- signature verification as an injected effect, not a cryptographic implementation;
- local dispatch of the currently supported opaque public-message payload;
- TTL-safe relay with explicit entropy and timer effects;
- bounded fragment collection and re-entry through the full admission path;
- bounded route observations and conservative direct-next-hop selection;
- deterministic tests for success, hostile input, backpressure, stale results, cancellation, and restart.

Phase 4 does not include:

- Bluetooth, BlueFalcon, CoreBluetooth, Android Bluetooth, or any concrete transport;
- Ed25519, Noise, key storage, identity establishment, or a `crypto:api` module;
- persistence, durable contacts, UI, notification, or application orchestration;
- graph-wide path finding, store-and-forward queues, offline delivery, acknowledgements, or retry protocols;
- media transfer, 1 MiB payload support, Nostr, geohash rooms, favorites, read receipts, or any Phase 5+ engine;
- speculative wire behavior inferred only from names, historical code, or one platform's implementation.

## Compatibility evidence procedure

The accepted Phase 1 compatibility corpus is immutable. Phase 4 may add narrowly scoped test vectors outside that corpus when they are copied from, or independently reproduced against, the pinned current Apple and Android clients. Such vectors must record:

- upstream repository and exact commit SHA;
- source file and symbol that establish the behavior;
- the literal input and expected output used by the test;
- whether both clients agree or the behavior is client-specific;
- the local production API or policy the evidence permits.

Before enabling the corresponding production path, Phase 4 must establish literal evidence for:

1. packet identity, including field order, timestamp byte order, hash algorithm, and truncation;
2. signing-transcript construction and the proof that TTL mutation does not invalidate the signature representation;
3. positive fragment metadata and at least one complete reassembly case;
4. any outer-fragment relay policy that differs from ordinary packet relay.

If a vector cannot be reproduced from the pinned clients, the dependent production path remains profile-blocked. The implementation must not fill an evidence gap with the historical donor repository.

Current inspection supports a candidate packet-identity rule—SHA-256 over packet type, sender ID, big-endian timestamp, and payload, truncated to 16 bytes—but the implementation plan must treat it as unenabled until the pinned literals are committed as tests.

## Module boundary

Create only these Phase 4 modules:

```text
:transport:api
  -> :core:foundation
  -> :core:model

:engine:mesh
  -> :core:foundation
  -> :core:model
  -> :protocol:bitchat
  -> :transport:api

:engine:mesh tests only
  -> :core:testing
```

`:transport:api` owns platform-neutral link contracts. It has no protocol, coroutine-runtime, Bluetooth, socket, platform, or application dependency.

`:engine:mesh` owns the reducer, mesh-specific values, policies, and serialized runtime. It does not import Android, Apple, database, Bluetooth, UI, service-locator, or concrete cryptographic APIs.

`:protocol:bitchat` remains the authority for wire parsing, encoding, signing-relevant byte construction, fragment metadata, and packet-identity input. The mesh engine must not duplicate wire layouts.

Production modules do not depend on `:core:testing`.

## Transport API

The transport contract exposes observations and requested actions, not a transport implementation.

### Link identity and readiness

`LinkId` is an opaque runtime-local identifier. It is not a `PeerId`, authenticated identity, route, address, or durable database key.

A link may be open before it is ready to write. Readiness and capabilities are explicit state. Capabilities contain only limits and features needed to validate an outgoing write, such as maximum payload bytes and whether an operation is supported.

### Link events

The runtime accepts transport-originated events equivalent to:

- link opened;
- readiness or capability changed;
- payload received;
- link closed.

Each event identifies the link and runtime generation. A payload is an immutable `Bytes` value. Transport exceptions are converted at the boundary to typed, redacted failure information.

### Link commands and results

Mesh effects may request:

- write bytes to a specific link with a correlation ID;
- close a specific link for a typed reason.

Write completion returns a typed result equivalent to written, backpressured, payload too large, disconnected, unsupported, or failed. Results are correlated and generation-stamped so a late result from an earlier runtime cannot mutate current state.

The transport API does not silently retry, broadcast, reorder, or drop mesh commands. Concrete transports added in later phases must report pressure and failure explicitly.

## Pure engine contract

`MeshEngine` has one behavioral operation:

```text
reduce(state, event) -> transition
```

A transition contains the next immutable state, an ordered list of effects, and typed trace records. The reducer reads no clock, random source, mutable singleton, coroutine context, transport, or cryptographic implementation.

The same initial state and event sequence must produce structurally equal transitions. An event never triggers an external operation directly; it only emits an effect.

Effects include:

- decode or encode a BitChat packet;
- compute a packet ID from a protocol-owned canonical input;
- verify a signature over protocol-owned signing bytes;
- request entropy for relay jitter;
- schedule or cancel a timer;
- write or close a link;
- dispatch a supported admitted payload to the application boundary.

Every asynchronous effect has a correlation ID and runtime generation. Every corresponding result event carries both. Unknown, duplicate, cancelled, or stale results are ignored and recorded with a typed trace; they are never reinterpreted as a new request.

## State model

`MeshState` contains only deterministic, bounded state:

```text
MeshState
  runtime generation and lifecycle state
  active links
  provisional peer bindings
  pending admissions
  admitted packet IDs
  fragment streams
  route observations
  scheduled relays
```

### Active links

Active links track readiness, capabilities, and transport-local observations. Closing a link removes its provisional bindings, link-scoped pending admissions, and relay eligibility. It does not manufacture a durable peer or identity relationship.

### Provisional peer bindings

A decoded sender or route observation may be associated with the ingress link as provisional evidence. This association is useful for conservative direct routing but is not authentication and must not be exposed as a durable contact binding.

Conflicting observations remain ambiguous. An ambiguous peer has no preferred direct link.

### Pending admissions

Pending admission is a bounded staging area for structurally decoded packets whose packet identity or authentication is incomplete. It contains the received representation and correlation state required to continue admission.

Pending admission is not authoritative deduplication. A malicious invalid signature must not occupy the admitted-ID cache or suppress a later valid packet with the same candidate identity.

### Admitted packet IDs

Only a packet that has completed required authentication and profile checks is inserted into admitted deduplication state. Entries have explicit expiry. The cache stores packet identities and expiry metadata, not full payloads.

### Fragment streams

Fragment state is indexed by a protocol-defined stream identity plus provisional source context. It records validated metadata, received indexes, byte counts, and expiry. It is neither authenticated identity nor authoritative packet deduplication.

### Route observations

Route observations are bounded, expiring evidence of directly reachable peers and explicit source-route hops. They are not a general topology graph and do not authorize Dijkstra or inferred multi-hop routing.

### Scheduled relays

A scheduled relay records the admitted packet ID, immutable received representation, selected targets, decremented outgoing TTL, timer correlation, and expiry. An authenticated duplicate can cancel an equivalent pending relay without causing another dispatch or write.

## Admission pipeline

Admission proceeds through explicit events and effects:

```text
payload received
  -> decode packet
  -> reserve bounded pending admission
  -> compute packet ID
  -> construct protocol signing evidence
  -> verify signature when required
  -> enforce profile, recipient, and TTL rules
  -> atomically consult and update admitted deduplication
  -> dispatch locally and/or schedule relay
```

Structural decode occurs before pending reservation is finalized so malformed packets cannot consume long-lived admission entries. The reducer enforces global and per-link limits before accepting proportional work.

When a signature is required, verification success is the admission boundary. A verification failure removes pending state, cancels related timers, emits a redacted rejection trace, and produces neither admitted deduplication, dispatch, nor relay.

When the profile explicitly permits an unsigned packet family, that policy is represented by a named protocol admission classification. The engine does not infer “unsigned” from an absent signature alone.

After authentication and profile checks, admitted deduplication is atomic within one reducer transition:

- a new ID is inserted, then may dispatch and relay;
- an existing unexpired ID is classified as a duplicate;
- a duplicate does not dispatch or relay a second time;
- an authenticated duplicate may cancel a matching scheduled relay, reproducing the historical duplicate-cancels-pending-relay behavior without allowing unauthenticated cancellation.

Recipient checks distinguish local consumption from relay eligibility. A packet not addressed to the local peer may still be relay-eligible if the profile permits it. The reducer never equates a transport sender with an authenticated content identity.

## Packet identity and signing

Packet identity construction belongs to `:protocol:bitchat`. The protocol module exposes a canonical immutable input or a pure identity operation; the mesh engine does not concatenate fields itself.

The result is represented by a dedicated fixed-size value, not arbitrary `Bytes` or a string. Invalid lengths are rejected at construction. Logs and traces use only a short non-secret display form.

Signing bytes also belong to `:protocol:bitchat`. The protocol must build verification evidence from retained received bytes, not by decoding and re-encoding foreign compressed or signed data.

Relay encoding may change TTL only through a protocol operation that preserves the signing-relevant representation. If the pinned evidence does not prove this invariant, signed packets may be locally admitted but signed relay remains profile-blocked.

Phase 4 injects signature verification as an effect handler contract. It does not select an algorithm, parse a durable identity, manage keys, or introduce a cryptographic module. Tests use deterministic fakes and pinned verification requests.

## Resource limits

`MeshLimits` is an immutable validated configuration. Production defaults are local security limits, not wire constants or claims about current-client capacities.

| Resource | Production default |
|---|---:|
| Active links | 32 |
| Provisional peer observations | 256 global, 8 per link |
| Pending admissions | 256 global, 8 per link |
| Pending authentication lifetime | 15 seconds |
| Admitted packet IDs | 10,000 global |
| Deduplication lifetime | 5 minutes |
| Fragment streams | 64 global, 4 per provisional source |
| Fragments per stream | 256 |
| Bytes per fragment stream | 128 KiB |
| Aggregate fragment bytes | 4 MiB |
| Fragment lifetime | 30 seconds |
| Route observations | 512 global, 16 per provisional source |
| Route observation lifetime | 3 minutes |
| Scheduled relays | 512 global, 8 per provisional source |
| Runtime event mailbox | 256 |
| Runtime effect queue | 256 |

All counts and byte limits are checked before state growth or proportional allocation. Constructors reject negative, zero where unusable, internally inconsistent, or overflow-prone values. Tests use smaller limits to prove every boundary and recovery path.

When a bounded collection is full, the reducer returns a typed limit or pressure outcome. It does not silently evict a live admission, fragment stream, or scheduled relay. Expired entries are removed deterministically before capacity is evaluated. The admitted deduplication cache may remove only expired entries; a full cache rejects new admission rather than weakening duplicate protection.

The 128 KiB fragment-stream default is deliberately narrower than any later 1 MiB media-transfer target. Phase 4 makes no Phase 13 compatibility claim.

## TTL and relay policy

TTL is treated as an unsigned wire value and converted through checked integer operations.

- Local consumption is independent of relay TTL.
- TTL 0 and TTL 1 packets may be admitted and locally dispatched, but are not relayed.
- The relay input is capped at 7.
- The outgoing TTL is `min(received TTL, 7) - 1` when relay is allowed.
- Subtraction is never performed for a value below 2, preventing underflow.
- Tests cover received values 0, 1, 2, 7, and hostile 255.

The cap is a local conservative relay policy, not a claim that 7 is a wire-format maximum.

Relay selection excludes the ingress link, closed links, links that are not write-ready, and links whose capabilities reject the encoded payload.

An explicit source-route next hop is used only when it maps unambiguously to one active link. Otherwise the engine applies the profile's broadcast-fanout policy. Fanout is an isolated pure function because pinned Apple and Android clients may differ. Phase 4 documents and tests the selected conservative policy without claiming exact cross-client fanout parity where the clients disagree.

Relay jitter is the only randomized policy input. The reducer emits an entropy request with a bounded range, then turns the returned value into an explicit timer effect. Tests supply exact entropy values. No reducer code reads a random generator or clock.

Write failure does not reopen admission or dispatch. A typed trace records the outcome and scheduled-relay state is resolved exactly once. Phase 4 adds no retry protocol.

## Fragment handling

The 13-byte fragment metadata layout is parsed and encoded only by `:protocol:bitchat`. The engine receives typed metadata and opaque fragment bytes.

Before retaining bytes, the reducer validates:

- stream ID shape and supported fragment family;
- fragment index and count relationship;
- maximum fragments per stream;
- per-source and global stream limits;
- per-stream and aggregate byte limits;
- metadata consistency with an existing stream;
- stream expiry and source context.

An identical duplicate fragment is ignored. Reusing an index with different bytes, or changing fixed metadata for an existing stream, rejects and removes the conflicting stream so mixed content cannot be assembled.

When all indexes are present, assembly occurs in ascending index order, the stream is removed in the same transition, and the engine emits an explicit `DecodeReassembledPacket` effect. The decoded inner packet returns as a normal result event and re-enters the complete admission pipeline, including packet identity, required authentication, profile checks, authoritative deduplication, dispatch, and relay.

Reassembly never calls the reducer recursively and never bypasses authentication. Completed outer fragment state is not treated as admitted inner-packet identity.

Positive fragment relay remains disabled unless pinned evidence establishes whether current clients relay outer fragments, reassembled inner packets, or both, and how signatures apply. Local reassembly and authenticated inner admission may proceed independently when their own evidence is complete.

## Runtime and lifecycle

`MeshRuntime` is the imperative shell around the pure reducer. Its constructor launches nothing.

### Start

`start` creates an owned coroutine scope, increments the runtime generation, initializes bounded event and effect queues, and launches exactly one reducer consumer plus one ordered effect executor. Calling `start` while already running is a typed no-op or rejection; it never creates duplicate consumers.

### Submission and pressure

Non-suspending submission returns accepted, backpressured, or closed. Suspending submission, if exposed, remains cancellable. The runtime never reports success for an event it dropped.

Events are reduced serially. Effects from a transition enter the bounded effect queue in declared order. The runtime does not allow an effect result to overtake state publication for the transition that emitted it.

### Stop

`stop` first rejects new input, allows the currently executing reducer transition to publish atomically, then cancels outstanding effects and timers. It clears transient link, provisional binding, pending admission, fragment, route, and scheduled-relay state.

Only unexpired admitted deduplication entries may survive a stop. Their remaining expiry is represented explicitly, not inferred from a running timer.

### Restart

A later `start` uses a new generation. It restores only surviving, unexpired deduplication entries and schedules fresh expiry effects. Results from prior generations are ignored with a stale-result trace.

### Close

`close` is permanent and idempotent. It cancels the owned scope and rejects all future starts and submissions.

Runtime code must rethrow coroutine cancellation. Broad exception handling may convert non-cancellation failures to typed internal events, but cannot consume cancellation or leave the runtime half-running.

## Trace and error model

Expected malformed input, profile rejection, duplicate detection, limit pressure, stale results, write failures, and lifecycle conflicts are typed data. They are not exposed parser exceptions.

Trace records contain category, correlation ID, generation, link ID where relevant, and safe numeric metadata. They exclude raw payloads, signing bytes, signatures, keys, full peer identifiers, and full packet IDs.

The trace stream is observational. Losing a trace consumer cannot change mesh state or block the reducer. Trace buffering is bounded and any dropped trace count is observable.

## Historical salvage classification

Historical code is non-normative and may be used only after current-client evidence defines behavior.

| Historical artifact | Classification | Permitted use |
|---|---|---|
| `PacketIdUtil` field assembly | `SALVAGE_ALGORITHM` | Structure tests after pinned current-client literals establish the exact rule |
| Duplicate cancelling a scheduled relay | `SALVAGE_TEST` | Regression test, with cancellation allowed only after authentication |
| `RelayController` fanout and timing | `REFERENCE_ONLY` | Identify cases to test; do not copy policy as compatibility truth |
| `RoutePlanner` | `REFERENCE_ONLY` | Identify direct-next-hop cases; no graph routing port |
| `FragmentManager` accounting and assembly | `SALVAGE_ALGORITHM` | Bounded implementation ideas after positive current-client vectors exist |
| Actor lifecycle and cancellation tests | `SALVAGE_TEST` | Runtime serialization, stop, stale-result, and restart scenarios |

The historical admission order that deduplicates before authentication is explicitly rejected.

## Test strategy

Behavior is developed test-first. The suite is split by authority rather than by implementation class.

### Protocol evidence tests

- pinned packet-ID known-answer literals for both current clients;
- signing-transcript literals, including TTL-preserving relay encoding;
- positive fragment metadata and complete reassembly literals;
- negative length, count, index, and overflow cases;
- proof that existing Phase 1 fixtures and hashes are unchanged.

### Pure reducer tests

- identical event sequences produce identical transitions;
- authentication failure cannot poison admitted deduplication;
- only an authenticated duplicate can suppress dispatch, relay, or a pending relay timer;
- local recipient, non-local recipient, broadcast, and unsupported profile cases;
- TTL 0, 1, 2, 7, and 255 behavior;
- ingress exclusion, readiness exclusion, ambiguous next hop, and fanout policy;
- exact entropy-to-jitter and timer-correlation behavior;
- fragment quotas, identical duplicates, conflicts, expiry, assembly order, and inner re-admission;
- every configured resource boundary and typed rejection path;
- stale, unknown, duplicate, and cancelled effect results.

### Runtime tests

- constructor performs no work;
- exactly one serialized reducer consumer is active;
- bounded submission reports backpressure without silent loss;
- effect order and result correlation are preserved;
- stop publishes the current transition, rejects new work, and cancels outstanding work;
- cancellation is not swallowed;
- restart increments generation, retains only valid deduplication, and ignores old results;
- close is idempotent and permanent.

### Hostile and generated tests

Generated sequences vary link churn, payload order, result order, timer order, fragment indexes, duplicate placement, queue pressure, and lifecycle boundaries. Assertions cover deterministic equality, bounded state, no reducer exceptions, no unauthenticated dispatch or relay, and no mutation from stale generations.

## Verification gates

The implementation plan must run narrow tests first, then finish with all of these gates:

```text
./gradlew :core:testing:compatibilityCheck
./gradlew :core:foundation:allTests :core:model:allTests :core:testing:allTests
./gradlew :protocol:bitchat:productionCompatibilityCheck :protocol:bitchat:allTests
./gradlew :transport:api:allTests :engine:mesh:allTests
./gradlew :androidApp:assembleDebug :sharedLogic:linkDebugFrameworkIosSimulatorArm64
```

The Phase 4 test gate must fail if no engine test executes. Test-count reporting must distinguish Android-host and iOS simulator executions and must not double-count a dedicated coverage gate as new behavioral coverage.

## Completion criteria

Phase 4 is complete when:

- both new modules obey the dependency graph;
- the reducer is pure and deterministic;
- all runtime queues and state collections are bounded;
- authenticated admission precedes authoritative deduplication, dispatch, and relay;
- packet identity, signing/TTL handling, and positive fragments are backed by pinned literals;
- fragment completion re-enters full admission without recursion;
- relay TTL cannot underflow and nondeterminism is effect-driven;
- stop, restart, cancellation, backpressure, and stale-result behavior are tested;
- existing compatibility data is byte-for-byte unchanged;
- all verification gates pass on the available Android host and iOS simulator targets;
- no Phase 5+ production scaffolding is introduced.

If the required pinned evidence cannot be established, the corresponding path remains explicitly profile-blocked and Phase 4 is reported as incomplete rather than guessed into completion.
