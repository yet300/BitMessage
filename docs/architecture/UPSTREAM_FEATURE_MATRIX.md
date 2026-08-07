# Upstream Feature Matrix

Last refreshed: 2026-08-07  
Apple snapshot: `permissionlesstech/bitchat@1f59e814f90c3f489f48d68262cb1bf640bf6181`  
Android snapshot: `permissionlesstech/bitchat-android@094657efa0aabbb6f71c9050149d1d01aee96400`  
BlueFalcon snapshot: `Reedyuk/blue-falcon@3.7.0` / `0338bb6b4ef6653179c5363946986ee838cd3c6f`

Historical donor inputs are classified separately in [Historical BitMessage Salvage Audit](HISTORICAL_BITMESSAGE_SALVAGE.md). The donor is not a fourth compatibility authority: its algorithms/tests may inform implementation, but its bytes require independent validation against the pinned Apple and Android snapshots.

## How to refresh

1. Record default branch, full SHA, commit date and latest tag for all three repositories.
2. Run the upstream test/fixture discovery and record exact executed counts, not README claims.
3. Fetch open, draft, recently merged, recently closed and superseded PR metadata.
4. Inspect implementation and tests for every row whose state changed.
5. Update `BITCHAT_COMPATIBILITY.md` if bytes, limits, capabilities or security policy changed.
6. Never change `BitMessage decision` based only on a PR title or prose spec.

Legend:

- `SHIP`: present and active on pinned main.
- `PARTIAL`: primitives or migration landed, but not fully active.
- `PR`: open/draft only; not shipped.
- `NO`: absent/not applicable.
- Decisions: `ADOPT_NOW`, `DESIGN_FOR`, `TRACK`, `DEFER`, `REJECT`.

## 1. Product and protocol features

