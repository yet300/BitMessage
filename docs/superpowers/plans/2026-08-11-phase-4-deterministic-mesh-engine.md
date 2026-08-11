# Phase 4 Deterministic MeshEngine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a bounded, deterministic mesh reducer and serialized runtime that admit, deduplicate, dispatch, reassemble, and relay evidence-supported BitChat packets without a physical transport or cryptographic implementation.

**Architecture:** `:transport:api` contains only link observations, commands, capabilities, and typed results. `:engine:mesh` implements `Engine<MeshState, MeshEvent, MeshEffect>` as a pure reducer; a separate `MeshRuntime` owns bounded channels, reducer serialization, timers, lifecycle, and effect execution. `:protocol:bitchat` remains the only wire authority and receives the smallest evidence-backed refinements for packet identity input, signing transcripts, relay encoding, and fragment metadata.

**Tech Stack:** Kotlin Multiplatform 2.4.10, Kotlin coroutines 1.11.0, existing foundation `Engine`/time/scheduling/trace values, existing `Bytes` ownership model, `kotlin.test`, `kotlinx-coroutines-test`, pinned Apple and Android upstream harnesses.

---

## Approved scope and implementation constraints

Implement only roadmap tasks 4.1–4.5. The accepted base is `6989e092c2eb8e90d267b77659960b4d8eb4fc86`; the design checkpoint is `91f3c4d025c5ada299431fd2d335286002dd2c9f` on `codex/phase-4-deterministic-mesh-engine`.

The Phase 1 `compatibility/` tree is immutable. New Phase 4 literals live in protocol tests and the pinned reproduction harness, not in `compatibility/BitchatBaseline2026_08/fixtures.json`.

The recorded pre-implementation baseline is: Phase 1 `compatibilityCheck` 32 Android-host tests including 6 corpus tests; Phase 2 foundation 28, model 6, and testing 58 target executions (92 total); Phase 3 protocol 72 target executions plus one dedicated production-coverage gate test. Android debug assembly and the iOS simulator `SharedLogic` framework link both passed before Phase 4 production work.

### Execution blocker recorded 2026-08-11

Task 1 completed in commit `704c2484ff0473f317e131013321e98d40c22c07`. Task 2's disposable harness ran against the exact pinned Apple and Android SHAs before any protocol or engine production implementation. Packet identity, full SHA-256, 16-byte truncation, 13-byte fragment metadata, fragment literals, and out-of-order reassembly matched the planned answers.

The signing transcript did not. Both production `toBinaryDataForSigning` helpers call their encoder with padding enabled and emitted a 256-byte transcript: the planned 26-byte core `0202000102030405060708000000000200112233445566774142` followed by 230 bytes of `e6` PKCS#7-style padding. The approved 26-byte literal is therefore the unpadded semantic packet, not the bytes currently signed by either pinned client. The Apple harness failed its exact assertion, and the independently executed Android harness failed the same assertion. Per Task 2's stop rule, `SigningTranscript`, authenticated relay, and all dependent mesh production paths remain unimplemented until the approved design either adopts the reproduced 256-byte transcript or explicitly narrows/de-scopes signed interoperability. No constant was altered and `compatibility/` remains untouched.

The implementation must preserve these stage boundaries:

```text
decoded candidate
  -> bounded pending admission
  -> packet digest result
  -> signature result when the profile requires it
  -> admitted dedup insertion
  -> local dispatch and/or relay scheduling
```

No packet enters `admittedPackets` before required authentication succeeds. No fragment completion calls `MeshEngine.reduce` recursively. No reducer reads a clock, generates entropy, launches a coroutine, writes a link, or catches an I/O exception.

## File map

### Build and repository registration

- `settings.gradle.kts`: register only `:transport:api` and `:engine:mesh` plus their empty grouping projects.
- `transport/api/build.gradle.kts`: KMP module depending on foundation/model.
- `engine/mesh/build.gradle.kts`: KMP module depending on foundation/model/protocol/transport; tests may depend on testing; owns `meshEngineCheck`.
- `AGENTS.md`: record the two implemented modules, dependency direction, and verification commands.

### Protocol refinements

- `protocol/bitchat/src/commonMain/kotlin/com/yet/bitmessage/protocol/bitchat/PacketIdentity.kt`: canonical packet-ID input and fixed 16-byte result construction from a SHA-256 digest.
- `protocol/bitchat/src/commonMain/kotlin/com/yet/bitmessage/protocol/bitchat/SigningTranscript.kt`: fixed-TTL, signature-free transcript backed by pinned literals.
- `protocol/bitchat/src/commonMain/kotlin/com/yet/bitmessage/protocol/bitchat/RelayEncoding.kt`: TTL-only mutation of the retained uncompressed representation.
- `protocol/bitchat/src/commonMain/kotlin/com/yet/bitmessage/protocol/bitchat/FragmentPayloadCodec.kt`: typed 13-byte fragment metadata decode/encode.
- `protocol/bitchat/src/commonMain/kotlin/com/yet/bitmessage/protocol/bitchat/WireModel.kt`: add only the resolved fragment packet assignment and fragment values.
- `protocol/bitchat/src/commonMain/kotlin/com/yet/bitmessage/protocol/bitchat/BitchatProfile.kt`: explicit admission/relay classifications; no implicit absent-signature rule.
- `protocol/bitchat/src/commonTest/kotlin/com/yet/bitmessage/protocol/bitchat/Phase4ProtocolEvidenceTest.kt`: literal packet-ID, transcript, relay, and fragment vectors.

### Transport contracts

- `transport/api/src/commonMain/kotlin/com/yet/bitmessage/transport/api/LinkCapabilities.kt`: validated write limit/readiness facts.
- `transport/api/src/commonMain/kotlin/com/yet/bitmessage/transport/api/LinkEvent.kt`: opened/readiness/payload/closed observations.
- `transport/api/src/commonMain/kotlin/com/yet/bitmessage/transport/api/LinkCommand.kt`: correlated write/close requests.
- `transport/api/src/commonMain/kotlin/com/yet/bitmessage/transport/api/LinkResult.kt`: written/backpressured/too-large/disconnected/unsupported/failed outcomes.
- `transport/api/src/commonTest/kotlin/com/yet/bitmessage/transport/api/LinkContractTest.kt`: construction, ownership, and correlation laws.

### Mesh reducer

- `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/MeshLimits.kt`: validated production defaults and cross-limit invariants.
- `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/MeshState.kt`: immutable snapshots and focused state records.
- `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/MeshEvent.kt`: closed event hierarchy with generation and explicit time facts.
- `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/MeshEffect.kt`: closed effect hierarchy with correlation/generation.
- `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/MeshEngine.kt`: exhaustive top-level reducer dispatch only.
- `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/AdmissionReducer.kt`: decode, digest, authentication, profile, dedup, and dispatch transitions.
- `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/RelayPolicy.kt`: TTL, direct next-hop, deterministic fallback fanout, jitter, and timer transitions.
- `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/FragmentReducer.kt`: bounded fragment streams, conflicts, expiry, completion, and reinjection.
- `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/MeshTrace.kt`: redacted transition-name helpers and safe size facts.

### Serialized runtime

- `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/runtime/MeshEffectExecutor.kt`: one suspending effect boundary returning at most one correlated event.
- `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/runtime/MeshRuntime.kt`: explicit start/stop/restart/close, bounded mailboxes, ordered effects, timer jobs, and state snapshot access.
- `engine/mesh/src/commonTest/kotlin/com/yet/bitmessage/engine/mesh/runtime/MeshRuntimeTest.kt`: lifecycle, pressure, ordering, cancellation, and generation races.

### Reducer tests

