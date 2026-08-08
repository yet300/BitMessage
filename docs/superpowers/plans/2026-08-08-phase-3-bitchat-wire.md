# Phase 3 BitChat Wire Protocol Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the smallest evidence-first, pure KMP `BitchatBaseline2026_08` codec without turning unresolved Phase 1 behavior into production policy.

**Architecture:** One `:protocol:bitchat` module owns immutable wire values, a bounded internal reader/writer, resolved outer-packet and announcement/capability codecs, and a profile-aware fixture-coverage report. It depends only on `:core:foundation` and `:core:model`; tests may consume `:core:testing`. Every decode either retains the exact raw input or returns a small typed failure.

**Tech Stack:** Kotlin Multiplatform 2.4, existing local KMP convention, Kotlin unsigned primitives, `kotlin.test`, Phase 1 literal fixtures.

---

## Evidence boundary before implementation

The Phase 1 corpus contains 46 fixtures. The initial production-codec coverage target is the ten resolved `OUTER_PACKET` accepts, two resolved announcement/capability accepts, and resolved structural rejects only where their flag/layout agrees with the outer-wire evidence. Every `DECODE_ONLY`, `BLOCKED_BY_PROTOCOL_DECISION`, Noise, Nostr, fragment/reassembly, GCS semantic, and unsupported payload fixture is recorded rather than guessed.

The first production coverage audit must explicitly identify corpus entries whose flag bits contradict the claimed reject reason (for example, compression cases marked with signature flags) or whose grammar is not established by a literal fixture. It must not change the fixture, expected outcome, hash, or Phase 1 gate.

### Task 1: Register the protocol module and fixture-audit harness

**Files:**
- Modify: `settings.gradle.kts`
- Create: `protocol/bitchat/build.gradle.kts`
- Create: `protocol/bitchat/src/androidHostTest/kotlin/com/yet/bitmessage/protocol/bitchat/FixtureCoverageAuditTest.kt`
- Modify: `AGENTS.md`

- [ ] Add `:protocol:bitchat` to `settings.gradle.kts` beside the existing core modules.

```kotlin
include(":protocol:bitchat")
```

- [ ] Apply the existing KMP convention and production dependencies only on foundation/model. Use `:core:testing` only in `androidHostTest`, and give that source set the existing checked-in `compatibility/` resources.

```kotlin
plugins { alias(libs.plugins.local.kotlin.multiplatform) }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.foundation)
            implementation(projects.core.model)
        }
        androidHostTest {
            dependencies { implementation(projects.core.testing) }
            resources.srcDir(rootProject.layout.projectDirectory.dir("compatibility"))
        }
    }
}
```

- [ ] Write `FixtureCoverageAuditTest` before codec code. It loads the existing manifest through `FixtureManifestParser`, asserts exactly 46 fixture IDs, and records each ID as `IMPLEMENT_NOW`, `BLOCKED`, `LATER_PHASE`, `METADATA_ONLY`, or `EVIDENCE_LAYOUT_CONFLICT`. Assert `malformed-compression-size-abuse`, `malformed-suspicious-compression-ratio`, `malformed-truncated-signature`, `malformed-invalid-padding`, and `malformed-unknown-message-type` are never claimed production-decoded until exact profile behavior is evidenced.

```kotlin
@Test
fun phaseOneCorpusIsClassifiedWithoutChangingItsEvidence() {
    val manifest = FixtureManifestParser.parse(resourceText("BitchatBaseline2026_08/fixtures.json"))
    assertEquals(46, manifest.fixtures.size)
    assertEquals(CoverageStatus.IMPLEMENT_NOW, classify("apple-v2-route"))
    assertEquals(CoverageStatus.BLOCKED, classify("drift-neighbor-encoding-ambiguity"))
}
```

- [ ] Run `rtk ./gradlew :protocol:bitchat:testAndroidHostTest`; expect compilation failure before the module/test support exists. Add only the build setup and audit helper needed to make it green.

- [ ] Run `rtk ./gradlew :protocol:bitchat:allTests`; expect executed Android-host and iOS Simulator tests, not `NO-SOURCE`.

- [ ] Update `AGENTS.md` repository map and dependency diagram for the new module; do not mention any uncreated protocol/engine module as implemented.

- [ ] Commit this boundary and audit task:

```bash
git add settings.gradle.kts protocol/bitchat/build.gradle.kts protocol/bitchat/src AGENTS.md
git commit -m "build: add BitChat protocol module"
```

### Task 2: Define immutable wire values, profile, limits, and typed results

