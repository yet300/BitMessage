# Phase 2 Foundation Design

Status: approved for implementation on 2026-08-07

## Goal

Establish the smallest deterministic Kotlin Multiplatform foundation required by the Phase 3–7 protocol and engine work, without defining protocol packets, messenger domain entities, authenticated identities, or runtimes.

## Module boundaries

```text
:core:model   -> :core:foundation
:core:testing -> :core:foundation
```

`:core:testing` remains test infrastructure. Production modules never depend on it. It has no initial dependency on `:core:model`; one may be added only to a test source set when a concrete model-aware helper needs it.

All three modules use the existing Kotlin Multiplatform convention, so their portable code targets Android, iOS ARM64, and iOS Simulator ARM64. They depend only on Kotlin/KMP libraries supplied by that convention.

## Values and identifier scope

`Bytes` belongs to `:core:foundation`. It is an immutable byte value with content-based equality and hashing, defensive copies at both ownership boundaries, an explicit `size`, unsigned indexed access, and two explicit constructors:

- `Bytes.copyOf(bytes)`: copy exactly the supplied bytes;
- `Bytes.requireExactSize(bytes, expectedSize)`: copy only if the supplied size equals the caller-specified size.

Neither constructor truncates, pads, normalizes, decodes text, or embeds a BitChat-specific length. `Bytes.toString()` reports only its size.

`LinkId` and `PeerId` belong to `:core:model` and are distinct `@JvmInline` value classes. `PeerId` wraps `Bytes`, is transport/protocol-visible, and has no authenticated-identity or contact meaning. It deliberately has no Phase 2 BitChat length constraint. `LinkId` wraps a validated nonblank opaque token. There is no conversion between them.

`TimerId`, `CorrelationId`, and neutral `Generation` belong to `:core:foundation` because they support time, effect, and stale-result mechanics rather than domain ownership. `Generation` is not a session model. `IdentityId`, `MessageId`, `AttemptId`, and `OperationId` are deferred; their precise semantics depend on Phase 7–9 contracts.

## Deterministic runtime contracts

`WallClock` returns `kotlinx.datetime.Instant` for external/persisted time. `MonotonicClock` returns a `MonotonicTime` value that is process-local, must not be serialized as durable time, and is used only with `Duration` for elapsed-time calculations.

Reducers express timer work with `ScheduleTimer(timerId, delay, generation)` and `CancelTimer(timerId)`. The corresponding callback is `TimerFired(timerId, generation)`. A future engine compares its own generation with the callback; the scheduler never assigns session meaning.

`VirtualWallClock`, `VirtualMonotonicClock`, and `VirtualScheduler` live in `:core:testing`. The scheduler advances only when instructed, keeps a `(deadline, insertionSequence)` priority order, replaces a timer ID explicitly, and suppresses cancelled entries. It never sleeps or reads wall time.

Entropy is a request/result boundary: `EntropyRequest(correlationId, byteCount)` and `EntropyGenerated(correlationId, bytes)`. Production implementations of `EntropySource` must use a cryptographically secure provider; Phase 2 supplies no production provider. Test-only `SeededEntropy` is deterministic and is not security entropy. Reducers request entropy through effects and later reduce its result event.

## Reducer and trace kernel

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

The API has no middleware, registry, actor, persistence, command bus, or composition support. Equal input values must yield equal next state, ordered effects, and trace records.

`TraceRecord` has a validated transition name, optional correlation ID, typed decision code, and nonnegative size facts. It has no raw byte, plaintext, key, ciphertext, or arbitrary diagnostic-value field. This creates a safe structured default while future modules add their own typed decisions.

## Verification

Tests are written before each behavior implementation. They prove defensive byte ownership, exact-size failure, identifier non-interchangeability, deterministic clocks/entropy/scheduling, cancellation/replacement order, stale-generation distinguishability, deterministic transition equality, and trace redaction. The unchanged Phase 1 compatibility gate, Android assembly, and iOS framework link remain required verification.

## Explicit exclusions

This design does not introduce `IdentityId`, authenticated identity/aliasing, packet or codec types, Noise sessions, transport/Bluetooth APIs, messenger/domain entities, persistence, UI, or Phase 1 decision changes. Phase 3 remains unstarted.
