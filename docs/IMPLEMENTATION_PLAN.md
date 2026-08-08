# BitMessage Implementation Plan

Status: executable roadmap; Phases 0, 0.4, 1, 2, and the evidence-first Phase 3 slice are complete; Phase 4 not started
Baseline date: 2026-08-07  
Rule: every task leaves the repository buildable, tested and reviewable. No big-bang migration.

Historical implementation inputs are governed by [Historical BitMessage Salvage Audit](architecture/HISTORICAL_BITMESSAGE_SALVAGE.md). Its architecture and bytes are not authoritative; only the task-specific algorithms, scenarios, platform knowledge, and independently validated fixture candidates listed below may influence implementation.

## 1. Milestone graph

```text
0 Audit
  -> 1 Compatibility baseline
      -> 2 Foundation
          -> 3 BitChat wire
              -> 4 MeshEngine
                  -> 5 Simulator
                      -> 6 Bluetooth/BlueFalcon
              -> 7 Identity/Noise -------------------+
          -> 8 Messenger/persistence ----------------+-> 9 Reliable delivery
              5 + 9 -> 10 Sync/courier
              3 + 7 + 8 -> 11 Nostr
              8 + runtime facade -> 12 Presentation
              6 + 7 + 8 + 9 -> 13 Media
              stable 6/11/12/13 -> 14 Platform capabilities
              10/11/13 + refreshed upstream -> 15 Upstream adoption
              all selected release scope -> 16 Interop/security hardening
```

Phases show dependency order, not a promise that all features ship in one release. Tasks within a phase are ordered unless stated otherwise.

## 2. Green gate evolution

| Gate | Begins | Required on ordinary PRs |
|---|---:|---|
| G0 Build | 0 | `:androidApp:assembleDebug`, affected module tests, `:sharedLogic:linkDebugFrameworkIosSimulatorArm64`; no new warnings without issue. |
| G1 Real tests | 1 | At least one executed test in every new behavior module; fail if selected tasks are `NO-SOURCE`. |
| G2 Compatibility | 1 | Neutral fixture schema, provenance validation, literal golden tests; codec changes require Apple+Android evidence. |
| G3 Determinism | 2 | Virtual time/entropy and reducer replay tests. No sleep/time/network in common tests. |
| G4 Simulator | 5 | Required multi-node scenarios replay with fixed seed; bounded queues/state assertions. |
| G5 Persistence | 8 | Fresh schema, every migration, rollback/failure injection, restart and corruption tests. |
| G6 Platform | 6/11 | Android unit/compile and iOS KMP link; scripted adapter tests. Instrumented/simulator tests for adapter changes when available. |
| G7 Fuzz/property | 16 | Bounded codec/state-machine fuzz corpora, regression seeds, memory/time caps. |
| G8 Physical release | 16 | Release-candidate only: Android↔Apple↔BitMessage device matrix. Not an ordinary PR gate. |

Initial verification on 2026-08-07: G0 passes. Phase 1 added `:core:testing:compatibilityCheck`, which parses JUnit XML and fails unless its selected Android-host suite executes more than zero tests. At Phase 1 completion, older behavior modules still had `NO-SOURCE` test targets; Phase 2 subsequently added executed foundation/model/testing suites, while later behavior modules remain future-phase work.

CI should use the repository's existing checks first. No CI configuration currently exists. Add one workflow only after the local commands are stable; do not add architecture-analysis tools merely to create activity.

## 3. Phase 0 — Repository and upstream audit

- **Goal:** establish exact local/upstream evidence and make architecture decisions reviewable.
- **Scope:** inventory current and historical BitMessage; pin Apple, Android and BlueFalcon snapshots; audit main plus important open/draft/merged/closed PRs; classify divergences and old code; publish the canonical architecture documents and the subsystem-level historical salvage audit.
- **Non-goals:** production modules, dependency changes, source migration, BLE/Noise/UI work.
- **Dependencies:** none.
- **Modules affected:** documentation only.
- **Core types/interfaces:** none.
- **State owner:** documentation snapshots own audit facts; upstream state remains external.
- **Platform responsibilities:** record both platform implementations without treating either as universal.
- **Tests:** links/SHAs, document consistency, local build baseline.
- **Compatibility gate:** every protocol claim cites pinned implementation/test evidence and labels PR-only work.
- **Completion criteria:** the canonical documents and historical salvage audit exist; all required sections, matrices, phases and open questions are present; production source unchanged.
- **Risks:** upstream moves after snapshot; stale prose; local historical donor mistaken for current source.
- **Rollback:** revert documentation files only.

### Tasks

| ID | Goal and scope | Dependencies | Expected files | Tests / green gate | Definition of Done |
|---|---|---|---|---|---|
| 0.1 | Inventory current repository, module graph, source sets, active dependencies, tests and build status. | None | Architecture docs | G0 commands; Git diff | Exact local SHA and actual `NO-SOURCE` state recorded. |
| 0.2 | Pin/audit Apple, Android, BlueFalcon and significant PRs; classify differences. | 0.1 | Compatibility + matrix docs | Snapshot and PR metadata validation | SHAs, states, evidence and uncertainty recorded. |
| 0.3 | Publish architecture, engines and roadmap; classify historical donor. | 0.1–0.2 | Original five canonical docs | Cross-link/heading review, G0 | No production changes; coding tasks need no redesign. |
| 0.4 | Audit historical donor subsystem-by-subsystem, including KMP boundaries, all tests/fixture candidates, regressions and custom BLE versus BlueFalcon 3.7.0. | 0.3 | `architecture/HISTORICAL_BITMESSAGE_SALVAGE.md` plus links/roadmap annotations | Exact donor SHA/clean status; document/test-inventory checks | Donor remains unchanged; every reusable input is classified without starting Phase 1. |

### Historical salvage inputs by implementation task

Only the items in this table are approved historical inputs. “Fixture candidate” always means the Phase 1 pinned-upstream validation flow, never direct copying into expected test output.

