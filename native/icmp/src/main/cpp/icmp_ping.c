// Unprivileged ICMPv4 echo (ping) over a SOCK_DGRAM + IPPROTO_ICMP socket.
//
// This relies on the Linux "ping socket" facility: a socket type that lets an
// unprivileged process send and receive ICMP echo request/reply packets
// without CAP_NET_RAW, gated by the `net.ipv4.ping_group_range` sysctl. Most
// Android kernels include the app's GID in that range; some vendors do not.
// There is no way to know in advance - nativeOpen() either succeeds or
// returns a negative errno, and the Kotlin side falls back to another ping
// method when it does.
//
// IPv4 only. IPv6 needs IPPROTO_ICMPV6 over an AF_INET6 socket, a different
// packet layout, and is out of scope for this module - callers should route
// IPv6 targets to the system ping binary instead.
//
// Known kernel behaviour this code relies on (see net/ipv4/ping.c):
//   - bind()'s sin_port acts as the requested ICMP identifier; binding to
//     port 0 lets the kernel assign a free one, exactly like an unbound UDP
//     socket. Incoming echo replies are demultiplexed to the matching socket
//     by that identifier, the same way UDP is demultiplexed by port - so a
//     packet arriving on this fd is already known to be ours.
//   - recvfrom() on a ping socket yields the ICMP message itself (type, code,
//     checksum, id, sequence, data), not the surrounding IP header - unlike a
//     SOCK_RAW socket.
// This is standard, documented behaviour, not vendor-specific, but it has
// only been reasoned through here, not exercised against a real kernel from
// this environment - the honest status is "implemented per documented
// semantics, unverified by a physical test".

#include <jni.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>
#include <time.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <poll.h>

#define ICMP_ECHO_REQUEST_TYPE 8
#define ICMP_ECHO_REPLY_TYPE 0
#define ICMP_TIME_EXCEEDED_TYPE 11
#define ICMP_DEST_UNREACHABLE_TYPE 3

#define ICMP_HEADER_SIZE 8
#define MAX_PACKET_SIZE 2048

// ---- traceroute support: the socket error queue -------------------------
//
// A router that drops a probe because its TTL hit zero, or a host that
// cannot forward it further, replies with an ICMP error - but that error
// does not arrive on the normal recvfrom() path used above for echo replies.
// Linux delivers it separately, through the socket's error queue, which has
// to be switched on with IP_RECVERR and drained with recvmsg(MSG_ERRQUEUE).
// This is the standard mechanism traceroute-style tools use over datagram
// sockets (see ip(7), "IP_RECVERR"), not something specific to ping sockets.
//
// The three pieces below (IP_RECVERR's value, the sock_extended_err layout,
// and the SO_EE_OFFENDER macro) are normally pulled from <linux/errqueue.h>
// and <linux/in.h>. They are defined here by hand instead of included,
// because they are long-stable kernel UAPI - unchanged since these
// mechanisms were introduced in the late 1990s/early 2000s, and every
// existing Linux networking tool already depends on their exact layout not
// changing - which makes hand-defining them lower risk than depending on a
// specific header being present at this exact path in this NDK version.
//
// This is the least-verified part of the whole module: reasoned from
// documented kernel behaviour, never exercised against a real device from
// this environment. If traceroute never resolves an intermediate hop's
// address - only ever showing the final destination or nothing at all -
// this is the first place to look.

#ifndef IP_RECVERR
#define IP_RECVERR 11
#endif

#define NETTOOLBOX_SO_EE_ORIGIN_ICMP 2

struct nettoolbox_sock_extended_err {
    uint32_t ee_errno;
    uint8_t ee_origin;
    uint8_t ee_type;
    uint8_t ee_code;
    uint8_t ee_pad;
    uint32_t ee_info;
    uint32_t ee_data;
};

#define NETTOOLBOX_SO_EE_OFFENDER(ee) \
    ((struct sockaddr *) (((struct nettoolbox_sock_extended_err *) (ee)) + 1))

static uint16_t internet_checksum(const void *data, size_t length) {
    const uint16_t *word = (const uint16_t *) data;
    uint32_t sum = 0;

    while (length > 1) {
        sum += *word++;
        length -= 2;
    }
    if (length == 1) {
        sum += *(const uint8_t *) word;
    }
    while (sum >> 16) {
        sum = (sum & 0xFFFFu) + (sum >> 16);
    }
    return (uint16_t) ~sum;
}

