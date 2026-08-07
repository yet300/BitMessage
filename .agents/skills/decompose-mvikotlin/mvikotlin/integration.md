# Decompose ↔ MVIKotlin Integration

How a component owns a store and exposes it to UI. This is the glue layer where
most architectural mistakes happen.

## Store ownership — instanceKeeper.getStore

The ONLY correct way to keep a store in a component:

```kotlin
class DefaultDetailsComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    repository: ItemRepository,
    itemId: Long,
    private val onFinished: () -> Unit,
) : DetailsComponent, ComponentContext by componentContext {

    private val store = instanceKeeper.getStore {
        DetailsStoreFactory(
            storeFactory = storeFactory,
            repository = repository,
            itemId = itemId,
        ).create()
    }
}
```

`getStore` is provided by `mvikotlin-extensions-coroutines` /
`com.arkivanov.mvikotlin.core.instancekeeper`. It wraps the store as an
`InstanceKeeper.Instance`: survives Android configuration changes, `dispose()`d
exactly once when the component is destroyed.

Anti-patterns:
- `private val store = DetailsStoreFactory(...).create()` — recreated on every
  configuration change, old one leaks (never disposed).
- Store provided by app-scoped DI singleton — outlives the screen, state leaks
  across sessions.

## Exposing state: two idioms

### A) StateFlow (coroutines projects)

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
override val state: StateFlow<DetailsStore.State> = store.stateFlow

// UI (Compose): val state by component.state.collectAsState()
```

`store.stateFlow` requires opt-in `ExperimentalCoroutinesApi` (it reads initial
state eagerly). For SwiftUI prefer `Value` (next idiom) — StateFlow is awkward
from Swift.

### B) Value (works for Compose AND SwiftUI)

MVIKotlin does NOT ship a `Store -> Value` converter — define this small helper
once per project (NOT a library API, document it as project code):

```kotlin
// asValue.kt — project helper, not a library API
fun <T : Any> StateFlow<T>.asValue(lifecycle: Lifecycle): Value<T> {
    val value = MutableValue(this.value)
    val scope = CoroutineScope(Dispatchers.Main.immediate)
    lifecycle.doOnDestroy { scope.cancel() }
    scope.launch { collect { value.value = it } }
    return value
}

// component:
override val model: Value<DetailsComponent.Model> =
    store.stateFlow.asValue(lifecycle).map(::toModel)
```

Mapping store `State` → UI `Model` in the component keeps the store's internal
state shape out of the UI contract.

## Labels → navigation callbacks

Labels are consumed by the COMPONENT and turned into callbacks; UI never sees them:

```kotlin
private val scope = coroutineScope(Dispatchers.Main.immediate + SupervisorJob())

init {
    scope.launch {
        store.labels.collect { label ->
            when (label) {
                is DetailsStore.Label.ItemDeleted -> onFinished()
                is DetailsStore.Label.ShowError -> _errorMessages.emit(label.message)
            }
        }
    }
}
```

(`coroutineScope` from Essenty — `com.arkivanov.essenty.lifecycle.coroutines` —
is auto-cancelled with the component lifecycle.)

## Intents: component methods → store.accept

```kotlin
override fun onRefreshClicked() { store.accept(DetailsStore.Intent.Refresh) }
override fun onFavoriteToggled(value: Boolean) { store.accept(DetailsStore.Intent.SetFavorite(value)) }
```

UI calls semantic component methods; the component is the only place that knows
about Intents. This keeps UI testable with a fake component.

## Full wiring picture

```
Parent creates child:  DefaultDetailsComponent(ctx, storeFactory, repo, id, onFinished = { nav.pop() })
Component init:        instanceKeeper.getStore { ... }; collect labels → callbacks
UI:                    observes component.model, calls component.onXxx()
```

## Dependency flow

- `StoreFactory` is created once at app root (debug: logging+timetravel wrappers)
  and passed DOWN the component tree through constructors (or DI).
- Repositories/use-cases flow the same way. Components receive them; stores
  receive them from components. Nothing reaches "up" or into globals.

## Review red flags specific to integration

1. `store.stateFlow` accessed in UI (`component.store.stateFlow`) → store leaked
   through component interface.
2. Label collection in UI (`LaunchedEffect { component.store.labels... }`) →
   labels are component-internal.
3. `asValue` presented as a library API → it's a project helper; verify it exists
   or add it.
4. Two stores in one component sharing Msg/State → split components or merge stores
   deliberately.
5. Store created in `childFactory` lambda and passed INTO the component → the
   component must own creation via its OWN instanceKeeper.