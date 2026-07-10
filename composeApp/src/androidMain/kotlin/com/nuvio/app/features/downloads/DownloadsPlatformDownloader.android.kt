package com.nuvio.app.features.downloads

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.Continuation
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

private val downloadHttpClient = OkHttpClient.Builder()
    .connectTimeout(60, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

internal actual object DownloadsPlatformDownloader {
    private const val notificationPermissionRequestCode = 0x4E55_4401

    private var appContext: Context? = null
    private var currentActivity: ComponentActivity? = null
    @Volatile private var pendingPermissionContinuation: Continuation<Boolean>? = null
    private val permissionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val permissionMutex = Mutex()

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun bindActivity(activity: ComponentActivity) {
        currentActivity = activity
    }

    fun unbindActivity(activity: ComponentActivity) {
        if (currentActivity === activity) {
            currentActivity = null
        }
    }

    fun handlePermissionRequestResult(requestCode: Int, grantResults: IntArray): Boolean {
        if (requestCode != notificationPermissionRequestCode) return false
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        pendingPermissionContinuation?.resume(granted)
        pendingPermissionContinuation = null
        return true
    }

    actual fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
    ): DownloadsTaskHandle {
        requestPostNotificationsPermissionAsync()

        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val callHolder = HttpCallHolder()
        val keepAliveContext = appContext
        var foregroundRetained = false
        if (keepAliveContext != null) {
            DownloadsForegroundService.retain(
                context = keepAliveContext,
                downloadId = request.downloadId,
                displayTitle = request.displayTitle,
            )
            foregroundRetained = true
        }

        val downloadJob = scope.launch {
            val context = appContext
            if (context == null) {
                onFailure(runBlocking { getString(Res.string.downloads_error_not_initialized) })
                return@launch
            }

            val downloadsDir = File(context.filesDir, "downloads").apply { mkdirs() }
            if (request.isHlsStream || request.sourceUrl.isHlsPlaylistUrl()) {
                performHlsDownload(request, downloadsDir, callHolder, onProgress, onSuccess, onFailure)
            } else {
                performDirectDownload(request, downloadsDir, callHolder, onProgress, onSuccess, onFailure)
            }
        }

        downloadJob.invokeOnCompletion {
            callHolder.cancel()
            if (foregroundRetained && keepAliveContext != null) {
                DownloadsForegroundService.release(keepAliveContext, request.downloadId)
            }
        }

        return AndroidDownloadsTaskHandle(job)
    }

    private fun requestPostNotificationsPermissionAsync() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val context = appContext ?: return
        val alreadyGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) return

        permissionScope.launch {
            if (!permissionMutex.tryLock()) return@launch
            try {
                requestPostNotificationsPermission()
            } finally {
                permissionMutex.unlock()
            }
        }
    }

    private suspend fun requestPostNotificationsPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        val context = appContext ?: return false
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        val activity = currentActivity ?: return false
        return suspendCancellableCoroutine { continuation ->
            pendingPermissionContinuation = continuation
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                notificationPermissionRequestCode,
            )
        }
    }

    actual fun removeFile(localFileUri: String?): Boolean {
        if (localFileUri.isNullOrBlank()) return false
        val file = localFileUri.toLocalFileOrNull() ?: return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    actual fun removePartialFile(destinationFileName: String): Boolean {
        val context = appContext ?: return false
        val downloadsDir = File(context.filesDir, "downloads")
        val tempFile = File(downloadsDir, "$destinationFileName.part")
        if (!tempFile.exists()) return true
        return runCatching { tempFile.delete() }.getOrDefault(false)
    }

    actual fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? {
        localFileUri
            ?.toLocalFileOrNull()
            ?.takeIf { it.exists() }
            ?.let { return it.toURI().toString() }

        val context = appContext ?: return null
        val fileName = destinationFileName.trim().takeIf { it.isNotBlank() }
            ?: localFileUri
                ?.toLocalFileOrNull()
                ?.name
                ?.takeIf { it.isNotBlank() }
            ?: return null
        val downloadsDir = File(context.filesDir, "downloads")
        val localFile = File(downloadsDir, fileName)
        return localFile.takeIf { it.exists() }?.toURI()?.toString()
    }

    actual fun openDownloadsDirectory(): Boolean {
        val context = appContext ?: return false
        val downloadsDir = File(context.filesDir, "downloads").apply { mkdirs() }
        val uri = runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                downloadsDir,
            )
        }.getOrNull() ?: return false

        val intents = listOf(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "resource/folder")
            },
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "vnd.android.document/directory")
            },
            Intent(Intent.ACTION_VIEW).apply {
                data = uri
            },
        )

        return intents.any { intent ->
            intent.addCategory(Intent.CATEGORY_DEFAULT)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)

            runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        }
    }
}

private class AndroidDownloadsTaskHandle(
    private val job: Job,
) : DownloadsTaskHandle {
    override fun cancel() {
        job.cancel()
    }
}

/** Tracks the in-flight OkHttp call so cancellation can abort a blocking read immediately. */
private class HttpCallHolder {
    @Volatile
    var call: Call? = null

    fun cancel() {
        call?.cancel()
    }
}

