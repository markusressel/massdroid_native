# Genre Enrichment via Last.fm

## Problem

Genre data availability depends on the music provider. Spotify provides genres only on artists (very granular), Tidal on tracks/albums (generic), Deezer almost nothing, local files only if ID3 tags exist. Many tracks end up with no genres, weakening Genre Radio, Smart Mix, and recommendation quality.

## Solution

Use Last.fm `artist.getTopTags` as fallback when MA server returns no genres for an artist. Artist-level tags are the most reliable source: available for almost every artist, weighted by community votes (0-100), and genre-focused.

## Last.fm API

```
GET https://ws.audioscrobbler.com/2.0/
  ?method=artist.getTopTags
  &artist={name}
  &api_key={key}
  &format=json
```

**API Key**: `b51aec46303940acc5bb7813f50b924d`
**Rate limit**: 5 req/sec
**Auth**: API key only (no session/signature needed)

### Response

```json
{
  "toptags": {
    "tag": [
      { "name": "techno", "count": "100" },
      { "name": "House", "count": "70" },
      { "name": "electronic", "count": "35" }
    ]
  }
}
```

### What we keep

Top 5 tags with count >= 5. This filters out noise like artist names, cities, label names used as tags.

## Test Results

| Artist | Last.fm top tags |
|--------|-----------------|
| Carl Cox | techno (100), House (70), electronic (35) |
| Solomun | deep house (100), minimal (98), tech house (51), Progressive House (27) |
| Boris Brejcha | minimal (100), minimal techno (77), techno (47) |
| Amelie Lens | techno (100), trance (30), electronic (11) |
| Nina Kraviz | techno (100), deep house (74), House (35) |
| Fritz Kalkbrenner | minimal (100), electronic (99), techno (42), german (31) |
| Bonobo | downtempo (100), electronic (81), trip-hop (58) |
| Radiohead | rock (100), alternative (96), alternative rock (88) |
| Khruangbin | funk (100), psychedelic (84), Psychedelic Rock (56) |

Track-level tags (`track.getTopTags`) are almost always empty for non-mainstream tracks. Artist tags are the reliable source.

## Architecture

### Current genre flow (unchanged)

```
QUEUE_UPDATED event
  → track metadata.genres (inline from server)
  → artistGenreCache (in-memory, from previous lookups)
  → music/tracks/get metadata.genres
  → music/albums/get metadata.genres
  → music/artists/get metadata.genres
```

### New: Last.fm fallback (added at the end)

```
  → (all MA sources empty for this artist)
  → Room cache lookup (lastfm_artist_tags table)
    → hit + fresh (< 30 days): use cached tags
    → hit + stale (> 30 days): use cached, refresh async
    → miss: fetch from Last.fm API, store in Room, use tags
```

Last.fm is ONLY queried when:
1. MA server returned no genres for the artist
2. The artist is not already cached in Room (or cache is stale)

### Data flow diagram

```
PlayerRepositoryImpl.fetchAndApplyGenres()
    │
    ├─ MA artist GET → metadata.genres
    │   └─ found? → use, cache in artistGenreCache, done
    │
    └─ (empty) → LastFmGenreResolver.resolve(artistName)
                    │
                    ├─ Room lookup (lastfm_artist_tags)
                    │   └─ fresh hit? → return cached tags
                    │
                    └─ HTTP GET artist.getTopTags
                        │
                        ├─ filter: count >= 5, top 5
                        ├─ store in Room
                        └─ return tags
```

## Database

### New table: `lastfm_artist_tags`

```sql
CREATE TABLE lastfm_artist_tags (
    artist_name TEXT NOT NULL PRIMARY KEY,
    tags TEXT NOT NULL,           -- comma-separated: "techno,house,electronic"
    fetched_at INTEGER NOT NULL   -- epoch millis
);
```

Room entity:

```kotlin
@Entity(tableName = "lastfm_artist_tags")
data class LastFmArtistTagsEntity(
    @PrimaryKey
    @ColumnInfo(name = "artist_name") val artistName: String,
    val tags: String,              // comma-separated
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long
)
```

Cache policy: 30 days. After that, cached value is still used but a background refresh is scheduled.

Migration: v2 -> v3, single CREATE TABLE statement.

## New class: `LastFmGenreResolver`

