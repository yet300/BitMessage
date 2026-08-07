# Phase 2 Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a minimal deterministic KMP foundation for later protocol and engine work while preserving the Phase 1 compatibility baseline.

**Architecture:** `:core:foundation` owns byte/time/scheduler/entropy/reducer primitives; `:core:model` owns only the currently stable `LinkId` and `PeerId`; `:core:testing` supplies deterministic fakes. Production modules do not depend on testing, and no Phase 3 protocol or Phase 7 identity semantics are added.

**Tech Stack:** Kotlin Multiplatform 2.4, existing local KMP convention, `kotlinx-datetime`, `kotlin.test`.

---

### Task 1: Register the Phase 2 module boundaries

**Files:**
- Modify: `settings.gradle.kts`
- Create: `core/foundation/build.gradle.kts`
- Create: `core/model/build.gradle.kts`
- Modify: `core/testing/build.gradle.kts`
- Modify: `AGENTS.md`

- [ ] Add `:core:foundation` and `:core:model` beside the existing `:core:common` and `:core:testing` projects.
- [ ] Apply only `libs.plugins.local.kotlin.multiplatform` in each new module.
- [ ] Give `:core:model` a `commonMain` dependency on `projects.core.foundation`, and give `:core:testing` a `commonMain` dependency on `projects.core.foundation`.

```kotlin
plugins { alias(libs.plugins.local.kotlin.multiplatform) }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.foundation)
        }
    }
}
```
- [ ] Run `rtk ./gradlew projects` and verify the three core modules appear without a circular dependency.
- [ ] Update the repository map and dependency graph in `AGENTS.md`.

### Task 2: Add bounded bytes and stable semantic IDs by TDD

**Files:**
- Create: `core/foundation/src/commonMain/kotlin/com/yet/bitmessage/foundation/Bytes.kt`
- Create: `core/foundation/src/commonTest/kotlin/com/yet/bitmessage/foundation/BytesTest.kt`
- Create: `core/foundation/src/commonMain/kotlin/com/yet/bitmessage/foundation/RuntimeIdentifiers.kt`
- Create: `core/model/src/commonMain/kotlin/com/yet/bitmessage/model/TransportIdentifiers.kt`
- Create: `core/model/src/commonTest/kotlin/com/yet/bitmessage/model/TransportIdentifiersTest.kt`

- [ ] Write a failing `BytesTest` proving copied input/output ownership, unsigned access, content equality/hash behavior, and explicit exact-size failure for short and long input.

```kotlin
@Test
fun exactSizeRejectsInsteadOfTruncatingOrPadding() {
    assertFailsWith<IllegalArgumentException> { Bytes.requireExactSize(byteArrayOf(1), 2) }
    assertFailsWith<IllegalArgumentException> { Bytes.requireExactSize(byteArrayOf(1, 2, 3), 2) }
}
```
- [ ] Run `rtk ./gradlew :core:foundation:allTests`; confirm compilation fails because `Bytes` is absent.
- [ ] Implement `Bytes` with private `ByteArray` storage, `copyOf`, `requireExactSize`, `copyToByteArray`, `get(index): UByte`, content equality/hashCode, and size-only `toString`.

```kotlin
class Bytes private constructor(private val storage: ByteArray) {
    val size: Int get() = storage.size
    operator fun get(index: Int): UByte = storage[index].toUByte()
    fun copyToByteArray(): ByteArray = storage.copyOf()

    companion object {
        fun copyOf(bytes: ByteArray): Bytes = Bytes(bytes.copyOf())
        fun requireExactSize(bytes: ByteArray, expectedSize: Int): Bytes {
            require(expectedSize >= 0 && bytes.size == expectedSize)
            return Bytes(bytes.copyOf())
        }
    }
}
```
- [ ] Run `rtk ./gradlew :core:foundation:allTests`; confirm the byte tests pass.
- [ ] Write failing model tests proving `LinkId` and `PeerId` have separate factory APIs, reject blank/empty input, preserve bytes without normalization, and cannot be exchanged at Kotlin compile time.
- [ ] Implement `@JvmInline` `LinkId` and `PeerId`; keep `PeerId` opaque and unconstrained by any BitChat byte length.
- [ ] Add `TimerId`, `CorrelationId`, and `Generation` to foundation; reject blank tokens and negative generation values. Do not create `IdentityId`, `SessionGeneration`, message, attempt, or operation IDs.

```kotlin
@JvmInline value class LinkId private constructor(val value: String) {
    companion object { fun of(value: String): LinkId = LinkId(value.also { require(it.isNotBlank()) }) }
}
@JvmInline value class PeerId private constructor(val value: Bytes) {
    companion object { fun of(value: Bytes): PeerId = PeerId(value.also { require(it.size > 0) }) }
}
@JvmInline value class Generation(val value: Long) {
    init { require(value >= 0) }
    fun next(): Generation = Generation(value + 1)
}
```
- [ ] Run `rtk ./gradlew :core:model:allTests :core:foundation:allTests`.

### Task 3: Add deterministic time, scheduling, and entropy contracts by TDD

