package net.asksakis.massdroidv2.data.proximity

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

private const val TAG = "RoomDetector"
private const val KNN_K = 5
private const val MIN_CONFIDENCE = 0.6
private const val MIN_MARGIN = 4.0
private const val MIN_CONSECUTIVE_WINS = 2
private const val STAY_BIAS = 1.5
private const val MISSING_PENALTY = 12.0
private const val RSSI_DIFF_CLAMP = 25

/**
 * Room classifier with two detection paths:
 * - Fingerprint k-NN: weighted Manhattan distance to stored fingerprints (preferred)
 * - Legacy beacon scoring: RSSI deviation from reference (fallback)
 */
@Singleton
class RoomDetector @Inject constructor() {

    private val _currentRoom = MutableStateFlow<DetectedRoom?>(null)
    val currentRoom: StateFlow<DetectedRoom?> = _currentRoom.asStateFlow()

    private var consecutiveWinnerId: String? = null
    private var consecutiveWinCount = 0

    fun detect(scanResults: Map<String, Int>, config: ProximityConfig): DetectedRoom? {
        if (config.rooms.isEmpty() || scanResults.isEmpty()) return null
        return detectFingerprint(scanResults, config)
    }

    fun reset() {
        resetConfidence()
        _currentRoom.value = null
    }

    // region Fingerprint k-NN detection

    private fun detectFingerprint(scanResults: Map<String, Int>, config: ProximityConfig): DetectedRoom? {
        // Build weight maps from beacon profiles per room
        val roomWeights = config.rooms.associate { room ->
            room.id to room.beaconProfiles.associate { it.address to it.weight }
        }

        // Compute distance to every fingerprint of every room
        data class FpEntry(val roomId: String, val distance: Double)

        val allDistances = mutableListOf<FpEntry>()
        for (room in config.rooms) {
            if (room.fingerprints.isEmpty()) continue
            val weights = roomWeights[room.id] ?: emptyMap()
            for (fp in room.fingerprints) {
                val dist = fingerprintDistance(scanResults, fp, weights)
                // Apply stay bias: current room gets a small distance reduction
                val biased = if (_currentRoom.value?.roomId == room.id) dist - STAY_BIAS else dist
                allDistances.add(FpEntry(room.id, biased))
            }
        }

        if (allDistances.isEmpty()) return null

        // k-NN: take top K nearest fingerprints
        val topK = allDistances.sortedBy { it.distance }.take(KNN_K)

        // Vote by room
        val votes = topK.groupBy { it.roomId }.mapValues { it.value.size }
        val sortedVotes = votes.entries.sortedByDescending { it.value }
        val winnerId = sortedVotes.first().key
        val winnerVotes = sortedVotes.first().value
        val confidence = winnerVotes.toDouble() / topK.size

        // Margin: average distance of winner fingerprints vs runner-up
        val winnerAvgDist = topK.filter { it.roomId == winnerId }.map { it.distance }.average()
        val runnerUpAvgDist = topK.filter { it.roomId != winnerId }
            .map { it.distance }.takeIf { it.isNotEmpty() }?.average() ?: (winnerAvgDist + MIN_MARGIN + 1)
        val margin = runnerUpAvgDist - winnerAvgDist

        val winnerRoom = config.rooms.first { it.id == winnerId }
        val topRoomNames = topK.map { e -> config.rooms.first { it.id == e.roomId }.name }

        Log.d(TAG, "k-NN: winner=${winnerRoom.name}, confidence=${String.format("%.2f", confidence)}, " +
            "margin=${String.format("%.1f", margin)}, top$KNN_K=$topRoomNames")

        // Thresholds
        if (confidence < MIN_CONFIDENCE) {
            resetConfidence()
            return null
        }
        if (sortedVotes.size > 1 && margin < MIN_MARGIN) {
            resetConfidence()
            return null
        }

        // Consecutive wins
        if (winnerId == consecutiveWinnerId) {
            consecutiveWinCount++
        } else {
            consecutiveWinnerId = winnerId
            consecutiveWinCount = 1
        }

        if (consecutiveWinCount < MIN_CONSECUTIVE_WINS) return null

        val detected = DetectedRoom(winnerRoom.id, winnerRoom.name, winnerRoom.playerId, winnerRoom.playerName)
        val changed = _currentRoom.value?.roomId != winnerRoom.id
        _currentRoom.value = detected
        return if (changed) detected else null
    }

    private fun fingerprintDistance(
        current: Map<String, Int>,
        fingerprint: RoomFingerprint,
        weights: Map<String, Double>
    ): Double {
        var total = 0.0
        var used = 0.0
        for ((addr, refRssi) in fingerprint.samples) {
            val weight = weights[addr] ?: 1.0
            val currentRssi = current[addr]
            val contribution = if (currentRssi != null) {
                weight * abs(currentRssi - refRssi).coerceAtMost(RSSI_DIFF_CLAMP)
            } else {
                weight * MISSING_PENALTY
            }
            total += contribution
            used += weight
        }
        return if (used > 0.0) total / used else Double.MAX_VALUE
    }

    // endregion

    private fun resetConfidence() {
        consecutiveWinnerId = null
        consecutiveWinCount = 0
    }
}
