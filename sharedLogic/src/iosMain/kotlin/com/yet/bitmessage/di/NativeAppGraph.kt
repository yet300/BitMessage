package com.yet.bitmessage.di

import com.app.common.di.CommonBindings
import com.yet.bitmessage.feature.root.RootComponent
import com.yet.bitmessage.feature.root.di.RootBindings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.createGraphFactory


@DependencyGraph(
    scope = AppScope::class,
    bindingContainers = [
        CommonBindings::class,

        RootBindings::class,
    ],
)
interface NativeAppGraph : AppGraph {

    override val rootFactory: RootComponent.Factory

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(): NativeAppGraph
    }
}

fun createNativeAppGraph(): NativeAppGraph {
    return createGraphFactory<NativeAppGraph.Factory>().create()
}