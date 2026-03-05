package net.asksakis.massdroidv2.ui.components

fun formatAlbumTypeYear(albumType: String?, year: Int?): String {
    val typeLabel = formatAlbumTypeLabel(albumType)
    return listOfNotNull(typeLabel, year?.toString()).joinToString(" \u00b7 ")
}

private fun formatAlbumTypeLabel(albumType: String?): String? {
    val normalized = albumType?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    return when (normalized) {
        "album", "lp" -> "Album"
        "single" -> "Single"
        "ep", "e.p." -> "EP"
        else -> normalized
            .replace('_', ' ')
            .replace('-', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                if (part == "ep") "EP" else part.replaceFirstChar { ch -> ch.uppercase() }
            }
            .ifBlank { null }
    }
}
