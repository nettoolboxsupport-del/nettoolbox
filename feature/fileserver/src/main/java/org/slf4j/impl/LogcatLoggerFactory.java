package org.slf4j.impl;

import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Hands out one {@link LogcatLogger} per name and remembers it.
 *
 * <p>Cached because SSHD asks for its logger inside per-connection classes, and
 * building the Logcat tag involves string work that has no business running on
 * every session.
 */
final class LogcatLoggerFactory implements ILoggerFactory {

    private final ConcurrentMap<String, Logger> loggers = new ConcurrentHashMap<>();

    @Override
    public Logger getLogger(String name) {
        Logger existing = loggers.get(name);
        if (existing != null) {
            return existing;
        }
        Logger created = new LogcatLogger(name);
        Logger raced = loggers.putIfAbsent(name, created);
        return raced != null ? raced : created;
    }
}