| Task(s) | Historical salvage inputs | Required guardrail |
|---|---|---|
| 1.1 | Historical golden-test provenance mistakes and the distinction between generated, hand-derived, and captured bytes. | Schema must label historical origin as candidate-only and record independent Apple/Android validation. |
| 1.2 | Plain v1 broadcast/private, v2 routed, legacy announce, absent/empty capabilities candidates. | Both pinned clients must accept/emit as required; historical Kompress bytes are excluded. |
| 1.3 | Noise transcript, fragment, request-sync/GCS, Nostr embedded, crypto KAT, private media/voice, malformed/bomb candidates; advanced Apple-feature candidates. | Validate limits and profile support independently; unsupported features stay decode-only/deferred. |
| 2.2 | Packet/logical ID separation, fixed-width peer bytes, `ConversationId`, fingerprint and canonical-conversation concepts; odd-hex inconsistency as a negative case. | New validated types reject malformed size/encoding and remain Swift-usable. |
| 2.3 | Injected clock in `RequestSyncManager`, injected jitter in `RelayController`/`RelayReconnectPolicy`, and historical timer/race scenarios. | Do not port constructors that launch jobs or read `Clock.System`/`Random` internally. |
| 2.4 | Pure-ish relay/reconnect/connection-selection decisions and regression traces. | Express entropy/timers/I/O as correlated effects and preserve cancellation. |
| 3.1 | Capability minimal-LE algorithm, absent-vs-empty semantics, unknown-bit/TLV requirements, historical mutable-packet failure modes. | Immutable raw representation and unknown preservation; current type registry only. |
| 3.2 | Binary cursor/endian helpers and protocol guard tests. | One strict malformed-input policy, pre-allocation bounds, typed errors. |
| 3.3–3.4 | Plain historical packet vectors and decoder/encoder boundary cases. | Neutral literals need dual-upstream provenance; no self-generated expected bytes. |
| 3.5 | Historical v2 route codec/tests and neighbor-TLV dispute evidence. | Do not choose count-byte semantics from donor code; isolate unresolved profile behavior. |
| 3.6 | TTL-zero signing transcript pattern, packet-ID field sensitivity, RSR logical-origin tests. | Verify against current literal signatures and retain original signed representation. |
| 3.7 | Compression bomb guards, cross-compressor test shape, and the donor's lossy decompress/recompress defect. | Preserve received compressed bytes and use conservative cap; discard donor-generated compressed goldens. |
| 3.8 | Baseline payload/TLV algorithms and tests; historical advanced codecs as decode/deferred evidence. | Rebuild from pinned inventory; authenticated peer-state `0x21` and media assignments follow current profile. |
| 3.9 | Historical golden families and their missing independent authority. | Report candidate provenance and cross-client result separately. |
| 4.1 | `BleRadioLink`/bearer boundaries as negative input and logical-origin/link-registry scenarios. | Only `LinkEvent`/`LinkCommand`/`LinkResult` cross inward; no callback delegate graph. |
| 4.2 | Packet-ID/dedup/TTL/LRU ideas; RSR/self-loop/direct-announce tests; forged-packet dedup-poison defect. | Authenticate before mutating dedup; one serialized owner and explicit bounds. |
| 4.3 | `RelayController`, `RoutePlanner`, fanout, local-degree TTL clamp and scheduled-duplicate cancellation scenarios. | Immutable snapshot, deterministic tie-breaking, injected entropy/timers, current profile. |
| 4.4 | Fragment calculation, quotas, contiguous reassembly, reorder/duplicate/1 MiB/RSR tests. | Reject conflicting duplicates; virtual expiry/entropy; no manager scope. |
| 4.5 | FIFO/actor-bound/lifecycle/cancellation and frame-loss regressions. | Explicit start/stop/close/generation behavior; no constructor launches. |
| 5.1 | MTU chunking, notify readiness, outbound-gone, directed-spool and frame-assembler scenarios. | Simulate link facts only; do not reproduce custom GATT implementation. |
| 5.2–5.3 | Mesh network, duplicate relay, redundant-link, partition/reconnect and first-DM-before-handshake cases. | Fixed seed/virtual time; assert global bounds and logical exactly-once projection. |
| 6.1 | Exact current BitChat service/characteristic UUID knowledge; categorical finding that donor has no BlueFalcon/QueuePlugin integration. | Use BlueFalcon 3.7.0 engine/peripheral/queue APIs, never donor GATT scaffolding. |
| 6.2 | Android central and Apple central lifecycle quirks, RSSI/connection scheduler, stale link and frame assembly tests. | Rewrite as adapter mapping over BlueFalcon typed outcomes. |
| 6.3 | CCCD/subscription, multi-central mapping, targeted-notify, MTU and peer-bind regression knowledge. | `PeripheralSession`/session ID is `LinkId`, never authenticated peer ID. |
| 6.4 | Historical bounded priority/drop/readiness/spool tests. | QueuePlugin owns ATT FIFO/backpressure only; DeliveryEngine/outbox owns reliability. |
| 6.5 | Grant/Bluetooth permission contract, foreground/power/location-off crash knowledge. | Android-only adapter/app lifecycle; no common fake platform capability. |
| 6.6 | Stable CoreBluetooth restore IDs, restored-session and notify-ready scenarios. | Early stable BlueFalcon manager; restoration restores links, not Noise/mesh state. |
| 7.1 | Ed25519/SHA/Base64 KATs, fixed Noise XX transcript and provider-parity test strategy. | Independently validate vectors; opaque handles and vetted providers. |
| 7.2 | Stable fingerprint, alias/canonical conversation, verified-evidence and panic-wipe domain concepts. | Split identity, alias, trust, capability and key material; no peer-ID primary key. |
| 7.3 | XX 32/96/64 transcript, replay-window scenarios, cipher serialization and timeout cases. | Deterministic reducer/generation; donor's 48-byte message-3 constant is rejected. |
| 7.4 | Simultaneous-open lexicographic collision, stale handshake replacement and pending first-message tests. | Explicit generation and virtual timer; durable pending work remains in outbox. |
| 7.5 | Announce/signing/static-key binding, RSR author/hop distinction and capability downgrade lessons. | Sensitive capability requires authenticated session evidence, not announce alone. |
| 7.6 | KSafe/platform store tests, close/reopen, key deletion and no-resurrection wipe scenarios. | Platform conformance on Android/Apple and cancellation-safe wipe order. |
| 8.1 | `ConversationId`, conversation/message/contact/attachment/retention/status semantics and pure use-case tests. | Recreate protocol-neutral entities with attempts/receipts; do not port wire models. |
| 8.2 | SQLDelight constraints, quotas, encrypted driver seams and migration scenarios. | Fresh schema v1; historical migrations/wire JSON are requirements evidence only. |
| 8.3 | Historical destructive outbox drain and async DB mirror as explicit crash-loss counterexamples; DAO transaction tests. | Atomic durable operations with failure injection at every statement boundary. |
| 8.4 | FIFO/cap/TTL/retention/restart scenarios. | Lease claims, idempotent completion, bounded queues; no delete-before-send. |
| 8.5 | Repository/user-state requirements and the global `AppStateStore` dual-authority failure. | Database/repositories are authority; Swift-friendly facade exposes projections only. |
| 8.6 | Panic-wipe, pending worker, database reopen and secure-store tests. | Stop effects before erase and prove a queued worker cannot resurrect data. |
| 9.1 | `DeliveryStatusPolicyTest`, receipt ordering, local-echo and reachability scenarios. | Status is monotonic projection over durable attempts/receipts. |
| 9.2 | Outbox restart/cap/FIFO tests and destructive-drain defect. | Lease heartbeat/recovery and bounded concurrency. |
| 9.3 | Queue-until-Noise-established, directed spool, link loss, relay return and route-selector tests. | Correlate attempt/session/link generations; retain logical work durably. |
| 9.4 | Delivered/read dedup, seen-store bounds, spoof/RSR identity lessons. | Only authenticated receipt advances state; database transaction is authority. |
| 10.1 | GCS Golomb-Rice/covered-prefix algorithm, public cache quotas and tests. | Add cross-client unsigned hash-to-range vector before port. |
| 10.2 | Solicitation window, response throttle/rate limits, announce-capacity separation and no-relay request policy. | Pure SyncEngine state with anti-amplification budgets. |
| 10.3 | Fragment-filter, partition/recovery, announce-storm and targeted resync scenarios. | Current extension profile and deterministic simulator. |
| 10.4 | Courier/prekey storage, replay, freshness, quota and handover scenarios. | Decode/local simulation only until refreshed Android counterpart/capability evidence. |
| 11.1 | Nostr event/Bech32/Schnorr, gift-wrap/embedded BitChat and tamper candidates. | Call it the deployed proprietary BitChat profile; verify all accepted events. |
| 11.2 | `PendingEventQueue`, pure reconnect policy, connected-but-busy, reentrant callback and network-reprobe tests. | Socket adapter state is separate from durable delivery and uses virtual time in tests. |
| 11.3 | Geohash presence/DM ingest, alias/conversation and signature-rejection scenarios. | Persist cursor/domain facts; unverified events never reach domain. |
| 11.4 | Historical route selector, reachability and Tor activation lessons. | Delivery engine owns route; explicit proxy provider prevents bypass. |
| 12.1 | Decompose root stack/configuration/lifecycle tests. | Rebuild around current Messenger lifecycle; historical shared Compose graph is rejected. |
| 12.2–12.4 | Historical store/component tests as user-flow requirements for conversations, chat, contacts, verification and settings. | Rewrite tests against facade/platform presentation; old executors/repositories are architecture-specific. |
| 12.5 | Historical iOS Compose actuals/stubs and duplicate shell state as deletion inventory. | Android Compose only; Apple native SwiftUI only. |
| 13.1–13.2 | Attachment/voice repository, fragment/concurrency/backpressure, file-limit and hash scenarios. | Media engine/file store own bounded transfer; QueuePlugin is not reliability. |
| 13.3 | Private-file outer packet, voice burst/frame and media alias candidates. | Validate both pinned clients; canonical `0x20`, `0x09` decode-only, authenticated capability. |
| 13.4–13.5 | Historical Android/iOS capture/playback codec and lifecycle knowledge; voice ingress ephemerality tests. | Keep capture/playback platform-native; no shared Compose iOS code. |
| 14.2 | Wi-Fi Aware support-floor/location-off crash and alternate-link knowledge. | Android optional adapter after BLE baseline; same link contract/profile bytes. |
| 14.5 | Apple restoration IDs, restore/notify-ready and redundant-link scenarios. | Restoration capability remains Apple-specific and generation-safe. |
| 15.2 | Donor's decompress/recompress representation loss and compression interop test shape. | Activate only after paired upstream resolution/literal foreign-DEFLATE vector. |
| 15.3–15.4 | Courier/prekey/group/vouch/board/ping/gateway/carrier codecs, stores, quotas and tests. | Re-audit current upstream; per-feature profile/capability gate; no parity bundle. |
| 15.5 | Fingerprint/alias mapping and canonical-conversation tests, plus donor's removed timed rotation. | Authenticated migration/dual-read; donor rotation policy is rejected. |
| 16.1 | Protocol guards, bomb/truncation/malformed TLV/fragment/Nostr corpora. | Bound fuzz resources and retain minimized regression seeds. |
| 16.2 | Historical regression ledger: logical origin, relay cancellation, announce storm, first DM, queue readiness, concurrency/cancellation. | Convert each to deterministic event-sequence/simulator property. |
| 16.3 | Persistence ordering, failed flush, destructive drain, corruption/reopen and panic-wipe scenarios. | Fault injection plus no-resurrection proof. |
| 16.4 | Android permission/power/location and Apple restoration/background/notify-ready scenarios. | Platform device/simulator tests and measured budgets. |

