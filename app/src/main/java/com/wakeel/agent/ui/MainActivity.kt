package com.wakeel.agent.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CandlestickChart
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.wakeel.agent.Hub

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WakeelTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    WakeelRoot()
                }
            }
        }
    }
}

private class Dest(val label: String, val icon: ImageVector)

private val destinations = listOf(
    Dest("القيادة", Icons.Rounded.AutoAwesome),
    Dest("المعرفة", Icons.Rounded.School),
    Dest("التداول", Icons.Rounded.CandlestickChart),
    Dest("السجل", Icons.Rounded.History),
    Dest("الإعدادات", Icons.Rounded.Tune),
)

@Composable
private fun WakeelRoot() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var draft by rememberSaveable { mutableStateOf("") }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                destinations.forEachIndexed { i, d ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(d.icon, null) },
                        label = { Text(d.label) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                0 -> HomeScreen(
                    task = draft,
                    onTask = { draft = it },
                    onOpenSettings = { tab = 4 },
                    onOpenKnowledge = { tab = 1 },
                )
                1 -> KnowledgeScreen(
                    onRun = { text -> Hub.engine.start(text); tab = 0 },
                    onDraft = { text -> draft = text; tab = 0 },
                    onGoHome = { tab = 0 },
                )
                2 -> TradeScreen(onDraft = { text -> draft = text; tab = 0 })
                3 -> HistoryScreen()
                else -> SettingsScreen()
            }
        }
    }
}
