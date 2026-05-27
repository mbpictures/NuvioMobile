package com.nuvio.app.features.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.skia.Bitmap as SkiaBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

internal object WindowsMpvPlayerBackend : DesktopPlaybackBackend {
    @Composable
    override fun PlayerSurface(
        sourceUrl: String,
        sourceAudioUrl: String?,
        sourceHeaders: Map<String, String>,
        sourceResponseHeaders: Map<String, String>,
        useYoutubeChunkedPlayback: Boolean,
        modifier: Modifier,
        playWhenReady: Boolean,
        resizeMode: PlayerResizeMode,
        useNativeController: Boolean,
        onControllerReady: (PlayerEngineController) -> Unit,
        onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
        onError: (String?) -> Unit,
    ) {
        var session by remember { mutableStateOf<MpvSession?>(null) }
        var initFailure by remember { mutableStateOf<String?>(null) }
        var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
        var frame by remember { mutableStateOf<ImageBitmap?>(null) }

        DisposableEffect(Unit) {
            val createdRef = arrayOfNulls<MpvSession>(1)
            try {
                val created = MpvSession(LibMpv.INSTANCE)
                createdRef[0] = created
                session = created
            } catch (t: Throwable) {
                initFailure = t.message ?: "Failed to initialize libmpv"
            }
            onDispose {
                createdRef[0]?.dispose()
                session = null
            }
        }

        LaunchedEffect(initFailure) {
            initFailure?.let(onError)
        }

        LaunchedEffect(session, sourceUrl, sourceAudioUrl, sourceHeaders) {
            session?.load(sourceUrl, sourceAudioUrl, sourceHeaders, playWhenReady)
        }

        LaunchedEffect(session, playWhenReady) {
            session?.setPaused(!playWhenReady)
        }

        LaunchedEffect(session, resizeMode) {
            session?.setResizeMode(resizeMode)
        }

        val controller = remember(session) {
            session?.let { sess ->
                object : PlayerEngineController {
                    override fun play() = sess.setPaused(false)
                    override fun pause() = sess.setPaused(true)
                    override fun seekTo(positionMs: Long) = sess.seekTo(positionMs)
                    override fun seekBy(offsetMs: Long) = sess.seekBy(offsetMs)
                    override fun retry() = sess.retry()
                    override fun setPlaybackSpeed(speed: Float) = sess.setSpeed(speed)
                    override fun getAudioTracks(): List<AudioTrack> = sess.audioTracks()
                    override fun getSubtitleTracks(): List<SubtitleTrack> = sess.subtitleTracks()
                    override fun selectAudioTrack(index: Int) = sess.selectAudioTrack(index)
                    override fun selectSubtitleTrack(index: Int) = sess.selectSubtitleTrack(index)
                    override fun setSubtitleUri(url: String) = sess.addExternalSubtitle(url)
                    override fun clearExternalSubtitle() = sess.clearExternalSubtitle()
                    override fun clearExternalSubtitleAndSelect(trackIndex: Int) =
                        sess.clearExternalSubtitleAndSelect(trackIndex)
                    override fun applySubtitleStyle(style: SubtitleStyleState) = sess.applySubtitleStyle(style)
                    override fun setSubtitleDelayMs(delayMs: Int) = sess.setSubtitleDelayMs(delayMs)
                }
            }
        }

        LaunchedEffect(controller) {
            controller?.let(onControllerReady)
        }

        LaunchedEffect(session) {
            val s = session ?: return@LaunchedEffect
            while (isActive) {
                delay(250)
                onSnapshot(s.snapshot())
                onError(s.consumeError())
            }
        }

        LaunchedEffect(session, surfaceSize) {
            val s = session ?: return@LaunchedEffect
            if (surfaceSize.width <= 0 || surfaceSize.height <= 0) return@LaunchedEffect
            while (isActive) {
                if (s.hasNewFrame()) {
                    val rendered = s.renderFrame(surfaceSize.width, surfaceSize.height)
                    if (rendered != null) frame = rendered
                }
                delay(8)
            }
        }

        Box(
            modifier = modifier
                .background(Color.Black)
                .onSizeChanged { surfaceSize = it },
            contentAlignment = Alignment.Center,
        ) {
            frame?.let { bm ->
                Image(
                    bitmap = bm,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = when (resizeMode) {
                        PlayerResizeMode.Fit -> ContentScale.Fit
                        PlayerResizeMode.Fill -> ContentScale.FillBounds
                        PlayerResizeMode.Zoom -> ContentScale.Crop
                    },
                )
            }
        }
    }
}

