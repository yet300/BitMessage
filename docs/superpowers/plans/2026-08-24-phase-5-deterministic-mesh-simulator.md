# Phase 5 Deterministic Multi-Node Mesh Simulator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a bounded deterministic Kotlin Multiplatform network simulator that drives real `MeshRuntime`/`MeshEngine` nodes through real BitChat protocol ingress, explicit directed-link faults, predicate-bounded virtual time, and exactly replayable multi-node scenarios.

**Architecture:** `:transport:simulation` owns one scheduled virtual-event queue ordered by `(deadline, insertionSequence)`, directed link topology, explicit fault plans, node-local effect providers, redacted traces, and stable snapshots. Every node owns a real Phase 4 runtime and reducer; Phase 4 receives only an acknowledged event boundary, deterministic immediate-effect fence, and injectable timer driver. Current-instant quiescence never advances passive future maintenance timers, while `runUntil` advances only until a caller predicate becomes true or explicit limits fail.

**Tech Stack:** Kotlin Multiplatform 2.4.10, Kotlin coroutines 1.11.0, existing `Bytes`/`MonotonicTime`/`Engine` values, existing BitChat codec/profile/fragment/relay APIs, existing transport contracts, `kotlin.test`, `kotlinx-coroutines-test`, Android-host canonical fixture resources, pure Kotlin simulation-only SHA-256.

**Accepted base:** Phase 4 acceptance commit `43b10ac7cb351f65905bf99586d9b96ffadb1a2d`; Phase 5 design commit `370fce5b9cd2d1e89ddf6b4a8dc83d4fea9d5d90`; branch `codex/phase-5-deterministic-mesh-simulator`.

**Scope guard:** Implement Phase 5 only. Do not add Bluetooth, Noise, production crypto, persistence, delivery/sync/media engines, durable retry, a generic simulator framework, a property-test shrinker, or any Phase 6 code.

---

## Required execution discipline

### Execution record (2026-09-05)

- Tasks 1 and 2 are implemented and reviewed. SHA-256 coverage includes independently checked algorithm vectors at padding boundaries, binary input, and one million `a` bytes; packet fixtures remain codec-derived and non-normative.
- Task 4 passed final specification and quality review at runtime commit `f778247448ce5e619dd14fce3a51615498856f96`: 26 acknowledgement tests and 93 total mesh tests on each of Android host and iOS Simulator ARM64, with a positive mesh gate. Task 5 is committed at `21a2c14` after eight timer-driver tests and the full Android/iOS mesh suite passed. Task 3 is committed at `7d8d77f` after eight queue/trace tests passed on both targets and a positive simulation gate. Task 6 is committed at `062508b` after 21 total simulation tests per target. Task 7 is committed at `9de33aa` after ten focused entropy/provider tests per target and passing simulation, protocol, and mesh suites. Task 8 has passed specification and quality review: current-time quiescence, explicit advancement, predicate-bounded execution, real-node scheduling, and 12 focused quiescence tests pass on Android host and iOS Simulator ARM64. Task 9 substrate scenarios have not started.
- Execute Tasks 4 and 5 before Task 3: Task 3's `FireRuntimeTimer` references `MeshTimerKey`, which Task 5 introduces. Do not create a temporary duplicate key type.
- Task 3 follows the plan's five initial event variants. Design 2 additionally names scheduled scenario actions: add a closed scenario-action event when that action type has a concrete consumer, and keep timed actions on the single global queue. `MESH_EVENT` is packet-network work because delayed digest/verification results can continue admission and relay; convergence must still inspect packet relevance and current-vs-future deadline rather than category alone.
- Run focused `testAndroidHostTest --tests ...` commands separately from the unfiltered `meshEngineCheck`/`allTests` invocation. Combining the filter with the gate suppresses the required coverage anchor after the gate cleans its XML results.
- Runtime pressure regressions require atomic complete-transition admission, bounded staging, settlement progress, and lifecycle preemption. Temporary queue occupancy is backpressure; it is not an invalid transition.
- Review findings require code-path evidence or a reproducing test. The proposed pre-settlement watermark race was withdrawn: a fence returns an immutable count, and a single worker cannot consume that fence before settling preceding effects. It must not be reported as a proven defect or regression fix.
- Phase 6 remains outside this execution scope.

For every behavioral task:

1. Write the named failing test first.
2. Run the narrow command and record the expected failure.
3. Add only the implementation required by that test.
4. Run the narrow Android-host test, then the relevant multiplatform suite.
5. Run `rtk git diff --check` before each commit.
6. Commit one coherent slice using the exact message in this plan.

Do not copy compatibility-significant hex literals into common simulator tests. Android-host canonical scenarios load `compatibility/BitchatBaseline2026_08/fixtures.json` through `FixtureManifestParser`. Common tests create non-normative packets through `BitchatCodec`, `RelayEncoding`, and `FragmentPayloadCodec`.

## File map

### Build and module registration

- `settings.gradle.kts`: register `:transport:simulation` only.
- `transport/simulation/build.gradle.kts`: declare the approved dependency graph and non-zero `simulationCheck` gate.
- `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/SimulationCoverageTest.kt`: Android/iOS common anchor for the Phase 5 gate.

### Narrow Phase 4 runtime refinement

- `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/runtime/MeshRuntime.kt`: add acknowledged event envelopes, causal effect fences, and retain bounded actor queues.
- `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/runtime/MeshTimerDriver.kt`: define timer request/driver/factory contracts and the coroutine-backed default.
- `engine/mesh/src/commonTest/kotlin/com/yet/bitmessage/engine/mesh/runtime/MeshRuntimeAcknowledgementTest.kt`: prove acknowledgement and immediate-quiescence semantics without polling.
- `engine/mesh/src/commonTest/kotlin/com/yet/bitmessage/engine/mesh/runtime/MeshTimerDriverTest.kt`: prove registration, cancel, stop, and default-delay behavior.

### Simulation primitives

- `transport/simulation/src/commonMain/kotlin/com/yet/bitmessage/transport/simulation/SimulationTypes.kt`: validated IDs, limits, outcomes, and immutable projections.
- `transport/simulation/src/commonMain/kotlin/com/yet/bitmessage/transport/simulation/VirtualEventQueue.kt`: bounded `(deadline, sequence)` scheduled queue with cancellation.
- `transport/simulation/src/commonMain/kotlin/com/yet/bitmessage/transport/simulation/SimulationEvent.kt`: closed scheduled-event hierarchy.
- `transport/simulation/src/commonMain/kotlin/com/yet/bitmessage/transport/simulation/SimulationTrace.kt`: structured redacted records and bounded ring retention.
- `transport/simulation/src/commonMain/kotlin/com/yet/bitmessage/transport/simulation/SimulationSha256.kt`: simulation-only real SHA-256.
- `transport/simulation/src/commonMain/kotlin/com/yet/bitmessage/transport/simulation/FaultPlan.kt`: explicit immutable transmission and timed link faults.
- `transport/simulation/src/commonMain/kotlin/com/yet/bitmessage/transport/simulation/DirectedSimulatedLink.kt`: endpoint/direction state and typed write validation.
- `transport/simulation/src/commonMain/kotlin/com/yet/bitmessage/transport/simulation/ProtocolPlans.kt`: independent protocol entropy, verification plan, and digest overrides.
- `transport/simulation/src/commonMain/kotlin/com/yet/bitmessage/transport/simulation/SimulationEffectExecutor.kt`: production codec effects plus scheduled network completions.
- `transport/simulation/src/commonMain/kotlin/com/yet/bitmessage/transport/simulation/SimulatedNode.kt`: one real engine/runtime and bounded node output.
- `transport/simulation/src/commonMain/kotlin/com/yet/bitmessage/transport/simulation/SimulatedNetwork.kt`: topology, queue, clock, faults, quiescence, `advanceTo`, `advanceBy`, and `runUntil`.
- `transport/simulation/src/commonMain/kotlin/com/yet/bitmessage/transport/simulation/SimulationSnapshot.kt`: deterministic replay projection and diagnostics.

### Simulator tests

- `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/SimulationFixtures.kt`: non-normative codec-built nodes, packets, fragments, and topology helpers.
- `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/SimulationSha256Test.kt`: standard and canonical-algorithm digest vectors.
- `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/VirtualEventQueueTest.kt`: ordering, cancellation, overflow, and sequence safety.
- `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/SimulationSubstrateTest.kt`: exact latency, duplication, loss, reorder, partition, reconnect, readiness, MTU, and backpressure.
- `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/SimulationQuiescenceTest.kt`: current-instant and predicate-bounded advancement.
- `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/SimulationEntropyTest.kt`: independence of plan RNG and node protocol entropy.
- `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/DirectAndRelayScenarioTest.kt`: direct delivery, A-B-C relay, duplicate paths, and TTL boundaries.
- `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/AdmissionAttackScenarioTest.kt`: invalid-auth poisoning and bounded pending state.
- `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/PartitionAndBackpressureScenarioTest.kt`: partition, reconnect/generation, typed pressure/failure.
- `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/FragmentScenarioTest.kt`: reorder, duplicate, delay, conflict, quota, reinjection, publication.
- `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/RelayLoopScenarioTest.kt`: triangle convergence with passive timers left queued.
- `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/DeterministicReplayTest.kt`: equality and exact failure diagnostics.
- `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/GeneratedAdversarialScenarioTest.kt`: 64 fixed seeds × at most 128 actions with continuous invariants.
- `transport/simulation/src/androidHostTest/kotlin/com/yet/bitmessage/transport/simulation/CanonicalMeshScenarioTest.kt`: canonical packet identity, 256-byte signing transcript, mutable TTL, `7 -> 6`, and positive fragments without a shadow corpus.

### Documentation

- `AGENTS.md`: module map, dependency direction, and Phase 5 commands.
- `docs/architecture/BITMESSAGE_ARCHITECTURE.md`: simulation-only dependency exception and node/network boundary.
- `docs/architecture/STATE_MACHINE_DESIGN.md`: acknowledged commitment, immediate-effect fences, timer driver, and current-instant quiescence.
- `docs/architecture/HISTORICAL_BITMESSAGE_SALVAGE.md`: record only concrete Phase 5 `SALVAGE_TEST` promotions.
- `docs/IMPLEMENTATION_PLAN.md`: mark Phase 5 implemented only after all gates pass; leave Phase 6 unstarted.

---

## Task 1: Register `:transport:simulation` and its non-zero gate

**Files:**
- Modify: `settings.gradle.kts`
- Create: `transport/simulation/build.gradle.kts`
- Create: `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/SimulationCoverageTest.kt`

- [ ] **Step 1: Add the module registration.**

```kotlin
include(":transport")
include(":transport:api")
include(":transport:simulation")
```

- [ ] **Step 2: Configure the exact production/test dependencies and gate.**

```kotlin
// transport/simulation/build.gradle.kts
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.local.kotlin.multiplatform)
}

val simulationResultFiles = fileTree(layout.buildDirectory.dir("test-results/testAndroidHostTest")) {
    include("TEST-*.xml")
}

val cleanSimulationTestResults = tasks.register<Delete>("cleanSimulationTestResults") {
    group = "verification"
    description = "Removes prior Android-host simulation results before the Phase 5 gate runs."
    delete(layout.buildDirectory.dir("test-results/testAndroidHostTest"))
}

tasks.withType<Test>().matching { it.name == "testAndroidHostTest" }.configureEach {
    mustRunAfter(cleanSimulationTestResults)
}

tasks.register("simulationCheck") {
    group = "verification"
    description = "Runs fresh Phase 5 simulator tests and fails when the coverage anchor did not execute."
    dependsOn(cleanSimulationTestResults, "testAndroidHostTest")
    inputs.files(simulationResultFiles)

    doLast {
        val result = requireNotNull(
            inputs.files.singleOrNull {
                it.name == "TEST-com.yet.bitmessage.transport.simulation.SimulationCoverageTest.xml"
            },
        ) { "Phase 5 simulation gate requires a fresh SimulationCoverageTest result." }
        val tests = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(result)
            .documentElement
            .getAttribute("tests")
            .toInt()
        check(tests > 0) { "Phase 5 simulation gate requires executed coverage; found $tests." }
        logger.lifecycle("Phase 5 simulation gate executed {} coverage tests.", tests)
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.foundation)
            implementation(projects.core.model)
            implementation(projects.protocol.bitchat)
            implementation(projects.transport.api)
            implementation(projects.engine.mesh)
        }
        commonTest.dependencies {
            implementation(projects.core.testing)
        }
        androidHostTest {
            resources.srcDir(rootProject.layout.projectDirectory.dir("compatibility"))
        }
    }
}
```

- [ ] **Step 3: Add the gate anchor.**

```kotlin
package com.yet.bitmessage.transport.simulation

import kotlin.test.Test
import kotlin.test.assertTrue

class SimulationCoverageTest {
    @Test
    fun phaseFiveSimulationSuiteIsPresent() {
        assertTrue(true)
    }
}
```

- [ ] **Step 4: Run the new gate and project inspection.**

Run:

```bash
rtk ./gradlew projects :transport:simulation:simulationCheck :transport:simulation:allTests --console=plain
```

Expected: `:transport:simulation` appears; one Android-host and one iOS-target anchor execute; `simulationCheck` reports a positive count.

- [ ] **Step 5: Check and commit.**

```bash
rtk git diff --check
rtk git add settings.gradle.kts transport/simulation
rtk git commit -m "build: add Phase 5 simulation module"
```

## Task 2: Implement real simulation-only SHA-256

**Files:**
- Create: `transport/simulation/src/commonMain/kotlin/com/yet/bitmessage/transport/simulation/SimulationSha256.kt`
- Create: `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/SimulationFixtures.kt`
- Create: `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/SimulationSha256Test.kt`

- [ ] **Step 1: Write known-answer tests before the provider.**