**Files:**
- Create: `protocol/bitchat/src/commonMain/kotlin/com/yet/bitmessage/protocol/bitchat/WireModel.kt`
- Create: `protocol/bitchat/src/commonMain/kotlin/com/yet/bitmessage/protocol/bitchat/BitchatProfile.kt`
- Create: `protocol/bitchat/src/commonMain/kotlin/com/yet/bitmessage/protocol/bitchat/DecodeResult.kt`
- Create: `protocol/bitchat/src/commonTest/kotlin/com/yet/bitmessage/protocol/bitchat/WireModelTest.kt`

- [ ] Write failing tests for exact eight-byte codec peer construction, unknown numeric packet type retention, immutable raw packet ownership, nonnegative/bounded limits, and profile emission denial for decode-only assignments.

```kotlin
@Test
fun wirePeerRejectsShortAndLongBytesWithoutCoercion() {
    assertFailsWith<IllegalArgumentException> { WirePeerId.of(Bytes.copyOf(ByteArray(7))) }
    assertFailsWith<IllegalArgumentException> { WirePeerId.of(Bytes.copyOf(ByteArray(9))) }
}

@Test
fun profileDoesNotPermitUnknownOrDecodeOnlyTypeEmission() {
    assertFalse(BitchatBaseline2026_08.canEmit(PacketType.of(0x2cu)))
    assertFalse(BitchatBaseline2026_08.canEmit(PacketType.of(0xffu)))
}
```

- [ ] Run `rtk ./gradlew :protocol:bitchat:allTests`; expect unresolved references for the wire values.

- [ ] Implement only these model shapes. `PacketType` is a value wrapper so unknown values survive; a known-type lookup is separate. `RawPacket` owns the exact wire bytes. `DecodedPacket` carries the raw packet alongside semantic fields and never exposes a `ByteArray`.

```kotlin
@JvmInline value class PacketType private constructor(val value: UByte) {
    companion object { fun of(value: UByte): PacketType = PacketType(value) }
}

@JvmInline value class WirePeerId private constructor(val value: PeerId) {
    companion object {
        fun of(bytes: Bytes): WirePeerId = WirePeerId(PeerId.of(Bytes.requireExactSize(bytes.copyToByteArray(), 8)))
    }
}

data class RawPacket(val wireBytes: Bytes)

sealed interface DecodeResult<out T> {
    data class Success<T>(val value: T) : DecodeResult<T>
    data class Failure(val error: DecodeError) : DecodeResult<Nothing>
}
```

- [ ] Keep `DecodeError` to `EMPTY_INPUT`, `TRUNCATED`, `UNSUPPORTED_VERSION`, `INVALID_LENGTH`, `LIMIT_EXCEEDED`, `MALFORMED_FIELD`, `INVALID_PADDING`, `UNSUPPORTED_FEATURE`, and `PROFILE_VIOLATION`. No error contains raw bytes.

- [ ] Run `rtk ./gradlew :protocol:bitchat:allTests`; expect green model tests on Android-host and iOS Simulator.

- [ ] Commit:

```bash
git add protocol/bitchat/src/commonMain protocol/bitchat/src/commonTest
git commit -m "feat: define immutable BitChat wire model"
```

### Task 3: Add bounded internal binary reading and writing

**Files:**
- Create: `protocol/bitchat/src/commonMain/kotlin/com/yet/bitmessage/protocol/bitchat/internal/BinaryReader.kt`
- Create: `protocol/bitchat/src/commonMain/kotlin/com/yet/bitmessage/protocol/bitchat/internal/BinaryWriter.kt`
- Create: `protocol/bitchat/src/commonTest/kotlin/com/yet/bitmessage/protocol/bitchat/internal/BinaryReaderWriterTest.kt`

- [ ] Write failing tests for cursor advancement, big-endian `UShort`/`UInt`/`ULong`, exact byte reads, truncation without exceptions, writer capacity failure, and result ownership.

```kotlin
@Test
fun readerReportsTruncationInsteadOfAllocatingAdvertisedLength() {
    val reader = BinaryReader(Bytes.copyOf(byteArrayOf(0x00, 0x04, 0x41)))
    assertEquals(BinaryReadResult.Failure(BinaryReadError.Truncated), reader.readExact(4))
}

@Test
fun writerProducesAnOwnedByteValue() {
    val writer = BinaryWriter(maxSize = 4)
    writer.writeUShortBigEndian(0x0102u)
    assertEquals(Bytes.copyOf(byteArrayOf(1, 2)), writer.toBytes())
}
```

- [ ] Run `rtk ./gradlew :protocol:bitchat:allTests`; expect missing reader/writer symbols.

