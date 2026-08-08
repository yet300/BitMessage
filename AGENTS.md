# BitMessage Agent Guide

## Purpose and Current State

BitMessage is an early-stage Kotlin Multiplatform application targeting Android and iOS. The repository currently provides the application shells, shared logic, an Android Compose UI module, reusable common utilities, and Gradle convention plugins.

Do not infer implemented product behavior from the project name or version catalog. Messaging protocols, persistence, networking, cryptographic workflows, feature boundaries, and the final navigation architecture are not established in the current source tree. Inspect the code and requirements before introducing them.

## Start Here

Before changing code:

1. Read this file and the nearest module `build.gradle.kts`.
2. Inspect `settings.gradle.kts` and `gradle/libs.versions.toml` when the change touches modules, plugins, platforms, or dependencies.
3. Use the code knowledge graph for code discovery when it is available. If the repository is not indexed or the index is stale, index the repository root first.
4. Read the relevant repository skill under `.agents/skills` before working with Kotlin, Compose, Decompose, MVIKotlin, Metro, Android, or iOS APIs.
5. Verify assumptions against current source. The project is evolving and this guide may lag behind a structural change.

## Repository Map

```text
BitMessage/
├── androidApp/                 Android application entry point
├── iosApp/                     Native iOS application and Xcode project
├── sharedUI/                   Compose UI; currently configured for Android
├── sharedLogic/                Android/iOS shared application logic
├── core/
│   ├── common/                 Reusable Android/iOS common utilities
│   ├── foundation/             Lowest-level multiplatform building blocks
│   ├── model/                  Typed kernel models
│   └── testing/                Phase 1 compatibility fixtures and test harnesses
├── protocol/
│   └── bitchat/                Pure BitChat wire codec
├── build-logic/
│   └── convention/             Local Gradle convention plugins
├── gradle/
│   └── libs.versions.toml      Central versions, libraries, bundles, plugins
├── .agents/skills/             Repository-local agent guidance
├── settings.gradle.kts         Module registration and repositories
└── build.gradle.kts            Root plugin declarations
```

### Module Responsibilities

| Module | Responsibility | Current targets and dependencies |
|---|---|---|
| `:androidApp` | Android manifest, activity, packaging, and Android application entry point | Android; depends on `:sharedUI` |
| `iosApp` | SwiftUI application entry point and Xcode configuration | iOS; imports the `SharedLogic` framework |
| `:sharedUI` | Compose presentation code and resources | Currently Android only; depends on `:sharedLogic` |
| `:sharedLogic` | Logic shared by Android and iOS and exported as `SharedLogic` | Android, iOS ARM64, iOS Simulator ARM64; depends on `:core:common` |
| `:core:common` | Reusable multiplatform utilities and infrastructure | Android, iOS ARM64, iOS Simulator ARM64; configured by the local KMP convention plugin |
| `:core:foundation` | Lowest-level multiplatform building blocks | Android, iOS ARM64, iOS Simulator ARM64; configured by the local KMP convention plugin with no project dependency |
| `:core:model` | Typed kernel models | Android, iOS ARM64, iOS Simulator ARM64; depends on `:core:foundation` |
| `:core:testing` | Test-only compatibility fixture models, loaders, and validation gates | Android, iOS ARM64, iOS Simulator ARM64; depends on `:core:foundation`; neutral fixture resources are consumed by tests only |
| `:protocol:bitchat` | Pure BitChat wire model and codec | Android, iOS ARM64, iOS Simulator ARM64; depends on `:core:foundation` and `:core:model`; test scope may depend on `:core:testing` |
| `build-logic:convention` | Shared Gradle configuration for multiplatform modules | Included build, not application runtime code |

Current dependency direction:

```text
androidApp -> sharedUI -> sharedLogic -> core:common
iosApp ----------------> sharedLogic -> core:common
core:model -----------------------> core:foundation
compatibility fixtures -----------> core:testing -> core:foundation
protocol:bitchat -----------------> core:foundation, core:model
```

Keep dependencies pointing inward along these paths unless a deliberate architecture change is requested. Production modules must not depend on `:core:testing`, and lower-level modules must not import application entry points or UI modules.

## Source Placement

- Put portable Kotlin in `commonMain`.
- Put Android API integrations in `androidMain` or `androidApp`.
- Put iOS Kotlin integrations in `iosMain`; use `nativeMain` only when behavior genuinely applies to all configured native targets.
- Put native SwiftUI and Apple application lifecycle code in `iosApp/iosApp`.
- Keep Compose views and Compose resources in `sharedUI`. Despite its name, `sharedUI` is currently configured only as an Android KMP library; do not claim iOS Compose support until its targets and Xcode integration are added.
- Put lowest-level portable building blocks in `core:foundation`; keep typed kernel models in `core:model`, which depends only on `core:foundation`.
- Put broadly reusable, UI-independent primitives in `core:common`. Do not turn it into a dumping ground for feature-specific behavior.
- Tests belong in the matching source set, normally `commonTest`, `androidHostTest`, or an iOS test source set.

