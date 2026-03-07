package net.asksakis.massdroidv2.domain.recommendation

import android.util.Log
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.repository.ArtistScore
import net.asksakis.massdroidv2.domain.repository.GenreScore
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

private const val TAG = "SmartMix"

private const val TOP_ARTISTS_LIMIT = 12
private const val TOP_GENRES_LIMIT = 4
private const val ARTISTS_PER_GENRE = 6
private const val MAX_TRACKS_PER_ARTIST = 2
private const val ARTIST_RANK_JITTER = 0.22
private const val TRACK_RANK_JITTER = 0.28
private const val FAVORITE_ARTIST_BONUS = 0.70
private const val FAVORITE_ALBUM_BONUS = 0.40
private const val FAVORITE_ARTISTS_CANDIDATE_LIMIT = 18

@Singleton
class SmartMixEngine @Inject constructor() {

    fun buildArtistOrder(
        artistScores: List<ArtistScore>,
        genreScores: List<GenreScore>,
        genreArtists: Map<String, List<String>>,
        excludedArtistUris: Set<String>,
        favoriteArtistUris: Set<String>,
        bllArtistScoreMap: Map<String, Double>,
        smartArtistScoreMap: Map<String, Double>,
        daypartAffinityByArtist: Map<String, Double>,
        randomSeed: Long = System.currentTimeMillis()
    ): List<String> {
        val random = Random(randomSeed)
        val artistScoreMap = artistScores.associate { it.artistUri to it.score }
        val topGenres = genreScores.take(TOP_GENRES_LIMIT).map { it.genre.lowercase() }

        val candidates = linkedSetOf<String>()
        favoriteArtistUris
            .asSequence()
            .filterNot { it in excludedArtistUris }
            .take(FAVORITE_ARTISTS_CANDIDATE_LIMIT)
            .forEach { candidates += it }
        artistScores
            .asSequence()
            .map { it.artistUri }
            .filterNot { it in excludedArtistUris }
            .take(TOP_ARTISTS_LIMIT)
            .forEach { candidates += it }

        for (genre in topGenres) {
            genreArtists[genre]
                .orEmpty()
                .asSequence()
                .filterNot { it in excludedArtistUris }
                .distinct()
                .sortedByDescending { uri ->
                    artistCompositeScore(
                        uri = uri,
                        artistScoreMap = artistScoreMap,
                        bllArtistScoreMap = bllArtistScoreMap,
                        smartArtistScoreMap = smartArtistScoreMap,
                        favoriteArtistUris = favoriteArtistUris,
                        daypartAffinityByArtist = daypartAffinityByArtist
                    )
                }
                .take(ARTISTS_PER_GENRE)
                .forEach { candidates += it }
        }

        val jitter = candidates.associateWith { random.nextDouble(-ARTIST_RANK_JITTER, ARTIST_RANK_JITTER) }
        Log.d(
            TAG,
            "buildArtistOrder: ${candidates.size} candidates " +
                "(${favoriteArtistUris.size} favorites, ${artistScores.size} BLL, " +
                "topGenres=${topGenres.joinToString()})"
        )
        val sorted = candidates.sortedByDescending { uri ->
            artistCompositeScore(
                uri = uri,
                artistScoreMap = artistScoreMap,
                bllArtistScoreMap = bllArtistScoreMap,
                smartArtistScoreMap = smartArtistScoreMap,
                favoriteArtistUris = favoriteArtistUris,
                daypartAffinityByArtist = daypartAffinityByArtist
            ) + (jitter[uri] ?: 0.0)
        }
        sorted.forEachIndexed { i, uri ->
            val score = artistCompositeScore(
                uri, artistScoreMap, bllArtistScoreMap, smartArtistScoreMap,
                favoriteArtistUris, daypartAffinityByArtist
            )
            val isFav = if (uri in favoriteArtistUris) " [FAV]" else ""
            Log.d(TAG, "  artist #${i + 1}: $uri score=${String.format("%.2f", score)}$isFav")
        }
        return sorted
    }

