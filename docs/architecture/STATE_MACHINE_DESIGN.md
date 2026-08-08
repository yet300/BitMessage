# Deterministic State-Machine Design

Status: Phase 2 generic kernel implemented; remaining concrete-engine design is future work
Scope: Phase 2 reducer/trace contracts plus future common protocol/runtime decisions; platform adapters remain future effect executors.

## 1. Reducer contract

Phase 2 implements this generic reducer contract only:

```kotlin
interface Engine<S : Any, E : Any, F : Any> {
    fun reduce(state: S, event: E): Transition<S, F>
}

class Transition<S : Any, F : Any>(
    val state: S,
    effects: List<F> = emptyList(),
    trace: List<TraceRecord> = emptyList(),
)
```

`Transition` keeps private snapshots of its ordered effect and trace lists and returns fresh list views. `TraceRecord` contains only a validated transition name, optional `CorrelationId`, a closed `TraceDecision`, and closed `TraceSizeKind` facts with nonnegative counts. Its schema excludes byte/payload and free-form diagnostic fields. Callers must supply only non-secret transition names and correlation IDs; this generic type does not sanitize those identifier strings.

There is no Phase 2 production or product `Engine` implementation, public concrete effect type, executor, middleware, registry, plugin, composition root, persistence mechanism, coroutine runtime, production scheduler, or production entropy provider. Test-only `VirtualScheduler` and non-cryptographic `SeededEntropy` are separate deterministic infrastructure. Given identical initial state and ordered events, a future engine implementation must produce identical state, effects and trace records; the generic kernel itself performs no I/O, clock/randomness read, or service lookup.

The following state ownership and runtime material is the normative design for later concrete engines. It is not implemented by Phase 2.

## 2. State ownership

| State | Sole mutable owner | Durable authority |
|---|---|---|
| Link availability, peer/link bindings, topology, dedup, fragments | `MeshEngine` actor | Snapshot/journal only where recovery is required; public history in repository |
| Handshake/session generation, transcript, authenticated peer evidence | `NoiseSessionEngine` actor | Opaque key/session records and identity repository according to protocol policy |
| Logical send and attempt policy | `DeliveryEngine` actor | Message/outbox/attempt repositories |
| GCS windows, sync peer budget, courier envelope decisions | `SyncEngine` actor | History/courier repositories |
| Transfer manifest/chunk/resume state | `MediaTransferEngine` actor | Transfer/media repositories |
| Messages, conversations, contacts and receipts | Repository transaction | Database |
| Bluetooth connection/session/readiness | Bluetooth adapter | Platform BLE manager; restoration metadata where supported |
| UI/navigation state | Decompose component/store | StateKeeper only when justified |

One state has one writer. Other engines receive facts as events or issue effects through the coordinator. They never share a mutable map.

The runtime owns actors, scopes and ordering. An engine actor may own a mailbox and child scope only with explicit `start`, `stop`, `close`, restart and error policies. There are no launches in object initialization. Cancellation is never swallowed by broad exception handling.

## 3. Future runtime event loop

```text
external callback / timer / recovery row
        |
        v
typed event -> bounded serialized mailbox -> reducer
                                           | state committed in memory
                                           v
                                    ordered effects
                                           |
                  +------------------------+-----------------------+
                  |                        |                       |
             persistence              crypto/provider         transport
                  |                        |                       |
                  +---------- typed result event ----------------+
```

Rules:

1. Each engine mailbox is bounded. Overflow has a typed policy; it never silently drops protocol input.
2. One transition is reduced atomically. Effects retain reducer order, although independent executors may run concurrently only when explicitly marked parallel-safe.
3. A state change that represents a durable fact is not externally acknowledged until its persistence effect succeeds.
4. At-least-once effect execution is assumed across process death. Commands therefore carry stable IDs and executors/repositories are idempotent.
5. Adapter callbacks are normalized and enqueued; they never call one another recursively through the engine.
6. Cross-engine communication passes through a coordinator as typed events with causal/correlation IDs.
7. Trace records contain IDs, transition names, sizes and reasons, never plaintext or key material.

## 4. Time, scheduling and entropy contracts

### Wall versus monotonic time

Phase 2 exposes `WallClock.now(): kotlin.time.Instant` for external time and `MonotonicClock.now(): MonotonicTime` for elapsed time. `MonotonicTime` is finite, nonnegative, process-local, and non-durable. It must not be serialized or treated as wall time.

Future protocol/runtime policy may use wall time for wire/user/persisted facts and monotonic time for elapsed deadlines. Wall time may jump and must never drive in-process elapsed deadlines.

