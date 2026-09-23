package de.nettoolbox.feature.fileserver.domain

import java.io.File

/**
 * One row in the explorer.
 *
 * A snapshot rather than a live [File] handle: the list is rendered on the main
 * thread, and File.length() or isDirectory hit the filesystem on every single
 * call. Reading them once off the main thread and passing values around keeps a
 * directory of a few thousand entries from stuttering the scroll.
 */
data class FileEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val canRead: Boolean,
    val canWrite: Boolean,
) {
    /** Lowercase extension without the dot; empty for directories and dotfiles. */
    val extension: String
        get() = if (isDirectory) "" else name.substringAfterLast('.', "").lowercase()

    val kind: FileKind get() = FileKind.of(this)
}

/**
 * Coarse grouping used for the icon and for deciding what a tap does.
 *
 * Deliberately small. A network engineer's share holds configs, firmware images
 * and logs; distinguishing forty document formats would be noise, while telling
 * a text file from a firmware blob is the distinction that changes what the
 * user does next.
 */
enum class FileKind {
    DIRECTORY,
    TEXT,
    ARCHIVE,
    IMAGE,
    FIRMWARE,
    CERTIFICATE,
    OTHER,
    ;

    companion object {
        private val TEXT_EXTENSIONS = setOf(
            "txt", "log", "cfg", "conf", "config", "ini", "json", "xml", "yml",
            "yaml", "md", "csv", "sh", "py", "kt", "java", "c", "h", "properties",
            "env", "hosts", "rsc", "set", "tcl", "bat", "ps1", "sql", "toml",
        )
        private val ARCHIVE_EXTENSIONS = setOf(
            "zip", "gz", "tgz", "bz2", "xz", "7z", "rar", "jar", "apk",
        )
        private val IMAGE_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "bmp", "webp", "svg", "heic",
        )

        /**
         * Firmware and boot images - the reason this whole feature exists.
         *
         * "bin" and "img" are ambiguous in general but not in this context: in
         * a share that a switch pulls from over TFTP they are firmware far more
         * often than anything else. "tar" is listed here rather than under
         * archives because several vendors ship images that way.
         */
        private val FIRMWARE_EXTENSIONS = setOf(
            "bin", "img", "tar", "spa", "pkg", "npe", "swi", "srec",
            "hex", "rom", "fw", "ios", "nos", "chk", "trx",
        )
        private val CERTIFICATE_EXTENSIONS = setOf(
            "pem", "crt", "cer", "der", "p12", "pfx", "key", "csr", "jks", "pub",
        )

        fun of(entry: FileEntry): FileKind = when {
            entry.isDirectory -> DIRECTORY
            entry.extension in CERTIFICATE_EXTENSIONS -> CERTIFICATE
            entry.extension in TEXT_EXTENSIONS -> TEXT
            entry.extension in IMAGE_EXTENSIONS -> IMAGE
            entry.extension in FIRMWARE_EXTENSIONS -> FIRMWARE
            entry.extension in ARCHIVE_EXTENSIONS -> ARCHIVE
            else -> OTHER
        }
    }
}

/** How a directory listing is ordered. Directories always come first regardless. */
enum class SortOrder { NAME, SIZE, MODIFIED, KIND }

internal fun File.toEntry(relativePath: String): FileEntry = FileEntry(
    name = name,
    relativePath = relativePath,
    isDirectory = isDirectory,
    sizeBytes = if (isDirectory) 0L else length(),
    modifiedAtMillis = lastModified(),
    canRead = canRead(),
    canWrite = canWrite(),
)
