package tech.nelreina.camel.quarkus.redis.stream.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.camel.CamelContext;
import org.apache.camel.Processor;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.lettuce.core.Consumer;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XReadArgs;
import tech.nelreina.camel.quarkus.redis.stream.RedisTestSupport;
import tech.nelreina.camel.quarkus.redis.stream.component.RedisStreamComponent;
import tech.nelreina.camel.quarkus.redis.stream.component.RedisStreamEndpoint;
import tech.nelreina.camel.quarkus.redis.stream.model.EventData;

class RedisStreamConsumerTest extends RedisTestSupport {

    private CamelContext camel;
    private RedisStreamComponent component;
    private final List<RedisStreamConsumer> started = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startCamel() throws Exception {
        camel = new DefaultCamelContext();
        component = new RedisStreamComponent();
        component.setRedisHosts(REDIS_URL);
        camel.addComponent("redis-stream", component);
        camel.start();
    }

    @AfterEach
    void stopCamel() throws Exception {
        for (RedisStreamConsumer c : started) {
            c.stop();
        }
        if (camel != null) {
            camel.stop();
        }
    }

    /** Consumer with the scheduler off: the test drives poll() itself. */
    private RedisStreamConsumer manualConsumer(String query, Processor processor) throws Exception {
        String stream = prefix + "s";
        RedisStreamEndpoint ep = (RedisStreamEndpoint) camel.getEndpoint(
            "redis-stream:" + stream + "?group=g&events=E&" + query);
        RedisStreamConsumer c = (RedisStreamConsumer) ep.createConsumer(processor);
        c.setStartScheduler(false);
        c.start();
        started.add(c);
        return c;
    }

    private String add(String event, String aggregateId) {
        return redis.xadd(prefix + "s", Map.of("event", event, "aggregateId", aggregateId, "payload", "{}",
            "serviceName", "test", "timestamp", String.valueOf(System.currentTimeMillis())));
    }

    private long pendingCount() {
        return redis.xpending(prefix + "s", "g").getCount();
    }

    @Test
    void createsGroupWithMkstreamAndNoDummyEntry() throws Exception {
        manualConsumer("consumerName=c1", ex -> { });
        assertEquals(1L, redis.exists(prefix + "s"));
        assertEquals(0L, redis.xlen(prefix + "s"), "no dummy XADD/XDEL left behind");
        List<Object> groups = redis.xinfoGroups(prefix + "s");
        assertEquals(1, groups.size());
        assertTrue(groups.get(0).toString().contains("g"));
    }

    @Test
    void groupStartIdIsTheActivationPoint() throws Exception {
        String first = add("E", "before");
        add("E", "after");
        List<String> seen = new CopyOnWriteArrayList<>();
        RedisStreamConsumer c = manualConsumer("consumerName=c1&groupStartId=" + first,
            ex -> seen.add(ex.getIn().getBody(EventData.class).getAggregateId()));
        c.poll();
        assertEquals(List.of("after"), seen);
    }

    @Test
    void defaultGroupStartIsDollar() throws Exception {
        add("E", "old");
        List<String> seen = new CopyOnWriteArrayList<>();
        RedisStreamConsumer c = manualConsumer("consumerName=c1",
            ex -> seen.add(ex.getIn().getBody(EventData.class).getAggregateId()));
        c.poll();
        assertTrue(seen.isEmpty());
        add("E", "new");
        c.poll();
        assertEquals(List.of("new"), seen);
    }