## 4. Phase 1 — Compatibility baseline

**Status: COMPLETE (2026-08-07).** Phase 1 established 46 checked-in fixtures, including 14 reciprocally accepted dual-upstream literals, 17 resolved hostile/reject cases, and 15 `BLOCKED_BY_PROTOCOL_DECISION` drift/security-limit cases. It also added five deferred regression scenarios, a pinned reproduction harness, and an offline CI gate. At Phase 1 completion, no production codec or Phase 2 module had started; Phase 2 later added the independent foundation/model/testing kernel without changing this evidence.

- **Goal:** turn upstream archaeology into executable, implementation-independent contracts.
- **Scope:** protocol inventory, fixture manifest/schema, neutral literal fixtures, pinned upstream harness instructions, compatibility profiles and CI test discovery.
- **Non-goals:** new production codec, behavior replacement, physical BLE.
- **Dependencies:** Phase 0.
- **Modules affected:** initially `:core:testing` or a test-fixture-only source set; create `:protocol:bitchat` only when fixture consumers require it.
- **Core types/interfaces:** `FixtureId`, `FixtureManifest`, `CompatibilityProfileId`, `ExpectedOutcome`.
- **State owner:** immutable checked-in fixtures; no runtime state.
- **Platform responsibilities:** thin Apple/Android fixture exporters/runners remain upstream or test tooling; neutral artifacts are common.
- **Tests:** schema validation, hashes, duplicate IDs, upstream provenance, selected fixtures decoded by pinned harnesses.
- **Compatibility gate:** expected bytes are literal and not generated by BitMessage code.
- **Completion criteria:** both upstream clients contribute passing and failing vectors; G1/G2 run locally and in first CI workflow.
- **Risks:** copyrighted/unstable fixture import, self-generated expectations, upstream harness drift.
- **Rollback:** fixtures and workflow are additive; revert without production impact.

### Tasks

| ID | Goal and scope | Dependencies | Expected files/modules | Tests / green gate | Definition of Done |
|---|---|---|---|---|---|
| 1.1 | **Complete.** Define fixture schema, provenance, immutable hash and profile naming. | 0.3 | `:core:testing`, `compatibility/schema` | Manifest parser and canonical-hash tests; G1 | Invalid/missing provenance fails. |
| 1.2 | **Complete.** Import minimal outer packet and announce literals from Apple and Android. | 1.1 | `compatibility/BitchatBaseline2026_08` | Independent hashes, semantic assertions, reciprocal pinned acceptance; G2 | Seven producer fixtures from each client. |
| 1.3 | **Complete for the Phase 1 preparation scope.** Add hostile families and explicit drift blockers for signing, compression, Noise, fragment, sync and Nostr. | 1.2 | Fixture and scenario manifests | Typed reject codes, limits, blocked metadata | Production decode/crypto assertions remain correctly deferred to their implementation phases. |
| 1.4 | **Complete.** Add pinned upstream exporter/acceptance sources and reproduction scripts. | 1.2 | `tools/upstream-compat` | All four producer/reciprocal-acceptance paths reproduced at exact SHAs | Ordinary PR CI is offline with respect to upstream repositories. |
| 1.5 | **Complete.** Add initial CI green gate for real tests, Android build and iOS framework link. | 1.1 | `.github/workflows`, `tools/ci` | G0–G2; JUnit count must be greater than zero | `:core:testing` cannot pass the compatibility gate as `NO-SOURCE`. |

## 5. Phase 2 — Foundation

**Status: COMPLETE (2026-08-07).** Phase 2 added only the validated deterministic kernel described here. Phase 3 later added a separate pure protocol module without changing this scope.

- **Goal:** provide deterministic, validated primitives before protocol code grows.
- **Scope:** new `:core:foundation` and `:core:model`, plus the existing `:core:testing`; `Bytes`; `TimerId`, `CorrelationId`, and neutral `Generation`; wall/monotonic time contracts; scheduler and entropy contracts; generic reducer/transition/trace primitives; virtual runtime.
- **Non-goals:** packet codecs, domain entities, transport or crypto implementation; `IdentityId`, authenticated identity, and `SessionGeneration` (all deferred to Phase 7); production scheduler or entropy provider; concrete engines/effect executors.
- **Dependencies:** Phase 1 fixture conventions.
- **Modules affected:** `:core:model -> :core:foundation`; `:core:testing -> :core:foundation`. No production module depends on `:core:testing`.
- **Core types/interfaces:** immutable `Bytes`; `TimerId`, `CorrelationId`, `Generation`; `WallClock` returning `kotlin.time.Instant`; finite non-durable `MonotonicTime`/`MonotonicClock`; `ScheduleTimer`/`CancelTimer`/`TimerFired`; entropy request/result plus `EntropySource` interface; `Engine`/`Transition`; and typed redacted trace facts.
- **State owner:** scheduler owns virtual queue; values are immutable.
- **Platform responsibilities:** production clock/secure-entropy adapters later; this phase supplies contracts and test implementations only.
- **Tests:** executed Android-host and iOS Simulator KMP tests for boundary/value semantics, virtual scheduling order, deterministic replay, cancellation, transition equality, and trace redaction. The verified `:sharedLogic:linkDebugFrameworkIosSimulatorArm64` task is a KMP interoperability smoke, not a native Swift source test.
- **Compatibility gate:** byte values remain unsigned/exact and never normalize wire data implicitly.
- **Completion criteria:** complete. The modules have no platform dependencies; selected tests execute on Android-host/iOS Simulator targets; G0–G3 checks pass.
- **Risks:** excessive abstraction, inline-class Swift boxing, conflating wall and monotonic time.
- **Rollback:** new modules are unreferenced by production; remove settings entries and modules.

### Tasks

| ID | Goal and scope | Dependencies | Expected files/modules | Tests / green gate | Definition of Done |
|---|---|---|---|---|---|
| 2.1 | **Complete.** Add coarsely scoped foundation/model/testing modules and dependency rules. | 1.5 | settings, build logic, three modules | `projects`, G0/G1 | `:core:model -> :core:foundation` and `:core:testing -> :core:foundation`; no circular/platform dependency or production dependency on testing. |
| 2.2 | **Complete.** Add validated IDs and bounded byte primitives with explicit conversions. | 2.1 | core model/foundation | Android-host/iOS Simulator value tests | `Bytes`, `TimerId`, `CorrelationId`, `Generation`, `LinkId`, and protocol-visible `PeerId` are validated; `LinkId` and `PeerId` have no conversion, and `PeerId` is not durable identity/contact state. |
| 2.3 | **Complete.** Add clock, scheduler and entropy contracts plus virtual implementations. | 2.1 | foundation/testing | Android-host/iOS Simulator ordered timer, stale generation, cancellation, and seeded replay tests | No sleeps/current-time reads in tests; no production scheduler or entropy source. |
| 2.4 | **Complete.** Add generic reducer/transition primitives and typed trace redaction rules. | 2.2–2.3 | foundation | Android-host/iOS Simulator replay, list-ownership, and trace-redaction tests; G3 | Same input produces equal transition state/effect order/trace; no production/product engine or effect executor was introduced. |

## 6. Phase 3 — BitChat wire protocol

- **Goal:** implement a side-effect-free `BitchatBaseline2026_08` codec proven by both upstream clients.
- **Scope completed:** resolved v1/v2 public-message outer packets, bounded reader/writer, flags/recipient/source-route structure, raw signature/compression retention, legacy/extended announcements, and bounded decoding.
- **Non-goals:** BLE, routing decisions, Noise cryptography, advanced feature activation, native BitMessage protocol.
- **Dependencies:** Phases 1–2.
- **Modules affected:** new `:protocol:bitchat`.
- **Core types/interfaces:** `DecodedPacket`, `RawPacket`, `BitchatCodec`, `DecodeLimits`, blocked `SigningTranscript`, `BitchatBaseline2026_08`, and announcement payload types.
- **State owner:** none; codecs are pure.
- **Platform responsibilities:** none. Phase 3 adds no compressor/decompressor or platform provider adapter; raw received bytes remain common values.
- **Tests:** 23 evidence-supported fixture outcomes, exact literal encode bytes, malformed/truncation/cap corpus, raw-retention checks, and deterministic full-manifest coverage reporting.
- **Compatibility gate:** literal Apple and Android bytes; no codec-generated expected values.
- **Completion criteria:** evidence-supported outer/announcement literals decode/encode exactly; unknown TLVs are preserved; security bounds precede allocation; every Phase 1 fixture has an explicit report status; G0–G3 pass.
- **Risks:** endianness, signed Kotlin bytes, payload-length allocation, padding ambiguity, recompression signature bug, doc drift.
- **Rollback:** production does not use codec until later feature flag; revert module/task independently.

