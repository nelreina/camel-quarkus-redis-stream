package tech.nelreina.camel.quarkus.redis.stream;

import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * 1.4.1: the library jar ships no application.properties. Quarkus loads every application.properties on the
 * classpath at ordinal 250, so one inside the jar overrode the consuming service's own settings (log levels).
 */
class PackagedResourcesTest {

    @Test
    void noApplicationPropertiesOnTheLibraryClasspath() {
        assertNull(getClass().getClassLoader().getResource("application.properties"),
            "src/main/resources/application.properties must not exist");
    }
}
