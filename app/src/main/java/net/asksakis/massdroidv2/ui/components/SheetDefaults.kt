package net.asksakis.massdroidv2.ui.components

import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

object SheetDefaults {
    @Composable
    fun containerColor(): Color = MaterialTheme.colorScheme.surfaceContainer

    @Composable
    fun listItemColors(): ListItemColors = ListItemDefaults.colors(
        containerColor = Color.Transparent
    )

    @Composable
    fun HeaderTitle(text: String, modifier: Modifier = Modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = modifier
        )
    }

    val HeaderHorizontalPadding = 16.dp
    val HeaderVerticalPadding = 2.dp
}