### Tasks

| ID | Goal and scope | Dependencies | Expected files/modules | Tests / green gate | Definition of Done |
|---|---|---|---|---|---|
| 3.1 | **Complete.** Immutable packet/header/flag/route/capability model with unknown preservation. | 2.2, 1.2 | protocol:bitchat model | construction and fixture field tests | No I/O/time/randomness; all bounds explicit. |
| 3.2 | **Complete.** Bounded binary reader/writer with endian and cursor errors. | 3.1 | protocol binary internals | exhaustive boundary/truncation tests | No unchecked allocation or exception-based malformed flow. |
| 3.3 | **Complete.** v1 decode against Apple/Android literal vectors. | 3.2 | codec | literal and hostile corpus | Exact fields/raw representation and resolved reject reasons. |
| 3.4 | **Complete.** v1 encode against literal vectors; decode-only aliases never emitted. | 3.3 | codec | byte equality | Only literal-supported packets re-encode. |
| 3.5 | **Complete.** v2 length and source routes; neighbor ambiguity remains report-blocked. | 3.4 | codec/profile | route vectors and malformed cases | No mesh route policy or neighbor-list inference. |
| 3.6 | **Complete, bounded.** Raw 64-byte signature retention; transcript construction is explicitly profile-blocked without a literal transcript vector. | 3.4–3.5 | codec input values | signature length/retention tests | No crypto or invented canonical transcript. |
| 3.7 | **Complete, bounded.** Raw compressed-payload retention and emission refusal; padding/decompression policy remains blocked. | 3.6 | codec | raw-retention tests | No implicit recompression, decompression, or provider dependency. |
| 3.8 | **Complete, scoped.** Legacy/extended announcement TLVs only; messages, receipts, fragments, and sync payloads remain later phases. | 3.3–3.7 | payload package | literal announcement corpus | Unknown TLVs survive; duplicate TLVs reject. |
| 3.9 | **Complete.** Fresh `productionCompatibilityCheck` creates a deterministic 46-fixture status report. | 3.8 | test/report tooling | Phase 3 production gate | Report is generated under `build/`; the unchanged Phase 1 gate remains separate. |

## 7. Phase 4 — Deterministic MeshEngine foundation

- **Goal:** decide mesh admission/relay/reassembly without a physical radio.
- **Scope:** packet IDs, validation pipeline, dedup, TTL, relay policy, link/peer mapping, bounded fragments, topology facts and event dispatch.
- **Non-goals:** Bluetooth, durable delivery retry, full sync/courier, Noise primitives.
- **Dependencies:** Phase 3 and reducer primitives.
- **Modules affected:** new `:transport:api`, `:engine:mesh`.
- **Core types/interfaces:** `LinkEvent/Command/Result`, `MeshState/Event/Effect/Engine`, `PeerBinding`, `FragmentStream`.
- **State owner:** one serialized MeshEngine actor; reducer itself is pure.
- **Platform responsibilities:** none beyond future link event execution.
- **Tests:** transition tables, duplicate/TTL/signature pipeline, fragment limits/expiry, stale results, adversarial event properties.
- **Compatibility gate:** relay bytes/signature/TTL and policy scenarios match pinned profile.
- **Completion criteria:** in-memory scripted link can receive/relay baseline packets deterministically; all collections bounded.
- **Risks:** giant engine, actor creation before validation, policy/codec coupling, relay amplification.
- **Rollback:** engine not connected to platform; remove module/wiring.

### Tasks

| ID | Goal and scope | Dependencies | Expected files/modules | Tests / green gate | Definition of Done |
|---|---|---|---|---|---|
| 4.1 | Define link and mesh event/effect contracts plus redacted trace. | 2.4, 3.1 | transport:api, engine:mesh | contract/replay tests | No BlueFalcon/platform type leaks. |
| 4.2 | Admission, packet ID, dedup and TTL transitions. | 4.1, 3.6 | engine:mesh | duplicate/replay/TTL properties | Invalid input allocates no peer actor/state. |
| 4.3 | Relay selection/jitter/source-route decisions under compatibility profile. | 4.2 | engine:mesh | pinned topology traces | Deterministic with supplied entropy result. |
| 4.4 | Bounded fragment reassembly and expiry. | 4.2 | engine:mesh | reorder/duplicate/conflict/quota tests | Global/per-peer memory limits asserted. |
| 4.5 | Typed dispatch and runtime actor lifecycle. | 4.1–4.4 | engine runtime composition | start/stop/restart/cancellation tests | No post-close events or constructor launches. |

## 8. Phase 5 — Deterministic simulator

- **Goal:** expose mesh design failures before BLE integration and feature growth.
- **Scope:** multi-node simulated network, virtual time, MTU, readiness, latency, bandwidth, drop/duplicate/reorder/corrupt, partitions and replay traces.
- **Non-goals:** mocking Bluetooth framework quirks, performance benchmark, production crypto replacement.
- **Dependencies:** Phase 4; Phase 7 scenarios extend it later.
- **Modules affected:** new `:transport:simulation`, `:core:testing`.
- **Core types/interfaces:** `SimulatedNode`, `SimulatedNetwork`, `FaultRule`, `Scenario`, `ScenarioTrace`.
- **State owner:** simulator global queue; each node retains its own engine actors/repositories.
- **Platform responsibilities:** none.
- **Tests:** deterministic seed replay, line/triangle, partition/heal, MTU/backpressure, shrinking and boundedness.
- **Compatibility gate:** simulator transports exact encoded baseline bytes through normal codec/engine path.
- **Completion criteria:** required early scenarios pass and any failure emits one-command replay data; G4 begins.
- **Risks:** simulator diverges from adapter semantics, fake crypto mistaken for crypto proof, nondeterministic test parallelism.
- **Rollback:** additive test/simulation module.

### Tasks

| ID | Goal and scope | Dependencies | Expected files/modules | Tests / green gate | Definition of Done |
|---|---|---|---|---|---|
| 5.1 | Virtual directed link network with MTU/readiness/latency. | 4.5 | transport:simulation | ordering and backpressure tests | Same Link API as production adapter. |
| 5.2 | Seeded fault rules and partition/join/leave scripting. | 5.1 | simulation/testing | replay equality, rule bounds | Seed+scenario fully reproduces trace. |
| 5.3 | Public line/triangle, duplicate and partition/heal scenarios. | 5.2 | scenario tests | G4 | No duplicate logical projection; convergence bounded. |
| 5.4 | Scenario trace minimization and invariant DSL. | 5.3 | core:testing | failing-seed shrink test | Minimal replay artifact has no plaintext/secrets. |

## 9. Phase 6 — BLE integration through BlueFalcon

- **Goal:** execute link commands on Android and Apple without contaminating engines.
- **Scope:** pin BlueFalcon 3.7.0 artifacts; central/peripheral engines; session mapping; QueuePlugin; lifecycle, permission and restoration adapters.
- **Non-goals:** mesh policy in callbacks, delivery retry in QueuePlugin, Wi-Fi Aware, UI migration.
- **Dependencies:** Phases 4–5.
- **Modules affected:** new `:transport:bluetooth`, final platform graphs, catalog/build files.
- **Core types/interfaces:** `BluetoothLinkAdapter`, `BluetoothRuntimeConfig`, platform factories; existing Link contracts.
- **State owner:** adapter owns BlueFalcon managers/session maps; MeshEngine owns mesh state.
- **Platform responsibilities:** Android permissions/GATT lifecycle; Apple restoration identifier/background mode/early construction.
- **Tests:** fake BlueFalcon mapping, session disconnect/drain, readiness epochs, payload-too-large, lifecycle; platform smoke tests.
- **Compatibility gate:** captured link traffic is passed unchanged to common codec; no feature emission beyond baseline.
- **Completion criteria:** two nearby BitMessage test apps can exchange raw baseline frames; simulator suite remains identical; G6.
- **Risks:** restoration construction too late, multiple manager instances, callback races, ATT queue confused with delivery, Bluetooth flakiness.
- **Rollback:** transport selected by runtime flag; simulator remains default/test path; remove adapter bindings.

