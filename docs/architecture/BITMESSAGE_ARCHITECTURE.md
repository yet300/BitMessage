# BitMessage Architecture

Status: architecture implemented through the Phase 4 deterministic mesh foundation
Audit date: 2026-08-11
Confidence: high for the local repository and pinned upstream snapshots; moderate where the two upstream clients disagree or work exists only in an open pull request.

## 1. Decision

BitMessage will be a durable Kotlin Multiplatform messenger with a deterministic common core, BitChat-compatible adapters, and explicit optional platform capabilities. It is not a Kotlin port of either BitChat client. Compatibility-visible behavior is preserved behind a protocol profile; product state and public APIs remain protocol-neutral.

The priority order is security correctness, BitChat interoperability, deterministic behavior, durability, explicit state ownership, incremental delivery, domain clarity, KMP sharing, performance, and convenience.

## 2. Product boundary

BitMessage owns:

- durable conversations, contacts, message content, attempts, receipts, and outbox operations;
- deterministic mesh, Noise-session, delivery, synchronization, and media-transfer decisions;
- encoding and decoding of selected BitChat wire profiles;
- transport-neutral link commands and events;
- Android and Apple adapters, application lifecycle, secure storage, notifications, and optional platform features;
- a small public Messenger API suitable for Compose and Swift callers.

BitMessage does not make these common-core responsibilities:

- CoreBluetooth, Android Bluetooth, BlueFalcon, Nostr websocket, Tor, file-system, notification, or database-driver APIs;
- Compose or SwiftUI navigation and view state;
- APK propagation on Apple or Apple restoration behavior on Android;
- unmerged upstream proposals;
- a new native BitMessage wire protocol during the compatibility phases.

## 3. Current repository assessment

The current repository is an early implementation through the deterministic mesh foundation, not yet a complete messenger.

```text
androidApp -> sharedUI -> sharedLogic -> feature:root
                         sharedLogic -> core:common
                         feature:root -> core:common
iosApp ----------------> sharedLogic -> feature:root

core:model ------------> core:foundation
protocol:bitchat ------> core:foundation, core:model
transport:api ---------> core:foundation, core:model
engine:mesh -----------> core:foundation, core:model, protocol:bitchat, transport:api
```

`sharedUI` is currently Android-only. `sharedLogic`, `feature:root`, and `core:common` target Android, iOS ARM64, and iOS Simulator ARM64. The iOS application is native SwiftUI and imports `SharedLogic`.

### Implemented deterministic core, codec, and mesh boundary

Phases 2–4 added the following narrow inward-pointing boundaries. They are not yet composed into either application shell:

```text
:core:model   -> :core:foundation
:core:testing -> :core:foundation
:protocol:bitchat -> :core:foundation, :core:model
:transport:api -> :core:foundation, :core:model
:engine:mesh -> :core:foundation, :core:model, :protocol:bitchat, :transport:api
```

`:core:testing` is test infrastructure only. No production module depends on it. `LinkId` remains owned by `:core:model`; `:transport:api` contains no BitChat protocol type. `:engine:mesh` is common Kotlin and owns a pure reducer plus a separate serialized runtime. These modules have executed Android-host and iOS Simulator KMP tests; this does not establish a native Swift source-test surface or physical-link interoperability.

### Existing-code disposition

| Area | Decision | Reason |
|---|---|---|
| Gradle wrapper, catalog, repositories | KEEP | Current builds succeed and dependency coordinates are centralized. |
| KMP convention plugin | ADAPT | Good target/source-set baseline; add narrowly scoped conventions only after repeated use. |
| `androidApp` and `iosApp` shells | KEEP | Correct platform lifecycle/composition boundary. |
| Metro final graphs and explicit binding containers | ADAPT | Correct composition direction; remove unused Android graph input and grow provider-first bindings. Domain modules remain Metro-free. |
| Decompose root contract and Swift helpers | ADAPT | Keep interface/default-component pattern; make root child models stable and add real navigation incrementally. |
| MVIKotlin factory wiring | KEEP | Available presentation mechanism, not a domain runtime. |
| `sharedUI` | ADAPT | Keep Android Compose presentation thin. Do not claim shared iOS Compose support. |
| Permissions, dispatchers, logging, JSON config, hex, geohash helpers | ADAPT | Reuse when contracts fit; do not let utility types become domain owners. |
| Protocol models/codecs | ADAPT | Phase 3 provides the narrow pure `:protocol:bitchat` codec from executable Phase 1 literals only; runtime policy, crypto, and product integration remain absent. |
| BLE, Noise, routing, persistence | DELETE as a category | None exist in this repository. There is no legacy implementation to preserve. |
| Tests | ADAPT | Phase 1 compatibility, Phase 2 foundation/model/testing, Phase 3 protocol, and Phase 4 transport/mesh suites execute real tests; other pre-existing behavior modules may still be `NO-SOURCE`. Add real tests before behavior. |
| BlueFalcon integration | DELETE as a category | Catalog availability is not integration. Introduce only behind the link adapter. |