internal class MpvSession(
    private val mpv: LibMpv,
    private val options: MpvSessionOptions = MpvSessionOptions(),
) {
    val handle: Pointer
    private var renderCtx: Pointer? = null
    private val shutdown = AtomicBoolean(false)
    private val eventThread: Thread
    @Volatile private var pendingError: String? = null
    @Volatile private var reportedError: String? = null
    private val frameReady = AtomicBoolean(false)
    private val json = Json { ignoreUnknownKeys = true }

    private var pixelBuffer: Memory? = null
    private var pixelWidth: Int = 0
    private var pixelHeight: Int = 0
    private val swSize = Memory(8)
    private val swStride = Memory(8)
    private val swFormat = Memory(("bgra".length + 1).toLong()).apply {
        setString(0, "bgra")
    }

    private val updateCallback = object : MpvRenderUpdateCallback {
        override fun invoke(ctx: Pointer?) {
            frameReady.set(true)
        }
    }

    init {
        val created = mpv.mpv_create() ?: throw IllegalStateException("mpv_create returned NULL")
        handle = created
        try {
            mpv.mpv_set_option_string(handle, "vo", "libmpv")
            mpv.mpv_set_option_string(handle, "osc", "no")
            mpv.mpv_set_option_string(handle, "input-default-bindings", "no")
            mpv.mpv_set_option_string(handle, "input-vo-keyboard", "no")
            mpv.mpv_set_option_string(handle, "input-cursor", "no")
            mpv.mpv_set_option_string(handle, "cursor-autohide", "no")
            mpv.mpv_set_option_string(handle, "keep-open", "yes")
            mpv.mpv_set_option_string(handle, "idle", "yes")
            mpv.mpv_set_option_string(handle, "hwdec", options.hwdec)
            mpv.mpv_set_option_string(handle, "ytdl", "no")
            mpv.mpv_set_option_string(handle, "terminal", "no")
            mpv.mpv_set_option_string(handle, "audio-client-name", "Nuvio")
            mpv.mpv_set_option_string(handle, "user-agent", options.userAgent)

            val rc = mpv.mpv_initialize(handle)
            if (rc < 0) throw IllegalStateException("mpv_initialize failed: ${errorText(rc)}")

            if (options.logLevel != null) {
                mpv.mpv_request_log_messages(handle, options.logLevel)
            }

            createRenderContext()
        } catch (t: Throwable) {
            renderCtx?.let { mpv.mpv_render_context_free(it) }
            mpv.mpv_terminate_destroy(handle)
            throw t
        }

        eventThread = Thread({ runEventLoop() }, "mpv-events").apply {
            isDaemon = true
            start()
        }
    }

    private fun createRenderContext() {
        val apiTypeStr = Memory(("sw".length + 1).toLong()).apply { setString(0, "sw") }
        val params = MpvRenderParam().toArrayContiguous(2)
        params[0].type = MPV_RENDER_PARAM_API_TYPE
        params[0].data = apiTypeStr
        params[1].type = 0
        params[1].data = null
        params.forEach { it.write() }

        val out = PointerByReference()
        val rc = mpv.mpv_render_context_create(out, handle, params[0].pointer)
        if (rc < 0) throw IllegalStateException("mpv_render_context_create failed: ${errorText(rc)}")
        val ctx = out.value ?: throw IllegalStateException("mpv_render_context_create returned NULL")
        renderCtx = ctx
        mpv.mpv_render_context_set_update_callback(ctx, updateCallback, null)
    }

    fun dispose() {
        if (shutdown.getAndSet(true)) return
        renderCtx?.let { ctx ->
            runCatching { mpv.mpv_render_context_set_update_callback(ctx, null, null) }
            runCatching { mpv.mpv_render_context_free(ctx) }
        }
        renderCtx = null
        runCatching { mpv.mpv_command_string(handle, "quit") }
        runCatching { eventThread.join(500) }
        runCatching { mpv.mpv_terminate_destroy(handle) }
        pixelBuffer = null
    }

    fun hasNewFrame(): Boolean = frameReady.getAndSet(false)

    fun renderFrame(width: Int, height: Int): ImageBitmap? {
        val ctx = renderCtx ?: return null
        if (width <= 0 || height <= 0) return null

        val stride = width.toLong() * 4L
        val needed = stride * height
        var buffer = pixelBuffer
        if (buffer == null || pixelWidth != width || pixelHeight != height) {
            buffer = Memory(needed)
            pixelBuffer = buffer
            pixelWidth = width
            pixelHeight = height
        }

        swSize.setInt(0, width)
        swSize.setInt(4, height)
        swStride.setLong(0, stride)

        val params = MpvRenderParam().toArrayContiguous(5)
        params[0].type = MPV_RENDER_PARAM_SW_SIZE
        params[0].data = swSize
        params[1].type = MPV_RENDER_PARAM_SW_FORMAT
        params[1].data = swFormat
        params[2].type = MPV_RENDER_PARAM_SW_STRIDE
        params[2].data = swStride
        params[3].type = MPV_RENDER_PARAM_SW_POINTER
        params[3].data = buffer
        params[4].type = 0
        params[4].data = null
        params.forEach { it.write() }

        val rc = mpv.mpv_render_context_render(ctx, params[0].pointer)
        if (rc < 0) return null

        val bytes = buffer.getByteArray(0, needed.toInt())
        val skiaBitmap = SkiaBitmap()
        val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
        skiaBitmap.setImageInfo(info)
        if (!skiaBitmap.installPixels(info, bytes, stride.toInt())) {
            return null
        }
        return skiaBitmap.asComposeImageBitmap()
    }

    fun load(url: String, audioUrl: String?, headers: Map<String, String>, playWhenReady: Boolean) {
        val headerValue = headers
            .filterValues { it.isNotBlank() }
            .filterKeys { it.isNotBlank() && !it.equals("Range", ignoreCase = true) }
            .entries
            .joinToString(",") { (k, v) -> "${k.trim()}: ${v.trim()}" }
        mpv.mpv_set_property_string(handle, "http-header-fields", headerValue)
        headers["User-Agent"]?.takeIf { it.isNotBlank() }
            ?.let { mpv.mpv_set_property_string(handle, "user-agent", it) }
        headers["Referer"]?.takeIf { it.isNotBlank() }
            ?.let { mpv.mpv_set_property_string(handle, "referrer", it) }
        mpv.mpv_set_property_string(handle, "pause", if (playWhenReady) "no" else "yes")

        val args = buildList {
            add("loadfile")
            add(url)
            add("replace")
            if (!audioUrl.isNullOrBlank()) add("audio-files-append=$audioUrl")
        }
        command(args)
        reportedError = null
        pendingError = null
    }

    fun setPaused(paused: Boolean) {
        mpv.mpv_set_property_string(handle, "pause", if (paused) "yes" else "no")
    }

    fun seekTo(positionMs: Long) {
        command(listOf("seek", (positionMs / 1000.0).toString(), "absolute", "exact"))
    }

    fun seekBy(offsetMs: Long) {
        command(listOf("seek", (offsetMs / 1000.0).toString(), "relative", "exact"))
    }

    fun retry() {
        command(listOf("revert-seek", "mark"))
        mpv.mpv_set_property_string(handle, "pause", "no")
    }

    fun setSpeed(speed: Float) {
        mpv.mpv_set_property_string(handle, "speed", speed.toString())
    }

    fun setResizeMode(mode: PlayerResizeMode) {
        when (mode) {
            PlayerResizeMode.Fit -> {
                mpv.mpv_set_property_string(handle, "keepaspect", "yes")
                mpv.mpv_set_property_string(handle, "panscan", "0")
                mpv.mpv_set_property_string(handle, "video-aspect-override", "-1")
            }
            PlayerResizeMode.Fill -> {
                mpv.mpv_set_property_string(handle, "keepaspect", "no")
                mpv.mpv_set_property_string(handle, "panscan", "0")
            }
            PlayerResizeMode.Zoom -> {
                mpv.mpv_set_property_string(handle, "keepaspect", "yes")
                mpv.mpv_set_property_string(handle, "panscan", "1.0")
            }
        }
    }

    fun audioTracks(): List<AudioTrack> = tracks()
        .filter { it.type == "audio" }
        .mapIndexed { i, t -> t.toAudio(i) }

    fun subtitleTracks(): List<SubtitleTrack> = tracks()
        .filter { it.type == "sub" }
        .mapIndexed { i, t -> t.toSubtitle(i) }

    fun selectAudioTrack(index: Int) {
        val list = audioTracks()
        if (index !in list.indices) return
        mpv.mpv_set_property_string(handle, "aid", list[index].id)
    }

    fun selectSubtitleTrack(index: Int) {
        if (index < 0) {
            mpv.mpv_set_property_string(handle, "sid", "no")
            return
        }
        val list = subtitleTracks()
        if (index !in list.indices) return
        mpv.mpv_set_property_string(handle, "sid", list[index].id)
    }

    fun addExternalSubtitle(url: String) {
        command(listOf("sub-add", url, "select"))
    }

    fun clearExternalSubtitle() {
        externalSubtitleId()?.let {
            command(listOf("sub-remove", it.toString()))
        }
    }

    fun clearExternalSubtitleAndSelect(trackIndex: Int) {
        externalSubtitleId()?.let {
            command(listOf("sub-remove", it.toString()))
        }
        selectSubtitleTrack(trackIndex)
    }

    fun applySubtitleStyle(style: SubtitleStyleState) {
        mpv.mpv_set_property_string(handle, "sub-color", style.textColor.toMpvHex())
        mpv.mpv_set_property_string(handle, "sub-back-color", style.backgroundColor.toMpvHex())
        mpv.mpv_set_property_string(handle, "sub-border-color", style.outlineColor.toMpvHex())
        mpv.mpv_set_property_string(
            handle,
            "sub-border-size",
            if (style.outlineEnabled) style.outlineWidth.toString() else "0",
        )
        mpv.mpv_set_property_string(handle, "sub-font-size", style.fontSizeSp.toString())
        mpv.mpv_set_property_string(handle, "sub-bold", if (style.bold) "yes" else "no")
        val subPos = (100 - style.bottomOffset).coerceIn(0, 100)
        mpv.mpv_set_property_string(handle, "sub-pos", subPos.toString())
    }

    fun setSubtitleDelayMs(delayMs: Int) {
        mpv.mpv_set_property_string(handle, "sub-delay", (delayMs / 1000.0).toString())
    }

    fun snapshot(): PlayerPlaybackSnapshot {
        val paused = propertyString("pause")?.equals("yes", ignoreCase = true) == true
        val coreIdle = propertyString("core-idle")?.equals("yes", ignoreCase = true) == true
        val eof = propertyString("eof-reached")?.equals("yes", ignoreCase = true) == true
        val seeking = propertyString("seeking")?.equals("yes", ignoreCase = true) == true
        val position = propertyDoubleSeconds("time-pos")
        val duration = propertyDoubleSeconds("duration")
        val buffered = propertyDoubleSeconds("demuxer-cache-time")
        val speed = propertyString("speed")?.toFloatOrNull() ?: 1f
        val isLoading = seeking || (coreIdle && !paused && !eof)
        return PlayerPlaybackSnapshot(
            isLoading = isLoading,
            isPlaying = !paused && !eof && !coreIdle,
            isEnded = eof,
            durationMs = duration,
            positionMs = position,
            bufferedPositionMs = buffered.takeIf { it > 0L } ?: position,
            playbackSpeed = speed,
        )
    }

    fun consumeError(): String? {
        val current = pendingError ?: return null
        if (current == reportedError) return null
        reportedError = current
        return current
    }

    private fun externalSubtitleId(): Long? = tracks()
        .firstOrNull { it.type == "sub" && it.external == true }
        ?.id

    private fun tracks(): List<MpvTrack> {
        val raw = propertyString("track-list") ?: return emptyList()
        return runCatching {
            val arr = json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
            arr.mapNotNull { (it as? JsonObject)?.toTrack() }
        }.getOrDefault(emptyList())
    }

    private fun JsonObject.toTrack(): MpvTrack? {
        val type = this["type"]?.jsonPrimitive?.contentOrNull ?: return null
        val id = this["id"]?.jsonPrimitive?.longOrNull ?: return null
        return MpvTrack(
            id = id,
            type = type,
            title = this["title"]?.jsonPrimitive?.contentOrNull,
            lang = this["lang"]?.jsonPrimitive?.contentOrNull,
            selected = this["selected"]?.jsonPrimitive?.booleanOrNull ?: false,
            forced = this["forced"]?.jsonPrimitive?.booleanOrNull ?: false,
            external = this["external"]?.jsonPrimitive?.booleanOrNull,
        )
    }

    private fun MpvTrack.toAudio(index: Int) = AudioTrack(
        index = index,
        id = id.toString(),
        label = title ?: lang ?: "Track $id",
        language = lang,
        isSelected = selected,
    )

    private fun MpvTrack.toSubtitle(index: Int) = SubtitleTrack(
        index = index,
        id = id.toString(),
        label = title ?: lang ?: "Track $id",
        language = lang,
        isSelected = selected,
        isForced = forced || inferForcedSubtitleTrack(
            label = title,
            language = lang,
            trackId = id.toString(),
        ),
    )

    private fun propertyString(name: String): String? {
        val raw = mpv.mpv_get_property_string(handle, name) ?: return null
        val value = raw.getString(0, "UTF-8")
        mpv.mpv_free(raw)
        return value.takeIf { it.isNotEmpty() }
    }

    private fun propertyDoubleSeconds(name: String): Long {
        val str = propertyString(name) ?: return 0L
        val secs = str.toDoubleOrNull() ?: return 0L
        if (secs < 0 || secs.isNaN() || secs.isInfinite()) return 0L
        return (secs * 1000.0).toLong()
    }

    private fun command(args: List<String>) {
        val arr = arrayOfNulls<String>(args.size + 1)
        for (i in args.indices) arr[i] = args[i]
        arr[args.size] = null
        mpv.mpv_command(handle, arr)
    }

    private fun errorText(code: Int): String =
        runCatching { mpv.mpv_error_string(code) }.getOrNull()?.let {
            "${it.getString(0, "UTF-8")} ($code)"
        } ?: "code $code"

    private fun runEventLoop() {
        val event = MpvEvent()
        while (!shutdown.get()) {
            val ptr = mpv.mpv_wait_event(handle, 0.1) ?: continue
            event.loadFrom(ptr)
            when (event.event_id) {
                MPV_EVENT_SHUTDOWN -> return
                MPV_EVENT_LOG_MESSAGE -> {
                    val dataPtr = event.data
                    if (dataPtr != null) {
                        val log = MpvEventLogMessage()
                        log.loadFrom(dataPtr)
                        val prefix = log.prefix?.getString(0, "UTF-8") ?: "?"
                        val level = log.level?.getString(0, "UTF-8") ?: "?"
                        val text = log.text?.getString(0, "UTF-8")?.trimEnd() ?: ""
                        System.err.println("[mpv][$level][$prefix] $text")
                    }
                }
                MPV_EVENT_END_FILE -> {
                    val dataPtr = event.data
                    if (dataPtr != null) {
                        val end = MpvEventEndFile()
                        end.loadFrom(dataPtr)
                        if (end.reason == MPV_END_FILE_REASON_ERROR) {
                            val msg = runCatching { mpv.mpv_error_string(end.error) }.getOrNull()
                                ?.getString(0, "UTF-8")
                                ?: "Playback error"
                            pendingError = msg
                        }
                    }
                }
                else -> Unit
            }
        }
    }
}