```kotlin
package com.yet.bitmessage.transport.simulation

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.protocol.bitchat.PacketIdentity
import kotlin.test.Test
import kotlin.test.assertEquals

class SimulationSha256Test {
    @Test
    fun standardKnownAnswersMatchSha256() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            SimulationSha256.digest(Bytes.copyOf(byteArrayOf())).hex(),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            SimulationSha256.digest(Bytes.copyOf("abc".encodeToByteArray())).hex(),
        )
    }

    @Test
    fun packetIdentityUsesTheRealDigestAndPhaseFourTruncation() {
        val digest = SimulationSha256.digest(SimulationFixtures.broadcastIdentityInput.canonicalBytes)
        assertEquals(digest.copyToByteArray().copyOfRange(0, 16).hex(), PacketIdentity.fromSha256(digest).value.hex())
    }
}

internal fun Bytes.hex(): String = copyToByteArray().hex()
internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
```

- [ ] **Step 2: Run the test and prove the provider is absent.**

Run:

```bash
rtk ./gradlew :transport:simulation:testAndroidHostTest --tests '*SimulationSha256Test' --console=plain
```

Expected: compilation fails because `SimulationSha256` and the fixture input do not exist.

- [ ] **Step 3: Add the pure SHA-256 provider.**

Implement `SimulationSha256.digest(Bytes): Bytes` as a complete FIPS 180-4 SHA-256 calculation:

```kotlin
package com.yet.bitmessage.transport.simulation

import com.yet.bitmessage.foundation.Bytes

internal object SimulationSha256 {
    fun digest(input: Bytes): Bytes {
        val source = input.copyToByteArray()
        val bitLength = source.size.toLong() * 8L
        val paddingBytes = ((56 - ((source.size + 1) % 64)) + 64) % 64
        val message = ByteArray(source.size + 1 + paddingBytes + 8)
        source.copyInto(message)
        message[source.size] = 0x80.toByte()
        repeat(8) { index ->
            message[message.lastIndex - index] = (bitLength ushr (index * 8)).toByte()
        }

        val hash = intArrayOf(
            0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
            0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19,
        )
        val words = IntArray(64)
        var offset = 0
        while (offset < message.size) {
            repeat(16) { index ->
                val base = offset + index * 4
                words[index] =
                    ((message[base].toInt() and 0xff) shl 24) or
                        ((message[base + 1].toInt() and 0xff) shl 16) or
                        ((message[base + 2].toInt() and 0xff) shl 8) or
                        (message[base + 3].toInt() and 0xff)
            }
            for (index in 16 until 64) {
                val s0 = words[index - 15].rotateRight(7) xor
                    words[index - 15].rotateRight(18) xor
                    (words[index - 15] ushr 3)
                val s1 = words[index - 2].rotateRight(17) xor
                    words[index - 2].rotateRight(19) xor
                    (words[index - 2] ushr 10)
                words[index] = words[index - 16] + s0 + words[index - 7] + s1
            }

            var a = hash[0]
            var b = hash[1]
            var c = hash[2]
            var d = hash[3]
            var e = hash[4]
            var f = hash[5]
            var g = hash[6]
            var h = hash[7]
            repeat(64) { index ->
                val upper = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val choose = (e and f) xor (e.inv() and g)
                val temporary1 = h + upper + choose + ROUND_CONSTANTS[index] + words[index]
                val lower = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val majority = (a and b) xor (a and c) xor (b and c)
                val temporary2 = lower + majority
                h = g
                g = f
                f = e
                e = d + temporary1
                d = c
                c = b
                b = a
                a = temporary1 + temporary2
            }
            hash[0] += a
            hash[1] += b
            hash[2] += c
            hash[3] += d
            hash[4] += e
            hash[5] += f
            hash[6] += g
            hash[7] += h
            offset += 64
        }

        val output = ByteArray(32)
        hash.forEachIndexed { index, word ->
            output[index * 4] = (word ushr 24).toByte()
            output[index * 4 + 1] = (word ushr 16).toByte()
            output[index * 4 + 2] = (word ushr 8).toByte()
            output[index * 4 + 3] = word.toByte()
        }
        return Bytes.copyOf(output)
    }

    private fun Int.rotateRight(bits: Int): Int = (this ushr bits) or (this shl (32 - bits))

    private val ROUND_CONSTANTS = intArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
        0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
        0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
        0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
        0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(),
        0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )
}
```

Create the referenced non-normative identity input from production encode/decode, without an expected digest literal:

```kotlin
internal object SimulationFixtures {
    private val sender = WirePeerId.of(Bytes.copyOf(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))

    fun messagePacket(
        ttl: UByte = 3u,
        payload: Bytes = Bytes.copyOf(byteArrayOf(0x41, 0x42)),
        timestamp: ULong = 42u,
    ): DecodedPacket {
        val candidate = DecodedPacket(
            version = PacketVersion.of(2u),
            type = PacketType.of(KnownPacketType.MESSAGE.value),
            ttl = ttl,
            timestamp = timestamp,
            flags = PacketFlags.of(0u),
            sender = sender,
            recipient = null,
            route = null,
            payload = payload,
            signature = null,
            rawPacket = RawPacket(Bytes.copyOf(byteArrayOf(0))),
        )
        val wire = assertIs<EncodeResult.Success>(BitchatCodec.encode(candidate)).bytes
        return assertIs<DecodeResult.Success<DecodedPacket>>(BitchatCodec.decode(wire)).value
    }

    val broadcastIdentityInput: PacketIdentityInput = PacketIdentity.input(messagePacket())
}
```

- [ ] **Step 4: Run both target suites.**

```bash
rtk ./gradlew :transport:simulation:testAndroidHostTest --tests '*SimulationSha256Test' :transport:simulation:iosSimulatorArm64Test --console=plain
```

Expected: both SHA tests pass.

- [ ] **Step 5: Check and commit.**

```bash
rtk git diff --check
rtk git add transport/simulation/src
rtk git commit -m "test: add real simulation packet digest"
```

## Task 3: Add bounded virtual events, limits, and redacted trace values

**Files:**
- Create: `transport/simulation/src/commonMain/kotlin/com/yet/bitmessage/transport/simulation/SimulationTypes.kt`
- Create: `transport/simulation/src/commonMain/kotlin/com/yet/bitmessage/transport/simulation/SimulationEvent.kt`
- Create: `transport/simulation/src/commonMain/kotlin/com/yet/bitmessage/transport/simulation/VirtualEventQueue.kt`
- Create: `transport/simulation/src/commonMain/kotlin/com/yet/bitmessage/transport/simulation/SimulationTrace.kt`
- Create: `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/VirtualEventQueueTest.kt`

- [ ] **Step 1: Write queue contract tests.**

```kotlin
class VirtualEventQueueTest {
    @Test
    fun ordersByDeadlineThenInsertionSequenceAndDoesNotAdvanceTime() {
        val queue = VirtualEventQueue<String>(capacity = 4)
        queue.schedule(MonotonicTime.ZERO.plus(100.milliseconds), "late")
        queue.schedule(MonotonicTime.ZERO, "first")
        queue.schedule(MonotonicTime.ZERO, "second")

        assertEquals("first", queue.removeNextDue(MonotonicTime.ZERO)?.value)
        assertEquals("second", queue.removeNextDue(MonotonicTime.ZERO)?.value)
        assertNull(queue.removeNextDue(MonotonicTime.ZERO))
        assertEquals("late", queue.peek()?.value)
    }

    @Test
    fun cancellationCapacityAndSequenceExhaustionAreTyped() {
        val queue = VirtualEventQueue<String>(capacity = 1, initialSequence = Long.MAX_VALUE)
        assertIs<QueueScheduleResult.SequenceExhausted>(queue.schedule(MonotonicTime.ZERO, "never"))

        val bounded = VirtualEventQueue<String>(capacity = 1)
        val accepted = assertIs<QueueScheduleResult.Scheduled>(bounded.schedule(MonotonicTime.ZERO, "one"))
        assertIs<QueueScheduleResult.Full>(bounded.schedule(MonotonicTime.ZERO, "two"))
        assertTrue(bounded.cancel(accepted.id))
        assertIs<QueueScheduleResult.Scheduled>(bounded.schedule(MonotonicTime.ZERO, "two"))
    }
}
```

Also test that `SimulationLimits` rejects zero/negative bounds and that trace retention keeps the newest records with an exact dropped count.

- [ ] **Step 2: Run the test and verify missing-type failures.**

```bash
rtk ./gradlew :transport:simulation:testAndroidHostTest --tests '*VirtualEventQueueTest' --console=plain
```

Expected: compilation fails for the new queue/types.

- [ ] **Step 3: Implement validated values and queue outcomes.**

```kotlin
@JvmInline
value class SimulatedNodeId private constructor(val value: String) : Comparable<SimulatedNodeId> {
    override fun compareTo(other: SimulatedNodeId): Int = value.compareTo(other.value)
    companion object {
        fun of(value: String): SimulatedNodeId = SimulatedNodeId(value.trim().also { require(it.isNotEmpty()) })
    }
}

@JvmInline
value class SimulatedLinkId private constructor(val value: String) : Comparable<SimulatedLinkId> {
    override fun compareTo(other: SimulatedLinkId): Int = value.compareTo(other.value)
    companion object {
        fun of(value: String): SimulatedLinkId = SimulatedLinkId(value.trim().also { require(it.isNotEmpty()) })
    }
}

@JvmInline
value class ScheduledEventId internal constructor(val value: Long) {
    init { require(value >= 0) }
}

data class SimulationLimits(
    val maxNodes: Int = 16,
    val maxDirectedLinks: Int = 64,
    val maxScheduledEvents: Int = 4_096,
    val maxFaultActions: Int = 2_048,
    val maxTraceRecords: Int = 8_192,
    val maxPublicationRecords: Int = 1_024,
    val maxDeliveryRecords: Int = 8_192,
    val maxProcessedEvents: Int = 100_000,
    val maxVirtualDuration: Duration = 10.minutes,
) {
    init {
        require(maxNodes > 0 && maxDirectedLinks > 0 && maxScheduledEvents > 0)
        require(maxFaultActions > 0 && maxTraceRecords > 0)
        require(maxPublicationRecords > 0 && maxDeliveryRecords > 0 && maxProcessedEvents > 0)
        require(maxVirtualDuration.isFinite() && maxVirtualDuration > Duration.ZERO)
    }
}

sealed interface QueueScheduleResult {
    data class Scheduled(val id: ScheduledEventId) : QueueScheduleResult
    data object Full : QueueScheduleResult
    data object SequenceExhausted : QueueScheduleResult
}
```

Implement `VirtualEventQueue<T>` with a private sorted mutable list. `schedule` checks sequence exhaustion and capacity before mutation, inserts by `(deadline, sequence)`, and returns an ID. `removeNextDue(now)` removes exactly one head entry only when its deadline equals `now`; it never chooses a future time. `cancel` removes the matching entry and makes capacity reusable.

- [ ] **Step 4: Add the closed scheduled-event hierarchy.**

```kotlin
internal sealed interface SimulationEvent {
    data class DeliverPayload(
        val directionId: SimulatedLinkId,
        val targetNode: SimulatedNodeId,
        val targetLinkId: LinkId,
        val bytes: Bytes,
    ) : SimulationEvent

    data class DeliverMeshEvent(
        val targetNode: SimulatedNodeId,
        val event: MeshEvent,
    ) : SimulationEvent

    data class ObserveLink(
        val targetNode: SimulatedNodeId,
        val event: LinkEvent,
    ) : SimulationEvent

    data class FireRuntimeTimer(
        val targetNode: SimulatedNodeId,
        val key: MeshTimerKey,
    ) : SimulationEvent

    data class ApplyLinkFault(val actionId: String) : SimulationEvent
}
```

- [ ] **Step 5: Add structured trace retention.**

`SimulationTraceRecord` contains `time`, `sequence`, optional node/link IDs, category, redacted packet-ID prefix, byte count, TTL, generation, correlation ID, and typed outcome. `BoundedSimulationTrace.append` removes the oldest record only when full and increments a saturating `droppedCount`. It accepts no raw payload or secret field.

- [ ] **Step 6: Run common tests on Android and iOS.**

```bash
rtk ./gradlew :transport:simulation:allTests --console=plain
```

Expected: queue, limit, trace, SHA, and coverage tests pass on all configured targets.

- [ ] **Step 7: Check and commit.**

```bash
rtk git diff --check
rtk git add transport/simulation/src
rtk git commit -m "feat: add bounded simulation event queue"
```

## Task 4: Add precise runtime acknowledgement and immediate-effect fences

**Files:**
- Modify: `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/runtime/MeshRuntime.kt`
- Create: `engine/mesh/src/commonTest/kotlin/com/yet/bitmessage/engine/mesh/runtime/MeshRuntimeAcknowledgementTest.kt`

- [ ] **Step 1: Write acknowledgement contract tests first.**

