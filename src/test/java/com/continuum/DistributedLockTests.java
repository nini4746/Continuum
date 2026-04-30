package com.continuum;

import com.continuum.engine.DistributedLock;
import com.continuum.engine.InProcessDistributedLock;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DistributedLockTests {

    @Test
    void singleHolderAcquiresAndReleases() {
        DistributedLock lock = new InProcessDistributedLock();
        DistributedLock.Token t = lock.tryLock("k1", 100, TimeUnit.MILLISECONDS);
        assertNotNull(t);
        lock.unlock("k1", t);
    }

    @Test
    void contentionReturnsNullForLoser() throws Exception {
        DistributedLock lock = new InProcessDistributedLock();
        DistributedLock.Token a = lock.tryLock("k2", 0, TimeUnit.MILLISECONDS);
        assertNotNull(a);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        var f = pool.submit(() -> lock.tryLock("k2", 50, TimeUnit.MILLISECONDS));
        DistributedLock.Token b = f.get();
        assertNull(b);
        lock.unlock("k2", a);
        pool.shutdown();
    }

    @Test
    void unlockWithWrongTokenIsNoop() {
        DistributedLock lock = new InProcessDistributedLock();
        DistributedLock.Token t = lock.tryLock("k3", 100, TimeUnit.MILLISECONDS);
        assertNotNull(t);
        lock.unlock("k3", new DistributedLock.Token("other"));
        // Original holder should still be able to unlock cleanly
        lock.unlock("k3", t);
    }

    @Test
    void differentKeysDoNotContend() {
        DistributedLock lock = new InProcessDistributedLock();
        DistributedLock.Token a = lock.tryLock("kx", 0, TimeUnit.MILLISECONDS);
        DistributedLock.Token b = lock.tryLock("ky", 0, TimeUnit.MILLISECONDS);
        assertNotNull(a);
        assertNotNull(b);
        lock.unlock("kx", a);
        lock.unlock("ky", b);
    }

    @Test
    void mutualExclusionUnderContention() throws Exception {
        DistributedLock lock = new InProcessDistributedLock();
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger violations = new AtomicInteger();
        int threads = 8;
        int iters = 300;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try { start.await(); } catch (InterruptedException ie) { return; }
                for (int j = 0; j < iters; j++) {
                    DistributedLock.Token t = lock.tryLock("hot", 1000, TimeUnit.MILLISECONDS);
                    if (t == null) continue;
                    try {
                        if (inside.incrementAndGet() != 1) violations.incrementAndGet();
                        inside.decrementAndGet();
                    } finally {
                        lock.unlock("hot", t);
                    }
                }
                done.countDown();
            });
        }
        start.countDown();
        done.await(15, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertEquals(0, violations.get(), "lock must serialize all critical sections");
    }
}