- [ ] Implement a cursor-owning reader that returns `BinaryReadResult` and checks `remaining >= count` before copying. Implement a writer with an explicit `maxSize`, checked additions, explicit endian methods, and `Bytes.copyOf` output. Do not expose a mutable buffer or add a serialization framework.

```kotlin
internal fun readExact(count: Int): BinaryReadResult<Bytes> {
    if (count < 0 || remaining < count) return BinaryReadResult.Failure(BinaryReadError.Truncated)
    val result = Bytes.copyOf(source.copyToByteArray().copyOfRange(cursor, cursor + count))
    cursor += count
    return BinaryReadResult.Success(result)
}
```

- [ ] Add generated bounded tests that truncate every prefix of each resolved outer fixture and mutate declared v1/v2 payload lengths. Assert a typed result and no thrown parser exception.

- [ ] Run `rtk ./gradlew :protocol:bitchat:allTests --rerun-tasks`; expect green Android-host and iOS Simulator execution.

- [ ] Commit:

```bash
git add protocol/bitchat/src/commonMain/kotlin/com/yet/bitmessage/protocol/bitchat/internal protocol/bitchat/src/commonTest
git commit -m "feat: add bounded BitChat binary primitives"
```

### Task 4: Decode and encode resolved v1 outer packets

**Files:**
- Create: `protocol/bitchat/src/commonMain/kotlin/com/yet/bitmessage/protocol/bitchat/BitchatCodec.kt`
- Create: `protocol/bitchat/src/commonMain/kotlin/com/yet/bitmessage/protocol/bitchat/V1Codec.kt`
- Create: `protocol/bitchat/src/commonTest/kotlin/com/yet/bitmessage/protocol/bitchat/V1CodecTest.kt`
- Create: `protocol/bitchat/src/androidHostTest/kotlin/com/yet/bitmessage/protocol/bitchat/V1FixtureTest.kt`

- [ ] Write literal-first tests for `apple-v1-broadcast`, `android-v1-broadcast`, `apple-v1-recipient`, and `android-v1-recipient`. Assert version/type/TTL/timestamp/sender/recipient/payload/raw bytes and exact encode equality with each literal.

```kotlin
@Test
fun v1RecipientLiteralDecodesAndEncodesExactly() {
    val wire = hex("010203010203040506070801000200112233445566778899aabbccddeeff4142")
    val decoded = assertIs<DecodeResult.Success<DecodedPacket>>(codec.decode(wire)).value
    assertEquals(1u, decoded.version.value)
    assertEquals(Bytes.copyOf(byteArrayOf(0x41, 0x42)), decoded.payload)
    assertEquals(wire, assertIs<EncodeResult.Success>(codec.encode(decoded)).bytes)
}
```

- [ ] Run `rtk ./gradlew :protocol:bitchat:allTests`; expect missing codec symbols.

- [ ] Implement the v1 grammar evidenced by literals: version, type, TTL, big-endian timestamp, flags, big-endian two-byte payload length, eight-byte sender, conditional recipient, payload, and conditional signature. Validate payload length against `DecodeLimits` before `readExact`; return typed failures. Preserve the original full input in `RawPacket`.

- [ ] Make `BitchatBaseline2026_08` allow canonical v1 public-message emission only when fields are within resolved evidence. Decode-only/unknown packet types return `PROFILE_VIOLATION` on encode rather than being emitted.

- [ ] Run `rtk ./gradlew :protocol:bitchat:allTests`; expect literal and hostile-input tests green.

- [ ] Commit:

```bash
git add protocol/bitchat/src
git commit -m "feat: codec resolved BitChat v1 packets"
```

### Task 5: Decode and encode resolved v2 routes without route-policy inference

**Files:**
- Create: `protocol/bitchat/src/commonMain/kotlin/com/yet/bitmessage/protocol/bitchat/V2Codec.kt`
- Create: `protocol/bitchat/src/commonTest/kotlin/com/yet/bitmessage/protocol/bitchat/V2CodecTest.kt`
- Create: `protocol/bitchat/src/androidHostTest/kotlin/com/yet/bitmessage/protocol/bitchat/V2FixtureTest.kt`

- [ ] Write literal-first tests for both producer copies of broadcast, recipient, and two-hop `v2-route` fixtures. Assert v2 four-byte payload length and ordered exact 8-byte route entries.

```kotlin
@Test
fun v2RouteLiteralPreservesRouteOrderAndExactWire() {
    val result = assertIs<DecodeResult.Success<DecodedPacket>>(codec.decode(APPLE_V2_ROUTE)).value
    assertEquals(listOf(WirePeerId.of(SENDER), WirePeerId.of(RECIPIENT)), result.route?.entries)
    assertEquals(APPLE_V2_ROUTE, assertIs<EncodeResult.Success>(codec.encode(result)).bytes)
}
```