internal data class MpvSessionOptions(
    val hwdec: String = "auto-safe",
    val userAgent: String = DEFAULT_USER_AGENT,
    val logLevel: String? = null,
)

internal inline fun <reified T : Structure> T.toArrayContiguous(size: Int): Array<T> {
    @Suppress("UNCHECKED_CAST")
    return toArray(size) as Array<T>
}

internal data class MpvTrack(
    val id: Long,
    val type: String,
    val title: String?,
    val lang: String?,
    val selected: Boolean,
    val forced: Boolean,
    val external: Boolean?,
)

internal fun Color.toMpvHex(): String {
    val r = (red * 255).toInt().coerceIn(0, 255)
    val g = (green * 255).toInt().coerceIn(0, 255)
    val b = (blue * 255).toInt().coerceIn(0, 255)
    val a = (alpha * 255).toInt().coerceIn(0, 255)
    return "#%02X%02X%02X%02X".format(a, r, g, b)
}

internal const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

internal const val MPV_EVENT_SHUTDOWN = 1
internal const val MPV_EVENT_LOG_MESSAGE = 2
internal const val MPV_EVENT_END_FILE = 7
internal const val MPV_END_FILE_REASON_ERROR = 4

internal const val MPV_RENDER_PARAM_API_TYPE = 1
internal const val MPV_RENDER_PARAM_SW_SIZE = 17
internal const val MPV_RENDER_PARAM_SW_FORMAT = 18
internal const val MPV_RENDER_PARAM_SW_STRIDE = 19
internal const val MPV_RENDER_PARAM_SW_POINTER = 20

