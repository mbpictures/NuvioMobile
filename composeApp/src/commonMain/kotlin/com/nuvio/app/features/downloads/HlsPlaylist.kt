package com.nuvio.app.features.downloads

/**
 * Pure-Kotlin HLS (`.m3u8`) playlist parsing used by the cross-platform download engine.
 *
 * Only the subset of RFC 8216 needed to download VOD streams to a single playable file is
 * supported: master playlists (variant selection), media playlists with MPEG-TS or fragmented-MP4
 * segments, byte-range requests, `EXT-X-MAP` initialization sections and `AES-128` encryption.
 *
 * Networking and file I/O live in the platform downloaders; this file is intentionally free of any
 * platform dependency so it can be unit tested on the JVM.
 */

internal sealed interface HlsPlaylist {
    data class Master(val variants: List<HlsVariant>) : HlsPlaylist
    data class Media(val playlist: HlsMediaPlaylist) : HlsPlaylist
}

internal data class HlsVariant(
    val url: String,
    val bandwidth: Long,
    val averageBandwidth: Long? = null,
    val resolutionHeight: Int? = null,
)

internal data class HlsByteRange(
    val length: Long,
    val offset: Long,
)

internal data class HlsSegmentEncryption(
    val method: String,
    val keyUrl: String,
    val iv: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HlsSegmentEncryption) return false
        return method == other.method &&
            keyUrl == other.keyUrl &&
            (iv?.toList() == other.iv?.toList())
    }

    override fun hashCode(): Int {
        var result = method.hashCode()
        result = 31 * result + keyUrl.hashCode()
        result = 31 * result + (iv?.toList()?.hashCode() ?: 0)
        return result
    }
}

internal data class HlsSegment(
    val url: String,
    val sequenceNumber: Long,
    val durationSeconds: Double = 0.0,
    val byteRange: HlsByteRange? = null,
    val encryption: HlsSegmentEncryption? = null,
)

internal data class HlsInitSection(
    val url: String,
    val byteRange: HlsByteRange? = null,
)

internal data class HlsMediaPlaylist(
    val segments: List<HlsSegment>,
    val initSection: HlsInitSection? = null,
    val isFmp4: Boolean = false,
)

private const val MASTER_TAG = "#EXT-X-STREAM-INF:"
private const val SEGMENT_TAG = "#EXTINF:"
private const val KEY_TAG = "#EXT-X-KEY:"
private const val MAP_TAG = "#EXT-X-MAP:"
private const val BYTERANGE_TAG = "#EXT-X-BYTERANGE:"
private const val MEDIA_SEQUENCE_TAG = "#EXT-X-MEDIA-SEQUENCE:"

/** Parses [text] (the body of a playlist fetched from [baseUrl]) into a master or media playlist. */
internal fun parseHlsPlaylist(text: String, baseUrl: String): HlsPlaylist {
    val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    val variants = parseMasterVariants(lines, baseUrl)
    val media = parseMediaPlaylist(lines, baseUrl)

    return when {
        media.segments.isNotEmpty() -> HlsPlaylist.Media(media)
        variants.isNotEmpty() -> HlsPlaylist.Master(variants)
        else -> HlsPlaylist.Media(media)
    }
}

private fun parseMasterVariants(lines: List<String>, baseUrl: String): List<HlsVariant> {
    val variants = mutableListOf<HlsVariant>()
    var pendingAttributes: Map<String, String>? = null
    for (line in lines) {
        when {
            line.startsWith(MASTER_TAG) -> {
                pendingAttributes = parseAttributeList(line.removePrefix(MASTER_TAG))
            }

            line.startsWith("#") -> {
                // Other tag between STREAM-INF and its URI is unusual; keep the pending attributes.
            }

            else -> {
                val attributes = pendingAttributes
                if (attributes != null) {
                    val averageBandwidth = attributes["AVERAGE-BANDWIDTH"]?.toLongOrNull()
                    val bandwidth = attributes["BANDWIDTH"]?.toLongOrNull()
                        ?: averageBandwidth
                        ?: 0L
                    val height = attributes["RESOLUTION"]
                        ?.substringAfter('x', "")
                        ?.toIntOrNull()
                    variants += HlsVariant(
                        url = resolveHlsUrl(baseUrl, line),
                        bandwidth = bandwidth,
                        averageBandwidth = averageBandwidth,
                        resolutionHeight = height,
                    )
                    pendingAttributes = null
                }
            }
        }
    }
    return variants
}

