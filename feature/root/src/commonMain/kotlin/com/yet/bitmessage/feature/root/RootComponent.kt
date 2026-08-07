package com.yet.bitmessage.feature.root

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.backhandler.BackHandlerOwner


interface RootComponent : BackHandlerOwner {

    val childStack: Value<ChildStack<*, Child>>


    fun onBackClicked()

    sealed class Child {
        class Test : Child()
    }

    fun interface Factory {
        fun create(componentContext: ComponentContext): RootComponent
    }
}