### Tasks

| ID | Goal and scope | Dependencies | Expected files/modules | Tests / green gate | Definition of Done |
|---|---|---|---|---|---|
| 6.1 | Add exact BlueFalcon 3.7.0 central/peripheral/queue dependencies and adapter skeleton. | 4.1 | catalog, transport:bluetooth | dependency/build checks | No deprecated facade; no types leak inward. |
| 6.2 | Central event/command mapping and explicit lifecycle. | 6.1 | common + android/ios adapter | mapping, stop/close, stale callback tests | Typed results cover all BlueFalcon outcomes. |
| 6.3 | Peripheral sessions, GATT request response and targeted notification mapping. | 6.1 | adapter platform sources | multi-central/session tests | Session ID is link ID, not peer identity. |
| 6.4 | Bounded QueuePlugin integration and readiness epochs. | 6.2–6.3 | adapter | backpressure/fairness/disconnect tests | No protocol retry/fragment logic added. |
| 6.5 | Android permission/lifecycle and final Metro bindings. | 6.2–6.4 | androidMain/androidApp | JVM/instrumented smoke, G0/G6 | Start/stop works across foreground cycles. |
| 6.6 | Apple early restoration manager and final Metro bindings. | 6.2–6.4 | iosMain/iosApp/Info.plist as required | framework + simulator restoration smoke | Stable identifier and early creation documented/tested. |

## 10. Phase 7 — Identity and Noise orchestration

- **Goal:** establish authenticated BitChat sessions deterministically with platform-secured keys.
- **Scope:** identity records/aliases, NoiseSessionEngine, XX initiator/responder, simultaneous open, timeout, stale generation, replacement, announce/static-key binding, authenticated peer state.
- **Non-goals:** double ratchet, peer-ID rotation emission, groups/prekeys/courier, UI verification workflow.
- **Dependencies:** Phases 2–3; simulator from 5; physical link optional.
- **Modules affected:** new `:crypto:api`, `:crypto:noise`; `:engine:mesh`; platform key providers.
- **Core types/interfaces:** `IdentityId`, `PeerAlias`, `KeyHandle`, crypto ports, `NoiseState/Event/Effect`, `SessionGeneration`, `CapabilityEvidence`.
- **State owner:** NoiseSessionEngine owns session generations; identity repository owns durable identity/evidence; provider owns key material.
- **Platform responsibilities:** secure entropy/key generation/storage/deletion; primitives where library/platform requires.
- **Tests:** known-answer vectors, cross-upstream transcripts, simultaneous open, timeout/replacement/restart, capability downgrade and key deletion.
- **Compatibility gate:** exact pinned Noise and identity-binding fixtures; advertised capability never authorizes sensitive behavior alone.
- **Completion criteria:** simulator and platform adapters complete first contact and encrypted echo; no key/plaintext logs; G2–G6.
- **Risks:** identity/peer-ID conflation, nonce/key reuse, FFI/KMP mismatch, stale session accepting ciphertext, Swift key-handle interop.
- **Rollback:** crypto/session feature flag; erase test identities; baseline public mesh remains.

### Tasks

| ID | Goal and scope | Dependencies | Expected files/modules | Tests / green gate | Definition of Done |
|---|---|---|---|---|---|
| 7.1 | Crypto API and provider conformance suite using known-answer vectors. | 2.2, 1.3 | crypto:api, platform providers | KATs on Android/iOS | Opaque key handles; cancellation safe. |
| 7.2 | Durable identity/alias/capability-evidence model, rotation-ready but no v2 emission. | 7.1 | domain/data contracts or crypto identity package | alias/binding/migration model tests | Conversations never key solely by 8-byte peer ID. |
| 7.3 | NoiseSessionEngine XX progression, generations and timers. | 7.1, 2.4 | crypto:noise | vector/replay/stale timer tests | Pure deterministic reducer. |
| 7.4 | Simultaneous handshake, replacement and stale ciphertext policy. | 7.3, 5.3 | noise + simulator | adversarial scenarios | One deterministic live generation per peer. |
| 7.5 | Announce/static-key and authenticated-peer-state binding. | 7.2–7.4 | noise/mesh | downgrade/capability pin tests | Sensitive capability needs authenticated evidence. |
| 7.6 | Platform secure storage, wipe and lifecycle conformance. | 7.1–7.5 | androidMain/iosMain | restart/deletion/platform tests | Keys survive intended restart and are unrecoverable after wipe. |

## 11. Phase 8 — Messenger domain and persistence

- **Goal:** make durable messenger facts independent of transport and protocol.
- **Scope:** domain entities/use cases, database/schema/migrations, repositories, atomic outgoing/incoming transactions, outbox leases, recovery and public Messenger facade.
- **Non-goals:** delivery execution, BLE UI, copying either upstream persistence API.
- **Dependencies:** Phase 2; identity model from 7.2 can be developed in parallel behind agreed contracts.
- **Modules affected:** new `:messenger:domain`, `:data:database`, `:data:repository`; adapt `:sharedLogic`.
- **Core types/interfaces:** `Conversation`, `Message`, `MessageContent`, `DeliveryAttempt`, `Receipt`, `OutboxOperation`, repositories, `Messenger`.
- **State owner:** database is durable authority; repositories serialize transactions; StateFlows are projections.
- **Platform responsibilities:** secure database/media key and filesystem protection; driver wiring; backup exclusion.
- **Tests:** domain invariants, fresh schema/all migrations, atomicity/failure injection, restart/lease recovery, quotas, corruption, Swift facade.
- **Compatibility gate:** protocol identifiers map at adapter boundary; stored logical content does not depend on raw BitChat models.
- **Completion criteria:** sending while all transports are fake/off persists and recovers queued message exactly once; G5.
- **Risks:** dual in-memory/database SSOT, destructive migrations, plaintext remnants/WAL, outbox races, Swift API complexity.
- **Rollback:** additive schema until release; feature flag facade; migration has downgrade/export plan before destructive step.

### Tasks

| ID | Goal and scope | Dependencies | Expected files/modules | Tests / green gate | Definition of Done |
|---|---|---|---|---|---|
| 8.1 | Protocol-neutral domain entities, monotonic status projection and repository ports. | 2.2 | messenger:domain | invariant/property tests | No protocol/Metro/platform imports. |
| 8.2 | Database choice/schema v1, constraints, encrypted sensitive columns and migration harness. | 8.1 | data:database | fresh/all-version/rollback tests | Schema documents quotas and key policy. |
| 8.3 | Atomic outgoing message+attempt+outbox and incoming dedup+ACK operations. | 8.2 | database/repository | injected crash at every statement boundary | No committed message lacks required durable work. |
| 8.4 | Lease-based outbox claim/recovery and bounded retention. | 8.3 | data:repository | process restart/expired lease tests | At-least-once execution, idempotent completion. |
| 8.5 | Messenger facade, StateFlow projections and Swift observation bridge. | 8.1–8.4 | sharedLogic | common + Swift smoke tests | UI sees durable state; public API contains no wire type. |
| 8.6 | Panic-wipe transaction/order and corrupted-store recovery policy. | 7.6, 8.2–8.5 | data/sharedLogic/platform | pending-write/wipe/corruption tests | No restart race resurrects data. |

## 12. Phase 9 — Reliable private delivery

- **Goal:** execute durable private sends with explicit attempt, retry and receipt semantics.
- **Scope:** DeliveryEngine, outbox executor, reachability/session coordination, retry/backoff/fallback, delivered/read receipts, reconnect and expiry.
- **Non-goals:** courier, Nostr implementation, media, ratchet.
- **Dependencies:** Phases 5, 7 and 8.
- **Modules affected:** new `:engine:delivery`; mesh/noise/repository/runtime integration.
- **Core types/interfaces:** `DeliveryState/Event/Effect`, `AttemptPolicy`, `RouteCandidate`, `ReceiptFact`.
- **State owner:** DeliveryEngine owns active policy state; repositories own messages/attempts/outbox.
- **Platform responsibilities:** none; adapters report reachability/results only.
- **Tests:** offline queue/restart/reconnect, link loss, backpressure, duplicate/out-of-order receipts, expiry/cancel, multi-route failure.
- **Compatibility gate:** BitChat private payload/receipt bytes and timeout behavior remain profile-gated; write success is not delivery.
- **Completion criteria:** simulator proves eventual delivery and exactly-once logical projection under loss/reconnect; attempts remain auditable.
- **Risks:** retry storm, status regression, duplicate sends, ACK spoofing, cancellation race.
- **Rollback:** disable executor while retaining durable queued records; no data loss.

### Tasks

