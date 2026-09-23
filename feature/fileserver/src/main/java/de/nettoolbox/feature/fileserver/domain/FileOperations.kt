package de.nettoolbox.feature.fileserver.domain

import de.nettoolbox.core.common.storage.ShareStorage
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import de.nettoolbox.core.common.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** What went wrong, as data. Rendered by the UI, never thrown at the user as a stack trace. */
sealed interface FileOpError {
    data object OutsideRoot : FileOpError
    data object AlreadyExists : FileOpError
    data object NotFound : FileOpError
    data object NotWritable : FileOpError
    data object InvalidName : FileOpError
    data object TargetInsideSource : FileOpError
    data class Io(val message: String?) : FileOpError
}

sealed interface FileOpResult<out T> {
    data class Ok<out T>(val value: T) : FileOpResult<T>
    data class Failed(val error: FileOpError) : FileOpResult<Nothing>
}

/** Progress of a long copy, so a 900 MB firmware image does not look frozen. */
data class CopyProgress(
    val currentName: String,
    val filesDone: Int,
    val filesTotal: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
)

/**
 * Everything the explorer does to the filesystem.
 *
 * All of it runs on the IO dispatcher and all of it goes through
 * [ShareStorage.resolve]: the explorer and the servers share one root and one
 * containment check, so a path the servers refuse cannot be reached by tapping
 * either.
 */
@Singleton
class FileOperations @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val storage: ShareStorage,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) {

    suspend fun list(
        relativePath: String,
        sortOrder: SortOrder = SortOrder.NAME,
        ascending: Boolean = true,
        showHidden: Boolean = false,
    ): FileOpResult<List<FileEntry>> = withContext(dispatcher) {
        val dir = storage.resolve(relativePath) ?: return@withContext failed(FileOpError.OutsideRoot)
        if (!dir.exists()) return@withContext failed(FileOpError.NotFound)

        // listFiles() returns null both for "not a directory" and for "could not
        // read it". Those are different answers and the caller deserves the
        // right one, so the directory test comes first.
        if (!dir.isDirectory) return@withContext failed(FileOpError.NotFound)
        val children = dir.listFiles() ?: return@withContext failed(FileOpError.NotWritable)

        val base = storage.relativeOf(dir).trimEnd('/')
        val entries = children
            .asSequence()
            .filter { showHidden || !it.name.startsWith(".") }
            .map { it.toEntry("$base/${it.name}") }
            .toList()

        FileOpResult.Ok(entries.sortedWith(comparatorFor(sortOrder, ascending)))
    }

    suspend fun createDirectory(parentPath: String, name: String): FileOpResult<FileEntry> =
        withContext(dispatcher) {
            val validation = validateName(name)
            if (validation != null) return@withContext failed(validation)
            val parent = storage.resolve(parentPath) ?: return@withContext failed(FileOpError.OutsideRoot)
            val target = storage.resolve("$parentPath/$name") ?: return@withContext failed(FileOpError.OutsideRoot)
            if (target.exists()) return@withContext failed(FileOpError.AlreadyExists)
            if (!parent.canWrite()) return@withContext failed(FileOpError.NotWritable)
            if (!target.mkdirs()) return@withContext failed(FileOpError.Io(null))
            FileOpResult.Ok(target.toEntry(storage.relativeOf(target)))
        }

    suspend fun rename(relativePath: String, newName: String): FileOpResult<FileEntry> =
        withContext(dispatcher) {
            val validation = validateName(newName)
            if (validation != null) return@withContext failed(validation)
            val source = storage.resolve(relativePath) ?: return@withContext failed(FileOpError.OutsideRoot)
            if (!source.exists()) return@withContext failed(FileOpError.NotFound)
            val target = File(source.parentFile, newName)
            if (storage.resolve(storage.relativeOf(target)) == null) {
                return@withContext failed(FileOpError.OutsideRoot)
            }
            // Case-only renames on a case-insensitive volume look like "already
            // exists" but are legitimate, so the same file is not a collision.
            if (target.exists() && target.canonicalPath != source.canonicalPath) {
                return@withContext failed(FileOpError.AlreadyExists)
            }
            if (!source.renameTo(target)) return@withContext failed(FileOpError.Io(null))
            FileOpResult.Ok(target.toEntry(storage.relativeOf(target)))
        }