private suspend fun performDirectDownload(
    request: DownloadPlatformRequest,
    downloadsDir: File,
    callHolder: HttpCallHolder,
    onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
    onFailure: (message: String) -> Unit,
) {
    val destination = File(downloadsDir, request.destinationFileName)
    val tempFile = File(downloadsDir, "${request.destinationFileName}.part")

    try {
        var resumeFromBytes = tempFile.takeIf { it.exists() }?.length()?.coerceAtLeast(0L) ?: 0L

        fun buildRequest(rangeStart: Long?): Request {
            val requestBuilder = Request.Builder().url(request.sourceUrl)
            request.sourceHeaders.forEach { (key, value) ->
                requestBuilder.header(key, value)
            }
            if (rangeStart != null && rangeStart > 0L) {
                requestBuilder.header("Range", "bytes=$rangeStart-")
            }
            return requestBuilder.get().build()
        }

        var attemptedRangeRequest = resumeFromBytes > 0L
        var httpRequest = buildRequest(if (attemptedRangeRequest) resumeFromBytes else null)
        var httpCall = downloadHttpClient.newCall(httpRequest)
        callHolder.call = httpCall
        var response = httpCall.execute()

        if (attemptedRangeRequest && response.code == 416) {
            response.close()
            tempFile.delete()
            resumeFromBytes = 0L
            attemptedRangeRequest = false
            httpRequest = buildRequest(null)
            httpCall = downloadHttpClient.newCall(httpRequest)
            callHolder.call = httpCall
            response = httpCall.execute()
        }

        response.use { response ->
            if (!response.isSuccessful) {
                error(
                    runBlocking {
                        getString(Res.string.downloads_error_http_failed, response.code)
                    },
                )
            }

            val isPartialResume = attemptedRangeRequest && response.code == 206 && resumeFromBytes > 0L
            val appendToTemp = isPartialResume
            val startingBytes = if (appendToTemp) resumeFromBytes else 0L

            if (!appendToTemp && tempFile.exists()) {
                tempFile.delete()
            }

            val body = response.body ?: error(
                runBlocking { getString(Res.string.downloads_error_empty_body) },
            )
            val totalBytes = resolveTotalBytes(
                startingBytes = startingBytes,
                isPartialResume = isPartialResume,
                contentRangeHeader = response.header("Content-Range"),
                contentLength = body.contentLength().takeIf { it > 0L },
            )
            var downloadedBytes = startingBytes
            onProgress(downloadedBytes, totalBytes)

            body.byteStream().use { input ->
                FileOutputStream(tempFile, appendToTemp).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read.toLong()
                        onProgress(downloadedBytes, totalBytes)
                    }
                    output.flush()
                }
            }

            if (destination.exists()) {
                destination.delete()
            }
            if (!tempFile.renameTo(destination)) {
                tempFile.copyTo(destination, overwrite = true)
                tempFile.delete()
            }

            val finalSize = destination.length()
            onSuccess(destination.toURI().toString(), totalBytes ?: finalSize)
        }
    } catch (error: Throwable) {
        onFailure(error.message ?: runBlocking { getString(Res.string.download_failed) })
    }
}

private suspend fun performHlsDownload(
    request: DownloadPlatformRequest,
    downloadsDir: File,
    callHolder: HttpCallHolder,
    onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
    onFailure: (message: String) -> Unit,
) {
    val callerContext = coroutineContext
    val tempFile = File(downloadsDir, "${request.destinationFileName}.part")

    try {
        if (tempFile.exists()) tempFile.delete()

        val outcome = FileOutputStream(tempFile, false).use { output ->
            downloadHlsToFile(
                sourceUrl = request.sourceUrl,
                httpGet = { url, range ->
                    val builder = Request.Builder().url(url)
                    request.sourceHeaders.forEach { (key, value) -> builder.header(key, value) }
                    if (range != null) {
                        builder.header(
                            "Range",
                            "bytes=${range.offset}-${range.offset + range.length - 1}",
                        )
                    }
                    val httpCall = downloadHttpClient.newCall(builder.get().build())
                    callHolder.call = httpCall
                    httpCall.execute().use { response ->
                        HlsHttpResult(
                            status = response.code,
                            body = response.body?.bytes() ?: ByteArray(0),
                            finalUrl = response.request.url.toString(),
                        )
                    }
                },
                appendBytes = { bytes -> output.write(bytes) },
                decryptAes128Cbc = ::aes128CbcDecrypt,
                onProgress = onProgress,
                ensureActive = { callerContext.ensureActive() },
            )
        }

        val destination = File(downloadsDir, hlsOutputFileName(request.destinationFileName, outcome.isFmp4))
        if (destination.exists()) destination.delete()
        if (!tempFile.renameTo(destination)) {
            tempFile.copyTo(destination, overwrite = true)
            tempFile.delete()
        }

        onSuccess(destination.toURI().toString(), outcome.totalBytes)
    } catch (error: Throwable) {
        onFailure(error.message ?: runBlocking { getString(Res.string.download_failed) })
    }
}

private fun aes128CbcDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
    if (data.isEmpty()) return data
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    return cipher.doFinal(data)
}

private fun String.toLocalFileOrNull(): File? {
    return runCatching {
        if (startsWith("file:")) {
            File(URI(this))
        } else {
            File(this)
        }
    }.getOrNull()
}

private fun resolveTotalBytes(
    startingBytes: Long,
    isPartialResume: Boolean,
    contentRangeHeader: String?,
    contentLength: Long?,
): Long? {
    parseContentRangeTotal(contentRangeHeader)?.let { return it }
    val normalizedLength = contentLength?.takeIf { it > 0L } ?: return null
    return if (isPartialResume && startingBytes > 0L) {
        startingBytes + normalizedLength
    } else {
        normalizedLength
    }
}

private fun parseContentRangeTotal(headerValue: String?): Long? {
    val value = headerValue?.trim().orEmpty()
    if (value.isBlank()) return null
    val slashIndex = value.lastIndexOf('/')
    if (slashIndex == -1 || slashIndex == value.lastIndex) return null
    val totalPart = value.substring(slashIndex + 1).trim()
    if (totalPart == "*") return null
    return totalPart.toLongOrNull()?.takeIf { it > 0L }
}
