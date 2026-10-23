package tech.nelreina.camel.quarkus.redis.stream.tenant;

import java.util.Objects;
import java.util.function.Function;

import org.apache.camel.RoutesBuilder;

/**
 * What the {@link TenantRouteManager} builds per tenant (host bank BIC).
 *
 * <ul>
 *   <li>{@code factory(bic)}: the routes of one tenant (consume route + the routes it feeds). Leave
 *       {@code consumerName} off the redis-stream URI: the component-level ConsumerNameSupplier names it,
 *       which also lets a slot change restart it under the new name. Define the redis-stream route last (or give
 *       it the highest startupOrder) so the routes it sends to are up before it polls.</li>
 *   <li>{@code fingerprint(bic)}: any string describing the tenant's route-relevant config (e.g. its
 *       protocols JSON). When it changes between reconciles the tenant's routes are removed and re-added.</li>
 *   <li>optional {@code streamKey(bic)} + {@code group}: the manager then creates the group at the tenant's
 *       activation id ({@code tenant-activated:<bic>}, else "$") before the routes are added, and exposes
 *       {@link TenantRouteManager#liveStreams()}.</li>
 *   <li>optional {@code onTenantAdded / onTenantRemoved} hooks: an exception from onTenantAdded marks the tenant
 *       degraded (group created, no routes, retried with backoff 1 s &hellip; 60 s).</li>
 * </ul>
 */
public final class TenantRouteSpec {

    /** A tenant lifecycle hook that may fail. */
    @FunctionalInterface
    public interface TenantHook {
        void accept(String bic) throws Exception;
    }

    private final Function<String, ? extends RoutesBuilder> factory;
    private final Function<String, String> fingerprint;
    private Function<String, String> streamKey;
    private String group;
    private TenantHook onTenantAdded;
    private TenantHook onTenantRemoved;

    public TenantRouteSpec(Function<String, ? extends RoutesBuilder> factory, Function<String, String> fingerprint) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.fingerprint = fingerprint != null ? fingerprint : bic -> "";
    }

    /** Spec without a fingerprint (routes are only rebuilt when the tenant is removed and added again). */
    public static TenantRouteSpec of(Function<String, ? extends RoutesBuilder> factory) {
        return new TenantRouteSpec(factory, null);
    }

    public TenantRouteSpec stream(Function<String, String> streamKey, String group) {
        this.streamKey = Objects.requireNonNull(streamKey, "streamKey");
        this.group = Objects.requireNonNull(group, "group");
        return this;
    }

    public TenantRouteSpec onTenantAdded(TenantHook hook) {
        this.onTenantAdded = hook;
        return this;
    }

    public TenantRouteSpec onTenantRemoved(TenantHook hook) {
        this.onTenantRemoved = hook;
        return this;
    }

    public RoutesBuilder routes(String bic) {
        return factory.apply(bic);
    }

    public String fingerprint(String bic) {
        String fp = fingerprint.apply(bic);
        return fp == null ? "" : fp;
    }

    public String streamKey(String bic) {
        return streamKey == null ? null : streamKey.apply(bic);
    }

    public boolean hasStream() {
        return streamKey != null && group != null;
    }

    public String getGroup() {
        return group;
    }

    TenantHook getOnTenantAdded() {
        return onTenantAdded;
    }

    TenantHook getOnTenantRemoved() {
        return onTenantRemoved;
    }
}
