# Aggressive Polling Configuration - Version 1.0.2-SNAPSHOT

## Updated Feature: Configurable Polling Interval

The Redis Stream consumer polling interval is now **configurable** and has been updated from 1 second to **100 milliseconds by default** for more aggressive message consumption.

## What Changed

### Before (1.0.1-SNAPSHOT)
- **Fixed polling interval**: 1000ms (1 second)
- **Not configurable**: Hardcoded in endpoint configuration

### After (1.0.2-SNAPSHOT)  
- **Default polling interval**: 100ms (10x more aggressive!)
- **Fully configurable**: Component, URI, and programmatic configuration
- **Better responsiveness**: Near real-time message processing

## Configuration Options

### 1. Component-Level Configuration (Recommended)
```properties
# Set aggressive polling for all Redis Stream consumers
camel.component.redis-stream.polling-interval=100

# Or even more aggressive (50ms)
camel.component.redis-stream.polling-interval=50

# Less aggressive for high-volume scenarios (500ms)
camel.component.redis-stream.polling-interval=500
```

### 2. URI-Level Configuration
```java
// Use default 100ms polling
from("redis-stream://order-events?group=inventory&events=OrderCreated")
    .log("Fast processing: ${body.event}");

// Custom polling interval (50ms for ultra-responsive)
from("redis-stream://critical-events?group=monitor&events=Alert&pollingInterval=50")
    .to("direct:immediate-response");

// Slower polling for batch processing (2 seconds)
from("redis-stream://batch-events?group=processor&events=BatchJob&pollingInterval=2000")
    .to("direct:batch-processor");
```

### 3. Programmatic Configuration
```java
@PostConstruct
public void configurePolling() {
    RedisStreamComponent component = camelContext.getComponent("redis-stream", RedisStreamComponent.class);
    
    // Set aggressive polling for all consumers
    component.setPollingInterval(100);  // 100ms
    
    // Or ultra-aggressive for real-time systems
    component.setPollingInterval(50);   // 50ms
}
```

## Performance Considerations

### Polling Interval Guidelines

| Use Case | Recommended Interval | Rationale |
|----------|---------------------|-----------|
| **Real-time alerts** | 50-100ms | Immediate response needed |
| **Order processing** | 100-200ms | Balance speed vs resources |
| **Batch processing** | 500-2000ms | Efficiency over speed |
| **Analytics/reporting** | 1000-5000ms | Background processing |

### Resource Impact

**More Aggressive Polling (50ms):**
- ✅ **Lower latency**: Messages processed almost immediately
- ✅ **Better user experience**: Near real-time responsiveness  
- ❌ **Higher CPU usage**: More frequent Redis calls
- ❌ **More network traffic**: Increased Redis connections

**Less Aggressive Polling (1000ms+):**
- ✅ **Lower resource usage**: Fewer Redis calls
- ✅ **Better for high volume**: Batch processing efficiency
- ❌ **Higher latency**: Up to polling interval delay
- ❌ **Less responsive**: Noticeable delays

## Usage Examples

### Example 1: Ultra-Responsive Order Processing
```java
@ApplicationScoped
public class OrderRoutes extends RouteBuilder {
    @Override
    public void configure() throws Exception {
        
        // Process orders with 50ms polling for immediate response
        from("redis-stream://order-events?group=payment-service&events=OrderCreated&pollingInterval=50")
            .log("Processing order within 50ms: ${body.aggregateId}")
            .to("bean:paymentProcessor?method=processPayment")
            .to("redis-stream://payment-events");
    }
}
```

### Example 2: Mixed Polling Strategies
```java
@Override
public void configure() throws Exception {
    
    // Critical alerts - ultra-fast polling
    from("redis-stream://alerts?group=monitoring&events=SystemFailure&pollingInterval=25")
        .to("direct:emergency-response");
    
    // Normal processing - default fast polling  
    from("redis-stream://user-events?group=email-service&events=UserRegistered")
        .to("direct:send-welcome-email");
    
    // Background analytics - slower polling
    from("redis-stream://analytics-events?group=reporting&events=UserActivity&pollingInterval=5000")
        .to("direct:update-analytics");
}
```

### Example 3: Environment-Specific Configuration
```properties
# Development - aggressive for testing
%dev.camel.component.redis-stream.polling-interval=100

# Production - balanced for efficiency  
%prod.camel.component.redis-stream.polling-interval=200

# Load testing - very aggressive
%test.camel.component.redis-stream.polling-interval=50
```

## Performance Monitoring

### Monitor Polling Effectiveness
```java
from("redis-stream://metrics?group=monitor&events=Performance&pollingInterval=100")
    .process(exchange -> {
        EventData eventData = exchange.getIn().getBody(EventData.class);
        long processTime = System.currentTimeMillis() - eventData.getTimestamp().toEpochMilli();
        
        // Log processing latency
        log.info("Message latency: {}ms", processTime);
        
        // Alert if latency too high
        if (processTime > 500) {
            exchange.setHeader("Alert", "High latency detected");
        }
    })
    .choice()
        .when(header("Alert").isNotNull())
            .to("direct:performance-alert")
    .end();
```

## Migration from Previous Versions

### Automatic Improvement
- **No code changes needed**: Default polling is now 10x faster (100ms vs 1000ms)
- **Better responsiveness**: Existing consumers will automatically poll more aggressively
- **Configurable**: Can be tuned per use case

### If You Need the Old Behavior
```properties
# Restore old 1-second polling
camel.component.redis-stream.polling-interval=1000
```

## Configuration Reference

| Property | Level | Default | Description |
|----------|-------|---------|-------------|
| `polling-interval` | Component/URI | `100` | Polling interval in milliseconds |

### Property Examples
```properties
# Component level
camel.component.redis-stream.polling-interval=100

# Environment specific
%prod.camel.component.redis-stream.polling-interval=200
%dev.camel.component.redis-stream.polling-interval=50
```

This aggressive polling configuration makes the Redis Stream component much more **responsive** while remaining **tunable** for different performance requirements! ⚡