package de.nettoolbox.core.common.storage

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one directory tree the servers are allowed to touch.
 *
 * Rooted at the app's own external files directory rather than at the whole
 * device. That is a deliberate limit, not an oversight:
 *
 * - It needs **no permission at all**, on any Android version.
 *   MANAGE_EXTERNAL_STORAGE would open the whole device but drags the app into
 *   a separate Play Store review whose stated use case is "file manager" - a
 *   poor fit for an app listed as network diagnostics, and a real risk to its
 *   production access.
 * - The directory is still reachable from a PC over USB and from every file
 *   manager on the device, so getting files in and out without the app works.
 * - Anything outside it can be pulled in through the system file picker, which
 *   needs no permission either and reaches Downloads, Drive and network shares
 *   alike.
 *
 * Lives in :core:common because two features write here: the file server
 * serves it, and the serial console saves its session logs into it - so a
 * captured "show running-config" can be fetched over SFTP straight away.
 *
 * Everything the three servers expose lives below [root], and [resolve] is the
 * only way to turn a client-supplied path into a file. A protocol server that
 * accepts remote path strings without that check is one "../../.." away from
 * serving the app's own databases.
 */
@Singleton
class ShareStorage @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /**
     * The share root, created on first access.
     *
     * The external files directory can genuinely be null - on a device whose
     * external storage is unmounted - so the internal directory is the fallback
     * rather than a crash. The servers keep working; the files are merely not
     * visible over USB.
     */
    val root: File by lazy {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        File(base, SHARE_DIRECTORY_NAME).apply { mkdirs() }
    }

    /** True when [root] sits on external storage and is therefore visible over USB. */
    val rootIsExternal: Boolean
        get() = context.getExternalFilesDir(null) != null

    /**
     * Turns a client-supplied relative path into a file inside [root].
     *
     * Returns null when the result would land outside the root, whatever route
     * it took to get there - ".." segments, an absolute path, a symlink planted
     * earlier, or a Windows-style backslash from an FTP client. The comparison
     * is on canonical paths because that is the only form in which all of those
     * collapse to the same answer.
     *
     * The trailing separator on the prefix matters: without it a sibling
     * directory named "Share-other" would pass a plain startsWith("Share").
     */
    fun resolve(relativePath: String): File? {
        val cleaned = relativePath.replace('\\', '/').trim('/')
        val candidate = if (cleaned.isEmpty()) root else File(root, cleaned)
        return try {
            val canonicalRoot = root.canonicalPath
            val canonicalCandidate = candidate.canonicalPath
            when {
                canonicalCandidate == canonicalRoot -> candidate
                canonicalCandidate.startsWith(canonicalRoot + File.separator) -> candidate
                else -> null
            }
        } catch (io: IOException) {
            // canonicalPath does real filesystem work and can fail. A path that
            // cannot be checked is not a path that gets served.
            null
        }
    }

    /** The path a client sees for [file]: leading slash, no root prefix. */
    fun relativeOf(file: File): String = try {
        val canonicalRoot = root.canonicalPath
        val canonical = file.canonicalPath
        if (canonical == canonicalRoot) {
            "/"
        } else {
            "/" + canonical
                .removePrefix(canonicalRoot + File.separator)
                .replace(File.separatorChar, '/')
        }
    } catch (io: IOException) {
        "/"
    }

    private companion object {
        /**
         * Named "Share" rather than using the files directory itself, so the
         * servers can never reach cached exports, map tiles or anything else
         * the app happens to store beside it.
         */
        const val SHARE_DIRECTORY_NAME = "Share"
    }
}
