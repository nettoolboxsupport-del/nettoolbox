# Phase 8 — File explorer and file server

A share on the device, browsable in the app and served over TFTP, FTP/FTPS,
SFTP and legacy SCP. The intended use is the one a network engineer has
constantly: getting a firmware image or a config onto or off a switch without
carrying a laptop.

This file records the decisions and the traps, not the feature list.

---

## Decisions

### The root is the app's own directory, not the device

`ShareStorage` roots everything at `getExternalFilesDir(null)/Share`.

The alternative was `MANAGE_EXTERNAL_STORAGE`, which would let the servers
expose any folder. It was rejected on the grounds that matter here: Google
reviews that permission separately, its approved use case is "file manager",
and NetToolbox is listed as network diagnostics. The app is in the middle of a
production-access application; a rejected permission declaration would put that
at risk for a convenience.

What makes the limit workable is the Storage Access Framework in both
directions — `OpenMultipleDocuments` to bring files in, `CreateDocument` to
write one out. Neither needs a permission, and both reach Downloads, Drive and
anything else with a document provider. The directory is also visible over USB
and to any file manager on the device.

If this is revisited, the root abstraction is the only place that has to
change: every path in the feature, in the explorer and in all three servers,
goes through `ShareStorage.resolve`.

### One containment check, shared by everything

`ShareStorage.resolve` compares canonical paths and returns null for anything
outside the root — `..` segments, absolute paths, backslashes from an FTP
client, or a symlink planted earlier. The explorer and the three servers all
go through it, so a path the servers refuse cannot be reached by tapping
either.

The trailing separator in the prefix comparison is deliberate: without it a
sibling directory named `Share-other` would pass a plain `startsWith("Share")`.

SFTP additionally refuses to *create* symbolic links at all, for anyone. A link
is the one operation that can point outside the share while every path check
still passes, because the target is resolved later by whatever opens it.

### TFTP is written here; FTP and SSH are not

TFTP is five packet types. What separates a useful implementation from a toy is
the option extensions, and those are exactly what the small libraries omit:

- **RFC 2348 `blksize`** — the 512-byte default caps a transfer at one block
  per round trip. 1468 is the largest payload that still fits one Ethernet
  frame after IP and UDP headers.
- **RFC 7440 `windowsize`** — without it throughput is bounded by latency
  rather than bandwidth.
- **RFC 2349 `tsize`** — lets the client show progress, and lets this server
  refuse an upload that will not fit *before* it starts writing.

FTP and SSH are the opposite case: both are large protocols with decades of
client quirks, and Apache FtpServer and Apache MINA SSHD are the mature
implementations. Both are Apache 2.0, verified by reading `META-INF/LICENSE`
inside the jars rather than from memory.

### Both SFTP and legacy SCP

Not redundant. OpenSSH 9 and later implement the `scp` command by speaking
SFTP, so a modern client needs only the subsystem. Cisco IOS `copy scp:` and
comparable network gear still speak the original SCP protocol — and that gear
is the audience. Shipping only one would quietly exclude the devices the
feature exists for.

### No shell, ever

The SSH server installs a command factory that handles SCP and nothing else,
and no shell factory at all. A shell here would be a remote shell on the user's
phone, reachable with a password typed on a small keyboard in a hurry.

### Passwords are stored in clear text

Deliberate, and stated in the UI.

A hash would be better if this were a login typed from memory. It is not: it is
a credential the user reads off the screen and types into a switch console
minutes later, and a hash cannot be read back. Storing a hash makes every
password write-once, which in practice is how people end up choosing `1234`.
The file is in the app's private storage and is excluded from backups.

Comparison is still constant-time, done by comparing SHA-256 digests through
`MessageDigest.isEqual` rather than by a hand-rolled loop.

### FTPS uses the Android keystore, not Bouncy Castle

The obvious route is generating an X.509 certificate with `bcpkix` and handing
FtpServer a keystore file. Instead, `KeyGenParameterSpec` generates an RSA key
**and a self-signed certificate for it** in `AndroidKeyStore`, and a custom
`SslConfiguration` builds the `SSLContext` from that.

This removes about a megabyte of dependency and removes a private key file from
the device at the same time — the key never leaves the keystore.

What it buys is limited and the UI says so: the certificate is self-signed, so
every client warns and is right to. It does mean passwords and file contents
are no longer readable by others on the same Wi-Fi. For a connection that is
authenticated as well as encrypted, the answer is SFTP.

### The auto-stop exists because of how this actually goes wrong

Not an attack. Finishing the job, pocketing the phone, and leaving a
write-enabled share running on a customer network all afternoon. Default is
60 minutes.

---

## Traps found along the way

### SSHD picks its I/O backend through ServiceLoader

`DefaultIoServiceFactoryFactory` uses `java.util.ServiceLoader`. Under R8 that
lookup is unreliable — there is no compile-time reference to keep the
implementation alive — and the failure surfaces at start as an unhelpful "no
IoServiceFactoryFactory found", in release builds only.

Fixed by setting `Nio2ServiceFactoryFactory` explicitly, which removes the
lookup entirely. NIO2 is the right backend anyway: it is built on
`java.nio.channels`, which Android has had since API 26.

### No SLF4J 1.7 binding exists for Android

