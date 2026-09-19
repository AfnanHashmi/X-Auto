package com.clipcut.ui.deletion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.clipcut.data.db.DeletedPost
import com.clipcut.data.db.DeletedPostDao
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class DeletionHistoryViewModel @Inject constructor(dao: DeletedPostDao) : ViewModel() {
    val rows = dao.observeAll()
}

private val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm")

/** Read-only. There is deliberately no way to remove a row: that would re-arm the id for a later find. */
@Composable
fun DeletionHistoryScreen(
    modifier: Modifier = Modifier,
    viewModel: DeletionHistoryViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
) {
    val rows by viewModel.rows.collectAsState(initial = emptyList())
    val zone = ZoneId.systemDefault()
    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Deletion history", style = MaterialTheme.typography.titleLarge)
        if (rows.isEmpty()) Text("Nothing deleted yet.")
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = { it.tweetId }) { r ->
                val outcome = when (r.outcome) {
                    DeletedPost.DELETED -> "deleted"
                    DeletedPost.ALREADY_GONE -> "already gone"
                    else -> "UNCONFIRMED - check on X"
                }
                Column {
                    Text(
                        r.bbNumber?.let { "BB LOGS - %03d".format(it) } ?: r.text.take(40),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "posted ${WHEN.format(Instant.ofEpochMilli(r.postedAtEpochMs).atZone(zone))}  -  " +
                            "$outcome ${WHEN.format(Instant.ofEpochMilli(r.deletedAtEpochMs).atZone(zone))}  -  id ${r.tweetId}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        TextButton(onClick = onBack) { Text("Back") }
    }
}
