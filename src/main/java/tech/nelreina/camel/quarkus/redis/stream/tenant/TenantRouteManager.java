package tech.nelreina.camel.quarkus.redis.stream.tenant;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.CamelContext;
import org.apache.camel.Route;
import org.apache.camel.RoutesBuilder;
import org.apache.camel.support.service.ServiceSupport;

import io.lettuce.core.KeyValue;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.api.sync.RedisCommands;
import io.quarkus.logging.Log;
import tech.nelreina.camel.quarkus.redis.stream.component.RedisStreamComponent;
import tech.nelreina.camel.quarkus.redis.stream.consumer.RedisStreamConsumer;
import tech.nelreina.camel.quarkus.redis.stream.slot.ConsumerNameSupplier;
import tech.nelreina.camel.quarkus.redis.stream.slot.ConsumerSlot;
import tech.nelreina.camel.quarkus.redis.stream.slot.ConsumerSlotListener;
import tech.nelreina.camel.quarkus.redis.stream.util.StreamGroups;

/**
 * Hot add / remove of per-tenant Camel routes driven by the config snapshot in Redis (proposal &sect;4.5).
 *
 * <p>Snapshot is truth, the control stream only wakes the reconcile:
 * <pre>
 * start(): consumer name (ConsumerSlot) &rarr; lastId = XREVRANGE events + - COUNT 1 &rarr; reconcile("boot")
 *          &rarr; XREAD BLOCK loop on its own connection &rarr; backstop reconcile("timer") every reconcileSec,
 *          heartbeat every heartbeatSec
 * reconcile(reason), serialized (requests during a run coalesce into one rerun):
 *   MGET cache:status cache:generation host-banks:active
 *   empty active list &rarr; WARN, heartbeat, return (never evict every tenant)
 *   timer &amp;&amp; generation unchanged &amp;&amp; nothing to retry &rarr; heartbeat, return
 *   generation lower than last seen &rarr; WARN "reset", full reconcile
 *   added:   ensureGroup(stream, tenant-activated:&lt;bic&gt; ?? "$") &rarr; onTenantAdded &rarr; addRoutes(factory(bic))
 *            failure &rarr; degraded {reason, nextRetryAt = backoff 1 s &hellip; 60 s}, no routes
 *   removed: stopRoute (redis-stream consume routes first) &rarr; removeRoute &rarr; onTenantRemoved
 *   kept:    fingerprint changed &rarr; routes removed + re-added
 *   heartbeat: HSET applied:&lt;svc&gt;:&lt;consumerName&gt; {generation, tenants, degraded, hotReload, consumerName, ts} EX 120
 * </pre>
 *
 * <p>Add it to the CamelContext ({@code camelContext.addService(manager, true, true)}) or call
 * {@code start()} / {@code stop()} yourself; stop removes every tenant route and deletes the heartbeat key.
 */
public class TenantRouteManager extends ServiceSupport {

    public static final String DEFAULT_ACTIVE_KEY = "ynohub:config:host-banks:active";
    public static final String DEFAULT_GENERATION_KEY = "ynohub:config:cache:generation";
    public static final String DEFAULT_STATUS_KEY = "ynohub:config:cache:status";
    public static final String DEFAULT_EVENTS_STREAM = "ynohub:config:events";
    public static final String DEFAULT_ACTIVATED_KEY_PREFIX = "ynohub:config:tenant-activated";
    public static final String DEFAULT_APPLIED_KEY_PREFIX = "ynohub:config:applied";
    public static final String EVENT_REFRESHED = "CONFIG_CACHE_REFRESHED";
    public static final String EVENT_REFRESH_FAILED = "CONFIG_CACHE_REFRESH_FAILED";

    static final long BACKOFF_MIN_MS = 1000;
    static final long BACKOFF_MAX_MS = 60_000;

    private final CamelContext camelContext;
    private final TenantRouteSpec spec;
    private final String serviceName;
    private final String activeKey;
    private final String generationKey;
    private final String statusKey;
    private final String eventsStream;
    private final String activatedKeyPrefix;
    private final String appliedKeyPrefix;
    private final long reconcileSec;
    private final long heartbeatSec;
    private final long heartbeatTtlSec;
    private final long blockMs;
    private final String hotReload;
    private final ObjectMapper json = new ObjectMapper();

