# Third-party licences

NetToolbox itself is licensed under the **Apache License 2.0**; see `LICENSE`.
That choice is compatible with every component listed below (MIT, BSD 3-clause
and the MIT-equivalent Bouncy Castle licence), and it was only available
because the terminal emulator was deliberately kept off GPLv3 - see
`PHASE6A_NOTES.md`.

Each entry below was verified by reading the upstream `LICENSE` file, not from
memory.

# Third-party components

Every vendored or linked third-party component, with the licence **as read
from the project's own LICENSE file**, not from memory or from a package
index. Where a component is vendored as a git submodule, the pinned commit is
recorded so the claim can be re-checked against exactly what is built.

This file exists because a licence mistake is not something a test catches.

## Vendored as git submodules (compiled into the app)

### iperf3

- **Project:** [esnet/iperf](https://github.com/esnet/iperf)
- **Path:** `native/iperf3/src/main/cpp/iperf3-src`
- **Licence:** BSD 3-clause
- **Used for:** throughput measurement, client and server
- **Notes:** built without autotools; `iperf_config.h` and `version.h` are
  hand-written replacements for what `./configure` generates. SCTP and the
  OpenSSL authentication support are excluded entirely. See
  `PHASE5C_NOTES.md`.

### libvterm

- **Project:** [neovim/libvterm](https://github.com/neovim/libvterm)
- **Path:** `native/vterm/src/main/cpp/libvterm-src`
- **Pinned commit:** `934bc2f`
- **Version:** 0.3.3 (from `VTERM_VERSION_*` in `include/vterm.h`)
- **Licence:** **MIT** — verified by reading
  `native/vterm/src/main/cpp/libvterm-src/LICENSE` in the vendored copy:
  "The MIT License", Copyright (c) 2008 Paul Evans
  <leonerd@leonerd.org.uk>
- **Used for:** terminal emulation (VT/xterm escape-sequence state machine)
  behind the SSH client
- **Notes:** no generated configuration header, no build-time script
  execution. The three `.inc` tables that upstream's Makefile generates with
  perl are already checked in and are used as-is.

## Maven dependencies

### JSch (mwiede fork)

- **Project:** [mwiede/jsch](https://github.com/mwiede/jsch)
- **Coordinates:** `com.github.mwiede:jsch:2.28.6`
- **Licence:** **BSD 3-clause** — verified by reading `LICENSE.txt` in the
  upstream repository: Copyright (c) 2002-2015 Atsuhiko Yamanaka, JCraft, Inc.
- **Used for:** the SSH transport
- **Notes:** the maintained successor to the dormant JCraft original. Compiles
  to Java 8 bytecode (`<release>8</release>` in its `pom.xml`), so it is
  usable on Android.

### Bouncy Castle

- **Project:** [bcgit/bc-java](https://github.com/bcgit/bc-java)
- **Coordinates:** `org.bouncycastle:bcprov-jdk18on:1.80`
- **Licence:** **MIT** — the Bouncy Castle Licence, which its own licence
  page states "should be read in the same way as the MIT license"; the
  permission grant is the MIT text verbatim.
- **Used for:** ed25519 and x25519, which jsch needs and Android's JCE does
  not provide. See `PHASE6B_NOTES.md` for why this is a requirement rather
  than an optimisation.
- **Version note:** 1.80 is the newest release Maven Central lists for this
  artifact. The git repository carries newer tags (up to `r1rv85`), but their
  publication under `bcprov-jdk18on` could not be confirmed, so the verified
  version was pinned rather than the newest-looking one.

### Apache MINA SSHD

- **Artifacts:** `org.apache.sshd:sshd-core`, `sshd-sftp`, `sshd-scp` 2.18.0
- **Licence:** **Apache 2.0** - verified by reading `META-INF/LICENSE` inside
  `sshd-core-2.18.0.jar` and the `<licenses>` block of the inherited
  `org.apache:apache:37` parent POM.
- **Used for:** the SFTP subsystem and the legacy SCP command of the file
  server
- **Notes:** `sshd-scp` is not redundant beside `sshd-sftp`. OpenSSH 9 and
  later implement the `scp` command over SFTP, but Cisco IOS and comparable
  network gear still speak the original SCP protocol - and that gear is the
  audience for the feature. The optional dependencies declared in its POM
  (Bouncy Castle PGP and PKIX, the EdDSA library, tomcat-apr) are deliberately
  not pulled in; see the `-dontwarn` rules in `app/proguard-rules.pro`.

### Apache FtpServer and Apache MINA

- **Artifacts:** `org.apache.ftpserver:ftpserver-core` 1.2.1, which pulls
  `org.apache.mina:mina-core`
- **Licence:** **Apache 2.0** - verified by reading `META-INF/LICENSE` inside
  both jars and the `<licenses>` block of `ftpserver-parent-1.2.1.pom`.
- **Used for:** the FTP and FTPS server
- **Notes:** the optional Spring dependency is not used; this app configures
  the server programmatically rather than through FtpServer's XML
  configuration.

### SLF4J

- **Artifact:** `org.slf4j:slf4j-api` 1.7.36
- **Licence:** **MIT** - verified by reading the `<licenses>` block of
  `slf4j-parent-1.7.36.pom`.
- **Used for:** the logging facade that SSHD and FtpServer both require
- **Notes:** pinned at 1.7.36 because both libraries declare that version and
  SSHD's parent POM carries an explicit warning against going beyond it. No
  published binding exists for 1.7 on Android, so this project supplies its own
  under `org/slf4j/impl/` in `:feature:fileserver` - about a hundred lines that
  forward to Logcat. That is also what keeps the release build quiet: SSHD logs
  at DEBUG on nearly every packet, and Logcat is world-readable on the device.

### usb-serial-for-android

- **Artifact:** `com.github.mik3y:usb-serial-for-android` 3.11.0, from JitPack
- **Licence:** **MIT** - verified by reading `LICENSE.txt` in the upstream
  repository (Copyright 2011-2013 Google Inc., 2013 Mike Wakerly).
- **Used for:** the USB-to-serial drivers behind the serial console - FTDI,
  Prolific PL2303, Silicon Labs CP210x, WCH CH34x and CDC/ACM.
- **Notes:** the only dependency not served from Maven Central or Google. The
  JitPack repository is declared in `settings.gradle.kts` with a content filter
  restricting it to the group `com.github.mik3y`, so it cannot answer for any
  other dependency. The AAR carries its own R8 rule for the reflection its
  prober uses; no rule was added in `:app`.

## Why these and not the obvious alternatives

The natural choice for terminal emulation would have been Termux's
`terminal-emulator`, which is **GPLv3**. Taking it would have placed the whole
of NetToolbox under GPLv3, because linking a GPLv3 library into an
application makes the combined work GPLv3.

That was originally the accepted plan, and it would have been a legitimate
choice — it is only a constraint, not a defect. It was reconsidered because
the GPLv3 would have bought comparatively little: Termux's emulator is built
to host **local processes** (pty allocation, fork/exec, signals, job control),
and an SSH client needs none of that. It renders a byte stream from a remote
host. Once that narrower requirement was clear, libvterm's MIT-licensed state
machine covered it, and Termux's Android `View` would have had to be replaced
by a Compose renderer in any case.

Also considered and rejected:

- **JediTerm** (JetBrains) — LGPL. For an Android app with no meaningful
  dynamic linking and store distribution, the relinking obligation is legally
  unclear. Avoided on the grounds that unclear is not the same as permitted.
- **xterm.js** — MIT, but runs in a WebView, which `PROJECT_SPEC.md`
  prohibits.
- **A hand-written VT parser** — no licence cost, but the parts that decide
  whether a terminal is usable in practice (alternate screen buffer, scroll
  regions, CJK and emoji cell widths, mouse reporting) are precisely the parts
  that are laborious to get right.
