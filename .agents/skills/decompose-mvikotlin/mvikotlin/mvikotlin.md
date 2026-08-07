# MVIKotlin Reference (4.4.0)

Dependencies:

```kotlin
implementation("com.arkivanov.mvikotlin:mvikotlin:4.4.0")
implementation("com.arkivanov.mvikotlin:mvikotlin-main:4.4.0")                 // DefaultStoreFactory
implementation("com.arkivanov.mvikotlin:mvikotlin-extensions-coroutines:4.4.0") // CoroutineExecutor, Flow APIs
// or:
implementation("com.arkivanov.mvikotlin:mvikotlin-extensions-reaktive:4.4.0")   // ReaktiveExecutor
// debug only:
implementation("com.arkivanov.mvikotlin:mvikotlin-logging:4.4.0")
implementation("com.arkivanov.mvikotlin:mvikotlin-timetravel:4.4.0")
```

## Store anatomy

```
Intent ──► Executor ──► Msg ──► Reducer ──► State
              │
   Action ───┘ (from Bootstrapper)
              └──► Label (one-off events out)
```

- **Intent** — what UI asks the store to do.
- **Action** — internal trigger from `Bootstrapper` (init work) — UI never sends Actions.
- **Msg** (Message) — result of executor work, input for Reducer. Private to the store.
- **State** — single immutable data class.
- **Label** — one-off outgoing event (navigation trigger, toast, analytics).
- **Reducer** — pure function `State + Msg -> State`. No side effects, ever.

`Store<Intent, State, Label>` interface: `state`, `states` (observable),
`labels` (observable), `accept(Intent)`, `dispose()`. All on Main thread.

## Store with CoroutineExecutor (default flavor)

```kotlin
interface DetailsStore : Store<DetailsStore.Intent, DetailsStore.State, DetailsStore.Label> {

    sealed interface Intent {
        data object Refresh : Intent
        data class SetFavorite(val isFavorite: Boolean) : Intent
    }

    data class State(
        val item: Item? = null,
        val isLoading: Boolean = false,
        val error: String? = null,
    )

    sealed interface Label {
        data class ShowError(val message: String) : Label
    }
}

class DetailsStoreFactory(
    private val storeFactory: StoreFactory,
    private val repository: ItemRepository,
    private val itemId: Long,
) {
    fun create(): DetailsStore =
        object : DetailsStore, Store<Intent, State, Label> by storeFactory.create(
            name = "DetailsStore",
            initialState = State(),
            bootstrapper = SimpleBootstrapper(Action.Load),
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl,
        ) {}

    private sealed interface Action { data object Load : Action }

    private sealed interface Msg {
        data object Loading : Msg
        data class Loaded(val item: Item) : Msg
        data class Failed(val message: String) : Msg
        data class FavoriteChanged(val isFavorite: Boolean) : Msg
    }

    private inner class ExecutorImpl : CoroutineExecutor<Intent, Action, State, Msg, Label>() {
        // `scope` is provided, Main-dispatched, auto-cancelled on dispose.

        override fun executeAction(action: Action) {
            when (action) { is Action.Load -> load() }
        }

        override fun executeIntent(intent: Intent) {
            when (intent) {
                is Intent.Refresh -> load()
                is Intent.SetFavorite -> {
                    dispatch(Msg.FavoriteChanged(intent.isFavorite)) // optimistic, Main thread
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { repository.setFavorite(itemId, intent.isFavorite) } }
                            .onFailure {
                                dispatch(Msg.FavoriteChanged(!intent.isFavorite)) // rollback
                                publish(Label.ShowError("Failed to update favorite"))
                            }
                    }
                }
            }
        }

        private fun load() {
            scope.launch {
                dispatch(Msg.Loading)
                runCatching { withContext(Dispatchers.IO) { repository.load(itemId) } }
                    .onSuccess { dispatch(Msg.Loaded(it)) }
                    .onFailure {
                        dispatch(Msg.Failed(it.message ?: "Unknown error"))
                        publish(Label.ShowError(it.message ?: "Unknown error"))
                    }
            }
        }
    }

    private object ReducerImpl : Reducer<State, Msg> {
        override fun State.reduce(msg: Msg): State =
            when (msg) {
                is Msg.Loading -> copy(isLoading = true, error = null)
                is Msg.Loaded -> copy(isLoading = false, item = msg.item)
                is Msg.Failed -> copy(isLoading = false, error = msg.message)
                is Msg.FavoriteChanged -> copy(item = item?.copy(isFavorite = msg.isFavorite))
            }
    }
}
```

