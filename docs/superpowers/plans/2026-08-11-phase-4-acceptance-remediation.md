# Phase 4 Acceptance Remediation Implementation Plan

> **For agentic workers:** Execute inline in this Phase 4 branch. Do not begin Phase 5. Use test-driven development for production behavior changes.

**Goal:** Move the Phase 4 positive known answers into the canonical compatibility corpus and move wire decoding from `MeshEngine` to the runtime protocol boundary.

**Architecture:** `compatibility/BitchatBaseline2026_08/fixtures.json` is the only normative literal corpus. `MeshRuntime` maps `LinkEvent` values through a pure `MeshProtocolAdapter`; only successfully decoded packets become `MeshEvent.PacketDecoded` values. Fragment completion hands reassembled bytes back to that adapter without asking the reducer to execute the codec.

**Tech stack:** Kotlin Multiplatform, kotlinx.coroutines, kotlinx.serialization, Gradle verification tasks, pinned Swift/JUnit upstream harnesses.

---

### Task 1: Canonical Phase 4 evidence

**Files:**

- Modify: `compatibility/BitchatBaseline2026_08/fixtures.json`
- Modify: `core/testing/src/androidHostTest/kotlin/com/yet/bitmessage/testing/compatibility/CompatibilityCorpusTest.kt`
- Modify: `protocol/bitchat/src/androidHostTest/kotlin/com/yet/bitmessage/protocol/bitchat/ProductionFixtureCoverage.kt`
- Modify: `protocol/bitchat/src/androidHostTest/kotlin/com/yet/bitmessage/protocol/bitchat/ProductionFixtureCoverageTest.kt`
- Modify: `protocol/bitchat/src/androidHostTest/kotlin/com/yet/bitmessage/protocol/bitchat/FixtureCoverageAuditTest.kt`
- Modify: `tools/upstream-compat/apple/BitMessagePhase4EvidenceTests.swift`
- Modify: `tools/upstream-compat/android/BitMessagePhase4EvidenceTest.kt`
- Remove: `protocol/bitchat/src/commonTest/kotlin/com/yet/bitmessage/protocol/bitchat/Phase4ProtocolEvidenceTest.kt`

- [x] Update corpus tests to require six new fixture IDs, 52 total fixtures, 20 dual-client accepted fixtures, and matching category counts; run `./gradlew :core:testing:compatibilityCheck --rerun-tasks --console=plain` and observe the missing-fixture failure.
- [x] Add Apple/Android packet-identity, signing/TTL-mutation, and positive-fragment fixtures with placeholder hashes; run the gate and capture each canonical hash mismatch.
- [x] Replace placeholders with the reported canonical hashes and rerun the gate to green.
- [x] Extend the pinned harness output with exact signed and relayed wire bytes, and make production coverage execute `PacketIdentity`, `SigningTranscript`, `RelayEncoding`, and `FragmentPayloadCodec` for every new fixture.
- [x] Remove the shadow common-test literal corpus and run `./gradlew :protocol:bitchat:productionCompatibilityCheck :protocol:bitchat:allTests --rerun-tasks --console=plain`.

### Task 2: Runtime protocol adapter boundary

**Files:**

- Create: `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/runtime/MeshProtocolAdapter.kt`
- Modify: `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/MeshEvent.kt`
- Modify: `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/MeshEffect.kt`
- Modify: `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/MeshState.kt`
- Modify: `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/AdmissionReducer.kt`
- Modify: `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/FragmentReducer.kt`
- Modify: `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/runtime/MeshRuntime.kt`
- Modify: matching `engine/mesh/src/commonTest` tests and fixtures.

- [x] Add failing tests proving a malformed `LinkEvent.PayloadReceived` is rejected by the runtime without changing `MeshState` or creating pending admission.
- [x] Add failing tests proving a valid payload becomes `PacketDecoded` evidence before reduction and that raw payload events cannot be reduced directly.
- [x] Add failing fragment tests requiring `ReinjectPacket` instead of `DecodePacket`.
- [x] Implement `MeshProtocolAdapter`, make `PacketDecoded` a successful ingress event containing packet/signing evidence, remove `DecodePacket`, and let the runtime decode both link payloads and reassembled bytes.
- [x] Run `./gradlew :transport:api:allTests :engine:mesh:meshEngineCheck :engine:mesh:allTests --rerun-tasks --console=plain`.

### Task 3: Policy and lifecycle documentation

**Files:**

- Modify: `docs/superpowers/specs/2026-08-11-phase-4-deterministic-mesh-engine-design.md`
- Modify: `docs/architecture/BITCHAT_COMPATIBILITY.md`
- Modify: `docs/architecture/STATE_MACHINE_DESIGN.md`
- Modify: `engine/mesh/src/commonMain/kotlin/com/yet/bitmessage/engine/mesh/RelayPolicy.kt`

- [x] Name the relay cap as a local policy and document that `7` and `255 -> 6` are not dual-upstream compatibility truth.
- [x] Define stop/start as in-process suspension/restart rather than a new security epoch, and document retention of only unexpired dedup entries.
- [x] Update the final codec-to-mesh data flow and remove the old decode-effect description.

### Task 4: Full acceptance verification

- [x] Run the pinned upstream harness at both exact commits.
- [x] Run all Phase 1-4 tests and gates on Android and iOS targets plus Android app/iOS framework builds.
- [x] Count executed tests from fresh XML reports; run `git diff --check` and verify a clean working tree after committing.
- [x] Commit the narrow remediation and report its full SHA. Confirm Phase 5 remains unstarted.
