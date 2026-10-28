package com.hereliesaz.illumera.ui.search

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * Voice search through the system speech recognizer (Google on Android TV and phones).
 * The recognizer owns the microphone and its permission, so the app requests none.
 * Returns null when the device has no recognizer, so callers can hide the control.
 */
@Composable
fun rememberVoiceSearchLauncher(onResult: (String) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val latestOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(latestOnResult)
    }
    val intent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Search movies and series")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }
    val available = remember(context) {
        intent.resolveActivity(context.packageManager) != null
    }
    return if (!available) null else {
        { runCatching { launcher.launch(intent) } }
    }
}