| Feature | Apple main | Android main | Open/draft evidence | Protocol impact | Maturity | BitMessage decision | Target |
|---|---|---|---|---|---|---|---|
| v1/v2 binary packet | SHIP | SHIP | Spec #1639 | Critical | Mature but under-specified in one place | ADOPT_NOW | 1–3 |
| Ed25519 signing transcript | SHIP | SHIP | Compressed relay #1631/#863 | Critical | Mature; foreign-compression bug open | ADOPT_NOW | 1–3 |
| Padding/compression | SHIP | SHIP | Cap conflict #1620/#1634/#859 | Critical/security | Divergent limit | ADOPT_NOW conservative baseline | 3,16 |
| Public text mesh | SHIP | SHIP | — | Critical | Mature | ADOPT_NOW | 3–6 |
| Noise XX private messages | SHIP | SHIP | — | Critical/security | Mature with recent reliability fixes | ADOPT_NOW | 7–9 |
| Noise identity binding | SHIP | SHIP | Rotation follow-up | Critical/security | Recent but tested | ADOPT_NOW | 7 |
| Authenticated peer state/capability pin | SHIP | SHIP | — | High/security | Recent | ADOPT_NOW | 7 |
| Delivery/read receipts | SHIP | SHIP | File/voice receipts incomplete in spec | High | Text mature; media less complete | ADOPT_NOW text, DESIGN_FOR media | 8–9,13 |
| Fragmentation/reassembly | SHIP | SHIP | Targeted sync differs | Critical | Shipping; limits must be hardened | ADOPT_NOW | 3–5 |
| Source routing/topology | SHIP | SHIP with policy differences | Neighbor encoding question | High | Divergent policy/docs | ADOPT_NOW decode; TRACK routing parity | 4–6,16 |
| GCS request sync | SHIP + extensions | SHIP baseline | — | High | Shipping; extension lag | ADOPT_NOW baseline, DESIGN_FOR extensions | 10 |
| Persistent public history | SHIP bounded | SHIP bounded | — | Medium | Shipping | ADOPT_NOW for sync, separate from conversation history | 10 |
| Durable conversation history | NO by explicit Apple choice | SHIP bounded SQLite | — | Product only | Divergent product decision | ADOPT_NOW BitMessage-native | 8 |
| Durable DM outbox/retry | SHIP | SHIP | Apple outbox perf #1585 | High | Shipping | ADOPT_NOW with native transactional model | 8–9 |
| Nostr geohash channels | SHIP | SHIP | Android Schnorr #743 | High/security | Android verification lag | ADOPT_NOW only with verification | 11 |
| Nostr private mailbox | SHIP proprietary envelope | SHIP proprietary envelope | Double ratchet #1107/#697 | Critical/security | Shipping baseline; ratchet experimental | ADOPT_NOW baseline, DESIGN_FOR agility | 11,15 |
| Tor/proxy relay path | SHIP/configurable | SHIP | — | Privacy/platform | Shipping | DESIGN_FOR explicit proxy boundary | 11 |
| Courier/store-and-forward | SHIP | NO | Android port #770 | Critical | Apple mature, Android lag | DESIGN_FOR; enable only with negotiated support | 10,15 |
| One-time prekeys / FS courier | SHIP | NO | Android #770 | Critical/security | Apple shipping | DESIGN_FOR | 7,10,15 |
| Private groups | SHIP | NO | Android #770 | Critical | Apple shipping | DESIGN_FOR; not MVP baseline | 15 |
| Board posts/tombstones | SHIP | NO confirmed | — | High | Single-platform lead | TRACK | 15 |
| Vouch/web of trust | SHIP | NO | Android #778 | High/security | Apple shipping, port open | TRACK | 15 |
| Mesh ping/diagnostics | SHIP | NO | Android #777 | Medium | Apple shipping, port open | TRACK | 15 |
| Gateway | SHIP | NO | Android #770 | High | Apple shipping, port open | DEFER | 15 |
| Mesh/Nostr bridge | SHIP | NO | Android #770 | High | Apple shipping, port open | DEFER | 15 |
| Public files/voice notes | SHIP | SHIP | — | High | Shipping | DESIGN_FOR | 13 |
| Noise-encrypted private media | SHIP bits 8/9 | SHIP bit 8 | Receipt details incomplete | Critical/security | Interop baseline exists | DESIGN_FOR with exact vectors | 13 |
| Live push-to-talk | SHIP | SHIP (#843) | — | High/realtime | Recent | DESIGN_FOR | 13 |
| Peer-ID rotation / announce v2 | PARTIAL parse/ignore (#1487) | PR draft #862 | Capability bit 14 | Critical/identity | Experimental | DESIGN_FOR data model, TRACK wire | 2,15 |
| Nostr double ratchet | PR #1107 | PR #697 | Coordinated, disabled by default | Critical/security | Experimental | DESIGN_FOR crypto agility; DEFER implementation | 15+ |
| Password channels | Legacy behavior | Broken/stubbed concern | Android #735 | Critical/security | Unresolved | REJECT as MVP target | after resolution |
| Future native BitMessage profile | NO | NO | None | Critical | Not designed | DEFER; reserve negotiation boundary only | post-parity |

## 2. Platform and transport features

| Feature | Apple | Android | BlueFalcon 3.7.0 support | Reason for difference | BitMessage classification | Decision |
|---|---|---|---|---|---|---|
| BLE central/client | SHIP CoreBluetooth | SHIP Android GATT | Engine-based central APIs | PLATFORM_API | Common link contract + platform adapter | ADOPT_NOW |
| BLE peripheral/GATT server | SHIP | SHIP | Separate peripheral artifact for Apple/Android | PLATFORM_API | Common link contract + platform adapter | ADOPT_NOW |
| Multi-central targeted notify | Platform implementation | Platform implementation | `PeripheralSession`, targeted notify | PLATFORM_API | Adapter feature | ADOPT_NOW |
| ATT backpressure queue | Custom queues | Custom queues | Bounded `QueuePlugin` | LIBRARY_CAPABILITY | Link implementation only | ADOPT_NOW |
| Apple state restoration | SHIP/in migration | NO equivalent | Restoration identifier/capability | PLATFORM_API | Apple capability | ADOPT_NOW lifecycle design |
| Android BLE permission/battery lifecycle | NO equivalent | SHIP | Adapter-level | PLATFORM_API | Android capability | ADOPT_NOW lifecycle design |
| Wi-Fi Aware alternate link | NO | SHIP, disabled by default | NO | PLATFORM_API | Android optional capability | DEFER until BLE baseline |
| Wi-Fi Direct/hotspot APK serving | NO | SHIP | NO | PLATFORM_API/PRODUCT | Android optional capability | DESIGN_FOR, then phase 14 |
| Local APK propagation | NO | SHIP | NO | PLATFORM_PRODUCT | `AppDistributionCapability` | DESIGN_FOR security requirements |
| Protected file/key storage | File protection/Keychain | Keystore/files | NO | PLATFORM_API | Crypto/data provider | ADOPT_NOW |
| Native SwiftUI UI | SHIP | NO | NO | PRODUCT/PLATFORM | Apple presentation | ADOPT_NOW |
| Compose UI | NO | SHIP | NO | PRODUCT/PLATFORM | Android presentation | ADOPT_NOW |

BitMessage's UI decision is Android Compose and native Apple SwiftUI. Kotlin Multiplatform shares domain/protocol/engines/runtime/repositories/business logic through `sharedLogic`; it does not share Compose UI with iOS.

Absence of an Android-only capability on Apple is not a parity defect. Absence of a protocol feature with an open cross-platform port is generally implementation lag.

## 3. Architecture maturity

| Subsystem | Apple main | Android main | BitMessage conclusion |
|---|---|---|---|
| Mesh state ownership | Serial engine queue, extracted stores/capability ports; effect reducer not complete | Multiple services/core/managers with substantial shared mutable lifecycle | Neither is the target. Use pure reducers with one owner. |
| BLE boundary | V3 extraction partially landed; `BLEService` still large | Bluetooth service/core and GATT managers remain coupled to app behavior | Adopt LinkEvent/LinkCommand and BlueFalcon adapter. |
| Simulator | Deterministic protocol-level simulated mesh landed | Test infrastructure plan mostly incomplete, although later suite expanded | Build simulator before mesh complexity. |
| Protocol tests | Extensive fixtures/unit/simulator tests | Rewrite contract suite plus broad unit tests | Import literal evidence from both. |
| Persistence | Specialized outbox/history/media stores; conversations ephemeral | Serialized SQLite conversation repository, encrypted columns, bounded retention | Native transactional repository with atomic outbox. |
| Presentation boundary | View model remains broad despite extracted stores | Large app/UI service integration | Thin Decompose/MVIKotlin components over Messenger. |
| Security hardening | Recent signing/capability/private-media work | Recent binding/decompression/logging work; some PRs still open | Adversarial tests and conservative limits precede feature parity. |

## 4. BlueFalcon 3.7.0 matrix

The historical donor had no BlueFalcon dependency, engine, peripheral session, or QueuePlugin integration. Its custom Android GATT/CoreBluetooth code is a source of adapter quirks and regression tests only; the detailed replacement map is in the [historical salvage audit](HISTORICAL_BITMESSAGE_SALVAGE.md#5-bluefalcon-370-and-platform-integration-audit).

| Capability | State at 3.7.0 | BitMessage use | Explicit non-responsibility |
|---|---|---|---|
| Central engines | SHIP | Create platform engine, expose reactive state/services/results through adapter | Mesh policy, reconnect policy, message delivery |
| Typed write results | SHIP | Map to `LinkResult` | Protocol ACK/delivery |
| Per-connection max write length/readiness | SHIP | Update link capability and readiness events | Fragmentation decision without engine command |
| Peripheral sessions | SHIP | Stable `LinkId`/session mapping | Peer identity |
| Explicit GATT request response | SHIP | Platform adapter correctness | Application request handling |
| Targeted notifications | SHIP | Send to selected link/session | Routing/fanout policy |
| Apple restoration | SHIP with configuration constraints | Early stable adapter manager | Durable message recovery |
| QueuePlugin bounded queues | SHIP | ATT FIFO/fairness/backpressure | Fragmentation, persistence, retry, ACK, dedup, delivery |
| Legacy facade | Deprecated | Do not use | — |

ADR 0007 is still marked “Proposed” even though its implementation PR chain (#238–#243) landed. The migration guide and code are stronger current evidence than ADR status.

## 5. Pull-request watchlist

### Apple

| PR | Audit state | Category | Refresh question | Current decision |
|---:|---|---|---|---|
| #1639 protocol spec v0.1.0 | Open, non-draft | Specification | Did it merge, gain hex vectors, and resolve missing semantics? | TRACK |
| #1107 double ratchet | Open | Coordinated crypto | Did paired Android #697 merge with identical capability/fallback tests? | DEFER |
| #1631 compressed payload preservation | Open | Coordinated wire fix | Did paired #863 merge unchanged with foreign-DEFLATE vector? | ADOPT_NOW design |
| #1620 decompression cap raise | Open | Security/wire limit | Was conflict with #1634 resolved jointly? | REJECT now |
| #1634 retain low cap | Open docs | Security | Is a joint safe cap and allocation strategy specified? | Conservative baseline |
| #1623/#1627 oversize logging | Open | Duplicate/supersession | Which remains active? Any actual behavior change? | TRACK |
| #1591 golden-vector guide | Open docs | Testing | Does it define independent fixture provenance? | ADOPT process |
| #1585 keychain/outbox | Open | Security/performance | Did `ThisDeviceOnly` and sweep change merge? | ADOPT requirement |

### Android

| PR | Audit state | Category | Refresh question | Current decision |
|---:|---|---|---|---|
| #697 double ratchet | Open | Coordinated crypto | Paired merge and vector parity with #1107? | DEFER |
| #863 payload preservation | Open | Coordinated wire fix | Paired merge and literal vector with #1631? | ADOPT_NOW design |
| #862 peer-ID rotation phase 1 | Draft | Identity/protocol | Still parse/discard? Any active emission/recognition binding? | TRACK |
| #770 Apple feature port | Open | Protocol parity | Which courier/prekey/group/gateway/bridge pieces merged? | DESIGN_FOR |
| #778 vouch | Open | Protocol parity | Merged with Apple-compatible vectors? | TRACK |
| #777 diagnostics | Open | Protocol parity | Merged with ping/pong vectors? | TRACK |
| #812 APK sharing hardening | Open | Android capability | Were source ranking, resume and certificate checks merged? | ADOPT requirements |
| #743 Schnorr verification | Open | Security | Is every accepted geohash event verified on main? | ADOPT_NOW invariant |
| #735 password channels | Open | Security | Is encryption functional and cross-client tested? | REJECT baseline |
| #859 10 MiB cap coupling | Open | Security/wire limit | Was Apple contradiction resolved? | REJECT now |
| #841 geohash routing | Open | Routing correctness | Did route selection and tests merge? | TRACK |

## 6. Protocol-difference ledger

| ID | Difference | Classification | Blocks | Resolution evidence required |
|---|---|---|---|---|
| D-01 | Apple capability/message-type superset | IMPLEMENTATION_LAG | Emitting advanced features to Android | Android merged port + bidirectional vectors |
| D-02 | Relay/fanout policy | PROTOCOL_POLICY_DIVERGENCE | Exact mesh behavior/performance | Cross-client simulator + physical trace |
| D-03 | Request-sync extension level | IMPLEMENTATION_LAG | since/filter emission | Android acceptance tests + bounded response vectors |
| D-04 | Neighbor TLV count-byte prose | DOC_DRIFT/POSSIBLE_PROTOCOL | Topology encoding | Joint literal fixture and implementation trace |
| D-05 | Decompression cap | SECURITY_DIVERGENCE | Large compressed payload compatibility | Coordinated cap and pre-allocation tests |
| D-06 | Relay recompression/signature | CONFIRMED_UNMERGED_FIX | Safe foreign relay | Paired #1631/#863 merged vector |
| D-07 | Durable conversation choice | PRODUCT_DECISION | Nothing on wire | Native BitMessage persistence policy |
| D-08 | Peer-ID rotation | UNFINISHED_MIGRATION | Rotation emission | Authenticated binding, migration and vectors |
| D-09 | Android geohash verification | SECURITY_IMPLEMENTATION_LAG | Nostr/geohash acceptance | Main-line Schnorr test fixtures |
| D-10 | Wi-Fi/APK feature | PLATFORM_API | Apple product parity | No resolution required; capability remains absent |

## 7. Adoption ledger

| Category | Feature | Upstream state | Protocol impact | Platform availability | Classification | Decision | Milestone | Dependencies | Compatibility impact |
|---|---|---|---|---|---|---|---|---|---|
| Required BitChat interop | Baseline packet/Noise/message/receipt/sync | Both main | Critical | Both | ADOPT_NOW | Literal profile | 1–10 | Fixtures, engines | Byte-for-byte |
| Required BitMessage MVP | Durable conversation/outbox | Divergent | None/domain mapping | Both | ADOPT_NOW | Native transaction model | 8–9 | Domain/data | No wire change |
| Future messenger | Courier/prekeys/groups | Apple main, Android PR | Critical | Apple currently | DESIGN_FOR | Reserve types/ports; later capability gate | 15 | Baseline, identity, sync | Must not emit early |
| Future messenger | Double ratchet | Paired open PR | Critical | Proposed both | DEFER | Envelope agility only | 15+ | Merged paired vectors | Downgrade-sensitive |
| Platform enhancement | Wi-Fi Aware/APK | Android main | Link/product | Android | DESIGN_FOR | Optional capability | 14 | Stable transport/runtime | No common fake |
| Platform enhancement | Apple restoration | Apple/BlueFalcon | Link lifecycle | Apple | ADOPT_NOW | Adapter lifecycle | 6 | BlueFalcon boundary | No wire change |
| Experimental upstream | Peer-ID rotation | Apple partial/Android draft | Critical | Neither active | TRACK | Durable alias-ready identity only | 2,15 | Coordinated rollout | Never emit now |
| Experimental upstream | Spec v0.1.0 | Apple open PR | Potentially all | Both intent | TRACK | Evidence index, not authority | 0/continuous | Merge + vectors | None by itself |

## 8. Current test evidence and gaps

Apple main has broad protocol, Noise, sync, courier, prekey, group, bridge, Nostr and deterministic simulator coverage. Android main has a literal client-rewrite contract suite and hundreds of tests, but its own `docs/test-implementation-plan.md` still marks only milestone 0 complete and lists deterministic infrastructure, fuzzing, lifecycle, persistence and physical interop work as incomplete. Counts in prose are snapshots, not gates.

Phase 1 added real executed tests in `:core:testing` and a `compatibilityCheck` task that first removes prior Android-host results, runs its corpus suite, and then requires both a fresh corpus JUnit result and a test count greater than zero. The checked-in offline corpus has 46 fixtures; its 14 producer literals were regenerated and reciprocally accepted at the pinned Apple and Android SHAs. The pre-existing `:core:common`, `:sharedLogic`, `:sharedUI`, and `:feature:root` behavior suites may still report `NO-SOURCE`; they are not allowed to satisfy the compatibility gate and remain work for the phase that adds behavior to each module. Android assembly and the iOS simulator framework link remain part of CI.
