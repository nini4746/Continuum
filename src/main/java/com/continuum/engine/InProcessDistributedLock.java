package com.continuum.engine;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Single-JVM implementation of {@link DistributedLock}. Acceptable for single-node
 * deployments and tests. Replace with a Redis/etcd backed bean to share locks
 * across nodes.
 *
 * The lock map only grows by distinct key count and is bounded by {@code MAX_LOCKS};
 * once exceeded, idle entries are evicted opportunistically when their lock is
 * not held.
 */
@Component
public class InProcessDistributedLock implements DistributedLock {

    static final int MAX_LOCKS = 100_000;

    private final ConcurrentHashMap<String, Entry> locks = new ConcurrentHashMap<>();

    @Override
    public Token tryLock(String key, long timeout, TimeUnit unit) {
        Entry entry = locks.computeIfAbsent(key, k -> new Entry());
        boolean acquired;
        try {
            acquired = entry.lock.tryLock(timeout, unit);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (!acquired) return null;
        Token t = new Token(UUID.randomUUID().toString());
        entry.token = t;
        if (locks.size() > MAX_LOCKS) {
            locks.entrySet().removeIf(e -> !e.getValue().lock.isLocked() && e.getKey().compareTo(key) != 0);
        }
        return t;
    }

    @Override
    public void unlock(String key, Token token) {
        Entry entry = locks.get(key);
        if (entry == null || token == null) return;
        if (entry.token != null && entry.token.equals(token) && entry.lock.isHeldByCurrentThread()) {
            entry.token = null;
            entry.lock.unlock();
        }
    }

    int trackedKeys() {
        return locks.size();
    }

    private static final class Entry {
        final ReentrantLock lock = new ReentrantLock();
        volatile Token token;
    }
}
