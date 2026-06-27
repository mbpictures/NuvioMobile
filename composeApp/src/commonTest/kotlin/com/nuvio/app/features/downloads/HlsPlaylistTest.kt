package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HlsPlaylistTest {

    @Test
    fun `detects hls playlist urls`() {
        assertTrue("https://cdn.example.com/path/index.m3u8".isHlsPlaylistUrl())
        assertTrue("https://cdn.example.com/index.m3u8?token=abc".isHlsPlaylistUrl())
        assertTrue("https://cdn.example.com/master.m3u".isHlsPlaylistUrl())
        assertFalse("https://cdn.example.com/video.mp4".isHlsPlaylistUrl())
        assertFalse("https://cdn.example.com/stream.mpd".isHlsPlaylistUrl())
    }

    @Test
    fun `resolves relative absolute scheme-relative and dot-dot references`() {
        val base = "https://h.com/a/b/playlist.m3u8?token=1"
        assertEquals("https://h.com/a/b/seg0.ts?token=2", resolveHlsUrl(base, "seg0.ts?token=2"))
        assertEquals("https://h.com/x/seg.ts", resolveHlsUrl(base, "/x/seg.ts"))
        assertEquals("https://h.com/a/c/seg.ts", resolveHlsUrl(base, "../c/seg.ts"))
        assertEquals("https://cdn.com/s.ts", resolveHlsUrl(base, "//cdn.com/s.ts"))
        assertEquals("https://other.com/abs.ts", resolveHlsUrl(base, "https://other.com/abs.ts"))
    }

    @Test
    fun `parses master playlist and exposes variants for highest-bandwidth selection`() {
        val playlist = parseHlsPlaylist(
            text = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
                low/index.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720
                mid/index.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080
                hi/index.m3u8
            """.trimIndent(),
            baseUrl = "https://cdn.example.com/path/master.m3u8",
        )

        val master = playlist as HlsPlaylist.Master
        assertEquals(3, master.variants.size)
        val best = master.variants.maxByOrNull { it.bandwidth }!!
        assertEquals(4_500_000L, best.bandwidth)
        assertEquals(1080, best.resolutionHeight)
        assertEquals("https://cdn.example.com/path/hi/index.m3u8", best.url)
    }

    @Test
    fun `parses media playlist with ts segments and sequence numbers`() {
        val playlist = parseHlsPlaylist(
            text = """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-TARGETDURATION:10
                #EXT-X-MEDIA-SEQUENCE:0
                #EXTINF:9.009,
                seg0.ts
                #EXTINF:9.009,
                seg1.ts
                #EXT-X-ENDLIST
            """.trimIndent(),
            baseUrl = "https://cdn.example.com/v/playlist.m3u8",
        )

        val media = (playlist as HlsPlaylist.Media).playlist
        assertFalse(media.isFmp4)
        assertNull(media.initSection)
        assertEquals(2, media.segments.size)
        assertEquals("https://cdn.example.com/v/seg0.ts", media.segments[0].url)
        assertEquals(0L, media.segments[0].sequenceNumber)
        assertEquals(1L, media.segments[1].sequenceNumber)
        assertEquals(9.009, media.segments[0].durationSeconds)
        assertNull(media.segments[0].encryption)
    }

    @Test
    fun `declared total bytes is a fixed value from bitrate and duration`() {
        // 8_000_000 bits/s ÷ 8 × 600s = 600_000_000 bytes — a single stable estimate, independent of
        // how big individual segments turn out to be.
        assertEquals(600_000_000L, declaredTotalBytes(bitsPerSecond = 8_000_000L, totalDuration = 600.0))
        // No bitrate (direct media playlist) or no duration → no estimate (indeterminate indicator).
        assertNull(declaredTotalBytes(bitsPerSecond = null, totalDuration = 600.0))
        assertNull(declaredTotalBytes(bitsPerSecond = 8_000_000L, totalDuration = 0.0))
    }

    @Test
    fun `master playlist exposes average bandwidth when present`() {
        val playlist = parseHlsPlaylist(
            text = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=6221600,AVERAGE-BANDWIDTH=5000000,RESOLUTION=1920x1080
                hi/index.m3u8
            """.trimIndent(),
            baseUrl = "https://cdn.example.com/master.m3u8",
        )
        val variant = (playlist as HlsPlaylist.Master).variants.single()
        assertEquals(6_221_600L, variant.bandwidth)
        assertEquals(5_000_000L, variant.averageBandwidth)
    }

    @Test
    fun `parses aes-128 key with explicit iv`() {
        val playlist = parseHlsPlaylist(
            text = """
                #EXTM3U
                #EXT-X-MEDIA-SEQUENCE:10
                #EXT-X-KEY:METHOD=AES-128,URI="https://k.example.com/key.bin",IV=0x00000000000000000000000000000010
                #EXTINF:6,
                seg10.ts
                #EXTINF:6,
                seg11.ts
            """.trimIndent(),
            baseUrl = "https://cdn.example.com/v/playlist.m3u8",
        )

        val media = (playlist as HlsPlaylist.Media).playlist
        val encryption = media.segments[0].encryption!!
        assertEquals("AES-128", encryption.method)
        assertEquals("https://k.example.com/key.bin", encryption.keyUrl)
        assertContentEquals(hlsSequenceIv(16), encryption.iv)
        assertEquals(10L, media.segments[0].sequenceNumber)
        assertEquals(11L, media.segments[1].sequenceNumber)
    }

    @Test
    fun `aes-128 key without iv leaves iv null for sequence derivation`() {
        val playlist = parseHlsPlaylist(
            text = """
                #EXTM3U
                #EXT-X-MEDIA-SEQUENCE:5
                #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
                #EXTINF:6,
                seg5.ts
            """.trimIndent(),
            baseUrl = "https://cdn.example.com/v/playlist.m3u8",
        )

        val media = (playlist as HlsPlaylist.Media).playlist
        val encryption = media.segments[0].encryption!!
        assertEquals("https://cdn.example.com/v/key.bin", encryption.keyUrl)
        assertNull(encryption.iv)
        assertEquals(5L, media.segments[0].sequenceNumber)
    }

    @Test
    fun `key method none clears encryption`() {
        val playlist = parseHlsPlaylist(
            text = """
                #EXTM3U
                #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
                #EXTINF:6,
                enc.ts
                #EXT-X-KEY:METHOD=NONE
                #EXTINF:6,
                clear.ts
            """.trimIndent(),
            baseUrl = "https://cdn.example.com/v/playlist.m3u8",
        )

        val media = (playlist as HlsPlaylist.Media).playlist
        assertEquals("AES-128", media.segments[0].encryption?.method)
        assertNull(media.segments[1].encryption)
    }

    @Test
    fun `parses fragmented mp4 with init section`() {
        val playlist = parseHlsPlaylist(
            text = """
                #EXTM3U
                #EXT-X-MAP:URI="init.mp4"
                #EXTINF:4,
                seg0.m4s
                #EXTINF:4,
                seg1.m4s
            """.trimIndent(),
            baseUrl = "https://cdn.example.com/v/playlist.m3u8",
        )

        val media = (playlist as HlsPlaylist.Media).playlist
        assertTrue(media.isFmp4)
        assertEquals("https://cdn.example.com/v/init.mp4", media.initSection?.url)
        assertEquals(2, media.segments.size)
    }

    @Test
    fun `parses byte ranges with implicit offsets`() {
        val playlist = parseHlsPlaylist(
            text = """
                #EXTM3U
                #EXT-X-MEDIA-SEQUENCE:0
                #EXTINF:4,
                #EXT-X-BYTERANGE:1000@0
                media.ts
                #EXTINF:4,
                #EXT-X-BYTERANGE:1000
                media.ts
            """.trimIndent(),
            baseUrl = "https://cdn.example.com/v/playlist.m3u8",
        )

        val media = (playlist as HlsPlaylist.Media).playlist
        assertEquals(HlsByteRange(length = 1000, offset = 0), media.segments[0].byteRange)
        assertEquals(HlsByteRange(length = 1000, offset = 1000), media.segments[1].byteRange)
    }

    @Test
    fun `derives 16-byte iv from media sequence number`() {
        val iv = hlsSequenceIv(0x0102L)
        assertEquals(16, iv.size)
        assertEquals(0x01.toByte(), iv[14])
        assertEquals(0x02.toByte(), iv[15])
        for (i in 0 until 14) assertEquals(0.toByte(), iv[i])
    }

    @Test
    fun `parses hex bytes with and without prefix`() {
        assertContentEquals(byteArrayOf(0x00, 0x10), parseHexBytes("0x0010"))
        assertContentEquals(byteArrayOf(0xAB.toByte(), 0xCD.toByte()), parseHexBytes("ABCD"))
        assertNull(parseHexBytes("0xABC"))
        assertNull(parseHexBytes("xy"))
    }

    @Test
    fun `output file name swaps extension to match container`() {
        assertEquals("Movie_abc.ts", hlsOutputFileName("Movie_abc.m3u8", isFmp4 = false))
        assertEquals("Movie_abc.mp4", hlsOutputFileName("Movie_abc.ts", isFmp4 = true))
        assertEquals("noext.ts", hlsOutputFileName("noext", isFmp4 = false))
    }
}
