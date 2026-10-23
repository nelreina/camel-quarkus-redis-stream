package tech.nelreina.camel.quarkus.redis.stream.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.camel.CamelContext;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.lettuce.core.StreamMessage;
import io.lettuce.core.XReadArgs;
import tech.nelreina.camel.quarkus.redis.stream.RedisTestSupport;
import tech.nelreina.camel.quarkus.redis.stream.component.RedisStreamComponent;
import tech.nelreina.camel.quarkus.redis.stream.model.EventData;
import tech.nelreina.camel.quarkus.redis.stream.slot.ConsumerNameSupplier;
import tech.nelreina.camel.quarkus.redis.stream.slot.ConsumerSlot;

class TenantRouteManagerTest extends RedisTestSupport {

    private CamelContext camel;
    private RedisStreamComponent component;
    private TenantRouteManager manager;
    private ConsumerSlot slot;
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final Map<String, String> fingerprints = new ConcurrentHashMap<>();
    private final Set<String> failingDb = ConcurrentHashMap.newKeySet();
    private final List<String> removedHooks = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        slot = ConsumerSlot.builder(redis, "svc").keyPrefix(prefix + "consumer-slot").podName("pod-a")
            .consumerNameOverride(null).build();
        slot.claim();
        camel = new DefaultCamelContext();
        camel.getRegistry().bind("slot", ConsumerNameSupplier.class, slot);
        component = new RedisStreamComponent();
        component.setRedisHosts(REDIS_URL);
        camel.addComponent("redis-stream", component);
        camel.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (manager != null) {
            manager.stop();
        }
        if (camel != null) {
            camel.stop();
        }
    }

    private String stream(String bic) {
        return prefix + "events:" + bic;
    }

    private TenantRouteManager build(long reconcileSec) {
        TenantRouteSpec spec = new TenantRouteSpec(bic -> new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:" + bic + "-work").routeId(bic + "-work")
                    .process(ex -> received.add(bic + ":" + ex.getIn().getBody(EventData.class).getAggregateId()));
                from("redis-stream:" + stream(bic) + "?group=svc&events=E&pollingInterval=20")
                    .routeId(bic + "-consume")
                    .to("direct:" + bic + "-work");
            }
        }, bic -> fingerprints.getOrDefault(bic, ""))
            .stream(this::stream, "svc")
            .onTenantAdded(bic -> {
                if (failingDb.contains(bic)) {
                    throw new IllegalStateException("3D000 database \"" + bic + "\" does not exist");
                }
            })
            .onTenantRemoved(removedHooks::add);
        return TenantRouteManager.builder(camel, spec)
            .component(component)
            .serviceName("svc")
            .activeKey(prefix + "host-banks:active")
            .generationKey(prefix + "cache:generation")
            .statusKey(prefix + "cache:status")
            .eventsStream(prefix + "config-events")
            .activatedKeyPrefix(prefix + "tenant-activated")
            .appliedKeyPrefix(prefix + "applied")
            .reconcileSec(reconcileSec)
            .heartbeatSec(30)
            .blockMs(5000)
            .build();
    }

    private void xaddEvent(String event) {
        redis.xadd(prefix + "config-events", Map.of("event", event, "aggregateId", redis.get(prefix + "cache:generation"),
            "payload", "{}", "serviceName", "config-service", "timestamp", String.valueOf(System.currentTimeMillis()),
            "headers", "{}"));
    }

    private void xaddTenant(String bic, String aggregateId) {
        redis.xadd(stream(bic), Map.of("event", "E", "aggregateId", aggregateId, "payload", "{}"));
    }

    @Test
    void bootReconcileAddsTenantsAtTheirActivationIdAndHeartbeats() throws Exception {
        // traffic before activation must not be replayed; activation id pins the start
        xaddTenant("aaa", "pre-activation");
        String activation = redis.xadd(stream("aaa"), Map.of("event", "E", "aggregateId", "at-activation", "payload", "{}"));
        redis.set(prefix + "tenant-activated:aaa", activation);
        xaddTenant("aaa", "post-activation");
        redis.set(prefix + "host-banks:active", "aaa,bbb");
        redis.set(prefix + "cache:generation", "1");

        manager = build(3600);
        manager.start();

        assertEquals(List.of("aaa", "bbb"), manager.tenants());
        assertNotNull(camel.getRoute("aaa-consume"));
        assertNotNull(camel.getRoute("bbb-work"));
        assertEquals(Set.of(stream("aaa"), stream("bbb")), manager.liveStreams());
        await(() -> received.contains("aaa:post-activation"), 5000, "post-activation entry consumed");
        assertFalse(received.contains("aaa:pre-activation"));
        assertFalse(received.contains("aaa:at-activation"));

        xaddTenant("bbb", "b1");
        await(() -> received.contains("bbb:b1"), 5000, "bbb consumed");

        Map<String, String> hb = redis.hgetall(prefix + "applied:svc:svc-1");
        assertEquals("1", hb.get("generation"));
        assertEquals("aaa,bbb", hb.get("tenants"));
        assertEquals("{}", hb.get("degraded"));
        assertEquals("all", hb.get("hotReload"));
        assertEquals("svc-1", hb.get("consumerName"));
        assertNotNull(hb.get("ts"));
        long ttl = redis.ttl(prefix + "applied:svc:svc-1");
        assertTrue(ttl > 0 && ttl <= 120, "ttl " + ttl);
        assertTrue(redis.xinfoConsumers(stream("aaa"), "svc").toString().contains("svc-1"));
    }

    @Test
    void controlEventRemovesAndAddsTenantsHot() throws Exception {
        redis.set(prefix + "host-banks:active", "aaa,bbb");
        redis.set(prefix + "cache:generation", "1");
        manager = build(3600);
        manager.start();

        redis.set(prefix + "host-banks:active", "aaa,ccc");
        redis.incr(prefix + "cache:generation");
        xaddEvent(TenantRouteManager.EVENT_REFRESHED);

        await(() -> manager.tenants().equals(List.of("aaa", "ccc")), 5000, "event-driven reconcile");
        assertNull(camel.getRoute("bbb-consume"));
        assertNull(camel.getRoute("bbb-work"));
        assertTrue(removedHooks.contains("bbb"));
        assertEquals(2L, manager.getLastSeenGeneration());
        // group of the removed tenant is kept (re-activation resumes where it left off)
        assertEquals(1, redis.xinfoGroups(stream("bbb")).size());

        xaddTenant("ccc", "c1");
        await(() -> received.contains("ccc:c1"), 5000, "new tenant consumed");
    }

    @Test
    void emptyActiveListNeverEvicts() {
        redis.set(prefix + "host-banks:active", "aaa");
        redis.set(prefix + "cache:generation", "1");
        manager = build(3600);
        manager.start();
        redis.del(prefix + "host-banks:active");
        TenantRouteManager.ReconcileResult r = manager.reconcile("event");
        assertEquals("empty active list", r.note);
        assertEquals(List.of("aaa"), manager.tenants());
        redis.set(prefix + "host-banks:active", " ");
        manager.reconcile("event");
        assertEquals(List.of("aaa"), manager.tenants());
    }

    @Test
    void degradedTenantPinsItsGroupAndRetriesWithBackoff() throws Exception {
        redis.set(prefix + "host-banks:active", "aaa,ddd");
        redis.set(prefix + "cache:generation", "1");
        failingDb.add("ddd");
        manager = build(3600);
        manager.start();

        assertEquals(List.of("aaa"), manager.tenants());
        assertTrue(manager.degradedReasons().get("ddd").contains("3D000"));
        assertEquals(1, redis.xinfoGroups(stream("ddd")).size(), "group created although degraded");
        assertNull(camel.getRoute("ddd-consume"));
        assertTrue(redis.hget(prefix + "applied:svc:svc-1", "degraded").contains("ddd"));

        // before nextRetryAt nothing happens even on a forced reconcile
        failingDb.remove("ddd");
        manager.reconcile("event");
        assertFalse(manager.tenants().contains("ddd"));
        Thread.sleep(1100); // first backoff step is 1 s
        manager.reconcile("timer"); // timer with unchanged generation still retries a due tenant
        assertEquals(List.of("aaa", "ddd"), manager.tenants());
        assertTrue(manager.degradedReasons().isEmpty());
    }

    @Test
    void fingerprintChangeRebuildsTheTenantsRoutes() throws Exception {
        redis.set(prefix + "host-banks:active", "aaa");
        redis.set(prefix + "cache:generation", "1");
        fingerprints.put("aaa", "v1");
        manager = build(3600);
        manager.start();
        Object before = camel.getRoute("aaa-consume");

        TenantRouteManager.ReconcileResult same = manager.reconcile("manual");
        assertTrue(same.rebuilt.isEmpty());

        fingerprints.put("aaa", "v2");
        TenantRouteManager.ReconcileResult r = manager.reconcile("timer");
        assertEquals(List.of("aaa"), r.rebuilt);
        assertNotNull(camel.getRoute("aaa-consume"));
        assertTrue(before != camel.getRoute("aaa-consume"), "route instance replaced");
        assertFalse(removedHooks.contains("aaa"), "rebuild does not run the tenant removal hook");
    }

    @Test
    void generationResetTriggersFullReconcileAndStopAllCleansUp() throws Exception {
        redis.set(prefix + "host-banks:active", "aaa");
        redis.set(prefix + "cache:generation", "5");
        manager = build(3600);
        manager.start();
        assertEquals(5L, manager.getLastSeenGeneration());
        redis.set(prefix + "cache:generation", "1"); // Redis restored / flushed
        redis.set(prefix + "host-banks:active", "aaa,bbb");
        manager.reconcile("timer");
        assertEquals(1L, manager.getLastSeenGeneration());
        assertEquals(List.of("aaa", "bbb"), manager.tenants());

        manager.stop();
        assertTrue(manager.tenants().isEmpty());
        assertNull(camel.getRoute("aaa-consume"));
        assertEquals(0L, redis.exists(prefix + "applied:svc:svc-1"));
        manager = null;
    }

    @Test
    void tailerStopsPromptlyWithClientUnblock() throws Exception {
        ConfigEventsTailer tailer = new ConfigEventsTailer(component.getRedisClient(), prefix + "config-events",
            new ConfigEventsTailer.Listener() {
                @Override
                public void onEvent(StreamMessage<String, String> entry) {
                    received.add("evt:" + entry.getBody().get("event"));
                }
            }).blockMs(5000).backstopSec(0).heartbeatSec(0).unblockWith(redis);
        assertEquals("0-0", tailer.captureLastId());
        tailer.start();
        redis.set(prefix + "cache:generation", "1");
        xaddEvent("CONFIG_CACHE_REFRESHED");
        await(() -> received.contains("evt:CONFIG_CACHE_REFRESHED"), 5000, "tailed event");
        Thread.sleep(100); // let it block again
        long t0 = System.currentTimeMillis();
        tailer.stop();
        long took = System.currentTimeMillis() - t0;
        assertTrue(took < 2000, "stop took " + took + " ms");
        assertFalse(tailer.isRunning());
        assertTrue(redis.xread(XReadArgs.StreamOffset.from(prefix + "config-events", "0")).size() == 1);
    }
}
