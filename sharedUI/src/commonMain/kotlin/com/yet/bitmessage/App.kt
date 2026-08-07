package com.yet.bitmessage

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.yet.bitmessage.feature.root.PreviewRootComponent
import com.yet.bitmessage.feature.root.RootComponent
import com.yet.bitmessage.screen.root.RootContent

@Composable
fun App(component: RootComponent) = MaterialTheme {
    RootContent(component = component)
}


@PreviewLightDark
@Composable
internal fun AppPreview() = RootContent(component = PreviewRootComponent())