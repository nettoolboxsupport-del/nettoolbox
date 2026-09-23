# Phase 9 — Serial console over USB

A terminal on a USB-to-serial adapter or on the USB console port of Cisco
equipment. It closes the gap the file server left: a switch with no IP address
yet cannot be reached over SSH, and `copy tftp: flash:` still had to be typed
somewhere. With both features on one phone the console cable and the TFTP
server run side by side.

---

## Decisions

### The terminal moved into `:core:terminal`

`TerminalCanvas` and `ModifierBar` lived in `:feature:ssh`. They were moved, not
copied, into a new module together with `TerminalPane` - screen, invisible input
field and extra-key bar as one composable - and SSH now uses that too.

The input overlay in particular came out of the long "cannot type" investigation
in phase 6. A second copy for the serial console would have drifted, and the
next keyboard fix would have landed in only one of the two.

`TerminalEmulator` wraps libvterm plus the Ctrl/Alt latches. **SSH does not use
it**, deliberately: `SshViewModel` drives `VtermBridge` directly, its session
code was hard-won, and migrating it for tidiness alone would risk that for no
visible gain.

### The session is a singleton, not the screen's ViewModel

The point of the feature is to use it together with the file server - and
reaching the file server means leaving this screen. A screen-scoped ViewModel
would close the port the moment the user navigated back past it.
`SerialConsoleManager` keeps the port, the reader and the screen contents for as
long as the process lives.

### Everything that touches libvterm runs on the main thread

libvterm is not thread-safe and the renderer reads it while drawing on the main
thread. The library's reader thread hands received bytes over rather than
writing into the terminal itself - the same thing the library's own example
does. At serial line rates the hop costs nothing.

The SSH terminal feeds libvterm from an I/O thread and has not shown a problem;
it is recorded here as a known difference, not changed.

### `ShareStorage` moved to `:core:common`

The console writes its session logs into the file server's share, under
`Console-Logs/`, so a captured `show running-config` can be fetched over SFTP
straight away. Two features needing the same root means the root cannot live in
either of them.

### usb-serial-for-android, from JitPack, scoped to one group

The standard library for this (MIT, active, 3.11.0 from July 2026). It is
published only on JitPack. The repository is declared with
`content { includeGroup("com.github.mik3y") }`: JitPack builds whatever a GitHub
repository contains, and an unfiltered entry would let it answer for any
dependency Maven Central does not have.

The AAR carries its own R8 rule, which covers the reflection its prober uses
(`getMethod("probe")`, `getConstructor(UsbDevice)`). Verified in `proguard.txt`
inside the AAR; no rule was added in `:app`.

### No permission, no service

USB host access is granted per device by the user at runtime; nothing is
declared in the manifest beyond the existing optional `usb.host` feature.

There is no foreground service. The screen is kept on while a session is open,
and the file server's service - which is what runs alongside in the intended
workflow - keeps the process alive anyway.

### The app offers itself when an adapter is plugged in

`MainActivity` has a `USB_DEVICE_ATTACHED` filter with a device list for FTDI,
CP210x, PL2303, CH34x and Cisco's console port (05a6:0009, confirmed in the
linux-usb.org USB ID repository). Accepting the offer also grants permission for
that device. The list only drives the prompt: detection inside the app also
recognises CDC/ACM devices by interface class.

---

## Details that are easy to get wrong

- **BREAK** holds the line for 300 ms, inside the 0.25-0.5 s of POSIX
  `tcsendbreak()`. It is how a Cisco device is dropped into ROMMON for password
  recovery. Adapters that cannot do it say so instead of failing silently.
- **Pasting** goes line by line with a pause (default 100 ms). At full speed a
  device loses lines without any error. The last line is sent without Enter
  unless the text ends in a line break - one copied command lands on the prompt
  instead of executing on a live device.
- **Paste confirmation.** Every line of a paste is executed as it arrives, so a
  dialog shows the line count and the first lines before anything is sent. The
  count comes from the same function the sender uses.