```kotlin
@Singleton
class LastFmGenreResolver @Inject constructor(
    private val dao: PlayHistoryDao,
    private val okHttpClient: OkHttpClient,
    private val json: Json
) {
    companion object {
        private const val API_KEY = "b51aec46303940acc5bb7813f50b924d"
        private const val BASE_URL = "https://ws.audioscrobbler.com/2.0/"
        private const val CACHE_DAYS = 30L
        private const val MIN_TAG_COUNT = 5
        private const val MAX_TAGS = 5
    }

    suspend fun resolve(artistName: String): List<String> {
        // 1. Room cache check
        val cached = dao.getLastFmTags(artistName)
        if (cached != null) {
            val age = System.currentTimeMillis() - cached.fetchedAt
            if (age < CACHE_DAYS * 86_400_000L) {
                return cached.tags.split(",").filter { it.isNotBlank() }
            }
            // Stale: return cached, refresh in background
            // (caller handles background refresh)
        }

        // 2. Fetch from Last.fm
        return fetchFromApi(artistName)
    }

    private suspend fun fetchFromApi(artistName: String): List<String> {
        val url = "$BASE_URL?method=artist.getTopTags" +
            "&artist=${URLEncoder.encode(artistName, "UTF-8")}" +
            "&api_key=$API_KEY&format=json"

        val request = Request.Builder().url(url)
            .header("User-Agent", "MassDroid/1.0")
            .build()

        return withContext(Dispatchers.IO) {
            try {
                val response = okHttpClient.newCall(request).execute()
                response.body?.use { body ->
                    val root = json.parseToJsonElement(body.string()).jsonObject
                    val tags = root["toptags"]?.jsonObject?.get("tag")
                        ?.jsonArray?.mapNotNull { el ->
                            val obj = el.jsonObject
                            val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                            val count = obj["count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                            if (count >= MIN_TAG_COUNT) name else null
                        }?.take(MAX_TAGS) ?: emptyList()

                    // Store in Room
                    val tagStr = tags.joinToString(",")
                    dao.insertLastFmTags(
                        LastFmArtistTagsEntity(
                            artistName = artistName,
                            tags = tagStr,
                            fetchedAt = System.currentTimeMillis()
                        )
                    )
                    tags
                } ?: emptyList()
            } catch (e: Exception) {
                Log.w("LastFmGenreResolver", "Failed for $artistName: ${e.message}")
                emptyList()
            }
        }
    }
}
```

## Integration point

In `PlayerRepositoryImpl.fetchAndApplyGenres()`, after the existing MA artist genre fetch loop returns empty:

```kotlin
// Existing code: MA sources all returned empty
// ...

// NEW: Last.fm fallback
if (allGenres.isEmpty()) {
    uncachedArtists.forEach { artist ->
        val lastFmTags = lastFmGenreResolver.resolve(artist.name)
        if (lastFmTags.isNotEmpty()) {
            allGenres.addAll(lastFmTags)
            artistGenreCache[artist.uri] = lastFmTags
        }
    }
}
```

Also in `enrichTrackGenresForHistory()`, as final fallback after the artist metadata loop:

```kotlin
// After all MA sources exhausted
if (mergedGenres.isEmpty()) {
    artists.forEach { (_, artistName) ->
        val lastFmTags = lastFmGenreResolver.resolve(artistName)
        mergedGenres.addAll(lastFmTags)
    }
}
```

## Files to change

| Order | File | Change |
|-------|------|--------|
| 1 | `Entities.kt` | Add `LastFmArtistTagsEntity` |
| 2 | `PlayHistoryDao.kt` | Add `insertLastFmTags()`, `getLastFmTags()` |
| 3 | `AppDatabase.kt` | Add entity to `@Database`, migration v2->v3 |
| 4 | `LastFmGenreResolver.kt` (new) | Last.fm API client with Room cache |
| 5 | `AppModule.kt` | Provide `LastFmGenreResolver` |
| 6 | `PlayerRepositoryImpl.kt` | Inject resolver, add fallback calls |

## Non-goals

- No genre normalization mapping in this phase (e.g. "progressive electro house" -> "House")
- No track-level Last.fm lookups (almost always empty)
- No UI changes (genres flow into existing Genre Radio / Smart Mix / Recommendations)
- No changes to the MA server genre flow (remains primary source)

## Risks

- **Last.fm API stability**: Free tier, no SLA. Mitigated by Room cache (app works fine without Last.fm).
- **Non-genre tags**: Last.fm tags include things like city names ("Berlin"), labels ("Harthouse"), and artist names. The `count >= 5` filter removes most noise. Future improvement: maintain a blocklist of non-genre tags.
- **Rate limiting**: 5 req/sec is generous. We fetch one artist at a time during queue updates, well within limits.
- **Name matching**: Last.fm matches by artist name string. Misspelled or localized names may fail. Falls back to empty gracefully.