Use a recording engine/executor rather than `runCurrent()` to prove the new API:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class MeshRuntimeAcknowledgementTest {
    @Test
    fun submitAndAwaitReturnsOnlyAfterTransitionAndEffectsAreRegistered() = runTest {
        val effectStarted = CompletableDeferred<Unit>()
        val effectMayReturn = CompletableDeferred<Unit>()
        val runtime = MeshRuntime(
            engine = MeshEngine(),
            executor = MeshEffectExecutor { effect ->
                if (effect is MeshEffect.ComputePacketDigest) {
                    effectStarted.complete(Unit)
                    effectMayReturn.await()
                }
                null
            },
            parentScope = backgroundScope,
            limits = MeshLimits(),
        )
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)

        assertEquals(SubmitResult.Accepted, runtime.submitAndAwait(opened(runtime.generation)))
        assertEquals(SubmitResult.Accepted, runtime.submitAndAwait(MeshFixtures.packetDecoded()))
        effectStarted.await()
        assertEquals(1, assertNotNull(runtime.state.value).pendingAdmissions.size)
        assertFalse(effectMayReturn.isCompleted)

        effectMayReturn.complete(Unit)
        assertIs<RuntimeQuiescenceResult.Quiescent>(runtime.awaitImmediateQuiescence(16))
        runtime.close(MeshFixtures.now)
    }

    @Test
    fun acknowledgementDoesNotWaitForAsynchronousCompletion() = runTest {
        val scheduled = CompletableDeferred<MeshEvent>()
        val runtime = runtime { effect ->
            if (effect is MeshEffect.ComputePacketDigest) {
                scheduled.complete(digestResult(effect))
                null
            } else {
                immediateProtocolResult(effect)
            }
        }
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)
        runtime.submitAndAwait(opened(runtime.generation))

        assertEquals(SubmitResult.Accepted, runtime.submitAndAwait(MeshFixtures.packetDecoded()))
        assertTrue(scheduled.isCompleted)
        assertTrue(assertNotNull(runtime.state.value).admittedPackets.isEmpty())

        assertEquals(SubmitResult.Accepted, runtime.submitAndAwait(scheduled.await()))
        runtime.awaitImmediateQuiescence(16)
        assertEquals(1, assertNotNull(runtime.state.value).admittedPackets.size)
        runtime.close(MeshFixtures.now)
    }

    @Test
    fun immediateResultChainsReachACausalFenceWithoutPolling() = runTest {
        val runtime = runtime(::immediateProtocolResult)
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)
        runtime.submitAndAwait(opened(runtime.generation))
        runtime.submitAndAwait(MeshFixtures.packetDecoded())

        val settled = assertIs<RuntimeQuiescenceResult.Quiescent>(runtime.awaitImmediateQuiescence(32))
        assertTrue(settled.processedEffects > 0)
        assertEquals(1, assertNotNull(runtime.state.value).admittedPackets.size)
        runtime.close(MeshFixtures.now)
    }

    @Test
    fun immediateEffectBoundStopsACausalCycle() = runTest {
        val loopEvent = opened(MeshFixtures.generation)
        val loopEffect = MeshEffect.PublishPublicPayload(
            correlationId = CorrelationId.of("loop"),
            generation = MeshFixtures.generation,
            packetId = PacketIdentity.fromSha256(MeshFixtures.fakeSha256Digest),
            sender = MeshFixtures.localPeer,
            ingressLink = MeshFixtures.linkA,
            timestamp = 0u,
            payload = Bytes.copyOf(byteArrayOf()),
        )
        val engine = object : Engine<MeshState, MeshEvent, MeshEffect> {
            override fun reduce(state: MeshState, event: MeshEvent): Transition<MeshState, MeshEffect> =
                Transition(state, effects = listOf(loopEffect))
        }
        val runtime = MeshRuntime(engine, MeshEffectExecutor { loopEvent }, backgroundScope, MeshLimits())
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)
        runtime.submitAndAwait(loopEvent)
        assertIs<RuntimeQuiescenceResult.LimitExceeded>(runtime.awaitImmediateQuiescence(4))
        runtime.close(MeshFixtures.now)
    }
}
```

Add these concrete local helpers, then add `Closed`, structural decode rejection, mailbox-pressure, and reducer-failure tests so the suspending API never hangs:

```kotlin
private fun TestScope.runtime(
    execute: suspend (MeshEffect) -> MeshEvent?,
): MeshRuntime = MeshRuntime(
    MeshEngine(),
    MeshEffectExecutor { effect -> execute(effect) },
    backgroundScope,
    MeshLimits(),
)

private fun opened(generation: Generation): MeshEvent.LinkObserved = MeshEvent.LinkObserved(
    generation = generation,
    observedAt = MeshFixtures.now,
    event = LinkEvent.Opened(MeshFixtures.linkA, LinkCapabilities(4_096, writeReady = true)),
)

private fun digestResult(effect: MeshEffect.ComputePacketDigest): MeshEvent.PacketDigestComputed =
    MeshEvent.PacketDigestComputed(
        correlationId = effect.correlationId,
        generation = effect.generation,
        observedAt = MeshFixtures.now,
        result = MeshResult.Success(MeshFixtures.fakeSha256Digest),
    )

private suspend fun immediateProtocolResult(effect: MeshEffect): MeshEvent? = when (effect) {
    is MeshEffect.ComputePacketDigest -> digestResult(effect)
    is MeshEffect.PublishPublicPayload -> null
    else -> error("Unexpected effect in acknowledgement test: ${effect::class.simpleName}")
}
```

- [ ] **Step 2: Run the test and prove the API is absent.**

```bash
rtk ./gradlew :engine:mesh:testAndroidHostTest --tests '*MeshRuntimeAcknowledgementTest' --console=plain
```

Expected: compilation fails for `submitAndAwait`, `awaitImmediateQuiescence`, and `RuntimeQuiescenceResult`.

- [ ] **Step 3: Add the public outcome contract.**

```kotlin
sealed interface RuntimeQuiescenceResult {
    data class Quiescent(val processedEffects: Long) : RuntimeQuiescenceResult
    data object Closed : RuntimeQuiescenceResult
    data class LimitExceeded(val maximumEffects: Int) : RuntimeQuiescenceResult
}
```

Add these overloads without changing `trySubmit`:

```kotlin
suspend fun submitAndAwait(event: MeshEvent): SubmitResult

suspend fun submitAndAwait(
    event: LinkEvent,
    observedAt: MonotonicTime,
): SubmitResult

suspend fun awaitImmediateQuiescence(maxProcessedEffects: Int): RuntimeQuiescenceResult
```

Require `maxProcessedEffects > 0`.

- [ ] **Step 4: Put acknowledgement on the bounded external mailbox.**

Replace `Channel<MeshEvent>` with `Channel<EventEnvelope>`, where:

```kotlin
private data class EventEnvelope(
    val event: MeshEvent,
    val acknowledged: CompletableDeferred<Unit>?,
)
```

`trySubmit` uses `acknowledged = null`. `submitAndAwait` creates a deferred, performs the same lifecycle/decode/capacity checks as `trySubmit`, uses `trySend`, releases `lifecycleMutex`, and selects between the acknowledgement and `actorJob.onJoin`. The actor completes the deferred only after `reduceAndPublish` returns. Because `reduceAndPublish` commits `mutableState`, publishes trace, and sends every emitted effect into the bounded ordered effect channel before returning, `Accepted` has the approved meaning:

```text
accepted -> transition committed -> effects submitted -> acknowledgement
```

It does not wait for executor results.

- [ ] **Step 5: Add causal fences to the existing effect and actor queues.**

Use closed command types:

```kotlin
private sealed interface EffectCommand {
    data class Execute(val envelope: EffectEnvelope) : EffectCommand
    data class Fence(val acknowledged: CompletableDeferred<Long>) : EffectCommand
}

private sealed interface ActorCommand {
    data class Reduce(
        val event: MeshEvent,
        val acknowledged: CompletableDeferred<Unit>,
    ) : ActorCommand

    data class EffectCount(
        val acknowledged: CompletableDeferred<Long>,
    ) : ActorCommand
}
```

The run context owns `registeredEffects` (written by the actor) and `processedEffects` (written by the effect worker), both checked against `Long.MAX_VALUE`. Each emitted effect is wrapped in `EffectCommand.Execute`; each completed executor/codec/timer-registration command increments `processedEffects` exactly once.

`awaitImmediateQuiescence` performs a deterministic causal-fence loop:

```kotlin
context.quiescenceMutex.withLock {
    val startingProcessed = context.lastReportedProcessedEffects
    while (true) {
        val processed = effectFence(context) ?: return RuntimeQuiescenceResult.Closed
        val registered = actorEffectCount(context) ?: return RuntimeQuiescenceResult.Closed
        val delta = processed - startingProcessed
        if (delta > maxProcessedEffects) {
            context.lastReportedProcessedEffects = processed
            return RuntimeQuiescenceResult.LimitExceeded(maxProcessedEffects)
        }
        if (processed == registered) {
            context.lastReportedProcessedEffects = processed
            return RuntimeQuiescenceResult.Quiescent(delta)
        }
    }
}
```

The run context initializes `lastReportedProcessedEffects = 0` and owns a `quiescenceMutex` so two barriers cannot race their cursor. The effect fence sits in the ordered effect channel. Immediate result delivery uses the existing rendezvous `results` channel; the actor commits that result transition before it can answer the subsequent actor count request. If the result emits another effect, `registeredEffects > processedEffects`, so the loop repeats. A provider that registers a future completion and returns `null` is processed without waiting for that future event.

Do not use `Channel.isEmpty`, `yield`, delay, `runCurrent`, public mutable state, or an unbounded auxiliary channel.

- [ ] **Step 6: Run narrow and full Phase 4 tests.**

```bash
rtk ./gradlew \
  :engine:mesh:testAndroidHostTest --tests '*MeshRuntimeAcknowledgementTest' \
  :engine:mesh:meshEngineCheck \
  :engine:mesh:allTests \
  --console=plain
```

Expected: new acknowledgement tests and all 66/66 existing Phase 4 target tests pass; the mesh gate remains positive.

- [ ] **Step 7: Check and commit.**

```bash
rtk git diff --check
rtk git add engine/mesh/src/commonMain engine/mesh/src/commonTest
rtk git commit -m "feat: add deterministic mesh runtime acknowledgement"
```

## Task 5: Route runtime timers through an injected driver

**Files:**
- Create: `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/runtime/MeshTimerDriver.kt`
- Modify: `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/runtime/MeshRuntime.kt`
- Create: `engine/mesh/src/commonTest/kotlin/com/yet/bitmessage/engine/mesh/runtime/MeshTimerDriverTest.kt`

- [ ] **Step 1: Write timer-driver tests before changing runtime timers.**

```kotlin
class MeshTimerDriverTest {
    @Test
    fun scheduleRegistersExactRequestWithoutWaitingForDeadline() = runTest {
        val driver = RecordingTimerDriver()
        val digestStarted = CompletableDeferred<Unit>()
        val releaseDigest = CompletableDeferred<Unit>()
        val runtime = MeshRuntime(
            MeshEngine(),
            MeshEffectExecutor { effect ->
                if (effect is MeshEffect.ComputePacketDigest) {
                    digestStarted.complete(Unit)
                    releaseDigest.await()
                }
                null
            },
            backgroundScope,
            MeshLimits(),
            timerDriverFactory = MeshTimerDriverFactory { driver },
        )
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)
        runtime.submitAndAwait(opened(runtime.generation))
        runtime.submitAndAwait(MeshFixtures.packetDecoded())
        digestStarted.await()

        val request = driver.requests.single()
        assertEquals(MeshFixtures.now.plus(15.seconds), request.deadline)
        assertEquals(runtime.generation, request.generation)
        assertEquals(1, assertNotNull(runtime.state.value).pendingAdmissions.size)
        releaseDigest.complete(Unit)
        runtime.awaitImmediateQuiescence(32)
        runtime.close(MeshFixtures.now)
    }

    @Test
    fun firingAndCancellationRemainExplicitAndGenerationCorrelated() = runTest {
        val driver = RecordingTimerDriver()
        val runtime = runtime(driver, ProtocolExecutor())
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)
        runtime.submitAndAwait(opened(runtime.generation))
        runtime.submitAndAwait(MeshFixtures.packetDecoded())
        runtime.awaitImmediateQuiescence(32)

        val pendingRequest = driver.requests.first { it.deadline == MeshFixtures.now.plus(15.seconds) }
        assertTrue(driver.cancelledKeys.contains(pendingRequest.key))
        val dedupRequest = driver.requests.single { it.deadline == MeshFixtures.now.plus(5.minutes) }
        dedupRequest.onElapsed()
        runtime.awaitImmediateQuiescence(32)
        assertTrue(assertNotNull(runtime.state.value).admittedPackets.isEmpty())
        runtime.close(dedupRequest.deadline)
    }

    @Test
    fun stopCancelsEveryRegisteredTimerAndDefaultDriverStillUsesCoroutineDelay() = runTest {
        val recording = RecordingTimerDriver()
        val recordedRuntime = runtime(recording, MeshEffectExecutor { null })
        recordedRuntime.start(MeshFixtures.localPeer, MeshFixtures.now)
        recordedRuntime.submitAndAwait(opened(recordedRuntime.generation))
        recordedRuntime.submitAndAwait(MeshFixtures.packetDecoded())
        recordedRuntime.awaitImmediateQuiescence(8)
        recordedRuntime.stop(MeshFixtures.now)
        assertEquals(1, recording.cancelAllCalls)

        val defaultRuntime = MeshRuntime(MeshEngine(), MeshEffectExecutor { null }, backgroundScope, MeshLimits())
        defaultRuntime.start(MeshFixtures.localPeer, MeshFixtures.now)
        defaultRuntime.submitAndAwait(opened(defaultRuntime.generation))
        defaultRuntime.submitAndAwait(MeshFixtures.packetDecoded())
        defaultRuntime.awaitImmediateQuiescence(8)
        assertEquals(1, assertNotNull(defaultRuntime.state.value).pendingAdmissions.size)
        advanceTimeBy(15.seconds)
        runCurrent()
        assertTrue(assertNotNull(defaultRuntime.state.value).pendingAdmissions.isEmpty())
        defaultRuntime.close(MeshFixtures.now.plus(15.seconds))
    }
}
```

Add these concrete local helpers below the tests:

```kotlin
private class RecordingTimerDriver : MeshTimerDriver {
    val requests = mutableListOf<MeshTimerRequest>()
    val cancelledKeys = mutableListOf<MeshTimerKey>()
    var cancelAllCalls = 0

    override suspend fun schedule(request: MeshTimerRequest) { requests += request }
    override suspend fun cancel(key: MeshTimerKey) { cancelledKeys += key }
    override suspend fun cancelAll() { cancelAllCalls += 1 }
}

private fun TestScope.runtime(driver: MeshTimerDriver, executor: MeshEffectExecutor): MeshRuntime =
    MeshRuntime(
        MeshEngine(), executor, backgroundScope, MeshLimits(),
        timerDriverFactory = MeshTimerDriverFactory { driver },
    )

