# SwiftUI (iOS) Integration for Decompose

Native SwiftUI rendering of Decompose components (alternative to wrapping the
whole app in `ComposeUIViewController`).

## Kotlin side: expose Value, not StateFlow

`Value<T>` is directly observable from Swift. Components intended for SwiftUI
should expose `Value<Model>` (see integration.md `asValue` helper if the source
is a store's StateFlow).

## Swift side: ObservableValue wrapper

A small Swift helper (commonly copied from the Decompose samples — it is sample
code, not a shipped library API):

```swift
import shared // your KMP framework

@MainActor
final class ObservableValue<T: AnyObject>: ObservableObject {
    @Published private(set) var value: T
    private var cancellation: Cancellation?

    init(_ value: Value<T>) {
        self.value = value.value
        self.cancellation = value.subscribe { [weak self] v in self?.value = v }
    }

    deinit { cancellation?.cancel() }
}
```

Usage in a SwiftUI view:

```swift
struct DetailsView: View {
    private let component: DetailsComponent
    @StateObject private var model: ObservableValue<DetailsComponentModel>

    init(_ component: DetailsComponent) {
        self.component = component
        self._model = StateObject(wrappedValue: ObservableValue(component.model))
    }

    var body: some View {
        VStack {
            if model.value.isLoading { ProgressView() }
            if let item = model.value.item {
                Text(item.title)
                Toggle("Favorite", isOn: Binding(
                    get: { item.isFavorite },
                    set: { component.onFavoriteToggled(value: $0) }
                ))
            }
            Button("Refresh") { component.onRefreshClicked() }
        }
    }
}
```

## Rendering Child Stack in SwiftUI

```swift
struct RootView: View {
    @StateObject private var stack: ObservableValue<ChildStack<AnyObject, RootComponentChild>>
    private let component: RootComponent

    init(_ component: RootComponent) {
        self.component = component
        self._stack = StateObject(wrappedValue: ObservableValue(component.childStack))
    }

    var body: some View {
        let child = stack.value.active.instance
        // Simple switch-rendering; for push/pop animations see StackView in Decompose samples
        switch child {
        case let list as RootComponentChild.ListChild: ListView(list.component)
        case let details as RootComponentChild.DetailsChild: DetailsView(details.component)
        default: EmptyView()
        }
    }
}
```

For native push/pop animations and swipe-back, use the `StackView` helper from
the Decompose `app-ios` sample (renders `ChildStack` via `NavigationStack` and
binds pops back to the component's `onBackClicked`). Key rule: a swipe-back MUST
call back into the component (`onBackClicked(toIndex:)`) so the component stack
stays the source of truth.

## App entry: lifecycle binding

```swift
@main
struct iOSApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var holder = RootHolder() // wraps LifecycleRegistry + root component

    var body: some Scene {
        WindowGroup {
            RootView(holder.root)
                .onChange(of: scenePhase) { _, phase in
                    switch phase {
                    case .background: holder.lifecycle.stop()
                    case .active: holder.lifecycle.resume()
                    default: break
                    }
                }
        }
    }
}
```

```swift
final class RootHolder: ObservableObject {
    let lifecycle: LifecycleRegistry
    let root: RootComponent

    init() {
        lifecycle = LifecycleRegistryKt.LifecycleRegistry()
        root = DefaultRootComponent(
            componentContext: DefaultComponentContext(lifecycle: lifecycle)
        )
        LifecycleRegistryExtKt.create(lifecycle)
    }

    deinit { LifecycleRegistryExtKt.destroy(lifecycle) }
}
```

## Rules / review red flags

1. Subscribing to `Value` without storing the `Cancellation` → leak; cancel in `deinit`.
2. Creating components inside SwiftUI `View.init` or `body` → views are recreated
   constantly; components live in holders (`RootHolder`, or child instances taken
   from `ChildStack`).
3. Driving navigation from SwiftUI state (`NavigationStack(path:)` as source of
   truth) → the Decompose stack is the single source of truth; SwiftUI mirrors it.
4. Forgetting lifecycle binding to `scenePhase` → stores keep running in background.
5. Exposing `StateFlow` to Swift and polling it → expose `Value` instead.