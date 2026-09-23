package org.slf4j.impl;

import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;

/**
 * A no-op mapped diagnostic context.
 *
 * <p>MDC carries per-thread context into log lines. That is useful in a server
 * that handles many requests on a thread pool and writes to a file - and
 * useless here, where the log a user actually reads is the in-app transfer log,
 * built from the servers' own event listeners rather than from SLF4J.
 *
 * <p>The class exists only so {@code MDC} finds a binder instead of warning on
 * first use.
 */
public final class StaticMDCBinder {

    public static final StaticMDCBinder SINGLETON = new StaticMDCBinder();

    private StaticMDCBinder() {
    }

    public static StaticMDCBinder getSingleton() {
        return SINGLETON;
    }

    public MDCAdapter getMDCA() {
        return new NOPMDCAdapter();
    }

    public String getMDCAdapterClassStr() {
        return NOPMDCAdapter.class.getName();
    }
}