    suspend fun delete(relativePaths: List<String>): FileOpResult<Int> = withContext(dispatcher) {
        var removed = 0
        for (path in relativePaths) {
            currentCoroutineContext().ensureActive()
            val file = storage.resolve(path) ?: return@withContext failed(FileOpError.OutsideRoot)
            if (!file.exists()) continue
            if (!deleteRecursively(file)) return@withContext failed(FileOpError.Io(file.name))
            removed++
        }
        FileOpResult.Ok(removed)
    }

    /**
     * Copies or moves a selection into [targetDirectory].
     *
     * A move is attempted as a rename first and only falls back to copy-then-
     * delete when that fails, which it does across storage volumes. Doing it
     * the other way round would rewrite gigabytes for what the kernel can do by
     * relinking an inode.
     */
    suspend fun transfer(
        sourcePaths: List<String>,
        targetDirectory: String,
        move: Boolean,
        onProgress: (CopyProgress) -> Unit = {},
    ): FileOpResult<Int> = withContext(dispatcher) {
        val target = storage.resolve(targetDirectory) ?: return@withContext failed(FileOpError.OutsideRoot)
        if (!target.isDirectory) return@withContext failed(FileOpError.NotFound)
        if (!target.canWrite()) return@withContext failed(FileOpError.NotWritable)

        val sources = sourcePaths.map { path ->
            storage.resolve(path) ?: return@withContext failed(FileOpError.OutsideRoot)
        }

        // Copying a directory into itself builds an infinitely deep tree and
        // fills the device. Caught before a single byte moves.
        val targetCanonical = target.canonicalPath
        for (source in sources) {
            if (!source.exists()) return@withContext failed(FileOpError.NotFound)
            if (source.isDirectory && targetCanonical.startsWith(source.canonicalPath + File.separator)) {
                return@withContext failed(FileOpError.TargetInsideSource)
            }
            if (source.canonicalPath == targetCanonical) {
                return@withContext failed(FileOpError.TargetInsideSource)
            }
        }

        val totalBytes = sources.sumOf { sizeOf(it) }
        val totalFiles = sources.sumOf { countFiles(it) }
        var doneBytes = 0L
        var doneFiles = 0

        for (source in sources) {
            currentCoroutineContext().ensureActive()
            val destination = uniqueDestination(target, source.name)

            if (move && source.renameTo(destination)) {
                doneFiles += countFiles(destination)
                doneBytes += sizeOf(destination)
                onProgress(CopyProgress(source.name, doneFiles, totalFiles, doneBytes, totalBytes))
                continue
            }

            val copied = copyTree(source, destination) { name, bytes ->
                doneBytes += bytes
                onProgress(CopyProgress(name, doneFiles, totalFiles, doneBytes, totalBytes))
            }
            if (!copied) return@withContext failed(FileOpError.Io(source.name))
            doneFiles += countFiles(destination)

            if (move && !deleteRecursively(source)) {
                // The copy succeeded, so no data was lost - but reporting
                // success would leave a duplicate the user never asked for.
                return@withContext failed(FileOpError.Io(source.name))
            }
        }
        FileOpResult.Ok(doneFiles)
    }

    /**
     * Brings a file in from anywhere the system file picker can reach.
     *
     * This is what makes the app-private root workable: Downloads, Drive, a
     * network share mounted by another app - all of them are one picker away,
     * and none of them needs a storage permission.
     */
    suspend fun importFrom(uri: Uri, targetDirectory: String): FileOpResult<FileEntry> =
        withContext(dispatcher) {
            val target = storage.resolve(targetDirectory) ?: return@withContext failed(FileOpError.OutsideRoot)
            if (!target.isDirectory) return@withContext failed(FileOpError.NotFound)
            // The cleaned name contains no path separator, so the destination
            // is a direct child of a directory resolve() already accepted.
            val name = safeImportName(displayNameOf(context.contentResolver, uri))
            val destination = uniqueDestination(target, name)
            try {
                context.contentResolver.openInputStream(uri).use { input ->
                    if (input == null) return@withContext failed(FileOpError.NotFound)
                    destination.outputStream().use { output -> input.copyTo(output, COPY_BUFFER) }
                }
            } catch (io: IOException) {
                destination.delete()
                return@withContext failed(FileOpError.Io(io.message))
            }
            FileOpResult.Ok(destination.toEntry(storage.relativeOf(destination)))
        }