private fun opened(generation: Generation): MeshEvent.LinkObserved = MeshEvent.LinkObserved(
    generation = generation,
    observedAt = MeshFixtures.now,
    event = LinkEvent.Opened(MeshFixtures.linkA, LinkCapabilities(4_096, writeReady = true)),
)
```

```kotlin
private class ProtocolExecutor : MeshEffectExecutor {
    override suspend fun execute(effect: MeshEffect): MeshEvent? = when (effect) {
        is MeshEffect.ComputePacketDigest -> MeshEvent.PacketDigestComputed(
            correlationId = effect.correlationId,
            generation = effect.generation,
            observedAt = MeshFixtures.now,
            result = MeshResult.Success(MeshFixtures.fakeSha256Digest),
        )
        is MeshEffect.PublishPublicPayload -> null
        else -> error("Unexpected effect in timer-driver test: ${effect::class.simpleName}")
    }
}
```

- [ ] **Step 2: Run and prove the driver API is absent.**

```bash
rtk ./gradlew :engine:mesh:testAndroidHostTest --tests '*MeshTimerDriverTest' --console=plain
```

Expected: compilation fails for the timer-driver types and constructor argument.

- [ ] **Step 3: Add the timer contract and coroutine default.**

```kotlin
data class MeshTimerKey(
    val correlationId: CorrelationId,
    val timerId: TimerId,
)

data class MeshTimerRequest(
    val key: MeshTimerKey,
    val generation: Generation,
    val observedAt: MonotonicTime,
    val deadline: MonotonicTime,
    val onElapsed: suspend () -> Unit,
)

interface MeshTimerDriver {
    suspend fun schedule(request: MeshTimerRequest)
    suspend fun cancel(key: MeshTimerKey)
    suspend fun cancelAll()
}

fun interface MeshTimerDriverFactory {
    fun create(scope: CoroutineScope): MeshTimerDriver
}
```

`CoroutineMeshTimerDriver` owns the current mutex/map/job behavior. `schedule` creates a lazy job, installs it by `MeshTimerKey`, cancels the previous job, then starts it. It computes delay with `request.deadline.elapsedSince(request.observedAt)`, invokes `onElapsed`, and removes only itself in `finally`. `cancelAll` removes and cancels every job. Export one default factory; construction launches nothing.

- [ ] **Step 4: Inject one driver per runtime generation.**

Add the optional final constructor parameter:

```kotlin
private val timerDriverFactory: MeshTimerDriverFactory = CoroutineMeshTimerDriver.Factory
```

`createRunContext` constructs the driver with the owned run scope. `MeshEffect.Schedule` becomes one `driver.schedule(MeshTimerRequest(...))`; `Cancel` calls `driver.cancel`; shutdown calls `driver.cancelAll()` before cancelling the supervisor. The timer callback sends the same correlated `MeshEvent.TimerElapsed` into `context.results`.

Remove the timer map/mutex/jobs from `MeshRuntime`; ownership now belongs to the driver. Count schedule and cancel as processed effects for Task 4 fences once driver registration/cancellation returns.

- [ ] **Step 5: Run timer, runtime, and complete mesh suites.**

```bash
rtk ./gradlew \
  :engine:mesh:testAndroidHostTest --tests '*MeshTimerDriverTest' \
  :engine:mesh:testAndroidHostTest --tests '*MeshRuntimeTest' \
  :engine:mesh:meshEngineCheck \
  :engine:mesh:allTests \
  --console=plain
```

Expected: injected-driver tests pass; existing cancellation/expiry/lifecycle behavior remains green on Android and iOS.

- [ ] **Step 6: Check and commit.**

```bash
rtk git diff --check
rtk git add engine/mesh/src/commonMain engine/mesh/src/commonTest
rtk git commit -m "feat: make mesh runtime timers injectable"
```

## Task 6: Define explicit topology, directed links, and fault plans

**Files:**
- Create: `transport/simulation/src/commonMain/kotlin/com/yet/bitmessage/transport/simulation/DirectedSimulatedLink.kt`
- Create: `transport/simulation/src/commonMain/kotlin/com/yet/bitmessage/transport/simulation/FaultPlan.kt`
- Create: `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/SimulationSubstrateTest.kt`

- [ ] **Step 1: Write construction and write-decision tests.**

```kotlin
class SimulationSubstrateTest {
    @Test
    fun bidirectionalConnectionCreatesTwoIndependentDirections() {
        val connection = SimulatedConnection.create(
            id = "ab",
            first = nodeA,
            second = nodeB,
            mtu = 512,
            latency = 100.milliseconds,
        )
        assertEquals(nodeA, connection.aToB.source.nodeId)
        assertEquals(nodeB, connection.aToB.target.nodeId)
        assertEquals(nodeB, connection.bToA.source.nodeId)
        assertEquals(nodeA, connection.bToA.target.nodeId)
        assertNotEquals(connection.aToB.id, connection.bToA.id)
    }

    @Test
    fun writeValidationReturnsTypedReadinessMtuAndDisconnectOutcomes() {
        val direction = readyDirection(mtu = 4)
        assertIs<DirectedWriteDecision.PayloadTooLarge>(direction.decide(bytes(5)))
        assertIs<DirectedWriteDecision.Backpressured>(direction.copy(writeReady = false).decide(bytes(4)))
        assertIs<DirectedWriteDecision.Disconnected>(direction.copy(open = false).decide(bytes(4)))
        assertIs<DirectedWriteDecision.Accepted>(direction.decide(bytes(4)))
    }

    @Test
    fun faultPlanMatchesOnlyExplicitOrdinalAndIsBounded() {
        val plan = FaultPlan(
            transmissionFaults = listOf(
                TransmissionFault.Drop(TransmissionSelector(directionAB, writeOrdinal = 2)),
                TransmissionFault.Duplicate(TransmissionSelector(directionAB, writeOrdinal = 3), copies = 2),
            ),
            timedLinkFaults = emptyList(),
            maximumActions = 2,
        )
        assertNull(plan.decision(directionAB, 1))
        assertIs<TransmissionFault.Drop>(plan.decision(directionAB, 2))
        assertIs<TransmissionFault.Duplicate>(plan.decision(directionAB, 3))
    }
}
```

- [ ] **Step 2: Run the narrow test and verify missing types.**

```bash
rtk ./gradlew :transport:simulation:testAndroidHostTest --tests '*SimulationSubstrateTest' --console=plain
```

Expected: compilation fails for connection/link/fault types.

- [ ] **Step 3: Implement endpoint and directed-link values.**

```kotlin
data class SimulatedEndpoint(
    val nodeId: SimulatedNodeId,
    val linkId: LinkId,
)

data class DirectedSimulatedLink(
    val id: SimulatedLinkId,
    val source: SimulatedEndpoint,
    val target: SimulatedEndpoint,
    val mtu: Int,
    val latency: Duration,
    val open: Boolean = true,
    val writeReady: Boolean = true,
    val epoch: Long = 0,
) {
    init {
        require(source.nodeId != target.nodeId)
        require(mtu > 0)
        require(latency.isFinite() && latency >= Duration.ZERO)
        require(epoch >= 0)
    }

    fun decide(byteCount: Int): DirectedWriteDecision = when {
        !open -> DirectedWriteDecision.Disconnected
        !writeReady -> DirectedWriteDecision.Backpressured
        byteCount > mtu -> DirectedWriteDecision.PayloadTooLarge(mtu)
        else -> DirectedWriteDecision.Accepted
    }
}
```

`SimulatedConnection.create` produces `ab:a-to-b` and `ab:b-to-a` directions and one local `LinkId` per node endpoint. The source endpoint's `LinkId` maps outbound commands to a direction; delivery uses the target endpoint's `LinkId`.

- [ ] **Step 4: Implement a finite non-random fault plan.**

Use these closed types:

```kotlin
data class TransmissionSelector(
    val directionId: SimulatedLinkId,
    val writeOrdinal: Long,
)

sealed interface TransmissionFault {
    val selector: TransmissionSelector
    data class Drop(override val selector: TransmissionSelector) : TransmissionFault
    data class Duplicate(override val selector: TransmissionSelector, val copies: Int) : TransmissionFault
    data class AddDelay(override val selector: TransmissionSelector, val delay: Duration) : TransmissionFault
    data class CompleteWith(override val selector: TransmissionSelector, val result: PlannedLinkResult) : TransmissionFault
    data class CorruptByte(override val selector: TransmissionSelector, val offset: Int, val xorMask: UByte) : TransmissionFault
}

data class TimedLinkFault(
    val id: String,
    val deadline: MonotonicTime,
    val action: LinkFaultAction,
)

sealed interface LinkFaultAction {
    data class SetReadiness(val directionId: SimulatedLinkId, val ready: Boolean) : LinkFaultAction
    data class SetOpen(val directionId: SimulatedLinkId, val open: Boolean) : LinkFaultAction
    data class Partition(val directionIds: List<SimulatedLinkId>, val active: Boolean) : LinkFaultAction
    data class Reconnect(val connectionId: String) : LinkFaultAction
}

sealed interface PlannedLinkResult {
    data object Backpressured : PlannedLinkResult
    data object Disconnected : PlannedLinkResult
    data object Unsupported : PlannedLinkResult
    data class Failed(val code: LinkFailureCode) : PlannedLinkResult
}
```

Validate positive ordinals, duplicate copies in `2..8`, finite nonnegative delays, valid byte offsets/masks, distinct fault IDs, and `actions.size <= maximumActions`. Build immutable defensive copies and deterministic lookup maps. Execution never reads an RNG.

- [ ] **Step 5: Run the primitive suites on both targets.**

```bash
rtk ./gradlew :transport:simulation:allTests --console=plain
```

Expected: topology/fault primitives and prior tests pass on Android and iOS.

- [ ] **Step 6: Check and commit.**

```bash
rtk git diff --check
rtk git add transport/simulation/src
rtk git commit -m "feat: model directed simulation links and faults"
```

## Task 7: Add independent protocol plans and one real node runtime

**Files:**
- Create: `transport/simulation/src/commonMain/kotlin/com/yet/bitmessage/transport/simulation/ProtocolPlans.kt`
- Create: `transport/simulation/src/commonMain/kotlin/com/yet/bitmessage/transport/simulation/SimulationEffectExecutor.kt`
- Create: `transport/simulation/src/commonMain/kotlin/com/yet/bitmessage/transport/simulation/SimulatedNode.kt`
- Create: `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/SimulationEntropyTest.kt`

- [ ] **Step 1: Write entropy/digest/verification tests first.**

```kotlin
class SimulationEntropyTest {
    @Test
    fun simulationPlanSeedCannotChangeProtocolEntropyTranscript() {
        val first = NodeProtocolEntropy(seed = 91)
        val second = NodeProtocolEntropy(seed = 91)
        compileScenario(seed = 1)
        val firstBytes = first.generate(EntropyRequest(CorrelationId.of("entropy:1"), 16)).bytes
        compileScenario(seed = 999)
        val secondBytes = second.generate(EntropyRequest(CorrelationId.of("entropy:1"), 16)).bytes
        assertEquals(firstBytes, secondBytes)
        assertEquals(first.transcript, second.transcript)
    }

    @Test
    fun protocolSeedCannotChangeCompiledFaultPlan() {
        val firstPlan = compileScenario(seed = 41, protocolSeeds = mapOf(nodeA to 1))
        val secondPlan = compileScenario(seed = 41, protocolSeeds = mapOf(nodeA to 2))
        assertEquals(firstPlan.topology, secondPlan.topology)
        assertEquals(firstPlan.actions, secondPlan.actions)
        assertEquals(firstPlan.faultPlan, secondPlan.faultPlan)
    }

    @Test
    fun digestDefaultsToRealShaAndUsesOverridesOnlyByExplicitOrdinal() = runTest {
        val host = RecordingEffectHost()
        val executor = SimulationEffectExecutor(nodeA, host, NodeProtocolEntropy(7), VerificationPlan.EMPTY, DigestPlan.EMPTY)
        val effect = digestEffect(ordinal = 1)
        val result = assertIs<MeshEvent.PacketDigestComputed>(executor.execute(effect))
        assertEquals(SimulationSha256.digest(effect.input.canonicalBytes), assertIs<MeshResult.Success<Bytes>>(result.result).value)

        val overridden = SimulationEffectExecutor(
            nodeA,
            host,
            NodeProtocolEntropy(7),
            VerificationPlan.EMPTY,
            DigestPlan.failure(requestOrdinal = 2),
        )
        assertEquals(MeshResult.Failure(MeshFailureCode.DIGEST_FAILED), digestResult(overridden, ordinal = 2).result)
    }

    @Test
    fun verificationIsFailClosedUnlessARequestIsExplicitlyValid() = runTest {
        val effect = verificationEffect()
        val denied = executor(VerificationPlan.EMPTY).execute(effect)
        assertEquals(MeshResult.Success(false), assertIs<MeshEvent.SignatureVerified>(denied).result)
        val allowed = executor(VerificationPlan.valid(requestOrdinal = 1)).execute(effect)
        assertEquals(MeshResult.Success(true), assertIs<MeshEvent.SignatureVerified>(allowed).result)
    }
}
```

- [ ] **Step 2: Run and prove the plan/executor types are absent.**

```bash
rtk ./gradlew :transport:simulation:testAndroidHostTest --tests '*SimulationEntropyTest' --console=plain
```

Expected: compilation fails for protocol plans, node entropy, effect host, and executor.

- [ ] **Step 3: Implement node-local entropy and explicit outcomes.**

```kotlin
data class ProtocolEntropyRecord(
    val correlationId: CorrelationId,
    val byteCount: Int,
    val bytes: Bytes,
)

class NodeProtocolEntropy(seed: Int) : EntropySource {
    private val random = Random(seed)
    private val mutableTranscript = mutableListOf<ProtocolEntropyRecord>()
    val transcript: List<ProtocolEntropyRecord> get() = mutableTranscript.toList()

    override fun generate(request: EntropyRequest): EntropyGenerated {
        val bytes = ByteArray(request.byteCount)
        random.nextBytes(bytes)
        val owned = Bytes.copyOf(bytes)
        mutableTranscript += ProtocolEntropyRecord(request.correlationId, request.byteCount, owned)
        return EntropyGenerated(request.correlationId, owned)
    }
}