    @Test
    void maxMessagesIsAppliedAsCount() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        RedisStreamConsumer c = manualConsumer("consumerName=c1&groupStartId=0&maxMessages=2",
            ex -> seen.add(ex.getIn().getBody(EventData.class).getAggregateId()));
        for (int i = 0; i < 5; i++) {
            add("E", "a" + i);
        }
        assertEquals(2, c.poll());
        assertEquals(2, c.poll());
        assertEquals(1, c.poll());
        assertEquals(List.of("a0", "a1", "a2", "a3", "a4"), seen);
        assertEquals(0, pendingCount());
    }

    @Test
    void failingEntryIsRetriedByAutoClaimThenParked() throws Exception {
        RedisStreamConsumer c = manualConsumer(
            "consumerName=c1&groupStartId=0&drainMaxDeliveries=2&autoClaimMinIdleMs=0&parkedStream=" + prefix + "parked",
            ex -> {
                throw new IllegalStateException("boom");
            });
        String id = add("E", "poison");
        c.poll(); // delivery 1 fails -> stays pending
        assertEquals(1, pendingCount());
        assertEquals(0L, redis.xlen(prefix + "parked"));

        c.autoClaimPass(); // delivery 2 fails on the last allowed delivery -> parked
        assertEquals(0, pendingCount());
        List<StreamMessage<String, String>> parked = redis.xrange(prefix + "parked", Range.create("-", "+"));
        assertEquals(1, parked.size());
        Map<String, String> f = parked.get(0).getBody();
        assertEquals(id, f.get("entryId"));
        assertEquals(prefix + "s", f.get("streamKey"));
        assertEquals("g", f.get("group"));
        assertEquals("c1", f.get("consumer"));
        assertEquals("2", f.get("deliveries"));
        assertEquals("poison", f.get("aggregateId"));
        assertTrue(f.get("reason").contains("boom"));
        assertEquals(1, c.getParkedCount());
    }

    @Test
    void drainsOwnPelWithMovingCursorAndParksOverDelivered() throws Exception {
        String stream = prefix + "s";
        // group + three entries delivered to c1 but never acked (e.g. a previous run that crashed)
        redis.xgroupCreate(XReadArgs.StreamOffset.from(stream, "$"), "g",
            io.lettuce.core.XGroupCreateArgs.Builder.mkstream());
        String a = add("E", "a");
        add("E", "b");
        add("E", "c");
        redis.xreadgroup(Consumer.from("g", "c1"), XReadArgs.Builder.count(10), XReadArgs.StreamOffset.lastConsumed(stream));
        // push the delivery count of "a" to 7 (> max 5) by re-reading the history
        for (int i = 0; i < 6; i++) {
            redis.xreadgroup(Consumer.from("g", "c1"), XReadArgs.Builder.count(1), XReadArgs.StreamOffset.from(stream, "0"));
        }
        assertEquals(7L, redis.xpending(stream, Consumer.from("g", "c1"), Range.create(a, a), Limit.from(1))
            .get(0).getRedeliveryCount());

        List<String> seen = new CopyOnWriteArrayList<>();
        RedisStreamConsumer c = manualConsumer("consumerName=c1&parkedStream=" + prefix + "parked",
            ex -> seen.add(ex.getIn().getBody(EventData.class).getAggregateId()));
        c.poll(); // first poll: drain
        assertEquals(List.of("b", "c"), seen);
        assertEquals(0, pendingCount());
        assertEquals(1L, redis.xlen(prefix + "parked"));
    }

    @Test
    void pendingEntryThatWasDeletedIsAcked() throws Exception {
        String stream = prefix + "s";
        redis.xgroupCreate(XReadArgs.StreamOffset.from(stream, "$"), "g",
            io.lettuce.core.XGroupCreateArgs.Builder.mkstream());
        String a = add("E", "a");
        redis.xreadgroup(Consumer.from("g", "c1"), XReadArgs.Builder.count(10), XReadArgs.StreamOffset.lastConsumed(stream));
        redis.xdel(stream, a);
        RedisStreamConsumer c = manualConsumer("consumerName=c1", ex -> { });
        c.poll();
        assertEquals(0, pendingCount());
    }

    @Test
    void autoClaimAdoptsEntriesOfADeadConsumerAndDeletesIdleNames() throws Exception {
        String stream = prefix + "s";
        redis.xgroupCreate(XReadArgs.StreamOffset.from(stream, "$"), "g",
            io.lettuce.core.XGroupCreateArgs.Builder.mkstream());
        add("E", "orphan");
        redis.xreadgroup(Consumer.from("g", "dead-pod"), XReadArgs.Builder.count(10), XReadArgs.StreamOffset.lastConsumed(stream));
        Thread.sleep(30);
        List<String> seen = new CopyOnWriteArrayList<>();
        RedisStreamConsumer c = manualConsumer("consumerName=c1&autoClaimMinIdleMs=10&deleteIdleConsumersMs=10",
            ex -> seen.add(ex.getIn().getBody(EventData.class).getAggregateId()));
        Thread.sleep(30);
        c.poll(); // drain (nothing own) + XAUTOCLAIM
        assertEquals(List.of("orphan"), seen);
        assertEquals(0, pendingCount());
        Thread.sleep(30);
        c.autoClaimPass();
        String consumers = redis.xinfoConsumers(stream, "g").toString();
        assertFalse(consumers.contains("dead-pod"), consumers);
    }

    @Test
    void stopWaitsForTheInFlightMessageAndAcksIt() throws Exception {
        String stream = prefix + "s";
        CountDownLatch inHandler = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        AtomicBoolean interrupted = new AtomicBoolean();
        RedisStreamEndpoint ep = (RedisStreamEndpoint) camel.getEndpoint(
            "redis-stream:" + stream + "?group=g&events=E&consumerName=c1&pollingInterval=20");
        RedisStreamConsumer c = (RedisStreamConsumer) ep.createConsumer(ex -> {
            inHandler.countDown();
            try {
                Thread.sleep(700);
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                return;
            }
            finished.set(true);
        });
        c.start();
        add("E", "slow");
        assertTrue(inHandler.await(5, TimeUnit.SECONDS));
        c.stop();
        assertTrue(finished.get(), "handler ran to completion");
        assertFalse(interrupted.get(), "handler was not interrupted");
        assertEquals(0, pendingCount(), "processed entry was acked before stop returned");
    }

    @Test
    void stopLeavesUnprocessedBatchEntriesPendingForTheNextStart() throws Exception {
        String stream = prefix + "s";
        CountDownLatch inHandler = new CountDownLatch(1);
        List<String> seen = new CopyOnWriteArrayList<>();
        RedisStreamEndpoint ep = (RedisStreamEndpoint) camel.getEndpoint(
            "redis-stream:" + stream + "?group=g&events=E&consumerName=c1&groupStartId=0&maxMessages=10&pollingInterval=20");
        add("E", "m1");
        add("E", "m2");
        add("E", "m3");
        RedisStreamConsumer c = (RedisStreamConsumer) ep.createConsumer(ex -> {
            seen.add(ex.getIn().getBody(EventData.class).getAggregateId());
            inHandler.countDown();
            Thread.sleep(300);
        });
        c.start();
        assertTrue(inHandler.await(5, TimeUnit.SECONDS));
        c.stop();
        assertEquals(List.of("m1"), seen);
        assertEquals(2, pendingCount());

        // restart under the same name: the drain picks up m2, m3
        c.start();
        await(() -> seen.size() == 3, 5000, "drain after restart");
        c.stop();
        assertEquals(List.of("m1", "m2", "m3"), seen);
        assertEquals(0, pendingCount());
        assertTrue(Duration.ofMillis(1).toMillis() > 0);
    }
}
