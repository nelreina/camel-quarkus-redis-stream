package tech.nelreina.camel.quarkus.redis.stream.slot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;

/** Service-name resolution of the opt-in consumer slot (1.4.1): no reliance on an unexpanded default. */
class ConsumerSlotProducerTest {

    private static Config config(Map<String, String> props) {
        return new SmallRyeConfigBuilder()
            .withSources(new PropertiesConfigSource(props, "test", 500))
            .build();
    }

    private static Function<String, Optional<String>> lookup(Config config) {
        return name -> ConsumerSlotProducer.configValue(config, name);
    }

    private static final Function<String, String> NO_ENV = name -> null;

    @Test
    void explicitPropertyWins() {
        Config c = config(Map.of("redis-stream.consumer-slot.service-name", "explicit", "SERVICE_NAME", "from-config"));
        assertEquals("explicit", ConsumerSlotProducer.resolveServiceName(lookup(c), name -> "from-env"));
    }

    @Test
    void propertyReferencingServiceNameIsExpanded() {
        Config c = config(Map.of("redis-stream.consumer-slot.service-name", "${SERVICE_NAME}", "SERVICE_NAME", "yno-swift-mt-parser"));
        assertEquals("yno-swift-mt-parser", ConsumerSlotProducer.resolveServiceName(lookup(c), NO_ENV));
    }

    @Test
    void withoutThePropertyServiceNameFromConfigIsUsed() {
        Config c = config(Map.of("SERVICE_NAME", "yno-swift-mx-parser"));
        assertEquals("yno-swift-mx-parser", ConsumerSlotProducer.resolveServiceName(lookup(c), NO_ENV));
    }

    @Test
    void withoutThePropertyTheEnvironmentIsUsed() {
        Config c = config(Map.of());
        assertEquals("svc-env", ConsumerSlotProducer.resolveServiceName(lookup(c), name -> "SERVICE_NAME".equals(name) ? " svc-env " : null));
    }

    @Test
    void unresolvableExpressionFallsBackToTheEnvironment() {
        // property set to ${SERVICE_NAME} while SERVICE_NAME is not in config: expansion fails, env still wins
        Config c = config(Map.of("redis-stream.consumer-slot.service-name", "${SERVICE_NAME}"));
        assertEquals("svc-env", ConsumerSlotProducer.resolveServiceName(lookup(c), name -> "svc-env"));
    }

    @Test
    void blankOrLiteralValuesCountAsMissing() {
        Map<String, String> props = new HashMap<>();
        props.put("redis-stream.consumer-slot.service-name", "   ");
        Function<String, Optional<String>> literal = name -> Optional.of("${SERVICE_NAME:}");
        assertEquals("svc-env", ConsumerSlotProducer.resolveServiceName(lookup(config(props)), name -> "svc-env"));
        assertEquals("svc-env", ConsumerSlotProducer.resolveServiceName(literal, name -> "svc-env"));
    }

    @Test
    void failsFastWithAClearMessage() {
        Config c = config(Map.of("redis-stream.consumer-slot.service-name", "${SERVICE_NAME}"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> ConsumerSlotProducer.resolveServiceName(lookup(c), NO_ENV));
        assertTrue(e.getMessage().contains("redis-stream.consumer-slot.service-name"), e.getMessage());
        assertTrue(e.getMessage().contains("SERVICE_NAME"), e.getMessage());
    }
}
