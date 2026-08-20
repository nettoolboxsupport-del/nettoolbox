// JNI bridge to the vendored libiperf (client mode only in this part -
// server mode and the foreground service follow separately).
//
// Every function called into libiperf here is from its public, documented
// API (iperf_api.h), verified by reading the real vendored header rather
// than assumed. The one non-public-API touch is redirecting where iperf3
// would otherwise print its text/JSON reports: by default that is the
// process's stdout (see iperf_api.c, iperf_defaults() sets
// `test->outfile = stdout`), which is not connected to anything useful
// inside an Android app. iperf_set_test_logfile() + iperf_open_logfile()
// are both public API and redirect it to /dev/null instead - the JSON this
// bridge actually returns comes from iperf_get_test_json_output_string(),
// independent of that redirection.
//
// Known limitation, stated rather than hidden: iperf_run_client() is a
// single blocking call for the whole test duration. Coroutine cancellation
// on the Kotlin side is cooperative and cannot preempt a blocking native
// call, and libiperf's public API does not expose a way to abort a running
// test from another thread. A "stop" button on this tool would not be able
// to interrupt an in-progress run - the UI keeps test durations short and
// says so rather than offering a stop control that would not work.

#include <jni.h>
#include <stdint.h>
#include "iperf_api.h"

JNIEXPORT jlong JNICALL
Java_de_nettoolbox_iperf3_Iperf3Native_nativeCreateTest(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;

    struct iperf_test *test = iperf_new_test();
    if (test == NULL) {
        return 0;
    }
    if (iperf_defaults(test) < 0) {
        iperf_free_test(test);
        return 0;
    }

    iperf_set_test_logfile(test, "/dev/null");
    iperf_open_logfile(test);

    return (jlong) (intptr_t) test;
}

JNIEXPORT jboolean JNICALL
Java_de_nettoolbox_iperf3_Iperf3Native_nativeConfigureClient(
    JNIEnv *env, jclass clazz, jlong testPtr, jstring host, jint port,
    jboolean useUdp, jint durationSeconds, jboolean reverse, jint parallelStreams,
    jlong rateLimitBitsPerSecond) {
    (void) clazz;

    struct iperf_test *test = (struct iperf_test *) (intptr_t) testPtr;
    if (test == NULL) {
        return JNI_FALSE;
    }

    const char *host_chars = (*env)->GetStringUTFChars(env, host, NULL);
    if (host_chars == NULL) {
        return JNI_FALSE;
    }

    iperf_set_test_role(test, 'c');
    iperf_set_test_server_hostname(test, host_chars);
    (*env)->ReleaseStringUTFChars(env, host, host_chars);

    iperf_set_test_server_port(test, (int) port);
    set_protocol(test, useUdp == JNI_TRUE ? Pudp : Ptcp);
    iperf_set_test_duration(test, (int) durationSeconds);
    iperf_set_test_reverse(test, reverse == JNI_TRUE ? 1 : 0);

    int streams = (int) parallelStreams;
    iperf_set_test_num_streams(test, streams > 0 ? streams : 1);

    iperf_set_test_json_output(test, 1);

    if (rateLimitBitsPerSecond > 0) {
        iperf_set_test_rate(test, (uint64_t) rateLimitBitsPerSecond);
    }

    return JNI_TRUE;
}

/** @return 0 on success, or the positive libiperf error code (i_errno) on failure. */
JNIEXPORT jint JNICALL
Java_de_nettoolbox_iperf3_Iperf3Native_nativeRunClient(JNIEnv *env, jclass clazz, jlong testPtr) {
    (void) env;
    (void) clazz;

    struct iperf_test *test = (struct iperf_test *) (intptr_t) testPtr;
    if (test == NULL) {
        return -1;
    }

    if (iperf_run_client(test) < 0) {
        return (jint) i_errno;
    }
    return 0;
}

