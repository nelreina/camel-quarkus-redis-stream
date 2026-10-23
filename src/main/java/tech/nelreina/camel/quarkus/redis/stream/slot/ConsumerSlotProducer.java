package tech.nelreina.camel.quarkus.redis.stream.slot;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import org.apache.camel.CamelContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.logging.Log;
import tech.nelreina.camel.quarkus.redis.stream.component.RedisStreamComponent;

/**
 * Opt-in CDI producer of the {@link ConsumerSlot} used as the component-level {@link ConsumerNameSupplier}.
 *
 * <p>Enable at build time with {@code redis-stream.consumer-slot.enabled=true}. Service name:
 * {@code redis-stream.consumer-slot.service-name}, default env {@code SERVICE_NAME}. The slot is claimed
 * lazily on the first lookup (first consumer start or TenantRouteManager start), i.e. before any stream
 * is read, using the component's shared connection. The component releases it on shutdown after its
 * consumers have stopped.
 */
@IfBuildProperty(name = "redis-stream.consumer-slot.enabled", stringValue = "true")
public class ConsumerSlotProducer {

    @Produces
    @Singleton
    @Unremovable
    ConsumerNameSupplier consumerSlot(CamelContext camelContext,
            @ConfigProperty(name = "redis-stream.consumer-slot.service-name", defaultValue = "${SERVICE_NAME:}") String serviceName,
            @ConfigProperty(name = "redis-stream.consumer-slot.key-prefix", defaultValue = ConsumerSlot.DEFAULT_KEY_PREFIX) String keyPrefix,
            @ConfigProperty(name = "redis-stream.consumer-slot.ttl-ms", defaultValue = "30000") long ttlMs,
            @ConfigProperty(name = "redis-stream.consumer-slot.renew-ms", defaultValue = "10000") long renewMs) {
        RedisStreamComponent component = camelContext.getComponent("redis-stream", RedisStreamComponent.class);
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalStateException(
                "redis-stream.consumer-slot.enabled=true needs redis-stream.consumer-slot.service-name or env SERVICE_NAME");
        }
        ConsumerSlot slot = ConsumerSlot.builder(component.getConnection().sync(), serviceName)
            .keyPrefix(keyPrefix)
            .ttlMs(ttlMs)
            .renewMs(renewMs)
            .build();
        slot.claim();
        Log.infof("redis-stream consumer name for this JVM: %s", slot.getName());
        return slot;
    }
}
