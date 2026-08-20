cmake_minimum_required(VERSION 3.22.1)
project(nettoolbox_icmp C)

add_library(nettoolbox_icmp SHARED
    icmp_ping.c
)

find_library(log-lib log)

target_link_libraries(nettoolbox_icmp
    ${log-lib}
)

# Diagnostics only - no -Werror. A warning here should be reported back and
# fixed with the exact text, not turned into a hard failure with no
# opportunity to see what it was.
target_compile_options(nettoolbox_icmp PRIVATE -Wall -Wextra)

# 16 KB page size support, mandatory for Play Store submissions targeting
# Android 15+ since November 2025: without this flag the linker lays out ELF
# LOAD segments on the traditional 4 KB boundary, which a 16 KB-page kernel
# then cannot map directly.
target_link_options(nettoolbox_icmp PRIVATE "-Wl,-z,max-page-size=16384")
