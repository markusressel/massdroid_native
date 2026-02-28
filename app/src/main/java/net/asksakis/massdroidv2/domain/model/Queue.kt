package net.asksakis.massdroidv2.domain.model

data class QueueState(
    val queueId: String,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val elapsedTime: Double = 0.0,
    val currentItem: QueueItem? = null
)

data class QueueItem(
    val queueItemId: String,
    val name: String = "",
    val duration: Double = 0.0,
    val track: Track? = null,
    val imageUrl: String? = null
)

enum class RepeatMode(val apiValue: String) {
    OFF("off"),
    ONE("one"),
    ALL("all");

    companion object {
        fun fromApi(value: String): RepeatMode = entries.find { it.apiValue == value } ?: OFF
    }
}
