package com.nuvio.app.features.updater

import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.updates_not_available
import org.jetbrains.compose.resources.getString
import com.nuvio.app.desktop.DesktopPreferences
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual object AppUpdaterPlatform {
    private const val preferencesName = "nuvio_updater"
    private const val ignoredTagKey = "ignored_release_tag"

    private val osName: String = System.getProperty("os.name").orEmpty().lowercase()
    private val isWindows: Boolean = osName.contains("win")
    private val isMac: Boolean = osName.contains("mac") || osName.contains("darwin")

    private val downloadHttpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(60))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    actual val isSupported: Boolean = isWindows || isMac
    actual val isDebugBuild: Boolean = false

    actual fun getSupportedAbis(): List<String> = emptyList()

    actual fun getAssetFileExtensions(): List<String> = when {
        isWindows -> listOf("msi", "exe")
        isMac -> listOf("dmg")
        else -> emptyList()
    }

    actual fun getDistributionFlavor(): String = ""

    actual fun getIgnoredTag(): String? = DesktopPreferences.getString(preferencesName, ignoredTagKey)

    actual fun setIgnoredTag(tag: String?) {
        DesktopPreferences.putNullableString(preferencesName, ignoredTagKey, tag)
    }

    actual suspend fun downloadApk(
        assetUrl: String,
        assetName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val safeName = assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "nuvio-update" }
            val targetDir = Paths.get(System.getProperty("java.io.tmpdir"), "Nuvio", "updates")
            Files.createDirectories(targetDir)
            val destination = targetDir.resolve(safeName)
            Files.deleteIfExists(destination)

            val request = HttpRequest.newBuilder()
                .uri(URI.create(assetUrl))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", "NuvioMobile")
                .header("Accept", "application/octet-stream")
                .GET()
                .build()

            val response = downloadHttpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() !in 200..299) {
                error("Download failed with HTTP ${response.statusCode()}")
            }

            val totalBytes = response.headers().firstValueAsLong("content-length")
                .takeIf { it.isPresent && it.asLong > 0L }
                ?.asLong

            response.body().use { input ->
                Files.newOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloadedBytes = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        onProgress(downloadedBytes, totalBytes)
                    }
                    output.flush()
                }
            }

            destination.toAbsolutePath().toString()
        }
    }

    // Desktop installs need no special "unknown sources" permission, so the
    // controller can hand off to installDownloadedApk directly.
    actual fun canRequestPackageInstalls(): Boolean = true

    actual fun openUnknownSourcesSettings() = Unit

    actual fun installDownloadedApk(path: String): Result<Unit> = runCatching {
        val installer = File(path)
        check(installer.exists()) { "Downloaded update file is missing." }

        openInstaller(installer)
        if (isWindows) {
            scheduleExit()
        }
    }

    private fun openInstaller(installer: File) {
        val openedViaDesktop = runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(installer)
                true
            } else {
                false
            }
        }.getOrDefault(false)
        if (openedViaDesktop) return

        val command = when {
            isWindows -> listOf("cmd", "/c", "start", "", installer.absolutePath)
            isMac -> listOf("open", installer.absolutePath)
            else -> error("Opening installers is not supported on this platform.")
        }
        ProcessBuilder(command).start()
    }

    private fun scheduleExit() {
        Thread {
            runCatching { Thread.sleep(1500) }
            exitProcess(0)
        }.apply {
            isDaemon = true
            name = "nuvio-update-exit"
            start()
        }
    }
}
