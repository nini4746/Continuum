package com.continuum.handler;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class HandlerRegistry {

    private final Map<String, StepHandler> handlers = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> sideEffectCounters = new ConcurrentHashMap<>();
    private final Map<String, Integer> flakyRemaining = new ConcurrentHashMap<>();

    public HandlerRegistry() {
        register("noop", inputs -> {});
        register("echo", inputs -> {
            Object msg = inputs.get("message");
            if (msg == null) throw new IllegalArgumentException("missing 'message'");
        });
        register("count", inputs -> {
            String key = String.valueOf(inputs.getOrDefault("key", "default"));
            sideEffectCounters.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        });
        register("fail", inputs -> {
            throw new RuntimeException(String.valueOf(inputs.getOrDefault("reason", "intentional failure")));
        });
        register("flaky", inputs -> {
            String key = String.valueOf(inputs.getOrDefault("key", "default"));
            int remaining = flakyRemaining.getOrDefault(key, 0);
            if (remaining > 0) {
                flakyRemaining.put(key, remaining - 1);
                throw new RuntimeException("flaky: " + remaining + " more failures remaining");
            }
        });
    }

    public void register(String type, StepHandler h) {
        handlers.put(type, h);
    }

    public StepHandler require(String type) {
        StepHandler h = handlers.get(type);
        if (h == null) throw new IllegalArgumentException("no handler for type: " + type);
        return h;
    }

    public int counter(String key) {
        AtomicInteger c = sideEffectCounters.get(key);
        return c == null ? 0 : c.get();
    }

    public void resetCounters() {
        sideEffectCounters.clear();
        flakyRemaining.clear();
    }

    public void primeFlaky(String key, int failures) {
        flakyRemaining.put(key, failures);
    }
}
