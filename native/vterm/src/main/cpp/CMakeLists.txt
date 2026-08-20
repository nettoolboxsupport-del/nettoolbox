cmake_minimum_required(VERSION 3.22.1)
project(nettoolbox_vterm C)

set(VTERM_SRC ${CMAKE_CURRENT_SOURCE_DIR}/libvterm-src)

# libvterm's own file list. Taken from libvterm-src/Makefile, which builds
# every .c under src/ into the library - there is no equivalent of iperf3's
# optional SCTP/OpenSSL pieces here, so nothing is excluded.
#
# Unlike iperf3 this needs NO replacement for a configure step: libvterm has
# no generated config header at all, and the version macros live directly in
# include/vterm.h (VTERM_VERSION_MAJOR/MINOR). Verified by grepping the tree
# for config.h and for external includes - there are none beyond the C
# standard library.
#
# The Makefile does generate three .inc files with perl (encoding/%.inc from
# %.tbl, and fullwidth.inc). That would have been the same trap as iperf3's
# ./configure under Windows - but all three are already checked in
# (src/fullwidth.inc, src/encoding/DECdrawing.inc, src/encoding/uk.inc), so
# no perl runs at build time. They are #included by encoding.c and unicode.c
# as plain headers.
add_library(nettoolbox_vterm SHARED
    ${VTERM_SRC}/src/encoding.c
    ${VTERM_SRC}/src/keyboard.c
    ${VTERM_SRC}/src/mouse.c
    ${VTERM_SRC}/src/parser.c
    ${VTERM_SRC}/src/pen.c
    ${VTERM_SRC}/src/screen.c
    ${VTERM_SRC}/src/state.c
    ${VTERM_SRC}/src/unicode.c
    ${VTERM_SRC}/src/vterm.c
    # This project's own code, not vendored upstream source:
    vterm_jni.c
)

# src/ is on the include path because encoding.c does #include
# "encoding/DECdrawing.inc" relative to itself, and the sources include
# "vterm_internal.h" from the same directory.
target_include_directories(nettoolbox_vterm PRIVATE
    ${VTERM_SRC}/include
    ${VTERM_SRC}/src
)

# libvterm targets C99 upstream (its Makefile passes -std=c99). The NDK would
# default to a later standard that is a superset, but pinning it matches what
# the code is actually tested against.
set_target_properties(nettoolbox_vterm PROPERTIES C_STANDARD 99)

find_library(log-lib log)

target_link_libraries(nettoolbox_vterm ${log-lib})

# Diagnostics only, no -Werror: a warning in vendored code should be read and
# judged, not turned into a build failure that hides its own text.
target_compile_options(nettoolbox_vterm PRIVATE -Wall -Wno-unused-parameter)

# 16 KB page size support - mandatory for Play Store submissions targeting
# Android 15+ since November 2025. See :native:icmp for the full reasoning.
target_link_options(nettoolbox_vterm PRIVATE "-Wl,-z,max-page-size=16384")