### Historical donor repository

`/Users/yet/development/projects/bitchat` at `10feab049becf4140c8bf10e0d9428c89222840f` is a separate historical implementation. It contains extensive domain, data, crypto, transport, UI, and feature modules, but is not part of this repository and predates the audited upstream snapshots.

The architectural whole and module graph remain `REFERENCE_ONLY`/rejected as transplant sources. Phase 0.4 found narrower salvageable units: pure algorithms, domain concepts, tests, independently validated fixture candidates, and platform regression knowledge. No historical production subsystem is approved for reuse as-is, and the donor contains no BlueFalcon integration. The complete file/subsystem matrix, KMP-boundary review, 191-file test inventory, fixture promotion rules, BlueFalcon comparison, and regression ledger are in [Historical BitMessage Salvage Audit](HISTORICAL_BITMESSAGE_SALVAGE.md).

## 4. Target dependency graph

Modules are introduced only in the phase that needs them. The target is intentionally coarser than the historical donor tree.

```text
androidApp ─┬─> sharedUI ─> feature:root
            └─> sharedLogic ───────────────┐
iosApp ─────────> sharedLogic ─────────────┤
                                           ├─> feature:root
                                           ├─> data:repository ─> data:database
                                           ├─> engine:{mesh,delivery,sync,media}
                                           ├─> transport:{bluetooth,nostr}
                                           └─> crypto:noise

feature:* ───────────────> messenger:domain
data:* ─────────────────> messenger:domain, core:*
engine:* ───────────────> core:*, protocol:bitchat, crypto:api, transport:api
transport:* ────────────> transport:api, core:*
crypto:noise ───────────> crypto:api, core:*, protocol:bitchat
protocol:* ─────────────> core:foundation, core:model
messenger:domain ───────> core:foundation, core:model
core:model ─────────────> core:foundation
core:testing ───────────> core:foundation (test infrastructure only)
core:foundation ────────> Kotlin libraries only
```

`sharedLogic` becomes application/runtime composition and the Swift-facing facade. It must not become the dumping ground for domain or protocol implementation.

## 5. Module contracts

Every entry lists responsibility; allowed dependencies; forbidden dependencies; public API; test strategy; required source sets.

### Existing modules

| Module | Contract |
|---|---|
| `:core:common` | Transitional home for current utilities. Allowed: portable Kotlin libraries. Forbidden: UI, protocol policy, transport implementations, repositories. API: clocks/dispatchers/logging/permission helpers until moved. Tests: common unit tests. Source sets: current common/android/iOS. Gradually shrink; do not add product orchestration. |
| `:sharedLogic` | App runtime composition and stable Swift-facing facade. Allowed: domain, repositories, engines, transport adapters, feature root. Forbidden: raw UI and wire decisions. API: `Messenger`, lifecycle controls, root factory. Tests: composition and lifecycle tests. Source sets: common/android/iOS. |
| `:sharedUI` | Android Compose views only. Allowed: feature contracts and UI libraries. Forbidden: raw codecs, database, BlueFalcon, repositories. API: root composable. Tests: semantics/screenshot tests. Source sets: commonMain used for portable Compose code plus androidMain; no iOS target until deliberately added. |
| `:feature:root` | Decompose root navigation and screen coordination. Allowed: feature APIs and messenger API. Forbidden: transport/data details. API: `RootComponent`. Tests: navigation and lifecycle tests. Source sets: common/android/iOS. |
| `:androidApp`, `iosApp` | Platform entry points, lifecycle, final graph creation, platform permissions/restoration. Forbidden: business decisions. Tests: smoke and lifecycle tests. Source sets: platform-owned. |

