package net.asksakis.massdroidv2.ui.screens.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import javax.inject.Inject

private const val TAG = "MiniPlayerVM"

@HiltViewModel
class MiniPlayerViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val wsClient: MaWebSocketClient
) : ViewModel() {

    val selectedPlayer = playerRepository.selectedPlayer
    val queueState = playerRepository.queueState
    val connectionState = wsClient.connectionState

    fun playPause() {
        val player = selectedPlayer.value ?: return
        viewModelScope.launch {
            try {
                playerRepository.playPause(player.playerId)
            } catch (e: Exception) {
                Log.w(TAG, "playPause failed: ${e.message}")
            }
        }
    }

    fun next() {
        val player = selectedPlayer.value ?: return
        viewModelScope.launch {
            try {
                playerRepository.next(player.playerId)
            } catch (e: Exception) {
                Log.w(TAG, "next failed: ${e.message}")
            }
        }
    }
}