Future persistence stores a wall deadline plus the policy inputs needed to recompute a monotonic deadline after recovery. It clamps negative or excessive elapsed durations caused by wall-clock correction.

### Scheduling

Phase 2 defines `ScheduleTimer(timerId, delay, generation)`, `CancelTimer(timerId)`, and `TimerFired(timerId, generation)`. Delays are finite and nonnegative; the generation is preserved so a future engine can detect staleness. Phase 2 supplies no production scheduler.

The test-only `VirtualScheduler` advances only when instructed, orders due timers by `(deadline, insertion sequence)`, suppresses cancellations, and replaces older pending entries for the same timer ID. It never sleeps or reads wall time.

### Randomness

Phase 2 defines only `EntropyRequest(correlationId, byteCount)`, `EntropyGenerated(correlationId, bytes)`, and the `EntropySource` interface. There is no production entropy provider. Test-only `SeededEntropy` is deterministic and non-cryptographic.

No future production security decision uses Kotlin `Random`. A future simulator keeps fault scheduling separate from protocol entropy.

## 5. Future effects and persistence

The representative effects and persistence mechanisms below are future architecture, not Phase 2 implementation.

Representative effects:

```text
Transport: SendLinkPayload, Connect, Disconnect, StartScan, StopScan
Crypto: Hash, VerifySignature, NoiseRead, NoiseWrite, GenerateKey, Seal, Open
Storage: LoadRecoveryState, CommitInbound, CommitOutbound, ClaimOutbox,
         CompleteAttempt, SaveSessionEvidence, StoreFragment/Media, DeleteSensitiveState
Time: Schedule, CancelTimer
Domain: PublishMessageProjection, PublishPeerProjection
Cross-engine: DispatchMesh, DispatchNoise, DispatchDelivery, DispatchSync, DispatchMedia
```

Persistence effects use transactions and optimistic version/generation checks. A success event includes the committed version. A stale writer receives `Conflict`, reloads authoritative state and deterministically re-evaluates. Database exceptions are classified as retryable, corruption, capacity/quota, constraint violation or permanent; raw driver exceptions do not enter reducers.

An outgoing acceptance transaction inserts:

```text
message(logicalId, conversationId, content, status=Queued)
delivery_attempt(attemptId, logicalId, state=Pending, profile, route intent)
outbox_operation(operationId, kind=SendMessage, availableAt, lease=null)
```

An executor claims the outbox operation with a lease. Crash recovery reclaims expired leases. The reducer never deletes the only durable evidence before a receipt-dependent transition is committed.

## 6. Engine decomposition

### MeshEngine

Owns:

- link/peer bindings and authenticated/provisional status;
- packet admission, packet IDs, dedup and replay windows;
- TTL and relay/fanout/source-route decisions;
- fragment streams, bounds and expiry;
- topology observations and dispatch to Noise, delivery, sync or media.

Does not own conversations, outbox retry, Noise primitives, GCS history storage, BlueFalcon or UI.

Core shape:

```text
MeshState(peers, links, seenPackets, fragments, topology, relayBudget)
MeshEvent(LinkUp, LinkDown, BytesReceived, PacketDecoded, SignatureChecked,
          TimerFired, SendRequested, EffectFailed)
MeshEffect(Decode, Verify, WriteLink, Schedule, PersistHistory,
           DispatchNoise/Sync/Delivery/Media)
```

### NoiseSessionEngine

One logical machine owns all peer session generations to resolve simultaneous handshakes deterministically.

States include `Absent`, `Initiating`, `Responding`, `Established`, `Replacing`, `Closing`, `Failed`. It owns transcript progression, remote-static binding, authenticated capability evidence, timeouts and replacement. Crypto bytes are produced by a provider effect.

Tie-break behavior must be a compatibility profile decision using authenticated identities/peer IDs exactly as upstream vectors specify. Stale link callbacks and ciphertext carry a session generation and are rejected without mutating the current generation.

### DeliveryEngine

Owns logical-send policy, not bytes. It evaluates reachability and capability evidence, starts attempts, waits for ACK/read receipt, schedules retry, selects fallback and projects monotonic status.

States are derived from durable messages and attempts. `SharedFlow` is not the authority. A receipt is idempotent and can arrive through a different transport than the attempt.

### SyncEngine

Owns reconciliation sessions, GCS requests/responses, public-history windows, per-peer response budgets, reconnect sync, and courier spray/encounter decisions. It never directly reads a database; it requests bounded summaries/pages. Request sync is link-local even if a malicious input has relayable TTL.

### MediaTransferEngine

Owns manifest validation, transfer identity, chunk window, resume, integrity verification, quota admission and completion. It is distinct from outer BitChat fragmentation: a media object can use protocol chunks whose resulting encrypted packet is itself fragmented for MTU.