private fun parseMediaPlaylist(lines: List<String>, baseUrl: String): HlsMediaPlaylist {
    val segments = mutableListOf<HlsSegment>()
    var initSection: HlsInitSection? = null
    var currentEncryption: HlsSegmentEncryption? = null
    var pendingByteRange: HlsByteRange? = null
    var pendingSegment = false
    var pendingDuration = 0.0
    var sequenceNumber = 0L
    var nextOffset = 0L
    var sawFmp4Hint = false

    for (line in lines) {
        when {
            line.startsWith(MEDIA_SEQUENCE_TAG) -> {
                sequenceNumber = line.removePrefix(MEDIA_SEQUENCE_TAG).trim().toLongOrNull() ?: 0L
            }

            line.startsWith(KEY_TAG) -> {
                currentEncryption = parseKey(line.removePrefix(KEY_TAG), baseUrl)
            }

            line.startsWith(MAP_TAG) -> {
                val attributes = parseAttributeList(line.removePrefix(MAP_TAG))
                val uri = attributes["URI"]
                if (!uri.isNullOrBlank()) {
                    initSection = HlsInitSection(
                        url = resolveHlsUrl(baseUrl, uri),
                        byteRange = attributes["BYTERANGE"]?.let { parseByteRange(it, null) },
                    )
                    sawFmp4Hint = true
                }
            }

            line.startsWith(BYTERANGE_TAG) -> {
                pendingByteRange = parseByteRange(line.removePrefix(BYTERANGE_TAG), nextOffset)
            }

            line.startsWith(SEGMENT_TAG) -> {
                pendingSegment = true
                pendingDuration = line.removePrefix(SEGMENT_TAG)
                    .substringBefore(',')
                    .trim()
                    .toDoubleOrNull()
                    ?: 0.0
            }

            line.startsWith("#") -> {
                // Ignore unsupported tags (#EXTM3U, #EXT-X-VERSION, #EXT-X-ENDLIST, ...).
            }

            else -> {
                if (pendingSegment) {
                    if (!sawFmp4Hint && looksLikeFmp4Segment(line)) {
                        sawFmp4Hint = true
                    }
                    segments += HlsSegment(
                        url = resolveHlsUrl(baseUrl, line),
                        sequenceNumber = sequenceNumber,
                        durationSeconds = pendingDuration,
                        byteRange = pendingByteRange,
                        encryption = currentEncryption,
                    )
                    pendingByteRange?.let { nextOffset = it.offset + it.length }
                    pendingByteRange = null
                    pendingSegment = false
                    pendingDuration = 0.0
                    sequenceNumber += 1
                }
            }
        }
    }

    return HlsMediaPlaylist(
        segments = segments,
        initSection = initSection,
        isFmp4 = sawFmp4Hint,
    )
}

private fun parseKey(attributesText: String, baseUrl: String): HlsSegmentEncryption? {
    val attributes = parseAttributeList(attributesText)
    val method = attributes["METHOD"]?.trim().orEmpty()
    if (method.isEmpty() || method.equals("NONE", ignoreCase = true)) return null
    val uri = attributes["URI"]?.takeIf { it.isNotBlank() } ?: return null
    return HlsSegmentEncryption(
        method = method.uppercase(),
        keyUrl = resolveHlsUrl(baseUrl, uri),
        iv = attributes["IV"]?.let { parseHexBytes(it) },
    )
}

private fun parseByteRange(value: String, fallbackOffset: Long?): HlsByteRange? {
    val trimmed = value.trim().substringBefore(',')
    if (trimmed.isEmpty()) return null
    val length = trimmed.substringBefore('@').trim().toLongOrNull() ?: return null
    val offset = if (trimmed.contains('@')) {
        trimmed.substringAfter('@').trim().toLongOrNull() ?: fallbackOffset ?: 0L
    } else {
        fallbackOffset ?: 0L
    }
    return HlsByteRange(length = length, offset = offset)
}

private fun looksLikeFmp4Segment(uriLine: String): Boolean {
    val path = uriLine.substringBefore('#').substringBefore('?').lowercase()
    return path.endsWith(".mp4") ||
        path.endsWith(".m4s") ||
        path.endsWith(".m4v") ||
        path.endsWith(".cmfv") ||
        path.endsWith(".cmfa") ||
        path.endsWith(".fmp4")
}

