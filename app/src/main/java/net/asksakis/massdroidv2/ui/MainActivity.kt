package net.asksakis.massdroidv2.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.ui.components.MiniPlayer
import net.asksakis.massdroidv2.ui.navigation.MassDroidNavHost
import net.asksakis.massdroidv2.ui.navigation.Routes
import net.asksakis.massdroidv2.ui.screens.home.MiniPlayerViewModel
import net.asksakis.massdroidv2.ui.theme.MassDroidTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkBatteryOptimization()
        setContent {
            MassDroidTheme {
                MassDroidApp()
            }
        }
    }

    private fun checkBatteryOptimization() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            showBatteryOptimizationDialog()
        }
    }

    @SuppressLint("BatteryLife")
    private fun showBatteryOptimizationDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Disable Battery Optimization")
            .setMessage(
                "For reliable background music playback, MassDroid needs to be " +
                "excluded from battery optimization.\n\n" +
                "Without this, Android may stop playback when the screen is off."
            )
            .setPositiveButton("Disable Optimization") { _, _ ->
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
            .setNegativeButton("Skip", null)
            .show()
    }
}

@Composable
private fun MassDroidApp(
    miniPlayerViewModel: MiniPlayerViewModel = hiltViewModel()
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBottomBar = currentRoute in listOf(Routes.HOME, Routes.LIBRARY, Routes.SEARCH)
    val showMiniPlayer = currentRoute != Routes.NOW_PLAYING

    val connectionState by miniPlayerViewModel.connectionState.collectAsStateWithLifecycle()
    val selectedPlayer by miniPlayerViewModel.selectedPlayer.collectAsStateWithLifecycle()
    val queueState by miniPlayerViewModel.queueState.collectAsStateWithLifecycle()

    val isConnected = connectionState is ConnectionState.Connected
    val player = selectedPlayer
    val currentTrack = queueState?.currentItem?.track
    val title = currentTrack?.name ?: player?.currentMedia?.title ?: player?.displayName ?: ""
    val artist = currentTrack?.artistNames ?: player?.currentMedia?.artist ?: ""
    val imageUrl = currentTrack?.imageUrl ?: player?.currentMedia?.imageUrl
    val hasMiniPlayer = isConnected && player != null && showMiniPlayer

    Scaffold(
        bottomBar = {
            Column {
                if (hasMiniPlayer) {
                    MiniPlayer(
                        title = title,
                        artist = artist,
                        imageUrl = imageUrl,
                        isPlaying = player?.state == PlaybackState.PLAYING,
                        onPlayPause = { miniPlayerViewModel.playPause() },
                        onNext = { miniPlayerViewModel.next() },
                        onQueue = {
                            navController.navigate(Routes.QUEUE) {
                                launchSingleTop = true
                            }
                        },
                        onClick = {
                            navController.navigate(Routes.NOW_PLAYING) {
                                launchSingleTop = true
                            }
                        }
                    )
                }
                if (showBottomBar) {
                    NavigationBar {
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                            label = { Text("Home") },
                            selected = currentRoute == Routes.HOME,
                            onClick = {
                                navController.navigate(Routes.HOME) {
                                    popUpTo(Routes.HOME) { inclusive = true }
                                }
                            }
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.LibraryMusic, contentDescription = "Library") },
                            label = { Text("Library") },
                            selected = currentRoute == Routes.LIBRARY,
                            onClick = {
                                navController.navigate(Routes.LIBRARY) {
                                    popUpTo(Routes.HOME)
                                    launchSingleTop = true
                                }
                            }
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                            label = { Text("Search") },
                            selected = currentRoute == Routes.SEARCH,
                            onClick = {
                                navController.navigate(Routes.SEARCH) {
                                    popUpTo(Routes.HOME)
                                    launchSingleTop = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        MassDroidNavHost(
            navController = navController,
            modifier = Modifier.padding(paddingValues)
        )
    }
}
