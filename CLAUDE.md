# CLAUDE.md

## Project Overview
**camel-quarkus-redis-stream** is an Apache Camel component extension for Quarkus that provides Redis Stream integration capabilities for event-driven microservice architectures.

**Primary Purpose**: Enable seamless Redis Stream consumption and production through Apache Camel routes in Quarkus applications

**Target Audience**: Java developers building event-driven microservices with Quarkus and Apache Camel

**Maven Coordinates**: `tech.nelreina:camel-quarkus-redis-stream`

## Architecture & Design Decisions

### Tech Stack
- **Language**: Java 17+
- **Framework**: Quarkus 3.x
- **Integration**: Apache Camel 4.x (Quarkus extension)
- **Redis Client**: Lettuce (via Quarkus Redis extension)
- **Build Tool**: Maven
- **Testing**: JUnit 5, TestContainers, Quarkus Test Framework
- **Documentation**: Maven Site Plugin, AsciiDoc

### Project Structure
```
src/
├── main/
│   ├── java/
│   │   └── tech/nelreina/camel/quarkus/redis/stream/
│   │       ├── component/
│   │       │   ├── RedisStreamComponent.java
│   │       │   ├── RedisStreamEndpoint.java
│   │       │   └── RedisStreamConfiguration.java
│   │       ├── consumer/
│   │       │   ├── RedisStreamConsumer.java
│   │       │   └── RedisStreamPollingConsumer.java
│   │       ├── producer/
│   │       │   └── RedisStreamProducer.java
│   │       ├── model/
│   │       │   ├── EventData.java
│   │       │   └── RedisStreamMessage.java
│   │       ├── converter/
│   │       │   ├── EventDataConverter.java
│   │       │   └── PayloadTypeConverter.java
│   │       ├── exception/
│   │       │   ├── RedisStreamException.java
│   │       │   └── EventDataConversionException.java
│   │       └── util/
│   │           ├── ConsumerNameGenerator.java
│   │           └── RedisStreamUtils.java
│   └── resources/
│       ├── META-INF/
│       │   └── services/
│       │       └── org/apache/camel/component/redis-stream
│       └── application.properties
├── test/
│   ├── java/
│   │   └── tech/nelreina/camel/quarkus/redis/stream/
│   │       ├── integration/
│   │       │   ├── RedisStreamComponentIT.java
│   │       │   └── RedisStreamEndpointIT.java
│   │       ├── unit/
│   │       │   ├── ConsumerNameGeneratorTest.java
│   │       │   ├── EventDataConverterTest.java
│   │       │   └── RedisStreamUtilsTest.java
│   │       └── testcontainers/
│   │           └── RedisStreamTestResource.java
│   └── resources/
│       └── application-test.properties
└── docs/
    ├── component-usage.adoc
    ├── configuration-reference.adoc
    └── examples/
```

### Component Design Patterns
- **Component-Endpoint-Producer/Consumer Pattern**: Standard Camel component architecture
- **Builder Pattern**: For EventData object construction
- **Factory Pattern**: For consumer name generation
- **Strategy Pattern**: For payload type conversion (String, Map, Custom Object)
- **Template Method**: For Redis Stream operations

## Component Specification

### URI Format
```
redis-stream://streamKeyName?group=groupName&events=eventsCommaSeparated[&options]
```

### Required Parameters
- **streamKeyName**: Redis stream key name
- **group**: Consumer group name
- **events**: Comma-separated list of event types to filter

### Optional Parameters
- **consumerName**: Override auto-generated consumer name (default: {group}-{instanceId})
- **maxMessages**: Max messages per poll (default: 10)
- **blockTimeout**: Block timeout in milliseconds (default: 1000)
- **autoAck**: Auto-acknowledge messages (default: true)
- **startId**: Stream start position (default: ">")
- **payloadType**: Expected payload type (STRING, MAP, OBJECT)
- **objectClass**: Target class for OBJECT payload type

### EventData Model
```java
public class EventData {
    private String keyId;           // Redis stream message ID
    private String aggregateId;     // Business aggregate identifier
    private Instant timestamp;      // Event timestamp
    private String event;           // Event type
    private Object payload;         // Event payload (String, Map, or Custom Object)
    private String serviceName;    // Originating microservice name
    private Map<String, Object> headers; // Additional metadata (supports JSON parsing)
}
```

