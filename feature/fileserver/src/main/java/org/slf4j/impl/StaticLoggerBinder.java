package org.slf4j.impl;

import org.slf4j.ILoggerFactory;
import org.slf4j.spi.LoggerFactoryBinder;

/**
 * The SLF4J 1.7 binding for this app.
 *
 * <p>Apache MINA SSHD and Apache FtpServer both log through SLF4J and both pin
 * slf4j-api to 1.7.36 - SSHD's parent POM carries an explicit warning against
 * going beyond it. There is no maintained 1.7 binding that writes to Logcat
 * (the Android binding only exists for 2.0), so this project supplies its own.
 *
 * <p>Written in Java rather than Kotlin on purpose. The 1.7 binding contract is
 * a set of exact static members that {@code LoggerFactory} links against at
 * compile time: a class named {@code org.slf4j.impl.StaticLoggerBinder}, a
 * static {@code getSingleton()}, and a static {@code REQUESTED_API_VERSION}
 * field. Kotlin can be made to emit that shape, but only by decorating it with
 * annotations that obscure why it looks the way it does - and the shape is the
 * whole point of the file.
 *
 * <p>Because the link is a compile-time reference rather than a
 * {@code ServiceLoader} lookup, R8 keeps this class without a keep rule, and no
 * reflection is involved anywhere. That was the deciding argument: the
 * ServiceLoader-based discovery of SLF4J 2.0 is exactly the kind of thing that
 * works in debug and disappears in release.
 */
public final class StaticLoggerBinder implements LoggerFactoryBinder {

    /**
     * Checked by {@code LoggerFactory} against its own version.
     *
     * <p>A mismatch produces a warning on stderr and nothing worse, but there
     * is no reason to earn one.
     */
    public static final String REQUESTED_API_VERSION = "1.7.36";

    private static final StaticLoggerBinder SINGLETON = new StaticLoggerBinder();

    private final ILoggerFactory loggerFactory = new LogcatLoggerFactory();

    private StaticLoggerBinder() {
    }

    public static StaticLoggerBinder getSingleton() {
        return SINGLETON;
    }

    @Override
    public ILoggerFactory getLoggerFactory() {
        return loggerFactory;
    }

    @Override
    public String getLoggerFactoryClassStr() {
        return LogcatLoggerFactory.class.getName();
    }
}