// Opens and binds a ping socket. Binding to port 0 asks the kernel to assign
// a free ICMP identifier, so there is nothing for the caller to collide with
// and nothing it needs to remember afterwards.
JNIEXPORT jint JNICALL
Java_de_nettoolbox_icmp_IcmpNative_nativeOpen(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;

    int fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_ICMP);
    if (fd < 0) {
        // Negative errno, not just -1: the caller can tell "permission
        // denied by ping_group_range" (EACCES/EPERM) apart from other
        // failures, which is the distinction that decides whether this
        // device can ever use this transport.
        return -errno;
    }

    struct sockaddr_in local_addr;
    memset(&local_addr, 0, sizeof(local_addr));
    local_addr.sin_family = AF_INET;
    local_addr.sin_addr.s_addr = INADDR_ANY;
    local_addr.sin_port = 0;

    if (bind(fd, (struct sockaddr *) &local_addr, sizeof(local_addr)) < 0) {
        int bind_errno = errno;
        close(fd);
        return -bind_errno;
    }

    return fd;
}

JNIEXPORT jboolean JNICALL
Java_de_nettoolbox_icmp_IcmpNative_nativeSetTtl(JNIEnv *env, jclass clazz, jint fd, jint ttl) {
    (void) env;
    (void) clazz;

    int ttl_value = (int) ttl;
    return setsockopt(fd, IPPROTO_IP, IP_TTL, &ttl_value, sizeof(ttl_value)) == 0
               ? JNI_TRUE
               : JNI_FALSE;
}

// destinationIp must be a numeric IPv4 address. Hostname resolution happens
// on the Kotlin side (java.net.InetAddress, already used everywhere else in
// the app) rather than via getaddrinfo() here, so this module has exactly
// one job and DNS failure modes stay in one place.
JNIEXPORT jboolean JNICALL
Java_de_nettoolbox_icmp_IcmpNative_nativeSendEcho(JNIEnv *env, jclass clazz, jint fd,
                                                   jstring destinationIp, jint sequence,
                                                   jint payloadSize) {
    (void) clazz;

    const char *ip_chars = (*env)->GetStringUTFChars(env, destinationIp, NULL);
    if (ip_chars == NULL) {
        return JNI_FALSE;
    }

    struct sockaddr_in dest_addr;
    memset(&dest_addr, 0, sizeof(dest_addr));
    dest_addr.sin_family = AF_INET;
    int parsed = inet_pton(AF_INET, ip_chars, &dest_addr.sin_addr);
    (*env)->ReleaseStringUTFChars(env, destinationIp, ip_chars);

    if (parsed != 1) {
        return JNI_FALSE;
    }

    int payload_len = (int) payloadSize;
    if (payload_len < 0) {
        payload_len = 0;
    }
    if (payload_len > MAX_PACKET_SIZE - ICMP_HEADER_SIZE) {
        payload_len = MAX_PACKET_SIZE - ICMP_HEADER_SIZE;
    }

    uint8_t packet[MAX_PACKET_SIZE];
    size_t packet_len = (size_t) ICMP_HEADER_SIZE + (size_t) payload_len;
    memset(packet, 0, packet_len);

    packet[0] = ICMP_ECHO_REQUEST_TYPE;
    packet[1] = 0; // code
    // packet[2..3] (checksum) filled in below, once the rest is written.
    // packet[4..5] (identifier): left at 0. The kernel overwrites this with
    // the socket's bound port for outgoing ping-socket packets regardless of
    // what is placed here, so filling in a value would be pointless - and
    // the reply is already known to be ours by virtue of arriving on this fd.
    uint16_t sequence_be = htons((uint16_t) sequence);
    memcpy(packet + 6, &sequence_be, sizeof(sequence_be));

    for (int i = 0; i < payload_len; i++) {
        // A recognisable, non-zero pattern - useful if this packet is ever
        // inspected in a capture while debugging; an all-zero payload gives
        // no such confirmation.
        packet[ICMP_HEADER_SIZE + i] = (uint8_t) (0x40 + (i % 32));
    }

    uint16_t csum = internet_checksum(packet, packet_len);
    memcpy(packet + 2, &csum, sizeof(csum));

    ssize_t sent = sendto(fd, packet, packet_len, 0,
                           (struct sockaddr *) &dest_addr, sizeof(dest_addr));
    return sent == (ssize_t) packet_len ? JNI_TRUE : JNI_FALSE;
}

static jobjectArray make_result(JNIEnv *env, const char *status, const char *from) {
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    jobjectArray result = (*env)->NewObjectArray(env, 2, string_class, NULL);
    (*env)->SetObjectArrayElement(env, result, 0, (*env)->NewStringUTF(env, status));
    (*env)->SetObjectArrayElement(env, result, 1, (*env)->NewStringUTF(env, from));
    return result;
}