- `engine/mesh/src/commonTest/kotlin/com/yet/bitmessage/engine/mesh/MeshFixtures.kt`: test-only packet/link/event builders.
- `engine/mesh/src/commonTest/kotlin/com/yet/bitmessage/engine/mesh/AdmissionReducerTest.kt`: security ordering and bounded pending/admitted state.
- `engine/mesh/src/commonTest/kotlin/com/yet/bitmessage/engine/mesh/RelayPolicyTest.kt`: TTL, jitter, target selection, signature-compatible bytes, and duplicate cancellation.
- `engine/mesh/src/commonTest/kotlin/com/yet/bitmessage/engine/mesh/FragmentReducerTest.kt`: reorder, duplicate, conflict, quota, expiry, and completion.
- `engine/mesh/src/commonTest/kotlin/com/yet/bitmessage/engine/mesh/MeshPropertyTest.kt`: deterministic bounded event sequences.
- `engine/mesh/src/commonTest/kotlin/com/yet/bitmessage/engine/mesh/MeshEngineCoverageTest.kt`: non-zero Android-host gate anchor.

## Task 1: Register the two Phase 4 modules and non-zero test gate

**Files:**
- Modify: `settings.gradle.kts`
- Create: `transport/api/build.gradle.kts`
- Create: `engine/mesh/build.gradle.kts`
- Create: `engine/mesh/src/commonTest/kotlin/com/yet/bitmessage/engine/mesh/MeshEngineCoverageTest.kt`
- Modify: `AGENTS.md`

- [ ] Add only the Phase 4 projects beside the existing protocol entry.

```kotlin
include(":transport")
include(":transport:api")

include(":engine")
include(":engine:mesh")
```

- [ ] Configure the production dependency graph exactly.

```kotlin
// transport/api/build.gradle.kts
plugins { alias(libs.plugins.local.kotlin.multiplatform) }

kotlin {
    sourceSets.commonMain.dependencies {
        implementation(projects.core.foundation)
        implementation(projects.core.model)
    }
}
```

```kotlin
// engine/mesh/build.gradle.kts
plugins { alias(libs.plugins.local.kotlin.multiplatform) }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.foundation)
            implementation(projects.core.model)
            implementation(projects.protocol.bitchat)
            implementation(projects.transport.api)
        }
        commonTest.dependencies {
            implementation(projects.core.testing)
        }
    }
}
```

- [ ] Write the gate anchor before engine production code.

```kotlin
class MeshEngineCoverageTest {
    @Test
    fun phaseFourEngineSuiteIsPresent() {
        assertTrue(true)
    }
}
```

- [ ] Add `meshEngineCheck` following the existing Phase 3 gate shape: delete stale `testAndroidHostTest` XML, run the Android-host test task, require `TEST-com.yet.bitmessage.engine.mesh.MeshEngineCoverageTest.xml`, and assert its `tests` attribute is greater than zero.

- [ ] Run `rtk ./gradlew :transport:api:allTests :engine:mesh:meshEngineCheck :engine:mesh:allTests --console=plain`. Expect `:transport:api` to report `NO-SOURCE` temporarily and the engine anchor to execute on Android and iOS.

- [ ] Update `AGENTS.md` with actual module rows and dependency arrows. Do not list Bluetooth, simulation, crypto, delivery, sync, media, domain, or data modules.

- [ ] Commit the module boundary.

```bash
rtk git add settings.gradle.kts transport/api engine/mesh AGENTS.md
rtk git commit -m "build: add Phase 4 mesh modules"
```

## Task 2: Reproduce and implement packet identity, signing, relay, and fragment evidence

**Files:**
- Create: `tools/upstream-compat/apple/BitMessagePhase4EvidenceTests.swift`
- Create: `tools/upstream-compat/android/BitMessagePhase4EvidenceTest.kt`
- Modify: `tools/upstream-compat/install-harness.sh`
- Modify: `tools/upstream-compat/verify-upstreams.sh`
- Modify: `tools/upstream-compat/README.md`
- Create: `protocol/bitchat/src/commonMain/kotlin/com/yet/bitmessage/protocol/bitchat/PacketIdentity.kt`
- Modify: `protocol/bitchat/src/commonMain/kotlin/com/yet/bitmessage/protocol/bitchat/SigningTranscript.kt`
- Create: `protocol/bitchat/src/commonMain/kotlin/com/yet/bitmessage/protocol/bitchat/RelayEncoding.kt`
- Create: `protocol/bitchat/src/commonMain/kotlin/com/yet/bitmessage/protocol/bitchat/FragmentPayloadCodec.kt`
- Modify: `protocol/bitchat/src/commonMain/kotlin/com/yet/bitmessage/protocol/bitchat/WireModel.kt`
- Modify: `protocol/bitchat/src/commonMain/kotlin/com/yet/bitmessage/protocol/bitchat/BitchatProfile.kt`
- Create: `protocol/bitchat/src/commonTest/kotlin/com/yet/bitmessage/protocol/bitchat/Phase4ProtocolEvidenceTest.kt`
- Modify: `protocol/bitchat/src/commonTest/kotlin/com/yet/bitmessage/protocol/bitchat/RawRetentionTest.kt`

- [ ] Extend the disposable pinned harness, keeping the SHAs unchanged. Both clients must emit or accept these exact known answers from their production packet-ID, signing, binary, and fragment code paths:

```text
packet identity input: 02001122334455667701020304050607084142
SHA-256:              25429fbd15e2051049307f8e650ae863fc909a182e634a6b6c171b1aa51b4fda
wire packet ID:       25429fbd15e2051049307f8e650ae863
signing transcript:   0202000102030405060708000000000200112233445566774142
fragment metadata:    00010203040506070001000202aabb
fragment zero:        0001020304050607000000020202020301020304050607080000
fragment one:         0001020304050607000100020200000200112233445566774142
reassembled packet:   0202030102030405060708000000000200112233445566774142
```

The semantic packet for identity is type `0x02`, sender `0011223344556677`, timestamp `0x0102030405060708`, and payload `4142`. The signing case is v2, received TTL 7, signature flag set, and 64 bytes of `5a`; its transcript fixes TTL to 0 and removes the signature flag/bytes. The relay case changes received TTL 7 to 6 while retaining the signature and producing the same transcript. The complete fragment case splits the 26-byte v2 broadcast packet into two 13-byte data portions and proves out-of-order assembly produces the exact original packet.

- [ ] Install and run the extended harness against only the pinned clones.

```bash
rtk tools/upstream-compat/prepare-upstreams.sh /tmp/bitmessage-phase4-upstreams
rtk tools/upstream-compat/install-harness.sh /tmp/bitmessage-phase4-upstreams
rtk tools/upstream-compat/verify-upstreams.sh /tmp/bitmessage-phase4-upstreams
```

Expected: the existing Phase 1 producer/reciprocal-acceptance paths remain green and both new Phase 4 tests print matching `BITMESSAGE_PHASE4` literals. If either client disagrees, stop the dependent production path and record the observed discrepancy instead of altering these constants.

- [ ] Write `Phase4ProtocolEvidenceTest` first. It asserts the exact identity input, digest truncation, signing transcript, TTL-only relay output, signature preservation, transcript equality before/after relay, both complete-reassembly fragment literals, fragment decode/encode, and rejection of short/zero-count/out-of-range fragments. Replace the former `RawRetentionTest.signingTranscriptIsExplicitlyBlockedWithoutLiteralTranscriptEvidence` assertion with the positive pinned transcript assertion while retaining compressed/padded blocks.

