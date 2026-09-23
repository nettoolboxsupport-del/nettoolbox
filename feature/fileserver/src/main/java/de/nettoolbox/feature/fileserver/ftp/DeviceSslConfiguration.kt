package de.nettoolbox.feature.fileserver.ftp

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.apache.ftpserver.ssl.ClientAuth
import org.apache.ftpserver.ssl.SslConfiguration
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Calendar
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.security.auth.x500.X500Principal

/**
 * TLS for the FTP listener, using a certificate the device makes for itself.
 *
 * The obvious route would be to generate an X.509 certificate with Bouncy
 * Castle and hand FtpServer a keystore file. This does neither, for a reason
 * worth stating: Android's own keystore will generate an RSA key **and a
 * self-signed certificate for it** from one KeyGenParameterSpec, and it keeps
 * the private key in hardware-backed storage where this app cannot read it and
 * neither can anything else. That removes a dependency (bcpkix, about a
 * megabyte) and removes a private key file from the device at the same time.
 *
 * ### What this protects, and what it does not
 *
 * The certificate is self-signed, so no client can verify who it is talking to;
 * every FTPS client will warn, and the honest answer is that it should. What
 * explicit FTPS with a self-signed certificate does buy is that the password
 * and the file contents are no longer readable by anyone on the same Wi-Fi -
 * which, on the shared networks where this app gets used, is the threat that
 * actually happens. The UI says exactly this rather than presenting a padlock.
 *
 * For a connection that is authenticated as well as encrypted, the answer is
 * SFTP: its host key is pinned by the client on first use and the fingerprint
 * is shown in the app for comparison.
 */
internal class DeviceSslConfiguration : SslConfiguration {

    private val context: SSLContext by lazy { buildContext() }

    override fun getSSLContext(): SSLContext = context

    override fun getSSLContext(protocol: String?): SSLContext = context

    override fun getSocketFactory(): SSLSocketFactory = context.socketFactory

    override fun getEnabledProtocol(): String = "TLSv1.2"

    /**
     * Both current TLS versions.
     *
     * TLS 1.0 and 1.1 are deliberately absent even though some old FTP clients
     * only speak them: a fallback that silently downgrades the connection would
     * make the reassurance in the UI untrue.
     */
    override fun getEnabledProtocols(): Array<String> = arrayOf("TLSv1.2", "TLSv1.3")

    /** null means "the platform default", which on Android is a current, curated list. */
    override fun getEnabledCipherSuites(): Array<String>? = null

    /** No client certificates: the accounts are the authentication. */
    override fun getClientAuth(): ClientAuth = ClientAuth.NONE

    private fun buildContext(): SSLContext {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) generateKey()

        val managers = KeyManagerFactory
            .getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply {
                // No password: an AndroidKeyStore entry is protected by the
                // keystore itself, not by a passphrase this app would have to
                // store somewhere alongside it.
                init(KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }, null)
            }

        return SSLContext.getInstance("TLS").apply {
            init(managers.keyManagers, null, null)
        }
    }

    private fun generateKey() {
        val notBefore = Calendar.getInstance()
        val notAfter = Calendar.getInstance().apply { add(Calendar.YEAR, CERTIFICATE_YEARS) }

        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE).apply {
            initialize(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    // Sign and verify cover the ECDHE_RSA suites and TLS 1.3,
                    // where the key only ever signs the handshake. Encrypt and
                    // decrypt are included as well because the older RSA key
                    // transport suites use the key the other way round, and a
                    // key generated without them fails the handshake with an
                    // error that says nothing about the cause.
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY or
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(KEY_SIZE)
                    .setCertificateSubject(X500Principal(CERTIFICATE_SUBJECT))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(notBefore.time)
                    .setCertificateNotAfter(notAfter.time)
                    .setDigests(
                        KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384,
                        KeyProperties.DIGEST_SHA512,
                    )
                    .setSignaturePaddings(
                        KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                        KeyProperties.SIGNATURE_PADDING_RSA_PSS,
                    )
                    .setEncryptionPaddings(
                        KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1,
                        KeyProperties.ENCRYPTION_PADDING_RSA_OAEP,
                    )
                    // Deliberately NOT setUserAuthenticationRequired: the FTP
                    // listener has to complete a handshake while the screen is
                    // off, and a key that needs the lock screen would make the
                    // server work only while someone is watching it.
                    .build(),
            )
            generateKeyPair()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "nettoolbox_ftps"
        const val KEY_SIZE = 2048
        const val CERTIFICATE_YEARS = 10
        const val CERTIFICATE_SUBJECT = "CN=NetToolbox FTPS"
    }
}