// Blocks until an echo reply matching `sequence` arrives or the timeout
// elapses, discarding replies to any other sequence (a late reply from a
// previous probe) rather than returning on the first packet seen - the same
// behaviour a normal ping client has toward duplicate or delayed replies.
JNIEXPORT jobjectArray JNICALL
Java_de_nettoolbox_icmp_IcmpNative_nativeReceiveEcho(JNIEnv *env, jclass clazz, jint fd,
                                                      jint sequence, jint timeoutMillis) {
    (void) clazz;

    struct pollfd poll_fd;
    poll_fd.fd = fd;
    poll_fd.events = POLLIN;
    poll_fd.revents = 0;

    long remaining_ms = timeoutMillis;
    uint8_t buffer[MAX_PACKET_SIZE];

    while (remaining_ms > 0) {
        struct timespec before;
        clock_gettime(CLOCK_MONOTONIC, &before);

        int poll_result = poll(&poll_fd, 1, (int) remaining_ms);

        struct timespec after;
        clock_gettime(CLOCK_MONOTONIC, &after);
        long elapsed_ms = (after.tv_sec - before.tv_sec) * 1000L +
                           (after.tv_nsec - before.tv_nsec) / 1000000L;
        remaining_ms -= elapsed_ms > 0 ? elapsed_ms : 1;

        if (poll_result == 0) {
            break; // timed out without a matching reply
        }
        if (poll_result < 0) {
            return make_result(env, "ERROR", strerror(errno));
        }

        struct sockaddr_in from_addr;
        socklen_t from_len = sizeof(from_addr);
        ssize_t received = recvfrom(fd, buffer, sizeof(buffer), 0,
                                     (struct sockaddr *) &from_addr, &from_len);
        if (received < ICMP_HEADER_SIZE) {
            continue;
        }

        uint8_t type = buffer[0];
        uint16_t reply_sequence;
        memcpy(&reply_sequence, buffer + 6, sizeof(reply_sequence));
        reply_sequence = ntohs(reply_sequence);

        if (type == ICMP_ECHO_REPLY_TYPE && reply_sequence == (uint16_t) sequence) {
            char from_str[INET_ADDRSTRLEN];
            inet_ntop(AF_INET, &from_addr.sin_addr, from_str, sizeof(from_str));
            return make_result(env, "REPLY", from_str);
        }
        // Anything else (a reply to a different sequence) is dropped; the
        // loop keeps waiting within whatever timeout budget remains.
    }

    return make_result(env, "TIMEOUT", "");
}

JNIEXPORT void JNICALL
Java_de_nettoolbox_icmp_IcmpNative_nativeClose(JNIEnv *env, jclass clazz, jint fd) {
    (void) env;
    (void) clazz;
    if (fd >= 0) {
        close(fd);
    }
}

// Switches on delivery of ICMP errors (TIME_EXCEEDED, DEST_UNREACHABLE) that
// this socket's own outgoing packets provoke. Must be called once per socket,
// before the first traceroute probe is sent.
JNIEXPORT jboolean JNICALL
Java_de_nettoolbox_icmp_IcmpNative_nativeEnableReceiveErrors(JNIEnv *env, jclass clazz, jint fd) {
    (void) env;
    (void) clazz;
    int on = 1;
    return setsockopt(fd, IPPROTO_IP, IP_RECVERR, &on, sizeof(on)) == 0 ? JNI_TRUE : JNI_FALSE;
}