JNIEXPORT jstring JNICALL
Java_de_nettoolbox_iperf3_Iperf3Native_nativeGetJsonOutput(JNIEnv *env, jclass clazz, jlong testPtr) {
    (void) clazz;

    struct iperf_test *test = (struct iperf_test *) (intptr_t) testPtr;
    if (test == NULL) {
        return (*env)->NewStringUTF(env, "");
    }

    char *json = iperf_get_test_json_output_string(test);
    return (*env)->NewStringUTF(env, json != NULL ? json : "");
}

JNIEXPORT jstring JNICALL
Java_de_nettoolbox_iperf3_Iperf3Native_nativeErrorString(JNIEnv *env, jclass clazz, jint errorCode) {
    (void) clazz;

    const char *message = iperf_strerror((int) errorCode);
    return (*env)->NewStringUTF(env, message != NULL ? message : "");
}

JNIEXPORT void JNICALL
Java_de_nettoolbox_iperf3_Iperf3Native_nativeFreeTest(JNIEnv *env, jclass clazz, jlong testPtr) {
    (void) env;
    (void) clazz;

    struct iperf_test *test = (struct iperf_test *) (intptr_t) testPtr;
    if (test != NULL) {
        iperf_free_test(test);
    }
}

// ---- server mode --------------------------------------------------------
//
// Two settings are deliberately NOT applied here, both for the same reason:
// keeping a library call from terminating the whole Android process.
//
//   - one_off is left at its default (0). iperf_server_api.c line ~709 calls
//     exit(0) when a one-off server hits its idle timeout with no connection.
//     That branch is guarded by iperf_get_test_one_off(), so leaving one_off
//     unset makes it unreachable. It is not needed anyway: iperf_run_server()
//     returns after each completed test regardless - the one_off flag only
//     tells iperf3's own CLI loop in main.c whether to break.
//
//   - idle_timeout is never set, which is the second guard on that same
//     exit(0) path.
//
// The one remaining exit() reachable from this module is in readentropy()
// (iperf_util.c), which dies if /dev/urandom cannot be read. On Android that
// file is always readable, and the working client confirms it - but it is a
// real, documented hazard rather than something ruled out.

JNIEXPORT jboolean JNICALL
Java_de_nettoolbox_iperf3_Iperf3Native_nativeConfigureServer(
    JNIEnv *env, jclass clazz, jlong testPtr, jint port) {
    (void) env;
    (void) clazz;

    struct iperf_test *test = (struct iperf_test *) (intptr_t) testPtr;
    if (test == NULL) {
        return JNI_FALSE;
    }
    // Ports below 1024 cannot be bound by an unprivileged process; the Kotlin
    // side rejects those before getting here, this is the backstop.
    if (port < 1024 || port > 65535) {
        return JNI_FALSE;
    }

    iperf_set_test_role(test, 's');
    iperf_set_test_server_port(test, (int) port);
    iperf_set_test_json_output(test, 1);

    return JNI_TRUE;
}

/**
 * Serves exactly one test and returns - the loop lives in Kotlin so it can
 * check a stop flag between runs.
 *
 * Follows iperf3's own server loop from main.c, with one deliberate
 * difference: upstream calls iperf_errexit() when the return code is below
 * -1, which would kill the app. Here every code is handed back to the caller
 * instead.
 *
 * @return iperf_run_server()'s return code: 0 on a completed test, negative
 *   on error, 2 for an authentication failure.
 */
JNIEXPORT jint JNICALL
Java_de_nettoolbox_iperf3_Iperf3Native_nativeRunServerOnce(JNIEnv *env, jclass clazz, jlong testPtr) {
    (void) env;
    (void) clazz;

    struct iperf_test *test = (struct iperf_test *) (intptr_t) testPtr;
    if (test == NULL) {
        return -1;
    }

    int rc = iperf_run_server(test);

    // Mandatory between runs - without it the next iperf_run_server() call
    // starts from the previous test's leftover state (see main.c).
    iperf_reset_test(test);

    return (jint) rc;
}
