package net.asksakis.massdroidv2.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.model.PlayerType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSelector(
    players: List<Player>,
    selectedPlayerId: String?,
    onPlayerSelected: (Player) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = "Select Player",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )

        LazyColumn(
            modifier = Modifier.padding(bottom = 32.dp)
        ) {
            items(players.filter { it.available }.sortedBy { it.displayName.lowercase() }) { player ->
                ListItem(
                    headlineContent = { Text(player.displayName) },
                    supportingContent = {
                        Text(
                            when (player.state) {
                                PlaybackState.PLAYING -> "Playing"
                                PlaybackState.PAUSED -> "Paused"
                                PlaybackState.IDLE -> "Idle"
                            }
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = when (player.type) {
                                PlayerType.GROUP -> Icons.Default.SpeakerGroup
                                PlayerType.STEREO_PAIR -> Icons.Default.Speaker
                                PlayerType.PLAYER -> Icons.Default.Speaker
                            },
                            contentDescription = null,
                            tint = if (player.playerId == selectedPlayerId)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        if (player.playerId == selectedPlayerId) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    modifier = Modifier.clickable {
                        onPlayerSelected(player)
                        onDismiss()
                    }
                )
            }
        }
    }
}