| ID | Goal and scope | Dependencies | Expected files/modules | Tests / green gate | Definition of Done |
|---|---|---|---|---|---|
| 9.1 | Delivery reducer and monotonic projection from attempts/receipts. | 8.1, 2.4 | engine:delivery | transition/property tests | Any event order preserves status law. |
| 9.2 | Outbox executor, lease heartbeat/completion and bounded concurrency. | 8.4, 9.1 | delivery/runtime/repository | restart/backpressure tests | Crash cannot lose or permanently lock work. |
| 9.3 | Noise/mesh route execution and reconnect flush. | 7.5, 9.2 | delivery coordinator | simulator loss/reconnect | Session generation correlated to attempt. |
| 9.4 | ACK/read receipt authentication, idempotence and retry/fallback policy. | 9.3 | delivery/protocol adapter | duplicate/spoof/out-of-order tests | Only authenticated receipt advances state. |

## 13. Phase 10 — Gossip, sync and store-and-forward

- **Goal:** reconcile public history after partitions and add capability-gated courier delivery.
- **Scope:** GCS, request budgets/windows, reconnect sync, targeted fragment resync, bounded public history, courier envelopes/quotas/spray policy.
- **Non-goals:** gateway/bridge, ratchet, unnegotiated courier to Android.
- **Dependencies:** Phases 4–5, 8–9; prekeys from selected 15 work before forward-secret courier emission.
- **Modules affected:** new `:engine:sync`; protocol/repository/runtime.
- **Core types/interfaces:** `SyncState/Event/Effect`, `HistorySummary`, `ResponseBudget`, `CourierRecord`, `SprayBudget`.
- **State owner:** SyncEngine owns sessions/policy; repository owns history/envelopes.
- **Platform responsibilities:** none.
- **Tests:** partition/heal convergence, malformed GCS, amplification budgets, history expiry, courier encounter/restart/replay/quota.
- **Compatibility gate:** baseline request-sync first; extensions and courier require receiver capability/profile vectors.
- **Completion criteria:** deterministic scenarios converge within bounds; unsupported peers receive no advanced traffic.
- **Risks:** amplification, storage exhaustion, privacy-linkable courier tags, replay, divergent extension semantics.
- **Rollback:** disable courier/extension flags; baseline history remains readable.

### Tasks

| ID | Goal and scope | Dependencies | Expected files/modules | Tests / green gate | Definition of Done |
|---|---|---|---|---|---|
| 10.1 | Bounded public-history repository and GCS primitive/contracts. | 8.2, 3.8 | sync/data | known vectors and quota tests | Stable hash/mapping and limits. |
| 10.2 | Link-local SyncEngine request/response with cadence and budgets. | 10.1, 4.5 | engine:sync | amplification/malformed tests | Crafted TTL never relays request. |
| 10.3 | Partition/heal and targeted fragment-resync scenarios. | 10.2, 5.4 | simulator | convergence/G4 | No unsolicited flood or duplicate delivery. |
| 10.4 | Courier storage/state machine decode-only and local simulation. | 10.2, 9.4 | sync/data/protocol | quota/replay/restart traces | Emit remains disabled absent negotiated profile. |

## 14. Phase 11 — Nostr and internet path

- **Goal:** interoperate with deployed BitChat geohash and private mailbox traffic through an explicit internet transport.
- **Scope:** proprietary BitChat Nostr profile, event verification, relay/subscription/reconnect, mailbox lookback, geohash routing, proxy/Tor boundary and route integration.
- **Non-goals:** claiming full NIP-17/44/59 compliance, double ratchet, gateway/bridge by default.
- **Dependencies:** Phases 3, 7–9.
- **Modules affected:** new `:protocol:nostr`, `:transport:nostr`; runtime/data.
- **Core types/interfaces:** Nostr event/envelope codecs, `RelayCommand/Event`, `RelayPolicy`, `ProxyProvider`.
- **State owner:** relay adapter owns sockets/subscriptions; delivery/sync engines own message policy; repositories own cursors/mailbox facts.
- **Platform responsibilities:** proxy/Tor implementation, reachability/background lifecycle and secure credential storage.
- **Tests:** Apple legacy Android fixture, Schnorr/Ed verification, relay script, reconnect/backoff, cursor/lookback, invalid event rejection.
- **Compatibility gate:** exact deployed envelope bytes; all accepted geohash events verified regardless of Android #743 status.
- **Completion criteria:** deterministic scripted relay delivers baseline public/private events after reconnect without duplicate projection.
- **Risks:** proprietary-vs-standard confusion, metadata leakage, malicious relay, reconnect storm, Tor bypass.
- **Rollback:** disable internet route; durable outbox remains queued for mesh/reenable.

### Tasks

| ID | Goal and scope | Dependencies | Expected files/modules | Tests / green gate | Definition of Done |
|---|---|---|---|---|---|
| 11.1 | Pure Nostr event and deployed BitChat envelope codecs/verification inputs. | 3, 7.1 | protocol:nostr | cross-client fixtures | No network dependency; naming says proprietary profile. |
| 11.2 | Relay transport state machine, subscriptions and bounded reconnect. | 11.1 | transport:nostr | scripted websocket/time tests | Stop/close cancels all work. |
| 11.3 | Geohash verification/routing and mailbox cursor/lookback persistence. | 11.1–11.2, 8 | protocol/data/runtime | forged event, restart, duplicate tests | Unverified event never reaches domain. |
| 11.4 | Delivery route integration and explicit proxy/Tor provider. | 9, 11.2–11.3 | delivery/runtime/platform | no-proxy-bypass and fallback tests | Route policy remains engine-owned. |

## 15. Phase 12 — Presentation

UI decision: Android uses Jetpack Compose/Compose UI; Apple uses native SwiftUI. There is intentionally no Compose Multiplatform UI target for iOS. `sharedLogic` shares the messenger/domain/runtime facade and supplies a Swift-friendly observation boundary; rendering and platform presentation remain native.

- **Goal:** expose durable messenger behavior through thin Android Compose and native SwiftUI features.
- **Scope:** root navigation, conversation list/private chat/nearby/contacts/verification/settings components, MVIKotlin stores, Swift facade/observation.
- **Non-goals:** protocol/transport decisions, full historical UI copy, media/platform feature screens before capability exists.
- **Dependencies:** Messenger facade Phase 8; delivery runtime as features need it.
- **Modules affected:** adapt `:feature:root`, `:sharedUI`, `iosApp`; add coarse `:feature:conversations`, `:feature:chat`, `:feature:contacts` only as real screens land.
- **Core types/interfaces:** Decompose `XComponent`/`DefaultXComponent`, store intents/labels/state, presentation models.
- **State owner:** domain in Messenger/repositories; components own navigation/UI element state; stores own screen projections.
- **Platform responsibilities:** Compose and SwiftUI rendering, permissions/settings launchers, accessibility and platform media pickers.
- **Tests:** component/store tests, Decompose save/restore/lifecycle, Compose semantics/screenshots, SwiftUI smoke/accessibility.
- **Compatibility gate:** presentation never imports raw codecs and does not invent delivery meaning.
- **Completion criteria:** baseline conversation workflow operates on both shells; root child stack is stable; forbidden imports absent.
- **Risks:** UI as business owner, duplicate state, Swift flow lifecycle, over-modularization.
- **Rollback:** each feature route is gated; previous test screen remains only until first real route is stable, then removed in its own PR.

### Tasks

| ID | Goal and scope | Dependencies | Expected files/modules | Tests / green gate | Definition of Done |
|---|---|---|---|---|---|
| 12.1 | Stabilize root Decompose stack and Messenger lifecycle facade. | 8.5 | feature:root/sharedLogic | navigation/lifecycle tests | Child stack constructed once; main-thread navigation. |
| 12.2 | Conversation list component/store and Android/Swift views. | 12.1 | feature:conversations/sharedUI/iosApp | store/semantics/Swift smoke | UI imports only feature/public domain API. |
| 12.3 | Private chat/send/retry/read component and views. | 9.4, 12.2 | feature:chat | status/retry/accessibility tests | Displayed status matches domain projection. |
| 12.4 | Nearby/contacts/verification/settings capabilities. | 7.5, 12.1 | coarse feature modules | component and availability tests | Unsupported capability is explicit. |
| 12.5 | Remove placeholder screen and dead shell state. | 12.2–12.4 | root/sharedUI/iosApp | full G0–G6 | No unrelated donor UI migration. |

## 16. Phase 13 — Media