### Foundation and domain

| Module | Contract |
|---|---|
| `:core:foundation` | Implemented portable kernel: immutable `Bytes`; `TimerId`, `CorrelationId`, and neutral `Generation`; `WallClock` returning `kotlin.time.Instant`; separate finite, non-durable `MonotonicTime`/`MonotonicClock`; `ScheduleTimer`/`CancelTimer`/`TimerFired` contracts; entropy request/result and `EntropySource` interface only; generic `Engine`/`Transition`; and typed, payload-excluding `TraceRecord` facts. Allowed: Kotlin libraries. Forbidden: messenger/protocol/UI and production scheduler or entropy implementations. Tests: executed common Android-host and iOS Simulator tests. Source sets: commonMain/commonTest. |
| `:core:model` | Implemented validated values only: `LinkId` and protocol/transport-visible `PeerId`. `LinkId` and `PeerId` are distinct, have no conversion, and `PeerId` is not a durable user/contact or authenticated identity. Allowed: foundation. Forbidden: protocols, storage, UI, and identity/contact semantics. Tests: executed common Android-host and iOS Simulator tests. Source sets: commonMain/commonTest. |
| `:core:testing` | Phase 1 compatibility fixtures/gate plus test-only virtual wall/monotonic time, deadline/sequence scheduler, and deterministic non-cryptographic seeded entropy. Allowed: foundation and test libraries. Forbidden: production dependencies on this module. Tests: executed Android-host and iOS Simulator tests. Source sets: commonMain/commonTest. |
| `:messenger:domain` | Conversations, participants, contacts, messages, content, attempts, receipts, policies, and use-case/repository ports. Allowed: core. Forbidden: BitChat types, wire codecs, Metro, SQL, Bluetooth, UI. API: `Messenger`, repositories and use cases. Tests: pure domain invariants. Source sets: commonMain/commonTest. |

Use `@JvmInline value class` for validated semantic identifiers where it materially prevents mixing values. Audit Swift export, serialization, nullable/generic boxing, and database adapters before committing each public type.

`IdentityId`, authenticated identity, and `SessionGeneration` are deferred to Phase 7. Phase 2 deliberately introduces neither `IdentityId` nor `SessionGeneration`.

### Protocol and crypto

| Module | Contract |
|---|---|
| `:protocol:bitchat` | Implemented evidence-first BitChat slice: immutable v1/v2 public-message packet values, bounded binary codecs, resolved announcement TLVs, raw signing/compression retention, and the `BitchatBaseline2026_08` production coverage gate. Allowed: `:core:foundation`, `:core:model`; `:core:testing` only in test scope. Forbidden: Bluetooth, crypto implementations, domain/UI, padding policy, decompression, and runtime routing. API: `BitchatCodec`, immutable wire values, `BitchatBaseline2026_08`. Tests: literal Apple/Android vectors, bounded hostile inputs, Android-host coverage report plus common Android-host/iOS Simulator tests. Source sets: commonMain/commonTest/androidHostTest. |
| `:protocol:nostr` | Nostr event/envelope representations and the deployed BitChat private envelope profile. Allowed: core and crypto API. Forbidden: websocket/Tor/UI. API: codecs and verification inputs. Tests: cross-client fixtures. Source sets: commonMain/commonTest. |
| `:crypto:api` | Primitive requests/results, key handles, signing, verification, hashing, AEAD, Noise backend port. Private key bytes do not cross the port unless the primitive requires it and storage policy permits. Forbidden: messenger, UI, transport. Tests: known-answer contracts. Source sets: commonMain/commonTest. |
| `:crypto:noise` | Deterministic Noise-session orchestration and BitChat payload wrapping; the crypto provider performs primitives. Allowed: crypto API, core, protocol. Forbidden: BLE/UI/database drivers. API: `NoiseSessionEngine`. Tests: Noise vectors, simultaneous open, replacement, timeout, stale generation. Source sets: commonMain/commonTest plus provider conformance tests. |

### Engines and transports