```kotlin
@Test
fun packetIdentityMatchesBothPinnedClients() {
    val packet = decode("0202030102030405060708000000000200112233445566774142")
    assertEquals(
        bytes("02001122334455667701020304050607084142"),
        PacketIdentity.input(packet).canonicalBytes,
    )
    assertEquals(
        PacketId.of(bytes("25429fbd15e2051049307f8e650ae863")),
        PacketIdentity.fromSha256(bytes("25429fbd15e2051049307f8e650ae863fc909a182e634a6b6c171b1aa51b4fda")),
    )
}

@Test
fun relayTtlMutationKeepsSigningTranscriptAndSignature() {
    val packet = decode(signedTtlSeven)
    val relayed = assertIs<EncodeResult.Success>(RelayEncoding.withTtl(packet, 6u)).bytes
    val relayedPacket = decode(relayed)
    assertEquals(SigningTranscript.build(packet), SigningTranscript.build(relayedPacket))
    assertEquals(packet.signature, relayedPacket.signature)
}
```

- [ ] Run `rtk ./gradlew :protocol:bitchat:allTests --console=plain`. Expect failures because packet identity, positive transcript, relay encoding, and fragment codec are absent.

- [ ] Implement fixed-size protocol values and canonical input. Hash execution remains an engine effect; the protocol owns field assembly and 16-byte truncation.

```kotlin
@JvmInline
value class PacketId private constructor(val value: Bytes) {
    companion object {
        const val BYTE_SIZE = 16
        fun of(value: Bytes): PacketId =
            PacketId(Bytes.requireExactSize(value.copyToByteArray(), BYTE_SIZE))
    }
}

data class PacketIdentityInput(val canonicalBytes: Bytes)

object PacketIdentity {
    fun input(packet: DecodedPacket): PacketIdentityInput {
        val sender = packet.sender.value.value.copyToByteArray()
        val payload = packet.payload.copyToByteArray()
        val canonical = ByteArray(1 + sender.size + ULong.SIZE_BYTES + payload.size)
        canonical[0] = packet.type.value.toByte()
        sender.copyInto(canonical, destinationOffset = 1)
        repeat(ULong.SIZE_BYTES) { index ->
            val shift = (ULong.SIZE_BYTES - index - 1) * Byte.SIZE_BITS
            canonical[1 + sender.size + index] = (packet.timestamp shr shift).toByte()
        }
        payload.copyInto(canonical, destinationOffset = 1 + sender.size + ULong.SIZE_BYTES)
        return PacketIdentityInput(Bytes.copyOf(canonical))
    }

    fun fromSha256(digest: Bytes): PacketId {
        val exactDigest = Bytes.requireExactSize(digest.copyToByteArray(), 32).copyToByteArray()
        return PacketId.of(Bytes.copyOf(exactDigest.copyOfRange(0, PacketId.BYTE_SIZE)))
    }
}
```

`input` writes type, exact eight-byte sender, big-endian timestamp, and retained payload in that order. `fromSha256` requires exactly 32 digest bytes and copies the first 16.

- [ ] Implement the transcript by copying the decoded packet with TTL 0, clearing only `HAS_SIGNATURE`, removing the signature, and using the matching v1/v2 encoder. Reject compressed or padded signing paths as `UNSUPPORTED_FEATURE`; the paired foreign-compression fixes remain unmerged.

```kotlin
object SigningTranscript {
    fun build(packet: DecodedPacket): DecodeResult<Bytes> {
        if (packet.flags.isCompressed || packet.flags.hasPadding) {
            return DecodeResult.Failure(DecodeError.UNSUPPORTED_FEATURE)
        }
        val flags = PacketFlags.of(
            (packet.flags.value.toUInt() and PacketFlags.SIGNATURE_BIT.inv()).toUByte(),
        )
        return when (val encoded = BitchatCodec.encode(packet.copy(ttl = 0u, flags = flags, signature = null))) {
            is EncodeResult.Success -> DecodeResult.Success(encoded.bytes)
            is EncodeResult.Failure -> DecodeResult.Failure(DecodeError.PROFILE_VIOLATION)
        }
    }
}
```

- [ ] Implement `RelayEncoding.withTtl` inside the protocol module. It validates a retained supported packet, rejects compressed/padded relay, copies raw bytes, changes byte offset 2 only, decodes the result, and asserts all fields other than TTL remain equal before returning success.

```kotlin
object RelayEncoding {
    fun withTtl(packet: DecodedPacket, outgoingTtl: UByte): EncodeResult {
        if (packet.flags.isCompressed || packet.flags.hasPadding) {
            return EncodeResult.Failure(EncodeError.UNSUPPORTED_FEATURE)
        }
        val updated = packet.rawPacket.wireBytes.copyToByteArray()
        if (updated.size < 3) return EncodeResult.Failure(EncodeError.INVALID_LENGTH)
        updated[2] = outgoingTtl.toByte()
        val bytes = Bytes.copyOf(updated)
        val decoded = (BitchatCodec.decode(bytes) as? DecodeResult.Success)?.value
            ?: return EncodeResult.Failure(EncodeError.PROFILE_VIOLATION)
        val restored = decoded.copy(ttl = packet.ttl, rawPacket = packet.rawPacket)
        return if (restored == packet) {
            EncodeResult.Success(bytes)
        } else {
            EncodeResult.Failure(EncodeError.PROFILE_VIOLATION)
        }
    }
}
```

- [ ] Add `FRAGMENT(0x20u)` as decode-supported but not ordinary profile-emittable. Implement `FragmentId` as exactly eight bytes and `FragmentPayloadCodec` for `id[8] | index[2] | total[2] | originalType[1] | data`, with big-endian unsigned indexes, `total > 0`, and `index < total`.

```kotlin
@JvmInline
value class FragmentId private constructor(val value: Bytes) {
    companion object {
        const val BYTE_SIZE = 8
        fun of(value: Bytes): FragmentId =
            FragmentId(Bytes.requireExactSize(value.copyToByteArray(), BYTE_SIZE))
    }
}

data class FragmentPayload(
    val id: FragmentId,
    val index: UShort,
    val total: UShort,
    val originalType: PacketType,
    val data: Bytes,
) {
    init {
        require(total > 0.toUShort())
        require(index < total)
    }
}

object FragmentPayloadCodec {
    const val HEADER_BYTES = 13

    fun decode(payload: Bytes): DecodeResult<FragmentPayload> {
        if (payload.size < HEADER_BYTES) return DecodeResult.Failure(DecodeError.TRUNCATED)
        val bytes = payload.copyToByteArray()
        val index = (((bytes[8].toInt() and 0xff) shl 8) or (bytes[9].toInt() and 0xff)).toUShort()
        val total = (((bytes[10].toInt() and 0xff) shl 8) or (bytes[11].toInt() and 0xff)).toUShort()
        if (total == 0.toUShort() || index >= total) {
            return DecodeResult.Failure(DecodeError.MALFORMED_FIELD)
        }
        return DecodeResult.Success(
            FragmentPayload(
                id = FragmentId.of(Bytes.copyOf(bytes.copyOfRange(0, FragmentId.BYTE_SIZE))),
                index = index,
                total = total,
                originalType = PacketType.of(bytes[12].toUByte()),
                data = Bytes.copyOf(bytes.copyOfRange(HEADER_BYTES, bytes.size)),
            ),
        )
    }

    fun encode(fragment: FragmentPayload): EncodeResult {
        val data = fragment.data.copyToByteArray()
        val output = ByteArray(HEADER_BYTES + data.size)
        fragment.id.value.copyToByteArray().copyInto(output)
        output[8] = (fragment.index.toInt() ushr 8).toByte()
        output[9] = fragment.index.toByte()
        output[10] = (fragment.total.toInt() ushr 8).toByte()
        output[11] = fragment.total.toByte()
        output[12] = fragment.originalType.value.toByte()
        data.copyInto(output, destinationOffset = HEADER_BYTES)
        return EncodeResult.Success(Bytes.copyOf(output))
    }
}
```

- [ ] Add explicit profile classification: `MESSAGE` is locally publishable and relayable; `FRAGMENT` is locally reassemblable and outer-relay blocked; either type requests verification when a signature is present, while unsigned acceptance is a named profile rule rather than an inference inside the engine.

