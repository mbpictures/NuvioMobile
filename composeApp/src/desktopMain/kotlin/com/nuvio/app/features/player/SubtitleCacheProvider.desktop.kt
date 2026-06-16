package com.nuvio.app.features.player

/**
 * Desktop implementation: external players on desktop accept remote subtitle URLs
 * directly, so no local caching is required — returns the input unchanged.
 */
actual object SubtitleCacheProvider {
    actual suspend fun cacheForExternalPlayer(subtitles: List<SubtitleInput>): List<SubtitleInput>? {
        return subtitles
    }
}
