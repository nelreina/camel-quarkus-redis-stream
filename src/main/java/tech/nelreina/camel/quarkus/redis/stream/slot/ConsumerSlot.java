package tech.nelreina.camel.quarkus.redis.stream.slot;

import java.net.InetAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Application;
import io.quarkus.runtime.Quarkus;

/**
 * Stable, bounded consumer name {@code <serviceName>-<n>} per pod, backed by a fenced lease in Redis.
 *
 * <ul>
 *   <li>claim: for n = 1, 2, &hellip; {@code SET <keyPrefix>:<svc>:<n> <podName> NX PX ttlMs}; first success wins.</li>
 *   <li>renew every {@code renewMs} with a fenced Lua script (PEXPIRE only if the value is still this pod).</li>
 *   <li>lease lost: try {@code SET NX} on the same slot; if another pod holds it, notify listeners
 *       ({@link ConsumerSlotListener#beforeReclaim}), claim a new slot, notify again
 *       ({@link ConsumerSlotListener#afterReclaim}). If no slot can be claimed the fatal handler runs
 *       (default: {@code Quarkus.asyncExit(1)} inside a Quarkus application).</li>
 *   <li>release: fenced DEL, after the consumers have stopped.</li>
 *   <li>env {@code CONSUMER_NAME} wins over the lease (no Redis key is used then).</li>
 * </ul>
 *
 * <p>Uses the sync API of the component's shared connection; every command is non-blocking.
 */
public class ConsumerSlot implements ConsumerNameSupplier, AutoCloseable {

    public static final String DEFAULT_KEY_PREFIX = "ynohub:config:consumer-slot";
    public static final long DEFAULT_TTL_MS = 30_000;
    public static final long DEFAULT_RENEW_MS = 10_000;
    public static final int DEFAULT_MAX_SLOTS = 1000;

    static final String RENEW_SCRIPT =
        "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('PEXPIRE', KEYS[1], ARGV[2]) else return 0 end";
    static final String RELEASE_SCRIPT =
        "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end";

    private final RedisCommands<String, String> redis;
    private final String serviceName;
    private final String podName;
    private final String keyPrefix;
    private final long ttlMs;
    private final long renewMs;
    private final int maxSlots;
    private final String fixedName;
    private final List<ConsumerSlotListener> listeners = new CopyOnWriteArrayList<>();
    private volatile Consumer<ConsumerSlot> fatalHandler = ConsumerSlot::defaultFatal;

    private final Object lock = new Object();
    private volatile int n;
    private volatile String name;
    private volatile boolean held;
    private volatile boolean released;
    private volatile long lastRenewOkAt;
    private ScheduledExecutorService renewer;
    private ScheduledFuture<?> renewTask;

    private ConsumerSlot(Builder b) {
        this.redis = Objects.requireNonNull(b.redis, "redis commands");
        this.serviceName = requireText(b.serviceName, "serviceName");
        this.podName = b.podName != null && !b.podName.isBlank() ? b.podName : defaultPodName();
        this.keyPrefix = b.keyPrefix;
        this.ttlMs = b.ttlMs;
        this.renewMs = b.renewMs;
        this.maxSlots = b.maxSlots;
        this.fixedName = b.consumerNameOverride != null && !b.consumerNameOverride.isBlank()
            ? b.consumerNameOverride.trim() : null;
        if (b.fatalHandler != null) {
            this.fatalHandler = b.fatalHandler;
        }
    }

    public static Builder builder(RedisCommands<String, String> redis, String serviceName) {
        return new Builder(redis, serviceName);
    }

    /** Builds and claims a slot with the defaults (env CONSUMER_NAME / HOSTNAME honoured). */
    public static ConsumerSlot claim(RedisCommands<String, String> redis, String serviceName) {
        ConsumerSlot slot = builder(redis, serviceName).build();
        slot.claim();
        return slot;
    }