```kotlin
enum class PacketAdmissionPolicy {
    VERIFY_SIGNATURE,
    ALLOW_UNSIGNED_MESSAGE,
    ALLOW_UNSIGNED_FRAGMENT,
    REJECT,
}

fun BitchatBaseline2026_08.admissionPolicy(packet: DecodedPacket): PacketAdmissionPolicy =
    when (packet.type.knownType) {
        KnownPacketType.MESSAGE ->
            if (packet.signature == null) PacketAdmissionPolicy.ALLOW_UNSIGNED_MESSAGE
            else PacketAdmissionPolicy.VERIFY_SIGNATURE
        KnownPacketType.FRAGMENT ->
            if (packet.signature == null) PacketAdmissionPolicy.ALLOW_UNSIGNED_FRAGMENT
            else PacketAdmissionPolicy.VERIFY_SIGNATURE
        null -> PacketAdmissionPolicy.REJECT
    }
```

- [ ] Run both protocol gates and the unchanged Phase 1 gate.

```bash
rtk ./gradlew :core:testing:compatibilityCheck :protocol:bitchat:productionCompatibilityCheck :protocol:bitchat:allTests --console=plain
```

Expected: all existing Phase 1/3 counts remain non-zero and green; the new protocol evidence tests execute on Android and iOS.

- [ ] Confirm `rtk git diff --exit-code 6989e09 -- compatibility` produces no output, then commit.

```bash
rtk git add tools/upstream-compat protocol/bitchat/src
rtk git commit -m "feat: add Phase 4 protocol evidence"
```

## Task 3: Define the transport-neutral link contract

**Files:**
- Create: `transport/api/src/commonMain/kotlin/com/yet/bitmessage/transport/api/LinkCapabilities.kt`
- Create: `transport/api/src/commonMain/kotlin/com/yet/bitmessage/transport/api/LinkEvent.kt`
- Create: `transport/api/src/commonMain/kotlin/com/yet/bitmessage/transport/api/LinkCommand.kt`
- Create: `transport/api/src/commonMain/kotlin/com/yet/bitmessage/transport/api/LinkResult.kt`
- Create: `transport/api/src/commonTest/kotlin/com/yet/bitmessage/transport/api/LinkContractTest.kt`

- [ ] Write failing contract tests for positive write limits, immutable payload ownership, distinct link IDs, write correlation/generation, and every result category.

```kotlin
@Test
fun writeCarriesImmutableBytesAndCorrelation() {
    val source = byteArrayOf(1, 2, 3)
    val command = LinkCommand.Write(
        linkId = LinkId.of("link-a"),
        correlationId = CorrelationId.of("write-1"),
        generation = Generation(4),
        bytes = Bytes.copyOf(source),
    )
    source[0] = 9
    assertEquals(1, command.bytes[0].toInt())
}
```

- [ ] Run `rtk ./gradlew :transport:api:allTests --console=plain`. Expect unresolved contract types.

- [ ] Implement only these closed shapes.

```kotlin
data class LinkCapabilities(
    val maxWriteBytes: Int,
    val writeReady: Boolean,
) {
    init { require(maxWriteBytes > 0) }
}

enum class LinkCloseReason {
    LOCAL_REQUEST,
    REMOTE_CLOSED,
    TRANSPORT_FAILED,
}

enum class LinkFailureCode {
    TRANSIENT,
    PERMANENT,
    CANCELLED,
}

sealed interface LinkEvent {
    val linkId: LinkId
    data class Opened(override val linkId: LinkId, val capabilities: LinkCapabilities) : LinkEvent
    data class ReadinessChanged(override val linkId: LinkId, val capabilities: LinkCapabilities) : LinkEvent
    data class PayloadReceived(override val linkId: LinkId, val bytes: Bytes) : LinkEvent
    data class Closed(override val linkId: LinkId, val reason: LinkCloseReason) : LinkEvent
}

sealed interface LinkCommand {
    val linkId: LinkId
    val correlationId: CorrelationId
    val generation: Generation

    data class Write(
        override val linkId: LinkId,
        override val correlationId: CorrelationId,
        override val generation: Generation,
        val bytes: Bytes,
    ) : LinkCommand

    data class Close(
        override val linkId: LinkId,
        override val correlationId: CorrelationId,
        override val generation: Generation,
        val reason: LinkCloseReason,
    ) : LinkCommand
}

sealed interface LinkResult {
    val linkId: LinkId
    val correlationId: CorrelationId
    val generation: Generation

    data class Written(override val linkId: LinkId, override val correlationId: CorrelationId, override val generation: Generation) : LinkResult
    data class Backpressured(override val linkId: LinkId, override val correlationId: CorrelationId, override val generation: Generation) : LinkResult
    data class PayloadTooLarge(override val linkId: LinkId, override val correlationId: CorrelationId, override val generation: Generation, val maximumBytes: Int) : LinkResult
    data class Disconnected(override val linkId: LinkId, override val correlationId: CorrelationId, override val generation: Generation) : LinkResult
    data class Unsupported(override val linkId: LinkId, override val correlationId: CorrelationId, override val generation: Generation) : LinkResult
    data class Failed(override val linkId: LinkId, override val correlationId: CorrelationId, override val generation: Generation, val code: LinkFailureCode) : LinkResult
}
```

`LinkResult` has `Written`, `Backpressured`, `PayloadTooLarge`, `Disconnected`, `Unsupported`, and `Failed`; every result repeats link/correlation/generation. `Failed` carries a redacted `LinkFailureCode` enum, never an exception message or payload.

- [ ] Verify the module has no imports from `protocol`, BlueFalcon, Android, Apple, UI, database, or application packages.

- [ ] Run `rtk ./gradlew :transport:api:allTests --rerun-tasks --console=plain`; expect Android and iOS execution.

- [ ] Commit.

```bash
rtk git add transport/api
rtk git commit -m "feat: define transport link contracts"
```

## Task 4: Define validated mesh limits, immutable state, events, effects, and traces

**Files:**
- Create: `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/MeshLimits.kt`
- Create: `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/MeshState.kt`
- Create: `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/MeshEvent.kt`
- Create: `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/MeshEffect.kt`
- Create: `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/MeshTrace.kt`
- Create: `engine/mesh/src/commonTest/kotlin/com/yet/bitmessage/engine/mesh/MeshStateContractTest.kt`
- Create: `engine/mesh/src/commonTest/kotlin/com/yet/bitmessage/engine/mesh/MeshFixtures.kt`

- [ ] Write failing tests for every production default, invalid/overflowing configurations, defensive collection snapshots, distinct pending/admitted stores, aggregate byte counters, and deterministic correlation issuance.

- [ ] Implement `MeshLimits` with the approved defaults plus byte and fanout bounds required to keep retained pending packets and relay bursts finite.

```kotlin
data class MeshLimits(
    val maxActiveLinks: Int = 32,
    val maxPeerObservations: Int = 256,
    val maxPeerObservationsPerLink: Int = 8,
    val maxPendingAdmissions: Int = 256,
    val maxPendingAdmissionsPerLink: Int = 8,
    val maxPendingPacketBytes: Int = 128 * 1024,
    val maxAggregatePendingBytes: Int = 4 * 1024 * 1024,
    val pendingAdmissionLifetime: Duration = 15.seconds,
    val maxAdmittedPacketIds: Int = 10_000,
    val dedupLifetime: Duration = 5.minutes,
    val maxFragmentStreams: Int = 64,
    val maxFragmentStreamsPerSource: Int = 4,
    val maxFragmentsPerStream: Int = 256,
    val maxFragmentStreamBytes: Int = 128 * 1024,
    val maxAggregateFragmentBytes: Int = 4 * 1024 * 1024,
    val fragmentLifetime: Duration = 30.seconds,
    val maxRouteObservations: Int = 512,
    val maxRouteObservationsPerSource: Int = 16,
    val routeLifetime: Duration = 3.minutes,
    val maxScheduledRelays: Int = 512,
    val maxScheduledRelaysPerSource: Int = 8,
    val maxRelayFanout: Int = 8,
    val eventMailboxCapacity: Int = 256,
    val effectQueueCapacity: Int = 256,
    val traceBufferCapacity: Int = 256,
)
```