Both SSHD and FtpServer declare slf4j-api **1.7.36**, and SSHD's parent POM
carries an explicit warning against going beyond it. The only maintained
Android binding (`uk.uuid.slf4j:slf4j-android`) is 2.0-only, and 2.0 finds its
binding through `ServiceLoader` — the same mechanism that just caused the
previous problem.

So this project supplies its own binding under `org/slf4j/impl/`, written in
Java because the 1.7 contract is a set of exact static members that
`LoggerFactory` links against at compile time. No `ServiceLoader`, no
reflection, and R8 keeps it without a keep rule.

A second benefit decided it: SSHD logs at DEBUG on essentially every packet,
and Logcat is a world-readable buffer on the device. The binding gates on
`Log.isLoggable`, which reports false for DEBUG and TRACE unless someone turns
them on deliberately with `adb shell setprop log.tag.<tag> DEBUG`. The release
build is silent, and a user reporting a problem can still be walked through
enabling protocol logging without a special build.

### Ports below 1024 are unreachable, and that had to be said out loud

Android runs apps unprivileged and the kernel refuses the bind. TFTP cannot use
69, FTP cannot use 21, SSH cannot use 22. No permission changes this.

Defaults are 6969, 2121 and 2222. Most clients accept a port; some network
hardware over TFTP does not, and for those this server is simply out of reach.
That is stated in a card on the Servers tab rather than left to be discovered
during a firmware upgrade.

### Binding "Wi-Fi only" must fail rather than fall back

When Wi-Fi only is selected and there is no Wi-Fi address, the service refuses
to start and reports it. Falling back to every interface would expose the share
on the mobile interface, which on a modern network carries a publicly routable
IPv6 address.

### The TFTP server had to separate binding from serving

The first version bound inside the serve loop, which runs in a coroutine. A
port clash was therefore invisible to the caller, and the UI would report a
running server that never bound. `bind()` and `serve()` are now separate, and
the clash is thrown at the caller.

This is the same failure shape as the two already recorded in this project — a
silent failure on a path whose only observable outcome is a UI that lies.

### Block numbers wrap after 32 MB

TFTP block numbers are sixteen bits. At the default block size they wrap after
32 MB, which is smaller than every firmware image this feature exists to move.
The counter is kept as a `Long` and narrowed only on the wire; an
acknowledgement is mapped back as the largest absolute value not greater than
what was sent.

That arithmetic was pulled into `TftpBlocks` specifically so it could be tested
without a socket. It is the subtlest code in the feature and "cannot be reached
from a test" was not an acceptable property for it.

### A short read is not the end of a file

`RandomAccessFile.read(byte[])` may return fewer bytes than asked for in the
middle of a file — and in TFTP a short block is precisely how the client is
told the transfer has ended. Reading once per block would truncate the file and
report success. Each block is filled in a loop.

### Uploads land under a temporary name

A TFTP or SFTP upload writes to `name.part` and is renamed only after the last
block. A half-transferred firmware image must never carry the name a device
would then be told to boot from.

---

## Not done, deliberately

- **QR codes for the connection URLs.** Mentioned when this was planned, then
  dropped: it needs an encoder, and the URLs are short enough to read. Say so
  rather than leave it as an unstated gap.
- **Sharing a file to another app.** The share root is under `Android/data`,
  which other apps cannot read on Android 11 and later, so it would need a
  `FileProvider`. Export through the system file picker covers the same need
  without new manifest surface. The string for it was removed rather than left
  pointing at nothing.
- **FTP transfer byte counts in the log.** FtpServer's `Ftplet` hooks report
  the command and its reply code but not the size, and instrumenting the data
  connection to get it was not worth the surface. A wrong number would be worse
  than none: the log would show a throughput figure that is simply false. TFTP,
  SFTP and SCP all report real byte counts and durations.

---

## Verification status

**This phase has not been compiled.** Gradle could not run in the session where
it was written: the JVM on that machine enumerated 85 network interfaces and
none of them reported `isLoopback() == true`, which is what the Gradle client
looks for, so every invocation failed with `Unable to establish loopback
connection`. Binding to `127.0.0.1` from Java worked; only the enumeration did
not. That is a Windows network-stack condition, not a project fault, and it
blocked `gradlew` itself rather than any task.

What *was* checked without a compiler:

- All four new dependencies are Java 8 bytecode and use no JDK API that Android
  lacks — verified by unpacking the jars and scanning for `java.beans`,
  `javax.naming`, `java.rmi`, `javax.management` and `ServiceLoader`.
- Every library API used here was read out of the jars with `javap` rather than
  recalled: `SslConfiguration`, `UserManager`, `Ftplet`, `FtpReply`,
  `SshServer`, `KeyPairProvider`, `SftpEventListener`, `ScpTransferEventListener`,
  `VirtualFileSystemFactory`, `Nio2ServiceFactoryFactory`.
- Licences read from `META-INF/LICENSE` in each jar and from the POMs.
- Both string files parse, hold identical key sets, and carry no
  double-encoded UTF-8. Every `R.string` referenced in the module exists, and
  nothing is defined but unused.

The first build will very likely need fixes. The riskiest single item is
whether Apache MINA SSHD starts at all on the device.