Threading rules:
- `dispatch(Msg)` and `publish(Label)` — Main thread only. After `withContext(IO)`
  you're back on `scope`'s Main dispatcher — correct by construction.
- Never `GlobalScope`; never create your own scope in the executor — use `scope`.
- `state` property inside executor (`state()` accessor) reads current state.

## DSL flavor (coroutineExecutorFactory)

Compact alternative for simple stores:

```kotlin
fun create(): DetailsStore =
    object : DetailsStore, Store<Intent, State, Label> by storeFactory.create<Intent, Action, Msg, State, Label>(
        name = "DetailsStore",
        initialState = State(),
        bootstrapper = SimpleBootstrapper(Action.Load),
        executorFactory = coroutineExecutorFactory {
            onAction<Action.Load> { launchLoad() }
            onIntent<Intent.Refresh> { launchLoad() }
        },
        reducer = { msg -> /* same reduce */ },
    ) {}
```

`onIntent<T> { }` / `onAction<T> { }` blocks have `dispatch`, `publish`, `state()`,
`launch { }` available. Prefer class-based `CoroutineExecutor` once logic grows.

## ReaktiveExecutor flavor

```kotlin
private inner class ExecutorImpl : ReaktiveExecutor<Intent, Action, State, Msg, Label>() {
    override fun executeIntent(intent: Intent) {
        when (intent) {
            is Intent.Refresh ->
                repository.load(itemId)                  // Single<Item>
                    .observeOn(mainScheduler)
                    .map(Msg::Loaded)
                    .subscribeScoped(onSuccess = ::dispatch) // scoped = auto-disposed
        }
    }
}
```

`subscribeScoped` ties subscriptions to executor lifetime. Same rule: dispatch on Main
(`observeOn(mainScheduler)` before dispatching).

## StoreFactory wiring

```kotlin
val storeFactory: StoreFactory =
    if (DEBUG) LoggingStoreFactory(TimeTravelStoreFactory()) else DefaultStoreFactory()
```

Inject `StoreFactory` into every `*StoreFactory` class — never hardcode
`DefaultStoreFactory()` inside.

## Testing stores

Stores are Main-bound — use `Dispatchers.setMain` (coroutines) or
`overrideSchedulers` (reaktive):

```kotlin
class DetailsStoreTest {
    @BeforeTest fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) } // or UnconfinedTestDispatcher
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Test fun loads_item_WHEN_created() = runTest {
        val store = DetailsStoreFactory(DefaultStoreFactory(), FakeRepository(), itemId = 1L).create()
        advanceUntilIdle()
        assertEquals(expectedItem, store.state.item)
    }
}
```

Test through the public interface: send Intents, assert `state` / collected labels.
Don't test Executor/Reducer in isolation — they're implementation details.

## Common mistakes (flag in review)

1. Store as singleton / app-scoped DI binding → must be per-component via InstanceKeeper.
2. `GlobalScope.launch` or custom scope in executor → use provided `scope`.
3. `dispatch` from IO thread → crash in debug builds (`assertOnMainThread`).
4. Side effects in Reducer (logging, repository calls) → move to Executor.
5. Label modeled as state flag (`val showToast: Boolean`) → use Label.
6. UI calling `store.accept` directly → UI talks to the component only.
7. Exposing `Msg` or `Action` outside the store file → they're private wiring.