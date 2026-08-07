# Navigation & Runtime Performance (Decompose + MVIKotlin)

How to analyze WHY navigation feels slow and how to prove it with measurements.
When asked to "audit performance" / "navigation is slow / janky", follow the
audit procedure at the bottom and report findings as
**finding → evidence/metric → fix**, never generic advice.

## What happens on `push(Config)` — the cost model

```
nav.push(Config.Details(id))
 ├─ 1. Stack transform + config serialization check        (~free)
 ├─ 2. childFactory: component constructor                  ← main-thread, must be cheap
 ├─ 3. Store creation on first access (instanceKeeper)      ← executor bootstrapper starts
 ├─ 4. First composition of the new screen                  ← usually the biggest cost
 ├─ 5. Stack animation (slide/fade/predictive back)         ← runs WHILE 3–4 settle
 └─ 6. Async data arrives → recomposition mid-animation     ← classic jank source
```

Frame budget: 16.6ms (60Hz) / 8.3ms (120Hz). A push that drops frames almost
always loses them in 2, 4, or 6 — measure before fixing.

## Rule 1 — Component constructors must be cheap

The constructor runs synchronously on the main thread during the navigation
frame. Forbidden in constructors/`init`: repository calls, JSON parsing, large
list mapping, file/prefs reads, store `.state` forcing heavy bootstrap work
synchronously.

```kotlin
// BAD: blocks the navigation frame
init {
    val cached = json.decodeFromString<List<Order>>(prefs.getString("orders")) // ❌
    _model.value = Model(cached)
}

// GOOD: constructor only wires; work starts via Bootstrapper or lifecycle
init {
    scope.launch { store.labels.collect(::onLabel) }  // cheap subscription is fine
}
// loading happens in the store's Bootstrapper (Action.Load), off the nav frame
```

## Rule 2 — Don't grow the stack with duplicates; reuse with `bringToFront`

For tab-like navigation `push` creates a NEW component every switch and the
stack grows unbounded (memory + stateKeeper payload). `bringToFront(config)`
reuses the existing instance — switch becomes O(1), state retained:

```kotlin
fun onTabClicked(tab: Tab) { nav.bringToFront(tab.toConfig()) }
```

Configs must implement equals correctly (data class/object) for this to work.

## Rule 3 — Granular subscriptions, stable models

A single `Value<BigModel>` observed at the screen root recomposes the whole
screen on every field change:

```kotlin
// BAD: cart badge recomposes the entire screen every tick
val model by component.model.subscribeAsState()

// GOOD: slice per region; only the badge recomposes
val cartCount by remember(component) { component.model.map { it.cartCount } }.subscribeAsState()
```

- `Value.map` is cheap; expose pre-sliced `Value`s from the component for hot regions.
- UI models: immutable + `@Immutable`, stable collections — otherwise Compose
  can't skip; verify with compiler reports, don't guess.
- Lambdas passed down: method references (`component::onRefreshClicked`) are
  stable; inline lambdas capturing the model are not.

## Rule 4 — Animation cost

- `fade()` < `slide()` < `slide() + scale()` < predictive back (renders TWO
  screens live). If a screen janks with slide but not fade — the screen's first
  composition is too heavy; fix the screen, not the animation.
- Heavy screens: render a lightweight skeleton first frame, fill content when
  data arrives — never block animation on data (no `runBlocking`, no sync cache reads).
- Nested `LazyColumn` inside animated stack child with unbounded height →
  measure storms during transition. Fix layout (fixed viewport, `Modifier.fillMaxSize`).

## Rule 5 — StateKeeper payload is on the critical path (Android)

`stateKeeper`-registered state of EVERY component in the stack is serialized in
`onSaveInstanceState` (and TransactionTooLargeException kills you near ~500KB
total). Keep ONLY identity/input state (ids, query text, scroll position), never
loaded data — data reloads from cache via the store.

```kotlin
// BAD: 2000 orders serialized on every background
@Serializable data class SavedState(val orders: List<Order>)        // ❌
// GOOD
@Serializable data class SavedState(val query: String, val firstVisibleIndex: Int)
```

## Rule 6 — Store-side hot paths

- Per-keystroke intents: debounce INSIDE the executor (`flow` of search queries +
  `debounce`/`mapLatest`), not in UI.
- `states` already emits distinct values; but if State holds a huge list that is
  re-built (new identity) on unrelated Msg, every observer recomposes —
  keep unchanged sub-objects referentially identical in the reducer (`copy` only
  what changed).
- `labels` are unbuffered: emitting from a tight loop drops events; batch in the
  executor.

## Measuring (always propose at least one)

**Android — Macrobenchmark** (navigation timing + frame metrics):

```kotlin
@Test fun navigateToDetails() = benchmarkRule.measureRepeated(
    packageName = "com.example.app",
    metrics = listOf(FrameTimingMetric(), TraceSectionMetric("DetailsComponent")),
    iterations = 10,
    startupMode = StartupMode.WARM,
) {
    device.findObject(By.res("order_item_0")).click()
    device.wait(Until.hasObject(By.res("details_screen")), 5_000)
}
```

Add trace sections to make component costs visible:

```kotlin
private fun child(config: Config, ctx: ComponentContext): Child =
    trace("child:${config::class.simpleName}") { ... } // androidx.tracing
```

**Compose compiler reports** — prove skippability problems:
`./gradlew assembleRelease -PcomposeCompilerReports=true` → check
`*-composables.txt` for `restartable but not skippable` on screen contents.

**Recomposition counts in dev**: Layout Inspector (Android Studio) or a debug
modifier logging recompositions for suspected regions.

**iOS**: Instruments → Time Profiler + SwiftUI view body counts; wrap component
creation in `os_signpost` regions from the Swift side.

## Performance audit procedure (audit mode)

When asked to audit, walk this list against the code IN ORDER and collect
evidence for each finding:

1. **Constructors**: grep feature components' `init`/constructors for repository,
   serialization, prefs, mapping calls.
2. **Stack hygiene**: tabs via `push`? duplicates possible? stack depth unbounded?
3. **Subscription granularity**: one big `subscribeAsState` at screen root? model
   sliced for hot regions (badges, timers, progress)?
4. **Stability**: UI models from non-Compose modules without `@Immutable`; lambda
   captures; collections identity churn in reducers.
5. **StateKeeper payload**: what is registered? any loaded data lists?
6. **Animations**: which animation, are both transition screens heavy, predictive
   back on heavy screens?
7. **Store hot paths**: per-keystroke intents, label loops, big-state copies.
8. **Measurement**: does the project have Macrobenchmark/tracing? If not, include
   the setup snippet in the report.

Report format per finding:

```
[P1] IO in DetailsComponent constructor (DetailsComponent.kt:42)
Evidence: prefs.getString + Json.decode on nav frame; FrameTimingMetric P90 frameDurationCpuMs 31ms on push.
Fix: move to store Bootstrapper; show skeleton until Msg.Loaded. Expected: P90 < 11ms.
```

Severity: P1 = main-thread IO / frame drops on navigation; P2 = unbounded growth
(stack, stateKeeper); P3 = recomposition waste without measured jank.