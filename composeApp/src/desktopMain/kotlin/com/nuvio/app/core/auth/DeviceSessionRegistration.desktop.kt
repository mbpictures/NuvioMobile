package com.nuvio.app.core.auth

internal actual fun currentDeviceClientMetadata(): DeviceClientMetadata {
    val osName = System.getProperty("os.name").orEmpty().trim()
    val osVersion = System.getProperty("os.version").orEmpty().trim()
    val deviceName = formatDeviceName(
        manufacturer = "",
        model = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull().orEmpty(),
        fallback = osName.ifBlank { "Desktop device" },
    )

    return DeviceClientMetadata(
        deviceName = deviceName,
        platform = listOf(osName, osVersion).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Desktop" },
    )
}