sealed interface PlannedOutcome<out T> {
    data class Immediate<T>(val result: MeshResult<T>) : PlannedOutcome<T>
    data class Delayed<T>(val delay: Duration, val result: MeshResult<T>) : PlannedOutcome<T>
}
```

`VerificationPlan` and `DigestPlan` are immutable maps keyed only by positive request ordinal. Duplicate ordinals are rejected. Verification defaults to immediate `Success(false)`. Digest defaults to immediate `Success(SimulationSha256.digest(input.canonicalBytes))`. Delayed overrides validate finite nonnegative delay. Digest overrides support explicit failure/collision/delay/stale tests; no pseudo-hash exists.

- [ ] **Step 4: Define the narrow effect-host boundary.**

```kotlin
internal interface SimulationEffectHost {
    val now: MonotonicTime
    suspend fun scheduleMeshEvent(nodeId: SimulatedNodeId, deadline: MonotonicTime, event: MeshEvent)
    suspend fun submitWrite(nodeId: SimulatedNodeId, command: LinkCommand.Write)
    suspend fun submitClose(nodeId: SimulatedNodeId, command: LinkCommand.Close)
    fun recordPublication(nodeId: SimulatedNodeId, effect: MeshEffect.PublishPublicPayload)
}
```

Host methods must either commit the scheduled operation or throw a typed simulator-limit exception that `executeEffect` converts to `MeshEvent.EffectFailed`; they never silently drop work.

- [ ] **Step 5: Implement exhaustive effect execution.**

`SimulationEffectExecutor.execute` increments per-kind positive request ordinals and handles every non-runtime-owned effect:

```kotlin
override suspend fun execute(effect: MeshEffect): MeshEvent? = when (effect) {
    is MeshEffect.ComputePacketDigest -> digestEvent(effect, digestPlan.outcome(nextDigestOrdinal(), effect.input))
    is MeshEffect.VerifySignature -> verificationEvent(effect, verificationPlan.outcome(nextVerificationOrdinal()))
    is MeshEffect.DecodeFragmentPayload -> MeshEvent.FragmentPayloadDecoded(
        effect.correlationId, effect.generation, host.now, effect.packetId, effect.source, effect.sender,
        FragmentPayloadCodec.decode(effect.payload),
    )
    is MeshEffect.EncodeRelay -> MeshEvent.RelayEncoded(
        effect.correlationId, effect.generation, host.now, effect.packetId, effect.targets,
        RelayEncoding.withTtl(effect.packet, effect.outgoingTtl),
    )
    is MeshEffect.RequestEntropy -> MeshEvent.EntropyProvided(
        effect.correlationId, effect.generation, host.now, effect.packetId, effect.source,
        effect.packet, effect.outgoingTtl,
        MeshResult.Success(entropy.generate(EntropyRequest(effect.correlationId, effect.byteCount)).bytes),
    )
    is MeshEffect.WriteLink -> host.submitWrite(nodeId, effect.command).let { null }
    is MeshEffect.CloseLink -> host.submitClose(nodeId, effect.command).let { null }
    is MeshEffect.PublishPublicPayload -> host.recordPublication(nodeId, effect).let { null }
    is MeshEffect.ReinjectPacket,
    is MeshEffect.Schedule,
    is MeshEffect.Cancel,
    -> error("Runtime-owned effect reached simulation executor: ${effect::class.simpleName}")
}
```

For `PlannedOutcome.Delayed`, construct the exact correlated result event with `observedAt = host.now.plus(delay)`, call `scheduleMeshEvent`, and return `null`. Immediate outcomes return the event directly.

- [ ] **Step 6: Add `SimulatedNode` around real production components.**

```kotlin
data class SimulatedNodeConfig(
    val id: SimulatedNodeId,
    val localPeer: WirePeerId,
    val protocolSeed: Int,
    val meshLimits: MeshLimits = MeshLimits(),
    val verificationPlan: VerificationPlan = VerificationPlan.EMPTY,
    val digestPlan: DigestPlan = DigestPlan.EMPTY,
)

class SimulatedNode internal constructor(
    val config: SimulatedNodeConfig,
    parentScope: CoroutineScope,
    host: SimulationEffectHost,
    timerDriverFactory: MeshTimerDriverFactory,
    maximumPublications: Int,
) {
    private val publications = BoundedPublications(maximumPublications)
    private val entropy = NodeProtocolEntropy(config.protocolSeed)
    private val executor = SimulationEffectExecutor(config.id, host, entropy, config.verificationPlan, config.digestPlan)
    val runtime = MeshRuntime(MeshEngine(config.meshLimits), executor, parentScope, config.meshLimits, timerDriverFactory)

    suspend fun start(at: MonotonicTime): StartResult = runtime.start(config.localPeer, at)
    suspend fun stop(at: MonotonicTime): StopResult = runtime.stop(at)
    suspend fun close(at: MonotonicTime) = runtime.close(at)
    internal fun record(effect: MeshEffect.PublishPublicPayload) = publications.append(
        PublicationProjection(
            nodeId = config.id,
            packetId = effect.packetId,
            sender = effect.sender,
            timestamp = effect.timestamp,
            payloadSize = effect.payload.size,
        ),
    )
    fun snapshot(): SimulatedNodeSnapshot = SimulatedNodeSnapshot(
        id = config.id,
        state = requireNotNull(runtime.state.value),
        publications = publications.snapshot(),
        entropyTranscript = entropy.transcript,
        structuralDecodeRejections = runtime.structuralDecodeRejectionCount.value,
    )
}
```

Define `PublicationProjection` with exactly the fields shown above. `BoundedPublications` owns a positive capacity, rejects overflow with the typed simulator-limit failure, returns defensive snapshots, and never exposes payload bytes. Route `recordPublication` to the owning node's store through the host, passing `limits.maxPublicationRecords` at construction. No public setter exposes node state.

- [ ] **Step 7: Run executor and existing production suites.**

```bash
rtk ./gradlew \
  :transport:simulation:testAndroidHostTest --tests '*SimulationEntropyTest' \
  :transport:simulation:allTests \
  :protocol:bitchat:allTests \
  :engine:mesh:allTests \
  --console=plain
```

Expected: entropy/digest/verification tests pass on both targets; protocol and mesh remain green.

- [ ] **Step 8: Check and commit.**

```bash
rtk git diff --check
rtk git add transport/simulation/src
rtk git commit -m "feat: add real simulated mesh nodes"
```

## Task 8: Implement network scheduling and exact quiescence

Implementation note: a runtime causal-fence limit reports the actual effect count already processed, so simulator event diagnostics remain exact even on limit exit. Scheduled events targeting a suspended runtime are recorded as rejected observations rather than resource-limit failures; `stop/start` is suspension/restart, not a new security epoch. A timer replacement may reclaim a full queue slot only while its prior event is still queued. These cases are covered by the Task 8 implementation and quiescence tests.

**Files:**
- Create: `transport/simulation/src/commonMain/kotlin/com/yet/bitmessage/transport/simulation/SimulatedNetwork.kt`
- Create: `transport/simulation/src/commonMain/kotlin/com/yet/bitmessage/transport/simulation/SimulationSnapshot.kt`
- Create: `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/SimulationQuiescenceTest.kt`

- [x] **Step 1: Write current-instant quiescence tests first.**

```kotlin
class SimulationQuiescenceTest {
    @Test
    fun currentQuiescenceDrainsSameTimeConsequencesButLeavesMaintenanceTimersQueued() = runTest {
        val network = testNetwork(backgroundScope)
        val (a, b, connection) = network.twoNodes()
        network.injectTransportWrite(connection.aToB.id, SimulationFixtures.broadcastWire)

        network.advanceBy(connection.aToB.latency)
        val settledAt = network.now
        val snapshot = network.snapshot()
        assertEquals(1, snapshot.node(b).publications.size)
        assertEquals(settledAt, network.now)
        assertTrue(snapshot.pendingEvents.any { it.category == ScheduledCategory.RUNTIME_TIMER })
        assertTrue(snapshot.pendingEvents.any { it.deadline > network.now })
    }

    @Test
    fun runUntilStopsAfterPredicateAndCurrentTimeConsequences() = runTest {
        val network = threeNodeRelayNetwork(backgroundScope)
        val result = network.runUntil(
            predicate = { it.node(nodeC).publications.size == 1 },
            maxProcessedEvents = 10_000,
            maxVirtualDuration = 30.seconds,
        )
        assertIs<RunUntilResult.Reached>(result)
        assertEquals(1, network.snapshot().node(nodeC).publications.size)
        assertTrue(network.snapshot().pendingEvents.any { it.deadline >= network.now.plus(4.minutes) })
    }

    @Test
    fun runUntilReportsUnreachableEventAndDurationLimitsDeterministically() = runTest {
        val noWork = testNetwork(backgroundScope)
        assertIs<RunUntilResult.Unreachable>(noWork.runUntil({ false }, 8, 1.seconds))

        val future = testNetwork(backgroundScope).apply { scheduleNoop(2.seconds) }
        assertIs<RunUntilResult.VirtualDurationExceeded>(future.runUntil({ false }, 8, 1.seconds))

        val cycle = zeroDelayCycleNetwork(backgroundScope)
        assertIs<RunUntilResult.EventLimitExceeded>(cycle.runUntil({ false }, 4, 1.seconds))
    }
}
```

- [x] **Step 2: Run and prove network APIs are absent.**

```bash
rtk ./gradlew :transport:simulation:testAndroidHostTest --tests '*SimulationQuiescenceTest' --console=plain
```

Expected: compilation fails for `SimulatedNetwork`, snapshots, and run results.

- [x] **Step 3: Add immutable snapshots and outcomes.**

```kotlin
data class PendingEventProjection(
    val id: ScheduledEventId,
    val deadline: MonotonicTime,
    val sequence: Long,
    val category: ScheduledCategory,
    val nodeId: SimulatedNodeId?,
    val linkId: SimulatedLinkId?,
    val packetId: PacketId?,
)

data class DeliveryProjection(
    val sequence: Long,
    val directionId: SimulatedLinkId,
    val sourceNode: SimulatedNodeId,
    val targetNode: SimulatedNodeId,
    val submittedAt: MonotonicTime,
    val deliveredAt: MonotonicTime,
    val byteCount: Int,
    val packetId: PacketId?,
    val ttl: UByte?,
    val packetClassification: String,
)

data class SimulationSnapshot(
    val now: MonotonicTime,
    val processedEvents: Long,
    val nodes: List<SimulatedNodeSnapshot>,
    val directions: List<DirectedSimulatedLink>,
    val publications: List<PublicationProjection>,
    val deliveries: List<DeliveryProjection>,
    val pendingEvents: List<PendingEventProjection>,
    val faultCursor: FaultCursor,
    val trace: List<SimulationTraceRecord>,
    val droppedTraceCount: Long,
)

sealed interface RunUntilResult {
    data class Reached(val snapshot: SimulationSnapshot) : RunUntilResult
    data class Unreachable(val snapshot: SimulationSnapshot) : RunUntilResult
    data class EventLimitExceeded(val maximum: Int, val diagnostics: SimulationDiagnostics) : RunUntilResult
    data class VirtualDurationExceeded(val maximum: Duration, val diagnostics: SimulationDiagnostics) : RunUntilResult
}
```

All lists are stable-ID sorted defensive snapshots. Pending events expose metadata only, never callback closures or payload bytes.

- [x] **Step 4: Implement the network-owned timer driver.**

For each node, `NetworkTimerDriver` implements `MeshTimerDriver`. `schedule` inserts `SimulationEvent.FireRuntimeTimer(nodeId, key)` at the exact deadline and stores the request callback in a bounded map keyed by `(nodeId, key)`. Rescheduling cancels/removes the previous queue entry before insertion. `cancel` removes both queue entry and callback. `cancelAll` removes every callback/event for that node. Firing removes the registration before invoking `onElapsed`.

The event queue remains the only future virtual-time authority. Runtime mailboxes/effects remain internal.

- [x] **Step 5: Implement node/topology lifecycle and effect-host methods.**

`SimulatedNetwork` constructor launches nothing. `addNode` validates node capacity and uniqueness, creates its runtime with a network timer driver, starts it at `now`, and acknowledges start. `connect` validates direction capacity, creates two directions, schedules `LinkEvent.Opened` observations at `now` in stable order, and returns the connection.

`injectTransportWrite(directionId, bytes)` represents an external transport submission, not a logical messenger send. It validates link state/MTU, consumes an explicit fault match, allocates the direction write ordinal, and schedules only transport delivery. Runtime-originated `submitWrite` also schedules the exact correlated `MeshEvent.LinkCompleted` for the source node. Written completion is scheduled before delivery at the same deadline; insertion sequence defines the order.

Loss suppresses delivery but may still return `Written`; duplication schedules exactly the planned copy count; added delay changes delivery deadline; backpressure/failure schedules only the matching typed `LinkResult`; corruption copies bytes then flips the selected byte.

`MeshRuntime` converts non-cancellation effect-provider exceptions into `EffectFailed`, so the host must latch the first simulator-limit failure from scheduling, writes, closes, and publication routing before throwing it. Quiescence and snapshot/predicate evaluation must rethrow that latched failure after immediate effects settle; a scenario must never appear successful because `MeshEngine` ignored an `EffectFailed`. `BoundedPublications` already keeps overflow sticky. Add an integration test that exceeds a host limit through a real runtime effect and proves the simulator reports the failure.

- [x] **Step 6: Implement current-time drainage without future advancement.**

```kotlin
suspend fun runCurrentUntilQuiescent(
    maxProcessedEvents: Int = limits.maxProcessedEvents,
): QuiescenceResult {
    require(maxProcessedEvents > 0)
    val starting = processedEvents
    while (true) {
        nodes.values.sortedBy { it.config.id }.forEach { node ->
            when (val result = node.runtime.awaitImmediateQuiescence(remaining(starting, maxProcessedEvents))) {
                is RuntimeQuiescenceResult.Quiescent -> consume(result.processedEffects)
                is RuntimeQuiescenceResult.LimitExceeded -> return QuiescenceResult.EventLimitExceeded(diagnostics())
                RuntimeQuiescenceResult.Closed -> Unit
            }
        }
        val due = queue.removeNextDue(now) ?: return QuiescenceResult.Quiescent(snapshot())
        consume(1)
        dispatch(due.value)
        if (processedEvents - starting > maxProcessedEvents) {
            return QuiescenceResult.EventLimitExceeded(diagnostics())
        }
    }
}
```

`removeNextDue(now)` removes one event whose deadline equals `now`. It never removes a future event. Dispatch uses `submitAndAwait`; structural rejection is traced and produces no mesh-state admission.

- [x] **Step 7: Implement explicit advancement and predicate-bounded execution.**

`advanceTo(target)` rejects backwards time, repeatedly sets `now` to the next queued deadline only when it is `<= target`, calls current-time quiescence, then sets `now = target` and drains once. `advanceBy` validates and uses checked `MonotonicTime.plus`.

`runUntil` follows this exact loop:

```kotlin
val startTime = now
val startCount = processedEvents
while (true) {
    when (val settled = runCurrentUntilQuiescent(maxProcessedEvents - (processedEvents - startCount).toInt())) {
        is QuiescenceResult.EventLimitExceeded -> return RunUntilResult.EventLimitExceeded(maxProcessedEvents, settled.diagnostics)
        is QuiescenceResult.Quiescent -> Unit
    }
    val stable = snapshot()
    if (predicate(stable)) return RunUntilResult.Reached(stable)
    val next = queue.peek()?.deadline ?: return RunUntilResult.Unreachable(stable)
    if (next.elapsedSince(startTime) > maxVirtualDuration) {
        return RunUntilResult.VirtualDurationExceeded(maxVirtualDuration, diagnostics())
    }
    advanceTo(next, maxProcessedEvents - (processedEvents - startCount).toInt())
}
```

Do not add `runUntilIdle` or automatic expiry drainage.

- [x] **Step 8: Run quiescence and complete simulation tests.**

```bash
rtk ./gradlew \
  :transport:simulation:testAndroidHostTest --tests '*SimulationQuiescenceTest' \
  :transport:simulation:allTests \
  --console=plain