Validate positive capacities, finite positive lifetimes, aggregate byte limits greater than or equal to per-item limits, and `effectQueueCapacity >= maxRelayFanout + 8` so one bounded transition cannot deadlock while publishing its effects.

- [ ] Centralize capacity preparation: before evaluating any attacker-influenced limit, remove entries whose explicit expiry is at or before the event's `observedAt`, repair aggregate counters in the same transition, and let their later timer callbacks be rejected as stale. Do not emit hundreds of cancellation effects during opportunistic cleanup; timer jobs remain bounded by the collection limits and are cancelled normally on stop.

- [ ] Define focused immutable records: `ActiveLink`, `PeerBinding`, `PendingAdmission`, `AdmittedPacket`, `FragmentStreamKey`, `FragmentStream`, `RouteObservation`, and `ScheduledRelay`. `MeshState` owns copied maps and exposes copied read-only views.

```kotlin
class SnapshotMap<K, V>(values: Map<K, V> = emptyMap()) : Map<K, V> by values.toMap() {
    private val snapshot = values.toMap()

    override fun equals(other: Any?): Boolean = other is Map<*, *> && snapshot == other
    override fun hashCode(): Int = snapshot.hashCode()
    override fun toString(): String = snapshot.toString()
}

data class MeshState(
    val generation: Generation,
    val localPeer: WirePeerId,
    val observedAt: MonotonicTime,
    val lifecycle: MeshLifecycle,
    val links: SnapshotMap<LinkId, ActiveLink> = SnapshotMap(),
    val provisionalBindings: SnapshotMap<PeerLinkKey, PeerBinding> = SnapshotMap(),
    val pendingAdmissions: SnapshotMap<CorrelationId, PendingAdmission> = SnapshotMap(),
    val admittedPackets: SnapshotMap<PacketId, AdmittedPacket> = SnapshotMap(),
    val fragmentStreams: SnapshotMap<FragmentStreamKey, FragmentStream> = SnapshotMap(),
    val routeObservations: SnapshotMap<PacketId, RouteObservation> = SnapshotMap(),
    val scheduledRelays: SnapshotMap<PacketId, ScheduledRelay> = SnapshotMap(),
    val aggregatePendingBytes: Int = 0,
    val aggregateFragmentBytes: Int = 0,
    val nextCorrelationSequence: Long = 0,
)
```

- [ ] Define `PacketSource` as `Link(linkId)` or `Reassembled(ingressLink, fragmentId)`. Define `MeshEvent` as a sealed interface. Every asynchronous result includes `generation`, `correlationId`, and `observedAt`. Link input is wrapped as `MeshEvent.LinkObserved(generation, observedAt, event)`. Result cases are `PacketDecoded`, `PacketDigestComputed`, `SignatureVerified`, `FragmentPayloadDecoded`, `RelayEncoded`, `EntropyProvided`, `TimerElapsed`, `LinkCompleted`, `EffectFailed`, `RuntimeStarted`, and `RuntimeStopping`. `MeshFailureCode` distinguishes decode, digest, verification, encoding, timer, write, and internal execution failures without retaining exception text.

- [ ] Define `MeshEffect` as a sealed interface. Correlated cases are `DecodePacket`, `ComputePacketDigest`, `VerifySignature`, `DecodeFragmentPayload`, `EncodeRelay`, `RequestEntropy`, `Schedule`, `Cancel`, `WriteLink`, `CloseLink`, and `PublishPublicPayload`. Only `PublishPublicPayload` crosses the application boundary; do not add future Noise/sync/delivery/media receivers.

```kotlin
sealed interface MeshEffect {
    val correlationId: CorrelationId
    val generation: Generation

    data class VerifySignature(
        override val correlationId: CorrelationId,
        override val generation: Generation,
        val sender: WirePeerId,
        val transcript: Bytes,
        val signature: Bytes,
    ) : MeshEffect
}
```

- [ ] Implement trace helpers using `TraceRecord`, `TransitionName`, `TraceDecision`, and safe `TraceSize` facts. No trace type accepts packet payload, transcript, signature, raw packet, full peer bytes, or full packet ID.

- [ ] Run `rtk ./gradlew :engine:mesh:allTests --console=plain`; expect state-contract tests green on both targets.

- [ ] Commit.

```bash
rtk git add engine/mesh/src/commonMain engine/mesh/src/commonTest
rtk git commit -m "feat: define bounded mesh state contracts"
```

## Task 5: Add the pure top-level reducer and link/decode transitions

**Files:**
- Create: `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/MeshEngine.kt`
- Create: `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/AdmissionReducer.kt`
- Create: `engine/mesh/src/commonTest/kotlin/com/yet/bitmessage/engine/mesh/LinkReducerTest.kt`

- [ ] Write failing tests for open/readiness/close, active-link capacity, payload size rejection before pending allocation, decode effect emission, decode failure, unknown correlation, and stale generation.

```kotlin
@Test
fun bytesReceivedEmitsDecodeWithoutParsingInsideTheEngine() {
    val transition = engine.reduce(
        stateWithReadyLink,
        MeshEvent.LinkObserved(
            generation = stateWithReadyLink.generation,
            observedAt = now,
            event = LinkEvent.PayloadReceived(linkA, bytes("0202030102030405060708000000000200112233445566774142")),
        ),
    )
    assertIs<MeshEffect.DecodePacket>(transition.effects.single())
    assertTrue(transition.state.pendingAdmissions.isEmpty())
}
```

- [ ] Run `rtk ./gradlew :engine:mesh:allTests --console=plain`. Expect missing reducer symbols.

- [ ] Implement `MeshEngine` as an exhaustive closed-domain dispatch with no `else`.

```kotlin
class MeshEngine(
    private val limits: MeshLimits = MeshLimits(),
) : Engine<MeshState, MeshEvent, MeshEffect> {
    override fun reduce(state: MeshState, event: MeshEvent): Transition<MeshState, MeshEffect> =
        when (event) {
            is MeshEvent.LinkObserved -> reduceLink(state, event, limits)
            is MeshEvent.PacketDecoded -> reduceDecoded(state, event, limits)
            is MeshEvent.PacketDigestComputed -> reduceDigest(state, event, limits)
            is MeshEvent.SignatureVerified -> reduceSignature(state, event, limits)
            is MeshEvent.FragmentPayloadDecoded -> reduceFragment(state, event, limits)
            is MeshEvent.RelayEncoded -> reduceRelayEncoded(state, event)
            is MeshEvent.EntropyProvided -> reduceEntropy(state, event, limits)
            is MeshEvent.TimerElapsed -> reduceTimer(state, event, limits)
            is MeshEvent.LinkCompleted -> reduceLinkResult(state, event)
            is MeshEvent.EffectFailed -> reduceEffectFailure(state, event)
            is MeshEvent.RuntimeStarted -> reduceRuntimeStart(state, event)
            is MeshEvent.RuntimeStopping -> reduceRuntimeStop(state, event)
        }
}
```

- [ ] Link open validates capacity before copying state. Link close removes its provisional bindings, link-scoped pending admissions, and relay eligibility, cancelling their timers in stable correlation order. It does not remove unexpired admitted packet IDs.

- [ ] Payload input validates runtime generation, lifecycle, active link, and `maxPendingPacketBytes`, then emits `DecodePacket`. Decode success reserves pending admission only after checking global count, per-link count, and aggregate pending bytes; decode failure emits a typed rejected trace and retains no state.

