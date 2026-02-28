package net.asksakis.massdroidv2.domain.model

enum class SortOption(val apiValue: String, val label: String) {
    NAME("name", "Name"),
    RECENTLY_ADDED("timestamp_added", "Recently Added"),
    LAST_PLAYED("last_played", "Last Played"),
    MOST_PLAYED("play_count", "Most Played"),
    RANDOM("random", "Random")
}

enum class LibraryDisplayMode {
    LIST, GRID
}