- **Goal:** deliver bounded, verifiable public/private files, images, voice notes and live voice.
- **Scope:** media domain records, storage/quota, MediaTransferEngine, manifests/chunks/resume/hash, BitChat outer/private media, live voice jitter/lifecycle.
- **Non-goals:** APK distribution, assuming QueuePlugin is media reliability, emitting underspecified receipts.
- **Dependencies:** Phases 6–9 and presentation as needed.
- **Modules affected:** new `:engine:media`; protocol/data/domain/feature/platform media adapters.
- **Core types/interfaces:** `MediaId`, `TransferId`, `MediaManifest`, media state/event/effect, `MediaStore`, capture/playback ports.
- **State owner:** media engine owns transfer; repository owns durable metadata; file store owns bytes; platform owns capture/playback session.
- **Platform responsibilities:** picker/camera/audio session/codec/file protection and background limits.
- **Tests:** chunk loss/reorder/corrupt/resume/restart/quota, hash verification, compatibility fixtures, audio lifecycle and private-media downgrade.
- **Compatibility gate:** private media requires authenticated bit 8; `0x09` decode only; receipts only under agreed bit/profile.
- **Completion criteria:** simulator resumes a transfer after disconnect without duplicate commit; platform smoke validates bounded storage.
- **Risks:** memory/disk exhaustion, path traversal, media parser attack, session/privacy leaks, receipt ambiguity.
- **Rollback:** disable transfer/capture capability; retain verified stored media and resumable metadata or migrate explicitly.

### Tasks

| ID | Goal and scope | Dependencies | Expected files/modules | Tests / green gate | Definition of Done |
|---|---|---|---|---|---|
| 13.1 | Media domain/storage contracts, content-addressed verification and quotas. | 8 | domain/data | traversal/quota/hash tests | Unverified bytes never become complete attachment. |
| 13.2 | Deterministic MediaTransferEngine with resume/window/backpressure. | 13.1, 5 | engine:media | fault/restart scenarios | State and memory globally bounded. |
| 13.3 | Public and private BitChat media codecs/profile integration. | 3, 7.5, 13.2 | protocol/media/delivery | cross-client fixtures/downgrade tests | Capability rules exact; alias never emitted. |
| 13.4 | Platform capture/picker/playback and protected storage. | 13.1–13.3 | sharedUI/iosApp/platform | instrumented/simulator lifecycle tests | Resource release on stop/background. |
| 13.5 | Live PTT bounded jitter/session integration. | 13.2–13.4 | media/platform | loss/reorder/lifecycle scenarios | Ephemeral frames never enter gossip/history. |

## 17. Phase 14 — Platform-specific capabilities

- **Goal:** add justified native capabilities without polluting common semantics or faking parity.
- **Scope:** explicit capability registry; Android Wi-Fi Aware and signed APK distribution/hotspot; Apple restoration/background/privacy refinements.
- **Non-goals:** forcing symmetry, changing BitChat bytes without negotiation, genericizing APK as media.
- **Dependencies:** stable runtime/transports/presentation; Phases 6, 11–13 as applicable.
- **Modules affected:** platform source sets/modules; minimal common capability contracts.
- **Core types/interfaces:** `PlatformCapability`, `CapabilityAvailability`, `WifiAwareCapability`, `AppDistributionCapability`, `BluetoothRestorationCapability`.
- **State owner:** each platform capability owns its lifecycle actor; common runtime receives availability/link events only.
- **Platform responsibilities:** all implementation; common code defines only meaningful integration contracts.
- **Tests:** availability/permission/lifecycle, alternate-link equivalence, APK signature/source/rate/resume, restoration kill/relaunch.
- **Compatibility gate:** alternate links carry the same profile bytes; platform feature absence changes no wire claim.
- **Completion criteria:** capabilities can be disabled/uninstalled without breaking baseline messenger; UI reports true availability.
- **Risks:** alternate-link identity confusion, APK supply-chain attack, hotspot exposure, background restrictions.
- **Rollback:** per-capability feature flag and module binding removal; baseline BLE/Nostr routes remain.

### Tasks

| ID | Goal and scope | Dependencies | Expected files/modules | Tests / green gate | Definition of Done |
|---|---|---|---|---|---|
| 14.1 | Capability registry/availability API and presentation integration. | 12 | sharedLogic/features | absent/disabled tests | No fake no-op capability. |
| 14.2 | Android Wi-Fi Aware link adapter behind existing Link API. | 14.1, 4–6 | android transport module | alternate-link simulator/device tests | Mesh sees link, not Wi-Fi policy details. |
| 14.3 | Android APK manifest/source ranking/download/resume/certificate verification. | 14.1, 11 | Android app-distribution module | malicious manifest/signature/rate tests | Install requires verified signer and user authorization. |
| 14.4 | Android hotspot/web server lifecycle and discovery integration. | 14.3 | Android module | exposure/stop/restart tests | No server remains after stop; bounded clients. |
| 14.5 | Apple restoration/background/privacy lifecycle hardening. | 6.6, 14.1 | iosMain/iosApp | kill/relaunch/protected-data tests | Restore maps links without resurrecting engine session state incorrectly. |

## 18. Phase 15 — Upstream feature adoption

- **Goal:** adopt selected post-baseline features only from refreshed, executable evidence.
- **Scope:** per-feature decision record, capability/profile gate, vectors, engine/domain impact, fallback and rollback.
- **Non-goals:** feature-count parity, wholesale PR ports, treating open work as shipped.
- **Dependencies:** baseline engines and the relevant feature prerequisites; refresh Phase 0 evidence first.
- **Modules affected:** only modules named by each adoption record.
- **Core types/interfaces:** profile/capability extensions; no generic “feature payload” escape hatch.
- **State owner:** existing engine that owns the behavior; create no global feature manager.
- **Platform responsibilities:** remain per matrix.
- **Tests:** joint fixtures, downgrade/unknown peer, simulator, persistence/restart and physical RC gate for wire features.
- **Compatibility gate:** both client implementations or a deliberate one-platform profile; old-client failure is predictable.
- **Completion criteria:** matrix row updated with merged SHA/evidence; feature separately disableable; no baseline bytes change.
- **Risks:** PR churn, downgrade/security regression, allocation collisions, incomplete counterpart.
- **Rollback:** profile/capability disable plus backward-compatible stored-state migration.

### Tasks

| ID | Goal and scope | Dependencies | Expected files/modules | Tests / green gate | Definition of Done |
|---|---|---|---|---|---|
| 15.1 | Refresh watchlist and write one adoption decision record per candidate. | 0, current upstream | matrix/compatibility docs | SHA/state verification | No coding task begins from stale PR state. |
| 15.2 | Adopt compressed-byte preservation after paired #1631/#863 resolution. | 3.7, 15.1 | protocol/mesh | foreign-DEFLATE cross-client vector | Relay verifies without normalizing bytes. |
| 15.3 | Adopt courier/prekeys/groups only after Android counterpart and vectors mature. | 7,10,15.1 | protocol/noise/sync/domain | downgrade/simulator/physical RC | Each capability individually gated. |
| 15.4 | Track vouch/diagnostics/board/gateway/bridge; implement only approved product subset. | 15.1 | affected engines/features | per-feature contract | No “parity bundle” PR. |
| 15.5 | Peer-ID rotation migration only after authenticated binding and coordinated active rollout. | 7.2,15.1 | identity/protocol/data/engines | old/new/rotation/outbox/media vectors | Never strands conversations/outbox; rollback dual-read plan. |
| 15.6 | Double ratchet only after paired merge, dependency audit and downgrade proof. | 11,15.1 | crypto/protocol/delivery/data | KAT/restart/disappearing-message tests | Disabled baseline fallback cannot violate message policy. |

## 19. Phase 16 — Interoperability and security hardening

- **Goal:** prove release behavior under hostile input, lifecycle stress, resource pressure and real cross-client radios.
- **Scope:** fuzz/property suites, hostile traffic, performance/memory/battery limits, corrupted persistence, panic wipe, platform lifecycle, physical matrix and release runbook.
- **Non-goals:** new user features, weakening limits to make a matrix cell green.
- **Dependencies:** selected release feature set from prior phases.
- **Modules affected:** all tested modules; minimal production changes only for verified defects.
- **Core types/interfaces:** test harness/runbook/metrics budgets; no new architecture layer.
- **State owner:** unchanged; tests assert ownership and bounds.
- **Platform responsibilities:** instrumented/simulator/device lifecycle and performance tests.
- **Tests:** codec fuzz, state event-sequence properties, simulator chaos, DB fault/corruption, kill/restart/background, physical interoperability and security invariant checklist.
- **Compatibility gate:** current and previous supported Apple/Android/BitMessage versions; captured bytes compared to profiles.
- **Completion criteria:** no critical/high unresolved security defect; budgets documented and enforced; release matrix signed with exact builds/devices.
- **Risks:** flaky physical tests, false fuzz confidence, performance fixes changing wire behavior, unrepresentative devices.
- **Rollback:** revert each defect fix independently; release block remains until gate is green.

