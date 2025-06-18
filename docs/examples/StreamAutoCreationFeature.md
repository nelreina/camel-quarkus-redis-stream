# Stream Auto-Creation Feature - Version 1.0.2-SNAPSHOT

## New Feature Overview
**Automatic Redis Stream Creation** - When consuming from a Redis Stream that doesn't exist, the component can now automatically create an empty stream instead of throwing an error.

## Problem Solved
Previously, if you tried to consume from a non-existent Redis Stream:
```
RedisStreamException: Stream key 'my-stream' does not exist
```

Now, the component can automatically create the stream for you!

## How It Works

### 1. Stream Detection
When a consumer starts, it checks if the target stream exists:
```java
boolean streamExists = redisCommands.exists(streamKeyName) == 1;
```

### 2. Auto-Creation Process
If stream doesn't exist and auto-creation is enabled:
1. **Add dummy message** to create the stream: `XADD stream-name * _dummy true`
2. **Remove dummy message** to leave stream empty: `XDEL stream-name message-id` 
3. **Continue with normal consumer setup** (create consumer group, etc.)

### 3. Result
- ✅ Empty Redis Stream exists and ready for messages
- ✅ Consumer group created and ready to consume
- ✅ No error thrown, consumer starts successfully

## Configuration Options

### Component-Level Configuration
```properties
# Enable/disable stream auto-creation (default: true)
camel.component.redis-stream.auto-create-streams=true

# Also control consumer group auto-creation (default: true)
camel.component.redis-stream.auto-create-groups=true
```

### URI-Level Configuration
```java
// Enable both stream and group auto-creation (default)
from("redis-stream://my-stream?group=my-group&events=Event1,Event2")

// Disable stream auto-creation
from("redis-stream://my-stream?group=my-group&events=Event1,Event2&autoCreateStreams=false")

// Disable both stream and group auto-creation
from("redis-stream://my-stream?group=my-group&events=Event1,Event2&autoCreateStreams=false&autoCreateGroups=false")
```

### Programmatic Configuration
```java
@PostConstruct
public void configureComponent() {
    RedisStreamComponent component = camelContext.getComponent("redis-stream", RedisStreamComponent.class);
    component.setAutoCreateStreams(true);  // Enable stream auto-creation
    component.setAutoCreateGroups(true);   // Enable group auto-creation
}
```

## Usage Examples

### Example 1: Basic Auto-Creation (Default Behavior)
```java
@ApplicationScoped
public class OrderRoutes extends RouteBuilder {
    @Override
    public void configure() throws Exception {
        
        // Stream 'order-events' will be created if it doesn't exist
        from("redis-stream://order-events?group=inventory-service&events=OrderCreated,OrderCancelled")
            .log("Processing order event: ${body.event}")
            .to("bean:orderProcessor");
    }
}
```

**Result**: If `order-events` stream doesn't exist, it will be automatically created as an empty stream.

### Example 2: Disable Auto-Creation for Critical Streams
```java
from("redis-stream://critical-events?group=monitor&events=SystemFailure&autoCreateStreams=false")
    .log("Critical event: ${body}")
    .to("direct:alert-system");
```

**Result**: If `critical-events` doesn't exist, throws `RedisStreamException` to ensure you don't accidentally create critical streams.

### Example 3: Mixed Configuration
```properties
# Default behavior for all streams
camel.component.redis-stream.auto-create-streams=false
camel.component.redis-stream.auto-create-groups=true
```

```java
// Override default for specific route
from("redis-stream://user-events?group=email-service&events=UserRegistered&autoCreateStreams=true")
    .to("direct:send-welcome-email");
```

## Configuration Reference

| Property | Level | Default | Description |
|----------|-------|---------|-------------|
| `auto-create-streams` | Component/URI | `true` | Auto-create Redis streams if they don't exist |
| `auto-create-groups` | Component/URI | `true` | Auto-create consumer groups if they don't exist |

## Benefits

1. **✅ Development Friendly**: No need to manually create streams during development
2. **✅ Microservice Ready**: Services can start before dependent streams exist
3. **✅ Configurable**: Can be disabled for production safety
4. **✅ Clean Implementation**: Creates truly empty streams (no dummy messages left)
5. **✅ Logging**: Clear messages about stream creation activities

## Logging Output

When auto-creation happens, you'll see:
```
INFO  Stream 'my-stream' does not exist, creating it...
INFO  Successfully created empty stream: my-stream
INFO  Created consumer group: my-group
```

## Migration from 1.0.1-SNAPSHOT

### Update Dependency
```xml
<dependency>
    <groupId>tech.nelreina</groupId>
    <artifactId>camel-quarkus-redis-stream</artifactId>
    <version>1.0.2-SNAPSHOT</version>  <!-- Updated version -->
</dependency>
```

### Behavior Change
- **Before**: Consumer failed if stream didn't exist
- **After**: Consumer creates stream automatically (unless disabled)

### Configuration (Optional)
If you want the old behavior, disable auto-creation:
```properties
camel.component.redis-stream.auto-create-streams=false
```

## Implementation Notes

- **Thread-Safe**: Stream creation is synchronized per component
- **Atomic**: Uses Redis XADD + XDEL for atomic stream creation
- **Efficient**: Only checks/creates streams during consumer startup
- **Safe**: Doesn't interfere with existing streams or data

This feature makes the component much more developer-friendly while maintaining production safety through configuration! 🎉