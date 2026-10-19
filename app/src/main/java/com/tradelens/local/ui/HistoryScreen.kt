package com.tradelens.local.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tradelens.local.data.AnalysisDao
import com.tradelens.local.data.AnalysisEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(analysisDao: AnalysisDao) {
    val analyses by remember(analysisDao) {
        analysisDao.observeNewestFirst()
    }.collectAsState(initial = emptyList())
    var selected by remember { mutableStateOf<AnalysisEntity?>(null) }

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Analysis history", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Stored locally on this device. Tap an item to see its captured text.",
                style = MaterialTheme.typography.bodyMedium
            )
            if (analyses.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text("No analyses yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Run an analysis from the floating overlay after setting up a model.",
                            modifier = Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(analyses, key = { it.id }) { item ->
                        AnalysisHistoryCard(item = item, onClick = { selected = item })
                    }
                }
            }
        }
    }

    selected?.let { item ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = {
                Text(
                    listOf(item.ticker.ifBlank { "Analysis" }, formatDate(item.timestamp))
                        .joinToString(" · ")
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Price: ${item.price.ifBlank { "Not extracted" }}")
                    Text("Risk: ${item.risk.takeIf { it > 0 }?.let { "$it / 10" } ?: "Not parsed"}")
                    Text("Summary: ${item.aiSummary}")
                    Text("Inference: ${item.inferenceMs} ms")
                    Text("Captured screen text:\n${item.rawScreenText}")
                }
            },
            confirmButton = {
                TextButton(onClick = { selected = null }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun AnalysisHistoryCard(item: AnalysisEntity, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    item.ticker.ifBlank { "Analysis" },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(formatDate(item.timestamp), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                item.price.ifBlank { "Price unavailable" },
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                item.aiSummary.take(180),
                maxLines = 3,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Risk ${item.risk.takeIf { it > 0 }?.let { "$it / 10" } ?: "not parsed"} · " +
                    "${item.inferenceMs} ms · on-device",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

private fun formatDate(timestamp: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(timestamp))