- [ ] Run `rtk ./gradlew :protocol:bitchat:allTests`; expect missing v2 implementation.

- [ ] Implement only the resolved v2 layout: four-byte big-endian payload length and the fixture-evidenced route count followed by 8-byte addresses. Reject malformed route tails as `INVALID_LENGTH`. Apply an explicit conservative route-entry limit before constructing the list.

- [ ] Keep `drift-neighbor-encoding-ambiguity` and `malformed-oversized-route` out of production-success assertions: the coverage audit records them `BLOCKED`; no neighbor-list policy or mesh route choice is introduced.

- [ ] Add generated mutations for v2 route count, tail length, and payload length. Assert typed failure or audit classification, never a crash.

- [ ] Run `rtk ./gradlew :protocol:bitchat:allTests --rerun-tasks`; expect green target execution.

- [ ] Commit:

```bash
git add protocol/bitchat/src
git commit -m "feat: codec resolved BitChat v2 routes"
```

### Task 6: Add announcement/capability payload codecs and unknown-preserving structure

**Files:**
- Create: `protocol/bitchat/src/commonMain/kotlin/com/yet/bitmessage/protocol/bitchat/payload/AnnouncementCodec.kt`
- Create: `protocol/bitchat/src/commonMain/kotlin/com/yet/bitmessage/protocol/bitchat/payload/AnnouncementPayload.kt`
- Create: `protocol/bitchat/src/commonTest/kotlin/com/yet/bitmessage/protocol/bitchat/payload/AnnouncementCodecTest.kt`
- Create: `protocol/bitchat/src/androidHostTest/kotlin/com/yet/bitmessage/protocol/bitchat/payload/AnnouncementFixtureTest.kt`

- [ ] Write tests for the literal legacy announcement, both extended announcement orderings, absent versus explicit capability TLV, low-64 capability preservation, unknown TLV raw preservation, and resolved duplicate-TLV rejection.

```kotlin
@Test
fun extendedAnnouncementRetainsObservedTlvOrderWithoutClaimingCanonicalOrder() {
    val decoded = assertIs<PayloadDecodeResult.Success<AnnouncementPayload>>(decode(APPLE_EXTENDED)).value
    assertEquals(listOf(0x04u, 0x05u), decoded.tlvs.map { it.type.value })
    assertEquals(Bytes.copyOf(hex("0102030405060708")), decoded.unknownTlvs.single().value)
}
```

- [ ] Run `rtk ./gradlew :protocol:bitchat:allTests`; expect payload types/codecs to be unresolved.

- [ ] Implement the literal TLV grammar: one-byte type, one-byte value length, bounded value read, ordered immutable TLV list, and duplicate detection for known singular fields. Store unknown TLVs as type plus exact `Bytes`; preserve them in decoded values but do not imply feature support.

- [ ] Implement legacy/extended announcement parsing needed by the two accepted fixture families. Do not settle the `0x04` neighbor-list count conflict; keep that fixture coverage blocked. Do not implement courier, prekey, group, board, Noise inner, fragment reassembly, GCS semantics, Nostr, or media payload codecs.

- [ ] Only provide exact encoding where an input can reproduce an observed literal order. Do not declare Apple and Android extended TLV ordering canonically interchangeable; profile encoding remains restricted until joint canonical evidence exists.

- [ ] Add bounded arbitrary-TLV tests for truncation, overlength, duplicates, and unknown entries. Run `rtk ./gradlew :protocol:bitchat:allTests` and expect green.

- [ ] Commit:

```bash
git add protocol/bitchat/src
git commit -m "feat: codec baseline announcement payloads"
```

### Task 7: Retain signing/compression/padding structure and report unresolved evidence without crypto

**Files:**
- Create: `protocol/bitchat/src/commonMain/kotlin/com/yet/bitmessage/protocol/bitchat/SigningTranscript.kt`
- Create: `protocol/bitchat/src/commonMain/kotlin/com/yet/bitmessage/protocol/bitchat/CompressionEnvelope.kt`
- Create: `protocol/bitchat/src/commonTest/kotlin/com/yet/bitmessage/protocol/bitchat/RawRetentionTest.kt`
- Modify: `protocol/bitchat/src/androidHostTest/kotlin/com/yet/bitmessage/protocol/bitchat/FixtureCoverageAuditTest.kt`

