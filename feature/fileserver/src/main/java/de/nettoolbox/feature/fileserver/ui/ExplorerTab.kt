package de.nettoolbox.feature.fileserver.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.nettoolbox.core.ui.icon.NetToolboxIcons
import de.nettoolbox.core.ui.theme.MonospaceTextStyle
import de.nettoolbox.feature.fileserver.R
import de.nettoolbox.feature.fileserver.domain.ChecksumAlgorithm
import de.nettoolbox.feature.fileserver.domain.FileEntry
import de.nettoolbox.feature.fileserver.domain.SortOrder

/**
 * The file explorer.
 *
 * Selection is by checkbox rather than by long press. Long press is the phone
 * convention, but this screen is used with one hand while holding a console
 * cable, and a gesture that has to be held is the one that fails in that
 * situation. The checkboxes also make it obvious that multi-select exists at
 * all.
 */
@Composable
internal fun ExplorerTab(viewModel: FileServerViewModel) {
    val state by viewModel.explorer.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val checksum by viewModel.checksum.collectAsStateWithLifecycle()

    var showNewFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<FileEntry?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }

    var pendingExport by remember { mutableStateOf<FileEntry?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        // CreateDocument lets the user put the file anywhere the system can
        // reach - Downloads, Drive, a network share - with no storage
        // permission involved. It is the counterpart to the import below, and
        // together they are what makes an app-private share root workable.
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
        onResult = { uri ->
            val entry = pendingExport
            pendingExport = null
            if (uri != null && entry != null) viewModel.exportTo(entry.relativePath, uri)
        },
    )

    val importLauncher = rememberLauncherForActivityResult(
        // OpenMultipleDocuments rather than GetContent: it returns real
        // documents from any provider the device has, including network shares
        // mounted by another app, and it needs no storage permission.
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris -> if (uris.isNotEmpty()) viewModel.importFrom(uris) },
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Breadcrumb(
            segments = state.segments,
            onRoot = { viewModel.navigateTo("/") },
            onSegment = viewModel::navigateToSegment,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Text(
                text = if (state.selectionCount > 0) {
                    stringResource(R.string.explorer_selected, state.selectionCount)
                } else {
                    stringResource(R.string.explorer_item_count, state.entries.size)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.explorer_free_space, formatBytes(state.freeSpaceBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { showSort = true }) {
                Icon(
                    painter = painterResource(NetToolboxIcons.History),
                    contentDescription = stringResource(R.string.explorer_sort),
                )
            }
            IconButton(onClick = viewModel::refresh) {
                Icon(
                    painter = painterResource(NetToolboxIcons.Refresh),
                    contentDescription = stringResource(R.string.explorer_refresh),
                )
            }
        }

        ActionRow(
            state = state,
            singleSelectedFile = state.entries.firstOrNull {
                it.relativePath in state.selection && !it.isDirectory
            }?.takeIf { state.selectionCount == 1 },
            onExport = { entry ->
                pendingExport = entry
                exportLauncher.launch(entry.name)
            },
            onNewFolder = { showNewFolder = true },
            onImport = { importLauncher.launch(arrayOf("*/*")) },
            onCopy = viewModel::copy,
            onCut = viewModel::cut,
            onPaste = viewModel::paste,
            onDelete = { confirmDelete = true },
            onSelectAll = viewModel::selectAll,
            onClearSelection = viewModel::clearSelection,
        )

        state.copyProgress?.let { progress ->
            CopyProgressCard(progress = progress, onCancel = viewModel::cancelTransfer)
        }

        HorizontalDivider()

        when {
            state.isLoading -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }

            state.entries.isEmpty() -> EmptyFolder(isRoot = state.isRoot)

            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (!state.isRoot) {
                    item {
                        UpRow(onClick = viewModel::navigateUp)
                    }
                }
                items(state.entries, key = { it.relativePath }) { entry ->
                    EntryRow(
                        entry = entry,
                        selected = entry.relativePath in state.selection,
                        onOpen = { viewModel.open(entry) },
                        onToggle = { viewModel.toggleSelection(entry) },
                        onRename = { renaming = entry },
                        onChecksum = {
                            viewModel.computeChecksum(entry, ChecksumAlgorithm.SHA256)
                        },
                    )
                }
            }
        }
    }

    if (showNewFolder) {
        TextPromptDialog(
            title = stringResource(R.string.explorer_new_folder),
            label = stringResource(R.string.explorer_folder_name),
            initial = "",
            onConfirm = { name ->
                viewModel.createDirectory(name)
                showNewFolder = false
            },
            onDismiss = { showNewFolder = false },
        )
    }

    renaming?.let { entry ->
        TextPromptDialog(
            title = stringResource(R.string.explorer_rename),
            label = stringResource(R.string.explorer_new_name),
            initial = entry.name,
            onConfirm = { name ->
                viewModel.rename(entry, name)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.explorer_confirm_delete_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.explorer_confirm_delete_body,
                        state.selectionCount,
                        state.selectionCount,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSelection()
                    confirmDelete = false
                }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showSort) {
        SortDialog(
            current = state.sortOrder,
            showHidden = state.showHidden,
            onSelect = { order ->
                viewModel.setSortOrder(order)
                showSort = false
            },
            onShowHidden = viewModel::setShowHidden,
            onDismiss = { showSort = false },
        )
    }

    preview?.let { PreviewDialog(it, viewModel::dismissPreview) }
    checksum?.let { ChecksumDialog(it, viewModel::dismissChecksum) }
}

