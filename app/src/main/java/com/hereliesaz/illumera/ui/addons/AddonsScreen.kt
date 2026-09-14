package com.hereliesaz.illumera.ui.addons

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.hereliesaz.illumera.data.auth.StremioConnectionState
import com.hereliesaz.illumera.ui.util.generateQrCodeBitmap
import com.hereliesaz.illumera.ui.util.rememberIsTvDevice

internal const val STREMIO_ADDONS_URL = "https://web.stremio.com/#/addons"

/**
 * Addon management deliberately lives in Stremio's own UI.
 *
 * Illumera is a consumer of the connected Stremio account's addon collection, not
 * a second addon store. Users install, configure, reorder and uninstall addons in
 * the official Stremio Web UI. When they return, Illumera reconciles the active
 * profile with the account collection.
 */
@Composable
fun AddonsScreen(
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") isTopNav: Boolean = false,
    viewModel: StremioAddonsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isTv = rememberIsTvDevice()
    val isCompact = LocalConfiguration.current.screenWidthDp < 600
    var openedStremio by remember { mutableStateOf(false) }

    val qrBitmap by produceState<Bitmap?>(initialValue = null, isTv) {
        value = if (isTv) generateQrCodeBitmap(STREMIO_ADDONS_URL) else null
    }

    BackHandler(onBack = onBack)

    // If this device opened Stremio Web directly, pull the account collection when
    // the user comes back. TV users commonly manage addons from a phone using the QR
    // code, so they also get an explicit Sync Changes action below.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && openedStremio) {
                openedStremio = false
                viewModel.syncFromStremio()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(end = 24.dp)
    ) {
        Text(
            text = "Stremio Addons",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp
            ),
            color = Color.White
        )
        Text(
            text = "Addon management uses Stremio's official interface.",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(28.dp))

        when (val connection = connectionState) {
            StremioConnectionState.Disconnected -> {
                StatusCard(
                    title = "Connect Stremio first",
                    body = "Connect a Stremio account in Settings → Integrations. Your account becomes the source of truth for installed addons."
                )
            }

            is StremioConnectionState.Connected -> {
                Text(
                    text = connection.email,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(14.dp))

                StatusCard(
                    title = "Manage addons in Stremio",
                    body = "Install, configure, reorder, update, and uninstall addons in Stremio. Return here and Illumera will mirror the account collection into this profile."
                )

                Spacer(Modifier.height(18.dp))

                val openStremio: () -> Unit = {
                    openedStremio = true
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(STREMIO_ADDONS_URL))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.onFailure {
                        openedStremio = false
                    }
                }

                if (isCompact) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        VoidButton(
                            text = "OPEN STREMIO ADDONS",
                            onClick = openStremio,
                            enabled = !state.isSyncing,
                            isPrimary = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        VoidButton(
                            text = if (state.isSyncing) "SYNCING…" else "SYNC CHANGES",
                            onClick = viewModel::syncFromStremio,
                            enabled = !state.isSyncing,
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (state.isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        VoidButton(
                            text = "OPEN STREMIO ADDONS",
                            onClick = openStremio,
                            enabled = !state.isSyncing,
                            isPrimary = true,
                            modifier = Modifier.width(250.dp)
                        )

                        VoidButton(
                            text = if (state.isSyncing) "SYNCING…" else "SYNC CHANGES",
                            onClick = viewModel::syncFromStremio,
                            enabled = !state.isSyncing,
                            modifier = Modifier.width(190.dp)
                        )

                        if (state.isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                if (state.message != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = state.message!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.error == null) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.error
                    )
                }

                if (isTv) {
                    Spacer(Modifier.height(28.dp))
                    Text(
                        text = "Manage from your phone",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Scan this code to open Stremio's addon manager, make your changes, then choose Sync Changes on the TV.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth(0.75f)
                    )
                    Spacer(Modifier.height(14.dp))

                    if (qrBitmap != null) {
                        Box(
                            modifier = Modifier
                                .size(190.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White)
                                .padding(7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = qrBitmap!!.asImageBitmap(),
                                contentDescription = "Open Stremio addon manager on phone",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.045f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(18.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.62f),
            textAlign = TextAlign.Start
        )
    }
}