```

Expected: same-time consequences settle without polling; future dedup/fragment/route timers remain queued; all prior tests pass on both targets.

- [ ] **Step 9: Check and commit.**

```bash
rtk git diff --check
rtk git add transport/simulation/src
rtk git commit -m "feat: add deterministic network quiescence"
```

## Task 9: Prove the simulation substrate before mesh scenarios

**Files:**
- Modify: `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/SimulationSubstrateTest.kt`
- Modify: `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/SimulationQuiescenceTest.kt`

- [ ] **Step 1: Add exact behavioral tests for the substrate.**

Implement these complete test cases using `testNetwork(backgroundScope)` and recorded delivery projections:

```kotlin
@Test fun payloadArrivesExactlyAtOneHundredMilliseconds()
@Test fun equalDeadlineDeliveriesUseInsertionSequence()
@Test fun explicitDropProducesNoDelivery()
@Test fun explicitDuplicateProducesExactlyTwoDeliveries()
@Test fun explicitDelayLetsALaterWriteArriveFirst()
@Test fun partitionBlocksOnlySelectedDirectionsAndHealingRestoresFutureTraffic()
@Test fun reconnectCreatesNewEndpointLifecycleWithoutReplayingLostTraffic()
@Test fun readinessBackpressureAndFailureReturnTypedLinkResults()
@Test fun mtuOverflowReturnsPayloadTooLargeWithoutDelivery()
@Test fun scheduledQueueOverflowIsTypedAndDoesNotPartiallyCommitAWrite()
@Test fun malformedPayloadStopsAtProtocolAdapterWithoutMutatingMeshState()
```

The exact-time case must assert:

```kotlin
network.injectTransportWrite(connection.aToB.id, payload)
network.advanceBy(99.milliseconds)
assertTrue(network.snapshot().deliveries.isEmpty())
network.advanceBy(1.milliseconds)
assertEquals(MonotonicTime.ZERO.plus(100.milliseconds), network.snapshot().deliveries.single().deliveredAt)
```

The equal-deadline case compares explicit delivery sequence IDs, not map iteration. The queue-overflow case snapshots before/after and proves neither completion nor delivery was partially inserted.

The malformed-payload case records B's `MeshState`, delivers `Bytes.copyOf(byteArrayOf())` through A-to-B, drains current-time consequences, then asserts exact state equality, zero pending admissions, one additional structural-decode rejection, and no publication/relay/write. This test proves failed structural decode never enters `MeshState`.

- [ ] **Step 2: Run tests and observe failures in incomplete fault transitions.**

```bash
rtk ./gradlew :transport:simulation:testAndroidHostTest --tests '*SimulationSubstrateTest' --console=plain
```

Expected: newly added cases fail until all link/fault paths update topology, runtime observations, traces, and queue reservations atomically.

- [ ] **Step 3: Implement only missing substrate behavior.**

Reserve all queue capacity required by one write before mutating ordinals, consuming a one-shot fault, or scheduling any event. Apply timed faults through `SimulationEvent.ApplyLinkFault`; schedule their events during network initialization in `(deadline, plan order)` sequence. A partition changes only its explicit directions and schedules matching `ReadinessChanged` or `Closed` observations. Reconnect increments checked link epoch and emits a new deterministic endpoint lifecycle; it does not reuse a stale callback.

- [ ] **Step 4: Run Android and iOS substrate suites.**

```bash
rtk ./gradlew :transport:simulation:allTests --console=plain
```

Expected: all Tier 1 cases pass identically on Android and iOS.

- [ ] **Step 5: Check and commit.**

```bash
rtk git diff --check
rtk git add transport/simulation/src
rtk git commit -m "test: prove deterministic simulation substrate"
```

## Task 10: Add direct delivery and canonical three-node relay

**Files:**
- Modify: `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/SimulationFixtures.kt`
- Create: `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/DirectAndRelayScenarioTest.kt`
- Create: `transport/simulation/src/androidHostTest/kotlin/com/yet/bitmessage/transport/simulation/CanonicalMeshScenarioTest.kt`

- [ ] **Step 1: Build non-normative packets only through production APIs.**

`SimulationFixtures.messagePacket(ttl, payloadByte, timestamp)` constructs a `DecodedPacket` with a one-byte non-authoritative `RawPacket` sentinel, calls `BitchatCodec.encode`, then decodes the emitted bytes and returns that production-decoded packet. `fragmentPackets` uses `FragmentPayloadCodec.encode`. No common helper contains a canonical expected wire, digest, signing transcript, or relayed output literal.

- [ ] **Step 2: Write common direct and unsigned relay tests.**

```kotlin
class DirectAndRelayScenarioTest {
    @Test
    fun directTransportDeliveryTraversesAdapterRuntimeAdmissionAndPublication() = runTest {
        val network = testNetwork(backgroundScope)
        val (a, b, ab) = network.twoNodes()
        network.injectTransportWrite(ab.aToB.id, SimulationFixtures.messageWire(ttl = 3u))
        val result = network.runUntil(
            predicate = { it.node(b).publications.size == 1 },
            maxProcessedEvents = 1_000,
            maxVirtualDuration = 5.seconds,
        )
        assertIs<RunUntilResult.Reached>(result)
        val node = network.snapshot().node(b)
        assertEquals(1, node.publications.size)
        assertEquals(1, node.state.admittedPackets.size)
        assertTrue(node.state.pendingAdmissions.isEmpty())
    }

    @Test
    fun threeNodeRelayUsesRealMeshTtlIdentityAndWrites() = runTest {
        val network = threeNodeLine(backgroundScope)
        network.injectTransportWrite(network.direction(nodeA, nodeB), SimulationFixtures.messageWire(ttl = 3u))
        val result = network.runUntil(
            predicate = { it.node(nodeC).publications.size == 1 },
            maxProcessedEvents = 5_000,
            maxVirtualDuration = 30.seconds,
        )
        assertIs<RunUntilResult.Reached>(result)
        val snapshot = network.snapshot()
        assertEquals(1, snapshot.node(nodeB).publications.size)
        assertEquals(1, snapshot.node(nodeC).publications.size)
        assertEquals(snapshot.node(nodeB).state.admittedPackets.keys, snapshot.node(nodeC).state.admittedPackets.keys)
        assertEquals(2u.toUByte(), snapshot.deliveries.last { it.targetNode == nodeC }.ttl)
    }
}
```

- [ ] **Step 3: Run and observe the missing integration behavior.**

```bash
rtk ./gradlew :transport:simulation:testAndroidHostTest --tests '*DirectAndRelayScenarioTest' --console=plain
```

Expected: failures identify any incomplete node publication, link mapping, relay timer, codec, or real digest wiring.

- [ ] **Step 4: Complete only the integration seams required by the tests.**

Fix host publication routing, relay timer firing, direction lookup by `(sourceNode, LinkId)`, and typed write completion. Do not implement local send/delivery semantics: the initial A-to-B action remains an external transport injection; B-to-C is a real Phase 4 relay write.

- [ ] **Step 5: Load canonical evidence in Android host tests.**

`CanonicalMeshScenarioTest` loads the manifest exactly:

```kotlin
private val manifest by lazy {
    FixtureManifestParser.parse(resource("BitchatBaseline2026_08/fixtures.json"))
}

private fun fixture(id: String): CompatibilityFixture = manifest.fixtures.single { it.id == id }

private fun resource(path: String): String = checkNotNull(javaClass.classLoader?.getResource(path)) {
    "Missing compatibility resource $path"
}.readText()
```

Add three tests:

1. `realSimulationShaMatchesCanonicalPacketIdentity`: decode `apple-phase4-packet-identity`, compare `PacketIdentity.input`, full SHA-256, and `PacketIdentity.fromSha256` against semantic fields.
2. `canonicalSignedPacketRelaysAcrossThreeNodesWithoutTranscriptMutation`: decode `apple-phase4-signing-relay`, assert the loaded transcript is 256 bytes and hash-locked, use production `RelayEncoding.withTtl(packet, 3u)` to create the A input, mark explicit verification requests valid, run A-B-C until C publishes, assert B relays TTL 2, packet IDs match, signatures match, and `SigningTranscript.build` is unchanged.
3. `canonicalSevenRelaysAsSixButHostileClampRemainsLocalPolicy`: send the loaded TTL 7 fixture and assert 6 at C; separately use a production-mutated TTL 255 packet and assert local 6 without adding or altering compatibility fixtures.

Read all semantic values from `CompatibilityFixture`; do not paste them into the test.

- [ ] **Step 6: Run canonical, common, production compatibility, and mesh gates.**

```bash
rtk ./gradlew \
  :transport:simulation:testAndroidHostTest --tests '*CanonicalMeshScenarioTest' \
  :transport:simulation:allTests \
  :protocol:bitchat:productionCompatibilityCheck \
  :engine:mesh:meshEngineCheck \
  --console=plain
```

Expected: canonical packet identity/transcript/TTL evidence drives the Android integration tests; common direct/relay behavior passes on both targets; existing gates remain positive; `compatibility/` is unchanged.

- [ ] **Step 7: Prove no shadow corpus and commit.**

```bash
rtk git diff --exit-code 43b10ac7cb351f65905bf99586d9b96ffadb1a2d -- compatibility
rtk rg -n "packetIdentitySha256|signingTranscriptHex|relayedWireBytesHex" transport/simulation/src/commonMain transport/simulation/src/commonTest
rtk git diff --check
rtk git add transport/simulation/src
rtk git commit -m "test: prove direct and canonical mesh relay"
```

Expected: compatibility diff and literal search are empty.

## Task 11: Prove duplicate, TTL, and invalid-auth admission invariants

**Files:**
- Modify: `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/SimulationFixtures.kt`
- Modify: `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/DirectAndRelayScenarioTest.kt`
- Create: `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/AdmissionAttackScenarioTest.kt`

- [ ] **Step 1: Add duplicate-path and TTL tests before changing simulator code.**

```kotlin
@Test
fun sameAuthenticatedPacketAcrossSameAndDifferentIngressLinksPublishesOnce() = runTest {
    val network = redundantIngressNetwork(backgroundScope)
    val wire = SimulationFixtures.messageWire(ttl = 3u)
    network.injectTransportWrite(directionAToB, wire)
    network.injectTransportWrite(directionAToB, wire)
    network.injectTransportWrite(directionCToB, wire)
    network.runUntil(
        predicate = { snapshot -> snapshot.deliveries.count { it.targetNode == nodeB } == 3 },
        maxProcessedEvents = 5_000,
        maxVirtualDuration = 5.seconds,
    )
    val b = network.snapshot().node(nodeB)
    assertEquals(1, b.publications.size)
    assertEquals(1, b.state.admittedPackets.size)
    assertTrue(b.state.pendingAdmissions.isEmpty())
    assertEquals(1, network.snapshot().trace.count { it.nodeId == nodeB && it.category == TraceCategory.ADMITTED })
}