    private RedisStreamComponent component;
    private RedisCommands<String, String> redis;
    private ConfigEventsTailer tailer;
    private ExecutorService reconciler;

    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicReference<String> queuedReason = new AtomicReference<>();
    private final Map<String, TenantEntry> current = new ConcurrentHashMap<>();
    private final Map<String, Degraded> degraded = new ConcurrentHashMap<>();
    private volatile long lastSeenGeneration = -1;
    private volatile String lastReason;
    private volatile long lastReconcileAt;
    private volatile String lastStatus;
    private volatile String heartbeatName;

    private final ConsumerSlotListener slotListener = new ConsumerSlotListener() {
        @Override
        public void beforeReclaim(String lostName) {
            lock.lock(); // no reconcile while the consumers change name; released in afterReclaim (same thread)
        }

        @Override
        public void afterReclaim(String lostName, String newName) {
            try {
                if (newName != null) {
                    deleteHeartbeat(lostName);
                    heartbeat();
                }
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    };

    private TenantRouteManager(Builder b) {
        this.camelContext = b.camelContext;
        this.spec = b.spec;
        this.serviceName = b.serviceName;
        this.activeKey = b.activeKey;
        this.generationKey = b.generationKey;
        this.statusKey = b.statusKey;
        this.eventsStream = b.eventsStream;
        this.activatedKeyPrefix = b.activatedKeyPrefix;
        this.appliedKeyPrefix = b.appliedKeyPrefix;
        this.reconcileSec = b.reconcileSec;
        this.heartbeatSec = b.heartbeatSec;
        this.heartbeatTtlSec = b.heartbeatTtlSec;
        this.blockMs = b.blockMs;
        this.hotReload = b.hotReload;
        this.component = b.component;
    }

    public static Builder builder(CamelContext camelContext, TenantRouteSpec spec) {
        return new Builder(camelContext, spec);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void doStart() throws Exception {
        if (component == null) {
            component = camelContext.getComponent("redis-stream", RedisStreamComponent.class);
        }
        redis = component.getConnection().sync();
        reconciler = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "redis-stream-tenant-reconcile");
            t.setDaemon(true);
            return t;
        });
        String name = consumerName(); // claims the slot before any tenant stream is read
        ConsumerNameSupplier supplier = component.getConsumerNameSupplier();
        if (supplier instanceof ConsumerSlot) {
            ((ConsumerSlot) supplier).addListener(slotListener);
        }
        if (eventsStream != null && !eventsStream.isBlank()) {
            tailer = new ConfigEventsTailer(component.getRedisClient(), eventsStream, new ConfigEventsTailer.Listener() {
                @Override
                public void onEvent(StreamMessage<String, String> entry) {
                    onControlEvent(entry);
                }

                @Override
                public void onBackstop() {
                    requestReconcile("timer");
                }

                @Override
                public void onHeartbeat() {
                    heartbeat();
                }

                @Override
                public void onResume(String reason) {
                    requestReconcile(reason);
                }
            }).blockMs(blockMs).backstopSec(reconcileSec).heartbeatSec(heartbeatSec).unblockWith(redis);
            tailer.captureLastId(); // before the first reconcile
        }
        Log.infof("TenantRouteManager %s starting as %s (active=%s, events=%s)", serviceName, name, activeKey, eventsStream);
        reconcile("boot");
        if (tailer != null) {
            tailer.start();
        }
    }

    @Override
    protected void doStop() throws Exception {
        stopAll();
    }