**Files:**
- Create: `core/foundation/src/commonMain/kotlin/com/yet/bitmessage/foundation/Time.kt`
- Create: `core/foundation/src/commonMain/kotlin/com/yet/bitmessage/foundation/Scheduling.kt`
- Create: `core/foundation/src/commonMain/kotlin/com/yet/bitmessage/foundation/Entropy.kt`
- Create: `core/testing/src/commonMain/kotlin/com/yet/bitmessage/testing/runtime/VirtualTime.kt`
- Create: `core/testing/src/commonMain/kotlin/com/yet/bitmessage/testing/runtime/VirtualScheduler.kt`
- Create: `core/testing/src/commonMain/kotlin/com/yet/bitmessage/testing/runtime/SeededEntropy.kt`
- Create: `core/testing/src/commonTest/kotlin/com/yet/bitmessage/testing/runtime/VirtualRuntimeTest.kt`

- [ ] Write failing virtual-runtime tests for explicit virtual-time advancement, equal-deadline sequence order, cancellation, replacement, stale generation distinction, and same-seed entropy replay.

```kotlin
@Test
fun equalDeadlinesFireInInsertionOrder() {
    scheduler.schedule(ScheduleTimer(timerA, 1.seconds, Generation(0)))
    scheduler.schedule(ScheduleTimer(timerB, 1.seconds, Generation(0)))
    assertEquals(listOf(TimerFired(timerA, Generation(0)), TimerFired(timerB, Generation(0))), scheduler.advanceBy(1.seconds))
}
```
- [ ] Run `rtk ./gradlew :core:testing:allTests`; confirm the test compilation fails before virtual runtime types exist.
- [ ] Implement `WallClock`, `MonotonicClock`, `MonotonicTime`, `ScheduleTimer`, `CancelTimer`, `TimerFired`, `EntropyRequest`, `EntropyGenerated`, and `EntropySource` in foundation without a production scheduler or entropy source.

```kotlin
fun interface WallClock { fun now(): Instant }
fun interface MonotonicClock { fun now(): MonotonicTime }
data class ScheduleTimer(val timerId: TimerId, val delay: Duration, val generation: Generation)
data class TimerFired(val timerId: TimerId, val generation: Generation)
data class EntropyRequest(val correlationId: CorrelationId, val byteCount: Int)
data class EntropyGenerated(val correlationId: CorrelationId, val bytes: Bytes)
fun interface EntropySource { fun generate(request: EntropyRequest): EntropyGenerated }
```
- [ ] Implement virtual clocks, a deadline/sequence scheduler, and a test-only seeded entropy source in `:core:testing`. The scheduler must not call sleep or a wall clock.
- [ ] Run `rtk ./gradlew :core:testing:allTests` and confirm all virtual-runtime tests pass on Android and iOS.

### Task 4: Add the reducer, transition, correlation, and redacted trace kernel by TDD

**Files:**
- Create: `core/foundation/src/commonMain/kotlin/com/yet/bitmessage/foundation/Engine.kt`
- Create: `core/foundation/src/commonMain/kotlin/com/yet/bitmessage/foundation/Trace.kt`
- Create: `core/foundation/src/commonTest/kotlin/com/yet/bitmessage/foundation/EngineTest.kt`
- Create: `core/foundation/src/commonTest/kotlin/com/yet/bitmessage/foundation/TraceTest.kt`

- [ ] Write a failing reducer test with an immutable counter state and event that asserts repeated `reduce` calls return identical state, effect ordering, and trace records.
- [ ] Write a failing trace test that verifies a trace contains its transition/correlation/size/decision facts but never exposes the bytes supplied to an entropy result.
- [ ] Run `rtk ./gradlew :core:foundation:allTests`; confirm the tests fail because `Engine`, `Transition`, and trace types do not exist.
- [ ] Implement only `Engine`, `Transition`, `TraceRecord`, `TransitionName`, `TraceDecision`, and nonnegative `TraceSize`; do not add composition or execution machinery.

```kotlin
interface Engine<S : Any, E : Any, F : Any> {
    fun reduce(state: S, event: E): Transition<S, F>
}
data class Transition<S : Any, F : Any>(
    val state: S,
    val effects: List<F> = emptyList(),
    val trace: List<TraceRecord> = emptyList(),
)
```
- [ ] Run `rtk ./gradlew :core:foundation:allTests` and confirm deterministic reducer and redaction tests pass.

### Task 5: Document and verify Phase 2

**Files:**
- Modify: `docs/architecture/BITMESSAGE_ARCHITECTURE.md`
- Modify: `docs/architecture/STATE_MACHINE_DESIGN.md`
- Modify: `docs/IMPLEMENTATION_PLAN.md`
- Modify: `AGENTS.md`

- [ ] Mark Tasks 2.1–2.4 complete only after implementation and tests establish the stated boundaries and laws.
- [ ] State that `IdentityId` and `SessionGeneration` are deferred to Phase 7, while `PeerId` remains transport-visible rather than durable identity.
- [ ] Run `rtk ./gradlew :core:testing:compatibilityCheck :core:foundation:allTests :core:model:allTests :core:testing:allTests :androidApp:assembleDebug :sharedLogic:linkDebugFrameworkIosSimulatorArm64`.
- [ ] Inspect Android-host and iOS test XML to report actual executed counts; run `rtk git diff --check` and re-index the code graph.
- [ ] Commit the verified Phase 2 change on `phase-1-compatibility-baseline` without merging or starting Phase 3.
