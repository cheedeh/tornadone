package com.tornado.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

private data class TabItem(val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem("Voice", Icons.Default.Mic),
    TabItem("Gesture", Icons.Default.Sensors),
    TabItem("Options", Icons.Default.Settings),
    TabItem("Tasks", Icons.Default.Checklist),
    TabItem("Debug", Icons.Default.Code),
)

@Composable
fun MainScreen(
    viewModel: HomeViewModel,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (selectedTab) {
            0 -> VoiceTab(viewModel, modifier)
            1 -> GestureTab(viewModel, onStartService, onStopService, modifier)
            2 -> OptionsTab(viewModel, modifier)
            3 -> TasksTab(modifier)
            4 -> DebugTab(viewModel, modifier)
        }
    }
}
