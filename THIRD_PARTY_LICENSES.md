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