- [ ] Run the engine tests twice with `--rerun-tasks` and assert transition equality for the same state/event input.

- [ ] Commit.

```bash
rtk git add engine/mesh/src
rtk git commit -m "feat: reduce mesh link and decode events"
```

## Task 6: Implement authenticate-before-dedup admission

**Files:**
- Modify: `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/AdmissionReducer.kt`
- Create: `engine/mesh/src/commonTest/kotlin/com/yet/bitmessage/engine/mesh/AdmissionReducerTest.kt`

- [ ] Write the mandatory failing security sequence and retain the admitted cache sentinel across repeated invalid packets.

```kotlin
@Test
fun invalidSignatureNeverPoisonsAdmittedDedup() {
    val sentinel = PacketId.of(bytes("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
    var state = stateWithAdmitted(sentinel)
    val decoded = driveDecodedSignedCandidate(state)
    state = decoded.state
    val digest = assertIs<MeshEffect.ComputePacketDigest>(decoded.effects.single())
    val awaitingSignature = engine.reduce(state, digestSuccess(digest, sha256Digest))
    val verify = assertIs<MeshEffect.VerifySignature>(awaitingSignature.effects.single { it is MeshEffect.VerifySignature })
    val rejected = engine.reduce(awaitingSignature.state, signatureResult(verify, authentic = false))

    assertEquals(setOf(sentinel), rejected.state.admittedPackets.keys)
    assertTrue(rejected.state.pendingAdmissions.isEmpty())
    assertTrue(rejected.effects.none { it is MeshEffect.PublishPublicPayload || it is MeshEffect.RequestEntropy })
}
```

- [ ] Add tests for signed valid admission, unsigned profile-authorized admission, authentication timeout, pending global/per-link/byte pressure, stale digest, stale verification, mismatched correlation, and digest result with the wrong byte length.

- [ ] Run `rtk ./gradlew :engine:mesh:allTests --console=plain`. Expect admission tests to fail.

- [ ] Implement the exact pending stages.

```kotlin
sealed interface AdmissionStage {
    data object AwaitingDigest : AdmissionStage
    data class AwaitingSignature(val packetId: PacketId) : AdmissionStage
}
```

`reduceDecoded` stores `AwaitingDigest`, schedules a 15-second timeout, and emits `ComputePacketDigest(PacketIdentity.input(packet))`. `reduceDigest` converts the 32-byte result through `PacketIdentity.fromSha256`; it then asks the protocol profile for `VERIFY_SIGNATURE`, `ALLOW_UNSIGNED_MESSAGE`, `ALLOW_UNSIGNED_FRAGMENT`, or `REJECT`.

- [ ] For `VERIFY_SIGNATURE`, call `SigningTranscript.build`, replace the pending stage with `AwaitingSignature(packetId)`, and emit `VerifySignature`. Transcript failure rejects and removes pending state. Signature failure removes pending state, subtracts retained bytes, cancels its timeout, records `mesh.admission.signature_rejected`, and touches no admitted/binding/topology/relay/dispatch collection.

- [ ] On signature success or named unsigned authorization, call one `admit` function. It removes expired admitted IDs using `event.observedAt`, checks the ID atomically, and returns one of `NEW`, `DUPLICATE`, or `DEDUP_CAPACITY_REACHED`. Only `NEW` inserts the ID with `observedAt + dedupLifetime`.

```kotlin
private fun admit(
    state: MeshState,
    pendingId: CorrelationId,
    packetId: PacketId,
    observedAt: MonotonicTime,
    limits: MeshLimits,
): Transition<MeshState, MeshEffect>
```

- [ ] A duplicate removes pending state and emits no dispatch or new relay. If the same authenticated packet has a scheduled relay, it emits `Cancel` and removes that relay. An unauthenticated duplicate candidate never reaches this branch.

- [ ] A new admission may record one provisional `(sender, ingress link)` observation only after authentication or named unsigned profile admission. Enforce global/per-link observation limits and `routeLifetime`; a full observation store does not weaken admission security and simply records a bounded observation rejection. Invalid signatures never create or refresh a binding.

- [ ] For an admitted packet carrying a source route, store one immutable route observation keyed by admitted packet ID, bounded globally/per source and expiring after `routeLifetime`. Maintain one earliest topology-expiry timer for both route observations and provisional bindings; stale topology timers are ignored.

- [ ] Add a deterministic loop of `maxPendingAdmissions * 3` unique invalid candidates. Assert pending count/bytes never exceed limits and the sentinel admitted ID is never displaced.

- [ ] Run `rtk ./gradlew :engine:mesh:allTests --rerun-tasks --console=plain`; expect all admission tests green.

- [ ] Commit.

```bash
rtk git add engine/mesh/src
rtk git commit -m "feat: authenticate before mesh dedup"
```

## Task 7: Add local dispatch, dedup expiry, and TTL classification

**Files:**
- Modify: `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/AdmissionReducer.kt`
- Create: `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/RelayPolicy.kt`
- Modify: `engine/mesh/src/commonTest/kotlin/com/yet/bitmessage/engine/mesh/AdmissionReducerTest.kt`
- Create: `engine/mesh/src/commonTest/kotlin/com/yet/bitmessage/engine/mesh/RelayPolicyTest.kt`

- [ ] Write failing tests for broadcast/local recipient dispatch, non-local recipient no-dispatch, fragment no-public-dispatch, dedup expiry, a full unexpired dedup cache, and received TTL values 0, 1, 2, 7, and 255.

```kotlin
@Test
fun ttlBoundariesSeparateLocalDispatchFromRelay() {
    assertEquals(null, RelayPolicy.outgoingTtl(0u))
    assertEquals(null, RelayPolicy.outgoingTtl(1u))
    assertEquals(1u, RelayPolicy.outgoingTtl(2u))
    assertEquals(6u, RelayPolicy.outgoingTtl(7u))
    assertEquals(6u, RelayPolicy.outgoingTtl(255u))
}
```

- [ ] Run the engine tests and expect TTL/dispatch failures.

- [ ] Implement the checked TTL operation exactly.

```kotlin
internal fun outgoingTtl(received: UByte): UByte? {
    val capped = minOf(received.toInt(), 7)
    return if (capped < 2) null else (capped - 1).toUByte()
}
```

- [ ] After a new admitted `MESSAGE`, emit `PublishPublicPayload` only when recipient is absent or equals `state.localPeer`. The effect contains packet ID, sender, ingress link, timestamp, and immutable payload. It contains no messenger `MessageId`, conversation, persistence, or delivery status.

- [ ] After a new admitted `FRAGMENT`, emit `DecodeFragmentPayload` instead of public dispatch. Fragment outer relay remains blocked by the profile.

- [ ] Maintain one earliest-expiry dedup timer rather than one timer per admitted ID. On insert or expiry, cancel/reschedule only when the earliest deadline changes. A stale timer generation or replaced timer ID is ignored.

- [ ] Run all engine tests; assert local dispatch still occurs for TTL 0/1 while relay effects remain absent.

- [ ] Commit.

```bash
rtk git add engine/mesh/src
rtk git commit -m "feat: add bounded dedup and TTL policy"
```

## Task 8: Implement deterministic relay selection, jitter, and write effects

**Files:**
- Modify: `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/RelayPolicy.kt`
- Modify: `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/AdmissionReducer.kt`
- Modify: `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/MeshEngine.kt`
- Modify: `engine/mesh/src/commonTest/kotlin/com/yet/bitmessage/engine/mesh/RelayPolicyTest.kt`

- [ ] Write failing tests for ingress exclusion, closed/unready/too-small links, stable `LinkId.value` ordering, fanout cap, explicit source-route next hop, ambiguous binding fallback, self-loop route suppression, binding/topology expiry, route global/per-source limits, scheduled-relay global/per-source limits, exact two-byte entropy mapping, timer correlation, signed relay bytes, link results, and authenticated duplicate cancellation.

