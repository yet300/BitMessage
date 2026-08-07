package com.yet.bitmessage.di

import com.yet.bitmessage.feature.root.RootComponent

interface AppGraph {
    val rootFactory: RootComponent.Factory
}