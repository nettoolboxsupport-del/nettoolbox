package org.slf4j.impl;

import org.slf4j.IMarkerFactory;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.spi.MarkerFactoryBinder;

/**
 * Present so {@code MarkerFactory} has something to bind to.
 *
 * <p>Nothing in this app uses markers, but SSHD and FtpServer are free to, and
 * a missing binder here makes {@code MarkerFactory} fall back with a warning on
 * every start. SLF4J's own basic implementation is entirely adequate: markers
 * are ignored by the Logcat logger anyway.
 */
public final class StaticMarkerBinder implements MarkerFactoryBinder {

    public static final StaticMarkerBinder SINGLETON = new StaticMarkerBinder();

    private final IMarkerFactory markerFactory = new BasicMarkerFactory();

    private StaticMarkerBinder() {
    }

    public static StaticMarkerBinder getSingleton() {
        return SINGLETON;
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return markerFactory;
    }

    @Override
    public String getMarkerFactoryClassStr() {
        return BasicMarkerFactory.class.getName();
    }
}
