package de.nettoolbox.feature.ssh.data

import android.util.Log
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Logger

/**
 * Routes jsch's internal logging into Logcat.
 *
 * This exists because of a concrete diagnostic gap: when a server tears down a
 * session it sends SSH_MSG_DISCONNECT with a reason, and jsch knows that
 * reason - but without a logger installed it never reaches us. All the app
 * sees is an input stream that hit EOF, which says *that* the channel closed
 * and nothing about *why*.
 *
 * jsch's own messages are not localised and can contain host names and
 * algorithm names. They contain no credentials: jsch never logs passwords or
 * key material. They are still only wired up for debuggable builds, because a
 * released app has no business writing protocol traces to a shared log.
 */
object SshJschLogger : Logger {

    private const val TAG = "NetToolboxSSHProto"

    override fun isEnabled(level: Int): Boolean = true

    override fun log(level: Int, message: String) {
        when (level) {
            Logger.DEBUG -> Log.d(TAG, message)
            Logger.INFO -> Log.i(TAG, message)
            Logger.WARN -> Log.w(TAG, message)
            Logger.ERROR, Logger.FATAL -> Log.e(TAG, message)
            else -> Log.d(TAG, message)
        }
    }

    /**
     * Installs this logger once.
     *
     * @param enabled pass the build's debuggable flag; false leaves jsch silent
     */
    @JvmStatic
    fun installIfEnabled(enabled: Boolean) {
        if (!enabled || installed) return
        JSch.setLogger(this)
        installed = true
    }

    @Volatile
    private var installed = false
}
