package de.nettoolbox.feature.tools.domain.dns

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Sends an encoded DNS query and returns the raw answer. One interface for all
 * four transports, so the codec and the UI stay unaware of how the bytes travel.
 */
interface DnsTransport {
    suspend fun query(request: ByteArray): ByteArray
}

/**
 * Classic DNS over UDP.
 *
 * The receive buffer is sized to the EDNS0 payload the query advertises;
 * anything larger sets the TC flag and has to be retried over TCP, which
 * [DnsResolverService] does.
 */
class UdpDnsTransport(
    private val host: String,
    private val port: Int,
    private val timeoutMillis: Int,
    private val dispatcher: CoroutineDispatcher,
) : DnsTransport {

    override suspend fun query(request: ByteArray): ByteArray = withContext(dispatcher) {
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMillis
            val address = InetAddress.getByName(host)
            socket.send(DatagramPacket(request, request.size, address, port))

            val buffer = ByteArray(DnsMessageCodec.EDNS_PAYLOAD_SIZE)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            buffer.copyOf(response.length)
        }
    }
}

/** DNS over TCP, with the two-byte length prefix from RFC 1035 section 4.2.2. */
class TcpDnsTransport(
    private val host: String,
    private val port: Int,
    private val timeoutMillis: Int,
    private val dispatcher: CoroutineDispatcher,
) : DnsTransport {

    override suspend fun query(request: ByteArray): ByteArray = withContext(dispatcher) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMillis)
            socket.soTimeout = timeoutMillis
            exchangeLengthPrefixed(socket, request)
        }
    }
}

/**
 * DNS over TLS (RFC 7858). Same framing as TCP, wrapped in TLS on port 853.
 *
 * The socket is created with the host name so the JSSE sends SNI and validates
 * the certificate against it - without that, DoT would encrypt the query but not
 * authenticate the resolver.
 */
class DotDnsTransport(
    private val host: String,
    private val port: Int,
    private val timeoutMillis: Int,
    private val dispatcher: CoroutineDispatcher,
) : DnsTransport {

    override suspend fun query(request: ByteArray): ByteArray = withContext(dispatcher) {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        (factory.createSocket() as SSLSocket).use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMillis)
            socket.soTimeout = timeoutMillis
            socket.startHandshake()
            exchangeLengthPrefixed(socket, request)
        }
    }
}

/**
 * DNS over HTTPS (RFC 8484), POST variant.
 *
 * POST rather than GET: no base64url encoding, no cache interference from
 * intermediaries, and the query does not end up in anybody's access log.
 */
class DohDnsTransport(
    private val url: String,
    private val client: OkHttpClient,
    private val dispatcher: CoroutineDispatcher,
) : DnsTransport {

    override suspend fun query(request: ByteArray): ByteArray = withContext(dispatcher) {
        val httpRequest = Request.Builder()
            .url(url)
            .header("Accept", DNS_MESSAGE)
            .post(request.toRequestBody(DNS_MESSAGE.toMediaType()))
            .build()

        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("DoH server answered HTTP ${response.code}")
            }
            response.body?.bytes() ?: throw IOException("DoH response had no body")
        }
    }

    private companion object {
        const val DNS_MESSAGE = "application/dns-message"
    }
}

/**
 * Writes a length-prefixed query and reads the length-prefixed answer. Shared by
 * TCP and DoT, which differ only in whether TLS sits underneath.
 */
private fun exchangeLengthPrefixed(socket: Socket, request: ByteArray): ByteArray {
    val output = socket.getOutputStream()
    output.write((request.size shr 8) and 0xFF)
    output.write(request.size and 0xFF)
    output.write(request)
    output.flush()

    val input = DataInputStream(socket.getInputStream())
    val length = (input.read() shl 8) or input.read()
    if (length <= 0) throw IOException("DNS server closed the connection")

    val response = ByteArray(length)
    input.readFully(response)
    return response
}
