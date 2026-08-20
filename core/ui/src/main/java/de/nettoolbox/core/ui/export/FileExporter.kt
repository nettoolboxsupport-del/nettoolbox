package de.nettoolbox.core.ui.export

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** MIME types the tools export. */
object ExportMimeType {
    const val PLAIN_TEXT = "text/plain"
    const val CSV = "text/csv"
    const val JSON = "application/json"
}

/**
 * Writes a tool result to a file the user picks.
 *
 * Storage Access Framework rather than a path: the app needs no storage
 * permission, the user decides where the file lands, and nothing is written
 * without an explicit pick - the condition the spec's privacy section puts on
 * exports.
 */
@Stable
class FileExporter internal constructor(
    private val onRequestFile: (String) -> Unit,
) {
    /** @param suggestedFileName including the extension */
    fun export(suggestedFileName: String) = onRequestFile(suggestedFileName)
}

/**
 * @param mimeType a concrete type, never a wildcard: the system picker renders
 *   an empty screen on some devices when the requested type is unspecific
 * @param content produced lazily, after a destination was picked, so a cancelled
 *   pick costs nothing. Suspending, because some exports have to read their data
 *   from the database first.
 * @param onResult true when the file was written; false carries the failure, or
 *   null when the user simply cancelled
 */
@Composable
fun rememberFileExporter(
    content: suspend () -> String,
    mimeType: String = ExportMimeType.PLAIN_TEXT,
    onResult: (Boolean, Throwable?) -> Unit = { _, _ -> },
): FileExporter {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(mimeType),
    ) { uri ->
        if (uri == null) {
            onResult(false, null)
            return@rememberLauncherForActivityResult
        }

        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(content().toByteArray())
                    } ?: error("Could not open the selected file for writing")
                }.exceptionOrNull()
            }
            onResult(failure == null, failure)
        }
    }

    return remember(launcher) {
        FileExporter { suggestedFileName ->
            // A device without a documents provider throws rather than showing a
            // picker; reporting that beats an unexplained blank screen.
            runCatching { launcher.launch(suggestedFileName) }
                .onFailure { throwable ->
                    if (throwable is ActivityNotFoundException) {
                        onResult(false, throwable)
                    } else {
                        throw throwable
                    }
                }
        }
    }
}

/** `nettoolbox-ping-20260817-142530.txt` - sortable and self-describing. */
fun exportFileName(toolName: String, extension: String): String {
    val timestamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT)
        .format(java.util.Date())
    return "nettoolbox-$toolName-$timestamp.$extension"
}
