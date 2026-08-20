cmake_minimum_required(VERSION 3.22.1)
project(nettoolbox_iperf3 C)

set(IPERF3_SRC ${CMAKE_CURRENT_SOURCE_DIR}/iperf3-src/src)

# libiperf's own file list (see iperf3-src/src/Makefile.am,
# libiperf_la_SOURCES), minus main.c - that is the CLI entry point, not part
# of the library - and minus iperf_sctp.c / iperf_auth.c, which are excluded
# on purpose rather than compiled with their features switched off. Neither
# SCTP nor OpenSSL is vendored here, and reading iperf_api.c / iperf_client_
# api.c / iperf_server_api.c confirmed every reference to their symbols is
# already wrapped in #if defined(HAVE_SCTP_H) / #if defined(HAVE_SSL) - both
# left undefined in iperf_config.h - so excluding the files entirely is safe
# and lower-risk than including them and trusting an internal guard to cover
# everything.
add_library(nettoolbox_iperf3 SHARED
    ${IPERF3_SRC}/cjson.c
    ${IPERF3_SRC}/iperf_api.c
    ${IPERF3_SRC}/iperf_error.c
    ${IPERF3_SRC}/iperf_client_api.c
    ${IPERF3_SRC}/iperf_locale.c
    ${IPERF3_SRC}/iperf_server_api.c
    ${IPERF3_SRC}/iperf_tcp.c
    ${IPERF3_SRC}/iperf_udp.c
    ${IPERF3_SRC}/iperf_util.c
    ${IPERF3_SRC}/iperf_time.c
    ${IPERF3_SRC}/iperf_pthread.c
    ${IPERF3_SRC}/dscp.c
    ${IPERF3_SRC}/net.c
    ${IPERF3_SRC}/tcp_info.c
    ${IPERF3_SRC}/timer.c
    ${IPERF3_SRC}/units.c
    # This project's own code, not vendored upstream source:
    iperf_jni.c
)

target_include_directories(nettoolbox_iperf3 PRIVATE ${IPERF3_SRC})

# iperf_config.h and version.h in the src/ tree here are hand-written
# replacements for what `./configure` would normally generate - see the long
# comment at the top of iperf_config.h for exactly which flags were set and
# why. Nothing in this CMakeLists.txt runs autotools.

find_library(log-lib log)

target_link_libraries(nettoolbox_iperf3
    ${log-lib}
)

# iperf3's own threading (iperf_pthread.c) needs libpthread's symbols. On
# most current NDK toolchains pthread is folded into libc and this is a
# no-op, but linking it explicitly costs nothing and covers older toolchains
# where it is still a separate library.
find_library(pthread-lib pthread)
if(pthread-lib)
    target_link_libraries(nettoolbox_iperf3 ${pthread-lib})
endif()

target_compile_options(nettoolbox_iperf3 PRIVATE -Wall -Wno-unused-parameter)

# 16 KB page size support - see native/icmp's CMakeLists.txt for why this is
# mandatory for Play Store submissions targeting Android 15+.
target_link_options(nettoolbox_iperf3 PRIVATE "-Wl,-z,max-page-size=16384")