- [ ] Define source-route interpretation narrowly. Find `state.localPeer` in `packet.route.entries`; the following entry is the candidate next hop. Use it only when one unambiguous write-ready provisional binding exists. A route containing local peer more than once is a loop and is not relayed. If no supported direct next hop exists, use deterministic controlled flood.

```kotlin
internal fun selectTargets(
    state: MeshState,
    packet: DecodedPacket,
    ingress: LinkId,
    encodedSize: Int,
    limits: MeshLimits,
): List<LinkId>
```

Fallback candidates exclude ingress, closed/unready links, and links whose `maxWriteBytes < encodedSize`; sort by opaque `LinkId.value` and take `maxRelayFanout`. This is the isolated conservative BitMessage policy, not a claim of exact Apple/Android fanout parity.

- [ ] A new relayable admitted message first emits `RequestEntropy` for exactly two bytes. Map the unsigned big-endian result to `0..500` milliseconds with modulo 501. Reject wrong-length entropy without scheduling.

```kotlin
internal fun relayDelay(bytes: Bytes): Duration {
    require(bytes.size == 2)
    val unsigned = (bytes[0].toInt() and 0xff) shl 8 or (bytes[1].toInt() and 0xff)
    return (unsigned % 501).milliseconds
}
```

- [ ] `EntropyProvided` checks scheduled-relay global/per-source limits before storing a `ScheduledRelay` and emitting `Schedule`. Relay timer firing removes the scheduled entry and emits `EncodeRelay(packet, outgoingTtl)`. `RelayEncoded` rechecks target readiness/size and emits ordered `MeshEffect.WriteLink` values containing fully correlated `LinkCommand.Write` commands.

- [ ] `LinkCompleted` resolves one write exactly once. Written is link-write success only; it does not emit delivery, acknowledgement, persistence, or retry effects. Backpressure/failure produces a redacted trace and no retry state.

- [ ] Test the complete signature path: signed packet admitted, jitter supplied, timer fired, protocol relay encoding returns TTL-decremented bytes, decoded relayed packet retains the signature, and `SigningTranscript.build` is unchanged.

- [ ] Run `rtk ./gradlew :engine:mesh:allTests :protocol:bitchat:allTests --rerun-tasks --console=plain`; expect green.

- [ ] Commit.

```bash
rtk git add engine/mesh/src
rtk git commit -m "feat: add deterministic mesh relay policy"
```

## Task 9: Implement bounded fragment reassembly and explicit reinjection

**Files:**
- Create: `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/FragmentReducer.kt`
- Modify: `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/MeshEngine.kt`
- Modify: `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/MeshState.kt`
- Create: `engine/mesh/src/commonTest/kotlin/com/yet/bitmessage/engine/mesh/FragmentReducerTest.kt`

- [ ] Write failing tests for two fragments arriving out of order, an identical duplicate, conflicting bytes at one index, total/original-type conflict, zero/oversized count, global/per-source stream caps, per-stream/aggregate byte caps, expiry, and completion.

```kotlin
@Test
fun completionRemovesStreamAndEmitsDecodeInsteadOfRecursiveReduction() {
    val afterSecond = reduceFragment(firstState, fragment(index = 1u, data = innerTail))
    val completed = reduceFragment(afterSecond.state, fragment(index = 0u, data = innerHead))

    assertTrue(completed.state.fragmentStreams.isEmpty())
    val decode = assertIs<MeshEffect.DecodePacket>(completed.effects.single())
    assertEquals(Bytes.copyOf(innerHead.copyToByteArray() + innerTail.copyToByteArray()), decode.bytes)
    assertEquals(PacketSource.Reassembled(linkA, fragmentId), decode.source)
}
```

- [ ] Run engine tests and expect fragment failures.

- [ ] Validate every count and byte addition before copying or growing a map. Key a stream by provisional source peer plus eight-byte fragment ID. Fixed metadata is total count and original type.

- [ ] An identical `(index, bytes)` repeat is ignored without extending expiry. Reusing an index with different bytes, or changing total/original type, removes the stream, subtracts all retained bytes, cancels its timer, and records a conflict rejection.

- [ ] On completion, join indexes `0 until total` in ascending order after a checked total-size sum, remove the stream in the same transition, cancel its timer, and emit `DecodePacket` with `PacketSource.Reassembled`. The eventual `PacketDecoded` result enters the ordinary pending/digest/auth/dedup pipeline.

- [ ] A fragment timer event must match stream key, timer ID, and generation. Expiry removes only that stream. A late timer or decode result cannot affect a replacement stream.

- [ ] Keep fragment outer relay disabled and do not add outbound fragmentation, targeted recovery, media transfer, or a fragment actor per stream.

- [ ] Run `rtk ./gradlew :engine:mesh:allTests --rerun-tasks --console=plain`; expect green.

- [ ] Commit.

```bash
rtk git add engine/mesh/src
rtk git commit -m "feat: add bounded fragment reassembly"
```

## Task 10: Add deterministic hostile-sequence and stale-result properties

**Files:**
- Create: `engine/mesh/src/commonTest/kotlin/com/yet/bitmessage/engine/mesh/MeshPropertyTest.kt`
- Modify: `engine/mesh/src/commonTest/kotlin/com/yet/bitmessage/engine/mesh/MeshEngineCoverageTest.kt`

- [ ] Add a small deterministic generator using a local linear-congruential sequence in the test file; do not add a property-testing dependency. Generate bounded link churn, candidate arrivals, digest/auth results, duplicate placement, fragment indexes, entropy, timers, and generation changes.

```kotlin
repeat(100) { seed ->
    val events = MeshSequenceGenerator(seed.toLong()).events(count = 250)
    val first = replay(initialState, events)
    val second = replay(initialState, events)
    assertEquals(first.transitions, second.transitions)
    first.transitions.forEach(::assertStateWithinLimits)
}
```

- [ ] Assert after every transition: active links, pending count/bytes, admitted IDs, streams, fragments/stream, aggregate fragment bytes, route observations, relays, and fanout stay within limits; TTL never underflows; invalid authentication never inserts; stale generations never change state.

- [ ] Add explicit internal-invariant tests for impossible negative counters or mismatched state snapshots. Constructor/factory rejection is distinct from hostile packet rejection; the actor must not continue from unknowable state.

- [ ] Replace the gate anchor body with a compact end-to-end reducer trace: link open, bytes received, decode result, digest result, valid signature, admitted dispatch, entropy, timer, relay encoding, write result. Keep it deterministic and independent of a physical transport.

- [ ] Run `rtk ./gradlew :engine:mesh:meshEngineCheck :engine:mesh:allTests --rerun-tasks --console=plain`; expect non-zero Android-host gate and green Android/iOS property execution.

- [ ] Commit.

```bash
rtk git add engine/mesh/src/commonTest
rtk git commit -m "test: harden deterministic mesh transitions"
```

## Task 11: Add the explicit serialized runtime lifecycle

**Files:**
- Create: `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/runtime/MeshEffectExecutor.kt`
- Create: `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/runtime/MeshRuntime.kt`
- Create: `engine/mesh/src/commonTest/kotlin/com/yet/bitmessage/engine/mesh/runtime/MeshRuntimeTest.kt`

- [ ] Write failing `runTest` cases proving construction launches nothing, start creates one consumer, two starts do not duplicate it, `trySubmit` reports accepted/backpressured/closed, transitions are serialized, effects remain ordered, timers are cancellable, trace buffering is bounded with an observable dropped count, stop clears transient state, restart increments generation and retains only unexpired dedup, old results are ignored, close is permanent, and cancellation is rethrown.