    /**
     * Claims the lowest free slot and starts the renew timer. Idempotent while the slot is held.
     *
     * @return the consumer name
     * @throws IllegalStateException when no slot in 1..maxSlots is free
     */
    public String claim() {
        synchronized (lock) {
            if (released) {
                throw new IllegalStateException("ConsumerSlot already released");
            }
            if (fixedName != null) {
                name = fixedName;
                held = true;
                Log.infof("Consumer name %s taken from CONSUMER_NAME (no slot lease)", fixedName);
                return name;
            }
            if (held) {
                return name;
            }
            claimLowestFree();
            startRenewer();
            return name;
        }
    }

    private void claimLowestFree() {
        for (int i = 1; i <= maxSlots; i++) {
            String res = redis.set(key(i), podName, SetArgs.Builder.nx().px(ttlMs));
            if ("OK".equals(res)) {
                n = i;
                name = serviceName + "-" + i;
                held = true;
                lastRenewOkAt = System.currentTimeMillis();
                Log.infof("Claimed consumer slot %s (key %s, pod %s)", name, key(i), podName);
                return;
            }
        }
        throw new IllegalStateException("No free consumer slot for " + serviceName + " in 1.." + maxSlots);
    }

    private void startRenewer() {
        if (renewer == null) {
            renewer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "redis-stream-consumer-slot-" + serviceName);
                t.setDaemon(true);
                return t;
            });
        }
        if (renewTask == null) {
            renewTask = renewer.scheduleWithFixedDelay(this::renewSafely, renewMs, renewMs, TimeUnit.MILLISECONDS);
        }
    }

    private void renewSafely() {
        try {
            renewOnce();
        } catch (Throwable t) {
            Log.errorf(t, "Consumer slot renew failed for %s", name);
        }
    }

    /**
     * One renew step (also used by tests). Returns true when the lease is held after the call.
     */
    public boolean renewOnce() {
        synchronized (lock) {
            if (released || fixedName != null) {
                return held;
            }
            Long r;
            try {
                r = redis.eval(RENEW_SCRIPT, ScriptOutputType.INTEGER, new String[] {key(n)}, podName, String.valueOf(ttlMs));
            } catch (Exception e) {
                // Redis unreachable: the key may still be ours; once the TTL has certainly passed treat it as lost
                // and re-check on the next tick (the SET NX below fails while Redis is away).
                Log.warnf("Consumer slot %s: renew error (%s)", name, e.getMessage());
                return held;
            }
            if (r != null && r == 1L) {
                lastRenewOkAt = System.currentTimeMillis();
                held = true;
                return true;
            }
        }
        handleLost();
        return held;
    }

    private void handleLost() {
        String lostName;
        synchronized (lock) {
            if (released) {
                return;
            }
            lostName = name;
            String res = redis.set(key(n), podName, SetArgs.Builder.nx().px(ttlMs));
            if ("OK".equals(res)) {
                lastRenewOkAt = System.currentTimeMillis();
                held = true;
                Log.warnf("Consumer slot %s lease had lapsed; re-acquired the same slot", lostName);
                return;
            }
            held = false;
        }
        Log.errorf("Consumer slot %s is now held by another pod; stopping consumers and claiming a new slot", lostName);
        for (ConsumerSlotListener l : listeners) {
            try {
                l.beforeReclaim(lostName);
            } catch (Throwable t) {
                Log.errorf(t, "ConsumerSlotListener.beforeReclaim failed");
            }
        }
        String newName = null;
        synchronized (lock) {
            try {
                claimLowestFree();
                newName = name;
            } catch (Exception e) {
                Log.errorf(e, "Could not claim a new consumer slot after losing %s", lostName);
            }
        }
        List<ConsumerSlotListener> rev = new java.util.ArrayList<>(listeners);
        java.util.Collections.reverse(rev);
        for (ConsumerSlotListener l : rev) {
            try {
                l.afterReclaim(lostName, newName);
            } catch (Throwable t) {
                Log.errorf(t, "ConsumerSlotListener.afterReclaim failed");
            }
        }
        if (newName == null) {
            fatalHandler.accept(this);
        }
    }

    /** Fenced DEL of the slot key and stop of the renew timer. Idempotent. */
    @Override
    public void release() {
        synchronized (lock) {
            if (released) {
                return;
            }
            released = true;
            if (renewTask != null) {
                renewTask.cancel(false);
                renewTask = null;
            }
            if (renewer != null) {
                renewer.shutdown();
                renewer = null;
            }
            if (fixedName == null && held) {
                try {
                    redis.eval(RELEASE_SCRIPT, ScriptOutputType.INTEGER, new String[] {key(n)}, podName);
                    Log.infof("Released consumer slot %s", name);
                } catch (Exception e) {
                    Log.warnf("Releasing consumer slot %s failed: %s (it expires after %d ms)", name, e.getMessage(), ttlMs);
                }
            }
            held = false;
        }
    }

    @Override
    public void close() {
        release();
    }

    @Override
    public String consumerName() {
        String current = name;
        if (current == null) {
            return claim();
        }
        return current;
    }

    public void addListener(ConsumerSlotListener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    public void removeListener(ConsumerSlotListener listener) {
        listeners.remove(listener);
    }

    public void setFatalHandler(Consumer<ConsumerSlot> fatalHandler) {
        this.fatalHandler = Objects.requireNonNull(fatalHandler);
    }

    public String getName() {
        return name;
    }

    public int getN() {
        return n;
    }

    public boolean isHeld() {
        return held;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getPodName() {
        return podName;
    }

    public String getKey() {
        return fixedName != null ? null : key(n);
    }

    public long getLastRenewOkAt() {
        return lastRenewOkAt;
    }

    String key(int slot) {
        return keyPrefix + ":" + serviceName + ":" + slot;
    }

    private static void defaultFatal(ConsumerSlot slot) {
        Log.errorf("No consumer slot could be claimed for %s; exiting so the orchestrator restarts the pod",
            slot.serviceName);
        if (Application.currentApplication() != null) {
            Quarkus.asyncExit(1);
        }
    }

    static String defaultPodName() {
        String h = System.getenv("HOSTNAME");
        if (h != null && !h.isBlank()) {
            return h.trim();
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "pod-" + ProcessHandle.current().pid();
        }
    }

    private static String requireText(String v, String what) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(what + " is required");
        }
        return v.trim();
    }

    public static final class Builder {
        private final RedisCommands<String, String> redis;
        private final String serviceName;
        private String podName;
        private String keyPrefix = DEFAULT_KEY_PREFIX;
        private long ttlMs = DEFAULT_TTL_MS;
        private long renewMs = DEFAULT_RENEW_MS;
        private int maxSlots = DEFAULT_MAX_SLOTS;
        private String consumerNameOverride = System.getenv("CONSUMER_NAME");
        private Consumer<ConsumerSlot> fatalHandler;

        private Builder(RedisCommands<String, String> redis, String serviceName) {
            this.redis = redis;
            this.serviceName = serviceName;
        }

        public Builder podName(String podName) {
            this.podName = podName;
            return this;
        }

        public Builder keyPrefix(String keyPrefix) {
            this.keyPrefix = requireText(keyPrefix, "keyPrefix");
            return this;
        }

        public Builder ttlMs(long ttlMs) {
            this.ttlMs = ttlMs;
            return this;
        }

        public Builder renewMs(long renewMs) {
            this.renewMs = renewMs;
            return this;
        }

        public Builder maxSlots(int maxSlots) {
            this.maxSlots = maxSlots;
            return this;
        }

        /** Overrides env CONSUMER_NAME (null/blank = use the lease). */
        public Builder consumerNameOverride(String consumerName) {
            this.consumerNameOverride = consumerName;
            return this;
        }

        public Builder fatalHandler(Consumer<ConsumerSlot> fatalHandler) {
            this.fatalHandler = fatalHandler;
            return this;
        }

        public ConsumerSlot build() {
            if (renewMs <= 0 || ttlMs <= renewMs) {
                throw new IllegalArgumentException("Require 0 < renewMs < ttlMs");
            }
            return new ConsumerSlot(this);
        }
    }
}