- **Flow control** is offered only as far as the connected chip supports it;
  an unsupported request falls back to none and says so.
- **Line settings apply live.** Garbage on screen almost always means a wrong
  baud rate, and changing it should not cost what the device printed meanwhile.
- **Session logging is off by default.** A console capture routinely contains
  configuration with password hashes and SNMP communities.

---

## Verification status

**Written without a compiler, then built and run by the user on 23.09.2026.**
Gradle could not run in the session it was written in (no loopback interface visible to the JVM - see
PHASE8_NOTES.md). Checked without a compiler: every library API used here was
read out of the AAR with `javap`, the permission flow and reader handling were
compared against the library's own example project, both string files hold the
same 69 keys with none missing or unused, and no source file contains a stray
NUL byte.

Unit tests cover the paste splitting and the Enter rewriting in `SerialText`.

**Tested on hardware (23.09.2026):** Aruba CX switch, USB-C console port,
connected with a plain USB-C to USB-C cable - console session works. Found on
that test: the soft keyboard's backspace did nothing. Fixed in the commit that
follows this one, in `TerminalPane`, so it applies to SSH as well.

Not yet tested on hardware: the FTDI, CP210x, PL2303 and CH34x adapters, Cisco's
USB console port, BREAK, and the plug-in prompt.

What cannot be checked without hardware: whether a given adapter works. That
needs a USB-serial adapter or a device with a USB console port, and an OTG
adapter for a phone with only USB-C.

---

## Review before 1.2.0

A pass over the whole code base before the next Play release. Automated scans
across all modules, then a close read of phases 8 and 9, which had been written
without a compiler and never reviewed.

### Fixed

1. **Read-only SCP accounts could truncate files.** SSHD's SCP receive path
   opens the target with TRUNCATE_EXISTING *before* it raises the transfer
   event the read-only check hung off - read out of `ScpHelper.receiveStream`
   in the 2.18.0 bytecode. The check now sits in a `ScpFileOpener`, at
   `resolveIncomingReceiveLocation` and `openWrite`. SFTP was verified in the
   same way and was not affected: its opening event comes before the open.
2. **SSH terminal logged keystrokes and remote output** at DEBUG, in release
   builds - left over from the phase 6 "cannot type" investigation. Removed at
   the source, and R8 now strips `Log.d` and `Log.v` from release builds.
3. **Starting the file server away from Wi-Fi crashed the app.** With
   "Wi-Fi only" and no Wi-Fi the service stopped before `startForeground()`,
   which Android punishes with a crash. The service now enters the foreground
   synchronously in `onStartCommand`.
4. **A failed start showed no reason.** When no protocol could start, the
   failure was written and then wiped by the reset in `stopServers()`. Order
   reversed.
5. **Import took the provider's display name as a path.** A name like
   `../../datastore/file_server.json` would have been written outside the
   share, over the app's own files. Only the last segment is used now,
   cleaned like a name typed into the rename dialog.
6. **SSH host key fingerprint:** read from disk on the main thread at every
   redraw, and still showing the old key after "generate a new key". Cached in
   `HostKeyStore`, exposed as state.
7. **TFTP had no limit on concurrent transfers.** Unauthenticated, each one
   holding a thread; a flood could stall every tool in the app. Capped at 16.
8. Smaller: `runCatching` swallowing cancellation in the widget; a log line
   printing a literal `$count`; the explorer's refresh button announcing
   itself to TalkBack as "OK".

### Looked at and deliberately left

- **Technical labels hardcoded in UI code** - TCP, UDP, mDNS, key names such as
  Home or F1. These are the same in both languages.
- **SSH feeds libvterm from an I/O thread**, unlike the serial console. It has
  not shown a problem, and its session code is the one part of the app that
  took a long investigation to get right.
- The baseline profile predates both new features. Worth regenerating, but not
  a correctness issue.
