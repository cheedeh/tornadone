package com.tornadone.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private data class TabItem(val label: String, val icon: ImageVector)

private val baseTabs = listOf(
    TabItem("Home", Icons.Default.Home),
    TabItem("Options", Icons.Default.Settings),
)
private val devTabs = baseTabs + TabItem("Debug", Icons.Default.Code)

@Composable
fun MainScreen(
    viewModel: HomeViewModel,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tabs = if (state.developerModeEnabled) devTabs else baseTabs

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showApiSettings by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.developerModeEnabled) {
        if (selectedTab >= tabs.size) selectedTab = 0
    }

    if (showApiSettings) {
        ApiSettingsScreen(
            viewModel = viewModel,
            onBack = { showApiSettings = false },
        )
        return
    }

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
            0 -> HomeTab(viewModel, onStartService, onStopService, modifier)
            1 -> OptionsTab(viewModel, modifier, onOpenApiSettings = { showApiSettings = true })
            2 -> DebugTab(viewModel, modifier)
        }
    }
}
