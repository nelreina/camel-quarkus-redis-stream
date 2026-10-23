package tech.nelreina.camel.quarkus.redis.stream.slot;

/**
 * Supplies the consumer name used by every redis-stream consumer that does not set
 * {@code consumerName} on its URI.
 *
 * <p>The {@code RedisStreamComponent} resolves one bean of this type from the Camel registry
 * (CDI in Quarkus) once per component, and asks it for the name each time a consumer starts.
 * {@link ConsumerSlot} is the standard implementation: {@code <serviceName>-<n>} backed by a
 * fenced lease in Redis.
 */
public interface ConsumerNameSupplier {

    /** Current consumer name. Must be cheap: it is called on every consumer start. */
    String consumerName();

    /** Called by the component on shutdown, after its consumers have stopped. Must be idempotent. */
    default void release() {
    }
}
