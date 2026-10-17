package com.clipcut.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A small tappable label: "→ Qazi", or "Who is this for?" when the tagline or hashtag has no contestant yet. */
@Composable
fun ContestantPill(name: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = if (name != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
    Surface(color = color.copy(alpha = 0.14f), shape = CircleShape, modifier = modifier.clickable(onClick = onClick)) {
        Text(
            if (name != null) "→ $name" else "Who is this for?",
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Links a tagline or hashtag to a contestant, for the weekly ranking. Names used before are one tap away. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContestantDialog(text: String, current: String?, known: List<String>, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(current.orEmpty()) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Who is this for?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Its counts go to this contestant in the weekly ranking. Fixing it here fixes past runs too.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(name, { name = it }, label = { Text("Contestant") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (known.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        known.forEach { k -> AssistChip(onClick = { name = k }, label = { Text(k) }) }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(name) }) { Text(if (name.isBlank()) "Remove link" else "Save") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
