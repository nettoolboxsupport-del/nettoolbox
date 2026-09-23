package de.nettoolbox.app.ui.about

/**
 * One third-party component, as shown on the about screen.
 *
 * @param name the project as it calls itself
 * @param licence the licence exactly as stated by that project, not a
 *   paraphrase - "BSD 3-clause" and "BSD" are not the same statement
 * @param usedFor why it is in this app, in one line
 * @param url where the licence text can be read
 * @param attributionRequired true when the licence obliges us to name the
 *   source, rather than merely permitting it. Those entries are marked in the
 *   UI so nobody removes them while tidying up.
 */
data class ThirdPartyComponent(
    val name: String,
    val licence: String,
    val usedFor: String,
    val url: String,
    val attributionRequired: Boolean = false,
)

/**
 * The components this app ships or links against.
 *
 * **This list and `THIRD_PARTY_LICENSES.md` in the project root have to be
 * changed together.** The duplication is deliberate: the Markdown file is the
 * record for anyone reading the repository, this list is what the app can
 * render without a Markdown parser. Neither is generated from the other, so a
 * new dependency means two edits.
 *
 * Every licence below was read in the upstream project's own LICENSE file
 * before being written here.
 */
object ThirdPartyComponents {

    val all: List<ThirdPartyComponent> = listOf(
        ThirdPartyComponent(
            name = "libvterm",
            licence = "MIT",
            usedFor = "Terminal emulation behind the SSH client",
            url = "https://github.com/neovim/libvterm",
        ),
        ThirdPartyComponent(
            name = "iperf3",
            licence = "BSD 3-clause",
            usedFor = "Throughput measurement, client and server",
            url = "https://github.com/esnet/iperf",
        ),
        ThirdPartyComponent(
            name = "JSch (mwiede fork)",
            licence = "BSD 3-clause",
            usedFor = "SSH transport",
            url = "https://github.com/mwiede/jsch",
        ),
        ThirdPartyComponent(
            name = "Bouncy Castle",
            licence = "MIT",
            usedFor = "ed25519 and x25519, which Android does not provide",
            url = "https://www.bouncycastle.org/licence.html",
        ),
        ThirdPartyComponent(
            name = "Apache MINA SSHD",
            licence = "Apache 2.0",
            usedFor = "SFTP and SCP server",
            url = "https://github.com/apache/mina-sshd",
        ),
        ThirdPartyComponent(
            name = "Apache FtpServer",
            licence = "Apache 2.0",
            usedFor = "FTP and FTPS server",
            url = "https://mina.apache.org/ftpserver-project/",
        ),
        ThirdPartyComponent(
            name = "Apache MINA",
            licence = "Apache 2.0",
            usedFor = "Network layer beneath Apache FtpServer",
            url = "https://mina.apache.org/",
        ),
        ThirdPartyComponent(
            name = "SLF4J",
            licence = "MIT",
            usedFor = "Logging facade required by the two server libraries",
            url = "https://www.slf4j.org/license.html",
        ),
        ThirdPartyComponent(
            name = "usb-serial-for-android",
            licence = "MIT",
            usedFor = "USB-to-serial drivers for the serial console",
            url = "https://github.com/mik3y/usb-serial-for-android",
        ),
        ThirdPartyComponent(
            name = "MapLibre GL Native",
            licence = "BSD 2-clause",
            usedFor = "Map rendering",
            url = "https://github.com/maplibre/maplibre-native",
        ),
        ThirdPartyComponent(
            name = "OkHttp",
            licence = "Apache 2.0",
            usedFor = "HTTP and TLS inspection",
            url = "https://square.github.io/okhttp/",
        ),
        ThirdPartyComponent(
            name = "AndroidX, Jetpack Compose, Room, DataStore",
            licence = "Apache 2.0",
            usedFor = "Application framework",
            url = "https://developer.android.com/jetpack",
        ),
        ThirdPartyComponent(
            name = "Dagger Hilt",
            licence = "Apache 2.0",
            usedFor = "Dependency injection",
            url = "https://dagger.dev/hilt/",
        ),
        ThirdPartyComponent(
            name = "Kotlin, Coroutines, kotlinx.serialization",
            licence = "Apache 2.0",
            usedFor = "Language and runtime libraries",
            url = "https://github.com/JetBrains/kotlin",
        ),
    )

    /**
     * Data sources whose licences require attribution.
     *
     * Kept apart from [all] because these are not a courtesy. OpenStreetMap's
     * tile usage policy and OpenCelliD's CC-BY-SA both oblige us to name the
     * source wherever the data is shown, which is why the same notice also sits
     * on the map itself and must not be removed from either place.
     */
    val dataSources: List<ThirdPartyComponent> = listOf(
        ThirdPartyComponent(
            name = "OpenStreetMap",
            licence = "ODbL",
            usedFor = "Map tiles",
            url = "https://www.openstreetmap.org/copyright",
            attributionRequired = true,
        ),
        ThirdPartyComponent(
            name = "OpenCelliD",
            licence = "CC-BY-SA 4.0",
            usedFor = "Imported cell tower positions",
            url = "https://opencellid.org/",
            attributionRequired = true,
        ),
    )
}