When adding `expect`/`actual`, keep the expected contract small and platform-neutral. Prefer common implementations when no platform API is required.

## Architecture Boundaries

- UI files render state and emit user actions. Keep business rules, data access, service lookup, routing decisions, and domain mutation outside composables and SwiftUI views.
- `sharedLogic` may expose platform-neutral state and operations to both application shells. It must not depend on Android UI or SwiftUI types.
- `core:common` contains reusable mechanisms, not product orchestration.
- Platform code adapts operating-system APIs to contracts owned by common code.
- Decompose, MVIKotlin, Metro, and other catalogued libraries are available foundations, not proof that a specific architecture has already been adopted. Follow existing use or an approved design instead of generating infrastructure speculatively.
- Prefer focused files and explicit interfaces. Avoid new abstraction layers until at least one concrete use requires them.

## Code Discovery

When codebase-memory MCP tools are available, use this order for code discovery:

1. `search_graph` for functions, classes, variables, and entry points.
2. `trace_path` for callers, callees, dependencies, and data flow.
3. `get_code_snippet` after resolving the exact qualified name.
4. `query_graph` for complex structural questions.
5. `get_architecture` for a high-level map.

Use `rg` for string literals, error messages, configuration values, documentation, and files the graph does not cover. Re-index the repository after major structural changes.

## Repository Skills

`.agents/skills` contains specialized guidance. If a task clearly matches a skill, read its full instruction file before editing. Relevant groups currently include:

- `compose-multiplatform/`: state, effects, stability, performance, layout, animation, focus, adaptive design, slots, and UI testing;
- `kotlin/`: control flow, functions, coroutines, Flow state/events, and value classes;
- `decompose-mvikotlin/`: Decompose, MVIKotlin, and performance patterns;
- `metro-di/`: Metro dependency injection;
- `android/`: edge-to-edge and Android performance guidance;
- `ios/`: iOS and Apple interface guidance;
- `kSafe/KSAFE_SKILL.md`: KSafe-specific guidance.

Use only skills relevant to the files being changed. Repository instructions and explicit task requirements take precedence over generic examples in a skill.

## Dependencies and Build Logic

- Declare versions and external coordinates in `gradle/libs.versions.toml`.
- Declare module dependencies in the consuming module's `build.gradle.kts`.
- Put configuration shared by multiple modules in `build-logic/convention`; keep one-off settings local.
- Do not assume every library in the version catalog is already in use. Confirm a module dependency before coding against it.
- Preserve type-safe project accessors and the repository/plugin configuration in `settings.gradle.kts`.
- Do not edit generated Gradle output or local machine configuration.

## Commands

Run commands from the repository root with the checked-in Gradle wrapper.

```bash
# Inspect modules and tasks
./gradlew projects
./gradlew tasks --all

# Build the Android debug application
./gradlew :androidApp:assembleDebug

# Run module tests
./gradlew :core:common:allTests
./gradlew :sharedLogic:allTests
./gradlew :sharedUI:allTests

# Run the Phase 1 fixture suite and fail if no compatibility test executes
./gradlew :core:testing:compatibilityCheck

# Run BitChat protocol module tests
./gradlew :protocol:bitchat:allTests

# Link the shared framework for the iOS simulator
./gradlew :sharedLogic:linkDebugFrameworkIosSimulatorArm64

# Broad pre-handoff verification (currently exposes the known issue below)
./gradlew :androidApp:assembleDebug :core:common:allTests :sharedLogic:allTests :sharedUI:allTests
```

Run the narrowest relevant task first. For iOS application UI or signing changes, open `iosApp/iosApp.xcodeproj` in Xcode and use the appropriate scheme and simulator; Gradle validates the Kotlin framework, not the complete Xcode application.

## Known Baseline Issues

As of 2026-08-07, the version catalog sets `android-minSdk` to 26 and the documented Android/iOS framework build baseline is green. Before Phase 1, the existing module `allTests` tasks were `NO-SOURCE`; `:core:testing:compatibilityCheck` is the first gate that requires a non-zero executed test count. Do not claim other modules have test coverage until their own source sets contain executed tests.

## Change Discipline

- Inspect neighboring code and tests before choosing names, packages, or patterns.
- Keep changes scoped to the request; do not add speculative feature scaffolding.
- Add or update tests for behavior changes.
- Never modify `.gradle/`, `.idea/`, `.kotlin/`, any `build/` directory, `local.properties`, `xcuserdata`, or `.DS_Store` as source work.
- Do not overwrite unrelated local changes.
- Check whether Git is available before relying on Git-based workflows; this project directory may be used before repository initialization.
- Report the exact verification commands run and any remaining unverified platform work.

## Maintaining This Guide

Update `AGENTS.md` in the same change whenever you add, remove, rename, or repurpose a module; change supported targets or dependency direction; move application entry points; adopt a project-wide architecture; or change standard build and test commands. Keep `CLAUDE.md` and `GEMINI.md` as thin pointers to this file.