### Consumer Naming Convention
- Pattern: `{groupName}-{instanceId}`
- Example: `group=email-service` → `consumer-name=email-service-1`
- Instance ID generation: Sequential numbering or UUID-based

## Development Guidelines

### Code Style
- Follow Google Java Style Guide
- Use Quarkus coding conventions
- Camel component naming: `redis-stream`
- Package naming: `tech.nelreina.camel.quarkus.redis.stream`
- Class naming: CamelCase with descriptive names
- Method naming: camelCase, action-oriented verbs

### Error Handling Strategy
- **RedisStreamException**: Component-specific exceptions
- **EventDataConversionException**: Payload conversion errors  
- **ConnectionException**: Redis connectivity issues
- **ConfigurationException**: Invalid component configuration
- Implement Camel's ExceptionHandler for graceful error recovery
- Use dead letter queues for failed message processing

### Testing Strategy
- **Unit Tests**: Component logic, converters, utilities (90%+ coverage)
- **Integration Tests**: Redis interaction with TestContainers
- **Quarkus Tests**: Component registration and DI
- **Performance Tests**: High-throughput scenarios
- **Contract Tests**: EventData serialization/deserialization

### Configuration Management
- Support both URI parameters and component-level configuration
- Quarkus ConfigProperty integration
- Environment-specific profiles (dev, test, prod)
- Configuration validation at startup

## Redis Stream Integration

### Consumer Implementation
- Use Redis Consumer Groups for scalability
- Implement backpressure handling
- Support for manual and auto-acknowledgment
- Dead letter handling for processing failures
- Graceful shutdown with message completion
- Jackson ObjectMapper for JSON header deserialization
- Backward compatibility for non-JSON header fields

### Producer Implementation  
- Batch message support for performance
- Message ordering guarantees
- Retry mechanism for failed sends
- Connection pooling optimization

### Message Format
Redis Stream Entry:
```
XADD mystream * 
  aggregateId "user-123"
  timestamp "1718539800000" 
  event "UserCreated"
  payload "{\"name\":\"John\",\"email\":\"john@example.com\"}"
  serviceName "user-service"
  headers "{\"correlationId\":\"abc-123\",\"priority\":\"high\",\"region\":\"US\"}"
  customHeader "value"
```

**Note**: The `headers` field is JSON-serialized and parsed automatically by the consumer using Jackson ObjectMapper. Additional non-standard fields are still added as individual headers for backward compatibility.

## Maven Publishing Configuration

### POM Configuration
```xml
<groupId>tech.nelreina</groupId>
<artifactId>camel-quarkus-redis-stream</artifactId>
<version>1.0.0-SNAPSHOT</version>
<packaging>jar</packaging>

<properties>
    <quarkus.version>3.12.0</quarkus.version>
    <camel-quarkus.version>3.2.0</camel-quarkus.version>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
</properties>
```

### Distribution Management
- **Snapshots**: Maven Central OSSRH snapshots
- **Releases**: Maven Central via OSSRH
- **Documentation**: GitHub Pages
- **Source/Javadoc**: Attached to releases

### Required Maven Plugins
- **maven-compiler-plugin**: Java 17 compilation
- **maven-surefire-plugin**: Unit test execution
- **maven-failsafe-plugin**: Integration test execution  
- **maven-source-plugin**: Source jar generation
- **maven-javadoc-plugin**: API documentation
- **maven-gpg-plugin**: Artifact signing for Central
- **nexus-staging-maven-plugin**: OSSRH deployment

## Quarkus Integration

### Extension Metadata
- Extension name: "Camel Redis Stream"
- Extension description: "Apache Camel Redis Stream component for Quarkus"
- Guide reference: Link to usage documentation
- Categories: messaging, integration, data

### Build-time Configuration
- Component auto-discovery through META-INF/services
- Native image configuration for reflection
- Quarkus extension descriptor

### Runtime Configuration  
```properties
# Redis connection
quarkus.redis.hosts=redis://localhost:6379
quarkus.redis.client-type=standalone

# Component defaults
camel.component.redis-stream.consumer-group-prefix=app
camel.component.redis-stream.auto-create-groups=true
camel.component.redis-stream.default-block-timeout=5000
```