    /** Stops the tailer and timers, removes every tenant route (consume routes first) and the heartbeat key. */
    public void stopAll() {
        if (tailer != null) {
            tailer.stop();
            tailer = null;
        }
        if (reconciler != null) {
            reconciler.shutdown();
            try {
                reconciler.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            reconciler = null;
        }
        lock.lock();
        try {
            for (String bic : new ArrayList<>(current.keySet())) {
                removeTenant(bic, true);
            }
            degraded.clear();
            if (heartbeatName != null) {
                deleteHeartbeat(heartbeatName);
            }
        } finally {
            lock.unlock();
        }
        if (component != null && component.getConsumerNameSupplier() instanceof ConsumerSlot) {
            ((ConsumerSlot) component.getConsumerNameSupplier()).removeListener(slotListener);
        }
    }

    // ------------------------------------------------------------------ reconcile

    private void onControlEvent(StreamMessage<String, String> entry) {
        Map<String, String> f = entry.getBody();
        String event = f == null ? null : f.get("event");
        if (EVENT_REFRESH_FAILED.equals(event)) {
            Log.warnf("Config refresh failed (generation %s): %s", f.get("aggregateId"), f.get("payload"));
            return; // the live snapshot is untouched by a failed run
        }
        requestReconcile(EVENT_REFRESHED.equals(event) ? "event" : "event:" + event);
    }

    /**
     * Asynchronous, coalescing reconcile request: at most one run is queued behind the running one; a non-timer
     * reason wins over "timer" so the queued run is not short-circuited.
     */
    public void requestReconcile(String reason) {
        ExecutorService ex = reconciler;
        if (ex == null || ex.isShutdown()) {
            return;
        }
        String prev = queuedReason.getAndAccumulate(reason, (old, neu) -> old == null ? neu
            : ("timer".equals(old) ? neu : old));
        if (prev == null) {
            try {
                ex.execute(() -> {
                    String r = queuedReason.getAndSet(null);
                    reconcile(r == null ? reason : r);
                });
            } catch (Exception e) {
                queuedReason.set(null);
            }
        }
    }

    /** Synchronous reconcile; serialized with every other reconcile. */
    public ReconcileResult reconcile(String reason) {
        lock.lock();
        try {
            return doReconcile(reason == null ? "manual" : reason);
        } catch (Exception e) {
            Log.errorf(e, "Tenant reconcile (%s) failed", reason);
            return new ReconcileResult(reason, false, List.of(), List.of(), List.of(), List.of(), e.toString());
        } finally {
            lock.unlock();
        }
    }

    private ReconcileResult doReconcile(String reason) {
        List<KeyValue<String, String>> values = redis.mget(statusKey, generationKey, activeKey);
        String status = value(values, 0);
        String genStr = value(values, 1);
        String activeCsv = value(values, 2);
        long gen = parseLong(genStr);
        lastStatus = status;
        lastReason = reason;
        lastReconcileAt = System.currentTimeMillis();

        Set<String> desired = parseCsv(activeCsv);
        if (desired.isEmpty()) {
            Log.warnf("Reconcile (%s): %s is empty or missing; keeping the %d current tenant(s)", reason, activeKey,
                current.size());
            heartbeat();
            return new ReconcileResult(reason, true, List.of(), List.of(), List.of(), List.of(), "empty active list");
        }
        long now = System.currentTimeMillis();
        boolean retryDue = degraded.entrySet().stream()
            .anyMatch(e -> desired.contains(e.getKey()) && e.getValue().nextRetryAt <= now);
        if ("timer".equals(reason) && gen == lastSeenGeneration && current.keySet().equals(desired) && !retryDue
                && !fingerprintsChanged()) {
            heartbeat();
            return new ReconcileResult(reason, true, List.of(), List.of(), List.of(), List.of(), null);
        }
        if (lastSeenGeneration >= 0 && gen >= 0 && gen < lastSeenGeneration) {
            Log.warnf("Reconcile (%s): generation went from %d to %d (reset); full reconcile", reason, lastSeenGeneration, gen);
        }

        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> rebuilt = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (String bic : desired) {
            if (current.containsKey(bic)) {
                continue;
            }
            Degraded d = degraded.get(bic);
            if (d != null && d.nextRetryAt > now) {
                continue;
            }
            if (addTenant(bic)) {
                added.add(bic);
            } else {
                failed.add(bic);
            }
        }
        for (String bic : new ArrayList<>(current.keySet())) {
            if (!desired.contains(bic)) {
                removeTenant(bic, true);
                removed.add(bic);
            }
        }
        for (String bic : new ArrayList<>(current.keySet())) {
            TenantEntry entry = current.get(bic);
            String fp = spec.fingerprint(bic);
            if (entry != null && !Objects.equals(fp, entry.fingerprint)) {
                Log.infof("Tenant %s: route fingerprint changed; rebuilding its routes", bic);
                removeRoutes(bic, entry);
                current.remove(bic);
                if (addRoutes(bic, false)) {
                    rebuilt.add(bic);
                } else {
                    failed.add(bic);
                }
            }
        }
        degraded.keySet().removeIf(bic -> !desired.contains(bic));
        lastSeenGeneration = gen;
        heartbeat();
        if (!added.isEmpty() || !removed.isEmpty() || !rebuilt.isEmpty() || !failed.isEmpty()) {
            Log.infof("Reconcile (%s, generation %d): added=%s removed=%s rebuilt=%s degraded=%s tenants=%s", reason, gen,
                added, removed, rebuilt, degraded.keySet(), tenants());
        }
        return new ReconcileResult(reason, true, added, removed, rebuilt, failed, null);
    }

    private boolean fingerprintsChanged() {
        for (Map.Entry<String, TenantEntry> e : current.entrySet()) {
            if (!Objects.equals(spec.fingerprint(e.getKey()), e.getValue().fingerprint)) {
                return true;
            }
        }
        return false;
    }

    private boolean addTenant(String bic) {
        String startId = activationStartId(bic);
        try {
            if (spec.hasStream()) {
                // Pins the position even if the tenant ends up degraded.
                StreamGroups.ensureGroup(redis, spec.streamKey(bic), spec.getGroup(), startId, true);
            }
        } catch (Exception e) {
            markDegraded(bic, e);
            return false;
        }
        if (spec.getOnTenantAdded() != null) {
            try {
                spec.getOnTenantAdded().accept(bic);
            } catch (Exception e) {
                markDegraded(bic, e);
                return false;
            }
        }
        if (!addRoutes(bic, true)) {
            return false;
        }
        Log.infof("Tenant %s added (group start %s)", bic, startId);
        return true;
    }

    private boolean addRoutes(String bic, boolean runRemoveHookOnFailure) {
        Set<String> before = routeIds();
        try {
            RoutesBuilder routes = spec.routes(bic);
            camelContext.addRoutes(routes);
            List<String> ids = new ArrayList<>(routeIds());
            ids.removeAll(before);
            current.put(bic, new TenantEntry(ids, spec.fingerprint(bic)));
            degraded.remove(bic);
            return true;
        } catch (Exception e) {
            List<String> partial = new ArrayList<>(routeIds());
            partial.removeAll(before);
            removeRoutes(bic, new TenantEntry(partial, null));
            if (runRemoveHookOnFailure) {
                runRemovedHook(bic);
            }
            markDegraded(bic, e);
            return false;
        }
    }

    private void removeTenant(String bic, boolean runHook) {
        TenantEntry entry = current.remove(bic);
        if (entry != null) {
            removeRoutes(bic, entry);
        }
        if (runHook) {
            runRemovedHook(bic);
        }
        Log.infof("Tenant %s removed", bic);
    }

    private void runRemovedHook(String bic) {
        if (spec.getOnTenantRemoved() != null) {
            try {
                spec.getOnTenantRemoved().accept(bic);
            } catch (Exception e) {
                Log.errorf(e, "onTenantRemoved(%s) failed", bic);
            }
        }
    }

    /** Stops the redis-stream consume routes first (in-flight message finishes and is acked), then the rest. */
    private void removeRoutes(String bic, TenantEntry entry) {
        List<String> consume = new ArrayList<>();
        List<String> others = new ArrayList<>();
        for (String id : entry.routeIds) {
            Route r = camelContext.getRoute(id);
            if (r == null) {
                continue;
            }
            if (r.getConsumer() instanceof RedisStreamConsumer) {
                consume.add(id);
            } else {
                others.add(id);
            }
        }
        List<String> order = new ArrayList<>(consume);
        order.addAll(others);
        for (String id : order) {
            try {
                camelContext.getRouteController().stopRoute(id);
            } catch (Exception e) {
                Log.errorf(e, "Stopping route %s of tenant %s failed", id, bic);
            }
        }
        for (String id : order) {
            try {
                camelContext.removeRoute(id);
            } catch (Exception e) {
                Log.errorf(e, "Removing route %s of tenant %s failed", id, bic);
            }
        }
    }

    private void markDegraded(String bic, Exception e) {
        Degraded prev = degraded.get(bic);
        int attempts = prev == null ? 1 : prev.attempts + 1;
        long delay = Math.min(BACKOFF_MAX_MS, BACKOFF_MIN_MS << Math.min(attempts - 1, 16));
        String reason = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        degraded.put(bic, new Degraded(reason, attempts, System.currentTimeMillis() + delay));
        Log.warnf("Tenant %s degraded (attempt %d, retry in %d ms): %s", bic, attempts, delay, reason);
    }

    /** {@code GET tenant-activated:<bic>}, else "$". */
    public String activationStartId(String bic) {
        try {
            String v = redis.get(activatedKeyPrefix + ":" + bic);
            return v == null || v.isBlank() ? "$" : v.trim();
        } catch (Exception e) {
            Log.warnf("Reading activation id of %s failed: %s; using $", bic, e.getMessage());
            return "$";
        }
    }

    // ------------------------------------------------------------------ heartbeat / status

    /** {@code HSET applied:<svc>:<consumerName> ... EX heartbeatTtlSec}. Never throws. */
    public void heartbeat() {
        try {
            String name = consumerName();
            String key = appliedKeyPrefix + ":" + serviceName + ":" + name;
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("generation", String.valueOf(lastSeenGeneration));
            fields.put("tenants", String.join(",", tenants()));
            fields.put("degraded", json.writeValueAsString(degradedReasons()));
            fields.put("hotReload", hotReload);
            fields.put("consumerName", name);
            fields.put("ts", String.valueOf(System.currentTimeMillis()));
            redis.hset(key, fields);
            redis.expire(key, heartbeatTtlSec);
            heartbeatName = name;
        } catch (Exception e) {
            Log.warnf("Heartbeat failed: %s", e.getMessage());
        }
    }

    private void deleteHeartbeat(String name) {
        try {
            redis.del(appliedKeyPrefix + ":" + serviceName + ":" + name);
        } catch (Exception e) {
            Log.debugf("Deleting heartbeat failed: %s", e.getMessage());
        }
    }

    /** Consumer name of this JVM: the component's ConsumerNameSupplier, else env CONSUMER_NAME, else serviceName. */
    public String consumerName() {
        ConsumerNameSupplier supplier = component == null ? null : component.getConsumerNameSupplier();
        if (supplier != null) {
            String n = supplier.consumerName();
            if (n != null && !n.isBlank()) {
                return n;
            }
        }
        String env = System.getenv("CONSUMER_NAME");
        return env != null && !env.isBlank() ? env.trim() : serviceName;
    }

    /** Tenants whose routes are live (sorted). */
    public List<String> tenants() {
        return new ArrayList<>(new TreeSet<>(current.keySet()));
    }

    /** Stream keys of the live tenants (only when the spec has a streamKey function). */
    public Set<String> liveStreams() {
        if (!spec.hasStream()) {
            return Set.of();
        }
        return current.keySet().stream().map(spec::streamKey).collect(Collectors.toCollection(TreeSet::new));
    }

    public Map<String, String> degradedReasons() {
        Map<String, String> m = new TreeMap<>();
        degraded.forEach((k, v) -> m.put(k, v.reason));
        return m;
    }

    /** Snapshot for /health-check. */
    public Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("serviceName", serviceName);
        s.put("consumerName", consumerName());
        s.put("generation", lastSeenGeneration);
        s.put("cacheStatus", lastStatus);
        s.put("tenants", tenants());
        Map<String, Object> deg = new TreeMap<>();
        degraded.forEach((k, v) -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("reason", v.reason);
            d.put("attempts", v.attempts);
            d.put("nextRetryAt", Instant.ofEpochMilli(v.nextRetryAt).toString());
            deg.put(k, d);
        });
        s.put("degraded", deg);
        s.put("hotReload", hotReload);
        s.put("lastReason", lastReason);
        s.put("lastReconcileAt", lastReconcileAt == 0 ? null : Instant.ofEpochMilli(lastReconcileAt).toString());
        s.put("eventsStream", eventsStream);
        s.put("lastEventId", tailer == null ? null : tailer.getLastId());
        return s;
    }

    public String statusJson() {
        try {
            return json.writeValueAsString(status());
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    public long getLastSeenGeneration() {
        return lastSeenGeneration;
    }

    public String getServiceName() {
        return serviceName;
    }

    public List<String> routeIdsOf(String bic) {
        TenantEntry e = current.get(bic);
        return e == null ? List.of() : Collections.unmodifiableList(e.routeIds);
    }

    // ------------------------------------------------------------------ helpers

    private Set<String> routeIds() {
        Set<String> ids = new HashSet<>();
        for (Route r : camelContext.getRoutes()) {
            ids.add(r.getRouteId());
        }
        return ids;
    }

    static Set<String> parseCsv(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv == null) {
            return out;
        }
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static String value(List<KeyValue<String, String>> values, int i) {
        if (values == null || values.size() <= i) {
            return null;
        }
        KeyValue<String, String> kv = values.get(i);
        return kv != null && kv.hasValue() ? kv.getValue() : null;
    }

    private static long parseLong(String s) {
        if (s == null) {
            return -1;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static final class TenantEntry {
        final List<String> routeIds;
        final String fingerprint;

        TenantEntry(List<String> routeIds, String fingerprint) {
            this.routeIds = routeIds;
            this.fingerprint = fingerprint;
        }
    }

    private static final class Degraded {
        final String reason;
        final int attempts;
        final long nextRetryAt;

        Degraded(String reason, int attempts, long nextRetryAt) {
            this.reason = reason;
            this.attempts = attempts;
            this.nextRetryAt = nextRetryAt;
        }
    }

    /** Outcome of one reconcile. */
    public static final class ReconcileResult {
        public final String reason;
        public final boolean ok;
        public final List<String> added;
        public final List<String> removed;
        public final List<String> rebuilt;
        public final List<String> failed;
        public final String note;

        ReconcileResult(String reason, boolean ok, List<String> added, List<String> removed, List<String> rebuilt,
                List<String> failed, String note) {
            this.reason = reason;
            this.ok = ok;
            this.added = added;
            this.removed = removed;
            this.rebuilt = rebuilt;
            this.failed = failed;
            this.note = note;
        }

        @Override
        public String toString() {
            return "ReconcileResult{reason=" + reason + ", ok=" + ok + ", added=" + added + ", removed=" + removed
                + ", rebuilt=" + rebuilt + ", failed=" + failed + ", note=" + note + "}";
        }
    }

    public static final class Builder {
        private final CamelContext camelContext;
        private final TenantRouteSpec spec;
        private RedisStreamComponent component;
        private String serviceName = System.getenv("SERVICE_NAME");
        private String activeKey = DEFAULT_ACTIVE_KEY;
        private String generationKey = DEFAULT_GENERATION_KEY;
        private String statusKey = DEFAULT_STATUS_KEY;
        private String eventsStream = envOr("CONFIG_EVENTS_STREAM", DEFAULT_EVENTS_STREAM);
        private String activatedKeyPrefix = DEFAULT_ACTIVATED_KEY_PREFIX;
        private String appliedKeyPrefix = DEFAULT_APPLIED_KEY_PREFIX;
        private long reconcileSec = Long.parseLong(envOr("CONFIG_RECONCILE_SEC", "60"));
        private long heartbeatSec = 30;
        private long heartbeatTtlSec = 120;
        private long blockMs = 5000;
        private String hotReload = "all";

        private Builder(CamelContext camelContext, TenantRouteSpec spec) {
            this.camelContext = Objects.requireNonNull(camelContext, "camelContext");
            this.spec = Objects.requireNonNull(spec, "spec");
        }

        public Builder component(RedisStreamComponent component) {
            this.component = component;
            return this;
        }

        public Builder serviceName(String serviceName) {
            this.serviceName = serviceName;
            return this;
        }

        public Builder activeKey(String activeKey) {
            this.activeKey = activeKey;
            return this;
        }

        public Builder generationKey(String generationKey) {
            this.generationKey = generationKey;
            return this;
        }

        public Builder statusKey(String statusKey) {
            this.statusKey = statusKey;
            return this;
        }

        /** Control stream to tail; null/blank disables the tailer (timer-only operation via external calls). */
        public Builder eventsStream(String eventsStream) {
            this.eventsStream = eventsStream;
            return this;
        }

        public Builder activatedKeyPrefix(String activatedKeyPrefix) {
            this.activatedKeyPrefix = activatedKeyPrefix;
            return this;
        }

        public Builder appliedKeyPrefix(String appliedKeyPrefix) {
            this.appliedKeyPrefix = appliedKeyPrefix;
            return this;
        }

        public Builder reconcileSec(long reconcileSec) {
            this.reconcileSec = reconcileSec;
            return this;
        }

        public Builder heartbeatSec(long heartbeatSec) {
            this.heartbeatSec = heartbeatSec;
            return this;
        }

        public Builder heartbeatTtlSec(long heartbeatTtlSec) {
            this.heartbeatTtlSec = heartbeatTtlSec;
            return this;
        }

        public Builder blockMs(long blockMs) {
            this.blockMs = blockMs;
            return this;
        }

        public Builder hotReload(String hotReload) {
            this.hotReload = hotReload;
            return this;
        }

        public TenantRouteManager build() {
            if (serviceName == null || serviceName.isBlank()) {
                throw new IllegalArgumentException("serviceName (or env SERVICE_NAME) is required");
            }
            return new TenantRouteManager(this);
        }

        private static String envOr(String name, String def) {
            String v = System.getenv(name);
            return v == null || v.isBlank() ? def : v.trim();
        }
    }
}
