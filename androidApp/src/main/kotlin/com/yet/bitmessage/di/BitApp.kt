package com.yet.bitmessage.di

import android.app.Application
import dev.zacsweers.metro.createGraphFactory

class BitApp : Application() {
    val appGraph: AppGraph by lazy {
        createGraphFactory<AndroidAppGraph.Factory>().create(this)
    }

    override fun onCreate() {
        super.onCreate()
        appGraph
    }
}