package com.nuvio.app.features.player.cast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.cast_picker_disconnect
import nuvio.composeapp.generated.resources.cast_picker_no_devices
import nuvio.composeapp.generated.resources.cast_picker_searching
import nuvio.composeapp.generated.resources.cast_picker_title
import org.jetbrains.compose.resources.stringResource

/**
 * Modal that lists discovered cast receivers and lets the user connect or disconnect. Discovery is
 * active only while this dialog is shown (started/stopped via [CastController]).
 */
@Composable
fun CastDevicePicker(
    controller: CastController,
    onDismiss: () -> Unit,
) {
    DisposableEffect(controller) {
        controller.startDiscovery()
        onDispose { controller.stopDiscovery() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF1A1A1A),
            contentColor = Color.White,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(Res.string.cast_picker_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 4.dp),
                )

                val devices = controller.devices
                val connectedName = controller.connectedDeviceName

                if (devices.isEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                        Text(
                            text = stringResource(Res.string.cast_picker_searching),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                    Text(
                        text = stringResource(Res.string.cast_picker_no_devices),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.5f),
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        devices.forEach { device ->
                            val isConnected = device.name == connectedName
                            CastDeviceRow(
                                device = device,
                                isConnected = isConnected,
                                onClick = {
                                    if (isConnected) {
                                        controller.disconnect()
                                    } else {
                                        controller.connect(device)
                                    }
                                    onDismiss()
                                },
                            )
                        }
                    }
                }

                if (connectedName != null) {
                    Text(
                        text = stringResource(Res.string.cast_picker_disconnect),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFFFF6B6B),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                controller.disconnect()
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CastDeviceRow(
    device: CastDevice,
    isConnected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isConnected) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
            contentDescription = null,
            tint = if (isConnected) MaterialTheme.colorScheme.primary else Color.White,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                fontWeight = if (isConnected) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (!device.modelName.isNullOrBlank()) {
                Text(
                    text = device.modelName,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f),
                )
            }
        }
    }
}
