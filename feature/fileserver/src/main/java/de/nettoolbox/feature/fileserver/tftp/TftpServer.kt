package de.nettoolbox.feature.fileserver.tftp

import android.util.Log
import de.nettoolbox.feature.fileserver.domain.Protocol
import de.nettoolbox.core.common.storage.ShareStorage
import de.nettoolbox.feature.fileserver.domain.TftpConfig
import de.nettoolbox.feature.fileserver.domain.TransferDirection
import de.nettoolbox.feature.fileserver.domain.TransferLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * A TFTP server, RFC 1350 with the option extensions that make it usable.
 *
 * Written here rather than pulled in as a dependency. TFTP is five packet
 * types; what separates a toy implementation from a useful one is the options,
 * and those are exactly what the small libraries omit:
 *
 * - **RFC 2348 blksize.** The protocol default of 512 bytes caps a transfer at
 *   roughly one block per round trip. Raising it to 1468 - the largest payload
 *   that still fits one Ethernet frame after IP and UDP headers - is the single
 *   biggest speed difference available.
 * - **RFC 7440 windowsize.** Without it every block waits for its own
 *   acknowledgement, so throughput is bounded by latency rather than by
 *   bandwidth. With a window of 4 the same link runs several times faster.
 * - **RFC 2349 tsize and timeout.** tsize lets the client show a progress bar,
 *   and lets this server refuse an upload that will not fit before it starts
 *   writing.
 *
 * ### The port
 *
 * TFTP's assigned port is 69, and this server cannot use it. Android runs apps
 * unprivileged, and the kernel refuses any bind below 1024 - that is not a
 * permission the app can ask for. Clients that let you name a port work fine;
 * clients that do not, including some network gear, cannot reach this server
 * over TFTP at all. The UI states that rather than letting it be discovered
 * during a firmware upgrade.
 */