## Performance Considerations

### Throughput Optimization
- Connection pooling (Lettuce async)
- Batch message processing
- Non-blocking I/O operations
- Configurable poll intervals
- Efficient payload serialization

### Memory Management
- Bounded message queues
- Stream-based processing for large payloads
- Garbage collection optimization
- Resource cleanup on shutdown

### Scalability Features
- Horizontal scaling via consumer groups
- Load balancing across consumers  
- Backpressure handling
- Circuit breaker integration

## Monitoring & Observability

### Metrics (Micrometer)
- Messages consumed/produced per second
- Processing latency percentiles
- Error rates by exception type
- Consumer lag monitoring
- Connection pool statistics

### Health Checks
- Redis connectivity check
- Consumer group status
- Stream existence validation
- Component configuration validation

### Logging Strategy
- Structured logging (JSON format)
- Configurable log levels per package
- Request correlation IDs
- Performance timing logs
- Error context preservation

## Usage Examples

### Basic Consumer
```java
from("redis-stream://order-events?group=inventory-service&events=OrderCreated,OrderCancelled")
  .log("Processing event: ${body.event} for aggregate: ${body.aggregateId}")
  .choice()
    .when(simple("${body.event} == 'OrderCreated'"))
      .to("bean:inventoryService?method=reserveItems")
    .when(simple("${body.event} == 'OrderCancelled'"))
      .to("bean:inventoryService?method=releaseItems")
  .end();
```

### Producer Pattern
```java
from("direct:publish-user-event")
  .setBody(exchange -> EventData.builder()
    .aggregateId(exchange.getIn().getHeader("userId", String.class))
    .event("UserUpdated")
    .payload(exchange.getIn().getBody())
    .serviceName("user-service")
    .timestamp(Instant.now())
    .build())
  .to("redis-stream://user-events");
```

## Deployment & Release Process

### Version Strategy
- Semantic Versioning (MAJOR.MINOR.PATCH)
- SNAPSHOT builds for development
- Release candidates for testing
- LTS versions for stability

### CI/CD Pipeline
1. **Build**: Compile, test, package
2. **Quality Gates**: Coverage, security scan, dependency check
3. **Integration Tests**: TestContainers Redis validation
4. **Documentation**: Generate and publish docs
5. **Release**: Deploy to Maven Central
6. **Notification**: Update changelog, notify users

### Compatibility Matrix
| camel-quarkus-redis-stream | Quarkus | Camel-Quarkus | Java |
|---------------------------|---------|---------------|------|
| 1.0.x                     | 3.12.x  | 3.2.x         | 17+  |
| 1.1.x                     | 3.13.x  | 3.3.x         | 17+  |

## Security Considerations

### Redis Security
- TLS/SSL connection support
- Redis AUTH authentication
- ACL user permissions
- Network security (VPN/firewall)

### Data Protection
- Sensitive data masking in logs
- Payload encryption options
- PII handling guidelines
- Audit trail maintenance

## Known Limitations
- Redis Cluster mode not fully supported in v1.0
- Maximum message size limited by Redis (512MB)
- Consumer group rebalancing requires manual intervention
- No built-in schema evolution support

## Future Enhancements
- Redis Cluster support
- Schema registry integration
- Dead letter queue automation
- Prometheus metrics out-of-the-box
- GraphQL subscription bridge
- Kubernetes operator for deployment

## Contributing Guidelines
- Fork and create feature branches
- Follow conventional commit messages
- Add tests for new functionality
- Update documentation
- Sign commits for security

## Version History

### v1.2.1 (July 9, 2025)
- Enhanced header parsing to support JSON-serialized headers
- Added Jackson ObjectMapper for reliable JSON parsing
- Improved backward compatibility for non-standard fields
- Fixed header deserialization issues

### v1.2.0 (July 9, 2025)
- Added header-based event filtering
- Added global header filters configurable via application properties
- Implemented filter merging logic (route overrides global)
- Enhanced logging to show effective filters

### v1.0.1
- Initial release with core functionality
- Redis Stream consumer/producer support
- Event filtering by event type
- Consumer group management

---

*Component Version: 1.2.1*  
*Last Updated: July 9, 2025*  
*Maintainer: nelreina.tech team*