| Module | Contract |
|---|---|
| `:engine:mesh` | Implemented bounded packet admission, authoritative dedup, TTL, deterministic relay/source-route decisions, fragment assembly/reinjection, redacted trace, and explicit serialized runtime. Allowed: `:core:foundation`, `:core:model`, `:protocol:bitchat`, and `:transport:api`; `:core:testing` only in test scope. Forbidden: crypto implementation, BlueFalcon/platform/UI/database/domain/simulator dependencies. API: `MeshEngine`, `MeshState`, `MeshEvent`, `MeshEffect`, `MeshLimits`, `MeshRuntime`, `MeshEffectExecutor`. Tests: transition, property/adversarial, lifecycle, pressure, and Android-host coverage-gate tests. Source sets: commonMain/commonTest/androidHostTest. |
| `:engine:delivery` | Durable send attempts, route selection requests, retry/fallback/receipt policy. Allowed: domain, core, transport/crypto ports. Forbidden: database/BlueFalcon/UI. API: `DeliveryEngine`. Tests: restart and monotonic status traces. Source sets: commonMain/commonTest. |
| `:engine:sync` | GCS reconciliation, history windows, requested response budgets, courier/store-and-forward decisions. Allowed: core, protocol, domain identifiers. Forbidden: sockets/UI. API: `SyncEngine`. Tests: partition/heal, bounded response, courier traces. Source sets: commonMain/commonTest. |
| `:engine:media` | Transfer admission, manifests/chunks, verification, resume and quotas. Allowed: core/domain. Forbidden: file system, audio frameworks, BLE implementation. API: `MediaTransferEngine`. Tests: loss/reorder/corruption/restart. Source sets: commonMain/commonTest. |
| `:transport:api` | Implemented transport-neutral `LinkEvent`, `LinkCommand`, `LinkCapabilities`, and `LinkResult` contracts. `LinkId` is imported from `:core:model`; no protocol type crosses this module. Allowed: `:core:foundation`, `:core:model`. Forbidden: BitChat/domain/platform/coroutine-runtime decisions. Tests: construction, defensive ownership, and correlation laws. Source sets: commonMain/commonTest. |
| `:transport:bluetooth` | BlueFalcon 3.7.0 adapter and platform lifecycle glue. Allowed: transport API, BlueFalcon, core. Forbidden: messenger/codec/routing decisions. API: `BluetoothLinkAdapter`, explicit `start/stop/close`. Tests: fake backend contract plus Android/Apple integration tests. Source sets: commonMain only for adapter-neutral mapping, androidMain, iosMain. |
| `:transport:simulation` | Multi-node simulated link network with loss, duplication, reordering, corruption, partitions, MTU and backpressure. Allowed: transport API/core testing. Forbidden: platform BLE. API: `SimulatedNetwork`. Tests: deterministic replay. Source sets: commonMain/commonTest. |
| `:transport:nostr` | Relay connections, subscription lifecycle, reconnect and proxy boundary. Allowed: transport API, protocol Nostr, Ktor. Forbidden: messenger/UI policy. API: Nostr commands/events. Tests: scripted websocket and reconnect tests. Source sets: commonMain/androidMain/iosMain as required. |

### Data and features

| Module | Contract |
|---|---|
| `:data:database` | Schema, migrations, transactions, query primitives, encryption-at-rest adapters, panic wipe. Allowed: domain/core plus database driver. Forbidden: UI/transport. API: internal transactional DAO boundary. Tests: migration, corruption, constraints, rollback. Source sets: commonMain where driver permits plus platform secure-key adapters. |
| `:data:repository` | Domain repository implementations and atomic message+outbox operations. Allowed: domain/data database/core. Forbidden: BlueFalcon/UI/wire codecs. API: repository bindings and recovery loader. Tests: persistence contracts and restart scenarios. Source sets: commonMain plus platform adapters. |
| `:feature:*` | Decompose components and MVIKotlin stores for conversations, chat, contacts, nearby, verification, settings. Allowed: messenger/domain APIs and sibling feature contracts only through root. Forbidden: raw wire/data/transport. API: component contracts. Tests: store/component and UI semantics. Source sets: common for component/store, presentation platform source sets. |