@Test
fun ttlZeroAndOnePublishLocallyWithoutRelayAndHostileValueUsesLocalCap() = runTest {
    listOf(0u, 1u).forEach { ttl ->
        val network = threeNodeLine(backgroundScope)
        network.injectTransportWrite(network.direction(nodeA, nodeB), SimulationFixtures.messageWire(ttl.toUByte()))
        network.advanceBy(1.seconds)
        assertEquals(1, network.snapshot().node(nodeB).publications.size)
        assertTrue(network.snapshot().node(nodeC).publications.isEmpty())
    }

    val hostile = threeNodeLine(backgroundScope)
    hostile.injectTransportWrite(hostile.direction(nodeA, nodeB), SimulationFixtures.messageWire(255u))
    hostile.runUntil({ it.node(nodeC).publications.size == 1 }, 5_000, 30.seconds)
    assertEquals(6u.toUByte(), hostile.snapshot().deliveries.last { it.targetNode == nodeC }.ttl)
}
```

- [ ] **Step 2: Add a generated non-normative signed test helper.**

Because the production profile deliberately refuses to emit signatures before a production signer exists, `SimulationFixtures.signedMessageWire` is a test-input builder, not a compatibility authority. It starts from `BitchatCodec.encode` output, derives the v2 flag position from named fixed header field sizes, sets `PacketFlags.SIGNATURE_BIT`, appends an exact 64-byte test signature, and immediately requires `BitchatCodec.decode` plus `SigningTranscript.build` to succeed. No expected wire or transcript literal is stored or asserted. Canonical signed behavior remains in Task 10's Android fixture test.

- [ ] **Step 3: Write the multi-node invalid-auth poisoning scenario.**

```kotlin
class AdmissionAttackScenarioTest {
    @Test
    fun invalidAuthenticationFloodCannotPoisonAdmittedDedup() = runTest {
        val limits = MeshLimits(maxPendingAdmissions = 4, maxPendingAdmissionsPerLink = 2, maxAdmittedPacketIds = 2)
        val verification = VerificationPlan(
            (1L..32L).associateWith { PlannedOutcome.Immediate(MeshResult.Success(false)) } +
                (33L to PlannedOutcome.Immediate(MeshResult.Success(true))),
        )
        val network = testNetwork(backgroundScope)
        val (_, b, ab) = network.twoNodes(nodeBLimits = limits, nodeBVerification = verification)

        repeat(32) { index ->
            network.injectTransportWrite(ab.aToB.id, SimulationFixtures.signedMessageWire(payloadByte = index.toByte()))
        }
        network.injectTransportWrite(ab.aToB.id, SimulationFixtures.signedMessageWire(payloadByte = 0x7f))
        val result = network.runUntil(
            predicate = { it.node(b).publications.size == 1 },
            maxProcessedEvents = 20_000,
            maxVirtualDuration = 30.seconds,
        )

        assertIs<RunUntilResult.Reached>(result)
        val state = network.snapshot().node(b).state
        assertTrue(state.pendingAdmissions.size <= limits.maxPendingAdmissions)
        assertEquals(1, state.admittedPackets.size)
        assertEquals(1, network.snapshot().node(b).publications.size)
        assertTrue(network.snapshot().deliveries.none { it.sourceNode == b && it.packetClassification == "invalid-auth" })
    }
}
```

Use wire payload/timestamp variation so packet identity inputs are unique. Verification outcomes are explicit by request ordinal; no randomness chooses validity.

- [ ] **Step 4: Run and observe failures before any necessary integration fix.**

```bash
rtk ./gradlew \
  :transport:simulation:testAndroidHostTest --tests '*DirectAndRelayScenarioTest' \
  :transport:simulation:testAndroidHostTest --tests '*AdmissionAttackScenarioTest' \
  --console=plain
```

Expected: tests either pass entirely through existing Phase 4 policy or expose only simulator ordering/diagnostic bugs. Do not change mesh admission/dedup/TTL policy to satisfy them.

- [ ] **Step 5: Fix simulator-only defects and run all targets.**

Any correction is limited to preserving per-write ordinals, stable direction mapping, explicit verification outcomes, or projections. If a production invariant fails, first reproduce it in `:engine:mesh` and stop for review rather than duplicating a workaround in simulation.

```bash
rtk ./gradlew :transport:simulation:allTests :engine:mesh:meshEngineCheck :engine:mesh:allTests --console=plain
```

Expected: duplicate, TTL, and poisoning scenarios pass on Android and iOS; mesh gate remains green.

- [ ] **Step 6: Check and commit.**

```bash
rtk git diff --check
rtk git add transport/simulation/src
rtk git commit -m "test: prove mesh admission invariants across nodes"
```

## Task 12: Prove partitions, reconnect generations, and backpressure

**Files:**
- Create: `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/PartitionAndBackpressureScenarioTest.kt`
- Modify: `transport/simulation/src/commonMain/kotlin/com/yet/bitmessage/transport/simulation/SimulatedNetwork.kt`

- [ ] **Step 1: Write partition and no-fake-retry tests.**

```kotlin
@Test
fun partitionPreventsImpossibleDeliveryAndHealingDoesNotReplayLostTraffic() = runTest {
    val network = threeNodeLine(backgroundScope, partitionBCAt = 100.milliseconds, healBCAt = 500.milliseconds)
    network.advanceTo(MonotonicTime.ZERO.plus(100.milliseconds))
    network.injectTransportWrite(network.direction(nodeA, nodeB), SimulationFixtures.messageWire(payloadByte = 1))
    network.advanceTo(MonotonicTime.ZERO.plus(500.milliseconds))
    assertTrue(network.snapshot().node(nodeC).publications.isEmpty())

    network.injectTransportWrite(network.direction(nodeA, nodeB), SimulationFixtures.messageWire(payloadByte = 2))
    network.runUntil({ it.node(nodeC).publications.size == 1 }, 5_000, 30.seconds)
    assertEquals(1, network.snapshot().node(nodeC).publications.size)
    assertEquals(
        SimulationFixtures.packetId(SimulationFixtures.messageWire(payloadByte = 2)),
        network.snapshot().node(nodeC).publications.single().packetId,
    )
}
```

- [ ] **Step 2: Write stale old-generation completion test.**

Arrange a real B relay write with link latency long enough that its correlated `LinkCompleted` remains scheduled. Stop B before its deadline, restart B to get `oldGeneration.next()`, reopen its links, then advance to the old result:

```kotlin
val beforeOldCompletion = network.snapshot().node(nodeB).state
network.advanceTo(oldCompletionDeadline)
assertEquals(beforeOldCompletion, network.snapshot().node(nodeB).state)
assertTrue(network.snapshot().trace.any {
    it.nodeId == nodeB && it.generation == oldGeneration && it.outcome == "stale-result"
})
```

The event carries the real `CorrelationId` and `Generation` captured from the original `LinkCommand.Write`. Do not invent a parallel mesh generation.

- [ ] **Step 3: Write backpressure/failure tests.**

Cover `ReadinessChanged(writeReady = false)`, explicit `TransmissionFault.CompleteWith(BACKPRESSURED)`, `FAILED`, and queue capacity overflow. Assert the real runtime receives `MeshEvent.LinkCompleted` with the typed `LinkResult`, resolves `pendingLinkWrites` once, emits no delivery, creates no hidden simulator queue, and does not retry.

- [ ] **Step 4: Run and prove incomplete lifecycle paths fail.**

```bash
rtk ./gradlew :transport:simulation:testAndroidHostTest --tests '*PartitionAndBackpressureScenarioTest' --console=plain
```

Expected: new tests fail until stop/restart event cancellation, new endpoint observation, and stale completion delivery are correctly distinguished.

- [ ] **Step 5: Implement exact lifecycle handling.**

`SimulatedNetwork.stopNode` calls the real runtime stop and cancels only runtime-owned timers through its timer driver. It does not erase already scheduled transport completions, because those are required to test stale generation. `restartNode` starts the same runtime, obtains the next real generation, and emits fresh link observations for currently open endpoint connections. Old delivery/completion events retain original generation/correlation and are never rewritten.

Partitions and readiness affect future writes only. No lost payload is retained for later healing.

- [ ] **Step 6: Run complete simulation and mesh suites.**

```bash
rtk ./gradlew \
  :transport:simulation:allTests \
  :transport:api:allTests \
  :engine:mesh:meshEngineCheck \
  :engine:mesh:allTests \
  --console=plain
```

Expected: lifecycle/pressure scenarios pass on Android and iOS; transport and mesh regressions remain green.

- [ ] **Step 7: Check and commit.**

```bash
rtk git diff --check
rtk git add transport/simulation/src
rtk git commit -m "test: prove partition and stale generation safety"
```

## Task 13: Prove fragment attacks and relay-loop convergence

**Files:**
- Modify: `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/SimulationFixtures.kt`
- Create: `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/FragmentScenarioTest.kt`
- Create: `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/RelayLoopScenarioTest.kt`
- Modify: `transport/simulation/src/androidHostTest/kotlin/com/yet/bitmessage/transport/simulation/CanonicalMeshScenarioTest.kt`

- [ ] **Step 1: Add a non-normative decode-only outer-packet builder.**

The production profile intentionally decodes but does not emit outer fragment packets. `SimulationFixtures.decodeOnlyOuterPacket(type, payload, ttl, timestamp)` is therefore test input infrastructure only. It derives the v2 header from named production value sizes, writes the supplied known packet type and payload length, and immediately requires `BitchatCodec.decode` to reproduce the requested typed packet. It is used only to wrap fragment payloads created by `FragmentPayloadCodec.encode`; no output bytes are an asserted compatibility answer. Canonical fragment payload values remain loaded from the Phase 1 corpus in Android host tests.

- [ ] **Step 2: Write positive fragment network behavior.**

```kotlin
@Test
fun reorderedDuplicatedAndDelayedFragmentsReenterTheFullAdmissionPathOnce() = runTest {
    val network = testNetwork(backgroundScope, faultPlan = reorderDuplicateAndDelayFragmentPlan())
    val (_, b, ab) = network.twoNodes()
    val fragments = SimulationFixtures.fragmentOuterWires(SimulationFixtures.messageWire(ttl = 0u), fragmentBytes = 13)

    fragments.reversed().forEach { network.injectTransportWrite(ab.aToB.id, it) }
    network.injectTransportWrite(ab.aToB.id, fragments.first())
    network.runUntil({ it.node(b).publications.size == 1 }, 10_000, 30.seconds)

    val node = network.snapshot().node(b)
    assertEquals(1, node.publications.size)
    assertTrue(node.state.fragmentStreams.isEmpty())
    assertEquals(0, node.state.aggregateFragmentBytes)
    assertEquals(1, node.state.admittedPackets.size)
    assertTrue(network.snapshot().trace.any { it.nodeId == b && it.category == TraceCategory.REINJECTED })
}
```

- [ ] **Step 3: Write conflict and quota attacks.**

One test delivers identical `(streamId, index)` with different data and proves the real stream is destroyed/rejected before later bytes can be trusted. One configures `maxFragmentStreams = 3`, `maxFragmentStreamsPerSource = 2`, a small aggregate byte cap, then delivers at least 12 unique streams. After every current-time quiescent boundary assert:

```kotlin
state.fragmentStreams.size <= limits.maxFragmentStreams
state.aggregateFragmentBytes <= limits.maxAggregateFragmentBytes
state.fragmentStreams.values.groupingBy { it.ingressLink }.eachCount().values.all {
    it <= limits.maxFragmentStreamsPerSource
}
```

Finally send one legitimate fragmented packet and prove the runtime remains usable.

- [ ] **Step 4: Add canonical fragment payload coverage.**

In `CanonicalMeshScenarioTest`, load `apple-phase4-fragment-reassembly`; obtain fragment zero from `wireBytesHex`, fragment one and expected inner wire from semantic fields; decode and re-encode both with production `FragmentPayloadCodec`; wrap them only with the test outer builder; deliver out of order; assert one publication whose admitted inner packet uses the fixture's reassembled bytes. Do not copy any fixture field into source.

- [ ] **Step 5: Write triangle convergence without emptying maintenance timers.**

```kotlin
@Test
fun triangleRelayLoopConvergesWhilePassiveExpiryTimersRemainQueued() = runTest {
    val network = triangleNetwork(backgroundScope)
    val packet = SimulationFixtures.messageWire(ttl = 7u)
    val packetId = SimulationFixtures.packetId(packet)
    network.injectTransportWrite(network.direction(nodeA, nodeB), packet)

    val result = network.runUntil(
        predicate = { snapshot ->
            snapshot.nodes.all { it.state.scheduledRelays.values.none { relay -> relay.packetId == packetId } } &&
                snapshot.pendingEvents.none { it.packetId == packetId && it.category.isPacketNetworkWork }
        },
        maxProcessedEvents = 20_000,
        maxVirtualDuration = 30.seconds,
    )
    assertIs<RunUntilResult.Reached>(result)
    val snapshot = network.snapshot()
    assertTrue(snapshot.nodes.all { node -> node.publications.count { it.packetId == packetId } <= 1 })
    assertTrue(snapshot.pendingEvents.any { it.category == ScheduledCategory.RUNTIME_TIMER })
    assertTrue(snapshot.pendingEvents.any { it.deadline > snapshot.now })
    assertTrue(snapshot.processedEvents < 20_000)
}
```

The predicate uses packet-relevant network projections and real `scheduledRelays`; passive dedup/topology/fragment maintenance does not block convergence.

- [ ] **Step 6: Run new tests and observe missing wrapper/projection behavior.**

```bash
rtk ./gradlew \
  :transport:simulation:testAndroidHostTest --tests '*FragmentScenarioTest' \
  :transport:simulation:testAndroidHostTest --tests '*RelayLoopScenarioTest' \
  :transport:simulation:testAndroidHostTest --tests '*CanonicalMeshScenarioTest' \
  --console=plain
```

Expected: tests fail only for missing test input construction or simulator event projections. The simulator must not directly call fragment completion or duplicate quota policy.

- [ ] **Step 7: Complete simulator projections and run all targets.**

Add redacted packet classification/ID metadata when scheduling delivery so convergence can identify packet-relevant work without payload inspection. Keep the raw scheduled bytes private. Run:

```bash
rtk ./gradlew \
  :transport:simulation:allTests \
  :protocol:bitchat:productionCompatibilityCheck \
  :engine:mesh:meshEngineCheck \
  :engine:mesh:allTests \
  --console=plain
```

Expected: generated positive/hostile fragment and loop tests pass on Android/iOS; canonical Android fragment scenario and production gates pass.

- [ ] **Step 8: Check and commit.**

```bash
rtk git diff --check
rtk git add transport/simulation/src
rtk git commit -m "test: prove fragment and relay loop convergence"
```

## Task 14: Add deterministic replay and bounded generated adversarial coverage

**Files:**
- Create: `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/DeterministicReplayTest.kt`
- Create: `transport/simulation/src/commonTest/kotlin/com/yet/bitmessage/transport/simulation/GeneratedAdversarialScenarioTest.kt`
- Modify: `transport/simulation/src/commonMain/kotlin/com/yet/bitmessage/transport/simulation/SimulationSnapshot.kt`
- Modify: `transport/simulation/src/commonMain/kotlin/com/yet/bitmessage/transport/simulation/SimulationTrace.kt`
- Modify: `transport/simulation/src/commonMain/kotlin/com/yet/bitmessage/transport/simulation/SimulationEvent.kt`
- Modify: `transport/simulation/src/commonMain/kotlin/com/yet/bitmessage/transport/simulation/SimulatedNetwork.kt`

- [ ] **Step 1: Write exact replay equality tests.**

```kotlin
class DeterministicReplayTest {
    @Test
    fun identicalInputsProduceIdenticalSnapshotsTraceCountAndTime() = runTest {
        val scenario = GeneratedScenarioCompiler.compile(seed = 0x5eed, maximumActions = 64)
        val first = executeScenario(scenario, backgroundScope)
        val second = executeScenario(scenario, backgroundScope)
        assertEquals(first.finalSnapshot.nodeStateProjections(), second.finalSnapshot.nodeStateProjections())
        assertEquals(first.finalSnapshot.publications, second.finalSnapshot.publications)
        assertEquals(first.finalSnapshot.deliveries, second.finalSnapshot.deliveries)
        assertEquals(first.finalSnapshot.trace, second.finalSnapshot.trace)
        assertEquals(first.finalSnapshot.processedEvents, second.finalSnapshot.processedEvents)
        assertEquals(first.finalSnapshot.now, second.finalSnapshot.now)
        assertEquals(first.finalSnapshot.pendingEvents, second.finalSnapshot.pendingEvents)
    }