@Structure.FieldOrder("event_id", "error", "reply_userdata", "data")
internal open class MpvEvent : Structure() {
    @JvmField var event_id: Int = 0
    @JvmField var error: Int = 0
    @JvmField var reply_userdata: Long = 0
    @JvmField var data: Pointer? = null

    fun loadFrom(p: Pointer) {
        useMemory(p)
        read()
    }
}

@Structure.FieldOrder(
    "reason",
    "error",
    "playlist_entry_id",
    "playlist_insert_id",
    "playlist_insert_num_entries",
)
internal open class MpvEventEndFile : Structure() {
    @JvmField var reason: Int = 0
    @JvmField var error: Int = 0
    @JvmField var playlist_entry_id: Long = 0
    @JvmField var playlist_insert_id: Long = 0
    @JvmField var playlist_insert_num_entries: Int = 0

    fun loadFrom(p: Pointer) {
        useMemory(p)
        read()
    }
}

@Structure.FieldOrder("type", "data")
internal open class MpvRenderParam : Structure() {
    @JvmField var type: Int = 0
    @JvmField var data: Pointer? = null
}

@Structure.FieldOrder("prefix", "level", "text", "log_level")
internal open class MpvEventLogMessage : Structure() {
    @JvmField var prefix: Pointer? = null
    @JvmField var level: Pointer? = null
    @JvmField var text: Pointer? = null
    @JvmField var log_level: Int = 0

