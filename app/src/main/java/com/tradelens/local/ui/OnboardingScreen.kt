package com.tradelens.local.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tradelens.local.data.AppSettings

@Composable
fun OnboardingScreen(
    settings: AppSettings,
    accessibilityEnabled: Boolean,
    overlayPermissionGranted: Boolean,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    onImportModel: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onRequestNotifications: () -> Unit,
    onAcceptDisclaimer: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                text = "TradeLens Local",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "A private, on-device reading companion for your trading screen.",
                style = MaterialTheme.typography.bodyLarge
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Text("Educational use only", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "TradeLens Local is for education only and is not financial advice. " +
                            "It never places trades or interacts with your trading app. Local AI " +
                            "can be wrong; verify every value and interpretation independently. " +
                            "Screen text and analysis stay on this device. Network access is used " +
                            "only when you choose to download a model.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Text("Set up permissions", style = MaterialTheme.typography.titleLarge)
            PermissionRow(
                label = "Accessibility service",
                detail = if (accessibilityEnabled) "Enabled" else "Not enabled",
                onClick = onOpenAccessibilitySettings
            )
            PermissionRow(
                label = "Floating overlay",
                detail = if (overlayPermissionGranted) "Allowed" else "Not allowed",
                onClick = onOpenOverlaySettings
            )
            PermissionRow(
                label = "Notifications",
                detail = "Needed for the persistent Android service notification",
                onClick = onRequestNotifications
            )
            OutlinedTextField(
                value = settings.targetPackage,
                onValueChange = { value ->
                    onSettingsChange { it.copy(targetPackage = value.trim()) }
                },
                label = { Text("Trading app package name(s)") },
                placeholder = { Text("For example: com.tradingview.tradingviewapp") },
                supportingText = {
                    Text("The accessibility reader monitors only configured packages. Comma-separated for multiple.")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "TradingView" to "com.tradingview.tradingviewapp",
                    "Binance" to "com.binance.dev",
                    "Webull" to "org.dayup.stocks",
                    "MetaTrader 5" to "net.metaquotes.metatrader5",
                    "Robinhood" to "com.robinhood.android",
                    "Interactive Brokers" to "com.interactivebrokers.mobile.charlotte"
                ).forEach { (name, pkg) ->
                    AssistChip(
                        onClick = { onSettingsChange { it.copy(targetPackage = pkg) } },
                        label = { Text(name) }
                    )
                }
            }
            ModelManagementSection(
                settings = settings,
                onSettingsChange = onSettingsChange,
                onImportModel = onImportModel,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onAcceptDisclaimer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Accept and continue")
            }
            Text(
                text = "You can change permissions, the model, and inference settings later.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    detail: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = onClick) {
            Text("Open")
        }
    }
}