    fun buildTrackUris(
        artistOrder: List<String>,
        tracksByArtist: Map<String, List<Track>>,
        genreScores: List<GenreScore>,
        excludedArtistUris: Set<String>,
        favoriteAlbumUris: Set<String>,
        artistBaseScore: (String) -> Double,
        target: Int,
        randomSeed: Long = System.currentTimeMillis()
    ): List<String> {
        if (target <= 0 || artistOrder.isEmpty()) return emptyList()
        val random = Random(randomSeed)
        val genreScoreMap = genreScores.associate { it.genre.lowercase() to it.score }

        val seenTrackUris = mutableSetOf<String>()
        val byArtistCount = mutableMapOf<String, Int>()
        val scored = mutableListOf<ScoredTrack>()

        for (artistUri in artistOrder) {
            val tracks = tracksByArtist[artistUri].orEmpty()
            if (tracks.isEmpty()) continue

            val ranked = tracks
                .asSequence()
                .filter { it.uri.isNotBlank() && it.uri !in seenTrackUris }
                .filterNot { track ->
                    val trackArtistKeys = buildSet {
                        addAll(track.artistUris)
                        MediaIdentity.canonicalArtistKey(track.artistItemId, track.artistUri)
                            ?.let(::add)
                        add(candidateArtistKey(artistUri))
                    }
                    trackArtistKeys.any { it in excludedArtistUris }
                }
                .map { track ->
                    val genreAffinity = track.genres.sumOf { g -> genreScoreMap[g.lowercase()] ?: 0.0 } * 0.6
                    val favoriteBonus = if (track.favorite) 0.25 else 0.0
                    val trackAlbumKey = MediaIdentity.canonicalAlbumKey(track.albumItemId, track.albumUri)
                    val favoriteAlbumBonus =
                        if (!trackAlbumKey.isNullOrBlank() && trackAlbumKey in favoriteAlbumUris) FAVORITE_ALBUM_BONUS
                        else 0.0
                    ScoredTrack(
                        track = track,
                        artistUri = artistUri,
                        score = artistBaseScore(artistUri) + genreAffinity + favoriteBonus +
                            favoriteAlbumBonus +
                            random.nextDouble(-TRACK_RANK_JITTER, TRACK_RANK_JITTER)
                    )
                }
                .sortedByDescending { it.score }
                .toList()

            for (candidate in ranked) {
                val bucketArtist = MediaIdentity.canonicalArtistKey(
                    itemId = candidate.track.artistItemId,
                    uri = candidate.track.artistUri
                ) ?: candidateArtistKey(candidate.artistUri)
                val count = byArtistCount[bucketArtist] ?: 0
                if (count >= MAX_TRACKS_PER_ARTIST) continue
                scored += candidate
                seenTrackUris += candidate.track.uri
                byArtistCount[bucketArtist] = count + 1
                if (scored.size >= target * 2) break
            }
            if (scored.size >= target * 2) break
        }

        val ordered = scored
            .sortedByDescending { it.score }
            .distinctBy { it.track.uri }

        Log.d(TAG, "buildTrackUris: ${ordered.size} scored tracks from ${tracksByArtist.size} artists, target=$target")

        val interleaved = interleaveByArtist(ordered, limit = target, random = random)
        interleaved.forEachIndexed { i, track ->
            Log.d(TAG, "  track #${i + 1}: ${track.artistNames} - ${track.name}")
        }
        return interleaved.map { it.uri }
    }

    private fun artistCompositeScore(
        uri: String,
        artistScoreMap: Map<String, Double>,
        bllArtistScoreMap: Map<String, Double>,
        smartArtistScoreMap: Map<String, Double>,
        favoriteArtistUris: Set<String>,
        daypartAffinityByArtist: Map<String, Double>
    ): Double {
        val artistScore = artistScoreMap[uri] ?: bllArtistScoreMap[uri] ?: 0.0
        val smart = (smartArtistScoreMap[uri] ?: 0.0) * 0.5
        val favoriteArtistBonus = if (uri in favoriteArtistUris) FAVORITE_ARTIST_BONUS else 0.0
        val daypart = daypartBonus(daypartAffinityByArtist[uri])
        return artistScore + smart + favoriteArtistBonus + daypart
    }

    private fun daypartBonus(affinity: Double?): Double {
        if (affinity == null) return 0.0
        return ((affinity - 0.45) * 0.9).coerceIn(-0.30, 0.45)
    }

    private fun candidateArtistKey(artistUri: String): String =
        MediaIdentity.canonicalArtistKey(uri = artistUri) ?: artistUri

    private fun interleaveByArtist(
        scoredTracks: List<ScoredTrack>,
        limit: Int,
        random: Random
    ): List<Track> {
        if (scoredTracks.isEmpty()) return emptyList()
        val buckets = scoredTracks
            .groupBy { it.artistUri }
            .mapValues { (_, list) -> ArrayDeque(list) }
            .toMutableMap()
        val result = mutableListOf<Track>()
        var lastArtistUri: String? = null

        while (buckets.isNotEmpty() && result.size < limit) {
            val candidateKeys = buckets.keys.shuffled(random)
            val preferredKey = candidateKeys.firstOrNull { it != lastArtistUri } ?: candidateKeys.firstOrNull()
            val queue = preferredKey?.let { buckets[it] } ?: break
            val next = queue.removeFirstOrNull()
            if (next != null) {
                result += next.track
                lastArtistUri = preferredKey
            }
            if (queue.isEmpty()) {
                buckets.remove(preferredKey)
            }
        }
        return result
    }

    private data class ScoredTrack(
        val track: Track,
        val artistUri: String,
        val score: Double
    )
}
