package tech.nelreina.camel.quarkus.redis.stream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Integration tests run against a real Redis: {@code -Dredis.test.url=redis://host:port} (default
 * redis://localhost:6379). They are skipped when Redis is unreachable. Every key lives under a random
 * {@code test:<id>:} prefix and is deleted afterwards (SCAN + DEL, never FLUSHALL).
 */
public abstract class RedisTestSupport {

    public static final String REDIS_URL = System.getProperty("redis.test.url", "redis://localhost:6379");

    protected RedisClient client;
    protected StatefulRedisConnection<String, String> connection;
    protected RedisCommands<String, String> redis;
    protected String prefix;

    @BeforeEach
    void connectRedis() {
        try {
            RedisURI uri = RedisURI.create(REDIS_URL);
            uri.setTimeout(Duration.ofSeconds(10));
            client = RedisClient.create(uri);
            connection = client.connect();
            redis = connection.sync();
            redis.ping();
        } catch (Exception e) {
            if (client != null) {
                client.shutdown();
            }
            assumeTrue(false, "Redis not reachable at " + REDIS_URL + ": " + e.getMessage());
        }
        prefix = "test:" + UUID.randomUUID().toString().substring(0, 8) + ":";
    }

    @AfterEach
    void cleanupRedis() {
        if (redis == null) {
            return;
        }
        try {
            ScanCursor cursor = ScanCursor.INITIAL;
            do {
                KeyScanCursor<String> page = redis.scan(cursor, ScanArgs.Builder.matches(prefix + "*").limit(500));
                if (!page.getKeys().isEmpty()) {
                    redis.del(page.getKeys().toArray(new String[0]));
                }
                cursor = page;
            } while (!cursor.isFinished());
        } finally {
            connection.close();
            client.shutdown();
        }
    }

    protected static void await(BooleanSupplier condition, long timeoutMs, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }
}
