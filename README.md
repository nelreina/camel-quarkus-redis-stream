# Camel Quarkus Redis Stream Component

[![GitHub Packages](https://img.shields.io/badge/GitHub%20Packages-1.4.0-blue)](https://github.com/nelreina/camel-quarkus-redis-stream/packages)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

Apache Camel component for Quarkus that provides Redis Stream integration capabilities for event-driven microservice architectures.

## Overview

This component enables seamless Redis Stream consumption and production through Apache Camel routes in Quarkus applications. It supports consumer groups for scalable message processing, event filtering, and automatic message acknowledgment.

## Features

- 🚀 **Native Quarkus Integration** - Built specifically for Quarkus with native compilation support
- 📡 **Consumer Groups** - Scalable message consumption with Redis consumer groups
- 🎯 **Event Filtering** - Process only specific event types with comma-separated filters
- 🔍 **Header-Based Filtering** - Filter messages by header key-value pairs for precise message routing
- 🌐 **Global Header Filters** - Configure service-wide header filters via application properties
- 🔄 **Auto-Acknowledgment** - Configurable automatic message acknowledgment
- 🏗️ **Builder Pattern** - EventData model with fluent builder API
- 🆕 **Stream Auto-Creation** - Automatically create Redis streams if they don't exist
- ⚡ **High Performance** - Non-blocking I/O with Lettuce Redis client
- 🔧 **Flexible Configuration** - URI parameters and application properties support
- 📍 **Group start at an activation id** - `XGROUP CREATE … MKSTREAM` at `groupStartId` (1.4.0)
- ♻️ **PEL recovery** - bounded drain of the consumer's own pending entries, periodic `XAUTOCLAIM`, parking of poison entries (1.4.0)
- 🏷️ **Stable consumer names** - `ConsumerSlot`: `<service>-<n>` per pod via a fenced Redis lease (1.4.0)
- 🔁 **Hot tenant routes** - `TenantRouteManager` adds/removes per-tenant routes from the config snapshot, woken by the control stream (1.4.0)

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
    <version>1.4.0</version>
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
camel.component.redis-stream.redis-hosts=redis://localhost:6379
camel.component.redis-stream.redis-password=<password>

# Component defaults (optional)
camel.component.redis-stream.consumer-group-prefix=myapp
camel.component.redis-stream.auto-create-groups=true
camel.component.redis-stream.default-block-timeout=5000
camel.component.redis-stream.max-messages=10
camel.component.redis-stream.auto-ack=true

# Global header filters (optional) - applies to all consumers
camel.component.redis-stream.global-header-filters=environment=production,datacenter=us-east-1

# 1.4.0 component defaults (optional; URI parameters override them)
camel.component.redis-stream.group-start-id=$
camel.component.redis-stream.auto-claim-min-idle-ms=60000
camel.component.redis-stream.auto-claim-every-ms=60000
camel.component.redis-stream.drain-max-deliveries=5
camel.component.redis-stream.stop-timeout-ms=30000
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
| `consumerName` | String | ❌ | supplier / auto-generated | Consumer name within the group. When unset: the component-level `ConsumerNameSupplier` (e.g. `ConsumerSlot`), else `{group}-{hostname}-{n}` |
| `maxMessages` | int | ❌ | 10 | Maximum messages per poll (`XREADGROUP COUNT`) |
| `blockTimeout` | int | ❌ | 1000 | Not used for reads: all routes share one connection, so reads are non-blocking |
| `groupStartId` | String | ❌ | `$` | Id the group is created at (`XGROUP CREATE … MKSTREAM`); `0` = whole stream, `<ms>-0` = activation point |
| `drainEnabled` | boolean | ❌ | true | Drain this consumer's own PEL (moving cursor, bounded by the XPENDING count) on the first poll |
| `drainBatch` | int | ❌ | 50 | COUNT per drain read |
| `drainMaxDeliveries` | int | ❌ | 5 | Entries delivered more than this, or failing on that last delivery, are parked; `0` disables parking |
| `parkedStream` | String | ❌ | `ynohub:parked:<group>` | Stream receiving parked entries (`MAXLEN ~ 10000`) |
| `autoClaimMinIdleMs` | long | ❌ | 60000 | `XAUTOCLAIM` min idle time |
| `autoClaimEveryMs` | long | ❌ | 60000 | Interval of the `XAUTOCLAIM` pass (also runs on the first poll); `0` disables the periodic pass |
| `autoClaimCount` | int | ❌ | 100 | COUNT per `XAUTOCLAIM` call |
| `deleteIdleConsumersMs` | long | ❌ | 3600000 | During the `XAUTOCLAIM` pass, `XGROUP DELCONSUMER` other consumers with an empty PEL idle longer than this; `0` disables |
| `stopTimeoutMs` | long | ❌ | 30000 | How long a stop waits for the in-flight poll |
| `pollingInterval` | int | ❌ | 100 | Polling interval in milliseconds |
| `autoAck` | boolean | ❌ | true | Auto-acknowledge messages |
| `autoCreateStreams` | boolean | ❌ | true | Auto-create Redis streams if they don't exist |
| `startId` | String | ❌ | ">" | Stream start position |
| `payloadType` | enum | ❌ | STRING | Expected payload type (STRING, MAP, OBJECT) |
| `objectClass` | String | ❌ | - | Target class for OBJECT payload type |
| `serviceName` | String | ❌ | - | Service name for produced messages |
| `headerFilters` | String | ❌ | - | Comma-separated header filters (key1=value1,key2=value2) |

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

### Consumer with Header Filtering

```java
// Only process OrderCreated events with region=US and priority=high headers
from("redis-stream://order-events?" +
     "group=regional-processor&" +
     "events=OrderCreated&" +
     "headerFilters=region=US,priority=high")
    .log("Processing high priority US order: ${body.aggregateId}")
    .to("bean:orderService?method=processHighPriorityUSOrder");

// Multiple consumers with different header filters
from("redis-stream://order-events?" +
     "group=eu-processor&" +
     "events=OrderCreated&" +
     "headerFilters=region=EU")
    .to("bean:euOrderService?method=processOrder");

from("redis-stream://order-events?" +
     "group=expedited-processor&" +
     "events=OrderCreated,OrderUpdated&" +
     "headerFilters=shippingType=expedited")
    .to("bean:expeditedService?method=handleExpeditedOrder");
```

### Global vs Route-Level Header Filtering

Header filters can be configured at two levels:

1. **Global Level** - Applied to all consumers via application properties
2. **Route Level** - Specified in the URI parameters

When both are present, they are merged with route-level filters taking precedence:

```properties
# Global configuration - applies to all consumers
camel.component.redis-stream.global-header-filters=environment=production,region=US
```

```java
// Route 1: Inherits global filters (environment=production,region=US)
from("redis-stream://events?group=service1&events=UserCreated")
    .to("bean:service1");

// Route 2: Overrides region filter, keeps environment filter
from("redis-stream://events?group=service2&events=UserCreated&headerFilters=region=EU")
    .to("bean:service2");
// Effective filters: environment=production,region=EU

// Route 3: Adds additional filter to global ones
from("redis-stream://events?group=service3&events=UserCreated&headerFilters=priority=high")
    .to("bean:service3");
// Effective filters: environment=production,region=US,priority=high
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
  headers "{\"correlationId\":\"abc-def-123\",\"mimeType\":\"application/json\",\"region\":\"US\"}"
```

**Note**: Starting from v1.2.1, the `headers` field is JSON-serialized for better structure and reliability. The consumer automatically deserializes this field using Jackson ObjectMapper.

## Consumer Groups and Scaling

The component automatically creates consumer groups if they don't exist:

```java
// Multiple consumers in the same group for load balancing
from("redis-stream://events?group=email-service&events=UserRegistered")
    .to("bean:emailService?method=sendWelcomeEmail");

from("redis-stream://events?group=analytics-service&events=UserRegistered")
    .to("bean:analyticsService?method=trackUserSignup");
```

Consumer name precedence: `consumerName` URI parameter → component-level `ConsumerNameSupplier` →
generated `{group}-{hostname}-{instance}` (one new name per route and per restart).

### Stable consumer names: `ConsumerSlot` (1.4.0)

`ConsumerSlot` gives every pod one bounded, restart-stable name `<service>-<n>` shared by all its routes:

- claim: lowest `n` with `SET ynohub:config:consumer-slot:<svc>:<n> <HOSTNAME> NX PX 30000`
- renew every 10 s with a fenced Lua script (`PEXPIRE` only while the value is still this pod)
- lease lapsed → `SET NX` on the same slot; taken by another pod → every supplier-named consumer stops,
  a new slot is claimed, the consumers restart under the new name; no free slot → `Quarkus.asyncExit(1)`
- env `CONSUMER_NAME` wins over the lease; the component releases the slot (fenced `DEL`) on shutdown after its routes stopped

Enable the bundled CDI producer at build time:

```properties
redis-stream.consumer-slot.enabled=true
# default: env SERVICE_NAME
redis-stream.consumer-slot.service-name=yno-swift-mt-parser
```

or bind your own `ConsumerNameSupplier` bean / call `component.setConsumerNameSupplier(...)`. Do not put
`consumerName=` on the URIs then: supplier-named consumers are the ones restarted on a slot change.

## Start sequence, failures and parking (1.4.0)

1. `XGROUP CREATE <stream> <group> <groupStartId> MKSTREAM` (BUSYGROUP ignored; no dummy `XADD`/`XDEL`).
2. First poll: drain the consumer's own PEL with a moving cursor (`XREADGROUP … STREAMS key <lastId>` from `0`),
   bounded by its XPENDING count.
3. `XAUTOCLAIM` entries idle ≥ `autoClaimMinIdleMs` (orphans of a crashed pod and this consumer's own failed
   entries); repeated every `autoClaimEveryMs` on the same non-blocking connection.
4. Then `XREADGROUP COUNT maxMessages … >`.

A message whose processing throws stays pending and is retried by `XAUTOCLAIM`. On its `drainMaxDeliveries`-th
delivery a failure parks it: `XADD <parkedStream> MAXLEN ~ 10000` with the original fields plus
`streamKey, group, consumer, entryId, deliveries, reason`, then `XACK`. A pending entry that was deleted from
the stream is acked. Exchanges whose exception was handled by the route's error handler count as processed
(unchanged from 1.3.x).

Stop (route stop, `stopRoute`, shutdown) no longer interrupts a poll: no new polls start, the in-flight message
finishes and is acked, remaining entries of that batch stay in the consumer's PEL for the next start.

**Behaviour changes vs 1.3.x**: new groups start at `$` (was `0-0`: replayed the whole stream); set
`groupStartId=0` to keep the old behaviour. `maxMessages` is now applied. Failing entries are retried and
eventually parked instead of staying pending forever. Exchange headers `RedisStreamConsumer` and
`RedisStreamDeliveries` are added.

## Hot tenant routes: `TenantRouteManager` (1.4.0)

Package `tech.nelreina.camel.quarkus.redis.stream.tenant`. The config snapshot in Redis is the truth; the
control stream `ynohub:config:events` only wakes the reconcile.

```java
TenantRouteSpec spec = new TenantRouteSpec(
        bic -> new BankRoutes(bic),               // RouteBuilder per tenant; define the redis-stream route last
        bic -> protocolsJson(bic))                // fingerprint: changed → routes rebuilt
    .stream(bic -> "ynohub:events:" + bic, "yno-swift-mt-parser") // group created at tenant-activated:<bic>
    .onTenantAdded(bic -> openTenantDb(bic))      // throws → tenant degraded, retried 1 s … 60 s
    .onTenantRemoved(bic -> closeTenantDb(bic));

TenantRouteManager manager = TenantRouteManager.builder(camelContext, spec)
    .serviceName("yno-swift-mt-parser")           // default env SERVICE_NAME
    .build();
camelContext.addService(manager, true, true);    // or manager.start() / manager.stop()
```

- start: consumer name (slot) → `XREVRANGE events + - COUNT 1` → `reconcile("boot")` → `ConfigEventsTailer`
  (own connection, `XREAD BLOCK 5000`), backstop reconcile every `CONFIG_RECONCILE_SEC` (60), heartbeat every 30 s.
- reconcile: `MGET cache:status cache:generation host-banks:active`; empty list → WARN, nothing evicted; added →
  ensure group at `tenant-activated:<bic>` (else `$`) → `onTenantAdded` → `addRoutes`; removed → redis-stream
  routes stopped first → `removeRoute` → `onTenantRemoved` (group and PEL kept); generation lower than seen → full reconcile.
- heartbeat: `HSET ynohub:config:applied:<svc>:<consumerName> {generation, tenants, degraded, hotReload, consumerName, ts}` `EX 120`.
- `tenants()`, `liveStreams()`, `status()` (for `/health-check`), `reconcile(reason)`, `requestReconcile(reason)`, `stopAll()`.
- `RedisStreamComponent.getRedisClient()` opens dedicated connections; the shared one must never block.

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

## Tests of this repository

`mvn test` runs the integration tests against a real Redis: `-Dredis.test.url=redis://host:port`
(default `redis://localhost:6379`); they are skipped when Redis is unreachable. Keys live under a random
`test:<id>:` prefix and are removed afterwards.

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
| 1.4.x                     | 3.12.x  | 3.2.x         | 17+  |

Redis ≥ 6.2 (XAUTOCLAIM, XGROUP CREATE … MKSTREAM).

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
