package tech.nelreina.camel.quarkus.redis.stream.slot;

/**
 * Notified when a {@link ConsumerSlot} lease was lost and the slot was taken by another pod.
 *
 * <p>Sequence: {@link #beforeReclaim} for every listener (registration order) &rarr; a new slot is
 * claimed &rarr; {@link #afterReclaim} for every listener (reverse order). Consumers must stop in
 * {@code beforeReclaim}: never keep consuming under a name this pod no longer holds.
 */
public interface ConsumerSlotListener {

    /** The lease on {@code lostName} is gone and another pod holds it. Stop consuming now. */
    default void beforeReclaim(String lostName) {
    }

    /**
     * A new slot was claimed. {@code newName} is null when no slot could be claimed
     * (the slot then triggers its fatal handler).
     */
    default void afterReclaim(String lostName, String newName) {
    }
}
