# Camel Quarkus Redis Stream Component

[![GitHub Packages](https://img.shields.io/badge/GitHub%20Packages-1.0.0-blue)](https://github.com/nelreina/camel-quarkus-redis-stream/packages)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

Apache Camel component for Quarkus that provides Redis Stream integration capabilities for event-driven microservice architectures.

## Overview

This component enables seamless Redis Stream consumption and production through Apache Camel routes in Quarkus applications. It supports consumer groups for scalable message processing, event filtering, and automatic message acknowledgment.

## Features

- 🚀 **Native Quarkus Integration** - Built specifically for Quarkus with native compilation support
- 📡 **Consumer Groups** - Scalable message consumption with Redis consumer groups
- 🎯 **Event Filtering** - Process only specific event types with comma-separated filters
- 🔄 **Auto-Acknowledgment** - Configurable automatic message acknowledgment
- 🏗️ **Builder Pattern** - EventData model with fluent builder API
- 🆕 **Stream Auto-Creation** - Automatically create Redis streams if they don't exist
- ⚡ **High Performance** - Non-blocking I/O with Lettuce Redis client
- 🔧 **Flexible Configuration** - URI parameters and application properties support

## Installation

### 1. Add GitHub Packages Repository
Add to your `pom.xml`:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/nelreina/camel-quarkus-redis-stream</url>
    </repository>
</repositories>
```

### 2. Add Dependency
```xml
<dependency>
    <groupId>tech.nelreina</groupId>
    <artifactId>camel-quarkus-redis-stream</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 3. Configure Authentication
Add to your `~/.m2/settings.xml`:

```xml
<settings>
    <servers>
        <server>
            <id>github</id>
            <username>YOUR_GITHUB_USERNAME</username>
            <password>YOUR_GITHUB_TOKEN</password>
        </server>
    </servers>
</settings>
```

**Note**: You need a GitHub Personal Access Token with `read:packages` scope. See [docs/GITHUB_PACKAGES.md](docs/GITHUB_PACKAGES.md) for details.

## Configuration

### Application Properties

Configure Redis connection in `application.properties`:

```properties
# Redis connection
quarkus.redis.hosts=redis://localhost:6379
quarkus.redis.password=your-password

# Component defaults (optional)
camel.component.redis-stream.consumer-group-prefix=myapp
camel.component.redis-stream.auto-create-groups=true
camel.component.redis-stream.default-block-timeout=5000
camel.component.redis-stream.max-messages=10
camel.component.redis-stream.auto-ack=true
```

### URI Format

```
redis-stream://streamKeyName?group=groupName&events=event1,event2[&options]
```

## URI Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `group` | String | ✅ | - | Consumer group name |
| `events` | String | ✅ | - | Comma-separated list of event types to filter |
| `consumerName` | String | ❌ | auto-generated | Consumer name within the group |
| `maxMessages` | int | ❌ | 10 | Maximum messages per poll |
| `blockTimeout` | int | ❌ | 1000 | Block timeout in milliseconds |
| `pollingInterval` | int | ❌ | 100 | Polling interval in milliseconds |
| `autoAck` | boolean | ❌ | true | Auto-acknowledge messages |
| `autoCreateStreams` | boolean | ❌ | true | Auto-create Redis streams if they don't exist |
| `startId` | String | ❌ | ">" | Stream start position |
| `payloadType` | enum | ❌ | STRING | Expected payload type (STRING, MAP, OBJECT) |
| `objectClass` | String | ❌ | - | Target class for OBJECT payload type |
| `serviceName` | String | ❌ | - | Service name for produced messages |

## Usage Examples

### Basic Consumer

```java
@ApplicationScoped
public class OrderEventRoutes extends RouteBuilder {

    @Override
    public void configure() {
        from("redis-stream://order-events?group=inventory-service&events=OrderCreated,OrderCancelled")
            .log("Processing event: ${body.event} for aggregate: ${body.aggregateId}")
            .choice()
                .when(simple("${body.event} == 'OrderCreated'"))
                    .to("bean:inventoryService?method=reserveItems")
                .when(simple("${body.event} == 'OrderCancelled'"))
                    .to("bean:inventoryService?method=releaseItems")
            .end();
    }
}
```

### Producer with EventData

```java
from("direct:publish-user-event")
    .process(exchange -> {
        EventData eventData = EventData.builder()
            .aggregateId(exchange.getIn().getHeader("userId", String.class))
            .event("UserUpdated")
            .payload(exchange.getIn().getBody())
            .serviceName("user-service")
            .timestamp(Instant.now())
            .header("correlationId", UUID.randomUUID().toString())
            .build();
        exchange.getIn().setBody(eventData);
    })
    .to("redis-stream://user-events");
```

### Producer with Headers

```java
from("direct:publish-simple")
    .setHeader("event", constant("ProductCreated"))
    .setHeader("aggregateId", simple("${body.id}"))
    .setHeader("serviceName", constant("product-service"))
    .to("redis-stream://product-events");
```

### Advanced Consumer Configuration

```java
from("redis-stream://payment-events?" +
     "group=accounting-service&" +
     "events=PaymentProcessed,PaymentFailed&" +
     "consumerName=accounting-worker-1&" +
     "maxMessages=5&" +
     "blockTimeout=2000&" +
     "autoAck=false")
    .process(exchange -> {
        EventData eventData = exchange.getIn().getBody(EventData.class);
        // Process the event
        processPaymentEvent(eventData);
    })
    .process(exchange -> {
        // Manual acknowledgment
        String messageId = exchange.getIn().getHeader("RedisStreamId", String.class);
        // Acknowledge manually if processing was successful
    });
```

## EventData Model

The `EventData` class represents a Redis Stream message with the following structure:

```java
EventData eventData = EventData.builder()
    .keyId("1234567890-0")           // Redis stream message ID (auto-set)
    .aggregateId("user-123")         // Business aggregate identifier
    .timestamp(Instant.now())        // Event timestamp
    .event("UserCreated")            // Event type
    .payload(userObject)             // Event payload (String, Map, or Object)
    .serviceName("user-service")     // Originating microservice
    .header("correlationId", "abc")  // Custom headers
    .build();
```

### EventData Properties

- **keyId**: Redis stream message ID (automatically set by Redis)
- **aggregateId**: Business entity identifier for event correlation
- **timestamp**: Event occurrence timestamp (defaults to current time)
- **event**: Event type used for filtering and routing
- **payload**: Event data (automatically serialized to JSON for objects)
- **serviceName**: Name of the service that produced the event
- **headers**: Additional metadata as key-value pairs

## Redis Stream Message Format

Messages are stored in Redis Stream with the following fields:

```
XADD order-events * 
  aggregateId "order-123"
  timestamp "2024-06-16T10:30:00.000Z" 
  event "OrderCreated"
  payload "{\"orderId\":\"order-123\",\"amount\":99.99}"
  serviceName "order-service"
  mimeType "application/json"
  correlationId "abc-def-123"
```

## Consumer Groups and Scaling

The component automatically creates consumer groups if they don't exist:

```java
// Multiple consumers in the same group for load balancing
from("redis-stream://events?group=email-service&events=UserRegistered")
    .to("bean:emailService?method=sendWelcomeEmail");

from("redis-stream://events?group=analytics-service&events=UserRegistered")
    .to("bean:analyticsService?method=trackUserSignup");
```

Consumer names are auto-generated as: `{group}-{hostname}-{instance}` or you can specify custom names.

## Error Handling

```java
from("redis-stream://orders?group=fulfillment&events=OrderCreated")
    .onException(Exception.class)
        .handled(true)
        .log("Failed to process order: ${exception.message}")
        .to("direct:dead-letter-queue")
    .end()
    .to("bean:fulfillmentService?method=processOrder");
```

## Testing with TestContainers

```java
@QuarkusTest
@TestProfile(RedisStreamTestProfile.class)
public class RedisStreamComponentTest {

    @Test
    public void testConsumerProducer() {
        // Test your Redis Stream routes
    }
}

public class RedisStreamTestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("quarkus.redis.hosts", "redis://localhost:6380");
    }
}
```

## Performance Considerations

- **Batch Processing**: Adjust `maxMessages` parameter based on throughput requirements
- **Consumer Groups**: Use multiple consumers in the same group for horizontal scaling
- **Connection Pooling**: Lettuce automatically manages connection pooling
- **Memory Management**: Consider Redis Stream MAXLEN for memory management

## Monitoring

The component provides headers for monitoring and observability:

```java
from("redis-stream://events?group=monitor&events=*")
    .process(exchange -> {
        String streamKey = exchange.getIn().getHeader("RedisStreamKey", String.class);
        String messageId = exchange.getIn().getHeader("RedisStreamId", String.class);
        String consumerGroup = exchange.getIn().getHeader("ConsumerGroup", String.class);
        // Log or send to monitoring system
    });
```

## Build and Development

### Prerequisites

- Java 17+
- Maven 3.8+
- Redis Server 5.0+

### Build

```bash
mvn clean compile
mvn test
mvn package
```

### Native Build

```bash
mvn package -Pnative
```

## Version Compatibility

| camel-quarkus-redis-stream | Quarkus | Camel-Quarkus | Java |
|---------------------------|---------|---------------|------|
| 1.0.x                     | 3.12.x  | 3.2.x         | 17+  |

## Contributing

1. Fork the repository
2. Create a feature branch
3. Add tests for new functionality
4. Ensure all tests pass
5. Submit a pull request

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Support

- 📧 Email: contact@nelreina.tech
- 🐛 Issues: [GitHub Issues](https://github.com/nelreina/camel-quarkus-redis-stream/issues)
- 📖 Documentation: [Project Wiki](https://github.com/nelreina/camel-quarkus-redis-stream/wiki)

## Acknowledgments

- Apache Camel team for the excellent integration framework
- Quarkus team for the cloud-native Java platform
- Redis Labs for Redis Streams functionality