`QueuePlugin` backpressure is only a link result. It does not advance transfer or message delivery without the appropriate engine-level acknowledgment.

## 7. Error model

Every rejection has a stable typed reason:

```text
Malformed, UnsupportedVersion, UnsupportedFeature, ProfileViolation,
Unauthenticated, SignatureInvalid, Replay, Duplicate, Expired,
LimitExceeded(kind, limit, observed), Backpressured, LinkLost,
CryptoFailure, PersistenceRetryable, PersistenceCorrupt,
Cancelled, InternalInvariantViolation
```

Malformed/hostile input changes metrics and possibly peer rate limits, not global engine failure. `InternalInvariantViolation` stops the affected actor, captures a redacted state fingerprint and requires recovery/restart policy; continuing from unknowable state is forbidden.

Retries are explicit effects with bounded exponential backoff, monotonic deadlines, attempt budgets and jitter results. Permanent protocol/security failures are not retried. Cancellation is propagated and mapped only at the actor boundary.

## 8. Simulator

`SimulatedNetwork` connects real protocol codecs and engines through the same `LinkEvent`/`LinkCommand` API as Bluetooth. It does not mock reducer decisions.

```text
SimulatedNode
  runtime coordinator
  MeshEngine + NoiseSessionEngine + DeliveryEngine + SyncEngine + MediaTransferEngine
  in-memory transactional repositories
  deterministic crypto test provider or known-answer provider
  virtual clocks/scheduler
  SimulatedLinkAdapter

SimulatedNetwork
  directed links, MTU, latency, bandwidth and readiness
  seeded drop/duplicate/reorder/corrupt rules
  partitions, joins/leaves and scripted lifecycle
  global event queue ordered by (time, sequence)
```

A scenario declares seed, nodes, links, inputs, fault schedule and invariants. Failure output is a replayable trace. Shrinking removes events/faults while preserving failure. Production crypto known-answer tests remain separate from deterministic fake crypto scenarios.

Required early scenarios:

1. line and triangle public relay with no duplicate delivery;
2. simultaneous first-contact handshakes;
3. partition, durable sends, heal and eventual exactly-once logical projection;
4. fragment loss/reorder/duplicate and targeted recovery;
5. backpressure/readiness without unbounded queue growth;
6. restart with claimed outbox lease;
7. courier encounter and quota exhaustion;
8. hostile oversized/decompression/sync inputs remain bounded.

### Platform-local state machines

Determinism does not require forcing every platform lifecycle into common code. Android Bluetooth permission/retry, Wi-Fi Aware, hotspot/APK serving, Apple CoreBluetooth restoration, protected-data availability, audio sessions and notification registration may each use a platform-local reducer or serialized actor. They expose only typed availability/link/effect results to common runtime.

Such a machine must still have one state owner, explicit start/stop/close, injectable time where policy depends on time, bounded queues, stale-callback generations and deterministic unit tests. It may model platform APIs that have no counterpart; no empty common implementation is created for symmetry.

## 9. Example traces

The traces show decisions, not concrete wire implementation.

### 9.1 Public message receive and relay

```text
Mesh.BytesReceived(link=B, raw)
 -> Decode(raw)
Codec.Decoded(packet=P, exactSigningBytes)
 -> ValidateBounds(P)
 -> VerifySignature(P.sender, exactSigningBytes, P.signature)
Crypto.SignatureValid(P)
 -> seen[P.id] = now; PersistPublicHistory(P)
 -> DispatchDomain(InboundPublicMessage(P))
 -> if P.ttl > 0 and relay policy permits:
      RequestJitter(P.id, profileRange)
Entropy.Jitter(P.id, j)
 -> Schedule(relay:P.id, j)
TimerFired(relay:P.id)
 -> WriteLink(all selected links except B, P with ttl-1, unchanged signature/payload)
```

Duplicate receipt hits `seen` and produces no domain delivery or relay. TTL mutation never changes signed bytes.

### 9.2 Private message first contact

```text
Delivery.SendRequested(message=M, peer=A)
 -> Persist/confirm queued attempt; DispatchNoise(EnsureSession(A))
Noise.Absent + EnsureSession
 -> RequestEntropy/NoiseStart(initiator, generation=G); Schedule(handshake:G)
Crypto.NoiseStarted(handshakeBytes, G)
 -> Mesh.SendNoiseHandshake(A, handshakeBytes, G)
Mesh.LinkWriteAccepted (not delivery)
Mesh.InboundNoiseHandshake(A, response, G)
 -> NoiseRead(response, G)
Crypto.NoiseEstablished(remoteStatic, cipherState, G)
 -> BindPeerIdentity(remoteStatic, announced evidence); cancel timer
 -> Send authenticated peer-state if profile supports it
 -> DispatchDelivery(SessionAvailable(A,G))
Delivery.SessionAvailable
 -> Encrypt private payload for attempt
Crypto.NoiseWritten(ciphertext,G)
 -> Mesh.SendEncrypted(A,ciphertext,G)
```

