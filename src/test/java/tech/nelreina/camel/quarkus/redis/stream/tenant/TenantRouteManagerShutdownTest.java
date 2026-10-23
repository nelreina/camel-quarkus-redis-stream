package tech.nelreina.camel.quarkus.redis.stream.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.camel.CamelContext;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.LifecycleStrategySupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tech.nelreina.camel.quarkus.redis.stream.RedisTestSupport;
import tech.nelreina.camel.quarkus.redis.stream.component.RedisStreamComponent;
import tech.nelreina.camel.quarkus.redis.stream.slot.ConsumerNameSupplier;
import tech.nelreina.camel.quarkus.redis.stream.slot.ConsumerSlot;

/**
 * Shutdown order (1.4.1): the TenantRouteManager must stop while the component's connection is still open,
 * whatever the wiring, so the heartbeat DEL and the slot release both reach Redis.
 */
class TenantRouteManagerShutdownTest extends RedisTestSupport {

    private CamelContext camel;
    private RedisStreamComponent component;
    private TenantRouteManager manager;
    private final List<String> removedHooks = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        ConsumerSlot slot = ConsumerSlot.builder(redis, "svc").keyPrefix(prefix + "consumer-slot").podName("pod-a")
            .consumerNameOverride(null).build();
        camel = new DefaultCamelContext();
        camel.getRegistry().bind("slot", ConsumerNameSupplier.class, slot);
        component = new RedisStreamComponent();
        component.setRedisHosts(REDIS_URL);
        camel.addComponent("redis-stream", component);
        camel.start();
        redis.set(prefix + "host-banks:active", "aaa");
        redis.set(prefix + "cache:generation", "1");
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

    private TenantRouteManager build() {
        TenantRouteSpec spec = new TenantRouteSpec(bic -> new RouteBuilder() {
            @Override
            public void configure() {
                from("redis-stream:" + prefix + "events:" + bic + "?group=svc&events=E&pollingInterval=20")
                    .routeId(bic + "-consume")
                    .log("${body}");
            }
        }, bic -> "")
            .stream(bic -> prefix + "events:" + bic, "svc")
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
            .reconcileSec(3600)
            .blockMs(5000)
            .build();
    }

    private void assertCleanShutdown() {
        assertTrue(manager.isStopped(), "manager stopped");
        assertEquals(0L, redis.exists(prefix + "applied:svc:svc-1"), "heartbeat key deleted");
        assertEquals(0L, redis.exists(prefix + "consumer-slot:svc:1"), "consumer slot released");
        assertEquals(List.of("aaa"), removedHooks, "onTenantRemoved ran once");
    }

    @Test
    void addServiceWiringStopsBeforeTheComponentClosesItsConnection() throws Exception {
        manager = build();
        camel.addService(manager, true, true);
        assertEquals(List.of("aaa"), manager.tenants());
        assertEquals(1L, redis.exists(prefix + "applied:svc:svc-1"));

        camel.stop();

        assertCleanShutdown();
        assertNull(camel.getRoute("aaa-consume"));
    }

    @Test
    void manualStartIsStoppedWithTheContext() throws Exception {
        manager = build();
        manager.start();
        assertEquals(1L, redis.exists(prefix + "applied:svc:svc-1"));

        camel.stop();

        assertCleanShutdown();
    }

    @Test
    void managerStopsBeforeTheContextStopsItsRoutes() throws Exception {
        List<Boolean> routeStartedWhenStopping = new CopyOnWriteArrayList<>();
        manager = build();
        camel.addService(manager, true, true);
        // registered after the manager's own strategy: by the time it runs the tenant routes are gone already
        camel.addLifecycleStrategy(new LifecycleStrategySupport() {
            @Override
            public void onContextStopping(CamelContext context) {
                routeStartedWhenStopping.add(context.getRoute("aaa-consume") != null);
            }
        });

        camel.stop();

        assertEquals(List.of(false), routeStartedWhenStopping);
        assertCleanShutdown();
    }

    @Test
    void componentStopAloneStopsTheManagerFirst() throws Exception {
        manager = build();
        manager.start();

        component.stop();

        assertCleanShutdown();
    }

    @Test
    void servicesThatAlreadyStopTheManagerOnStoppingStayCompatible() throws Exception {
        // the 1.4.0 workaround of the parsers: stop the manager in onContextStopping / CamelContextStoppingEvent
        manager = build();
        manager.start();
        camel.addService(manager, true, false);
        camel.addLifecycleStrategy(new LifecycleStrategySupport() {
            @Override
            public void onContextStopping(CamelContext context) {
                if (!manager.isStopped()) {
                    manager.stop();
                }
            }
        });

        camel.stop();

        assertCleanShutdown();
        assertFalse(manager.isStarted());
    }
}