    @Test
    fun failureDiagnosticsContainExactReplayInputsAndOnlyLastSixtyFourTraceRecords() {
        val scenario = GeneratedScenarioCompiler.compile(seed = 17, maximumActions = 128)
        val failure = forceEventLimitFailure(scenario, maximumEvents = 4)
        assertEquals(scenario.name, failure.scenarioName)
        assertEquals(17, failure.seed)
        assertEquals(scenario.topology, failure.topology)
        assertEquals(scenario.actions, failure.actions)
        assertEquals(scenario.faultPlan, failure.faultPlan)
        assertEquals(scenario.protocolSeeds, failure.protocolSeeds)
        assertTrue(failure.lastTrace.size <= 64)
        assertTrue(failure.render().contains("seed=17"))
        assertFalse(failure.render().contains(SimulationFixtures.privatePayloadText))
    }
}
```

- [ ] **Step 2: Write the 64-seed adversarial test.**

```kotlin
class GeneratedAdversarialScenarioTest {
    @Test
    fun sixtyFourFixedSeedsPreserveBoundsAndSecurityInvariants() = runTest {
        PHASE_FIVE_SEEDS.forEach { seed ->
            val scenario = GeneratedScenarioCompiler.compile(seed, maximumActions = 128)
            val result = executeScenario(scenario, backgroundScope) { snapshot ->
                snapshot.nodes.forEach { node ->
                    assertTrue(node.state.pendingAdmissions.size <= scenario.meshLimits.maxPendingAdmissions, "seed=$seed")
                    assertTrue(node.state.admittedPackets.size <= scenario.meshLimits.maxAdmittedPacketIds, "seed=$seed")
                    assertTrue(node.state.fragmentStreams.size <= scenario.meshLimits.maxFragmentStreams, "seed=$seed")
                    assertTrue(node.state.aggregateFragmentBytes <= scenario.meshLimits.maxAggregateFragmentBytes, "seed=$seed")
                    assertTrue(node.state.scheduledRelays.size <= scenario.meshLimits.maxScheduledRelays, "seed=$seed")
                    assertEquals(node.publications.map { it.packetId }.distinct(), node.publications.map { it.packetId }, "seed=$seed")
                }
                assertTrue(snapshot.pendingEvents.size <= scenario.simulationLimits.maxScheduledEvents, "seed=$seed")
                assertTrue(snapshot.trace.none {
                    it.category == TraceCategory.WRITE_COMPLETED && it.outcome == TraceOutcome.WRITTEN_WHILE_CLOSED
                }, "seed=$seed")
            }
            assertTrue(result.finalSnapshot.processedEvents <= scenario.simulationLimits.maxProcessedEvents, "seed=$seed")
        }
    }

    private companion object {
        val PHASE_FIVE_SEEDS: IntRange = 0 until 64
    }
}
```

Add explicit assertions from verification trace/state that invalid-auth packet IDs never occur in `admittedPackets`, stale generation results do not alter current state, and every relayed TTL is `0..6` with no underflow.

- [ ] **Step 3: Run and prove replay/generator types are absent.**

```bash
rtk ./gradlew \
  :transport:simulation:testAndroidHostTest --tests '*DeterministicReplayTest' \
  :transport:simulation:testAndroidHostTest --tests '*GeneratedAdversarialScenarioTest' \
  --console=plain
```

Expected: compilation fails for the compiler/executor/diagnostics, then behavioral failures identify any unstable projection or iteration.

- [ ] **Step 4: Implement compile-before-execute generation.**

`GeneratedScenarioCompiler.compile` creates `Random(seed)` locally and returns an immutable `GeneratedScenario` containing:

```kotlin
data class GeneratedScenario(
    val name: String,
    val seed: Int,
    val topology: GeneratedTopology,
    val actions: List<ScenarioAction>,
    val faultPlan: FaultPlan,
    val protocolSeeds: Map<SimulatedNodeId, Int>,
    val meshLimits: MeshLimits,
    val simulationLimits: SimulationLimits,
)
```

Generation uses at most four nodes, six connections, and 128 actions. It chooses all packets, delays, fault matches, partitions, and protocol seeds before execution. `executeScenario` receives no RNG. All action and map projections are sorted or preserve explicit generation order.

Define a closed `ScenarioAction` and add a matching scheduled `SimulationEvent` variant. `executeScenario` inserts every future action into the one global virtual-event queue in explicit plan order before execution; it must not maintain a second timed action list or manually jump the clock to dispatch those actions. The network's dispatch path handles each action at its queued deadline. This closes the Design 2 scheduled-scenario-action family without a speculative Task 3 variant.

Do not implement shrinking. A failing test throws one bounded `SimulationFailure` containing scenario name, seed, explicit topology/actions/fault plan, protocol seeds, time, processed-event count, state sizes, and the last 64 redacted trace records.

- [ ] **Step 5: Stabilize trace and snapshot equality.**

Remove class-name/reflection rendering and unordered collection iteration. Render closed enum categories and stable IDs. Snapshot only immutable mesh values and redacted scheduled metadata. Use a bounded delivery history and publication history; add them to `SimulationLimits` if not already present.

- [ ] **Step 6: Run generated tests on both targets twice.**

```bash
rtk ./gradlew :transport:simulation:allTests --rerun-tasks --console=plain
rtk ./gradlew :transport:simulation:allTests --rerun-tasks --console=plain
```

Expected: all 64 seeds execute on Android and iOS in both runs with identical semantic results and within bounds.

- [ ] **Step 7: Check forbidden nondeterminism and commit.**

```bash
rtk rg -n "Random\.Default|System\.currentTimeMillis|Clock\.System|Thread\.sleep|delay\(" transport/simulation/src/commonMain
rtk rg -n "shrinker|shrink\(" transport/simulation/src
rtk git diff --check
rtk git add transport/simulation/src
rtk git commit -m "test: add replayable adversarial mesh scenarios"
```

Expected: both searches return no matches except explanatory test names that do not implement forbidden behavior.

## Task 15: Update architecture records and run the complete Phase 1-5 gate

**Files:**
- Modify: `AGENTS.md`
- Modify: `docs/architecture/BITMESSAGE_ARCHITECTURE.md`
- Modify: `docs/architecture/STATE_MACHINE_DESIGN.md`
- Modify: `docs/architecture/HISTORICAL_BITMESSAGE_SALVAGE.md`
- Modify: `docs/IMPLEMENTATION_PLAN.md`

- [ ] **Step 1: Update only materially changed documentation.**

Record this exact dependency direction:

```text
transport:simulation -> core:foundation, core:model, protocol:bitchat, transport:api, engine:mesh
transport:simulation tests only -> core:testing
applications/sharedLogic -X-> transport:simulation
```

Document:

- one real runtime/engine per node;
- one scheduled virtual-time queue plus preserved bounded runtime actor queues;
- acknowledged commitment ending after effect submission, not asynchronous completion;
- causal effect fence and current-instant quiescence;
- `advanceTo`/`advanceBy`/predicate-bounded `runUntil`;
- passive future maintenance timers not preventing convergence;
- independent plan RNG/protocol entropy;
- real default SHA-256 and explicit digest overrides;
- compatibility corpus authority and local `255 -> 6` policy distinction;
- no persistence/Delivery/Sync/Noise/Bluetooth/Phase 6 work.

In the historical salvage table record only:

- scheduled duplicate relay cancellation: `SALVAGE_TEST`, exercised through multi-node virtual time;
- fragment reorder/conflict/quota scenarios: `SALVAGE_TEST` while old manager code remains rejected;
- reconnect/late-result scenario: `SALVAGE_TEST` while old runtime remains `REFERENCE_ONLY`;
- historical directed spool semantics: deferred to Phase 9; Phase 5 proves typed pressure and no fake durable queue.

- [ ] **Step 2: Run documentation and dependency checks.**

```bash
rtk ./gradlew :transport:simulation:dependencies --configuration androidHostTestRuntimeClasspath --console=plain
rtk ./gradlew :androidApp:dependencies --configuration debugRuntimeClasspath --console=plain
rtk rg -n "transport[.:]simulation" androidApp sharedLogic sharedUI protocol engine transport/api core --glob '*.kts' --glob '*.kt'
rtk git diff --exit-code 43b10ac7cb351f65905bf99586d9b96ffadb1a2d -- compatibility
rtk git diff --check
```

Expected: simulator depends inward as approved; no application dependency points to it; compatibility is unchanged; whitespace check is clean.

- [ ] **Step 3: Run the dedicated Phase 5 gate from fresh results.**

```bash
rtk ./gradlew \
  :transport:simulation:simulationCheck \
  :transport:simulation:allTests \
  --rerun-tasks --console=plain
```

Expected: `simulationCheck` reports a positive `SimulationCoverageTest` count; no selected Phase 5 behavioral suite is `NO-SOURCE`.

- [ ] **Step 4: Run the complete Phase 1-5 regression gate.**

```bash
rtk ./gradlew \
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
  :transport:simulation:allTests \
  --rerun-tasks --console=plain
```

Expected: every Phase 1-5 test and all three non-zero gates pass.

- [ ] **Step 5: Build Android and link the iOS simulator framework.**

```bash
rtk ./gradlew \
  :androidApp:assembleDebug \
  :sharedLogic:linkDebugFrameworkIosSimulatorArm64 \
  --rerun-tasks --console=plain
```

Expected: Android debug assembly and iOS simulator framework link pass; only previously documented warnings may remain.

- [ ] **Step 6: Derive exact fresh test counts.**

Run the repository's XML-count command over these fresh result roots:

```bash
rtk awk '/<testsuite / { match($0, /tests="[0-9]+"/); count = substr($0, RSTART + 7, RLENGTH - 8); split(FILENAME, part, "/"); totals[part[1] "/" part[2] " " part[5]] += count } END { for (key in totals) print key, totals[key] }' \
  core/foundation/build/test-results/*/TEST-*.xml \
  core/model/build/test-results/*/TEST-*.xml \
  core/testing/build/test-results/*/TEST-*.xml \
  protocol/bitchat/build/test-results/*/TEST-*.xml \
  transport/api/build/test-results/*/TEST-*.xml \
  engine/mesh/build/test-results/*/TEST-*.xml \
  transport/simulation/build/test-results/*/TEST-*.xml
```

Expected: exact non-zero Android/iOS counts for every selected module; record generated scenario count `64` and seed range `0..63` separately.

- [ ] **Step 7: Run final source/status checks.**

```bash
rtk rg -n "Random\.Default|System\.currentTimeMillis|Clock\.System|Thread\.sleep|delay\(" transport/simulation/src/commonMain
rtk rg -n "Noise|BlueFalcon|CoreBluetooth|DeliveryEngine|SyncEngine|shrinker|shrink\(" transport/simulation/src
rtk git diff --check
rtk git status --short
```

Expected: no forbidden implementation, no whitespace errors, and only the intended documentation changes are uncommitted.

- [ ] **Step 8: Commit documentation and final Phase 5 acceptance state.**

```bash
rtk git add AGENTS.md docs/architecture/BITMESSAGE_ARCHITECTURE.md docs/architecture/STATE_MACHINE_DESIGN.md docs/architecture/HISTORICAL_BITMESSAGE_SALVAGE.md docs/IMPLEMENTATION_PLAN.md
rtk git commit -m "docs: record Phase 5 deterministic simulation"
```

- [ ] **Step 9: Verify final commit and clean tree.**

```bash
rtk git log -1 --format='%H %s'
rtk git status --short --branch
rtk git diff --check HEAD^
```

Expected: clean `codex/phase-5-deterministic-mesh-simulator` tree; final commit is the Phase 5 documentation acceptance commit; Phase 6 is unstarted.

---

## Final acceptance report checklist

Return all 43 requested items:

1. branch/base/commits;
2. pre-implementation Phase 1-4 baseline;
3. modules/files changed;
4. actual dependency graph;
5. node architecture;
6. virtual-time design;
7. global event ordering;
8. current-instant quiescence algorithm;
9. limits and queue bounds;
10. directed-link model;
11. explicit `FaultPlan`;
12. simulation RNG;
13. per-node protocol entropy;
14. independence proof;
15. effect execution;
16. exact Phase 4 API refinements;
17. direct delivery;
18. canonical three-node relay;
19. duplicates;
20. TTL 0/1/7/255 and evidence/policy distinction;
21. partition;
22. reconnect/stale generation;
23. backpressure;
24. positive fragment/reassembly;
25. fragment conflict/quota;
26. invalid-auth poisoning;
27. relay-loop/current-time convergence;
28. replay proof;
29. generated count and seeds;
30. historical scenarios salvaged;
31. exact module/target counts;
32. Phase 1 gate;
33. Phase 2 gates;
34. Phase 3 `productionCompatibilityCheck`;
35. Phase 4 `meshEngineCheck`;
36. Android build;
37. iOS framework link;
38. exact commands;
39. remaining `NO-SOURCE` tasks;
40. documentation;
41. Git status;
42. final Phase 5 commit;
43. explicit confirmation that Phase 6 was not started.