Only the delivered ACK advances to `Delivered`; link write acceptance advances only the attempt submission state.

### 9.3 Send to disconnected peer: durable outbox

```text
Messenger.send(C, content)
 -> DB transaction inserts M + attempt A1 + outbox O1
DB.Committed(version=V)
 -> UI projection M=Queued
Delivery.RecoveredOrQueued(O1)
 -> QueryReachability(peer)
Reachability.None
 -> persist A1 waiting; Schedule(retry, backoff)
```

Process death loses no message. No in-memory flow is required to recreate the work.

### 9.4 Reconnect and outbox flush

```text
Mesh.PeerReachable(peer, links)
 -> Delivery.ReachabilityChanged(peer)
Delivery -> ClaimOutbox(peer, limit=N, lease=L)
DB.Claimed([O1...])
 -> ensure session; submit in stable (priority, createdAt, id) order
Link.Backpressured
 -> keep attempt/outbox; wait for ReadyToWrite(epoch)
Link.ReadyToWrite(epoch)
 -> resume one bounded command
DeliveredReceipt(M)
 -> atomic attempt receipt + M projection + complete O1
```

Repeated reconnect or receipt events are idempotent.

### 9.5 Fragmented packet receive and reassembly

```text
Mesh.Packet(fragment stream=S, index=2, count=4)
 -> validate count/index/chunk/global quotas before allocation
 -> create/update bounded stream S; Schedule(expiry:S,generation)
duplicate index=2, same bytes -> ignore
duplicate index=2, different bytes -> reject stream conflict
indices 0,1,3 arrive -> ordered concatenate; verify declared size/hash
 -> delete stream state; cancel timer; Decode(reassembled exact bytes)
Timer before completion -> evict and optionally request targeted resync
```

Outer fragments never recursively fragment without an explicit bounded rule.

### 9.6 Partition, heal and gossip synchronization

```text
t0 network partitions {A,B} | {C}
A accepts public packet P; histories persist bounded P
t1 link B-C restored
Mesh.PeerReachable -> Sync.PeerConnected(C)
Sync -> load bounded history IDs; BuildGCS; SendRequestSync(ttl=0)
C validates link-local request and budget
C -> compute missing bounded page; SendSolicited(P...)
B receives P, verifies/dedups/persists, may deliver locally
Sync sessions close after response window; no flood beyond direct peer
```

The simulator asserts convergence without duplicate logical delivery and with response bytes below budget.

### 9.7 Courier deposit, encounter and delivery

```text
Sender cannot reach recipient R
Delivery profile permits courier -> create sealed envelope E with expiry/budget
DB transaction persists E + spray budget
Sync encounters authenticated courier C advertising required capability
 -> reserve one copy transactionally; SendCourier(E,C)
C persists accepted E before ACK
later C encounters R
 -> validate recipient tag/expiry/quota; submit sealed E
R opens/authenticates E, commits message+dedup+delivery ACK atomically
C receives ACK -> tombstone/remove E
ACK eventually reaches sender -> Delivery marks M Delivered
```

A transport write, courier acceptance and recipient delivery are three distinct facts.

## 10. Test laws

Every engine must satisfy:

- same seed/events produce byte-identical effects and trace fingerprints;
- replaying a successful effect result is idempotent;
- stale generation/timer/effect results cannot mutate current state;
- state collections have configured global and per-peer bounds;
- time advances only through events;
- serialization/recovery followed by the same remaining events is observationally equivalent;
- cancellation/stop releases scheduled work and no event appears after close;
- unknown protocol input never crashes the actor;
- logical delivery projection is monotonic;
- secrets and plaintext are absent from traces.

Property/fuzz targets include codec totality, decode limits, arbitrary event sequences, fragment algebra, GCS bounds, dedup eviction, receipt reordering and failure/recovery injection at every persistence effect.

## 11. Non-goals

- A single global Redux-like state machine.
- Event sourcing every UI gesture.
- Persisting every transient BLE callback.
- Reimplementing cryptographic primitives in reducers.
- Treating coroutines, actors or flows as durable storage.
- Simulating CoreBluetooth/Android frameworks in the common simulator.
- Guaranteeing exactly-once network transmission. The guarantee is idempotent, exactly-once logical projection where the protocol supplies stable IDs.
