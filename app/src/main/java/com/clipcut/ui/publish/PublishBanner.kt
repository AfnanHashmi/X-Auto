package com.clipcut.ui.publish

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clipcut.domain.publish.PublishStatus
import com.clipcut.ui.components.StatusRow
import com.clipcut.ui.components.Tone

/**
 * A small line beside the results saying whether the website has them. It never hides or delays the results: when
 * the update fails the results above are exactly as they were, and Retry only resends the same payload.
 */
@Composable
fun PublishBanner(status: PublishStatus, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    when (status) {
        PublishStatus.Idle -> Unit
        PublishStatus.Sending -> Text("Updating website…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
        is PublishStatus.Done -> StatusRow("Website updated ✓", Tone.Good, modifier)
        is PublishStatus.Failed -> Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Not updated", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text("Retry") }
            }
            Text(status.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
