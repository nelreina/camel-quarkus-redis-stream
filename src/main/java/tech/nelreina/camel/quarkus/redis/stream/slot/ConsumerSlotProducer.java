package tech.nelreina.camel.quarkus.redis.stream.slot;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Function;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import org.apache.camel.CamelContext;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.logging.Log;
import tech.nelreina.camel.quarkus.redis.stream.component.RedisStreamComponent;

/**
 * Opt-in CDI producer of the {@link ConsumerSlot} used as the component-level {@link ConsumerNameSupplier}.
 *
 * <p>Enable at build time with {@code redis-stream.consumer-slot.enabled=true}. Service name, first non-blank of:
 * config {@code redis-stream.consumer-slot.service-name}, config {@code SERVICE_NAME} (env / system property /
 * .env), {@code System.getenv("SERVICE_NAME")}; none &rarr; startup fails with a clear message. The slot is claimed
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
            @ConfigProperty(name = "redis-stream.consumer-slot.key-prefix", defaultValue = ConsumerSlot.DEFAULT_KEY_PREFIX) String keyPrefix,
            @ConfigProperty(name = "redis-stream.consumer-slot.ttl-ms", defaultValue = "30000") long ttlMs,
            @ConfigProperty(name = "redis-stream.consumer-slot.renew-ms", defaultValue = "10000") long renewMs) {
        Config config = ConfigProvider.getConfig();
        String serviceName = resolveServiceName(name -> configValue(config, name), System::getenv);
        RedisStreamComponent component = camelContext.getComponent("redis-stream", RedisStreamComponent.class);
        ConsumerSlot slot = ConsumerSlot.builder(component.getConnection().sync(), serviceName)
            .keyPrefix(keyPrefix)
            .ttlMs(ttlMs)
            .renewMs(renewMs)
            .build();
        slot.claim();
        Log.infof("redis-stream consumer name for this JVM: %s", slot.getName());
        return slot;
    }

    static final String SERVICE_NAME_PROPERTY = "redis-stream.consumer-slot.service-name";
    static final String SERVICE_NAME_ENV = "SERVICE_NAME";

    /**
     * {@code redis-stream.consumer-slot.service-name}, else {@code SERVICE_NAME} from config, else from the
     * environment. A value that is blank, or an unexpanded {@code ${...}} expression, counts as missing.
     *
     * @throws IllegalStateException when none is set
     */
    static String resolveServiceName(Function<String, Optional<String>> config, Function<String, String> env) {
        Optional<String> name = usable(config.apply(SERVICE_NAME_PROPERTY))
            .or(() -> usable(config.apply(SERVICE_NAME_ENV)))
            .or(() -> usable(Optional.ofNullable(env.apply(SERVICE_NAME_ENV))));
        return name.orElseThrow(() -> new IllegalStateException(
            "redis-stream.consumer-slot.enabled=true but no service name: set redis-stream.consumer-slot.service-name"
                + " or the SERVICE_NAME environment variable"));
    }

    private static Optional<String> usable(Optional<String> value) {
        return value.map(String::trim).filter(v -> !v.isEmpty() && !v.contains("${"));
    }

    /** Optional config value; an expression that references a missing variable ({@code ${SERVICE_NAME}}) is absent. */
    static Optional<String> configValue(Config config, String name) {
        try {
            return config.getOptionalValue(name, String.class);
        } catch (NoSuchElementException e) {
            return Optional.empty();
        }
    }
}