    /** Writes a file out to a destination the user picked. */
    suspend fun exportTo(relativePath: String, uri: Uri): FileOpResult<Long> =
        withContext(dispatcher) {
            val source = storage.resolve(relativePath) ?: return@withContext failed(FileOpError.OutsideRoot)
            if (!source.isFile) return@withContext failed(FileOpError.NotFound)
            try {
                context.contentResolver.openOutputStream(uri).use { output ->
                    if (output == null) return@withContext failed(FileOpError.NotFound)
                    source.inputStream().use { input -> input.copyTo(output, COPY_BUFFER) }
                }
            } catch (io: IOException) {
                return@withContext failed(FileOpError.Io(io.message))
            }
            FileOpResult.Ok(source.length())
        }

    /**
     * Hashes a file.
     *
     * Present because it is the step that closes a firmware transfer: the
     * vendor publishes a checksum, the device computes one after the copy, and
     * without a way to check it here the transfer was never actually verified.
     */
    suspend fun checksum(
        relativePath: String,
        algorithm: ChecksumAlgorithm,
        onProgress: (Float) -> Unit = {},
    ): FileOpResult<String> = withContext(dispatcher) {
        val file = storage.resolve(relativePath) ?: return@withContext failed(FileOpError.OutsideRoot)
        if (!file.isFile) return@withContext failed(FileOpError.NotFound)
        val digest = MessageDigest.getInstance(algorithm.jceName)
        val total = file.length().coerceAtLeast(1L)
        var read = 0L
        try {
            file.inputStream().use { input ->
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    read += count
                    onProgress(read.toFloat() / total)
                }
            }
        } catch (io: IOException) {
            return@withContext failed(FileOpError.Io(io.message))
        }
        FileOpResult.Ok(digest.digest().joinToString("") { "%02x".format(it) })
    }

    /**
     * Reads the head of a text file for the preview.
     *
     * Capped rather than unbounded: the point is to confirm that a config is
     * the config you meant, and loading a 200 MB log into a Compose text field
     * to answer that question would take the app down with it.
     */
    suspend fun previewText(relativePath: String): FileOpResult<TextPreview> =
        withContext(dispatcher) {
            val file = storage.resolve(relativePath) ?: return@withContext failed(FileOpError.OutsideRoot)
            if (!file.isFile) return@withContext failed(FileOpError.NotFound)
            try {
                val bytes = file.inputStream().use { input ->
                    // Not readNBytes: that overload is Java 9 and only reached
                    // Android in API 33, while this module targets 28. A plain
                    // loop is also the only way to be sure the whole preview
                    // window is filled - a single read() may return less.
                    val buffer = ByteArray(PREVIEW_LIMIT_BYTES)
                    var filled = 0
                    while (filled < buffer.size) {
                        val count = input.read(buffer, filled, buffer.size - filled)
                        if (count < 0) break
                        filled += count
                    }
                    buffer.copyOf(filled)
                }
                // A NUL byte in the first block is the classic binary test and
                // is right far more often than trusting the extension.
                val binary = bytes.any { it == 0.toByte() }
                FileOpResult.Ok(
                    TextPreview(
                        text = if (binary) "" else String(bytes, Charsets.UTF_8),
                        isBinary = binary,
                        truncated = file.length() > PREVIEW_LIMIT_BYTES,
                        totalBytes = file.length(),
                    ),
                )
            } catch (io: IOException) {
                failed(FileOpError.Io(io.message))
            }
        }

    /** Free space on the volume holding the share, for the header. */
    suspend fun freeSpaceBytes(): Long = withContext(dispatcher) {
        runCatching { storage.root.usableSpace }.getOrDefault(0L)
    }

    // --- internals ----------------------------------------------------------

    private fun comparatorFor(order: SortOrder, ascending: Boolean): Comparator<FileEntry> {
        val within: Comparator<FileEntry> = when (order) {
            SortOrder.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortOrder.SIZE -> compareBy { it.sizeBytes }
            SortOrder.MODIFIED -> compareBy { it.modifiedAtMillis }
            SortOrder.KIND -> compareBy<FileEntry> { it.kind.ordinal }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        val directed = if (ascending) within else within.reversed()
        // Directories first in both directions. Reversing that too would put
        // folders at the bottom on a descending sort, which no file manager
        // does and nobody expects.
        return compareByDescending<FileEntry> { it.isDirectory }.then(directed)
    }

    private fun validateName(name: String): FileOpError? = when {
        name.isBlank() -> FileOpError.InvalidName
        name == "." || name == ".." -> FileOpError.InvalidName
        name.any { it in ILLEGAL_NAME_CHARACTERS } -> FileOpError.InvalidName
        name.length > MAX_NAME_LENGTH -> FileOpError.InvalidName
        else -> null
    }

    /**
     * Never overwrites. A second "config.bin" becomes "config (1).bin".
     *
     * Silent overwrite is the one file-manager behaviour that destroys work
     * without a way back, and a share that a device is actively pulling from is
     * the worst possible place for it.
     */
    /**
     * The display name a document provider reports, made safe to use as a file
     * name.
     *
     * That name is chosen by whichever app provides the document, and nothing
     * obliges it to be a plain name. Taken as-is, "../../datastore/x.json"
     * would have been written outside the share, over the app's own files -
     * including the file server's account list. Only the last path segment is
     * kept, the characters the rename dialog refuses are replaced, and names
     * that are only dots fall back to a default.
     */
    private fun safeImportName(reported: String?): String {
        val lastSegment = reported
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.map { if (it in ILLEGAL_NAME_CHARACTERS || it.code < 0x20) '_' else it }
            ?.joinToString("")
            ?.trim()
            ?.take(MAX_NAME_LENGTH)
        return if (lastSegment.isNullOrEmpty() || lastSegment.all { it == '.' }) {
            DEFAULT_IMPORT_NAME
        } else {
            lastSegment
        }
    }

    private fun uniqueDestination(directory: File, name: String): File {
        val candidate = File(directory, name)
        if (!candidate.exists()) return candidate
        val stem = name.substringBeforeLast('.', name)
        val suffix = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var index = 1
        while (true) {
            val next = File(directory, "$stem ($index)$suffix")
            if (!next.exists()) return next
            index++
        }
    }

    private fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                if (!deleteRecursively(child)) return false
            }
        }
        return file.delete()
    }

    private suspend fun copyTree(
        source: File,
        destination: File,
        onBytes: (String, Long) -> Unit,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        if (source.isDirectory) {
            if (!destination.mkdirs() && !destination.isDirectory) return false
            source.listFiles()?.forEach { child ->
                if (!copyTree(child, File(destination, child.name), onBytes)) return false
            }
            return true
        }
        return try {
            source.inputStream().use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        onBytes(source.name, count.toLong())
                    }
                }
            }
            // Carried over so a config keeps its date after being reorganised.
            destination.setLastModified(source.lastModified())
            true
        } catch (io: IOException) {
            destination.delete()
            false
        }
    }

    private fun sizeOf(file: File): Long =
        if (file.isDirectory) file.listFiles()?.sumOf { sizeOf(it) } ?: 0L else file.length()

    private fun countFiles(file: File): Int =
        if (file.isDirectory) file.listFiles()?.sumOf { countFiles(it) } ?: 0 else 1

    private fun displayNameOf(resolver: ContentResolver, uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun <T> failed(error: FileOpError): FileOpResult<T> = FileOpResult.Failed(error)

    private companion object {
        const val COPY_BUFFER = 64 * 1024
        const val PREVIEW_LIMIT_BYTES = 256 * 1024
        const val MAX_NAME_LENGTH = 255
        const val DEFAULT_IMPORT_NAME = "imported"

        /**
         * Rejected in names. The forward slash is what the filesystem itself
         * refuses; the rest are the characters that make a file unusable on a
         * Windows client once it is pulled off here - and a share exists to be
         * read by other machines.
         *
         * The space is deliberately absent from this list. It is legal
         * everywhere, vendors put it in released firmware names, and rejecting
         * it would also break the "(1)" suffix this class generates itself.
         */
        val ILLEGAL_NAME_CHARACTERS = charArrayOf(
            '/', '\\', ':', '*', '?', '"', '<', '>', '|',
        ).toSet()
    }
}

data class TextPreview(
    val text: String,
    val isBinary: Boolean,
    val truncated: Boolean,
    val totalBytes: Long,
)

enum class ChecksumAlgorithm(val jceName: String, val label: String) {
    MD5("MD5", "MD5"),
    SHA1("SHA-1", "SHA-1"),
    SHA256("SHA-256", "SHA-256"),
}
