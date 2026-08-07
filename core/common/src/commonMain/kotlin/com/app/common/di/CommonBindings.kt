package com.app.common.di

import com.app.common.AppDispatchers
import com.app.common.permission.GrantPermissionController
import com.app.common.permission.PermissionController
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

@ContributesTo(AppScope::class)
@BindingContainer
abstract class CommonBindings {

    /** The app-wide runtime-permission seam, backed by Grant (GrantManager provided per-platform). */
    @Binds
    abstract val GrantPermissionController.bindPermissionController: PermissionController

    companion object {
        @SingleIn(AppScope::class)
        @Provides
        fun provideStoreFactory(): StoreFactory = DefaultStoreFactory()

        @SingleIn(AppScope::class)
        @Provides
        fun provideAppDispatchers(): AppDispatchers = AppDispatchers()
    }
}