    fun loadFrom(p: Pointer) {
        useMemory(p)
        read()
    }
}

internal interface MpvRenderUpdateCallback : Callback {
    fun invoke(ctx: Pointer?)
}

internal interface LibMpv : Library {
    companion object {
        val INSTANCE: LibMpv by lazy { loadLibrary() }

        private val isMac: Boolean
            get() = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

        private fun loadLibrary(): LibMpv {
            extendJnaSearchPath()
            val candidates = if (isMac) {
                listOf("mpv", "mpv.2", "mpv-2")
            } else {
                listOf("libmpv-2", "mpv-2", "mpv-1", "libmpv")
            }
            for (name in candidates) {
                try {
                    return Native.load(name, LibMpv::class.java)
                } catch (_: UnsatisfiedLinkError) {
                }
            }
            throw IllegalStateException(
                if (isMac) {
                    "libmpv.dylib was not found. The bundled dylib should ship in resources at " +
                        "macos-universal/libmpv.dylib. If you are running a custom build, run " +
                        "'./gradlew :composeApp:fetchMacOSLibmpv' so the dylib is downloaded and patched."
                } else {
                    "libmpv-2.dll was not found. The bundled DLL should ship in resources at " +
                        "win32-x86-64/libmpv-2.dll. If you are running a custom build, ensure mpv is " +
                        "on PATH or place libmpv-2.dll into an 'mpv' folder next to the executable."
                },
            )
        }

        private fun extendJnaSearchPath() {
            val dirs = candidateSearchDirs().map { it.absolutePath }
            if (dirs.isEmpty()) return
            val existing = System.getProperty("jna.library.path", "")
            val combined = (sequenceOf(existing) + dirs.asSequence())
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(File.pathSeparator)
            System.setProperty("jna.library.path", combined)
        }

        private fun candidateSearchDirs(): List<File> =
            if (isMac) macSearchDirs() else windowsSearchDirs()

        private fun windowsSearchDirs(): List<File> {
            val home = System.getProperty("user.home").orEmpty()
            val userDir = System.getProperty("user.dir").orEmpty()
            val programFiles = System.getenv("ProgramFiles") ?: "C:\\Program Files"
            val programFilesX86 = System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"
            val localAppData = System.getenv("LOCALAPPDATA") ?: "$home\\AppData\\Local"
            return listOf(
                File(userDir, "mpv"),
                File(userDir),
                File(programFiles, "mpv"),
                File(programFilesX86, "mpv"),
                File(localAppData, "Programs\\mpv"),
                File("$home\\scoop\\apps\\mpv\\current"),
                File("$home\\scoop\\shims"),
                File("C:\\ProgramData\\chocolatey\\lib\\mpv\\tools"),
                File("C:\\ProgramData\\chocolatey\\bin"),
            ).filter { it.isDirectory }
        }

        private fun macSearchDirs(): List<File> {
            val home = System.getProperty("user.home").orEmpty()
            val userDir = System.getProperty("user.dir").orEmpty()
            val workspaceBundles = listOfNotNull(
                File(userDir).takeIf { it.path.isNotEmpty() },
                File(userDir).parentFile,
            ).flatMap { root ->
                listOf(
                    File(root, "composeApp/build/generated/libmpv/macos-universal"),
                    File(root, "composeApp/build/processedResources/desktop/main/macos-universal"),
                    File(root, "build/generated/libmpv/macos-universal"),
                    File(root, "build/processedResources/desktop/main/macos-universal"),
                )
            }
            return (listOfNotNull(bundledLibmpvDir()) + workspaceBundles + listOf(
                File("/opt/homebrew/lib"),
                File("/usr/local/lib"),
                File("$home/Applications/mpv.app/Contents/MacOS/lib"),
                File("/Applications/mpv.app/Contents/MacOS/lib"),
            )).filter { it.isDirectory }
        }

        private fun bundledLibmpvDir(): File? {
            // When packaged via Compose Desktop, dylibs may live in resources next to the JAR
            // or extracted under the .app's bundle. Walk up from this class' code-source and
            // probe known relative locations at each level.
            val classUrl = LibMpv::class.java.protectionDomain?.codeSource?.location ?: return null
            val classFile = runCatching { File(classUrl.toURI()) }.getOrNull() ?: return null
            val base = if (classFile.isFile) classFile.parentFile else classFile
            val relativeCandidates = listOf(
                "macos-universal",
                "generated/libmpv/macos-universal",
                "processedResources/desktop/main/macos-universal",
                "../macos-universal",
            )
            return generateSequence(base) { it.parentFile }
                .take(8)
                .flatMap { dir -> relativeCandidates.asSequence().map { File(dir, it) } }
                .firstOrNull { it.isDirectory && File(it, "libmpv.dylib").isFile }
        }
    }

    fun mpv_create(): Pointer?
    fun mpv_initialize(handle: Pointer): Int
    fun mpv_terminate_destroy(handle: Pointer)
    fun mpv_set_option_string(handle: Pointer, name: String, data: String): Int
    fun mpv_set_property_string(handle: Pointer, name: String, data: String): Int
    fun mpv_get_property_string(handle: Pointer, name: String): Pointer?
    fun mpv_free(data: Pointer)
    fun mpv_command(handle: Pointer, args: Array<String?>): Int
    fun mpv_command_string(handle: Pointer, args: String): Int
    fun mpv_wait_event(handle: Pointer, timeout: Double): Pointer?
    fun mpv_error_string(error: Int): Pointer?
    fun mpv_request_log_messages(handle: Pointer, minLevel: String): Int

    fun mpv_render_context_create(out: PointerByReference, mpv: Pointer, params: Pointer): Int
    fun mpv_render_context_render(ctx: Pointer, params: Pointer): Int
    fun mpv_render_context_free(ctx: Pointer)
    fun mpv_render_context_set_update_callback(
        ctx: Pointer,
        cb: MpvRenderUpdateCallback?,
        cbCtx: Pointer?,
    )
}