Do not create `:protocol:binary`, `:protocol:fixtures`, `:data:outbox`, `:transport:routing`, or `:crypto:identity` initially. Split them only after a second independent consumer or build-time boundary justifies the cost.

## 6. Architecture rules

1. Messenger domain does not depend on BitChat or any future native protocol.
2. Engines do not depend on BlueFalcon, CoreBluetooth, Android Bluetooth, Ktor, SQL, Compose, or SwiftUI.
3. Protocol codecs do not perform I/O, mutate global state, or read time/randomness.
4. UI files render immutable state and emit intents. The call path is UI -> Component -> Store/use case -> repository/runtime.
5. Domain and engine modules contain no Metro annotations. Final Android/Apple graphs assemble explicit binding containers.
6. Every runtime owner exposes explicit lifecycle. No coroutine is launched from a constructor or `init` without a containing owner that can stop and close it.
7. Navigation changes occur on the main thread and Decompose state is created once, not from computed getters.
8. A durable fact is persisted before its success is announced. Durable events are not represented only by `SharedFlow`.
9. Changing retry transport never regresses the logical message status. Attempts are append-only facts; status is a monotonic projection.
10. Unknown protocol capability bits and forward-compatible TLVs are preserved where the deployed protocol does so.
11. Decode limits precede allocation/decompression. Signature verification uses the exact received signing transcript, including preserved compressed bytes when required.
12. Optional platform capability absence is explicit, not emulated with a misleading no-op.

These rules should first be enforced through module dependencies and tests. Add architecture-test tooling only if ordinary Gradle boundaries fail to catch a real recurring violation.

## 7. Deterministic engine architecture

Phase 2 introduced the generic reducer kernel below. Phase 4 now implements its first production use, `MeshEngine`, plus the separate `MeshRuntime` imperative shell. There is still no production entropy provider, physical transport, cryptographic implementation, persistence, or application composition for the mesh path. Test-only `VirtualScheduler` and non-cryptographic `SeededEntropy` remain in `:core:testing`:

```kotlin
fun reduce(state: State, event: Event): Transition<State, Effect>
```

`Transition` preserves ordered effects and typed `TraceRecord` values with defensive list ownership. `TraceRecord` permits only a validated transition name, optional `CorrelationId`, closed decision/size kinds, and nonnegative counts; its schema has no raw payload or free-form diagnostic field. Callers must provide only non-secret names and correlation IDs because those identifier strings are not sanitized by the generic type.

`MeshEngine.reduce` commits immutable `MeshState` and emits ordered `MeshEffect` and redacted trace records. Decode, SHA-256, signature verification, entropy, timers, relay encoding, link writes, and public-payload publication are effect boundaries; their correlated `MeshEvent` results carry `Generation` and explicit `MonotonicTime`. `MeshRuntime` creates no jobs or channels in its constructor, then on `start` creates one reducer actor, one ordered effect worker, bounded event/effect/trace channels, and correlated timer jobs. `trySubmit` reports `Accepted`, `Backpressured`, or `Closed`; stop retains only unexpired admitted packet IDs, later start increments generation, and close is permanent. Detailed contracts, bounds, TTL, relay, fragment, and lifecycle behavior are in `STATE_MACHINE_DESIGN.md`.

There is no giant application reducer:

- `MeshEngine` owns link-visible mesh protocol state.
- `NoiseSessionEngine` owns one authenticated session state per peer/generation.
- `DeliveryEngine` owns durable logical send and attempt policy.
- `SyncEngine` owns reconciliation and courier policy.
- `MediaTransferEngine` owns bounded transfer state.
- repositories own durable records; the runtime coordinator owns lifecycle, not domain policy.

## 8. BlueFalcon 3.7.0 boundary

The audited `3.7.0` tag and `master` are the same commit (`0338bb6b4ef6653179c5363946986ee838cd3c6f`). Use the engine-based central API and the separate peripheral artifact; do not use deprecated facade APIs.

The future adapter must collapse BlueFalcon discovery/connection/service/restoration callbacks into the implemented narrow link boundary before anything reaches `:engine:mesh`:

```text
LinkEvent: Opened, ReadinessChanged, PayloadReceived, Closed

LinkCommand: Write, Close

LinkResult: Written, Backpressured, PayloadTooLarge, Disconnected,
            Unsupported, Failed
```