```kotlin
@Test
fun constructorDoesNotLaunchAndClosePreventsResurrection() = runTest {
    val executor = RecordingExecutor()
    val runtime = MeshRuntime(engine, executor, backgroundScope, limits)
    assertTrue(executor.effects.isEmpty())

    assertEquals(StartResult.Started, runtime.start(localPeer, MonotonicTime.ZERO))
    runtime.close(MonotonicTime.ZERO)
    assertEquals(SubmitResult.Closed, runtime.trySubmit(linkOpened(runtime.generation)))
    assertEquals(StartResult.Closed, runtime.start(localPeer, MonotonicTime.ZERO))
}
```

- [ ] Run engine tests and expect missing runtime symbols.

- [ ] Define the narrow executor boundary.

```kotlin
fun interface MeshEffectExecutor {
    suspend fun execute(effect: MeshEffect): MeshEvent?
}

sealed interface SubmitResult {
    data object Accepted : SubmitResult
    data object Backpressured : SubmitResult
    data object Closed : SubmitResult
}
```

- [ ] `MeshRuntime` accepts a caller-owned parent scope and creates a child `SupervisorJob` only inside `start`; the constructor creates no job/channel and launches nothing. It owns a bounded external mailbox, bounded effect channel, rendezvous effect-result channel, bounded trace channel, timer child jobs, and one actor job plus one ordered effect job.

- [ ] The actor publishes each `Transition.state` before queuing its ordered effects. It gives a waiting effect result priority over another external event. One transition emits no more than `maxRelayFanout + 8` effects, which the limits validate against queue capacity.

- [ ] Publish transition traces with non-suspending `trySend` so a missing trace consumer cannot block or change mesh state. When the bounded trace channel is full, increment an observable `droppedTraceCount`; never enqueue an unbounded replacement buffer.

- [ ] The executor loop handles schedule/cancel internally with child timer jobs. Other effects execute through `MeshEffectExecutor`; a returned event goes through the rendezvous result channel. Catch cancellation first and rethrow it. Convert only non-cancellation failures to correlated `MeshEvent.EffectFailed`.

```kotlin
try {
    executor.execute(effect)?.let { resultChannel.send(it) }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    resultChannel.send(effect.failedEvent(MeshFailureCode.EFFECT_EXECUTION_FAILED))
}
```

- [ ] `stop` first flips acceptance off, sends one internal `RuntimeStopping`, waits for that transition to publish, cancels effect/timer children, closes runtime channels, and joins owned jobs. The stopped snapshot clears links, bindings, pending admissions, fragments, routes, and relays while retaining only admitted IDs whose expiry is after the supplied monotonic time.

- [ ] `restart` calls explicit `start` with `generation.next()` and reschedules only the next retained dedup expiry. `close` is idempotent, stops once, marks the runtime permanently closed, and rejects every future start/submission.

- [ ] Run `rtk ./gradlew :engine:mesh:allTests --rerun-tasks --console=plain`; expect lifecycle/race tests green without sleeps.

- [ ] Commit.

```bash
rtk git add engine/mesh/src
rtk git commit -m "feat: add serialized mesh runtime"
```

## Task 12: Update only affected architecture, compatibility, and salvage records

**Files:**
- Modify: `docs/architecture/BITMESSAGE_ARCHITECTURE.md`
- Modify: `docs/architecture/BITCHAT_COMPATIBILITY.md`
- Modify: `docs/architecture/STATE_MACHINE_DESIGN.md`
- Modify: `docs/architecture/HISTORICAL_BITMESSAGE_SALVAGE.md`
- Modify: `docs/IMPLEMENTATION_PLAN.md`
- Modify: `docs/superpowers/specs/2026-08-11-phase-4-deterministic-mesh-engine-design.md`
- Modify: `docs/superpowers/plans/2026-08-11-phase-4-deterministic-mesh-engine.md`
- Modify: `AGENTS.md`

- [ ] Update the repository map and dependency diagram with the actual modules. Record that `LinkId` remains in `:core:model`, transport contracts contain no protocol types, and the engine depends inward on protocol/transport ports.

- [ ] Replace conceptual Phase 4 state-machine prose with actual type names, bounds, admission ordering, TTL table, relay fallback uncertainty, fragment reinjection, and runtime lifecycle. Do not alter Phase 5 design or claim physical/simulator coverage.

- [ ] Update the compatibility document only with reproduced facts: packet-ID known answer, fixed-TTL transcript, signature-compatible uncompressed relay, fragment metadata vector, and continued block on compressed signed relay/fanout parity/outer-fragment relay.

- [ ] Record concrete historical promotions:

```text
PacketIdUtil field order                 SALVAGE_ALGORITHM
invalid-auth dedup-poison regression     SALVAGE_TEST
scheduled duplicate relay cancellation  SALVAGE_TEST
fragment quota/reorder structure         SALVAGE_ALGORITHM
RelayController numeric policy           REFERENCE_ONLY
RoutePlanner general graph routing       REFERENCE_ONLY
cancellation propagation regression      SALVAGE_TEST
```

- [ ] Mark tasks 4.1–4.5 complete only after their tests execute. Update the design/plan status with actual evidence and explicitly state that Phase 5 did not start.

- [ ] Run `rtk git diff --check` and a placeholder scan over the modified docs. Fix every stale module path, contradictory count, and unsupported parity claim.

- [ ] Commit documentation separately.

```bash
rtk git add AGENTS.md docs tools/upstream-compat/README.md
rtk git commit -m "docs: record deterministic mesh engine"
```

## Task 13: Run full Phase 4 verification and record exact counts

**Files:**
- Modify only if verification exposes a defect in an already planned file.

- [ ] Run narrow gates from clean test results.

```bash
rtk ./gradlew :transport:api:allTests :engine:mesh:meshEngineCheck :engine:mesh:allTests --rerun-tasks --console=plain
```

- [ ] Run all inherited compatibility and core/protocol gates.

```bash
rtk ./gradlew :core:testing:compatibilityCheck :core:foundation:allTests :core:model:allTests :core:testing:allTests :protocol:bitchat:productionCompatibilityCheck :protocol:bitchat:allTests --rerun-tasks --console=plain
```

- [ ] Run the application/platform gates.

```bash
rtk ./gradlew :androidApp:assembleDebug :sharedLogic:linkDebugFrameworkIosSimulatorArm64 --rerun-tasks --console=plain
```

- [ ] Inspect fresh JUnit XML under each module's `build/test-results` and report exact Android-host/iOS counts without counting `meshEngineCheck` or `productionCompatibilityCheck` as additional behavioral executions. List every selected `NO-SOURCE` task.

- [ ] Prove the evidence corpus is unchanged and the module graph is narrow.

```bash
rtk git diff --exit-code 6989e09 -- compatibility
rtk ./gradlew projects --console=plain
rtk git diff --check
rtk git status --short --branch
```

- [ ] Re-index the repository knowledge graph, then inspect the final diff for platform imports, database imports, unbounded channels, wall-clock reads, reducer randomness, constructor launches, swallowed cancellation, ambiguous admission booleans, and Phase 5+ module names.

- [ ] Use `superpowers:verification-before-completion`, then `superpowers:requesting-code-review`. Fix every finding and rerun the affected narrow gate plus the complete gate before the final implementation commit.

- [ ] Commit verification fixes, if any, as one focused commit.

```bash
rtk git add engine/mesh transport/api protocol/bitchat docs AGENTS.md
rtk git commit -m "test: complete Phase 4 verification"
```

## Required final report

The handoff must include all 30 items requested by the Phase 4 brief: branch/base/commits, pre-implementation Phase 1–3 counts, modules/dependency graph, state/event/effect categories, exact admission proof, packet-ID evidence, every bound/expiry, TTL/relay/source-route/fragment behavior, stale-result/runtime lifecycle, salvage classifications, exact per-target test counts, adversarial tests, every verification command/result, `NO-SOURCE` tasks, blocked compatibility questions, documentation changes, and explicit confirmation that Phase 5 was not started.
