package com.yet.bitmessage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.arkivanov.decompose.defaultComponentContext
import com.yet.bitmessage.di.BitApp
import com.yet.bitmessage.feature.root.PreviewRootComponent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val appGraph = (application as BitApp).appGraph
        val rootComponent = appGraph.rootFactory.create(
            componentContext = defaultComponentContext()
        )

        setContent {
            App(component = rootComponent)
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App(component = PreviewRootComponent())
}