package com.tradelens.local.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tradelens.local.data.AnalysisDao
import com.tradelens.local.data.AnalysisEntity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(analysisDao: AnalysisDao) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val analyses by remember(analysisDao) {
        analysisDao.observeNewestFirst()
    }.collectAsState(initial = emptyList())
    var selected by remember { mutableStateOf<AnalysisEntity?>(null) }
    var showClearConfirmation by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Analysis history", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Stored locally on this device (${analyses.size} saved)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (analyses.isNotEmpty()) {
                    OutlinedButton(onClick = { showClearConfirmation = true }) {
                        Text("Clear all")
                    }
                }
            }

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

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("Clear all history?") },
            text = { Text("This will permanently remove all saved analysis records from this device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            analysisDao.deleteAll()
                            showClearConfirmation = false
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear all")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
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
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Price: ${item.price.ifBlank { "Not extracted" }}", style = MaterialTheme.typography.bodyMedium)
                    Text("Risk: ${item.risk.takeIf { it > 0 }?.let { "$it / 10" } ?: "Not parsed"}", style = MaterialTheme.typography.bodyMedium)
                    Text("Summary:\n${item.aiSummary}", style = MaterialTheme.typography.bodyMedium)
                    Text("Inference time: ${item.inferenceMs} ms", style = MaterialTheme.typography.bodySmall)
                    Text("Captured screen text:\n${item.rawScreenText}", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clipText = "TradeLens Analysis: ${item.ticker} (${item.price})\nRisk: ${item.risk}/10\n${item.aiSummary}"
                            clipboard?.setPrimaryClip(ClipData.newPlainText("TradeLens", clipText))
                            Toast.makeText(context, "Analysis copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("Copy")
                    }
                    TextButton(
                        onClick = {
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, "TradeLens Analysis: ${item.ticker} (${item.price})\nRisk: ${item.risk}/10\n\n${item.aiSummary}")
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Share analysis"))
                        }
                    ) {
                        Text("Share")
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                analysisDao.deleteById(item.id)
                                selected = null
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete")
                    }
                    TextButton(onClick = { selected = null }) {
                        Text("Close")
                    }
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    item.ticker.ifBlank { "Analysis" },
                    style = MaterialTheme.typography.titleMedium
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (item.risk > 0) {
                        RiskBadge(risk = item.risk)
                    }
                    Text(formatDate(item.timestamp), style = MaterialTheme.typography.bodySmall)
                }
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
                "${item.inferenceMs} ms · on-device",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun RiskBadge(risk: Int) {
    val (bgColor, textColor, label) = when {
        risk <= 3 -> Triple(Color(0xFFE8F5E9), Color(0xFF2E7D32), "Low $risk")
        risk <= 6 -> Triple(Color(0xFFFFF3E0), Color(0xFFE65100), "Mod $risk")
        else -> Triple(Color(0xFFFFEBEE), Color(0xFFC62828), "High $risk")
    }
    Box(
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(label, color = textColor, style = MaterialTheme.typography.labelSmall)
    }
}

private fun formatDate(timestamp: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(timestamp))
