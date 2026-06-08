package com.nuvio.app.features.player.cast

/** A discoverable cast receiver (e.g. a Chromecast or Cast-enabled TV). */
data class CastDevice(
    val id: String,
    val name: String,
    val modelName: String? = null,
)

/** Lifecycle of the connection to a cast receiver. */
enum class CastConnectionState {
    /** Casting is not supported on this platform/build, or no receivers are reachable. */
    Unavailable,

    /** Casting is supported and receivers may be available, but none is connected. */
    NotConnected,

    /** A connection to a receiver is being established. */
    Connecting,

    /** Connected to a receiver and ready to load/control media. */
    Connected,
}

/**
 * Everything a receiver needs to start playing a stream.
 *
 * For the default (non-custom) Cast receiver, [headers] are best-effort only: the receiver fetches
 * [url] itself, so streams whose authorization lives in the URL (debrid/tokenized links) play
 * reliably, while streams that strictly require request headers may be rejected by the receiver.
 */
data class CastMediaRequest(
    val url: String,
    val title: String,
    val subtitle: String? = null,
    val posterUrl: String? = null,
    /** MIME type, e.g. "video/mp4", "application/x-mpegURL" (HLS), "application/dash+xml" (DASH). */
    val contentType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val startPositionMs: Long = 0L,
)

/** Snapshot of remote playback on the connected receiver, mirrored back to the local UI. */
data class CastPlaybackSnapshot(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

/**
 * Guess a Cast-friendly MIME type from a stream URL. The default receiver keys playback off the
 * content type, so getting this right matters more than for local players that sniff the container.
 */
fun guessCastContentType(url: String): String {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return when {
        path.endsWith(".m3u8") -> "application/x-mpegURL"
        path.endsWith(".mpd") -> "application/dash+xml"
        path.endsWith(".mkv") -> "video/x-matroska"
        path.endsWith(".webm") -> "video/webm"
        path.endsWith(".mov") -> "video/quicktime"
        path.endsWith(".ts") -> "video/mp2t"
        else -> "video/mp4"
    }
}
