package tech.nelreina.camel.quarkus.redis.stream.tenant;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.UnblockType;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.quarkus.logging.Log;

/**
 * Tails the config control stream ({@code ynohub:config:events}) on its OWN connection with
 * {@code XREAD BLOCK <blockMs> STREAMS <key> <lastId>} (fan-out: every pod reads every entry, no group), plus a
 * backstop timer (default 60 s) and a heartbeat timer (default 30 s).
 *
 * <p>Entries are triggers only; the listener re-reads the snapshot. {@link #captureLastId()} should be called
 * before the first reconcile so nothing committed after it is missed.
 */
public class ConfigEventsTailer implements AutoCloseable {

    /** Callbacks; all run on the tailer's threads and must not throw. */
    public interface Listener {
        void onEvent(StreamMessage<String, String> entry);

        default void onBackstop() {
        }

        default void onHeartbeat() {
        }

        /** The tail loop recovered from an error (entries may have been trimmed in between). */
        default void onResume(String reason) {
        }
    }

    private final RedisClient client;
    private final String streamKey;
    private final Listener listener;
    private long blockMs = 5000;
    private long backstopSec = 60;
    private long heartbeatSec = 30;
    private int count = 100;
    private RedisCommands<String, String> control;

    private volatile StatefulRedisConnection<String, String> connection;
    private volatile Long clientId;
    private volatile String lastId;
    private volatile boolean running;
    private Thread thread;
    private ScheduledExecutorService timers;

    public ConfigEventsTailer(RedisClient client, String streamKey, Listener listener) {
        this.client = Objects.requireNonNull(client, "client");
        this.streamKey = Objects.requireNonNull(streamKey, "streamKey");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public ConfigEventsTailer blockMs(long blockMs) {
        this.blockMs = blockMs;
        return this;
    }

    /** 0 disables the backstop timer. */
    public ConfigEventsTailer backstopSec(long backstopSec) {
        this.backstopSec = backstopSec;
        return this;
    }

    /** 0 disables the heartbeat timer. */
    public ConfigEventsTailer heartbeatSec(long heartbeatSec) {
        this.heartbeatSec = heartbeatSec;
        return this;
    }

    public ConfigEventsTailer count(int count) {
        this.count = count;
        return this;
    }

    /** A non-blocking connection used to send CLIENT UNBLOCK on stop (otherwise stop waits up to blockMs). */
    public ConfigEventsTailer unblockWith(RedisCommands<String, String> control) {
        this.control = control;
        return this;
    }

    /** {@code XREVRANGE key + - COUNT 1}: the id to tail from ("0-0" when the stream is empty or missing). */
    public synchronized String captureLastId() {
        List<StreamMessage<String, String>> last = conn().sync().xrevrange(streamKey, Range.create("-", "+"), Limit.from(1));
        lastId = last == null || last.isEmpty() ? "0-0" : last.get(0).getId();
        return lastId;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        if (lastId == null) {
            captureLastId();
        }
        clientId = conn().sync().clientId();
        running = true;
        thread = new Thread(this::loop, "redis-stream-config-events");
        thread.setDaemon(true);
        thread.start();
        timers = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "redis-stream-config-timers");
            t.setDaemon(true);
            return t;
        });
        if (backstopSec > 0) {
            timers.scheduleWithFixedDelay(() -> safely(listener::onBackstop, "backstop"), backstopSec, backstopSec,
                TimeUnit.SECONDS);
        }
        if (heartbeatSec > 0) {
            timers.scheduleWithFixedDelay(() -> safely(listener::onHeartbeat, "heartbeat"), heartbeatSec, heartbeatSec,
                TimeUnit.SECONDS);
        }
        Log.infof("Tailing %s from %s (block %d ms, backstop %d s, heartbeat %d s)", streamKey, lastId, blockMs,
            backstopSec, heartbeatSec);
    }

    private void loop() {
        long backoff = 1000;
        boolean hadError = false;
        while (running) {
            try {
                List<StreamMessage<String, String>> entries = connection.sync().xread(
                    XReadArgs.Builder.block(blockMs).count(count), XReadArgs.StreamOffset.from(streamKey, lastId));
                if (hadError) {
                    hadError = false;
                    backoff = 1000;
                    final String reason = "resume";
                    safely(() -> listener.onResume(reason), "resume");
                }
                if (entries == null) {
                    continue;
                }
                for (StreamMessage<String, String> e : entries) {
                    lastId = e.getId();
                    safely(() -> listener.onEvent(e), "event");
                }
            } catch (Exception e) {
                if (!running) {
                    break;
                }
                hadError = true;
                Log.warnf("XREAD on %s failed: %s (retry in %d ms)", streamKey, e.getMessage(), backoff);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoff = Math.min(30_000, backoff * 2);
            }
        }
    }

    /** Stops the loop (CLIENT UNBLOCK when a control connection is set), waits for it, closes the connection. */
    public synchronized void stop() {
        if (!running && thread == null) {
            closeConnection();
            return;
        }
        running = false;
        if (timers != null) {
            timers.shutdownNow();
            timers = null;
        }
        if (thread != null) {
            long deadline = System.currentTimeMillis() + blockMs + 2000;
            try {
                // repeat the UNBLOCK: a stop right after start can race the loop into its XREAD BLOCK
                do {
                    unblock();
                    thread.join(control == null ? Math.max(1, deadline - System.currentTimeMillis()) : 100);
                } while (thread.isAlive() && System.currentTimeMillis() < deadline);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        closeConnection();
    }

    private void unblock() {
        if (control != null && clientId != null) {
            try {
                control.clientUnblock(clientId, UnblockType.TIMEOUT);
            } catch (Exception e) {
                Log.debugf("CLIENT UNBLOCK failed: %s", e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        stop();
    }

    public String getLastId() {
        return lastId;
    }

    public boolean isRunning() {
        return running;
    }

    public String getStreamKey() {
        return streamKey;
    }

    private StatefulRedisConnection<String, String> conn() {
        if (connection == null) {
            connection = client.connect();
        }
        return connection;
    }

    private void closeConnection() {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                Log.debugf("Closing tailer connection failed: %s", e.getMessage());
            }
            connection = null;
        }
    }

    private static void safely(Runnable r, String what) {
        try {
            r.run();
        } catch (Throwable t) {
            Log.errorf(t, "ConfigEventsTailer %s callback failed", what);
        }
    }
}
