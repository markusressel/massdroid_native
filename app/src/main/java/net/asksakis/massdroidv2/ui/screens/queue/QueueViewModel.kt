package net.asksakis.massdroidv2.ui.screens.queue

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.model.QueueItem
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import javax.inject.Inject

private const val TAG = "QueueVM"

@HiltViewModel
class QueueViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository
) : ViewModel() {

    private val _queueItems = MutableStateFlow<List<QueueItem>>(emptyList())
    val queueItems: StateFlow<List<QueueItem>> = _queueItems.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error: SharedFlow<String> = _error.asSharedFlow()

    private val queueId: String?
        get() = playerRepository.selectedPlayer.value?.playerId

    init {
        loadQueue()
    }

    private fun loadQueue() {
        val id = queueId ?: return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _queueItems.value = musicRepository.getQueueItems(id)
            } catch (e: Exception) {
                Log.w(TAG, "loadQueue failed: ${e.message}")
            }
            _isLoading.value = false
        }
    }

    fun playIndex(index: Int) {
        val id = queueId ?: return
        viewModelScope.launch {
            try {
                musicRepository.playQueueIndex(id, index)
            } catch (e: Exception) {
                Log.w(TAG, "playIndex failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun removeItem(itemId: String) {
        val id = queueId ?: return
        viewModelScope.launch {
            try {
                musicRepository.deleteQueueItem(id, itemId)
                loadQueue()
            } catch (e: Exception) {
                Log.w(TAG, "removeItem failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun moveItemUp(queueItemId: String) {
        val id = queueId ?: return
        viewModelScope.launch {
            try {
                musicRepository.moveQueueItem(id, queueItemId, -1)
                loadQueue()
            } catch (e: Exception) {
                Log.w(TAG, "moveItemUp failed: ${e.message}", e)
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun moveItemDown(queueItemId: String) {
        val id = queueId ?: return
        viewModelScope.launch {
            try {
                musicRepository.moveQueueItem(id, queueItemId, 1)
                loadQueue()
            } catch (e: Exception) {
                Log.w(TAG, "moveItemDown failed: ${e.message}", e)
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun playNext(queueItemId: String, currentIndex: Int) {
        val id = queueId ?: return
        viewModelScope.launch {
            try {
                musicRepository.moveQueueItem(id, queueItemId, -(currentIndex - 1))
                loadQueue()
            } catch (e: Exception) {
                Log.w(TAG, "playNext failed: ${e.message}", e)
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun clearQueue() {
        val id = queueId ?: return
        viewModelScope.launch {
            try {
                musicRepository.clearQueue(id)
                _queueItems.value = emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "clearQueue failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }
}
