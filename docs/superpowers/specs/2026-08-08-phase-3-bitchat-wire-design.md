# Phase 3 BitChat Wire Protocol Design

Status: reviewed and approved for planning

## Goal

Implement the smallest pure Kotlin Multiplatform BitChat wire codec supported by the resolved `BitchatBaseline2026_08` Phase 1 evidence, while preserving raw signing-relevant bytes and leaving unresolved protocol decisions explicitly blocked.

## Module boundary

Create only:

```text
:protocol:bitchat -> :core:foundation
                  -> :core:model
```

`:core:testing` is test-only infrastructure. No production code depends on it. The protocol module is common KMP code and has no platform API, clocks, entropy, coroutines, logging, filesystem, network, transport, persistence, mesh, or cryptographic implementation dependency.

## Profile and production scope

`BitchatBaseline2026_08` is one explicit profile value, not a general profile framework. It owns supported versions, outbound message assignments, codec limits, and the distinction between allowed, decode-only, blocked, and unsupported behavior.

Production codec behavior is driven only by Phase 1 resolved literal evidence:

- v1 and v2 outer packets, recipient presence, and resolved route bytes;
- legacy announcement and extended capability inputs;
- exact canonical encoding only when a literal fixture is canonical evidence;
- resolved malformed rejection cases where a codec layer can establish the listed failure;
- signing-transcript fixtures that have literal transcript evidence.

The codec may represent an unknown or reserved value without authorizing it. It must not emit a value merely because it can decode or represent it.

## Wire model and raw retention

The module owns immutable wire structures: packet version/type/flags, packet/header data, source route, raw payload, signature, capabilities, decode limits, profile, and typed decode failures. Public byte data uses `Bytes`; no public API exposes mutable `ByteArray`.

BitChat peer values entering/leaving the codec must be exactly eight bytes. The codec applies that wire constraint without truncation, padding, normalization, or coercion. `PeerId` remains a transport/protocol address only, never a durable contact or authenticated identity. Phase 3 introduces neither `IdentityId` nor a user/contact model.

Successful decode keeps the original received representation needed by later signing/relay compatibility work. In particular, the model can retain received compressed payload bytes separately from any decoded payload so later code never has to recompress foreign bytes to build a signing transcript.

## Binary, decode, and error design

Internal reader/writer primitives are narrowly owned by the BitChat codec. The reader has an explicit cursor and checks bounds before every read. It validates each attacker-controlled size before proportional allocation. The writer makes endianness, capacity, and length checks explicit and returns `Bytes`.

Ordinary malformed input is represented as a typed result, not an exposed parser exception. Stable categories are limited to truncated input, unsupported version, invalid length, limit exceeded, malformed field, invalid padding, unsupported feature, and profile violation. Errors never include raw packet bytes or secrets.

## Version, route, signing, padding, and compression behavior

v1 and v2 header/length handling follows literal outer-packet fixtures. Source-route parsing is limited to resolved route bytes; the neighbor-list count-byte dispute remains blocked and is not used to infer a higher-level encoding rule.

The signing transcript is an explicit pure byte construction operation, separate from signing or verification. It uses the retained received representation, applies only fixture-proven canonical TTL/signature treatment, and has no cryptographic dependency.

Padding and compression use only resolved structural evidence and stable rejection guards. The implementation preserves compressed received bytes and carries conservative limits, but does not decide blocked invalid-padding outcomes, foreign-compression equivalence, or the unresolved decompression-cap boundary. It adds no production compression-provider abstraction unless literal encoding evidence requires one.

## Payload and compatibility coverage

Payload codecs are limited to resolved Phase 1 literal evidence: legacy announcement and extended capabilities. Other illustrative Phase 3 payload families remain represented in the compatibility report as blocked, later-phase, metadata-only, or not applicable until independent literal evidence supports a codec implementation.

The Phase 3 coverage report classifies every Phase 1 fixture and never turns a blocked/decode-only fixture into an acceptance or emission rule. Existing fixtures, provenance, hashes, and the Phase 1 compatibility gate remain unchanged.

## Test and verification strategy

Tests are written before each codec behavior. They prove reader/writer bounds, fixed peer size, v1/v2 literal decoding, canonical literal encoding, raw representation retention, signing transcript literals, resolved rejection behavior, and generated hostile input resilience. Generated tests mutate truncation points, lengths, flags, routes, and arbitrary bounded byte sequences; they assert typed outcomes and no unbounded allocation path.

Final verification includes the unchanged compatibility gate, protocol tests, Phase 2 tests, Android debug assembly, and iOS simulator framework linking. Coverage reporting records actual fixture status and XML test counts.

## Explicit exclusions

Phase 3 does not implement MeshEngine, routing policy, dedup, TTL policy, fragment reassembly, Noise session/crypto, Ed25519, Bluetooth, BlueFalcon, persistence, messenger-domain types, Nostr networking, UI, authenticated identity, or Phase 4 code. Blocked Phase 1 decisions remain blocked.
