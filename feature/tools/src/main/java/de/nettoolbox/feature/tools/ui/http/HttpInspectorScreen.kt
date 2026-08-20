package de.nettoolbox.feature.tools.ui.http

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.nettoolbox.core.ui.component.ResultActionBar
import de.nettoolbox.core.ui.error.errorHint
import de.nettoolbox.core.ui.error.errorTitle
import de.nettoolbox.core.ui.export.exportFileName
import de.nettoolbox.core.ui.export.rememberFileExporter
import de.nettoolbox.core.ui.theme.MonospaceTextStyle
import de.nettoolbox.feature.tools.R
import de.nettoolbox.feature.tools.domain.http.CertificateInfo
import de.nettoolbox.feature.tools.domain.http.HttpStep
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HttpInspectorScreen(
    modifier: Modifier = Modifier,
    viewModel: HttpInspectorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val exporter = rememberFileExporter(content = viewModel::asPlainText)

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = uiState.url,
            onValueChange = viewModel::onUrlChange,
            label = { Text(stringResource(R.string.http_url_label)) },
            placeholder = { Text("example.com") },
            supportingText = { Text(stringResource(R.string.http_url_hint)) },
            singleLine = true,
            enabled = !uiState.isRunning,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            HttpMethod.entries.forEach { method ->
                FilterChip(
                    selected = uiState.method == method,
                    onClick = { viewModel.onMethodChange(method) },
                    enabled = !uiState.isRunning,
                    label = { Text(method.name) },
                )
            }
        }

        Button(
            onClick = { if (uiState.isRunning) viewModel.stop() else viewModel.start() },
            enabled = uiState.isRunning || uiState.canStart,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            Text(
                stringResource(
                    if (uiState.isRunning) R.string.action_stop else R.string.http_inspect,
                ),
            )
        }

        uiState.error?.let { error ->
            Text(
                text = errorTitle(error),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = errorHint(error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val inspection = uiState.inspection
        if (inspection != null) {
            LazyColumn(modifier = Modifier.weight(1f).padding(top = 12.dp)) {
                if (inspection.loopDetected) {
                    item { WarningLine(stringResource(R.string.http_loop_detected)) }
                }
                if (inspection.redirectLimitReached) {
                    item { WarningLine(stringResource(R.string.http_limit_reached)) }
                }

                items(inspection.steps) { step -> StepCard(step) }

                item {
                    Text(
                        text = stringResource(
                            R.string.http_total,
                            inspection.steps.size,
                            inspection.totalElapsedMillis,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }

            ResultActionBar(
                plainText = viewModel.asPlainText(),
                onExport = { exporter.export(exportFileName("http", "txt")) },
            )
        }
    }
}

@Composable
private fun WarningLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun StepCard(step: HttpStep) {
    var showHeaders by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(text = step.url, style = MaterialTheme.typography.bodyMedium)

            Text(
                text = "${step.protocol} · ${step.statusCode} ${step.statusMessage} · ${step.elapsedMillis} ms",
                style = MaterialTheme.typography.labelMedium,
                color = if (step.statusCode >= 400) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 4.dp),
            )

            step.location?.let { location ->
                Text(
                    text = stringResource(R.string.http_redirects_to, location),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            step.tls?.let { tls ->
                Text(
                    text = "${tls.version} · ${tls.cipherSuite}",
                    style = MonospaceTextStyle,
                    modifier = Modifier.padding(top = 8.dp),
                )
                tls.leaf?.let { CertificateSummary(it) }
            }

            TextButton(onClick = { showHeaders = !showHeaders }) {
                Text(
                    stringResource(
                        if (showHeaders) R.string.http_hide_headers else R.string.http_show_headers,
                        step.headers.size,
                    ),
                )
            }

            if (showHeaders) {
                step.headers.forEach { header ->
                    Text(
                        text = "${header.name}: ${header.value}",
                        style = MonospaceTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CertificateSummary(certificate: CertificateInfo) {
    val days = certificate.daysUntilExpiry()
    val expiringSoon = days in 0..EXPIRY_WARNING_DAYS
    val expired = certificate.isExpired()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(text = certificate.subject, style = MonospaceTextStyle)
            Text(
                text = stringResource(R.string.http_issuer, certificate.issuer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    R.string.http_valid_until,
                    DATE_FORMAT.format(Date(certificate.notAfterMillis)),
                    days,
                ),
                style = MaterialTheme.typography.labelMedium,
                // The expiry date is the number people open this tool for, so a
                // certificate about to lapse says so in colour and in words.
                color = when {
                    expired || expiringSoon -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (expired) {
                Text(
                    text = stringResource(R.string.http_expired),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (certificate.isNotYetValid()) {
                Text(
                    text = stringResource(R.string.http_not_yet_valid),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (certificate.subjectAlternativeNames.isNotEmpty()) {
                Text(
                    text = certificate.subjectAlternativeNames.joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

private const val EXPIRY_WARNING_DAYS = 30L
private val DATE_FORMAT = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
