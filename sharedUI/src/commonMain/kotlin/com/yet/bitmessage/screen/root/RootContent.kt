package com.yet.bitmessage.screen.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yet.bitmessage.component.utils.cupertinoPredictiveBackAnimation
import com.yet.bitmessage.feature.root.RootComponent
import com.yet.bitmessage.screen.test.TestContent


@OptIn(ExperimentalDecomposeApi::class)
@Composable
fun RootContent(
    modifier: Modifier = Modifier,
    component: RootComponent
) {
    val childStack by component.childStack.subscribeAsState()

    Children(
        modifier = modifier,
        stack = childStack,
        animation = cupertinoPredictiveBackAnimation(
            backHandler = component.backHandler,
            onBack = component::onBackClicked,
        ),
    ) { child ->
        when (val instance = child.instance) {
            is RootComponent.Child.Test -> TestContent()
        }
    }
}