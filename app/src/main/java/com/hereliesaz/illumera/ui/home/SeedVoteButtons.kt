package com.hereliesaz.illumera.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbDownOffAlt
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.ThumbUpOffAlt
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.hereliesaz.illumera.data.recommend.PersonalRowPrefs

/** Thumbs up / down on a personal row's seed title. Provided by HomeScreen on the Home tab. */
class SeedVotes(
    val approved: Set<String>,
    val onApprove: (String) -> Unit,
    val onReject: (String) -> Unit
)

val LocalSeedVotes = compositionLocalOf<SeedVotes?> { null }

/** Header controls for [rowId], or null when the row isn't a personal row. */
@Composable
fun seedVoteActions(rowId: String): (@Composable () -> Unit)? {
    val votes = LocalSeedVotes.current ?: return null
    val seed = PersonalRowPrefs.seedOf(rowId) ?: return null
    return {
        Spacer(Modifier.width(12.dp))
        VoteButton(
            icon = if (seed in votes.approved) Icons.Default.ThumbUp else Icons.Default.ThumbUpOffAlt,
            description = "Good suggestion source",
            onClick = { votes.onApprove(seed) }
        )
        Spacer(Modifier.width(6.dp))
        VoteButton(
            icon = Icons.Default.ThumbDownOffAlt,
            description = "Don't suggest from this title",
            onClick = { votes.onReject(seed) }
        )
    }
}

@Composable
private fun VoteButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(if (focused) Color.White else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (focused) Color.Black else Color.White.copy(0.7f),
            modifier = Modifier.padding(4.dp)
        )
    }
}