// Drains one entry from the error queue and, if it is an ICMP error that
// belongs to `expected_sequence`, returns a result for it. Returns NULL when
// the queue is empty, the entry does not carry an ICMP error, or it belongs
// to a different (older) probe - in which case the caller should keep
// waiting rather than treat this as the answer for the current hop.
static jobjectArray read_error_queue(JNIEnv *env, int fd, jint expected_sequence) {
    uint8_t data_buf[MAX_PACKET_SIZE];
    uint8_t control_buf[512];

    struct iovec iov;
    iov.iov_base = data_buf;
    iov.iov_len = sizeof(data_buf);

    struct sockaddr_in peer_addr;
    memset(&peer_addr, 0, sizeof(peer_addr));

    struct msghdr msg;
    memset(&msg, 0, sizeof(msg));
    msg.msg_name = &peer_addr;
    msg.msg_namelen = sizeof(peer_addr);
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control_buf;
    msg.msg_controllen = sizeof(control_buf);

    ssize_t n = recvmsg(fd, &msg, MSG_ERRQUEUE);
    if (n < 0) {
        return NULL;
    }

    // The payload here is our own outgoing echo request, returned inside the
    // error report - its sequence number identifies which hop this error
    // belongs to, the same way a normal reply's sequence does in
    // nativeReceiveEcho above.
    if (n >= ICMP_HEADER_SIZE) {
        uint16_t original_sequence;
        memcpy(&original_sequence, data_buf + 6, sizeof(original_sequence));
        original_sequence = ntohs(original_sequence);
        if (original_sequence != (uint16_t) expected_sequence) {
            return NULL;
        }
    }

    for (struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg); cmsg != NULL; cmsg = CMSG_NXTHDR(&msg, cmsg)) {
        if (cmsg->cmsg_level != IPPROTO_IP || cmsg->cmsg_type != IP_RECVERR) {
            continue;
        }

        struct nettoolbox_sock_extended_err *ee =
            (struct nettoolbox_sock_extended_err *) CMSG_DATA(cmsg);
        if (ee->ee_origin != NETTOOLBOX_SO_EE_ORIGIN_ICMP) {
            continue;
        }

        struct sockaddr_in *offender = (struct sockaddr_in *) NETTOOLBOX_SO_EE_OFFENDER(ee);
        char offender_str[INET_ADDRSTRLEN];
        offender_str[0] = '\0';
        if (offender != NULL && offender->sin_family == AF_INET) {
            inet_ntop(AF_INET, &offender->sin_addr, offender_str, sizeof(offender_str));
        }

        if (ee->ee_type == ICMP_TIME_EXCEEDED_TYPE) {
            return make_result(env, "TIME_EXCEEDED", offender_str);
        }
        if (ee->ee_type == ICMP_DEST_UNREACHABLE_TYPE) {
            return make_result(env, "UNREACHABLE", offender_str);
        }
        // Some other ICMP error type reached us - reported rather than
        // silently dropped, so it is at least visible during diagnosis.
        return make_result(env, "ERROR", offender_str);
    }

    return NULL;
}

// One traceroute probe's outcome: the destination's own echo reply (probe
// reached all the way), an intermediate router's TIME_EXCEEDED, a
// DEST_UNREACHABLE, or a timeout because nothing answered at all - the last
// being the normal, expected result for plenty of hops on the real internet,
// not a failure of this code.
JNIEXPORT jobjectArray JNICALL
Java_de_nettoolbox_icmp_IcmpNative_nativeReceiveHop(JNIEnv *env, jclass clazz, jint fd,
                                                     jint sequence, jint timeoutMillis) {
    (void) clazz;

    struct pollfd poll_fd;
    poll_fd.fd = fd;
    poll_fd.events = POLLIN;

    long remaining_ms = timeoutMillis;
    uint8_t buffer[MAX_PACKET_SIZE];

    while (remaining_ms > 0) {
        struct timespec before;
        clock_gettime(CLOCK_MONOTONIC, &before);

        poll_fd.revents = 0;
        int poll_result = poll(&poll_fd, 1, (int) remaining_ms);

        struct timespec after;
        clock_gettime(CLOCK_MONOTONIC, &after);
        long elapsed_ms = (after.tv_sec - before.tv_sec) * 1000L +
                           (after.tv_nsec - before.tv_nsec) / 1000000L;
        remaining_ms -= elapsed_ms > 0 ? elapsed_ms : 1;

        if (poll_result == 0) {
            break; // nothing answered within the budget - a normal outcome
        }
        if (poll_result < 0) {
            return make_result(env, "ERROR", strerror(errno));
        }

        // POLLERR is reported whenever it applies regardless of the
        // requested event mask (poll(2)), so it does not need to be added to
        // poll_fd.events above.
        if (poll_fd.revents & POLLERR) {
            jobjectArray error_result = read_error_queue(env, fd, sequence);
            if (error_result != NULL) {
                return error_result;
            }
            continue; // queue entry was for a different, older probe
        }

        if (poll_fd.revents & POLLIN) {
            struct sockaddr_in from_addr;
            socklen_t from_len = sizeof(from_addr);
            ssize_t received = recvfrom(fd, buffer, sizeof(buffer), 0,
                                         (struct sockaddr *) &from_addr, &from_len);
            if (received < ICMP_HEADER_SIZE) {
                continue;
            }

            uint8_t type = buffer[0];
            uint16_t reply_sequence;
            memcpy(&reply_sequence, buffer + 6, sizeof(reply_sequence));
            reply_sequence = ntohs(reply_sequence);

            if (type == ICMP_ECHO_REPLY_TYPE && reply_sequence == (uint16_t) sequence) {
                char from_str[INET_ADDRSTRLEN];
                inet_ntop(AF_INET, &from_addr.sin_addr, from_str, sizeof(from_str));
                return make_result(env, "REPLY", from_str);
            }
        }
    }

    return make_result(env, "TIMEOUT", "");
}