Scanning, discovery, connection establishment, service resolution, restoration, central/peripheral role, and GATT request handling remain adapter lifecycle concerns. They are not mesh commands or events.

No BlueFalcon type crosses `:transport:bluetooth`. The runtime owns `BlueFalcon` and `BlueFalconPeripheral` instances and calls `start`, `stop`, and `close` explicitly. While BlueFalcon PR #254 (ADR 0011 `blue-falcon-plugin-mesh`) demonstrates concurrent Central + Peripheral lifecycle, `peripheral.requests.collect` write handling, and `pendingConnections` concurrency guards (protecting iOS CoreBluetooth against multiple `connect()` calls when `central.peripherals` re-emits on RSSI changes), its custom 81-byte `MeshFramer` and ad-hoc routing are not used; all mesh framing, TTL, and deduplication remain owned by `:protocol:bitchat` and `:engine:mesh`.

`QueuePlugin` is an ATT write/notification queue only. It provides bounded per-session FIFO, total-byte limits, fair session scheduling, readiness handling and typed outcomes. It does not provide fragmentation, protocol ACKs, persistence, retry across disconnect, deduplication, ordering across links, or delivery semantics. Those belong to protocol and engines.

Apple state restoration requires an early, stable manager with the exact restoration identifier and appropriate Info.plist background mode. Android GATT server and scanning permissions/lifecycle remain in the Android adapter.

## 9. Messenger and persistence model

Core entities include `Conversation`, `Participant`, `Contact`, `Message`, `MessageContent`, `DeliveryAttempt`, `Receipt`, `OutboxOperation`, and `Attachment`.

A message has a stable logical ID independent of packet IDs, fragments, transport attempts, routes, or protocol profile. A delivery attempt records protocol profile, transport, route, session generation, timestamps, failure and receipt facts.

Recommended logical projection:

```text
Draft -> Queued -> Sending -> Sent -> Delivered -> Read
                   \-> Failed (retryable/terminal metadata)
Queued/Sending/Sent -> Expired or Cancelled
```

Retries add attempts; they do not move a message backward. A later receipt may advance a message even after an earlier attempt failed.

The database transaction that accepts an outgoing message inserts the message and its first outbox operation atomically. Runtime effects claim operations with a lease, persist attempt outcomes, and recover expired leases after restart. Incoming deduplication, message insert, receipt state, and any generated ACK operation are also transactional.

Encryption at rest must cover sensitive payload columns and attachment metadata using a platform-secured database/content key. Database-driver encryption alone is insufficient if media and keys remain exposed. Migrations are explicit and rollback-tested. Panic wipe orders transport stop, durable queue drain/cancel, database/media/key deletion, and identity regeneration without racing pending writes.

## 10. Identity and crypto

The domain distinguishes:

- stable local account identity and device installation identity;
- protocol identity/key material;
- rotating or link-visible peer identifiers;
- authenticated peer identity, aliases, verification and capability evidence.

Peer aliases are evidence attached to an authenticated identity, not primary keys for conversations. This is required before adopting peer-ID rotation.

Crypto providers expose primitives and opaque key handles. Protocol code defines signing transcripts. Noise orchestration owns handshake/session states but does not implement cryptographic primitives. Secure storage, biometric policy, Android Keystore/Keychain behavior, entropy and key deletion are platform adapters.

The shipping BitChat peer ID is the first eight bytes of SHA-256 over the Noise static public key and is linkable. Apple `announceV2` and Android draft rotation work are parsed/discarded experiments, not a shipping identity model. BitMessage designs durable identities so rotation can be added, but does not emit rotation traffic until coordinated vectors and negotiation are merged upstream.

## 11. Platform capabilities

Capabilities are registered explicitly:

```kotlin
interface PlatformCapability
interface WifiAwareCapability : PlatformCapability
interface AppDistributionCapability : PlatformCapability
interface BluetoothRestorationCapability : PlatformCapability
interface NotificationCapability : PlatformCapability
```

Availability is data (`Supported`, `Unavailable(reason)`, `Disabled(policy)`), not an exception or fake implementation.

