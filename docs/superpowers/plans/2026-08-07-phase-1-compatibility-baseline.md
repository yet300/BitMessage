# Phase 1 Compatibility Baseline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Establish an implementation-neutral, hashed BitChat compatibility corpus with pinned Apple/Android reproduction harnesses and a CI gate that proves compatibility tests actually execute.

**Architecture:** Pull `:core:testing` forward from the target architecture solely as test infrastructure. Store neutral JSON schemas, literal fixtures, provenance, promotion decisions, and deferred regression scenarios under the repository-level `compatibility/` directory; parse them with small common Kotlin data types that do not reference future packet models. Ordinary CI validates checked-in data only, while explicit disposable-checkout harnesses reproduce producers and cross-client acceptance at the pinned upstream SHAs.

**Tech Stack:** Kotlin Multiplatform 2.4.10, kotlinx.serialization JSON, kotlin.test, Android host tests, Gradle Kotlin DSL, Swift Testing, JUnit, GitHub Actions.

---

## File map

- `settings.gradle.kts`: register only `:core:testing` for Phase 1.
- `core/testing/build.gradle.kts`: configure the test-only KMP module, neutral-resource directory, and a non-zero-test Gradle gate.
- `core/testing/src/commonMain/kotlin/com/yet/bitmessage/testing/compatibility/FixtureModels.kt`: schema enums and serializable data classes only.
- `core/testing/src/commonMain/kotlin/com/yet/bitmessage/testing/compatibility/FixtureManifestParser.kt`: strict JSON parsing and structural validation; no packet decoding.
- `core/testing/src/commonTest/.../FixtureManifestParserTest.kt`: parser contract tests.
- `core/testing/src/androidHostTest/.../CompatibilityCorpusTest.kt`: load the checked-in corpus, recompute SHA-256 hashes with the JDK, and enforce provenance/count/inventory rules.
- `compatibility/schema/*.schema.json`: implementation-neutral fixture, scenario, and promotion-decision format descriptions.
- `compatibility/BitchatBaseline2026_08/*.json`: pinned profile metadata, fixtures, deferred scenarios, and historical promotion decisions.
- `tools/upstream-compat/`: exact-SHA checks, disposable-checkout runbooks/scripts, producer exporters, and cross-client acceptance tests.
- `.github/workflows/phase1-compatibility.yml`: one macOS job runs the offline Android/fixture gate and iOS-framework link.
- `AGENTS.md` and canonical Phase 1 documents: factual module/status/command updates only.

### Task 1: Register the Phase 1 testing boundary

**Files:** `settings.gradle.kts`, `core/testing/build.gradle.kts`, `AGENTS.md`

- [x] Add a failing repository-structure assertion to the future corpus test plan: the fixture module must exist as `:core:testing`, and no `:protocol:bitchat`, `:engine:*`, transport, data, crypto, or domain module may be introduced.
- [x] Add `include(":core:testing")` and a minimal module using the existing local KMP convention plugin.
- [x] Configure `androidHostTest` resources to include repository `compatibility/` and register `compatibilityCheck`, depending on `testAndroidHostTest`, which fails if JUnit XML is absent or sums to zero tests.
- [x] Update `AGENTS.md` module map and commands; remove the stale minSdk-24 failure claim because the catalog is now minSdk 26 and the build baseline is green.
- [x] Run `rtk ./gradlew projects :core:testing:tasks --all` and confirm no later-phase module exists.
- [x] Include this checkpoint in the consolidated Phase 1 branch commit.

### Task 2: Define and parse fixture format v1 with TDD

**Files:** `FixtureModels.kt`, `FixtureManifestParser.kt`, `FixtureManifestParserTest.kt`, `compatibility/schema/fixture-format-v1.schema.json`

- [x] RED: write parser tests for a minimal valid manifest, missing required provenance, duplicate fixture IDs, non-hex/odd wire bytes, outcome/reject-code consistency, blocked-decision metadata, and fixed 64-character lowercase SHA-256 values.
- [x] Run `rtk ./gradlew :core:testing:testAndroidHostTest --tests '*FixtureManifestParserTest*'`; verify failures are caused by missing parser/types.
- [x] GREEN: implement serializable `FixtureManifest`, `CompatibilityFixture`, `FixtureProvenance`, `FixtureOutcome`, `SemanticField`, `SecurityLimit`, and enums for category, direction, producer, outcome, reject code, and decision state.
- [x] Implement `FixtureManifestParser.parse(json)` with `Json { ignoreUnknownKeys = false; explicitNulls = false }` and `validate()` returning typed validation issues.
- [x] Keep `wireBytesHex` literal and opaque; do not add packet parsing, normalization, compression, or future production types.
- [x] Add the matching JSON Schema document and rerun the targeted test to green.
- [x] Include this checkpoint in the consolidated Phase 1 branch commit.

### Task 3: Add immutable hashing and corpus rules with TDD

**Files:** `CompatibilityCorpusTest.kt`, `FixtureCanonicalForm.kt`

- [x] RED: add tests proving changes to wire bytes, profile, expected outcome/reject code, signing transcript/hash, provenance, or security limits change the canonical content and invalidate `contentSha256`.
- [x] Verify the tests fail because canonicalization is absent.
- [x] GREEN: implement an unambiguous length-prefixed canonical form in common code. Sort semantic fields and limits by ID; exclude only `description`, `notes`, and `contentSha256`.
- [x] In Android host tests, compute SHA-256 with `java.security.MessageDigest`; validate every fixture and reject duplicate IDs/hashes that claim different semantics.
- [x] Rerun targeted and module tests to green.
- [x] Include this checkpoint in the consolidated Phase 1 branch commit.

