# BitChat Compatibility Baseline

Status: audit baseline; no BitMessage production codec exists yet  
Analysis date: 2026-08-07  
Compatibility rule: executable cross-client evidence outranks prose and open proposals.

## 1. Pinned evidence

| Source | Branch/tag | Commit | Commit date | Notes |
|---|---|---|---|---|
| [permissionlesstech/bitchat](https://github.com/permissionlesstech/bitchat) | `main` | `1f59e814f90c3f489f48d68262cb1bf640bf6181` | `2026-08-01T14:51:31+02:00` | Apple client; commit “Keyboard navigation (#1542)”; latest observed tag `v1.7.1`. |
| [permissionlesstech/bitchat-android](https://github.com/permissionlesstech/bitchat-android) | `main` | `094657efa0aabbb6f71c9050149d1d01aee96400` | `2026-08-03T00:18:52+02:00` | Android client; merge of PR #861; latest observed tag `1.7.4`. |
| [Reedyuk/blue-falcon](https://github.com/Reedyuk/blue-falcon) | `3.7.0` | `0338bb6b4ef6653179c5363946986ee838cd3c6f` | `2026-08-06T13:39:48+01:00` | Tag and `master` were identical on the audit date. |
| Current BitMessage | `main` | `1e4b824` | local | Shell only; no BitChat wire implementation. |
| Historical donor | `bitMessage/main` | `10feab049becf4140c8bf10e0d9428c89222840f` | 2026-07-15 | Separate local repository; architecture is reference-only, with subsystem salvage governed by the [historical salvage audit](HISTORICAL_BITMESSAGE_SALVAGE.md). |

The GitHub pull-request audit captured the most recently updated 100 PRs from each repository, including open, draft, merged and closed work. PR status in this document is as observed on 2026-08-07; refresh before adoption.

## 2. Source-of-truth hierarchy

For every compatibility-visible behavior, use this order:

1. Literal bytes accepted/emitted by both pinned clients and cross-client fixture tests.
2. A jointly coordinated Apple/Android merged change with identical vectors.
3. Single-client executable tests at the pinned commit, checked against the other decoder.
4. Shipping implementation at the pinned commit.
5. Merged specifications and migration documents that match the code.
6. Current main-branch prose documents.
7. Open/draft coordinated PRs, used for design only.
8. Historical code, comments, round-trip-only tests and the whitepaper.

The whitepaper is architectural intent, not a byte-level contract. An open “spec” PR is not normative. A round trip proves that one codec agrees with itself, not that another client agrees.

Historical fixtures are candidates, never provenance by themselves. The Phase 0.4 [Historical BitMessage Salvage Audit](HISTORICAL_BITMESSAGE_SALVAGE.md) identifies candidate packet, announcement, Noise, fragment, sync, Nostr, media, and advanced-feature vectors. A candidate enters the neutral corpus only after the pinned current Apple and Android clients accept or emit it as required and the result is recorded with an immutable hash.

### Protocol parity versus product parity

Protocol parity means a client can parse, validate, authenticate, route and respond to the same deployed bytes with the same limits, downgrade rules and security meaning. It includes reserved assignments and safe rejection of unsupported features. It does not require exposing every feature in the UI.

Product parity means offering comparable user-visible behavior. BitMessage does not pursue product parity blindly: durable conversations deliberately differ from Apple's ephemeral conversation choice; Android APK propagation and Wi-Fi Aware remain Android-only; Apple restoration remains Apple-only. A platform may omit a feature while its common codec still reserves and safely decodes the wire assignment.

## 3. Shipping wire inventory

### Outer packet

Both clients implement a compact binary packet with:

- version, type, mutable TTL, big-endian millisecond timestamp and flags;
- 8-byte sender ID and optional 8-byte recipient ID;
- v1 2-byte or v2 4-byte payload length;
- optional source route and optional 64-byte Ed25519 signature;
- padding rules for privacy-sensitive Noise packets;
- optional compression whose exact received representation may affect signature verification;
- fragmentation above link payload limits.

Signing excludes mutable TTL by constructing a canonical transcript with fixed TTL and no signature. Exact header/route/flags ordering must come from golden vectors, not this summary.

### Outer message types

| Hex | Meaning | Apple main | Android main | Compatibility decision |
|---:|---|:---:|:---:|---|
| `01` | announce | Yes | Yes | REQUIRED |
| `02` | public message | Yes | Yes | REQUIRED |
| `03` | leave | Yes | Yes | REQUIRED |
| `04` | courier envelope | Yes | No | DESIGN_FOR; do not emit to Android without capability/profile evidence |
| `10` | Noise handshake | Yes | Yes | REQUIRED |
| `11` | Noise encrypted | Yes | Yes | REQUIRED |
| `20` | fragment | Yes | Yes | REQUIRED |
| `21` | request sync | Yes | Yes | REQUIRED, but extension TLVs/policy require profile tests |
| `22` | file transfer | Yes | Yes | REQUIRED for public media profile |
| `23` | board post | Yes | No shipping equivalent confirmed | TRACK/DESIGN_FOR |
| `24` | prekey bundle | Yes | No; port is open | DESIGN_FOR |
| `25` | private group message | Yes | No; port is open | DESIGN_FOR |
| `26`/`27` | ping/pong diagnostics | Yes | No; port is open | TRACK |
| `28` | Nostr carrier/gateway | Yes | No; port is open | DEFER until bridge policy |
| `29` | live voice frame | Yes | Yes | DESIGN_FOR media phase |
| `2C` | announce v2 rotation primitive | Parse/test only; not active | Draft PR only | DO NOT EMIT |

### Noise inner payload types

| Hex | Meaning | Apple main | Android main | Decision |
|---:|---|:---:|:---:|---|
| `01` | private message | Yes | Yes | REQUIRED |
| `02` | read receipt | Yes | Yes | REQUIRED |
| `03` | delivered receipt | Yes | Yes | REQUIRED |
| `06`/`07` | group invite/key update | Yes | No shipping support | DESIGN_FOR |
| `08` | private live voice | Yes | Yes | DESIGN_FOR |
| `09` | prerelease private-file alias | Decode only | Decode only | ACCEPT, NEVER EMIT |
| `10`/`11` | verification challenge/response | Yes | Yes | REQUIRED for verification phase |
| `12` | vouch | Yes | No; open port | TRACK |
| `20` | private file | Yes | Yes | DESIGN_FOR media phase |
| `21` | authenticated peer state | Yes | Yes | REQUIRED with capability pinning |

### Capability field

Announcement capability TLV is a minimal little-endian bitfield, at least one byte when explicitly present. Absence differs from an explicit zero. Decoders preserve unknown low-64-bit values and ignore extension bytes above 64 bits.

| Bit | Apple main | Android main advertises | Decision |
|---:|---|:---:|---|
| 0 prekeys | Yes | No | DESIGN_FOR |
| 1 Wi-Fi bulk | Declared; wire behavior not fully specified | No | TRACK |
| 2 gateway | Yes | No | DEFER |
| 3 groups | Yes | No | DESIGN_FOR |
| 4 board | Yes | No | TRACK |
| 5 vouch | Yes | No | TRACK |
| 6 diagnostics | Yes | No | TRACK |
| 7 bridge | Yes | No | DEFER |
| 8 private media | Yes | Yes | REQUIRED for private media |
| 9 private-media receipts | Yes | No | DESIGN_FOR; capability-gated |
| 10 reserved non-destructive Noise replacement | Decoded, not advertised/acted on | Preserved as unknown | NEVER REUSE |
| 14 proposed peer-ID rotation | Not in shipping capability set | Draft PR #862 | DO NOT EMIT |

Advertisement is a hint. Security-sensitive behavior is enabled only after a signed announce is bound to the authenticated Noise remote static key and, where defined, an authenticated peer-state payload pins the capability to the current session generation.

## 4. Required compatibility profile

The first BitMessage profile is `BitchatBaseline2026_08`, defined by literal fixtures from both pinned commits. It supports:

- v1 and v2 outer packet decode/encode, route and signature transcripts;
- legacy and extended announces, including absent/empty/unknown capabilities;
- public and private messages, receipts, Noise handshake/encrypted payloads;
- fragmentation/reassembly and bounded decompression;
- baseline GCS request sync;
- public file packet decoding and private-media `0x20` when authenticated capability evidence exists;
- decode-only aliases and unknown-field preservation stated above.

It does not initially emit courier, prekey, groups, board, diagnostics, gateway, bridge, vouch, rotation, double-ratchet, or future native BitMessage traffic. Receiving an understood but disabled feature produces an explicit unsupported/profile result; it must not be silently mis-decoded as baseline traffic.

## 5. Implementations disagree

| Area | Apple pinned behavior | Android pinned behavior | Why | BitMessage rule | Confidence |
|---|---|---|---|---|---|
| Feature breadth | Courier/prekeys/groups/board/vouch/diagnostics/gateway/bridge shipped | Ports absent or open | IMPLEMENTATION_LAG | Preserve assignments; negotiate before emit. | High |
| Conversation history | In-memory `ConversationStore`; persistence explicitly a non-goal | Bounded SQLite private history with encrypted sensitive columns | PRODUCT_DECISION | BitMessage is durable; copy neither storage API. | High |
| BLE structure | Serial engine ownership and partial link-layer extraction; deterministic simulator | Large Android service/core split, plus Wi-Fi Aware | UNFINISHED_MIGRATION/platform history | New deterministic engine + narrow BlueFalcon link adapter. | High |
| Relay/fanout | Controlled flood, deterministic subset and topology/source routes | Different fanout/policy details | PROTOCOL_POLICY_DIVERGENCE | Pin baseline behavior by cross-client scenario; never assume prose parity. | Moderate |
| Request sync | Newer since-timestamp and targeted fragment filters documented/implemented | Baseline GCS documentation and behavior | IMPLEMENTATION_LAG | Decode unknown TLVs, baseline emit first; enable extensions only with vectors. | Moderate |
| Decompression limit | About 1.13 MiB; open PRs argue both raise and retain | 10 MiB | SECURITY/PROTOCOL_DIVERGENCE | Use the lower safe baseline until coordinated resolution; reject before allocation. | High |
| Compressed signed relays | Open PR #1631 preserves original compressed bytes | Paired open PR #863 | CONFIRMED_BUG/UNMERGED_FIX | Design raw signing transcript retention now; adoption gated on paired vectors. | High |
| Nostr geohash signatures | Schnorr verification present in Apple path | Android #743 remains open | IMPLEMENTATION_LAG/security | Verify before accepting; no “compatibility” downgrade. | Moderate |
| Wi-Fi Aware/APK propagation | Unavailable/not applicable | Shipping Android capabilities | PLATFORM_API/PRODUCT | Optional Android capability, absent on Apple. | High |
| BLE restoration | Apple-specific state restoration constraints | Android lifecycle differs | PLATFORM_API | Platform adapter state machines; common link semantics only. | High |
| Peer-ID rotation | Primitives parse/test but are ignored by shipping mesh | Draft PR #862 parse/discard | EXPERIMENTAL_UNFINISHED | Design durable aliases now; never emit `announceV2`. | High |

An apparent source-route documentation difference also exists: one neighbor-list document includes a count byte while another describes only `N * 8` bytes. This is a documentation conflict until implementation and a joint literal vector settle it.

## 6. Important upstream archaeology

### Merged Apple work relevant to the design

| PR | State | Architectural evidence |
|---:|---|---|
| [#1498](https://github.com/permissionlesstech/bitchat/pull/1498) | Merged | BLE Architecture V3 plan of record: link layer, serialized owner, capability ports, simulator, eventual sans-I/O effects. |
| #1539/#1540/#1547/#1548/#1551 | Merged | Radio controller, engine-owned auth/link state, deterministic simulator, link events and delegate extraction. The migration is substantial but incomplete. |
| [#1487](https://github.com/permissionlesstech/bitchat/pull/1487) | Merged | Peer-ID rotation primitives and `announceV2`; intentionally not wired into shipping mesh. |
| #1334 | Merged | `ConversationStore` becomes in-memory message SSOT; persistence remains explicitly out of scope. |
| #1371/#1372/#1375/#1378 | Merged | Sync hardening, courier/outbox/history, capability TLV, source routes and targeted fragment resync. |
| #1380/#1381/#1383/#1384 | Merged | Vouch, prekeys, private groups and gateway. |
| #1412 | Merged | Mesh/Nostr bridge. |
| #1403/#1406/#1434/#1432/#1349 | Merged | Live PTT, private media, Noise identity binding and signing-key pinning. |

### Merged Android work relevant to the design

| PR | State | Architectural evidence |
|---:|---|---|
| [#779](https://github.com/permissionlesstech/bitchat-android/pull/779) | Merged | Client rewrite contract suite with literal fixtures; the strongest Android compatibility starting point. |
| #806/#815 | Merged | Persistent conversations and follow-up polish. |
| #794/#789/#792 | Merged | DM outbox retry, receipt reliability and Noise handshake reliability. |
| #730/#729/#775 | Merged | Noise peer binding, bounded compressed expansion and key-log removal. |
| #632/#801/#808 | Merged | APK sharing, local ARM64 build source and hotspot reliability. |
| #711/#712 | Merged | Wi-Fi Aware refactor into mesh core, disabled by default. |
| [#843](https://github.com/permissionlesstech/bitchat-android/pull/843) | Merged | Apple-compatible live PTT on Android/Wear. |

The Android `docs/security-review-jul-27.md` targets an older commit. Findings fixed by later merged PRs must not be presented as current. It remains valuable as an adversarial-test inventory.

### Open/draft work: design evidence, not shipped behavior

| Repository / PR | State on 2026-08-07 | Paired work | Impact and decision |
|---|---|---|---|
| Apple [#1639](https://github.com/permissionlesstech/bitchat/pull/1639) | Open, not draft | None | Protocol spec v0.1.0 calls itself a first draft. Useful inventory; not normative. Missing courier/wire hex vectors and underspecified Wi-Fi bulk, favorites, file/voice receipts. TRACK. |
| Apple [#1107](https://github.com/permissionlesstech/bitchat/pull/1107) | Open | Android [#697](https://github.com/permissionlesstech/bitchat-android/pull/697) | Capability-gated Nostr double ratchet, disabled by default, FFI dependency. DESIGN_FOR crypto-envelope agility; DEFER implementation. |
| Apple [#1631](https://github.com/permissionlesstech/bitchat/pull/1631) | Open | Android [#863](https://github.com/permissionlesstech/bitchat-android/pull/863) | Preserve foreign compressed payload bytes for signature verification on relay. ADOPT_NOW in data model/design; wire behavior after paired merge/vectors. |
| Android [#862](https://github.com/permissionlesstech/bitchat-android/pull/862) | Draft | Apple merged primitives #1487 | Peer-ID rotation phase 1, parse/discard, capability bit 14. DESIGN_FOR durable identities; TRACK wire activation. |
| Android [#770](https://github.com/permissionlesstech/bitchat-android/pull/770) | Open | Apple features | Courier/prekeys/groups/gateway/bridge port. Feature is not in Android main. DESIGN_FOR assignments; phase 15 adoption. |
| Android #778/#777 | Open | Apple merged | Vouch and diagnostics ports. TRACK. |
| Android [#812](https://github.com/permissionlesstech/bitchat-android/pull/812) | Open | Android-specific | APK source ranking, resume/retry, signature verification and lifecycle fixes. ADOPT security requirements in Android capability phase; re-audit merge status. |
| Android [#743](https://github.com/permissionlesstech/bitchat-android/pull/743) | Open | Apple behavior | Schnorr verification for geohash events. BitMessage must verify regardless of lag. |
| Android #735 | Open | None | Restores password channel encryption. Current behavior is not a safe compatibility target; DEFER channel until resolved. |
| Apple #1620 / Android #859 | Open | Related | Raise/couple decompression cap to 10 MiB. Conflicts with Apple #1634. REJECT until coordinated security decision. |
| Apple #1634 | Open docs | Conflicts above | Argues to retain low cap due pre-allocation DoS. Use conservative cap. |
| Apple #1623/#1627 | Open | None | Duplicate logging-only oversize rejection work; no wire change. TRACK, likely superseded/duplicate. |
| Apple #1591 | Open docs | None | Golden-vector authoring guide. ADOPT process if merged content is sound; vectors still need independent execution. |
| Apple #1585 | Open | None | Keychain `ThisDeviceOnly` and outbox sweep optimization. ADOPT_NOW as security/performance requirements, not code. |

Closed-unmerged or superseded work is not silently promoted. Example: Android #772 (pre-auth decompression bomb) was closed unmerged; verify the resulting main-line protection directly rather than citing its proposal.

## 7. Golden-vector program

### Fixture provenance

Every fixture is immutable and records:

```text
id
source repository + commit + test/file
producer platform, if known
profile and direction
wire bytes (hex/base64)
expected decoded fields
expected signing transcript/hash
expected accept/reject reason
limits applied
```

Do not generate the expected byte string using the codec under test. Import literal upstream bytes or produce them with an independent pinned harness, then make both upstream decoders and BitMessage consume the same artifact.

Phase 1 implements format version 1 under `compatibility/`. Its length-prefixed canonical form hashes every compatibility-significant field: profile, ID/category/direction, current and historical provenance, raw wire bytes, expected status and typed reject code, semantic fields, signing transcript/hash, limits, and blocked-decision metadata. Descriptions and notes are deliberately outside the lock so editorial corrections do not masquerade as compatibility changes.

The `BitchatBaseline2026_08` Phase 1 corpus contains 46 fixtures: 10 outer packets, 6 announcements, 3 capability cases, 4 compression cases, 2 fragment cases, 13 other malformed cases, 4 Noise cases, 1 Nostr case, 1 signing case, and 2 sync cases. Fourteen are current upstream producer literals (seven Apple and seven Android), each accepted by the opposite pinned client. Seventeen have resolved typed rejection outcomes. Fifteen unresolved drift/security-limit cases are `DECODE_ONLY` and `BLOCKED_BY_PROTOCOL_DECISION`; they cannot silently establish shipping semantics.

This is the Phase 1 target-preparation subset of the larger corpus below. Positive signing/crypto KATs, full fragment/sync/Nostr semantics, and production decode assertions remain assigned to their implementation phases rather than being fabricated before those components exist.

### Required baseline corpus

1. v1 and v2 minimal packets; all flags; recipient/no-recipient; boundary lengths.
2. Route-free and routed packets, including empty/intermediate/malformed routes.
3. Signing transcript and verified signature with relayed TTL changes.
4. Exact PKCS-style padding block boundaries and invalid padding.
5. Compressed messages from both native compressors plus handcrafted foreign DEFLATE blocks; retain original compressed bytes.
6. Legacy nickname-only and current TLV announcements; absent versus zero capabilities; unknown TLVs/bits.
7. Noise XX handshake and transport known-answer vectors; simultaneous initiation and stale generation.
8. Private message/ACK/read receipt/authenticated-peer-state payloads.
9. Fragment first/middle/final, duplicates, out-of-order, conflicting metadata, expiry and memory limits.
10. GCS sync, since-timestamp and fragment-filter extension acceptance, malformed/budget-exceeding requests.
11. Nostr embedded BitChat envelope, including Android legacy fixture already present in Apple tests.
12. Private media canonical `0x20` and decode-only `0x09`.
13. Feature values not initially emitted: courier, prekey, group, board, diagnostics, gateway, bridge, vouch and announce v2 decode fixtures.

### Compatibility CI gate

The gate fails on any byte change, acceptance widening, unknown-field loss, different signing transcript, cap increase, or cross-client decode difference unless:

- a compatibility profile/version is explicit;
- new literal vectors execute against pinned Apple and Android harnesses;
- downgrade/old-client behavior is documented;
- the security limit is reviewed;
- the feature matrix and this document are updated.

The reproducible pinned exporter and reciprocal-acceptance harness is checked in under `tools/upstream-compat`. It passed against Apple `1f59e814f90c3f489f48d68262cb1bf640bf6181` and Android `094657efa0aabbb6f71c9050149d1d01aee96400` on 2026-08-07. A read-only remote-head check that day found both official `main` branches still at those SHAs, so no fresher generation was mixed into the profile. Ordinary PRs use checked-in neutral fixtures through `:core:testing:compatibilityCheck`; they do not clone upstream repositories. Physical BLE is not required for ordinary PRs.

## 8. Interoperability matrix

| Sender | Receiver | Required first release result | Later capability-gated result |
|---|---|---|---|
| BitMessage | Apple pinned | Public/private text, handshake, receipts, baseline sync and fragments interoperate byte-for-byte. | Private media, voice, courier/prekeys/groups/etc. only after profile evidence. |
| Apple pinned | BitMessage | Accept baseline plus safely parse/reject known newer feature types; preserve unknown fields. | Enable selected Apple feature with exact vectors. |
| BitMessage | Android pinned | Same baseline; use only Android-advertised capability 8 for private media. | Android platform features do not alter common wire without negotiation. |
| Android pinned | BitMessage | Accept baseline, Android private media and live voice when media phase is enabled. | Wi-Fi Aware is an alternate link, not a different message domain. |
| BitMessage old/new | BitMessage new/old | Baseline profile remains stable. | A future native profile requires explicit negotiation and no accidental fallback ambiguity. |
| Apple pinned | Android pinned | Existing upstream behavior is a reference, not proof of full parity. | BitMessage must not “fix” one side by emitting unsupported features to the other. |

Physical validation eventually covers current and previous release devices for Android↔Android, Apple↔Apple, Android↔Apple, BitMessage↔each upstream, small MTU, reconnect, background/foreground and partition/heal.

## 9. Compatibility risks

| Risk | Severity | Control |
|---|---|---|
| A prose spec differs from deployed bytes | Critical | Literal cross-client vectors outrank prose. |
| Relay recompression invalidates foreign signatures | Critical | Preserve original payload/transcript; paired #1631/#863 fixtures. |
| Pre-allocation decompression bomb | Critical | Inspect advertised length, enforce conservative cap before allocation, bounded streaming decode. |
| Peer ID treated as durable identity | High | Authenticated identity record + alias table; do not key conversations/outbox solely by 8-byte peer ID. |
| Capabilities trusted before Noise binding | High | Separate advertised hint from authenticated/pinned evidence and generation. |
| Sync amplification | High | Link-local enforcement, request authentication/context, response budgets, cadence/rate limits. |
| Unbounded fragment/session/actor state | High | Validate before allocation; global/per-peer limits, monotonic expiry and eviction tests. |
| Android/Apple feature assignment collision | High | Reserve every observed value, including abandoned decode-only values. |
| Nostr kind labels mistaken for standard NIP-17/44/59 | High | Name the deployed proprietary BitChat envelope profile explicitly. |
| Open PR silently treated as shipped | High | Snapshot/state column and profile gate in every adoption task. |
| Future double ratchet downgrade | High | Capability pinning and no downgrade of disappearing/ratcheted messages. |
| QueuePlugin mistaken for reliable messaging | High | ATT queue results feed engines; delivery remains durable domain policy. |

## 10. Unresolved protocol questions

These block feature emission, not baseline architecture work:

1. What jointly accepted literal packet/signature vectors define announce-v2 neighbor/recognition encoding and Noise binding?
2. Does the neighbor-list TLV contain a count byte? The current prose is inconsistent.
3. Which request-sync extensions are safe to emit to the pinned Android client, and what are their response budgets?
4. Will #1631/#863 merge unchanged, and is raw compressed representation part of the normative signing model?
5. What decompressed-size limit will both clients adopt? Current 1.13 MiB versus 10 MiB proposals conflict.
6. What capability and receipts contract fully defines file and live-voice delivery?
7. Is `wifiBulk` only a link-selection hint or does it change a wire negotiation?
8. What is the final courier spray-ACK allocation (`0x2A`/`0x2B`) and replay/quota contract?
9. How will double-ratchet capability, FFI version and safe fallback be standardized?
10. Which Nostr behaviors are proprietary BitChat compatibility versus standard NIP semantics?
11. What downgrade behavior applies to password channels while Android #735 is unresolved?
12. Which Apple-only feature ports will land on Android, and with which capability bits/vectors?

Until resolved, BitMessage decodes conservatively, reserves assignments, and does not emit the affected feature.