internal class TftpServer(
    private val storage: ShareStorage,
    private val log: TransferLog,
) {

    @Volatile
    private var socket: DatagramSocket? = null

    @Volatile
    private var stopping = false

    private val transfers = mutableListOf<Job>()

    /**
     * Claims the port, and nothing else.
     *
     * Separate from [serve] so a port clash is thrown at the caller rather than
     * inside the serving coroutine. When binding happens inside the loop, the
     * caller has no way to learn it failed, and the UI reports a running server
     * that never bound - which is precisely the class of lie this project keeps
     * finding and removing.
     */
    fun bind(config: TftpConfig, bindAddress: InetAddress?) {
        stopping = false
        socket = DatagramSocket(config.port, bindAddress)
    }

    /**
     * Serves until the socket is closed. Requires [bind] to have succeeded.
     *
     * Blocking on purpose: [DatagramSocket.receive] cannot be interrupted by
     * coroutine cancellation, so the only way out is [stop] closing the socket
     * underneath it. Pretending otherwise - wrapping it in something cancellable
     * and reporting the server as stopped while the socket is still bound - is
     * the failure this project has already paid for twice.
     */
    fun serve(scope: CoroutineScope, config: TftpConfig) {
        val bound = socket ?: return

        // The receive buffer has to hold the largest block any client may
        // negotiate, plus the four-byte header - not the currently configured
        // maximum, because the request that raises it arrives on this socket.
        val buffer = ByteArray(TftpConfig.MAX_BLOCK_SIZE + 4)

        while (!stopping) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                bound.receive(packet)
            } catch (socketException: SocketException) {
                // Closed by stop(). Anything else here means the interface went
                // away, which ends the server just the same.
                break
            } catch (io: IOException) {
                if (stopping) break
                log.error(Protocol.TFTP, "", io.message ?: io.javaClass.simpleName)
                continue
            }

            val client = InetSocketAddress(packet.address, packet.port)
            val request = TftpCodec.parseRequest(packet.data, packet.length)
            if (request == null) {
                // Not worth an ERROR reply: an unparseable datagram on an open
                // UDP port is usually a scanner, and answering it confirms
                // something is listening.
                log.warn(Protocol.TFTP, client.describe(), MESSAGE_MALFORMED)
                continue
            }

            // Each transfer holds a thread in blocking socket calls for as long
            // as it runs, and TFTP has no authentication: without a ceiling,
            // anyone on the network can queue requests until the whole I/O
            // pool is taken - stalling every other tool in the app with it.
            // Over the limit a client gets a clear refusal it can retry on.
            val active = synchronized(transfers) {
                transfers.removeAll { it.isCompleted }
                transfers.size
            }
            if (active >= MAX_CONCURRENT_TRANSFERS) {
                val busy = TftpCodec.error(TftpError.NOT_DEFINED, "server busy")
                runCatching { bound.send(DatagramPacket(busy, busy.size, client)) }
                log.warn(Protocol.TFTP, client.describe(), MESSAGE_BUSY, request.filename)
                continue
            }

            val job = scope.launch {
                runCatching { handle(request, client, config) }
                    .onFailure { failure ->
                        Log.e(TAG, "transfer failed", failure)
                        log.error(
                            Protocol.TFTP,
                            client.describe(),
                            failure.message ?: failure.javaClass.simpleName,
                            request.filename,
                        )
                    }
            }
            synchronized(transfers) {
                transfers.removeAll { it.isCompleted }
                transfers += job
            }
        }
    }

    fun stop() {
        stopping = true
        socket?.close()
        socket = null
        synchronized(transfers) {
            transfers.forEach { it.cancel() }
            transfers.clear()
        }
    }

    // --- one transfer -------------------------------------------------------

    private fun handle(request: TftpRequest, client: InetSocketAddress, config: TftpConfig) {
        // Every transfer gets its own ephemeral socket. That is the protocol's
        // own design - the server's reply comes from a new transfer identifier -
        // and connecting it to the client means the kernel drops datagrams from
        // anywhere else, which is the TID check of RFC 1350 section 4 for free.
        DatagramSocket().use { transferSocket ->
            transferSocket.connect(client)

            if (request.mode == TftpMode.UNSUPPORTED) {
                transferSocket.sendError(TftpError.ILLEGAL_OPERATION, "unsupported mode")
                log.warn(Protocol.TFTP, client.describe(), MESSAGE_BAD_MODE, request.filename)
                return
            }

            val file = storage.resolve(request.filename)
            if (file == null) {
                // Reported as an access violation rather than "not found": the
                // path was refused because it left the share, and saying which
                // paths exist outside it would be an answer in itself.
                transferSocket.sendError(TftpError.ACCESS_VIOLATION, "path outside share")
                log.warn(Protocol.TFTP, client.describe(), MESSAGE_OUTSIDE_ROOT, request.filename)
                return
            }

            if (request.write) {
                handleWrite(transferSocket, request, file, client, config)
            } else {
                handleRead(transferSocket, request, file, client, config)
            }
        }
    }

    // --- read (client downloads) -------------------------------------------

    private fun handleRead(
        socket: DatagramSocket,
        request: TftpRequest,
        target: File,
        client: InetSocketAddress,
        config: TftpConfig,
    ) {
        if (!target.isFile || !target.canRead()) {
            socket.sendError(TftpError.FILE_NOT_FOUND, "no such file")
            log.warn(Protocol.TFTP, client.describe(), MESSAGE_NOT_FOUND, request.filename)
            return
        }

        // netascii is converted up front into a temporary file rather than on
        // the fly. Retransmission needs to seek to an arbitrary block, and a
        // stream whose byte count changes as it is converted cannot be seeked
        // by block number - so the conversion happens once and the rest of the
        // code sees an ordinary file.
        val source = if (request.mode == TftpMode.NETASCII) {
            toNetascii(target) ?: run {
                socket.sendError(TftpError.NOT_DEFINED, "conversion failed")
                return
            }
        } else {
            target
        }
        val temporary = source !== target

        try {
            val negotiated = negotiate(request.options, config, transferSize = source.length())
            if (negotiated.accepted.isNotEmpty()) {
                socket.setSoTimeout(negotiated.timeoutMillis)
                // An OACK is itself acknowledged with block zero, and until
                // that arrives the options are only proposed. Sending data
                // before it would use a block size the client never agreed to.
                if (!socket.exchangeOack(negotiated, config)) {
                    log.warn(Protocol.TFTP, client.describe(), MESSAGE_NO_OACK_ACK, request.filename)
                    return
                }
            } else {
                socket.setSoTimeout(negotiated.timeoutMillis)
            }

            sendFile(socket, source, negotiated, config, client, request.filename)
        } finally {
            if (temporary) source.delete()
        }
    }

    private fun sendFile(
        socket: DatagramSocket,
        file: File,
        negotiated: Negotiated,
        config: TftpConfig,
        client: InetSocketAddress,
        displayName: String,
    ) {
        val blockSize = negotiated.blockSize
        val window = negotiated.windowSize
        val totalBlocks = totalBlocksFor(file.length(), blockSize)
        val startedAt = System.currentTimeMillis()

        RandomAccessFile(file, "r").use { input ->
            val payload = ByteArray(blockSize)
            val ackBuffer = ByteArray(ACK_BUFFER_SIZE)
            var base = 1L
            var retries = 0

            while (base <= totalBlocks) {
                val lastInWindow = minOf(base + window - 1, totalBlocks)
                for (block in base..lastInWindow) {
                    input.seek((block - 1) * blockSize.toLong())
                    // Filled in a loop rather than trusting one read(): a single
                    // call is allowed to return fewer bytes than asked for even
                    // in the middle of a file, and a short block is how a TFTP
                    // client is told the transfer has ended. Getting that wrong
                    // truncates the file and reports success.
                    var filled = 0
                    while (filled < blockSize) {
                        val read = input.read(payload, filled, blockSize - filled)
                        if (read < 0) break
                        filled += read
                    }
                    socket.send(TftpCodec.data(wireBlock(block), payload, filled))
                }

                val acked = socket.awaitAck(ackBuffer, lastSent = lastInWindow)
                when {
                    acked == null -> {
                        retries++
                        if (retries > config.maxRetries) {
                            log.error(Protocol.TFTP, client.describe(), MESSAGE_TIMEOUT, displayName)
                            return
                        }
                    }

                    // A duplicate or stale acknowledgement is not progress, but
                    // it is not a fault either - it is what a lost DATA packet
                    // looks like from here. The window is simply resent.
                    acked < base -> retries++

                    else -> {
                        base = acked + 1
                        retries = 0
                    }
                }
            }
        }

        log.transferred(
            protocol = Protocol.TFTP,
            client = client.describe(),
            path = displayName,
            direction = TransferDirection.DOWNLOAD,
            bytes = file.length(),
            durationMillis = System.currentTimeMillis() - startedAt,
        )
    }

    // --- write (client uploads) --------------------------------------------

    private fun handleWrite(
        socket: DatagramSocket,
        request: TftpRequest,
        target: File,
        client: InetSocketAddress,
        config: TftpConfig,
    ) {
        if (!config.allowUpload) {
            socket.sendError(TftpError.ACCESS_VIOLATION, "uploads disabled")
            log.warn(Protocol.TFTP, client.describe(), MESSAGE_UPLOAD_DISABLED, request.filename)
            return
        }
        if (target.exists() && !config.allowOverwrite) {
            socket.sendError(TftpError.FILE_ALREADY_EXISTS, "file exists")
            log.warn(Protocol.TFTP, client.describe(), MESSAGE_EXISTS, request.filename)
            return
        }
        val parent = target.parentFile
        if (parent == null || !parent.isDirectory || !parent.canWrite()) {
            socket.sendError(TftpError.ACCESS_VIOLATION, "not writable")
            log.warn(Protocol.TFTP, client.describe(), MESSAGE_NOT_WRITABLE, request.filename)
            return
        }

        val negotiated = negotiate(request.options, config, transferSize = null)

        // tsize on a write is the client telling us how large the file is. It
        // is the one chance to refuse before filling the device, and a phone
        // that runs out of storage mid-upgrade takes more than the transfer
        // with it.
        val announced = request.options["tsize"]?.toLongOrNull()
        if (announced != null && announced > storage.root.usableSpace) {
            socket.sendError(TftpError.DISK_FULL, "not enough space")
            log.error(Protocol.TFTP, client.describe(), MESSAGE_DISK_FULL, request.filename)
            return
        }

        socket.setSoTimeout(negotiated.timeoutMillis)

        // Written under a temporary name and renamed only once the last block
        // has arrived. A firmware image that is half transferred must never
        // carry the name a device would then be told to boot from.
        val partial = File(parent, target.name + PARTIAL_SUFFIX)
        val startedAt = System.currentTimeMillis()
        var received = 0L

        try {
            partial.outputStream().use { output ->
                if (negotiated.accepted.isNotEmpty()) {
                    socket.send(TftpCodec.oack(negotiated.accepted))
                } else {
                    socket.send(TftpCodec.ack(0))
                }

                val buffer = ByteArray(negotiated.blockSize + 4)
                var expected = 1L
                var sinceAck = 0
                var retries = 0

                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (timeout: SocketTimeoutException) {
                        retries++
                        if (retries > config.maxRetries) {
                            log.error(Protocol.TFTP, client.describe(), MESSAGE_TIMEOUT, request.filename)
                            return
                        }
                        // Re-acknowledging the last good block is what prompts
                        // the client to resend; silence would just stall.
                        socket.send(TftpCodec.ack(wireBlock(expected - 1)))
                        sinceAck = 0
                        continue
                    }
                    retries = 0

                    when (TftpCodec.opcodeOf(packet.data, packet.length)) {
                        TftpOpcode.DATA -> Unit
                        TftpOpcode.ERROR -> {
                            log.warn(Protocol.TFTP, client.describe(), MESSAGE_CLIENT_ABORT, request.filename)
                            return
                        }
                        else -> continue
                    }

                    val block = absoluteBlock(TftpCodec.blockOf(packet.data, packet.length), expected)
                    val payloadLength = packet.length - 4

                    if (block != expected) {
                        // Out of order. Acknowledging the last block received in
                        // sequence is the RFC 7440 way to make the sender rewind
                        // to it, and it costs one packet.
                        socket.send(TftpCodec.ack(wireBlock(expected - 1)))
                        sinceAck = 0
                        continue
                    }

                    if (payloadLength > 0) {
                        output.write(packet.data, 4, payloadLength)
                        received += payloadLength
                    }
                    expected++
                    sinceAck++

                    val last = payloadLength < negotiated.blockSize
                    if (last || sinceAck >= negotiated.windowSize) {
                        socket.send(TftpCodec.ack(wireBlock(expected - 1)))
                        sinceAck = 0
                    }
                    if (last) break
                }
            }

            if (request.mode == TftpMode.NETASCII) {
                fromNetasciiInPlace(partial)
            }

            if (target.exists() && !target.delete()) {
                socket.sendError(TftpError.ACCESS_VIOLATION, "could not replace")
                return
            }
            if (!partial.renameTo(target)) {
                socket.sendError(TftpError.NOT_DEFINED, "could not finalise")
                log.error(Protocol.TFTP, client.describe(), MESSAGE_RENAME_FAILED, request.filename)
                return
            }

            log.transferred(
                protocol = Protocol.TFTP,
                client = client.describe(),
                path = request.filename,
                direction = TransferDirection.UPLOAD,
                bytes = received,
                durationMillis = System.currentTimeMillis() - startedAt,
            )
        } catch (io: IOException) {
            socket.sendError(TftpError.DISK_FULL, io.message ?: "write failed")
            log.error(Protocol.TFTP, client.describe(), io.message ?: MESSAGE_WRITE_FAILED, request.filename)
        } finally {
            // A partial file left behind after a failure is litter that looks
            // exactly like a real one to whoever browses the share next.
            if (partial.exists()) partial.delete()
        }
    }

    // --- option negotiation -------------------------------------------------

    private data class Negotiated(
        val blockSize: Int,
        val windowSize: Int,
        val timeoutMillis: Int,
        val accepted: Map<String, String>,
    )

    /**
     * Works out which requested options to accept, per RFC 2347.
     *
     * Unknown options are ignored rather than refused: that is what the RFC
     * asks for, and refusing makes some clients abort the whole transfer over
     * an option they only offered.
     *
     * Every accepted value is clamped to what this server configuration
     * permits. A client asking for a 64 KB block on a link with a 1500 byte MTU
     * would get a transfer that fragments and stalls, so the answer is a
     * smaller number rather than a refusal - which is precisely what the OACK
     * mechanism exists to express.
     */
    private fun negotiate(
        options: Map<String, String>,
        config: TftpConfig,
        transferSize: Long?,
    ): Negotiated {
        val accepted = linkedMapOf<String, String>()

        val blockSize = options["blksize"]?.toIntOrNull()
            ?.coerceIn(TftpConfig.MIN_BLOCK_SIZE, config.maxBlockSize)
            ?.also { accepted["blksize"] = it.toString() }
            ?: DEFAULT_BLOCK_SIZE

        val timeoutSeconds = options["timeout"]?.toIntOrNull()
            ?.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
            ?.also { accepted["timeout"] = it.toString() }
            ?: config.timeoutSeconds

        val windowSize = options["windowsize"]?.toIntOrNull()
            ?.coerceIn(1, config.windowSize)
            ?.also { accepted["windowsize"] = it.toString() }
            ?: 1

        // On a read the client sends tsize as "0" and expects the real size
        // back; on a write it sends the real size and expects it echoed.
        if (options.containsKey("tsize") && transferSize != null) {
            accepted["tsize"] = transferSize.toString()
        } else if (options.containsKey("tsize")) {
            accepted["tsize"] = options.getValue("tsize")
        }

        return Negotiated(
            blockSize = blockSize,
            windowSize = windowSize,
            timeoutMillis = timeoutSeconds * 1000,
            accepted = accepted,
        )
    }

    private fun DatagramSocket.exchangeOack(negotiated: Negotiated, config: TftpConfig): Boolean {
        val oack = TftpCodec.oack(negotiated.accepted)
        val buffer = ByteArray(ACK_BUFFER_SIZE)
        repeat(config.maxRetries) {
            send(oack)
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                receive(packet)
            } catch (timeout: SocketTimeoutException) {
                return@repeat
            }
            if (TftpCodec.opcodeOf(packet.data, packet.length) == TftpOpcode.ACK &&
                TftpCodec.blockOf(packet.data, packet.length) == 0
            ) {
                return true
            }
        }
        return false
    }

    private fun DatagramSocket.awaitAck(buffer: ByteArray, lastSent: Long): Long? {
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            receive(packet)
            if (TftpCodec.opcodeOf(packet.data, packet.length) != TftpOpcode.ACK) {
                null
            } else {
                absoluteBlock(TftpCodec.blockOf(packet.data, packet.length), lastSent)
            }
        } catch (timeout: SocketTimeoutException) {
            null
        }
    }

    private fun DatagramSocket.send(bytes: ByteArray) =
        send(DatagramPacket(bytes, bytes.size))

    private fun DatagramSocket.sendError(code: Int, message: String) {
        runCatching { send(TftpCodec.error(code, message)) }
    }

    // --- block numbering ----------------------------------------------------

    // Block arithmetic lives in TftpBlocks so it can be tested without a
    // socket. It is the subtlest code in this file - a wraparound that only
    // shows up on files past 32 MB - and "cannot be reached from a test" was
    // not an acceptable property for it.
    private fun wireBlock(absolute: Long): Int = TftpBlocks.wire(absolute)

    private fun absoluteBlock(wire: Int, near: Long): Long = TftpBlocks.absolute(wire, near)

    private fun totalBlocksFor(length: Long, blockSize: Int): Long =
        TftpBlocks.totalBlocks(length, blockSize)

    // --- netascii -----------------------------------------------------------

    /** LF becomes CRLF, a bare CR becomes CR NUL. RFC 764 by way of RFC 1350. */
    private fun toNetascii(source: File): File? = runCatching {
        val converted = File.createTempFile("netascii", null, source.parentFile)
        source.inputStream().buffered().use { input ->
            converted.outputStream().buffered().use { output ->
                var previous = -1
                while (true) {
                    val byte = input.read()
                    if (byte < 0) break
                    when {
                        byte == LF && previous != CR -> {
                            output.write(CR)
                            output.write(LF)
                        }
                        else -> output.write(byte)
                    }
                    if (previous == CR && byte != LF) output.write(0)
                    previous = byte
                }
                if (previous == CR) output.write(0)
            }
        }
        converted
    }.getOrNull()

    /** The inverse, applied to a freshly received upload. */
    private fun fromNetasciiInPlace(file: File) {
        runCatching {
            val converted = File(file.parentFile, file.name + CONVERT_SUFFIX)
            file.inputStream().buffered().use { input ->
                converted.outputStream().buffered().use { output ->
                    var previous = -1
                    while (true) {
                        val byte = input.read()
                        if (byte < 0) break
                        when {
                            previous == CR && byte == LF -> output.write(LF)
                            previous == CR && byte == 0 -> output.write(CR)
                            byte == CR -> Unit
                            else -> output.write(byte)
                        }
                        previous = byte
                    }
                    if (previous == CR) output.write(CR)
                }
            }
            if (file.delete()) converted.renameTo(file) else converted.delete()
        }
    }

    private fun InetSocketAddress.describe(): String =
        "${address?.hostAddress ?: hostString}:$port"

    private companion object {
        const val TAG = "NetToolboxTftp"
        const val DEFAULT_BLOCK_SIZE = 512
        const val BLOCK_MODULUS = 65536L
        const val ACK_BUFFER_SIZE = 64
        const val MIN_TIMEOUT_SECONDS = 1
        const val MAX_TIMEOUT_SECONDS = 255
        const val PARTIAL_SUFFIX = ".part"
        const val CONVERT_SUFFIX = ".conv"
        const val CR = 13
        const val LF = 10

        // Marker strings; the UI turns them into localised text.
        const val MESSAGE_MALFORMED = "tftp.malformed"
        const val MESSAGE_BAD_MODE = "tftp.mode"
        const val MESSAGE_OUTSIDE_ROOT = "tftp.outside"
        const val MESSAGE_NOT_FOUND = "tftp.notfound"
        const val MESSAGE_UPLOAD_DISABLED = "tftp.readonly"
        const val MESSAGE_EXISTS = "tftp.exists"
        const val MESSAGE_NOT_WRITABLE = "tftp.notwritable"
        const val MESSAGE_DISK_FULL = "tftp.diskfull"
        const val MESSAGE_TIMEOUT = "tftp.timeout"
        const val MESSAGE_NO_OACK_ACK = "tftp.oack"
        const val MESSAGE_CLIENT_ABORT = "tftp.abort"
        const val MESSAGE_RENAME_FAILED = "tftp.rename"
        const val MESSAGE_WRITE_FAILED = "tftp.write"
        const val MESSAGE_BUSY = "tftp.busy"

        /**
         * Well above what a technician does - a handful of devices pulling the
         * same image - and well below the 64 threads of the I/O dispatcher.
         */
        const val MAX_CONCURRENT_TRANSFERS = 16
    }
}