### Tasks

| ID | Goal and scope | Dependencies | Expected files/modules | Tests / green gate | Definition of Done |
|---|---|---|---|---|---|
| 16.1 | Bounded codec fuzz/property harness with persistent regression corpus. | 3 | protocol tests | G7 | No crash/OOM/hang; reject reason stable. |
| 16.2 | Arbitrary engine event/failure/recovery properties and simulator chaos. | 4–10,13 | engine/simulator tests | G4/G7 | Invariants hold or minimal seed recorded. |
| 16.3 | Persistence corruption, disk-full, migration interruption and panic-wipe suite. | 8 | data/platform tests | G5/G7 | Recovery policy deterministic; no partial resurrection. |
| 16.4 | Android/iOS lifecycle, memory, CPU, radio and battery budgets. | 6,11,13,14 | platform tests/runbooks | G6 | Measured limits and device matrix recorded. |
| 16.5 | Physical Apple↔Android↔BitMessage release matrix and packet capture comparison. | all release scope | runbook/artifacts | G8 | Exact app SHAs, OS/devices, results and captures archived. |
| 16.6 | Security review closure and release decision. | 16.1–16.5 | security report/matrix | all gates | No critical/high open; exceptions explicitly time-bounded and non-wire-critical. |

## 20. Upstream Feature Adoption roadmap

This table is the planning view; the refreshable evidence view is `architecture/UPSTREAM_FEATURE_MATRIX.md`.

| Feature | Upstream state at audit | Protocol impact | Availability | Classification | BitMessage decision | Milestone | Dependencies | Compatibility impact |
|---|---|---|---|---|---|---:|---|---|
| Baseline packet/Noise/text/receipts | Both main | Critical | Both | Required interop + MVP | ADOPT_NOW | 1–9 | Fixtures, codecs, engines, data | Exact bytes |
| Original compressed-byte retention | Paired open PRs #1631/#863 | Critical/security | Proposed both | Required interop fix | ADOPT_NOW in design; activate after merge | 3.7/15.2 | Paired vector | Prevents relay signature failure |
| Conservative decompression cap | Clients differ; PRs conflict | Critical/security | Both | Required safety | ADOPT_NOW lower cap | 3.7/16 | Coordinated resolution for change | May reject Android-large payload |
| Durable conversations/outbox | Android ship; Apple mixed stores | Domain/product | Both possible | MVP | Native implementation | 8–9 | DB/identity | No byte impact |
| Courier/prekeys/groups | Apple ship; Android #770 open | Critical | Apple current | Future messenger | DESIGN_FOR, later gated adoption | 10/15 | Identity/sync/joint vectors | Unsupported peer must not receive |
| Vouch/diagnostics/board | Apple ship; Android partial/open | High | Uneven | Future messenger | TRACK | 15 | Product decision | Capability/profile required |
| Gateway/bridge | Apple ship; Android #770 open | High | Uneven | Future/experimental | DEFER | 15+ | Nostr/sync/privacy review | Metadata/privacy impact |
| Live PTT/private media | Both ship, recent | High | Both | Future messenger | DESIGN_FOR | 13 | Media engine/capability evidence | Exact type/receipt rules |
| Peer-ID rotation | Apple partial; Android draft #862 | Critical | Neither active | Experimental | Data model DESIGN_FOR; wire TRACK | 2/7/15 | Joint authenticated rollout | Identity/outbox migration |
| Double ratchet | Paired open #1107/#697 | Critical/security | Proposed both | Experimental | DEFER, preserve crypto agility | 15+ | Dependency audit/vectors | Downgrade-sensitive |
| Wi-Fi Aware | Android ship/off by default | Link only | Android | Platform enhancement | DEFER until BLE stable | 14 | Link contract | Same profile bytes |
| APK propagation | Android ship, #812 hardening open | Product/security | Android | Platform enhancement | DESIGN_FOR with signer verification | 14 | Internet/hotspot/capability API | No Apple fake |
| Apple restoration/privacy | Apple/BlueFalcon ship | Lifecycle | Apple | Platform enhancement | ADOPT_NOW boundary | 6/14 | Early manager/key policy | No wire change |
| Password channels | Android fix #735 open | Critical/security | Divergent | Unresolved upstream | REJECT for MVP | — | Joint secure contract | Avoid unsafe downgrade |
| Native BitMessage protocol | No upstream | Critical | Future | Future product | DEFER | post-16 | Negotiation and migration design | Must coexist explicitly |

## 21. Platform roadmap

### Android

1. Baseline app lifecycle, Bluetooth permissions and central/peripheral BLE.
2. Compose Messenger UI and notification behavior.
3. Nostr route with explicit proxy policy.
4. Optional Wi-Fi Aware as a second `LinkAdapter`, disabled until equivalent engine tests pass.
5. Optional APK propagation as a separate `AppDistributionCapability`: trusted manifest, source ranking, resume, rate limits, signing-certificate verification, user-approved install and bounded hotspot/server lifecycle.

### Apple

1. Early stable BlueFalcon central/peripheral manager with restoration identifier and correct background declarations.
2. Native SwiftUI over the public Messenger facade.
3. Keychain/protected-file policy, including device-only identity where required and panic-wipe ordering.
4. Background/restoration stress and notification privacy.

Neither roadmap blocks the other where the wire/common contracts are stable.

## 22. Cross-cutting risks and controls

| Risk | Earliest phase | Control / release condition |
|---|---:|---|
| Architecture modules proliferate before use | 2 | Create only named phase modules; require second consumer to split further. |
| Protocol guessed from prose | 1 | Literal dual-upstream fixtures; open PR is non-normative. |
| Common code overreaches platform APIs | 2/6 | Link/crypto/data capability ports; final platform graphs. |
| Coroutine lifecycle leaks | 4 | Explicit owner lifecycle, cancellation tests, no init launches. |
| Mutable state has two owners | 4/8 | Reducer/repository authority table and versioned persistence results. |
| Durability acknowledged too early | 8 | Transaction before public success; restart/failure injection. |
| BLE queue treated as reliable delivery | 6/9 | Typed link result only; DeliveryEngine/outbox owns retry and ACK. |
| KMP/Swift API unusable | 2/8 | Swift compile/smoke test for every exported surface. |
| Hostile packet exhausts resources | 3/4/10/13 | Validate before allocation; global/per-peer quotas; fuzz/chaos. |
| Identity rotation strands data | 7/15 | Stable authenticated identity + aliases; dual-read migration and rollback. |
| Feature-only PR breaks old client | 15 | Capability/profile gate, downgrade vector and physical RC matrix. |

## 23. Definition of Done for any task

A task is complete only when:

1. its named scope is implemented and its non-goals remain absent;
2. dependency direction matches `architecture/BITMESSAGE_ARCHITECTURE.md`;
3. behavior tests execute on the intended source sets and cannot pass as `NO-SOURCE`;
4. compatibility-visible behavior has literal provenance and profile coverage;
5. mutable state has one documented owner and lifecycle;
6. error, cancellation, restart and bounds paths are tested in proportion to risk;
7. Android and relevant iOS/KMP builds are green;
8. persistence changes include forward migration and failure/rollback evidence;
9. feature availability and rollback switch are explicit;
10. the architecture, compatibility and feature matrix documents are updated if their facts changed.

## 24. Completed work and next task

The completed Phase 1, Phase 2, and evidence-first Phase 3 sequence is:

1. **1.1 — Fixture schema and provenance: complete.**
2. **1.2 — Dual-upstream minimal literal packet/announce fixtures: complete.**
3. **1.3 — Security-sensitive and malformed compatibility corpus: complete.**
4. **1.4/1.5 — Pinned reproduction plus real-test/build CI gate: complete.**
5. **2.1 — Foundation/model/testing module boundaries: complete.**
6. **2.2 — Validated IDs and bounded bytes: complete.**
7. **2.3 — Time, scheduler, entropy contracts and virtual runtime: complete.**
8. **2.4 — Generic reducer/transition and typed redacted trace kernel: complete.**
9. **3.1–3.9 — Evidence-first BitChat wire codec: complete.** The implementation executes only 23 resolved fixture outcomes, records every other fixture as metadata-only, blocked, later-phase, or evidence-layout conflict, and starts no Phase 4 engine code.

**Next task: Phase 4 is not started.** Do not infer mesh/runtime work from the Phase 3 codec; it is a pure codec and report only.
