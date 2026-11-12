package com.flopster101.usbaudiobridge

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(
    state: MainUiState,
    onToggleGadget: (Boolean) -> Unit,
    onToggleCapture: () -> Unit,
    onToggleRecording: () -> Unit,
    onShareRecording: () -> Unit,
    onBufferSizeChange: (Float) -> Unit,
    onBufferModeChange: (Int) -> Unit,
    onLatencyPresetChange: (Int) -> Unit,
    onPeriodSizeChange: (Int) -> Unit,
    onEngineTypeChange: (Int) -> Unit,
    onUseOboeChange: (Boolean) -> Unit,
    onSampleRateChange: (Int) -> Unit,
    onUacVersionChange: (Int) -> Unit,
    onKeepAdbChange: (Boolean) -> Unit,
    onAutoRestartChange: (Boolean) -> Unit,
    onActiveDirectionsChange: (Int) -> Unit,
    onMicSourceChange: (Int) -> Unit,
    onRecordingFormatChange: (Int) -> Unit,
    onChooseRecordingDirectory: () -> Unit,
    onUseDefaultRecordingDirectory: () -> Unit,
    onNotificationEnabledChange: (Boolean) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onScreensaverEnabledChange: (Boolean) -> Unit,
    onScreensaverTimeoutChange: (Int) -> Unit,
    onScreensaverRepositionIntervalChange: (Int) -> Unit,
    onScreensaverDvdModeChange: (Boolean) -> Unit,
    onScreensaverDvdSpeedChange: (Int) -> Unit,
    onScreensaverFpsLimitChange: (Int) -> Unit,
    onScreensaverFullscreenChange: (Boolean) -> Unit,
    onScreensaverActivate: () -> Unit,
    onScreensaverDeactivate: () -> Unit,
    onToggleSpeakerMute: () -> Unit,
    onToggleMicMute: () -> Unit,
    onMuteOnMediaButtonChange: (Boolean) -> Unit,
    onThemeModeChange: (Int) -> Unit,
    onDynamicColorsChange: (Boolean) -> Unit,
    onResetSettings: () -> Unit,
    onToggleLogs: () -> Unit
) {
    val screensaverFadeDurationMs = 250
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val homeScrollBehavior     = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val settingsScrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val aboutScrollBehavior    = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    val currentScrollBehavior = when (currentRoute) {
        "settings" -> settingsScrollBehavior
        "about"    -> aboutScrollBehavior
        else       -> homeScrollBehavior
    }

    // Screensaver timer logic
    val screensaverEnabled = state.keepScreenOnOption && state.screensaverEnabled
    // The screensaver is suppressed during busy states, and the timer resets when they finish.
    val isBusy = state.isGadgetPending || state.isCapturePending
    val isBusyState = rememberUpdatedState(isBusy)
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Reset timer on navigation
    LaunchedEffect(currentRoute) {
        lastInteractionTime = System.currentTimeMillis()
    }

    // Reset screensaver timer when a busy operation finishes
    LaunchedEffect(isBusy) {
        if (!isBusy) {
            lastInteractionTime = System.currentTimeMillis()
        }
    }

    // Rekeyed on currentScrollBehavior so the wrapper always delegates to the active tab's connection
    val myNestedScrollConnection = remember(currentScrollBehavior) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                lastInteractionTime = System.currentTimeMillis()
                return currentScrollBehavior.nestedScrollConnection.onPreScroll(available, source)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                lastInteractionTime = System.currentTimeMillis()
                return currentScrollBehavior.nestedScrollConnection.onPostScroll(consumed, available, source)
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(myNestedScrollConnection),
        topBar = {
            AnimatedContent(
                targetState = currentRoute,
                transitionSpec = {
                    (fadeIn(tween(200)) togetherWith fadeOut(tween(150)))
                        .using(SizeTransform(clip = false) { _, _ -> tween(220) })
                },
                label = "TopBarTransition"
            ) { route ->
                when (route) {
                    "home" -> LargeTopAppBar(
                        title = { Text("USB Audio Bridge") },
                        scrollBehavior = homeScrollBehavior
                    )
                    "settings" -> MediumTopAppBar(
                        title = { Text("Settings") },
                        scrollBehavior = settingsScrollBehavior
                    )
                    "about" -> MediumTopAppBar(
                        title = { Text("About") },
                        scrollBehavior = aboutScrollBehavior
                    )
                }
            }
        },
        bottomBar = {
            BottomNavBar(navController = navController)
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding)
        ) {
            composable("home") {
                HomeScreen(
                    state = state,
                    onToggleGadget = onToggleGadget,
                    onToggleCapture = onToggleCapture,
                    onToggleSpeakerMute = onToggleSpeakerMute,
                    onToggleMicMute = onToggleMicMute,
                    onToggleRecording = onToggleRecording,
                    onShareRecording = onShareRecording,
                    onToggleLogs = onToggleLogs
                )
            }
            composable("settings") {
                SettingsScreen(
                    state = state,
                    onBufferSizeChange = onBufferSizeChange,
                    onBufferModeChange = onBufferModeChange,
                    onLatencyPresetChange = onLatencyPresetChange,
                    onPeriodSizeChange = onPeriodSizeChange,
                    onEngineTypeChange = onEngineTypeChange,
                    onUseOboeChange = onUseOboeChange,
                    onSampleRateChange = onSampleRateChange,
                    onUacVersionChange = onUacVersionChange,
                    onKeepAdbChange = onKeepAdbChange,
                    onAutoRestartChange = onAutoRestartChange,
                    onActiveDirectionsChange = onActiveDirectionsChange,
                    onMicSourceChange = onMicSourceChange,
                    onRecordingFormatChange = onRecordingFormatChange,
                    onChooseRecordingDirectory = onChooseRecordingDirectory,
                    onUseDefaultRecordingDirectory = onUseDefaultRecordingDirectory,
                    onNotificationEnabledChange = onNotificationEnabledChange,
                    onKeepScreenOnChange = onKeepScreenOnChange,
                    onScreensaverEnabledChange = onScreensaverEnabledChange,
                    onScreensaverTimeoutChange = onScreensaverTimeoutChange,
                    onScreensaverRepositionIntervalChange = onScreensaverRepositionIntervalChange,
                    onScreensaverDvdModeChange = onScreensaverDvdModeChange,
                    onScreensaverDvdSpeedChange = onScreensaverDvdSpeedChange,
                    onScreensaverFpsLimitChange = onScreensaverFpsLimitChange,
                    onScreensaverFullscreenChange = onScreensaverFullscreenChange,
                    onMuteOnMediaButtonChange = onMuteOnMediaButtonChange,
                    onThemeModeChange = onThemeModeChange,
                    onDynamicColorsChange = onDynamicColorsChange,
                    onResetSettings = onResetSettings
                )
            }
            composable("about") {
                AboutScreen()
            }
        }
    }

    // Screensaver timer
    LaunchedEffect(screensaverEnabled, state.screensaverTimeout) {
        if (!screensaverEnabled) return@LaunchedEffect

        lastInteractionTime = System.currentTimeMillis()  // Reset timer when enabled

        while (true) {
            delay(1000) // Check every second
            // Suppress activation while a gadget/capture operation is in progress.
            if (isBusyState.value) continue
            val timeSinceLastInteraction = System.currentTimeMillis() - lastInteractionTime
            val shouldActivate = timeSinceLastInteraction >= (state.screensaverTimeout * 1000L)

            if (shouldActivate && !state.screensaverActive) {
                onScreensaverActivate()
            }
        }
    }

    // Screensaver overlay with fade transitions
    AnimatedVisibility(
        visible = state.screensaverActive,
        enter = fadeIn(animationSpec = tween(durationMillis = screensaverFadeDurationMs)),
        exit = fadeOut(animationSpec = tween(durationMillis = screensaverFadeDurationMs))
    ) {
        ScreensaverOverlay(
            state = state,
            fadeInDurationMs = screensaverFadeDurationMs,
            onDismiss = {
                onScreensaverDeactivate()
                lastInteractionTime = System.currentTimeMillis()
            }
        )
    }
}

@Composable
fun BottomNavBar(navController: NavController) {
    val items = listOf("Home", "Settings", "About")
    val icons = listOf(Icons.Default.Home, Icons.Default.Settings, Icons.Default.Info)
    val routes = listOf("home", "settings", "about")

    NavigationBar {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route

        items.forEachIndexed { index, item ->
            NavigationBarItem(
                icon = { Icon(icons[index], contentDescription = item) },
                label = { Text(item) },
                selected = currentRoute == routes[index],
                onClick = {
                    navController.navigate(routes[index]) {
                        popUpTo(navController.graph.startDestinationId) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    }
}