### Task 4: Reproduce and add the minimal dual-upstream corpus

**Files:** `tools/upstream-compat/**`, `compatibility/bitchat-baseline-2026-08/fixtures.json`

- [x] Add exact-SHA guards for Apple `1f59e814f90c3f489f48d68262cb1bf640bf6181` and Android `094657efa0aabbb6f71c9050149d1d01aee96400`.
- [x] Add disposable-checkout Apple and Android exporter tests for deterministic, unpadded v1 broadcast/recipient, v2 broadcast/recipient/route, legacy announcement, and extended announcement/capability examples.
- [x] Run both pinned exporters, capture their literal hex, and add 14 positive fixtures with distinct producer provenance even when bytes match.
- [x] Add static acceptance tests that consume those literals in the opposite pinned client; record actual accepted-by evidence without fabricating producer symmetry.
- [x] RED: run `CompatibilityCorpusTest` with empty/unfinished hashes and observe failure.
- [x] Generate hashes with the repository hash tool/test task, insert them intentionally, and rerun green.
- [x] Include this checkpoint in the consolidated Phase 1 branch commit.

### Task 5: Add hostile, drift, and deferred regression evidence

**Files:** fixture/scenario/promotion JSON, scenario models/parser/tests

- [x] RED: add inventory assertions requiring one entry for every hostile family named by the Phase 1 brief and every Phase 0.4 drift regression.
- [x] Add literal malformed inputs grounded in pinned Apple/Android tests for empty/truncated headers, versions, sender/recipient, routes, lengths, padding, compression, signatures, types/TLVs/capabilities, fragments, sync, Noise, and Nostr.
- [x] Mark unknown-forward-compatible data `DECODE_ONLY`; use typed reject codes for `REJECT`; mark neighbor, GCS unsigned mapping, compressed representation, and any one-client-only semantics `BLOCKED_BY_PROTOCOL_DECISION` rather than guessing.
- [x] Add deferred scenarios for authenticate-before-dedup and separation of `LinkId`, `PeerId`, `AuthenticatedIdentityId`, and `ContactId`, including reconnect, multiple links, stable static identity, and future rotation.
- [x] Record historical semantic candidates promoted only after current validation and rejected historical bytes such as Kompress-generated compressed goldens and the incorrect 48-byte Noise message-three expectation.
- [x] Recompute hashes and run the inventory tests to green.
- [x] Include this checkpoint in the consolidated Phase 1 branch commit.

### Task 6: Add pinned reproduction documentation and executable checks

**Files:** `tools/upstream-compat/README.md`, scripts and harness source

- [x] Document disposable clone/checkout commands, prerequisites, producer/export commands, cross-client acceptance commands, artifact diffing, and immutable SHA recording.
- [x] Ensure scripts refuse dirty/wrong-SHA upstream checkouts and never clone moving `main` in ordinary CI.
- [x] Run exact-SHA checks and selected Apple/Android harness tests locally; record any platform limitation instead of claiming it passed.
- [x] Include this checkpoint in the consolidated Phase 1 branch commit.

### Task 7: Add the real-test CI gate

**Files:** `.github/workflows/phase1-compatibility.yml`, `core/testing/build.gradle.kts`

- [x] RED: excluding `testAndroidHostTest` originally let stale JUnit XML pass. The gate now deletes prior Android-host results first, and exclusion fails because the fresh `CompatibilityCorpusTest` result is absent.
- [x] GREEN: make `compatibilityCheck` run a fresh `testAndroidHostTest`, require its corpus-suite XML, and require a total test count above zero.
- [x] Add a macOS job that runs `:core:testing:compatibilityCheck`, affected tests, `:androidApp:assembleDebug`, and `:sharedLogic:linkDebugFrameworkIosSimulatorArm64`; use checked-in fixtures only.
- [x] Run the same Gradle commands locally and inspect exact executed counts.
- [x] Include this checkpoint in the consolidated Phase 1 branch commit.

### Task 8: Update Phase 1 status and perform final verification

**Files:** `BITCHAT_COMPATIBILITY.md`, `HISTORICAL_BITMESSAGE_SALVAGE.md`, `UPSTREAM_FEATURE_MATRIX.md`, `IMPLEMENTATION_PLAN.md`

- [x] Update only material Phase 1 facts: format/version, fixture counts/provenance, blocked cases, harness paths, real-test state, pulled-forward `:core:testing`, and task completion status.
- [x] Confirm no production `BitChatCodec`, `BinaryProtocol`, `PacketDecoder`, `PacketEncoder`, packet model, or Phase 2+ module was added.
- [x] Run `rtk ./gradlew :core:testing:compatibilityCheck :core:testing:allTests :androidApp:assembleDebug :sharedLogic:linkDebugFrameworkIosSimulatorArm64`.
- [x] Run the repository structure/content/hash/link checks and `rtk git diff --check`.
- [x] Reconfirm the historical repository is clean at `bitMessage/main` / `10feab049becf4140c8bf10e0d9428c89222840f`.
- [x] Re-index the current repository knowledge graph after the structural change.
- [x] Stop after Phase 1; do not create Phase 2 modules or production codec code.
