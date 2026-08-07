package com.yet.bitmessage.feature.root

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.value.Value
import dev.zacsweers.metro.Inject
import kotlinx.serialization.Serializable


internal class DefaultRootComponent(
    componentContext: ComponentContext,
) : RootComponent, ComponentContext by componentContext {

    private val navigation = StackNavigation<Configuration>()

    override val childStack: Value<ChildStack<*, RootComponent.Child>>
        get() = childStack(
            source = navigation,
            serializer = Configuration.serializer(),
            initialConfiguration = Configuration.TestScreen,
            childFactory = ::createChild,
        )

    override fun onBackClicked() {
        navigation.pop()
    }


    private fun createChild(
        config: Configuration,
        componentContext: ComponentContext,
    ): RootComponent.Child =
        when (config) {
            Configuration.TestScreen -> RootComponent.Child.Test()
        }

    @Serializable
    sealed class Configuration {
        @Serializable
        data object TestScreen : Configuration()
    }

}

@Inject
internal class DefaultRootComponentFactory : RootComponent.Factory {
    override fun create(componentContext: ComponentContext): RootComponent =
        DefaultRootComponent(
            componentContext = componentContext,
        )
}