/** Builds the 16-byte AES IV from a segment's media sequence number (RFC 8216 §5.2). */
internal fun hlsSequenceIv(sequenceNumber: Long): ByteArray {
    val iv = ByteArray(16)
    var value = sequenceNumber
    for (i in 0 until 8) {
        iv[15 - i] = (value and 0xFF).toByte()
        value = value ushr 8
    }
    return iv
}

/** Parses a hex string (optionally `0x`-prefixed) into bytes; returns null on malformed input. */
internal fun parseHexBytes(input: String): ByteArray? {
    val cleaned = input.trim().removePrefix("0x").removePrefix("0X")
    if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
    val out = ByteArray(cleaned.length / 2)
    for (i in out.indices) {
        val high = cleaned[i * 2].digitToIntOrNull(16) ?: return null
        val low = cleaned[i * 2 + 1].digitToIntOrNull(16) ?: return null
        out[i] = ((high shl 4) or low).toByte()
    }
    return out
}

internal fun String.isHlsPlaylistUrl(): Boolean {
    val normalized = trim().lowercase().substringBefore('#')
    val path = normalized.substringBefore('?')
    if (path.endsWith(".m3u8") || path.endsWith(".m3u")) return true
    return normalized.contains(".m3u8")
}

/**
 * Resolves [reference] against [baseUrl] following the relevant parts of RFC 3986. Handles absolute
 * URLs, scheme-relative (`//host/...`), absolute paths (`/...`) and relative references with `.`/`..`
 * normalization, while preserving any query string carried by the reference.
 */
internal fun resolveHlsUrl(baseUrl: String, reference: String): String {
    val ref = reference.trim()
    if (ref.isEmpty()) return baseUrl.trim()
    if (ABSOLUTE_URL_REGEX.containsMatchIn(ref)) return ref

    val base = baseUrl.trim()
    val schemeSeparator = base.indexOf("://")
    if (schemeSeparator <= 0) return ref

    val scheme = base.substring(0, schemeSeparator)
    if (ref.startsWith("//")) return "$scheme:$ref"

    val afterScheme = base.substring(schemeSeparator + 3)
    val firstSlash = afterScheme.indexOf('/')
    val authority = if (firstSlash == -1) afterScheme else afterScheme.substring(0, firstSlash)
    val basePath = if (firstSlash == -1) "" else afterScheme.substring(firstSlash)

    val refPath = ref.substringBefore('?').substringBefore('#')
    val refSuffix = ref.substring(refPath.length)

    val resolvedPath = if (ref.startsWith("/")) {
        normalizePathSegments(refPath)
    } else {
        val baseDirectory = basePath
            .substringBefore('?')
            .substringBefore('#')
            .let { if (it.isEmpty()) "/" else it.substringBeforeLast('/', "") + "/" }
        normalizePathSegments(baseDirectory + refPath)
    }

    return "$scheme://$authority$resolvedPath$refSuffix"
}

private fun normalizePathSegments(path: String): String {
    val leadingSlash = path.startsWith("/")
    val trailingSlash = path.endsWith("/")
    val stack = ArrayDeque<String>()
    for (part in path.split('/')) {
        when (part) {
            "", "." -> Unit
            ".." -> if (stack.isNotEmpty()) stack.removeLast()
            else -> stack.addLast(part)
        }
    }
    val joined = stack.joinToString("/")
    return buildString {
        if (leadingSlash) append('/')
        append(joined)
        if (trailingSlash && joined.isNotEmpty()) append('/')
    }
}

/** Parses an HLS attribute list, honoring quoted values that may contain commas. */
private fun parseAttributeList(input: String): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    var index = 0
    val length = input.length
    while (index < length) {
        val keyStart = index
        while (index < length && input[index] != '=') index++
        if (index >= length) break
        val key = input.substring(keyStart, index).trim()
        index++ // skip '='

        val value: String
        if (index < length && input[index] == '"') {
            index++
            val valueStart = index
            while (index < length && input[index] != '"') index++
            value = input.substring(valueStart, index)
            if (index < length) index++ // skip closing quote
        } else {
            val valueStart = index
            while (index < length && input[index] != ',') index++
            value = input.substring(valueStart, index).trim()
        }

        if (key.isNotEmpty()) result[key] = value

        while (index < length && input[index] != ',') index++
        if (index < length) index++ // skip ','
    }
    return result
}

private val ABSOLUTE_URL_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://")
