package com.yet.bitmessage.di

import android.content.Context
import com.app.common.di.CommonBindings
import com.yet.bitmessage.feature.root.RootComponent
import com.yet.bitmessage.feature.root.di.RootBindings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides

@DependencyGraph(
    scope = AppScope::class,
    bindingContainers = [
        CommonBindings::class,

        RootBindings::class,
    ],
)
interface AndroidAppGraph : AppGraph {

    override val rootFactory: RootComponent.Factory

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides context: Context,
        ): AndroidAppGraph
    }
}