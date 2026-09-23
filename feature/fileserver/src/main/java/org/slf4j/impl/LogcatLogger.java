package org.slf4j.impl;

import android.util.Log;

import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MarkerIgnoringBase;
import org.slf4j.helpers.MessageFormatter;

/**
 * Routes one SLF4J logger to Logcat.
 *
 * <p>Extends {@code MarkerIgnoringBase} so only the ten core methods have to be
 * written; SLF4J supplies the forty marker-carrying overloads by delegating to
 * them.
 *
 * <h2>Why the release build is quiet</h2>
 *
 * <p>Apache MINA SSHD logs at DEBUG on essentially every packet. Left enabled
 * that is thousands of lines per session, and Logcat is a shared, world-readable
 * buffer: file names, user names and remote addresses would be visible to
 * anything on the device that can read it. Rather than gate on a build flag, the
 * level check goes through {@link Log#isLoggable}, which reports false for DEBUG
 * and TRACE unless someone deliberately turns them on with
 * {@code adb shell setprop log.tag.<tag> DEBUG}.
 *
 * <p>That is the better mechanism for two reasons: it is the platform's own, and
 * it means a user reporting a problem can be walked through enabling protocol
 * logging on a release build without needing a special version of the app.
 */
final class LogcatLogger extends MarkerIgnoringBase {

    /**
     * Logcat tags were capped at 23 characters before API 26, and long tags are
     * unreadable in a terminal regardless. A fully qualified SSHD class name is
     * far past that, so the tag keeps the prefix and the simple name.
     */
    private static final int MAX_TAG_LENGTH = 23;
    private static final String PREFIX = "NT.";

    private final String tag;

    LogcatLogger(String name) {
        this.name = name;
        this.tag = buildTag(name);
    }

    private static String buildTag(String name) {
        int lastDot = name.lastIndexOf('.');
        String simple = lastDot < 0 ? name : name.substring(lastDot + 1);
        String candidate = PREFIX + simple;
        return candidate.length() <= MAX_TAG_LENGTH
                ? candidate
                : candidate.substring(0, MAX_TAG_LENGTH);
    }

    // --- level checks -------------------------------------------------------

    @Override
    public boolean isTraceEnabled() {
        return Log.isLoggable(tag, Log.VERBOSE);
    }

    @Override
    public boolean isDebugEnabled() {
        return Log.isLoggable(tag, Log.DEBUG);
    }

    @Override
    public boolean isInfoEnabled() {
        return Log.isLoggable(tag, Log.INFO);
    }

    @Override
    public boolean isWarnEnabled() {
        return Log.isLoggable(tag, Log.WARN);
    }

    @Override
    public boolean isErrorEnabled() {
        return Log.isLoggable(tag, Log.ERROR);
    }

    // --- trace --------------------------------------------------------------

    @Override
    public void trace(String message) {
        log(Log.VERBOSE, message, null);
    }

    @Override
    public void trace(String format, Object argument) {
        formatted(Log.VERBOSE, format, new Object[]{argument});
    }

    @Override
    public void trace(String format, Object first, Object second) {
        formatted(Log.VERBOSE, format, new Object[]{first, second});
    }

    @Override
    public void trace(String format, Object... arguments) {
        formatted(Log.VERBOSE, format, arguments);
    }

    @Override
    public void trace(String message, Throwable thrown) {
        log(Log.VERBOSE, message, thrown);
    }

    // --- debug --------------------------------------------------------------

    @Override
    public void debug(String message) {
        log(Log.DEBUG, message, null);
    }

    @Override
    public void debug(String format, Object argument) {
        formatted(Log.DEBUG, format, new Object[]{argument});
    }

    @Override
    public void debug(String format, Object first, Object second) {
        formatted(Log.DEBUG, format, new Object[]{first, second});
    }

    @Override
    public void debug(String format, Object... arguments) {
        formatted(Log.DEBUG, format, arguments);
    }

    @Override
    public void debug(String message, Throwable thrown) {
        log(Log.DEBUG, message, thrown);
    }

    // --- info ---------------------------------------------------------------

    @Override
    public void info(String message) {
        log(Log.INFO, message, null);
    }

    @Override
    public void info(String format, Object argument) {
        formatted(Log.INFO, format, new Object[]{argument});
    }

    @Override
    public void info(String format, Object first, Object second) {
        formatted(Log.INFO, format, new Object[]{first, second});
    }

    @Override
    public void info(String format, Object... arguments) {
        formatted(Log.INFO, format, arguments);
    }

    @Override
    public void info(String message, Throwable thrown) {
        log(Log.INFO, message, thrown);
    }

    // --- warn ---------------------------------------------------------------

    @Override
    public void warn(String message) {
        log(Log.WARN, message, null);
    }

    @Override
    public void warn(String format, Object argument) {
        formatted(Log.WARN, format, new Object[]{argument});
    }

    @Override
    public void warn(String format, Object... arguments) {
        formatted(Log.WARN, format, arguments);
    }

    @Override
    public void warn(String format, Object first, Object second) {
        formatted(Log.WARN, format, new Object[]{first, second});
    }

    @Override
    public void warn(String message, Throwable thrown) {
        log(Log.WARN, message, thrown);
    }

    // --- error --------------------------------------------------------------

    @Override
    public void error(String message) {
        log(Log.ERROR, message, null);
    }

    @Override
    public void error(String format, Object argument) {
        formatted(Log.ERROR, format, new Object[]{argument});
    }

    @Override
    public void error(String format, Object first, Object second) {
        formatted(Log.ERROR, format, new Object[]{first, second});
    }

    @Override
    public void error(String format, Object... arguments) {
        formatted(Log.ERROR, format, arguments);
    }

    @Override
    public void error(String message, Throwable thrown) {
        log(Log.ERROR, message, thrown);
    }

    // --- internals ----------------------------------------------------------

    /**
     * Expands SLF4J's {@code {}} placeholders, but only once the level is on.
     *
     * <p>The check comes first on purpose: formatting is the expensive part, and
     * SSHD's debug messages interpolate buffers and key names on paths that run
     * per packet.
     */
    private void formatted(int level, String format, Object[] arguments) {
        if (!Log.isLoggable(tag, level)) {
            return;
        }
        FormattingTuple tuple = MessageFormatter.arrayFormat(format, arguments);
        log(level, tuple.getMessage(), tuple.getThrowable());
    }

    private void log(int level, String message, Throwable thrown) {
        if (!Log.isLoggable(tag, level)) {
            return;
        }
        String text = thrown == null
                ? message
                : message + '\n' + Log.getStackTraceString(thrown);
        Log.println(level, tag, text == null ? "null" : text);
    }
}