Common candidates are protocol semantics, engines, persistence contracts, domain policy, simulator, capability negotiation and public APIs. Android owns Wi-Fi Aware, APK download/verification/serving, Wi-Fi Direct/hotspot, battery exemptions and Android notifications. Apple owns CoreBluetooth restoration/background declarations, Apple protected files/keychain policy and Apple notifications. Both own final DI graphs and lifecycle adapters.

APK propagation is an optional Android product capability, not a mesh-engine feature. Its security boundary includes trusted manifests, source ranking, resumable download, signing-certificate verification, user authorization, rate limits and separate web/hotspot lifecycle. It cannot be represented as a generic file transfer.

## 12. Presentation and public API

The presentation decision is explicit and asymmetric by design:

```text
Android -> Jetpack Compose / Compose UI
Apple   -> native SwiftUI
```

There is no shared Compose UI target for iOS. Kotlin Multiplatform shares messenger domain, protocol, deterministic state machines, runtime coordination, repositories, and business logic. `sharedLogic` exposes a Swift-friendly application/messenger facade and observation bridge; it does not export Compose UI or raw engine internals. This decision may change only through an explicit architecture revision, not because the historical donor contains shared Compose screens.

Compose and SwiftUI consume the same protocol-neutral facade:

```kotlin
interface Messenger {
    val state: StateFlow<MessengerState>
    fun conversations(): Flow<List<ConversationSummary>>
    fun conversation(id: ConversationId): Flow<ConversationView>
    suspend fun send(conversationId: ConversationId, content: MessageContent): MessageId
    suspend fun markRead(conversationId: ConversationId, through: MessageId?)
    suspend fun retry(messageId: MessageId)
    suspend fun deleteConversation(conversationId: ConversationId)
}

interface MessengerRuntime {
    val state: StateFlow<RuntimeState>
    suspend fun start()
    suspend fun stop()
    suspend fun close()
}
```

The exported Swift surface should favor concrete data classes/sealed projections and suspend bridges tested from Swift. Avoid exposing Kotlin generic-heavy engine internals, raw byte arrays, inline classes whose export is awkward, or framework-specific flows without a stable observation bridge.

## 13. Runtime lifecycle

The app graph constructs a single `MessengerRuntime`. Start is idempotent and performs recovery before enabling links. Stop rejects new work, stops transport intake, checkpoints/drains bounded work and releases radios. Close permanently cancels owned scopes and closes BlueFalcon/database resources. Restart creates or reinitializes runtime-owned actors according to an explicit policy; no global singleton may silently retain stale session state.

Runtime uses structured concurrency. Each serialized engine actor may own a child scope only because its lifecycle and failure policy are explicit. Broad exception handling never swallows cancellation. `StateFlow` represents current state; one-shot transient effects use bounded channels or acknowledged durable operations according to replay/durability requirements.

## 14. Future native BitMessage protocol boundary

A native BitMessage profile is deferred until the compatibility baseline is stable. The architecture prepares for it by keeping domain IDs/content independent of wire packets and by recording the protocol profile on each delivery attempt.

Any future profile requires explicit authenticated negotiation, a reserved version/profile identifier, bidirectional downgrade tests, coexistence with queued BitChat attempts, and a migration plan for contacts/receipts/media. It must not be selected by heuristic parsing, app version strings, platform type or an unauthenticated capability hint. Failure to negotiate leaves the message on an allowed existing route or in the durable outbox; it never silently changes security properties.

## 15. Verification and architectural Definition of Done

An architecture increment is complete only when:

- dependencies point inward and forbidden imports are absent;
- common tests contain real executed tests, not `NO-SOURCE` tasks;
- protocol changes pass literal golden vectors from both upstream clients;
- engine changes pass deterministic traces and simulator replay;
- Android builds and relevant KMP/iOS framework links succeed;
- persistent changes include migration, restart, rollback and corruption tests;
- platform features fail explicitly when unavailable;
- compatibility-visible changes document their upstream evidence and profile behavior;
- the change can be reverted without an on-disk or on-wire ambiguity.

The executable roadmap is in `../IMPLEMENTATION_PLAN.md`; compatibility evidence is in `BITCHAT_COMPATIBILITY.md` and `UPSTREAM_FEATURE_MATRIX.md`.
