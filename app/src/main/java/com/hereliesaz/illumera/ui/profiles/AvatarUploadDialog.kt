package com.hereliesaz.illumera.ui.profiles

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.hereliesaz.illumera.remote_input.AvatarServerManager
import com.hereliesaz.illumera.remote_input.ServerInfo
import com.hereliesaz.illumera.ui.util.generateQrCodeBitmap
import com.hereliesaz.illumera.ui.util.rememberDialogWidth
import com.hereliesaz.illumera.ui.util.rememberIsTvDevice
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Dialog that displays a QR code for remote avatar upload.
 * Starts a local web server and shows QR code pointing to it.
 * When the user uploads and crops an image, it's saved locally
 * and the path is returned via onAvatarReceived.
 */
@Composable
fun AvatarUploadDialog(
    onDismissRequest: () -> Unit,
    onAvatarReceived: (String) -> Unit
) {
    val context = LocalContext.current
    val isTv = rememberIsTvDevice()
    var serverInfo by remember { mutableStateOf<ServerInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val serverManager = remember { AvatarServerManager() }
    val focusRequester = remember { FocusRequester() }

    // Phones/tablets already have a native photo picker — no need to make the user
    // scan a QR code with the same device that's holding the photo.
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val imageBytes = try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                if (com.hereliesaz.illumera.BuildConfig.DEBUG) {
                    android.util.Log.w("AvatarUploadDialog", "Photo picker read error", e)
                }
                null
            }
            val avatarPath = imageBytes?.let { saveAvatarImage(context, it) }
            if (avatarPath != null) {
                onAvatarReceived(avatarPath)
                onDismissRequest()
            } else {
                error = "Could not read the selected photo."
            }
        }
    }

    // Start server when dialog opens
    LaunchedEffect(Unit) {
        delay(100)
        focusRequester.requestFocus()
        
        val info = serverManager.startServer { imageBytes ->
            // Image received from phone - save to file
            val avatarPath = saveAvatarImage(context, imageBytes)
            if (avatarPath != null) {
                onAvatarReceived(avatarPath)
            }
            onDismissRequest()
        }
        
        if (info != null) {
            serverInfo = info
            qrBitmap = generateQrCodeBitmap(info.url)
        } else {
            error = "Could not start server. Check your network connection."
        }
    }

    // Stop server when dialog closes
    DisposableEffect(Unit) {
        onDispose {
            serverManager.stopServer()
        }
    }

    Dialog(onDismissRequest = {
        serverManager.stopServer()
        onDismissRequest()
    }) {
        Box(
            modifier = Modifier
                .width(rememberDialogWidth(420))
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.background)
                .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))
                .padding(32.dp)
                .focusRequester(focusRequester)
                .focusable()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Upload Avatar",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                
                Text(
                    if (isTv) "Scan with your phone to upload a picture" else "Choose a photo from this device",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 8.dp)
                )

                Spacer(Modifier.height(32.dp))

                if (!isTv) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable {
                                photoPickerLauncher.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Choose Photo",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "Or scan with another device:",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    Spacer(Modifier.height(12.dp))
                }

                when {
                    error != null -> {
                        Text(
                            error!!,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }
                    qrBitmap != null && serverInfo != null -> {
                        // QR Code
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.White)
                                .padding(3.dp)
                        ) {
                            Image(
                                bitmap = qrBitmap!!.asImageBitmap(),
                                contentDescription = "QR Code",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        
                        Spacer(Modifier.height(24.dp))
                        
                        // Manual URL
                        Text(
                            "Or visit:",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                        Text(
                            serverInfo!!.url,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.5.sp
                            ),
                            color = Color.White,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    else -> {
                        // Loading
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Starting server...",
                            color = Color.Gray
                        )
                    }
                }
            }
        }
    }
}

/**
 * Saves the avatar image bytes to internal storage.
 * Returns the file path on success, null on failure.
 */
private fun saveAvatarImage(context: Context, imageBytes: ByteArray): String? {
    return try {
        // Create avatars directory if it doesn't exist
        val avatarsDir = File(context.filesDir, "avatars")
        if (!avatarsDir.exists()) {
            avatarsDir.mkdirs()
        }
        
        // Generate unique filename
        val fileName = "avatar_${UUID.randomUUID()}.png"
        val file = File(avatarsDir, fileName)
        
        // Write bytes to file
        FileOutputStream(file).use { fos ->
            fos.write(imageBytes)
        }
        
        // Return the path with custom: prefix
        "custom:${file.absolutePath}"
    } catch (e: Exception) {
        if (com.hereliesaz.illumera.BuildConfig.DEBUG) android.util.Log.w("AvatarUploadDialog", "Image save error", e)
        null
    }
}