@Composable
private fun Breadcrumb(
    segments: List<String>,
    onRoot: () -> Unit,
    onSegment: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.explorer_root),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (segments.isEmpty()) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.clickable(onClick = onRoot),
        )
        segments.forEachIndexed { index, segment ->
            Text(
                text = "  /  ",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = segment,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (index == segments.lastIndex) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clickable { onSegment(index) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionRow(
    state: ExplorerState,
    /** Non-null only when exactly one file - not a folder - is selected. */
    singleSelectedFile: FileEntry?,
    onExport: (FileEntry) -> Unit,
    onNewFolder: () -> Unit,
    onImport: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onDelete: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
) {
    // Wraps instead of scrolling sideways. A horizontally scrolled row hides
    // the chips past the edge with nothing to suggest they are there, and on a
    // narrow screen that was most of them.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        AssistChip(onClick = onImport, label = { Text(stringResource(R.string.explorer_import)) })
        AssistChip(onClick = onNewFolder, label = { Text(stringResource(R.string.explorer_new_folder)) })

        if (state.selectionCount > 0) {
            AssistChip(onClick = onCopy, label = { Text(stringResource(R.string.explorer_copy)) })
            AssistChip(onClick = onCut, label = { Text(stringResource(R.string.explorer_cut)) })
            AssistChip(onClick = onDelete, label = { Text(stringResource(R.string.explorer_delete)) })
            singleSelectedFile?.let { entry ->
                AssistChip(
                    onClick = { onExport(entry) },
                    label = { Text(stringResource(R.string.explorer_export)) },
                )
            }
            AssistChip(
                onClick = onClearSelection,
                label = { Text(stringResource(R.string.explorer_clear_selection)) },
            )
        } else {
            AssistChip(
                onClick = onSelectAll,
                label = { Text(stringResource(R.string.explorer_select_all)) },
            )
        }

        if (state.clipboard.isNotEmpty()) {
            AssistChip(
                onClick = onPaste,
                label = {
                    Text(stringResource(R.string.explorer_paste_pending, state.clipboard.size))
                },
            )
        }
    }
}

@Composable
private fun CopyProgressCard(
    progress: de.nettoolbox.feature.fileserver.domain.CopyProgress,
    onCancel: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.explorer_copying, progress.currentName),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            Text(
                text = stringResource(
                    R.string.explorer_copy_progress,
                    progress.filesDone,
                    progress.filesTotal,
                    formatBytes(progress.bytesDone),
                    formatBytes(progress.bytesTotal),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = {
                    if (progress.bytesTotal <= 0) {
                        0f
                    } else {
                        progress.bytesDone.toFloat() / progress.bytesTotal
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
            TextButton(onClick = onCancel, modifier = Modifier.padding(top = 4.dp)) {
                Text(stringResource(R.string.explorer_cancel_copy))
            }
        }
    }
}

@Composable
private fun UpRow(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(
            painter = painterResource(NetToolboxIcons.Folder),
            contentDescription = stringResource(R.string.explorer_up),
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = "..",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun EntryRow(
    entry: FileEntry,
    selected: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onChecksum: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Icon(
            painter = painterResource(
                if (entry.isDirectory) NetToolboxIcons.Folder else NetToolboxIcons.File,
            ),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
            )
            Text(
                text = if (entry.isDirectory) {
                    formatDate(entry.modifiedAtMillis)
                } else {
                    "${formatBytes(entry.sizeBytes)}  ·  ${formatDate(entry.modifiedAtMillis)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        IconButton(onClick = onRename) {
            Icon(
                painter = painterResource(NetToolboxIcons.Copy),
                contentDescription = stringResource(R.string.explorer_rename),
            )
        }
        if (!entry.isDirectory) {
            IconButton(onClick = onChecksum) {
                Icon(
                    painter = painterResource(NetToolboxIcons.Lock),
                    contentDescription = stringResource(R.string.explorer_checksum),
                )
            }
        }
    }
}

@Composable
private fun EmptyFolder(isRoot: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.explorer_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        if (isRoot) {
            Text(
                text = stringResource(R.string.explorer_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SortDialog(
    current: SortOrder,
    showHidden: Boolean,
    onSelect: (SortOrder) -> Unit,
    onShowHidden: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.explorer_sort)) },
        text = {
            Column {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = current == SortOrder.NAME,
                        onClick = { onSelect(SortOrder.NAME) },
                        label = { Text(stringResource(R.string.explorer_sort_name)) },
                    )
                    FilterChip(
                        selected = current == SortOrder.SIZE,
                        onClick = { onSelect(SortOrder.SIZE) },
                        label = { Text(stringResource(R.string.explorer_sort_size)) },
                    )
                    FilterChip(
                        selected = current == SortOrder.MODIFIED,
                        onClick = { onSelect(SortOrder.MODIFIED) },
                        label = { Text(stringResource(R.string.explorer_sort_modified)) },
                    )
                    FilterChip(
                        selected = current == SortOrder.KIND,
                        onClick = { onSelect(SortOrder.KIND) },
                        label = { Text(stringResource(R.string.explorer_sort_kind)) },
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Checkbox(checked = showHidden, onCheckedChange = onShowHidden)
                    Text(
                        text = stringResource(R.string.explorer_show_hidden),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun PreviewDialog(state: PreviewState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.entry.name, maxLines = 1) },
        text = {
            when {
                state.isLoading -> CircularProgressIndicator()
                state.preview?.isBinary == true ->
                    Text(stringResource(R.string.explorer_preview_binary))

                else -> Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (state.preview?.truncated == true) {
                        Text(
                            text = stringResource(
                                R.string.explorer_preview_truncated,
                                formatBytes(state.preview.totalBytes),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    Text(
                        text = state.preview?.text.orEmpty(),
                        style = MonospaceTextStyle,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun ChecksumDialog(state: ChecksumState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.explorer_checksum_title, state.entry.name)) },
        text = {
            Column {
                if (state.value == null) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        text = "${state.algorithm.label}\n${state.value}",
                        style = MonospaceTextStyle,
                    )
                }
                Text(
                    text = stringResource(R.string.explorer_checksum_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
        dismissButton = {
            state.value?.let { value ->
                TextButton(onClick = { context.copyToClipboard(value) }) {
                    Text(stringResource(R.string.explorer_checksum_copy))
                }
            }
        },
    )
}

@Composable
internal fun TextPromptDialog(
    title: String,
    label: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim()) },
                enabled = value.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