- [ ] Write failing tests proving a decoded compressed-flag packet retains exact input/raw payload bytes, a signature flag with fewer than 64 trailing bytes returns `TRUNCATED`, and the profile refuses to emit compression or padding modes without resolved canonical evidence.

```kotlin
@Test
fun signedPacketNeverTreatsAShortTrailingFieldAsPayload() {
    val result = codec.decode(hex("0202030102030405060708020000000200112233445566774142aabb"))
    assertEquals(DecodeResult.Failure(DecodeError.TRUNCATED), result)
}
```

- [ ] Run `rtk ./gradlew :protocol:bitchat:allTests`; expect missing retention/transcript types.

- [ ] Implement a pure `SigningTranscript` value/builder that is callable only for a fully parsed supported packet and returns `Bytes`; it neither signs nor verifies. Preserve TTL and signature treatment as explicit arguments, but mark it `PROFILE_VIOLATION` when no fixture-proven transcript rule is available.

- [ ] Implement `CompressionEnvelope` as raw-representation retention only. It has no inflater/deflater and no comparison-by-recompression. Record the two malformed compression fixtures as evidence-layout conflicts when their flags select signature parsing, and retain foreign-compression/decompression-boundary fixtures as blocked. Treat `malformed-invalid-padding` as blocked rather than inventing an acceptance/rejection policy.

- [ ] Run `rtk ./gradlew :protocol:bitchat:allTests`; expect raw-retention tests green. This task must not add an Ed25519, compression, randomness, or platform dependency.

- [ ] Commit:

```bash
git add protocol/bitchat/src
git commit -m "feat: retain BitChat signing representations"
```

### Task 8: Add production fixture coverage report, documentation, and final gates

**Files:**
- Create: `protocol/bitchat/src/androidHostTest/kotlin/com/yet/bitmessage/protocol/bitchat/ProductionFixtureCoverageTest.kt`
- Modify: `protocol/bitchat/build.gradle.kts`
- Modify: `docs/architecture/BITCHAT_COMPATIBILITY.md`
- Modify: `docs/architecture/BITMESSAGE_ARCHITECTURE.md`
- Modify: `docs/IMPLEMENTATION_PLAN.md`
- Modify: `docs/superpowers/plans/2026-08-08-phase-3-bitchat-wire.md`

- [ ] Write a failing Android-host test that loads every fixture, maps it to `EXECUTED`, `METADATA_ONLY`, `BLOCKED`, `LATER_PHASE`, `NOT_APPLICABLE`, or `EVIDENCE_LAYOUT_CONFLICT`, and writes deterministic JSON under the module build report directory. Assert every manifest ID has exactly one status and every executed resolved outer/announcement fixture matches production result.

```kotlin
@Test
fun everyPhaseOneFixtureHasOneExplicitProductionCoverageStatus() {
    val report = ProductionFixtureCoverage.create(manifest, codec)
    assertEquals(manifest.fixtures.map { it.id }.toSet(), report.entries.map { it.fixtureId }.toSet())
    assertTrue(report.entries.none { it.status == null })
}
```

- [ ] Add `productionCompatibilityCheck`, which removes stale Android-host protocol results, runs `ProductionFixtureCoverageTest`, requires its fresh JUnit XML, and fails unless it executes more than zero tests. It must not alter `:core:testing:compatibilityCheck`.

- [ ] Update only the three affected architecture/roadmap documents with actual module ownership, resolved coverage, evidence conflicts, blocked decisions, and explicit confirmation that Phase 4 did not start. Update the historical salvage audit only if a concrete historical reader concept is actually used; otherwise leave it unchanged.

- [ ] Run the complete gate:

```bash
rtk ./gradlew :core:testing:compatibilityCheck :protocol:bitchat:productionCompatibilityCheck :protocol:bitchat:allTests :core:foundation:allTests :core:model:allTests :core:testing:allTests :androidApp:assembleDebug :sharedLogic:linkDebugFrameworkIosSimulatorArm64
```

- [ ] Inspect JUnit XML for each protocol/foundation/model/testing target. Report actual test counts and every selected test-suite `NO-SOURCE` result. Run `rtk git diff --check`, re-index the code graph, then obtain fresh specification and code-quality reviews; fix all findings before committing.

- [ ] Commit the complete Phase 3 result only after the gate/reviews pass:

```bash
git add protocol/bitchat docs/architecture/BITCHAT_COMPATIBILITY.md docs/architecture/BITMESSAGE_ARCHITECTURE.md docs/IMPLEMENTATION_PLAN.md docs/superpowers/plans/2026-08-08-phase-3-bitchat-wire.md
git commit -m "feat: add evidence-first BitChat wire codec"
```
