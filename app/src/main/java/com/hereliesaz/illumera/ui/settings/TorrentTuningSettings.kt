package com.hereliesaz.illumera.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.illumera.data.torrent.TorrentDeviceTuner

/**
 * Playback-facing controls for TorrServer transport tuning.
 * Automatic mode remains the default; manual values are bounded to limits the
 * transport layer also enforces, including the device-specific RAM ceiling.
 */
@Composable
fun TorrentTuningSettings(onGoBack: () -> Unit) {
    val context = LocalContext.current
    val initial = remember { TorrentDeviceTuner.getOrCreateSettings(context) }
    val maxSafeCacheMb = remember { TorrentDeviceTuner.maxSafeCacheMb(context) }

    var manualOverride by remember { mutableStateOf(TorrentDeviceTuner.isManualOverride(context)) }
    var cacheMb by remember { mutableIntStateOf(initial.cacheSizeMb) }
    var connections by remember { mutableIntStateOf(initial.connectionsLimit) }

    Spacer(Modifier.height(12.dp))
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.1f)))
    Spacer(Modifier.height(12.dp))

    Text(
        text = "Torrent Streaming",
        style = MaterialTheme.typography.titleSmall.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp
        ),
        color = Color.White.copy(alpha = 0.7f),
        modifier = Modifier.padding(bottom = 4.dp)
    )
    Text(
        text = if (manualOverride) {
            "Manual limits are active. Changes apply the next time a torrent source starts."
        } else {
            "Illumera automatically sizes the torrent cache and peer limits for this device."
        },
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
        color = Color.White.copy(alpha = 0.55f),
        modifier = Modifier.padding(bottom = 8.dp)
    )

    SettingToggleRow(
        label = "Manual Torrent Tuning",
        subtitle = "Override the device-aware automatic values",
        isChecked = manualOverride,
        onCheckedChange = { enabled ->
            manualOverride = enabled
            val settings = if (enabled) {
                TorrentDeviceTuner.saveManualSettings(context, cacheMb, connections)
            } else {
                TorrentDeviceTuner.resetToAutomatic(context)
            }
            cacheMb = settings.cacheSizeMb
            connections = settings.connectionsLimit
        },
        onBack = onGoBack
    )

    if (manualOverride) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "RAM Cache: $cacheMb MB",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp
            ),
            modifier = Modifier.padding(start = 4.dp, bottom = 3.dp)
        )
        Text(
            text = "Safe device maximum: $maxSafeCacheMb MB (1/8 of reported RAM, capped at 512 MB)",
            color = Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )

        if (maxSafeCacheMb > 64) {
            VoidSlider(
                value = cacheMb.toFloat(),
                onValueChange = { value ->
                    val requested = value.toInt()
                    val settings = TorrentDeviceTuner.saveManualSettings(context, requested, connections)
                    cacheMb = settings.cacheSizeMb
                    connections = settings.connectionsLimit
                },
                valueRange = 64f..maxSafeCacheMb.toFloat(),
                steps = ((maxSafeCacheMb - 64) / 32).coerceAtLeast(0)
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = "Peer / DHT Connection Limit: $connections",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp
            ),
            modifier = Modifier.padding(start = 4.dp, bottom = 3.dp)
        )
        Text(
            text = "Higher values can improve discovery but increase CPU, memory, and router load.",
            color = Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        VoidSlider(
            value = connections.toFloat(),
            onValueChange = { value ->
                val requested = value.toInt()
                val settings = TorrentDeviceTuner.saveManualSettings(context, cacheMb, requested)
                cacheMb = settings.cacheSizeMb
                connections = settings.connectionsLimit
            },
            valueRange = 40f..200f,
            steps = 15
        )
    }
}
