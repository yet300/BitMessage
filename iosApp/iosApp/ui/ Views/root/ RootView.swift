import SwiftUI
import SharedLogic

struct RootView: View {
    private let root: RootComponent

    init(_ root: RootComponent) {
        self.root = root
    }

    var body: some View {
        StackView(
            stackValue: StateValue(root.childStack),
            getTitle: { _ in "Heh" },
            onBack: { _ in
                root.onBackClicked()
            }
        ) { child in
            childView(for: child)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

@ViewBuilder
private func childView(for child: RootComponentChild) -> some View {
    switch child {
    case let child as TestChild:
        TestView()
    default:
        EmptyView()
    }
}

private typealias TestChild = RootComponentChild.Test
