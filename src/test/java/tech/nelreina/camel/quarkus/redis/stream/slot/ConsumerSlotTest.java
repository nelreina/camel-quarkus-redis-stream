package tech.nelreina.camel.quarkus.redis.stream.slot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.camel.CamelContext;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;

import tech.nelreina.camel.quarkus.redis.stream.RedisTestSupport;
import tech.nelreina.camel.quarkus.redis.stream.component.RedisStreamComponent;
import tech.nelreina.camel.quarkus.redis.stream.component.RedisStreamEndpoint;
import tech.nelreina.camel.quarkus.redis.stream.consumer.RedisStreamConsumer;

class ConsumerSlotTest extends RedisTestSupport {

    private ConsumerSlot slot(String pod) {
        return ConsumerSlot.builder(redis, "svc").keyPrefix(prefix + "slot").podName(pod)
            .consumerNameOverride(null).ttlMs(30000).renewMs(10000).build();
    }

    @Test
    void claimsLowestFreeSlotAndReleasesIt() {
        ConsumerSlot a = slot("pod-a");
        ConsumerSlot b = slot("pod-b");
        assertEquals("svc-1", a.claim());
        assertEquals("svc-2", b.claim());
        assertEquals("pod-a", redis.get(prefix + "slot:svc:1"));
        assertTrue(redis.pttl(prefix + "slot:svc:1") > 0);

        a.release();
        assertNull(redis.get(prefix + "slot:svc:1"));
        ConsumerSlot c = slot("pod-c");
        assertEquals("svc-1", c.claim(), "freed slot is reused");
        b.release();
        c.release();
    }

    @Test
    void renewIsFencedAndReleaseNeverDeletesAnotherPodsKey() {
        ConsumerSlot a = slot("pod-a");
        a.claim();
        assertTrue(a.renewOnce());
        // another pod took the slot (e.g. after a lapse)
        redis.set(prefix + "slot:svc:1", "pod-b");
        List<String> events = new CopyOnWriteArrayList<>();
        a.addListener(new ConsumerSlotListener() {
            @Override
            public void beforeReclaim(String lostName) {
                events.add("before:" + lostName);
            }

            @Override
            public void afterReclaim(String lostName, String newName) {
                events.add("after:" + lostName + "->" + newName);
            }
        });
        a.renewOnce();
        assertEquals(List.of("before:svc-1", "after:svc-1->svc-2"), events);
        assertEquals("svc-2", a.getName());
        assertEquals("pod-b", redis.get(prefix + "slot:svc:1"), "renew did not touch pod-b's key");
        a.release();
        assertEquals("pod-b", redis.get(prefix + "slot:svc:1"), "release did not delete pod-b's key");
        assertNull(redis.get(prefix + "slot:svc:2"));
    }

    @Test
    void lapsedLeaseIsReacquiredWithoutNotifying() {
        ConsumerSlot a = slot("pod-a");
        a.claim();
        AtomicBoolean notified = new AtomicBoolean();
        a.addListener(new ConsumerSlotListener() {
            @Override
            public void beforeReclaim(String lostName) {
                notified.set(true);
            }
        });
        redis.del(prefix + "slot:svc:1"); // TTL ran out while Redis was away
        assertTrue(a.renewOnce());
        assertFalse(notified.get());
        assertEquals("svc-1", a.getName());
        assertEquals("pod-a", redis.get(prefix + "slot:svc:1"));
        a.release();
    }

    @Test
    void noFreeSlotCallsFatalHandler() {
        AtomicBoolean fatal = new AtomicBoolean();
        ConsumerSlot a = ConsumerSlot.builder(redis, "svc").keyPrefix(prefix + "slot").podName("pod-a")
            .consumerNameOverride(null).maxSlots(1).fatalHandler(s -> fatal.set(true)).build();
        a.claim();
        redis.set(prefix + "slot:svc:1", "pod-b");
        assertFalse(a.renewOnce());
        assertTrue(fatal.get());
        assertFalse(a.isHeld());
    }

    @Test
    void consumerNameEnvOverrideSkipsTheLease() {
        ConsumerSlot a = ConsumerSlot.builder(redis, "svc").keyPrefix(prefix + "slot").podName("pod-a")
            .consumerNameOverride("svc-7").build();
        assertEquals("svc-7", a.claim());
        assertEquals(0L, redis.exists(prefix + "slot:svc:1"));
        a.release();
    }

    @Test
    void componentUsesRegistrySupplierAndRestartsConsumersOnSlotChange() throws Exception {
        ConsumerSlot s = slot("pod-a");
        s.claim();
        CamelContext camel = new DefaultCamelContext();
        camel.getRegistry().bind("consumerSlot", ConsumerNameSupplier.class, s);
        RedisStreamComponent component = new RedisStreamComponent();
        component.setRedisHosts(REDIS_URL);
        camel.addComponent("redis-stream", component);
        camel.start();
        try {
            RedisStreamEndpoint ep = (RedisStreamEndpoint) camel.getEndpoint(
                "redis-stream:" + prefix + "s?group=g&events=E&pollingInterval=20");
            RedisStreamConsumer c = (RedisStreamConsumer) ep.createConsumer(ex -> { });
            c.start();
            assertEquals("svc-1", c.getConsumerName());

            redis.set(prefix + "slot:svc:1", "pod-b");
            s.renewOnce();
            assertEquals("svc-2", c.getConsumerName(), "consumer restarted under the new slot name");
            assertTrue(c.isStarted());

            // a URI consumerName still wins over the supplier
            RedisStreamEndpoint ep2 = (RedisStreamEndpoint) camel.getEndpoint(
                "redis-stream:" + prefix + "s2?group=g&events=E&consumerName=fixed");
            RedisStreamConsumer c2 = (RedisStreamConsumer) ep2.createConsumer(ex -> { });
            c2.start();
            assertEquals("fixed", c2.getConsumerName());
            c2.stop();
            c.stop();
        } finally {
            camel.stop(); // component stop releases the slot
        }
        assertNull(redis.get(prefix + "slot:svc:2"));
        assertEquals("pod-b", redis.get(prefix + "slot:svc:1"));
        assertTrue(Map.of().isEmpty());
    }
}
